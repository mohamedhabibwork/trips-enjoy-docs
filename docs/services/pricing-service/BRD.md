# Pricing Service — Business Requirements Document

## 1. Document Purpose

Read by product owners, finance, the ride/food engineering teams,
and the pricing-service team. It informs the design of the quote
algorithm, the capture of historical price snapshots, and the
operational SLOs. Implementation details in `SRS.md` and
`INTEGRATION.md`.

## 2. Business Context

Pricing is a **shared capability** across ride-hailing and food
delivery. Both products depend on a single, deterministic quote
algorithm that:

- Resolves business rules from configuration (no hard-coded fares).
- Applies surge per zone, with a city-level cap.
- Applies tax per jurisdiction (delegated to ``pricing-service` (tax)`).
- Optionally applies a promotion (delegated to ``pricing-service` (promotion)`).
- Captures a `config_snapshot` so historical orders remain
  reproducible when the rules change.

This service exists so that **price changes do not require a
redeploy**, and so that **the same algorithm serves ride and food**
without divergence.

## 3. Objectives

| ID | Objective | Metric |
|----|-----------|--------|
| BR--001 | Reach 99.95% availability on the quote path so quote latency never blocks a request. | Availability SLO; P99 quote latency < 200ms. |
| BR--002 | Ensure historical orders are reproducible from the captured `config_snapshot`. | 100% of quotes carry a snapshot. |
| BR--003 | Cap surge per city to a configurable maximum (default 3.0). | Surge multiplier ≤ cap, enforced in code. |
| BR--004 | Support ride-hailing and food-delivery products with the same engine. | One service, multiple product adapters. |
| BR--005 | Allow operators to change any pricing rule (base fare, per-km, per-min, fees, surge cap) without code change. | All rules in `configuration-service`. |

## 4. Stakeholders

| Stakeholder | Role | Interest |
|-------------|------|----------|
| Head of Pricing | owner | Algorithm, surge, fees |
| Finance | consumer | Reproducible quotes for audits |
| Ride engineering | consumer | Low-latency quote API |
| Food engineering | consumer | Same engine, different inputs |
| Customer Support | consumer | Quote breakdown for dispute resolution |
| Compliance | auditor | Snapshot reproducibility |

## 5. Actors / Personas

- **``trip-service` (ride-request)`** — calls `POST /v1/quotes` to produce a
  price quote for a customer requesting a ride.
- **``food-order-service` (cart)` / ``food-order-service` (checkout)`** — calls `POST /v1/quotes`
  to produce a quote for a food cart.
- **``trip-service` (ride-request)` (cancel) / `food-order-service` (cancel)**
  — call `POST /v1/quotes/cancellation-fee` when a customer
  cancels.
- **Operator (admin)** — edits pricing rules in
  `configuration-service` (this service picks them up).
- **Auditor** — inspects a captured snapshot via
  `POST /v1/quotes/snapshot/{snapshot_id}`.

## 6. Business Capabilities

- Ride quote (economy, premium, xl, …).
- Food quote (per branch with delivery fee).
- Surge multiplier resolution per zone.
- Distance + time rate computation.
- Tax computation per jurisdiction.
- Promotion discount (optional, validated by ``pricing-service` (promotion)`).
- Cancellation fee calculation (per stage of the ride / order).
- Waiting fee calculation.
- Tip suggestion (delegated to configuration, surfaced in quote).
- Currency-aware (multi-currency: EUR, USD, AED, SAR, …).
- `config_snapshot` capture on every quote.

## 7. Business Requirements

