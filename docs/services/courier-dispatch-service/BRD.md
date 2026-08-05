# courier-dispatch-service — Business Requirements Document

## 1. Document Purpose

This BRD is the source of truth for **what** the courier-dispatch
service does for the business. It is read by product, operations,
support, and engineering. It informs the SRS (how) and acceptance
criteria. It is updated whenever the business goal or rules change.

## 2. Business Context

Food delivery is fundamentally a time-sensitive marketplace. Once a
restaurant marks an order `ready`, the value of the order decays
rapidly — the food gets cold, the customer gets impatient, and the
courier who could have taken it is now moving away. The
`courier-dispatch-service` exists to compress the time between
"ready" and "courier assigned" to under one minute (p95).

The matching problem is a high-frequency, geographically-constrained,
multi-constraint optimisation. The same courier should not be offered
two orders at once. Couriers should be matched to orders they can
physically reach in a reasonable time, that are heading in a direction
that makes sense given their next likely move, and that pay enough
to be worth the trip. The service optimises for *assignment success
rate* and *assignment latency*, with secondary goals of fairness
across couriers and minimisation of deadhead miles.

## 3. Objectives

| ID | Objective | Metric |
|----|-----------|--------|
| BR--001 | Reduce median time from `food.order.ready` to courier assignment | p50 ≤ 45s, p95 ≤ 90s |
| BR--002 | Maximise the share of orders matched to a courier on the first offer | first-offer acceptance ≥ 80% |
| BR--003 | Minimise the share of orders that fail to find a courier | `no_courier` rate ≤ 2% (per zone-hour) |
| BR--004 | Treat couriers fairly — distribute offers across the available pool | Gini coefficient of offers-per-courier ≤ 0.25 per 24h |
| BR--005 | Make the dispatch decision auditable and replayable | 100% of assignments persisted in the assignment ledger within 1s of commit |
| BR--006 | Allow operational intervention (force reassign) | admin endpoint available with audit trail |

## 4. Stakeholders

| Stakeholder | Role | Interest |
|-------------|------|----------|
| Product (Food) | owns the food marketplace | assignment rate; customer satisfaction |
| Operations | city ops | manual intervention; surge control |
| Couriers (represented by Trust & Safety) | end users of the offer flow | fair distribution; offer clarity |
| Customers | downstream consumer | fast delivery; accurate ETA |
| Finance | owns the cost side | deadhead miles; cancellation cost |
| Engineering (Courier Domain) | implements | reliability; observability |

## 5. Actors / Personas

- **Courier** — receives offers on the mobile app, accepts or rejects.
  Their behaviour is the primary input to the matching algorithm.
