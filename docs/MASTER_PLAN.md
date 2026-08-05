# Master Implementation Plan

> **Purpose:** Single source of truth for **what** is being built, **in what
> order**, and **where the per-service plan lives**. The 20 per-service
> `PLAN.md` files linked from this document are the per-service source of
> truth for **how** each active service is built.
>
> **Updated:** 2026-08-05 (consolidated from 58 to 20 per
> [ADR-0017](architecture/adrs/0017-20-service-architecture.md);
> see [MIGRATION_HUB.md](MIGRATION_HUB.md))
> **Total active services:** 20
> **Timeline:** 44 weeks (Phase 1-6 = 40 weeks, Phase 7 = 4 weeks, Phase 7.5 = 2 weeks)
> **Status:** All 20 per-service PLAN.md files exist; this master plan binds
> them to the locked implementation order.

---

## How to read this document

| Question | Answer |
|----------|--------|
| _What is the order of implementation?_ | The "Implementation Order" tables below, one per phase. |
| _How do I build a specific service?_ | The per-service `PLAN.md` linked from the "Per-service Plans" section. |
| _What cross-cutting changes ship later?_ | Phase 7 (Guaranteed Rewards + Rating-Based Pricing) and Phase 7.5 (Make-a-Deal kernel). |
| _What events are produced and consumed?_ | `services/<svc>/INTEGRATION.md` is authoritative; the per-service `PLAN.md` summarizes. |
| _Which tier is a service in?_ | `SERVICE_INTEGRATION_MATRIX.md` (Tier 0–6 column). |

The order is **locked** to `IMPLEMENTATION_PHASES.md`. Do not re-order
without updating this file, `IMPLEMENTATION_PHASES.md`, and notifying
all teams.

---

## Implementation Order (locked)

### Phase 1 — Platform Foundation (Weeks 1–4)

| # | Service | Tier | Tech | Plan |
|---|---------|------|------|------|
| 1 | `configuration-service` | 0 | Kotlin/Spring | [PLAN](services/configuration-service/PLAN.md) |
| 2 | ``configuration-service` (flags)` | 0 | Kotlin/Spring | [PLAN](services/`configuration-service` (flags)/PLAN.md) |
| 3 | `identity-service` | 1 | Node/TS | [PLAN](services/identity-service/PLAN.md) |
| 4 | `geolocation-service` | 1 | Go | [PLAN](services/geolocation-service/PLAN.md) |
| 5 | `api-gateway` | 1 | Go/Envoy | [PLAN](services/api-gateway/PLAN.md) |
| 6 | ``notification-service` (provider ACL)` | 1 | Go | [PLAN](services/`notification-service` (provider ACL)/PLAN.md) |
| 7 | `file-service` | 1 | Go | [PLAN](services/file-service/PLAN.md) |
| 8 | `audit-service` | 1 | Go | [PLAN](services/audit-service/PLAN.md) |
| 9 | ``geolocation-service` (zones)` | 1 | Kotlin/Spring | [PLAN](services/`geolocation-service` (zones)/PLAN.md) |
| 10 | `ledger-service` | 1 | Node/TS | [PLAN](services/ledger-service/PLAN.md) |

**Block on:** nothing. Start in order; items 5–7 can move in parallel
with 1–4 once their first dependency is green.

### Phase 2 — Core Business & Identity (Weeks 5–12)

