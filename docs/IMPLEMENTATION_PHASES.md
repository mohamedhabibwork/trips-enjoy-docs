# Implementation Phases & Timeline

> **Purpose:** Week-by-week implementation roadmap for all 20 active services
> **Duration:** 40 weeks (10 months)
> **Updated:** 2026-08-05 (consolidated from 58 to 20 per [ADR-0017](architecture/adrs/0017-20-service-architecture.md); see [MIGRATION_HUB.md](MIGRATION_HUB.md))

## Phase Overview

| Phase | Duration | Services | Focus Area |
|-------|----------|----------|------------|
| Phase 1 | Weeks 1-4 | 10 services | Platform Foundation |
| Phase 2 | Weeks 5-12 | 12 services | Core Business & Identity |
| Phase 3 | Weeks 13-20 | 12 services | Ride-Hailing Domain |
| Phase 4 | Weeks 21-28 | 10 services | Food Marketplace |
| Phase 5 | Weeks 29-34 | 9 services | Food Delivery & Financial |
| Phase 6 | Weeks 35-40 | 5 services | Analytics & Enhancements |
| Phase 7.5 | Weeks 41-42 | 9 services | Make-a-Deal kernel (cross-cutting) |

---

## Phase 1: Platform Foundation (Weeks 1-4)

**Goal:** Establish core platform services that all other services depend on

### Week 1-2: Configuration & Identity
- [ ] **configuration-service** - Business rules & config
- [ ] **`configuration-service` (flags)** - Feature toggles
- [ ] **identity-service** - Identity management
- [ ] **geolocation-service** - Geospatial queries

### Week 2-3: Gateway & Communication
- [ ] **api-gateway** - API entry point
- [ ] **`notification-service` (provider ACL)** - Multi-channel messaging
- [ ] **file-service** - File storage

### Week 3-4: Audit & Zones
- [ ] **audit-service** - Audit logging
- [ ] **`geolocation-service` (zones)** - Service zones
- [ ] **ledger-service** - Financial ledger

**Deliverables:**
- All Tier 0-1 services operational
- JWT authentication working
- Event bus configured
- PostgreSQL schemas created
- Redis caching operational


---

## Phase 2: Core Business & Identity (Weeks 5-12)

**Goal:** Build user management, vehicle, address, and financial core

### Week 5-6: User Profiles
- [ ] **`customer-service` (cross-persona profile)** - User preferences
- [ ] **customer-service** - Customer profiles
- [ ] **driver-service** - Driver profiles
- [ ] **courier-service** - Courier profiles

### Week 7-8: Support Services
- [ ] **`driver-service` (vehicles)** - Vehicle registry
- [ ] **`customer-service` (addresses)** - Address management
- [ ] **notification-service** - Notification orchestration
- [ ] **admin-service** - Admin operations

### Week 9-10: Financial Core
- [ ] **payment-service** - Payment orchestration
- [ ] **`payment-service` (wallet)** - Wallet management
- [ ] **`pricing-service` (tax)** - Tax calculations

### Week 11-12: Platform Support
- [ ] **`admin-service` (support module)** - Support ticketing
- [ ] **fraud-risk-service** - Risk scoring
- [ ] **`pricing-service` (promotion)** - Promotions & coupons

**Deliverables:**
- User identity management complete
- Payment processing operational
- Admin console functional
- Support ticketing system ready

---

## Phase 3: Ride-Hailing Domain (Weeks 13-20)

**Goal:** Complete ride-hailing business line

### Week 13-14: Pricing & Availability
- [ ] **pricing-service** - Dynamic pricing
- [ ] **`driver-service` (availability)** - Driver online status
- [ ] **`driver-service` (location)** - Location tracking
- [ ] **`geolocation-service` (ETA/routing)** - ETA calculations

### Week 15-17: Ride Flow
- [ ] **`trip-service` (ride-request)** - Ride requests
- [ ] **`driver-service` (dispatch)** - Ride dispatch
- [ ] **trip-service** - Trip management

### Week 18-19: Ride Payment & Earnings
- [ ] **`payment-service` (ride saga)** - Ride payment saga
- [ ] **`payment-service` (driver earnings)** - Driver earnings

### Week 19-20: Ride Enhancements
- [ ] **`trip-service` (scheduled)** - Scheduled rides
- [ ] **`trip-service` (safety)** - Safety features
- [ ] **`trip-service` (history)** - Historical trips
- [ ] **`driver-service` (incentives)** - Driver bonuses

**Deliverables:**
- End-to-end ride booking working
- Driver matching functional
- Payment capture complete
- Driver earnings calculated


---

## Phase 4: Food Marketplace (Weeks 21-28)

**Goal:** Complete food ordering business line

### Week 21-22: Merchant & Restaurant
- [ ] **`restaurant-service` (merchant)** - Merchant management
- [ ] **restaurant-service** - Restaurant profiles
- [ ] **`restaurant-service` (branch)** - Restaurant branches
- [ ] **`restaurant-service` (staff)** - Staff management

