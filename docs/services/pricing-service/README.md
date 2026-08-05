# Pricing Service

## 1. Purpose

`pricing-service` is the platform's **pure computational engine** for
price quotes. It computes a `PriceQuote` for a ride or a food order
given an evaluation context (city, ride type, distance, time,
demand, product, tax rules, optional promotion). It is shared by the
ride-hailing and food-delivery products. The service captures a
**configuration snapshot** with every quote so historical orders
remain reproducible even when business rules change.

## 2. Bounded Context

**Bounded context**: Pricing engine. In scope:

- Reading business-rule values from `configuration-service`.
- Reading tax rules from ``pricing-service` (tax)`.
- Resolving a `PriceQuote` (base fare + distance + time + surge +
  fees + tax - promotion discount).
- Capturing the configuration snapshot used in the quote.
- Surfacing cancellation fees and waiting fees for a trip in flight.
- Emitting `pricing.quote.created.v1` for analytics.
- **Pricing-time rating-density enrichment** (low driver rating ×
  dense recent trips in a nearby zone → small multiplicative surge
  surcharge, composed with the existing zone surge and capped by
  `pricing.surge.max_multiplier`).
- **Frequent-rider loyalty discount** (customer has ≥ N completed
  trips in the pickup zone in the last 30 days → small negative line,
  applied AFTER ``pricing-service` (promotion)` validation, BEFORE ``pricing-service` (tax)`
  recalculation, capped so `total ≥ pricing.min_fare.{city_id}`).
- **Per-location and city-to-city pricing overrides** sourced from
  the new `admin-service` geo-config API; precedence: most-specific
  match wins (OD-pair > exact location > zone > city > tenant > global).
  Always elevated from the existing SHOULD (FR--025) to MUST.

Out of scope:

- Persisting price quotes for a long period (each consumer may
  persist; pricing-service itself keeps a short cache for idempotency).
- Tax rate storage (owned by ``pricing-service` (tax)`).
- Promotion storage (owned by ``pricing-service` (promotion)`).
- Customer's actual payment (owned by `payment-service`).
- Driver / courier earnings accrual — ``payment-service` (driver earnings)` /
  ``payment-service` (courier earnings)`.
- Per-driver ratings and zone-aggregated ratings — ``trip-service` / `food-order-service` / `search-service` (review projections)`
  (this service only consumes the aggregate).
- Per-customer trip history and frequent-zone aggregation —
  ``pricing-service` (loyalty rules) / `customer-service` (account)` (this service only consumes the aggregate).
- Geo-config rule storage and admin CRUD — `admin-service`
  (this service only consumes the published config via event).

## 3. Responsibilities

- Resolve business-rule values from `configuration-service` (base
  fare, per-km, per-min, surge rules, fees).
- Resolve geo-config overrides from `admin-service` (per the new
  `pricing.geo_config.updated.v1` event; in-memory hash, O(1) lookup
  on the quote path).
- Compute a `PriceQuote` deterministically from a `QuoteRequest`.
- Apply surge multiplier per zone, composed (multiplicative) with
  the rating-density surcharge when the zone qualifies (B1, SRS
  FR--026..FR--030); the composed value is capped by
  `pricing.surge.max_multiplier` and emitted via
  `pricing.rating_density.applied.v1` whenever the surcharge
  contributes non-zero.
- Apply tax rules per jurisdiction (after all pricing-side
  adjustments).
- Apply an optional promotion discount (validated by
  ``pricing-service` (promotion)`), then the loyalty frequent-rider discount
  (validated by ``pricing-service` (loyalty rules) / `customer-service` (account)`); both compose and are captured in
  the snapshot (B2, SRS FR--031..FR--035).
- Apply any matched geo-config override (per-tenant, per-zone,
  per-city, or OD-pair; most-specific wins — B3, SRS FR--036..FR--041).
- Capture a `config_snapshot` with the version of every key used,
  including matched geo-config ids, rating-density snapshot, and
  loyalty snapshot.
- Emit `pricing.quote.created.v1`, `pricing.geo_overrides.matched.v1`,
  `pricing.rating_density.applied.v1`, `pricing.loyalty_discount.applied.v1`
  for analytics.