| # | Service | Tier | Tech | Plan |
|---|---------|------|------|------|
| 11 | `customer-service` (absorbs ``customer-service` (cross-persona profile)`, ``customer-service` (addresses)`) | 2 | Kotlin/Spring | [PLAN](services/customer-service/PLAN.md) |
| 12 | `driver-service` (absorbs ``driver-service` (availability)`, ``driver-service` (location)`, ``driver-service` (dispatch)`, ``driver-service` (incentives)`, ``driver-service` (vehicles)`) | 2 | Kotlin/Spring | [PLAN](services/driver-service/PLAN.md) |
| 13 | `courier-service` (absorbs ``courier-service` (dispatch)`, ``courier-service` (tracking)`, ``courier-service` (delivery)`) | 2 | Kotlin/Spring | [PLAN](services/courier-service/PLAN.md) |
| 14 | `notification-service` (absorbs ``notification-service` (provider ACL)`) | 2 | Kotlin/Spring | [PLAN](services/notification-service/PLAN.md) |
| 15 | `admin-service` (absorbs ``admin-service` (support module)`) | 2 | Kotlin/Spring | [PLAN](services/admin-service/PLAN.md) |
| 16 | `payment-service` (absorbs ``payment-service` (wallet)`, ``payment-service` (ride saga)`, ``payment-service` (food saga)`, ``payment-service` (driver earnings)`, ``payment-service` (courier earnings)`, ``payment-service` (merchant settlement)`) | 3 | Kotlin/Spring | [PLAN](services/payment-service/PLAN.md) |
| 17 | `fraud-risk-service` | 2 | Python/FastAPI | [PLAN](services/fraud-risk-service/PLAN.md) |
| 18 | `pricing-service` (absorbs ``pricing-service` (tax)`, ``pricing-service` (promotion)`, loyalty-rules from ``pricing-service` (loyalty rules) / `customer-service` (account)`) | 3 | Kotlin/Spring | [PLAN](services/pricing-service/PLAN.md) |

### Phase 3 — Ride-Hailing Domain (Weeks 13–20)

| # | Service | Tier | Tech | Plan |
|---|---------|------|------|------|
| 19 | `trip-service` (absorbs ``trip-service` (ride-request)`, ``trip-service` (scheduled)`, ``trip-service` (safety)`, ``trip-service` (history)`, trip-review projection of ``trip-service` / `food-order-service` / `search-service` (review projections)`) | 4 | Kotlin/Spring | [PLAN](services/trip-service/PLAN.md) |
| 20 | `geolocation-service` (absorbs ``geolocation-service` (ETA/routing)`, ``geolocation-service` (zones)`) | 3 | Go | [PLAN](services/geolocation-service/PLAN.md) |

### Phase 4 — Food Marketplace (Weeks 21–28)

| # | Service | Tier | Tech | Plan |
|---|---------|------|------|------|
| 21 | `restaurant-service` (absorbs ``restaurant-service` (merchant)`, ``restaurant-service` (branch)`, ``restaurant-service` (menu)`, ``restaurant-service` (inventory)`, ``restaurant-service` (staff)`) | 3 | Kotlin/Spring | [PLAN](services/restaurant-service/PLAN.md) |
| 22 | `food-order-service` (absorbs ``food-order-service` (cart)`, ``food-order-service` (checkout)`, ``food-order-service` (queue)`, food-review projection of ``trip-service` / `food-order-service` / `search-service` (review projections)`) | 5 | Kotlin/Spring | [PLAN](services/food-order-service/PLAN.md) |
| 23 | `search-service` (absorbs search-review projection of ``trip-service` / `food-order-service` / `search-service` (review projections)`) | 6 | Kotlin/Spring | [PLAN](services/search-service/PLAN.md) |

### Phase 5 — Food Delivery & Financial (Weeks 29–34)

| # | Service | Tier | Tech | Plan |
|---|---------|------|------|------|
| 24 | `payment-service` — financial aggregation already in Phase 2; Phase 5 hardens ride + food + driver + courier + restaurant settlement end-to-end | 3 | Kotlin/Spring | [PLAN](services/payment-service/PLAN.md) |

### Phase 6 — Analytics & Enhancements (Weeks 35–40)

| # | Service | Tier | Tech | Plan |
|---|---------|------|------|------|
| 25 | `reporting-service` (absorbs ``reporting-service` (data lake)`) | 6 | Kotlin/Spring | [PLAN](services/reporting-service/PLAN.md) |