### Week 23-25: Menu & Cart
- [ ] **`restaurant-service` (menu)** - Menu management
- [ ] **`restaurant-service` (inventory)** - Stock management
- [ ] **`food-order-service` (cart)** - Shopping cart
- [ ] **search-service** - Search indexing

### Week 26-28: Order Flow
- [ ] **`food-order-service` (checkout)** - Checkout orchestration
- [ ] **food-order-service** - Food orders
- [ ] **`food-order-service` (queue)** - Kitchen order management

**Deliverables:**
- Restaurant onboarding complete
- Menu browsing & search working
- End-to-end food ordering functional
- Kitchen order management operational

---

## Phase 5: Food Delivery & Financial (Weeks 29-34)

**Goal:** Complete food delivery and financial settlement

### Week 29-31: Delivery
- [ ] **`courier-service` (dispatch)** - Courier matching
- [ ] **`courier-service` (tracking)** - Courier location tracking
- [ ] **`courier-service` (delivery)** - Delivery orchestration

### Week 32-34: Financial Settlement
- [ ] **`payment-service` (food saga)** - Food payment saga
- [ ] **`payment-service` (merchant settlement)** - Merchant payouts
- [ ] **`payment-service` (courier earnings)** - Courier earnings

**Deliverables:**
- Courier dispatch working
- End-to-end food delivery complete
- Merchant settlements operational
- Courier earnings calculated

---

## Phase 6: Analytics & Enhancements (Weeks 35-40)

**Goal:** Analytics, reporting, and platform enhancements

### Week 35-37: Analytics & Reporting
- [ ] **`reporting-service` (data lake)** - Data warehouse ingestion
- [ ] **reporting-service** - BI dashboards
- [ ] **`trip-service` (history)** (if not done in Phase 3)

### Week 38-40: Loyalty & Review
- [ ] **`pricing-service` (loyalty rules) / `customer-service` (account)** - Loyalty program
- [ ] **`trip-service` / `food-order-service` / `search-service` (review projections)** - Reviews & ratings

**Deliverables:**
- Analytics pipeline operational
- BI dashboards available
- Loyalty program functional
- Review system live

---

## Phase 7.5: Make a Deal Kernel (Weeks 41-42)

**Goal:** Ship the InDriver-style price negotiation kernel
([`docs/shared/DEAL_FEATURE.md`](shared/DEAL_FEATURE.md)) across the
9 participating services. Each participating service owns its own
deal rows and event production; the canonical contract lives in the
shared hub.

**Approach:** embedded per service. No new service binary. No shared
library at runtime (the hub is a Markdown contract, not a JAR).

### Week 41: Shared kernel + pricing/config/notifications

- [ ] **`pricing-service`** — add `GET /v1/quotes/{id}/fairness-band` endpoint and the new `pricing.geo_overrides.rule_kind` value `max_fare_override` (resolution order documented in `INTEGRATION.md` 1.7). Produce `pricing.fairness_band.computed.v1`.
- [ ] **`configuration-service`** — register the `deal.*` key family (see `INTEGRATION.md` 4.5.1) with the `{min, max, currency}` schema and `422 INVALID_BAND` validation.
- [ ] **`notification-service`** — add the 5 deal templates (`deal.opened`, `deal.bid_received`, `deal.counter_received`, `deal.accepted`, `deal.expired`) with `template_version_snapshot_id` audit binding. Consume the 12 `*.deal.*.v1` events.
- [ ] **``configuration-service` (flags)`** — surface `deal.enabled.{city_id}.{ride_type}`.
- [ ] **`audit-service`** — consume all `*.deal.*.v1` and write `audit.deal_transition.v1`.

### Week 42: Ride + dispatch + food

- [ ] **``trip-service` (ride-request)`** — rider-side boundary; add `POST /v1/rides/{id}/deal`, `POST /v1/deals/{id}/counter|accept|reject`, `GET /v1/deals/{id}`. Produce `ride.deal.opened.v1`, `ride.deal.countered.v1`, `ride.deal.accepted.v1`, `ride.deal.rejected.v1`, `ride.deal.expired.v1`. Consume `dispatch.deal.bid.submitted.v1`, `dispatch.deal.bid.expired.v1`, `dispatch.deal.accepted.v1`.
- [ ] **``driver-service` (dispatch)`** — driver-side boundary; add `GET /v1/dispatch/drivers/{id}/open-deals`, `POST /v1/dispatch/deals/{id}/bids|accept`, `POST /v1/dispatch/deals/{id}/bid/{bid_id}/reject`. Produce `dispatch.deal.bid.submitted.v1`, `dispatch.deal.bid.expired.v1`, `dispatch.deal.accepted.v1`, `dispatch.deal.rejected.v1`. Consume `ride.deal.opened.v1`, `ride.deal.countered.v1`, `ride.deal.accepted.v1`, `ride.deal.rejected.v1`, `ride.deal.expired.v1`.
- [ ] **`food-order-service`** — customer-side boundary; add `POST /v1/orders/{id}/deal` and the deal endpoints. Produce `food.deal.*.v1`. Consume `delivery.deal.bid.submitted.v1` (delivered by ``courier-service` (dispatch)`).
- [ ] **``courier-service` (dispatch)`** — courier-side boundary; mirror the `driver-service` (dispatch) changes for the food vertical.