- Re-quote on `menu.item.price.changed.v1` for food orders
  (read-only consumer; the cart/checkout services call us again).
- Serve cancellation fee calculation for ride / food cancellation.
- Serve the cross-border trip story: when `pickup_city_id ≠
  dropoff_city_id`, call ``pricing-service` (tax)` twice (origin + destination),
  produce two `lines[].code` (`tax_origin` and `tax_destination`),
  and capture both rule snapshots in `config_snapshot`.

## 4. Explicitly NOT Owned

- **Configuration data** — `configuration-service`.
- **Geo-config rule storage and CRUD** — `admin-service`
  (this service only consumes `pricing.geo_config.updated.v1`).
- **Tax rules** — ``pricing-service` (tax)`.
- **Promotion storage** — ``pricing-service` (promotion)`.
- **Customer credit / wallet** — ``payment-service` (wallet)`.
- **Driver / courier earnings** — ``payment-service` (driver earnings)` /
  ``payment-service` (courier earnings)`.
- **Per-driver rating storage** — ``trip-service` / `food-order-service` / `search-service` (review projections)`.
- **Customer loyalty points balance / tier** — ``pricing-service` (loyalty rules) / `customer-service` (account)`.

## 5. Actors

| Actor | Type | Access |
|-------|------|--------|
| ``trip-service` (ride-request)` | system | inbound quote requests |
| ``food-order-service` (cart)` | system | inbound quote requests |
| ``food-order-service` (checkout)` | system | inbound quote requests |
| ``trip-service` (ride-request)` (cancel) | system | inbound cancellation fee requests |
| `food-order-service` (cancel) | system | inbound cancellation fee requests |
| ``reporting-service` (data lake)` | system | consumer of `pricing.quote.created.v1`, `pricing.geo_overrides.matched.v1`, `pricing.rating_density.applied.v1`, `pricing.loyalty_discount.applied.v1` |
| `reporting-service` | system | consumer of `pricing.rating_density.applied.v1`, `pricing.loyalty_discount.applied.v1` |
| ``trip-service` / `food-order-service` / `search-service` (review projections)` | system | zone-aggregated driver rating for the rating-density sub-pipeline |
| ``pricing-service` (loyalty rules) / `customer-service` (account)` | system | frequent-zone aggregation for the loyalty sub-pipeline |
| `admin-service` | system | CRUDs and publishes pricing geo-config |

## 6. Dependencies

### Synchronous (REST)

- `configuration-service` — read business rules
  (SLO 99.95%; circuit breaker: yes; cached).
- ``pricing-service` (tax)` — read tax rules
  (SLO 99.9%; circuit breaker: yes; cached); up to two synchronous
  calls per cross-border trip (origin + destination jurisdictions).
- ``pricing-service` (promotion)` — validate promotion code (optional, only if
  the request includes one; SLO 99.9%; circuit breaker: yes).
- `geolocation-service` — read ETA / distance (cached, optional; the
  caller may pass distance/duration directly).
- `admin-service` — fetch the current geo-config by id
  (`GET /v1/admin/pricing/geo-config/{id}`, optional; the live path
  is the async event). Class **DEGRADABLE**; circuit breaker: yes.
- ``trip-service` / `food-order-service` / `search-service` (review projections)` — zone-aggregated driver rating
  (`GET /v1/zones/{zone_id}/driver-rating?window_minutes=15`).
  Class **DEGRADABLE**; circuit breaker: yes; in-memory cache
  `pricing.rating_density_cache` is the fallback when the live call
  fails.
- ``pricing-service` (loyalty rules) / `customer-service` (account)` — frequent-zone aggregation
  (`GET /v1/accounts/{customer_id}/frequent-zones?window_days=30`).
  Class **DEGRADABLE**; circuit breaker: yes; in-memory cache
  `pricing.loyalty_frequent_cache` is the fallback when the live call
  fails.

### Asynchronous (events consumed)

- `configuration.updated.v1` (from `configuration-service`) — reload
  in-memory cache.