### Phase 7 — Cross-cutting: Guaranteed Rewards & Rating-Based Pricing (Weeks 41–44)

```mermaid
flowchart LR
  TS[trip-service] -- trip.reward.granted.v1 --> DE[`payment-service` (driver earnings)]
  TS -- trip.reward.granted.v1 (user-side) --> WS[`payment-service` (wallet)]
  TS -- trip.reward.granted.v1 --> LED[ledger-service]
  TS -- trip.reward.granted.v1 --> NOT[notification-service]
  TS -- trip.reward.granted.v1 --> AUD[audit-service]
  TS -- trip.reward.granted.v1 --> ANA[`reporting-service` (data lake)]
  RR[`trip-service` / `food-order-service` / `search-service` (review projections)] -- review.zone_aggregated.v1 --> PR[pricing-service]
  LOY[`pricing-service` (loyalty rules) / `customer-service` (account)] -- loyalty.frequent_zone.aggregated.v1 --> PR
  ADM[admin-service] -- pricing.geo_config.updated.v1 --> PR
  PR -- pricing.rating_density.applied.v1 --> AUD
  PR -- pricing.loyalty_discount.applied.v1 --> AUD
  PR -- pricing.geo_overrides.matched.v1 --> AUD
  CFG[configuration-service] -- hosts key families --> TS
  CFG --> PR
  CFG --> ADM
  CUS[customer-service] -- exposes credit balance --> WS
  TS -- trip.reward.reversed.v1 --> DE
  TS -- trip.reward.reversed.v1 (user-side) --> WS
  TS -- trip.reward.reversed.v1 --> LED
  TS -- trip.reward.reversed.v1 --> NOT
  TS -- trip.reward.reversed.v1 --> AUD
  TS -- trip.reward.reversed.v1 --> ANA
```

Participating services (each ships a `Phase 7.0` block in its PLAN.md):

| Service | Role in Phase 7 |
|---------|-----------------|
| [`trip-service`](services/trip-service/PLAN.md) | Producer — `trip.reward.granted.v1`, `trip.reward.reversed.v1`; append-only `trip.trip_reward` + `trip.trip_reward_reversal` (REVOKE UPDATE/DELETE) |
| [`pricing-service`](services/pricing-service/PLAN.md) | Consumer — rating-density (`review.zone_aggregated.v1`), frequent-rider (`loyalty.frequent_zone.aggregated.v1`), geo-config (`pricing.geo_config.updated.v1`) |
| [`admin-service`](services/admin-service/PLAN.md) | Producer — `/v1/admin/pricing/geo-config[...]` (create/read/patch/disable/rollback/list) |
| [``trip-service` / `food-order-service` / `search-service` (review projections)`](services/`trip-service` / `food-order-service` / `search-service` (review projections)/PLAN.md) | Producer — `review.zone_aggregated.v1` (debounced per zone) |
| [``pricing-service` (loyalty rules) / `customer-service` (account)`](services/`pricing-service` (loyalty rules) / `customer-service` (account)/PLAN.md) | Producer — `loyalty.frequent_zone.aggregated.v1` (debounced daily) |
| [``payment-service` (driver earnings)`](services/`payment-service` (driver earnings)/PLAN.md) | Consumer — `type=guaranteed_topup` on grant, `type=correction` on reversal |
| [``payment-service` (wallet)`](services/`payment-service` (wallet)/PLAN.md) | Consumer — user-side grant; idempotency `trip:{trip_id}:reward:user:grant` |
| [`customer-service`](services/customer-service/PLAN.md) | Mirror — exposes user-side credit balance |
| [`ledger-service`](services/ledger-service/PLAN.md) | Informational consumer — chart-of-account sub-accounts `6302_guaranteed_minimum` and `2100_customer_credit_liability` |
| [`notification-service`](services/notification-service/PLAN.md) | Consumer — `trip.reward.granted`, `trip.reward.reversed` templates |
| [`audit-service`](services/audit-service/PLAN.md) | Consumer — `audit.trip_reward.v1` rows |
| [``reporting-service` (data lake)`](services/`reporting-service` (data lake)/PLAN.md) | Mirror — rewards fact table |
| [`configuration-service`](services/configuration-service/PLAN.md) | Hosts key families `trip.reward.*`, `pricing.rating_density.*`, `pricing.loyalty.frequent_rider.*`, `pricing.geo_overrides.*` |