- **Customer** — does not interact with this service directly, but
  the assignment outcome is visible to them ("Your courier is X, ETA
  Y minutes").
- **Restaurant operator** — does not interact with this service
  directly, but the readiness of an order is the trigger.
- **City operator** — uses the admin console to monitor dispatch
  health, force-reassign, or adjust parameters in their city.
- **Support agent** — uses the support console to investigate failed
  dispatches and, when appropriate, force a reassignment.

## 6. Business Capabilities

- Match a `food.order.ready.v1` event to a courier and emit
  `delivery.courier.assigned.v1`.
- Push a delivery offer to a single courier; record the offer attempt.
- Accept an offer (courier action) within an offer window.
- Reject an offer (courier action) and re-offer to the next candidate.
- Cancel a committed assignment (compensating action) and re-dispatch.
- Batch multiple orders from the same restaurant to a single courier
  (when allowed).
- Surface a `no_courier` event when the offer window is exhausted.
- Provide a city-level operational view (current pool, recent
  attempts, failure rate).

## 7. Business Requirements

| ID | Requirement | Priority | Source |
|----|-------------|----------|--------|
| BR--010 | The service MUST produce exactly one `delivery.courier.assigned.v1` per successful match. | MUST | Food product team |
| BR--011 | The service MUST emit `delivery.dispatch.no_courier.v1` when no courier accepts within the offer window. | MUST | Food product team |
| BR--012 | The service MUST persist every offer attempt in the assignment ledger, including the courier id, the offer timestamp, and the outcome. | MUST | Audit / Legal |
| BR--013 | The service MUST NOT offer the same delivery to two couriers at the same time. | MUST | Operations |
| BR--014 | The service MUST NOT offer a delivery to a courier who is currently assigned to another active delivery. | MUST | Operations |
| BR--015 | The service MUST support a batched offer for multiple orders from the same restaurant, up to `batch_max_size`. | SHOULD | Operations (efficiency) |
| BR--016 | The service MUST re-dispatch automatically when a courier cancels before pickup. | MUST | Food workflows |
| BR--017 | The service MUST support a manual "force reassign" admin action. | MUST | Operations |
| BR--018 | The service MUST honour city-level surge / restricted zones when scoring couriers. | SHOULD | Surge product |
| BR--019 | The service MUST be configurable per city (offer window, max attempts). | MUST | Operations |
| BR--020 | The service MUST publish operational metrics (pool size, offer latency, success rate) every 10 seconds. | SHOULD | Observability |

## 8. Business Rules

| ID | Rule | Notes |
|----|------|-------|
| BR--030 | An offer expires after `offer_window_seconds` (default 30). | Per-city configurable. |
| BR--031 | If a courier rejects an offer, the next-best candidate is offered immediately (no delay). | |
| BR--032 | A courier can hold at most one active offer at a time. | Enforced at offer time. |
| BR--033 | A courier can hold at most one active delivery at a time. | Enforced at offer time. |
| BR--034 | Batched offers count as a single assignment for the courier; each child order has its own delivery. | |
| BR--035 | The assignment ledger is append-only; cancellations are recorded, not erased. | |
| BR--036 | The service is silent (does not offer) for zones where the courier pool is below `min_pool_size`. | Surfaces `no_courier` quickly instead of offering to a thin pool. |

## 9. Assumptions

- Couriers are online in the same city/zone as the order.
- A courier's `last_known_location` is no older than 60 seconds.
- The `food-order-service` emits `food.order.ready.v1` exactly once
  per order (consumer is responsible for dedup).
- The `courier-tracking-service` provides a curated location stream
  at 1 Hz per courier (sub-events are dropped).
- Map / routing is provided by `eta-routing-service` and
  `geolocation-service`.

## 10. Constraints

- The service MUST be deployed in a Tier-1 SLO (99.95% availability).
- All time-sensitive operations MUST be in p99 ≤ 500ms.
- The service MUST NOT store courier PII (name, phone, photo).
- The service MUST run in the region of the order (no cross-region
  matching in the same request).

## 11. Dependencies

| Dependency | Type | Notes |
|------------|------|-------|
| `food-order-service` | service | produces `food.order.ready.v1` |
| `courier-service` | service | courier online/offline state |
| `courier-tracking-service` | service | last-known location stream |
| `delivery-service` | service | consumes `delivery.courier.assigned.v1`; emits `delivery.courier.cancelled.v1` |
| `notification-service` | service | push offer to courier |
| `geolocation-service` | service | distance calculation, geofence |
| `eta-routing-service` | service | ETA to pickup |
| `zone-service` | service | surge / restricted zones |
| `configuration-service` | service | city-level tuning |
| `feature-flag-service` | service | batched dispatch rollout |
| `audit-service` | consumer | subscribes to `courier_dispatch.audit.*` |

## 12. Business Workflows

- Customer-ready → courier assigned (see `WORKFLOWS.md`).
- Batched offer (multiple orders, one courier).
- Reassignment after courier cancel.
- No-courier escalation.
- Admin force-reassign.

## 13. Exception Workflows

- All couriers reject every offer → `no_courier` after max attempts.
- Courier accepts but never arrives at restaurant → reassignment
  (driven by `delivery-service` timeout, not here).
- City operator forces a courier to go offline mid-offer → active
  offer is cancelled, the delivery is re-offered.
- The `courier-tracking-service` is down → fallback to last-known
  location with a degraded confidence flag; the matching still
  proceeds but with a wider radius.

## 14. Success Criteria

- p50 time-to-assignment ≤ 45s, p95 ≤ 90s.
- First-offer acceptance rate ≥ 80% in steady state.
- `no_courier` rate ≤ 2% per zone-hour.
- Assignment ledger write-success rate ≥ 99.99% over 30 days.
- No incidents in production caused by double-assignment.

## 15. KPIs

| KPI | Target | Measurement |
|-----|--------|-------------|
| Time to assignment (p50 / p95) | 45s / 90s | from `food.order.ready` event timestamp to `delivery.courier.assigned.v1` timestamp |
| First-offer acceptance rate | ≥ 80% | offers accepted / offers sent (excluding the final successful one) |
| `no_courier` rate | ≤ 2% | `no_courier` events / `food.order.ready` events per zone-hour |
| Offer latency (p95) | ≤ 200ms | from "I want to offer" to "push sent" |
| Pool freshness | ≥ 95% with location ≤ 60s old | `count(pool where last_ping_age ≤ 60s) / count(pool)` |

## 16. Acceptance Criteria

- The service emits `delivery.courier.assigned.v1` for ≥ 80% of
  `food.order.ready` events within 90s.
- The service never produces two `delivery.courier.assigned.v1` events
  for the same `food_order_id` in the same dispatch (verified by
  a state-machine test).
- The service never offers a delivery to a courier who already has
  an active offer (verified by a concurrent-offer test).
- The assignment ledger contains a row for every offer attempt,
  including rejections and expirations.
- Admin force-reassign works in production and is audit-logged.
- Batched dispatch works end-to-end for 2-3 orders from the same
  restaurant to one courier.

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
- [`../../README.md`](../../README.md) — services overview (the catalog of all 58 services)
- [`../../../main.md`](../../../main.md) — top-level platform specification (architecture, Keycloak, PostgreSQL 18, messaging, observability baseline)