### Rollout

- [ ] `deal.enabled.{city_id}.{ride_type}` feature flag = OFF in production.
- [ ] Smoke test: 1 city × 1 ride_type ("economy") → 1 city × all ride_types → all cities × all ride_types. Per `docs/shared/DEAL_FEATURE.md` 9.

**Acceptance criteria:**

- All 9 participating services have `TECH.md` 12 with the standard template.
- `pricing-service` returns a fairness band for a quote in any city.
- `configuration-service` rejects an invalid band write with `422 INVALID_BAND`.
- A rider-side `POST /v1/rides/{id}/deal` with out-of-band price returns `422 FARE_OUT_OF_BAND`.
- A matched deal emits the existing `ride.request.created.v1` / `food.order.placed.v1` with `accepted_fare_minor`.
- `audit-service` has rows for every deal transition.

---

## Phase 7: Guaranteed Rewards & Rating-Based Pricing (Weeks 41-44)

**Goal:** Per-trip + hourly/daily guaranteed rewards for driver and user;
rating-density surge-pressure and frequent-rider loyalty discount in
the pricing quote; per-location and city-to-city (OD-pair) pricing
overrides managed through a new `admin-service` geo-config API.

### Week 41-42: Trip-service guaranteed rewards + accounting
- [x] **trip-service** — `trip.reward.granted.v1` + `trip.reward.reversed.v1`; per-trip + hourly + daily top-ups for the driver and a per-trip credit for the user; idempotency-key `request:{request_id}:reward:{grant|reversal}`; append-only `trip.trip_reward` + `trip.trip_reward_reversal` tables (REVOKE UPDATE/DELETE).
- [x] **`payment-service` (driver earnings)** — consume the grant as `type=guaranteed_topup` and the reversal as `type=correction`; new `GET /v1/drivers/{id}/period-eligible-earnings?window=hourly|daily` endpoint for `trip-service`.
- [x] **`payment-service` (wallet)** — consume the user-side grant (idempotency-key `request:{request_id}:reward:user:grant`) and credit/debit the customer wallet.
- [x] **ledger-service** — informational consumer of both events; new chart-of-account sub-accounts `6302_guaranteed_minimum` (existing) for the driver side and `2100_customer_credit_liability` (new) for the user side; cross-service view in `ACCOUNTING_WORKFLOWS.md` "Guaranteed Rewards — Driver Top-Up + Customer Credit".

### Week 43-44: Pricing-service rating-based + geo-config
- [x] **pricing-service** — rating-density surge-pressure (`review.zone_aggregated.v1` consumed; `pricing.rating_density.applied.v1` produced) and frequent-rider loyalty discount (`loyalty.frequent_zone.aggregated.v1` consumed; `pricing.loyalty_discount.applied.v1` produced); per-location and OD-pair overrides via `admin-service` (`pricing.geo_config.updated.v1` consumed; `pricing.geo_overrides.matched.v1` produced); cross-border trips produce both `tax_origin` and `tax_destination` line items.
- [x] **`trip-service` / `food-order-service` / `search-service` (review projections)** — new `GET /v1/zones/{zone_id}/driver-rating?window_minutes=15` and `review.zone_aggregated.v1` event (debounced per zone).
- [x] **`pricing-service` (loyalty rules) / `customer-service` (account)** — new `GET /v1/accounts/{customer_id}/frequent-zones?window_days=30` and `loyalty.frequent_zone.aggregated.v1` event (debounced daily).
- [x] **admin-service** — geo-config CRUD at `/v1/admin/pricing/geo-config[...]` (`create` / `read` / `patch` / `disable` / `rollback` / `list`); emits `pricing.geo_config.updated.v1`; rollback requires break-glass and writes a new `pricing.rule_bindings_history` row (never UPDATE/DELETE).
- [x] **configuration-service** — hosts the new `trip.reward.*`, `pricing.rating_density.*`, `pricing.loyalty.frequent_rider.*`, and `pricing.geo_overrides.*` config key families.

**Deliverables:**
- Per-trip + hourly/daily guaranteed rewards live for both driver and user
- Rating-density and frequent-rider pricing signals live in every quote
- Admin geo-config CRUD operational with version/rollback semantics
- Cross-service view documented in `ACCOUNTING_WORKFLOWS.md`,
  `RIDE_WORKFLOWS.md`, `PAYMENT_WORKFLOWS.md`,
  `SERVICE_INTEGRATION_MATRIX.md`, `EVENT_ARCHITECTURE.md`
- Cross-doc consistency on the 17-service accounting-impact list preserved