| ID | Requirement | Priority | Source |
|----|-------------|----------|--------|
| BR--010 | The service MUST read all pricing rules from `configuration-service`; no hard-coded values. | MUST | Engineering |
| BR--011 | Every quote MUST include a `config_snapshot` listing the rule key, value, and version used. | MUST | Finance / Compliance |
| BR--012 | The service MUST cap surge at the city-level maximum from `configuration-service`. | MUST | Operations |
| BR--013 | The service MUST round the total to integer minor units in the requested currency. | MUST | Finance |
| BR--014 | The service MUST return a quote in < 200ms P99. | MUST | Ride Engineering |
| BR--015 | The service MUST support multiple ride types (economy, premium, xl, …) and food delivery in the same engine. | MUST | Product |
| BR--016 | The service MUST validate an optional promotion code via ``pricing-service` (promotion)` and apply the discount as a separate line item. | MUST | Product |
| BR--017 | The service MUST compute tax via ``pricing-service` (tax)` for the delivery address's jurisdiction. | MUST | Finance |
| BR--018 | The service MUST emit `pricing.quote.created.v1` for every successful quote. | MUST | Analytics |
| BR--019 | The service MUST compute a cancellation fee per the documented policy. | MUST | Operations |
| BR--020 | The service MUST re-quote on `menu.item.price.changed.v1` so a stale cart never causes an over-charge. | MUST | Food Engineering |
| BR--021 | The service MUST support a "configurable" tip suggestion (default [10, 15, 20]). | SHOULD | Product |
| BR--022 | The service MUST support minimum-fare enforcement (a quote is never below the city minimum). | MUST | Operations |
| BR--023 | The service MUST support per-branch delivery fee overrides (food). | MUST | Food Engineering |
| BR--024 | The service MUST support per-zone surge step (the amount surge increments when supply is short). | SHOULD | Operations |
| BR--025 | The service MUST support a "scheduled ride" quote (locked fare for a future ride). | MUST | Product |
| BR--036 | The service MUST apply a small multiplicative rating-density surcharge when the pickup zone has a low average driver rating AND high recent-trip density; the surcharge composes with the existing zone surge and is capped by `pricing.surge.max_multiplier`. | MUST | Operations |
| BR--037 | The service MUST apply a tier-aware negative loyalty line (the "frequent-rider discount") when the customer has ≥ `pricing.loyalty.frequent_rider.min_trips_30d` trips in the pickup zone in the last 30 days; applied AFTER any promotion and BEFORE tax; capped so `total ≥ pricing.min_fare.{city_id}`. | MUST | Product |
| BR--038 | The service MUST support per-tenant, per-zone, per-city, and per-OD-pair (city-to-city) rule overrides sourced from `admin-service`'s geo-config CRUD API; precedence: most-specific match wins; matched ids+versions are captured in the quote's `config_snapshot.values`. | MUST | Operations / Finance |
| BR--039 | For cross-border trips (`pickup_city_id ≠ dropoff_city_id`), the service MUST call ``pricing-service` (tax)` twice — once with the pickup jurisdiction and once with the dropoff jurisdiction — producing two `lines[].code` (`tax_origin`, `tax_destination`); both `snapshot_id`s MUST be captured under `config_snapshot.values`. | MUST | Finance |
| BR--040 | Geo-config override records are append-only with version + rollback semantics; a rollback creates a new history row and a new head pointing at the prior version, mirroring `configuration-service`'s version/rollback pattern. | MUST | Operations |
| BR--041 | The pricing quote MUST continue to satisfy the cap rules of `pricing.surge.max_multiplier` (for surge composition with rating-density) and `pricing.min_fare.{city_id}` (for the loyalty discount and the combined-discount cap), regardless of any matched geo-config override. | MUST | Operations / Finance |

## 8. Business Rules

| ID | Rule | Notes |
|----|------|-------|
| BR--030 | The total = `base_fare + distance_rate*km + time_rate*min + surge*subtotal + fees - promotion - tip_credit` rounded to minor units. | Standard formula. |
| BR--031 | Tax is computed on the post-surge, post-promotion subtotal, per ``pricing-service` (tax)`. | Standard. |
| BR--032 | The minimum fare is enforced as `max(minimum_fare, total)`. | Standard. |
| BR--033 | Surge is computed as `1 + step * bucket_index`, capped at `max_multiplier`. | Standard. |
| BR--034 | Cancellation fee depends on the stage: before match = 0; after match before pickup = `pricing.cancellation.fee_amount`; at pickup = higher fee. | Standard. |
| BR--035 | A promotion is a separate line item with a `type` (amount, percent) and an `applies_to` (subtotal, delivery_fee, total). | Standard. |
| BR--036 | A scheduled ride quote is "frozen" at the time of creation; if the customer books later, the quote is the locked fare. | Standard. |

## 9. Assumptions

- Distance and time are provided by the caller (or fetched from
  `geolocation-service`); pricing-service does not compute a route.
- Surge is a multiplier in the range `[1.0, max]`; it is provided by
  ``geolocation-service` (zones)` via `zone.surge.updated.v1`.
- All monetary values are integer minor units.
- A quote has a TTL (default 5 minutes); after that it must be
  re-validated.

## 10. Constraints

- Pricing is a hot path on every ride request and food cart
  update; the P99 budget is 200ms.
- The service cannot write any persistent data; it is essentially
  stateless.
- A failed quote MUST NOT silently default to a value; the caller
  is told to retry.

## 11. Dependencies