- `zone.surge.updated.v1` (from ``geolocation-service` (zones)`) — refresh surge
  multipliers.
- `menu.item.price.changed.v1` (from ``restaurant-service` (menu)`) — invalidate
  cached quotes for the affected branch (food order re-quote).
- `tax.calculated.v1` (from ``pricing-service` (tax)`) — refresh tax cache.
- `pricing.geo_config.updated.v1` (from `admin-service`) — invalidate
  in-memory geo-config hash; reload on next quote.
- `review.zone_aggregated.v1` (from ``trip-service` / `food-order-service` / `search-service` (review projections)`) — warm
  `pricing.rating_density_cache` (B1's batch / offline path; the
  live path is the synchronous REST call above).
- `loyalty.frequent_zone.aggregated.v1` (from ``pricing-service` (loyalty rules) / `customer-service` (account)`) —
  warm `pricing.loyalty_frequent_cache` (B2's batch / offline path).

## 7. Technology Assumptions

- Runtime: Go 1.22 (CPU-bound calculation; predictable latency).
- Database: PostgreSQL 18 (per-service schema `pricing`; cache tables
  only — no domain state).
- Cache: Redis cluster for business rules and tax rules.
- Event broker: Kafka (consumes + produces).

## 8. Database Ownership

- Schema: `pricing` (cache tables only; no source-of-truth domain
  rows).
- Migrations: `services/pricing-service/migrations/`.
- Soft delete: no (cache is TTL-bounded).
- Partitioning: no.

## 9. API Overview

| Method | URI | Auth | Purpose |
|--------|-----|------|---------|
| POST | `/v1/quotes` | bearer (service) | compute a quote |
| POST | `/v1/quotes/{quote_id}/re-quote` | bearer (service) | re-evaluate a prior quote |
| POST | `/v1/quotes/cancellation-fee` | bearer (service) | calculate cancellation fee |
| POST | `/v1/quotes/waiting-fee` | bearer (service) | calculate waiting fee |
| GET | `/v1/quotes/{quote_id}` | bearer (service) | read a prior quote (cache) |
| POST | `/v1/quotes/snapshot/{snapshot_id}` | bearer (admin) | inspect a captured snapshot |

(Full contracts in `INTEGRATION.md`.)

## 10. Events Produced

| Event | Trigger | Consumers |
|-------|---------|-----------|
| `pricing.quote.created.v1` | every successful quote | ``reporting-service` (data lake)` |
| `pricing.quote.expired.v1` | a quote's TTL elapsed | ``reporting-service` (data lake)` |
| `pricing.geo_overrides.matched.v1` | a quote matched ≥ 1 geo-config override | ``reporting-service` (data lake)`, `reporting-service` |
| `pricing.rating_density.applied.v1` | rating-density surcharge composed into the surge line | ``reporting-service` (data lake)`, `reporting-service` |
| `pricing.loyalty_discount.applied.v1` | frequent-rider loyalty discount applied | ``reporting-service` (data lake)`, `reporting-service` |

## 11. Events Consumed

| Event | Producer | Reason | Handler |
|-------|----------|--------|---------|
| `configuration.updated.v1` | `configuration-service` | reload in-memory cache | cache invalidation + reload |
| `zone.surge.updated.v1` | ``geolocation-service` (zones)` | refresh surge | cache invalidation |
| `menu.item.price.changed.v1` | ``restaurant-service` (menu)` | invalidate cached food quotes | cache invalidation |
| `tax.calculated.v1` | ``pricing-service` (tax)` | refresh tax cache | cache invalidation |
| `pricing.geo_config.updated.v1` | `admin-service` | geo-config override changed | invalidate `pricing.rule_bindings` in-memory hash; reload on next quote |
| `review.zone_aggregated.v1` | ``trip-service` / `food-order-service` / `search-service` (review projections)` | refresh rating-density cache | warm `pricing.rating_density_cache` |
| `loyalty.frequent_zone.aggregated.v1` | ``pricing-service` (loyalty rules) / `customer-service` (account)` | refresh loyalty cache | warm `pricing.loyalty_frequent_cache` |

## 12. External Integrations

- **HashiCorp Vault** — DB credentials at
  `secret/pricing-service/<env>`.

## 13. Configuration

Operational parameters (build-time only):

| Key | Type | Source | Notes |
|-----|------|--------|-------|
| `DB_URL` | string | env | Per-env database URL |
| `KAFKA_BROKERS` | string | env | |
| `REDIS_URL` | string | env | |
| `QUOTE_TTL_SECONDS` | int | env | 300 (default) |
| `CACHE_TTL_SECONDS` | int | env | 300 (default) |
| `pricing.rating_density.enabled` | bool | configuration-service | true (default) |
| `pricing.rating_density.window_minutes` | int | configuration-service | 15 (default) |
| `pricing.rating_density.density_threshold_pct` | int | configuration-service | 75 (default) |
| `pricing.rating_density.min_avg_rating` | float | configuration-service | 4.2 (default) |
| `pricing.rating_density.max_multiplier_pct` | int | configuration-service | 25 (default) |
| `pricing.loyalty.frequent_rider.enabled` | bool | configuration-service | true (default) |
| `pricing.loyalty.frequent_rider.min_trips_30d` | int | configuration-service | 8 (default) |
| `pricing.loyalty.frequent_rider.max_discount_pct` | int | configuration-service | 10 (default) |
| `pricing.loyalty.frequent_rider.tiers.silver.multiplier` | float | configuration-service | 1.0 (default) |
| `pricing.loyalty.frequent_rider.tiers.gold.multiplier` | float | configuration-service | 1.25 (default) |
| `pricing.loyalty.frequent_rider.tiers.platinum.multiplier` | float | configuration-service | 1.5 (default) |
| `pricing.geo_overrides.cache_ttl_seconds` | int | configuration-service | 600 (default) |
| `pricing.geo_overrides.refresh_window_seconds` | int | configuration-service | 2 (default) |
| `pricing.geo_overrides.kind` | enum | configuration-service | `LOCATION_OVERRIDE` (default) — values: `LOCATION_OVERRIDE` \| `OD_CORRIDOR` |

The business rule values are loaded from `configuration-service`.
The geo-config rule records are loaded from `admin-service` via the
`pricing.geo_config.updated.v1` event.

## 14. Security

- AuthN: service-account JWT (RS256, Keycloak). The service is
  internal; the gateway routes only internal callers.
- AuthZ: scope `pricing.quote` for reads; `pricing.admin` for
  admin endpoints.
- Secrets: Vault paths.
- PII: a quote may include the customer's `customer_id` (a UUID,
  not PII by itself); the service does not store customer contact
  info.