Cross-doc consistency: the canonical 17-service accounting-impact list lives
in `workflows/ACCOUNTING_WORKFLOWS.md` §"Guaranteed Rewards — Driver Top-Up +
Customer Credit".

### Phase 7.5 — Make-a-Deal Kernel (Weeks 41–42, parallel with Phase 7)

Single source of truth: `docs/shared/DEAL_FEATURE.md`. Each participating
service ships a `Phase 7.5` block in its PLAN.md and owns its own deal rows
and event production. **No central binary** — the kernel is a Markdown
contract, not a JAR.

| # | Service | Role | Week |
|---|---------|------|------|
| 59 | [`pricing-service`](services/pricing-service/PLAN.md) | Helper — `GET /v1/quotes/{id}/fairness-band`, `max_fare_override` rule kind | 41 |
| 60 | [`configuration-service`](services/configuration-service/PLAN.md) | Helper — `deal.*` key family `{min, max, currency}` (422 `INVALID_BAND`) | 41 |
| 61 | [`notification-service`](services/notification-service/PLAN.md) | Helper — 5 deal templates, audit-bound to `template_version_snapshot_id` | 41 |
| 62 | [``configuration-service` (flags)`](services/`configuration-service` (flags)/PLAN.md) | Helper — `deal.enabled.{city_id}.{ride_type}` | 41 |
| 63 | [`audit-service`](services/audit-service/PLAN.md) | Helper — consume all 12 `*.deal.*.v1` events, write `audit.deal_transition.v1` | 41 |
| 64 | [``trip-service` (ride-request)`](services/`trip-service` (ride-request)/PLAN.md) | Rider boundary — ride-side endpoints + 5 ride events | 42 |
| 65 | [``driver-service` (dispatch)`](services/`driver-service` (dispatch)/PLAN.md) | Driver boundary — dispatch-side endpoints + 4 dispatch events | 42 |
| 66 | [`food-order-service`](services/food-order-service/PLAN.md) | Customer boundary — food-side endpoints + 5 food events | 42 |
| 67 | [``courier-service` (dispatch)`](services/`courier-service` (dispatch)/PLAN.md) | Courier boundary — mirrors dispatch for the food vertical | 42 |

Rollout: `deal.enabled.{city_id}.{ride_type}` = OFF in production. Smoke
test 1 city × 1 ride_type → 1 city × all ride_types → all cities × all
ride_types per `docs/shared/DEAL_FEATURE.md` §9.

---

## Per-service Plans (alphabetical)

Every service has a PLAN.md. The 10-phase structure is identical across
all 20 active services; only the per-service body, events, and Phase 7 / 7.5
participation blocks differ.