| Dependency | Type | Notes |
|------------|------|-------|
| `configuration-service` | service | All pricing rules |
| ``pricing-service` (tax)` | service | Tax rules per jurisdiction |
| ``pricing-service` (promotion)` | service | Optional promotion validation |
| `geolocation-service` | service (optional) | Distance / ETA |
| ``geolocation-service` (zones)` | async (zone.surge.updated.v1) | Surge multiplier |
| ``restaurant-service` (menu)` | async (menu.item.price.changed.v1) | Food re-quote |
| PostgreSQL 19 | database | Cache only |
| Redis | cache | Business rules, tax rules |
| Kafka | broker | Consumes + produces |

## 12. Business Workflows

- Ride quote (workflow 1).
- Food cart quote (workflow 2).
- Scheduled ride quote (workflow 3).
- Cancellation fee (workflow 4).
- Re-quote on price change (workflow 5).
- Cross-border trip pricing (workflow 6).

## 13. Exception Workflows

- **`configuration-service` unreachable** — fall back to the
  in-memory cache; if cache is cold, return 503 `CIRCUIT_OPEN`.
- **``pricing-service` (tax)` unreachable** — fall back to the cached tax rules;
  if cache is cold, return 503.
- **Invalid promotion code** — return the quote without the
  discount; the caller may surface a UI error.
- **Quote TTL expired** — caller re-requests; pricing-service
  re-computes.

## 14. Success Criteria

- 99.95% quote availability in steady state.
- P99 quote latency < 200ms.
- 100% of quotes carry a `config_snapshot`.
- A historical order's `PriceQuote` is reproducible from the
  snapshot.
- Surge is never above the configured cap.

## 15. KPIs

| KPI | Target | Measurement |
|-----|--------|-------------|
| Quote availability | 99.95% | Synthetic probes per region |
| P99 quote latency | 200ms | RED metrics |
| Cache hit rate | ≥ 90% | Redis hit ratio |
| Surge cap adherence | 100% | Reconciliation job |
| Snapshot completeness | 100% | Schema check |
| Cancellation fee accuracy | 100% | Reconciliation job |

## 16. Acceptance Criteria

- A ride quote is produced in < 200ms P99 with the correct total
  and breakdown (rating-density, loyalty, and geo-config sub-pipelines
  add at most 30+50+5ms; cross-border trips add at most 50ms more).
- A food cart quote includes the per-branch delivery fee override.
- A cancellation fee is computed correctly per the documented
  policy.
- A scheduled ride quote is "frozen" at creation; subsequent
  re-quotes return the same total.
- A re-quote on `menu.item.price.changed.v1` updates the cart's
  total.
- The `config_snapshot` is sufficient to reconstruct the total
  bit-for-bit from the rules (including matched geo-config ids,
  rating-density signal, and loyalty snapshot).
- A rating-density surcharge is applied when both conditions hold
  (low avg_rating AND high density_pct); the composed surge is
  never above the cap.
- A loyalty discount is applied for qualifying customers in the
  pickup zone; the total is never below `min_fare.{city_id}`.
- A cross-border quote always carries both `tax_origin` and
  `tax_destination` lines (the destination may be 0 under
  `reverse_charge`); both `snapshot_id`s are captured.
- All 41 business requirements (BR--001..BR--005, BR--010..BR--025,
  BR--036..BR--041) are implemented with the 16 list as the
  acceptance contract; releases that introduce new requirements
  must update these criteria and the corresponding SRS FR ids.

---

## See also

### Sibling docs for this service

- [`README.md`](./README.md) — purpose, bounded context, responsibilities
- [`BRD.md`](./BRD.md) — business requirements
- [`SRS.md`](./SRS.md) — functional + non-functional requirements
- [`ERD.md`](./ERD.md) — data model (entities, relationships)
- [`INTEGRATION.md`](./INTEGRATION.md) — inter-service contracts (APIs, events, sagas)
- [`WORKFLOWS.md`](./WORKFLOWS.md) — operational workflows (happy paths, failure modes)
- [`TECH.md`](./TECH.md) — technology profile (runtime, libraries, data layer, admin endpoints, RBAC)

### Platform-wide

- [`../../shared/README.md`](../../shared/README.md) — `platform-spring-boot-starter` shared library (the single source of cross-cutting code for all Spring Boot services in the platform)
- [`../RECOMMENDATIONS.md`](../RECOMMENDATIONS.md) — platform-wide technology map (language, framework, version baseline, admin/RBAC pattern)
- [`../../README.md`](../../README.md) — services overview (the catalog of all 20 services)
- [`../../../main.md`](../../../main.md) — top-level platform specification (architecture, Keycloak, PostgreSQL 19, messaging, observability baseline)