- Money: integer minor units with `currency` field; no floats.

## 15. Observability

- Logs: structured JSON to stdout; standard fields + `quote_id`,
  `city`, `ride_type`, `surge_multiplier`, `total_minor`,
  `rating_density_applied`, `loyalty_discount_applied`,
  `geo_config_matched_ids[]`.
- Metrics: RED per route + `pricing_quote_seconds`,
  `pricing_quote_cache_hit_ratio{type}`,
  `pricing_quote_total{currency,ride_type}`,
  `pricing_cancellation_fee_seconds`,
  `pricing_rating_density_applied_pct_total{zone_id,surge_bucket}`
  (gauge: avg applied multiplier pct over the rate-density lookups),
  `pricing_loyalty_discount_applied_pct_total{customer_tier}`,
  `pricing_geo_overrides_matched_total{rule_kind,scope}`
  (counter: # of times each geo-config matched),
  `pricing_quote_with_cross_border_tax_total{city_from,city_to}`.
- Traces: OpenTelemetry; one root span per quote; child spans for
  config, tax, promotion, surge, rating-density, loyalty, geo-config,
  distance.
- Health: `/health`, `/ready`, `/started`.

## 16. Scalability

- Replicas: default 12; HPA on CPU > 60% and
  `pricing_quote_seconds` P99 > 200ms.
- Hot path: `POST /v1/quotes` (read from in-memory cache; pure
  compute).
- In-memory hash for `pricing.rule_bindings` (per-tenant, per-zone,
  per-city, OD-pair) is O(1) on the quote path; P95 ≤ 5ms.
- The rating-density sub-pipeline adds 1 cache lookup or 1 REST call;
  P95 ≤ 30ms when cached, ≤ 100ms when not.
- The loyalty-frequent-rider sub-pipeline adds 1 cache lookup or
  1 REST call; P95 ≤ 50ms.
- Cross-border trips add 1 additional ``pricing-service` (tax)` call (origin +
  destination); P95 budget increases by ≤ 50ms per extra call.

## 17. Local Development

```bash
docker compose -f deploy/compose/pricing-service.yml up -d db
make -C services/pricing-service migrate-up
go run services/pricing-service/cmd/server
```

## 18. Deployment

- Image: `ghcr.io/trips-enjoy-platform/pricing-service:<sha>`.
- Replicas: 12 in production.
- Resource limits: see `architecture/DEPLOYMENT_ARCHITECTURE.md`.
- Migrations: `pre-upgrade` Job (no schema changes typical).
- Rollback: re-deploy prior image; the service is stateless.

## 19. Disaster Recovery

- RPO: 5 minutes (PITR).
- RTO: 30 minutes (warm standby).
- The service is stateless beyond cache; recovery is from the
  latest event stream.

## 20. Accounting impact

`pricing-service` is the **integration point between
``pricing-service` (tax)` (recognition) and `payment-service` (collection)**. On
every quote it calls `POST /v1/tax/calculate`, captures the snapshot
returned (which freezes the rule set used), and integrates the
`tax_minor` into the `total_minor` returned to the caller. That
amount flows as a single `amount_minor` to `payment-service` at
capture — the ledger does not see tax as a separate line.
For cross-border trips (`pickup_city_id ≠ dropoff_city_id`),
``pricing-service` (tax)` is called twice (origin + destination) and the result
is exposed as two `lines[].code` (`tax_origin`, `tax_destination`),
each capturing its own `snapshot_id` in `config_snapshot.values`.

- **What money facts it owns:** quotes, fare / order breakdowns,
  applied promotions, applied loyalty, snapshot_id of the tax rule,
  matched geo-config override ids+versions, rating-density snapshot,
  loyalty discount snapshot.
- **Postings:** none directly. Pricing is read-only against
  ``pricing-service` (tax)`, ``pricing-service` (promotion)`, ``pricing-service` (loyalty rules) / `customer-service` (account)`,
  ``trip-service` / `food-order-service` / `search-service` (review projections)`, and `admin-service`; tax collection is
  recorded by `ledger-service` on `payment.captured.v1`.
- **Snapshot integrity:** the `snapshot_id` is persisted with the
  quote so that audit and dispute resolution can reconstruct the
  exact rule set used at quote time. Tax-rule changes after the
  snapshot do not retroactively alter past quotes. The same
  guarantee holds for `pricing.rating_density.applied.v1`,
  `pricing.loyalty_discount.applied.v1`, and the matched geo-config
  ids each quote references.
- **Rating-density and loyalty adjustments** are NOT separate
  expense entries — they affect `revenue` and are captured inside
  the quote's `lines[]` and `config_snapshot`. The funds flow as
  part of the standard single `amount_minor` to
  `payment-service` at capture; see
  [`../../workflows/ACCOUNTING_WORKFLOWS.md`](../../workflows/ACCOUNTING_WORKFLOWS.md)
  §"Rating-Density Surge Surcharge + Loyalty Discount".
- **Reconciliation:** indirect — quote-to-capture reconciliation
  is performed by `reporting-service` to detect dropped quotes.
- **Human operator path:** admin overrides on per-tenant / per-zone
  pricing via `pricing.admin` role; geo-config rules are CRUDed
  through `admin-service`'s `/v1/admin/pricing/geo-config` API
  (this service only consumes the published event). Rule changes
  emit both `configuration.updated.v1` (for plain numeric rules) and
  `pricing.geo_config.updated.v1` (for geo-config records).
- **Rewards interaction:** this service does not own reward
  accrual. Per-trip / hourly / daily guaranteed rewards for driver
  and user are evaluated by `trip-service` on `state=completed` and
  flow through `trip.reward.granted.v1` to ``payment-service` (driver earnings)`
  and ``payment-service` (wallet)`. See
  [`../../workflows/ACCOUNTING_WORKFLOWS.md`](../../workflows/ACCOUNTING_WORKFLOWS.md)
  §"Guaranteed Rewards — Driver Top-Up + Customer Credit".

See [`../../workflows/ACCOUNTING_WORKFLOWS.md`](../../workflows/ACCOUNTING_WORKFLOWS.md)
for the cross-service view.

## 21. References

- Architecture: `docs/architecture/CONFIGURATION_ARCHITECTURE.md`,
  `docs/architecture/CONSISTENCY_STRATEGY.md`,
  `docs/architecture/EVENT_ARCHITECTURE.md` (event naming,
  outbox/inbox, DLQ semantics).
- Adjacent services: `docs/services/`trip-service` / `food-order-service` / `search-service` (review projections)/README.md`
  (zone-aggregated driver rating),
  `docs/services/`pricing-service` (loyalty rules) / `customer-service` (account)/README.md` (frequent-zone
  aggregation), `docs/services/admin-service/README.md` (geo-config
  CRUD), `docs/services/`geolocation-service` (zones)/README.md` (zone geometry and
  surge), `docs/services/`pricing-service` (tax)/README.md` (tax rules and
  cross-border), `docs/services/`payment-service` (wallet)/README.md` and
  `docs/services/`payment-service` (driver earnings)/README.md` (downstream
  reward consumers).
- Workflows: `docs/workflows/RIDE_WORKFLOWS.md`,
  `docs/workflows/FOOD_ORDER_WORKFLOWS.md`,
  `docs/workflows/PAYMENT_WORKFLOWS.md`,
  `docs/workflows/ACCOUNTING_WORKFLOWS.md`.


---

## Appendix A — Removed predecessor capability

The capability that used to live in ``pricing-service` (tax)` (tax jurisdiction
rules), ``pricing-service` (promotion)` (coupons, campaigns, redemption rules),
and the **rules** slice of ``pricing-service` (loyalty rules) / `customer-service` (account)` (earn / burn / tier math)
is now absorbed into this service. The canonical source is
[`../../MIGRATION_HUB.md`](../../MIGRATION_HUB.md) §3.13 (tax),
§3.14 (promotion), §3.15 (loyalty-rules). The **loyalty account**
(per-user balance + history) is owned by `customer-service`.

### A.1 Bounded context (post-merger)

Pricing engine + tax jurisdiction rules + promotion / coupon rules
+ loyalty pricing rules. The service is the **only** writer of the
`pricing` schema. Out of scope: customer wallet (owned by
`payment-service`), loyalty account balance (owned by
`customer-service`).

### A.2 Absorbed responsibilities (from `pricing-service` (tax))

- Tax jurisdiction rules, product tax codes, exemptions.
- Cross-border trip handling (origin + destination tax lines).
- Emit `tax.calculated.v1`.

### A.3 Absorbed responsibilities (from `pricing-service` (promotion))

- Coupons, campaigns, redemption rules.
- Redemption history.
- Segment-targeted promotions (driven by `customer.segment.changed.v1`).
- Emit `promotion.redeemed.v1`, `promotion.created.v1`,
  `promotion.disabled.v1`.

### A.4 Absorbed responsibilities (loyalty-rules)

- Loyalty tiers, earn rules, burn rules, promo-bindings.
- Source of truth for loyalty math.
- Emit `loyalty.tier.changed.v1` and consume
  `customer.loyalty_account.changed.v1` from `customer-service`.

### A.5 Absorbed REST endpoints (highlights)

| Method | URI | Auth | Purpose |
|--------|-----|------|---------|
| POST | `/v1/tax/calculate` | bearer (service) | compute tax |
| GET  | `/v1/tax/rules?jurisdiction=…` | bearer (admin) | read rules |
| POST | `/v1/tax/rules` | bearer (admin) | upsert rule |
| POST | `/v1/promotions/coupons` | bearer (admin) | create |
| GET  | `/v1/promotions/coupons/{code}` | bearer (customer) | read |
| POST | `/v1/promotions/redeem` | bearer (customer) | redeem |
| GET  | `/v1/promotions/metrics` | bearer (admin) | metrics |
| GET  | `/v1/loyalty/tiers` | bearer | read tiers |
| POST | `/v1/loyalty/tiers` | bearer (admin) | upsert tier |
| GET  | `/v1/loyalty/earn-rules` | bearer | read earn rules |
| POST | `/v1/loyalty/earn-rules` | bearer (admin) | upsert earn rules |

### A.6 Compatibility window

For at least six calendar months from 2026-08-05:

- `tax.calculated.v1`, `promotion.*.v1`, `loyalty.tier.changed.v1`
  are published under the same topic names and schema versions by
  this service.
- `/v1/tax/*`, `/v1/promotions/*`, `/v1/loyalty/*` continue to be
  served from this service.
- Old schema names `tax.*`, `promotion.*`, `loyalty.*` (rules
  namespace) remain readable as views in the `pricing` schema.

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

### Related services

- **Depends on**: [`configuration-service`](../configuration-service/README.md), [`customer-service`](../customer-service/README.md), [`food-order-service`](../food-order-service/README.md), [`geolocation-service`](../geolocation-service/README.md), [`payment-service`](../payment-service/README.md), [`trip-service`](../trip-service/README.md)
- **Depended on by**: [`admin-service`](../admin-service/README.md), [`customer-service`](../customer-service/README.md), [`driver-service`](../driver-service/README.md), [`food-order-service`](../food-order-service/README.md), [`payment-service`](../payment-service/README.md), [`reporting-service`](../reporting-service/README.md), [`restaurant-service`](../restaurant-service/README.md), [`trip-service`](../trip-service/README.md)

> Full dependency map in [`../README.md`](../README.md) and [`../../architecture/MICROSERVICES_MAP.md`](../../architecture/MICROSERVICES_MAP.md).

### Platform-wide

- [`../../shared/README.md`](../../shared/README.md) — `platform-spring-boot-starter` shared library (the single source of cross-cutting code for all Spring Boot services in the platform)
- [`../../shared/PLATFORM_BASELINE.md`](../../shared/PLATFORM_BASELINE.md) — single source for PostgreSQL 18, Kafka, Keycloak, Redis, OpenTelemetry, Vault, deployment, DR (do not restate these in this README)
- [`../../architecture/SERVICE_ISOLATION.md`](../../architecture/SERVICE_ISOLATION.md) — **how this service behaves when a downstream is down** (timeout / bulkhead / circuit / retry / fallback, by class: CRITICAL / DEGRADABLE / BEST-EFFORT)
- [`../../architecture/DOWNSTREAM_ERROR_CATALOG.md`](../../architecture/DOWNSTREAM_ERROR_CATALOG.md) — **canonical error-code catalog + propagation rules** (the `downstream` block, forward/translate/degrade/reject)
- [`../RECOMMENDATIONS.md`](../RECOMMENDATIONS.md) — platform-wide technology map (language, framework, version baseline, admin/RBAC pattern)
- [`../../README.md`](../../README.md) — services overview (the catalog of all 58 services)
- [`../../../main.md`](../../../main.md) — top-level platform specification (architecture, Keycloak, PostgreSQL 18, messaging, observability baseline)
- [`../../shared/OSS_DEPENDENCIES.md`](../../shared/OSS_DEPENDENCIES.md) — **open-source dependencies & license attribution** (platform-wide OSS projects + per-language OSS libraries with SPDX IDs; per-service bundle index; license compatibility matrix)

### Workflows this service participates in

- [`../../workflows/RIDE_WORKFLOWS.md`](../../workflows/RIDE_WORKFLOWS.md) — end-to-end ride flows
- [`../../workflows/FOOD_ORDER_WORKFLOWS.md`](../../workflows/FOOD_ORDER_WORKFLOWS.md) — end-to-end order/delivery flows
- [`../../workflows/ACCOUNTING_WORKFLOWS.md`](../../workflows/ACCOUNTING_WORKFLOWS.md) — accounting view (quote / tax snapshot / capture integration)