| Service | Tier | Tech | Criticality | PLAN.md |
|---------|------|------|-------------|---------|
| ``customer-service` (addresses)` | 2 | Kotlin/Spring | T2 (99.9%) | [PLAN](services/`customer-service` (addresses)/PLAN.md) |
| `admin-service` | 2 | Kotlin/Spring | T2 (99.9%) | [PLAN](services/admin-service/PLAN.md) |
| ``reporting-service` (data lake)` | 6 | Kotlin/Spring | T3 (99.5%) | [PLAN](services/`reporting-service` (data lake)/PLAN.md) |
| `api-gateway` | 1 | Go/Envoy | T0 (99.99%) | [PLAN](services/api-gateway/PLAN.md) |
| `audit-service` | 1 | Go | T1 (99.95%) | [PLAN](services/audit-service/PLAN.md) |
| ``restaurant-service` (branch)` | 3 | Kotlin/Spring | T2 (99.9%) | [PLAN](services/`restaurant-service` (branch)/PLAN.md) |
| ``food-order-service` (cart)` | 4 | Kotlin/Spring | T2 (99.9%) | [PLAN](services/`food-order-service` (cart)/PLAN.md) |
| ``food-order-service` (checkout)` | 5 | Kotlin/Spring | T1 (99.95%) | [PLAN](services/`food-order-service` (checkout)/PLAN.md) |
| ``notification-service` (provider ACL)` | 1 | Go | T2 (99.9%) | [PLAN](services/`notification-service` (provider ACL)/PLAN.md) |
| `configuration-service` | 0 | Kotlin/Spring | T1 (99.95%) | [PLAN](services/configuration-service/PLAN.md) |
| ``courier-service` (dispatch)` | 5 | Python/FastAPI | T1 (99.95%) | [PLAN](services/`courier-service` (dispatch)/PLAN.md) |
| ``payment-service` (courier earnings)` | 5 | Kotlin/Spring | T2 (99.9%) | [PLAN](services/`payment-service` (courier earnings)/PLAN.md) |
| `courier-service` | 2 | Kotlin/Spring | T2 (99.9%) | [PLAN](services/courier-service/PLAN.md) |
| ``courier-service` (tracking)` | 3 | Go | T1 (99.95%) | [PLAN](services/`courier-service` (tracking)/PLAN.md) |
| `customer-service` | 2 | Kotlin/Spring | T1 (99.95%) | [PLAN](services/customer-service/PLAN.md) |
| ``courier-service` (delivery)` | 5 | Kotlin/Spring | T1 (99.95%) | [PLAN](services/`courier-service` (delivery)/PLAN.md) |
| ``driver-service` (dispatch)` | 4 | Kotlin/Spring | T1 (99.95%) | [PLAN](services/`driver-service` (dispatch)/PLAN.md) |
| ``driver-service` (availability)` | 3 | Go | T1 (99.95%) | [PLAN](services/`driver-service` (availability)/PLAN.md) |
| ``payment-service` (driver earnings)` | 4 | Kotlin/Spring | T2 (99.9%) | [PLAN](services/`payment-service` (driver earnings)/PLAN.md) |
| ``driver-service` (incentives)` | 5 | Python/FastAPI | T3 (99.5%) | [PLAN](services/`driver-service` (incentives)/PLAN.md) |
| ``driver-service` (location)` | 3 | Go | T1 (99.95%) | [PLAN](services/`driver-service` (location)/PLAN.md) |
| `driver-service` | 2 | Kotlin/Spring | T1 (99.95%) | [PLAN](services/driver-service/PLAN.md) |
| ``geolocation-service` (ETA/routing)` | 3 | Go | T2 (99.9%) | [PLAN](services/`geolocation-service` (ETA/routing)/PLAN.md) |
| ``configuration-service` (flags)` | 0 | Kotlin/Spring | T0 (99.99%) | [PLAN](services/`configuration-service` (flags)/PLAN.md) |
| `file-service` | 1 | Go | T2 (99.9%) | [PLAN](services/file-service/PLAN.md) |
| `food-order-service` | 5 | Kotlin/Spring | T1 (99.95%) | [PLAN](services/food-order-service/PLAN.md) |
| ``payment-service` (food saga)` | 5 | Kotlin/Spring | T1 (99.95%) | [PLAN](services/`payment-service` (food saga)/PLAN.md) |
| `fraud-risk-service` | 2 | Python/FastAPI | T2 (99.9%) | [PLAN](services/fraud-risk-service/PLAN.md) |
| `geolocation-service` | 1 | Go | T1 (99.95%) | [PLAN](services/geolocation-service/PLAN.md) |
| `identity-service` | 1 | Node/TS | T0 (99.99%) | [PLAN](services/identity-service/PLAN.md) |
| ``restaurant-service` (inventory)` | 4 | Kotlin/Spring | T2 (99.9%) | [PLAN](services/`restaurant-service` (inventory)/PLAN.md) |
| `ledger-service` | 1 | Node/TS | T0 (99.99%) | [PLAN](services/ledger-service/PLAN.md) |
| ``pricing-service` (loyalty rules) / `customer-service` (account)` | 4 | Kotlin/Spring | T2 (99.9%) | [PLAN](services/`pricing-service` (loyalty rules) / `customer-service` (account)/PLAN.md) |
| ``restaurant-service` (menu)` | 4 | Kotlin/Spring | T2 (99.9%) | [PLAN](services/`restaurant-service` (menu)/PLAN.md) |
| ``restaurant-service` (merchant)` | 3 | Kotlin/Spring | T2 (99.9%) | [PLAN](services/`restaurant-service` (merchant)/PLAN.md) |
| `notification-service` | 2 | Kotlin/Spring | T2 (99.9%) | [PLAN](services/notification-service/PLAN.md) |
| `payment-service` | 3 | Kotlin/Spring | T0 (99.99%) | [PLAN](services/payment-service/PLAN.md) |
| `pricing-service` | 3 | Kotlin/Spring | T1 (99.95%) | [PLAN](services/pricing-service/PLAN.md) |
| ``pricing-service` (promotion)` | 2 | Kotlin/Spring | T2 (99.9%) | [PLAN](services/`pricing-service` (promotion)/PLAN.md) |
| `reporting-service` | 6 | Python/FastAPI | T3 (99.5%) | [PLAN](services/reporting-service/PLAN.md) |
| ``food-order-service` (queue)` | 5 | Kotlin/Spring | T2 (99.9%) | [PLAN](services/`food-order-service` (queue)/PLAN.md) |
| `restaurant-service` | 3 | Kotlin/Spring | T2 (99.9%) | [PLAN](services/restaurant-service/PLAN.md) |
| ``payment-service` (merchant settlement)` | 5 | Kotlin/Spring | T1 (99.95%) | [PLAN](services/`payment-service` (merchant settlement)/PLAN.md) |
| ``restaurant-service` (staff)` | 4 | Kotlin/Spring | T3 (99.5%) | [PLAN](services/`restaurant-service` (staff)/PLAN.md) |
| ``trip-service` / `food-order-service` / `search-service` (review projections)` | 4 | Kotlin/Spring | T2 (99.9%) | [PLAN](services/`trip-service` / `food-order-service` / `search-service` (review projections)/PLAN.md) |
| ``trip-service` (history)` | 6 | Kotlin/Spring | T3 (99.5%) | [PLAN](services/`trip-service` (history)/PLAN.md) |
| ``payment-service` (ride saga)` | 5 | Kotlin/Spring | T1 (99.95%) | [PLAN](services/`payment-service` (ride saga)/PLAN.md) |
| ``trip-service` (ride-request)` | 4 | Kotlin/Spring | T1 (99.95%) | [PLAN](services/`trip-service` (ride-request)/PLAN.md) |
| ``trip-service` (safety)` | 4 | Kotlin/Spring | T1 (99.95%) | [PLAN](services/`trip-service` (safety)/PLAN.md) |
| ``trip-service` (scheduled)` | 4 | Kotlin/Spring | T2 (99.9%) | [PLAN](services/`trip-service` (scheduled)/PLAN.md) |
| `search-service` | 6 | Kotlin/Spring | T2 (99.9%) | [PLAN](services/search-service/PLAN.md) |
| ``admin-service` (support module)` | 2 | Kotlin/Spring | T2 (99.9%) | [PLAN](services/`admin-service` (support module)/PLAN.md) |
| ``pricing-service` (tax)` | 2 | Kotlin/Spring | T2 (99.9%) | [PLAN](services/`pricing-service` (tax)/PLAN.md) |
| `trip-service` | 4 | Kotlin/Spring | T0 (99.99%) | [PLAN](services/trip-service/PLAN.md) |
| ``customer-service` (cross-persona profile)` | 2 | Kotlin/Spring | T2 (99.9%) | [PLAN](services/`customer-service` (cross-persona profile)/PLAN.md) |
| ``driver-service` (vehicles)` | 2 | Kotlin/Spring | T2 (99.9%) | [PLAN](services/`driver-service` (vehicles)/PLAN.md) |
| ``payment-service` (wallet)` | 3 | Kotlin/Spring | T1 (99.95%) | [PLAN](services/`payment-service` (wallet)/PLAN.md) |
| ``geolocation-service` (zones)` | 1 | Kotlin/Spring | T1 (99.95%) | [PLAN](services/`geolocation-service` (zones)/PLAN.md) |

---

## Domain × Phase matrix

| Domain | Phase 1 | Phase 2 | Phase 3 | Phase 4 | Phase 5 | Phase 6 |
|--------|---------|---------|---------|---------|---------|---------|
| Platform Foundation | 10 | – | – | – | – | – |
| Identity & User | – | 7 | – | – | – | – |
| Financial Core | 1 (ledger) | 3 | 1 | – | – | – |
| Platform Support | – | 4 | – | – | – | – |
| Ride-Hailing | – | – | 13 | – | – | – |
| Food Marketplace | – | – | – | 11 | – | – |
| Food Delivery | – | – | – | – | 6 | – |
| Analytics | – | – | 1 (ride-history) | 1 (search) | – | 4 |

Phase 7 modifies 13 services (cross-cutting); Phase 7.5 modifies 9 of them
(Make-a-Deal kernel). See the per-service `PLAN.md` for the exact
participation block.

---

## Implementation rules (apply to every service)

1. **Outbox pattern** for every published event. **Inbox pattern** for
   every consumed event. Idempotency-Key on every mutating REST route.
2. **Ci / CD** — every PR runs unit tests + contract tests + lint + OpenAPI
   diff. Merging to `main` deploys to staging automatically; production is
   gated by the release manager.
3. **SLO** — T0=99.99%, T1=99.95%, T2=99.9%, T3=99.5% per
   `SERVICE_INTEGRATION_MATRIX.md`.
4. **Schema migrations** — forward-only; pre-upgrade Job before deploy.
5. **No cross-service direct DB access.** Every cross-service read is a
   REST call or an event consumer.
6. **Every `PLAN.md` is owned** by the service's tech lead. Any change to
   the per-service plan must also reopen the master plan row.

---

## Cross-cutting reference docs

- `IMPLEMENTATION_PHASES.md` — week-by-week roadmap
- `PLAN_INDEX.md` — short index that links to this master plan
- `SERVICE_INTEGRATION_MATRIX.md` — tier, tech, deps, events
- `MASTER_SERVICE_PLAN.md` — legacy pre-Phase-7 detailed plan (kept for history)
- `MASTER_PLAN_SUMMARY.md` — legacy executive summary
- `workflows/ACCOUNTING_WORKFLOWS.md` — cross-service accounting view
- `docs/shared/DEAL_FEATURE.md` — Phase 7.5 kernel contract
- `architecture/EVENT_ARCHITECTURE.md` — canonical event catalog
- `architecture/DATABASE_ARCHITECTURE.md` — partitioning rules
- `architecture/KEYCLOAK_ARCHITECTURE.md` — identity bridge

---

## Status

- [x] All 20 active services have a `PLAN.md`
- [x] All 20 active PLAN.md files are linked from this master plan
- [x] Implementation order is locked to `IMPLEMENTATION_PHASES.md`
- [x] Phase 7 cross-cutting participation is documented per service
- [x] Phase 7.5 Make-a-Deal participation is documented per service
- [x] The 17-service accounting-impact list is preserved across docs
