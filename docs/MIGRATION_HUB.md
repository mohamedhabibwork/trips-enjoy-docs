# Service Migration Hub

This hub is the **single authoritative map** for the consolidation
described in
[ADR-0016: Service Domain Consolidation (58 → 44)](architecture/adrs/0016-service-domain-consolidation.md).

The platform's active microservices catalog now contains **44
services**. The 14 services listed below have been **removed**
(directory deleted after their documentation, schemas, events,
endpoints, and operational details were absorbed into the surviving
service and a cross-reference appendix inside the surviving
service's docs).

> This hub is the only place outside the surviving service docs
> where the removed service names appear. Any deep link, internal
> note, or external reference to a removed service resolves here or
> inside the absorbing service's "Removed predecessor capability"
> appendix. The compatibility window for old event topics, old REST
> paths, and old schema names is **at least six calendar months**
> from 2026-08-05.

## 1. Active service count

| When | Active services | Removed | Reference |
|------|----------------:|--------:|-----------|
| Before 2026-08-05 | 58 | 0 | [MICROSERVICES_MAP §"Service Count Summary"](architecture/MICROSERVICES_MAP.md#service-count-summary) historical |
| After  2026-08-05 | 44 | 14 | this hub + ADR-0016 |

The removed services are **not retained as a retired suite**.
Their docs, schemas, and operational notes have been migrated into
the absorbing service and into the section-by-section appendix
table in §2 of this hub. The 14 directories under
`docs/services/<removed>/` were deleted after absorption.

## 2. Consolidation matrix

Each row links to the absorbing service's "Removed predecessor
capability" appendix where the absorbed schema, events, endpoints,
and workflows are appended (preserving original section numbers).

| # | Removed service | Absorbing service | Capability absorbed | Hub appendix |
|---|-----------------|-------------------|---------------------|--------------|
| 1 | `courier-dispatch-service` | `courier-service` | courier matching, assignment ledger, batched offers, no-courier handling | [§3.1](#31-courier-dispatch) |
| 2 | `courier-tracking-service` | `courier-service` | high-frequency courier location stream, `courier_tracking` schema, curated `courier.location.updated.v1` | [§3.2](#32-courier-tracking) |
| 3 | `courier-earnings-service` | `payment-service` | courier earnings ledger, withdrawal requests, `courier.earning.accrued.v1` | [§3.3](#33-courier-earnings) |
| 4 | `dispatch-service` | `driver-service` | ride matching, match-attempt ledger, offer/accept/expire flow, fairness | [§3.4](#34-dispatch) |
| 5 | `driver-availability-service` | `driver-service` | driver online state machine, current shift, accepted ride types, current zone | [§3.5](#35-driver-availability) |
| 6 | `driver-location-service` | `driver-service` | high-frequency driver location stream, `driver_location` schema, curated `driver.location.updated.v1` | [§3.6](#36-driver-location) |
| 7 | `driver-incentive-service` | `driver-service` | quests, bonuses, surge guarantees, eligibility (operational capability) | [§3.7](#37-driver-incentive) |
| 8 | `driver-earnings-service` | `payment-service` | driver earnings ledger, withdrawal requests, `driver.earning.accrued.v1` | [§3.8](#38-driver-earnings) |
| 9 | `restaurant-order-mgmt-service` | `food-order-service` | restaurant-side queue, accept/reject timer, prep state, ready signal | [§3.9](#39-restaurant-order-mgmt) |
| 10 | `restaurant-staff-service` | `restaurant-service` | staff invitations, role assignments, devices | [§3.10](#310-restaurant-staff) |
| 11 | `restaurant-settlement-service` | `payment-service` | merchant payable, payout runs, disputes | [§3.11](#311-restaurant-settlement) |
| 12 | `food-payment-integration-service` | `payment-service` | food payment saga orchestration | [§3.12](#312-food-payment-integration) |
| 13 | `ride-payment-integration-service` | `payment-service` | ride payment saga orchestration | [§3.13](#313-ride-payment-integration) |
| 14 | `wallet-service` | `payment-service` | wallet balance, holds, top-ups, statement | [§3.14](#314-wallet) |

## 3. Per-service migration record

This section preserves the unique schemas, events, endpoints, and
operational notes from each removed service. It is the canonical
reference; the absorbing service's "Removed predecessor capability"
appendix mirrors and links back here.

> **Compatibility window note.** For at least six calendar months
> from 2026-08-05, every event topic, REST path, and database
> schema listed in this section is **also** published under the
> removed-service name by the absorbing service. Old topic names
> are not renamed; old REST paths are 301-redirected to the
> canonical path under the absorbing service; old schema names are
> mirrored as readable views in the absorbing service's schema.

### 3.1 courier-dispatch

**Absorbed by:** `courier-service`.

**Bounded context:** courier matching, assignment ledger, batched
offers, no-courier handling, reassignment, courier release.

**Schema rename:** `courier_dispatch` → `courier` (see
[`services/courier-service/ERD.md`](services/courier-service/ERD.md)
appendix §A.1).

**Tables absorbed** (partitioning conventions preserved — see
`architecture/DATABASE_ARCHITECTURE.md`):

- `courier_dispatch.dispatches` → `courier.dispatches`.
  State machine:
  `initiated → offered → accepted → committed`;
  `↘ expired ↗`; `↘ rejected ↗`;
  `initiated → no_courier → re_offered (loop) → no_courier`.
  CHECK `state IN ('initiated','offered','accepted','committed','no_courier','cancelled','failed')`;
  `attempt_number BETWEEN 1 AND 50`;
  `offer_window_seconds BETWEEN 1 AND 120`;
  `max_offer_attempts BETWEEN 1 AND 20`.
- `courier_dispatch.assignments` → `courier.assignments`
  (append-only; INSERT only; no UPDATE / no DELETE).
  Partitioned by RANGE on `assigned_at` (monthly); 12 months
  pre-created; 3-year retention.
  CHECK `outcome IN ('offered','accepted','rejected','expired','cancelled','no_courier')`;
  `responded_at IS NULL OR responded_at >= offered_at`;
  `sequence BETWEEN 1 AND 50`.
- `courier_dispatch.courier_pool_entries` → `courier.courier_pool_entries`.
  Redis-first (sorted set `courier_pool:{city_id}` keyed on
  `last_ping_ms`); PostgreSQL projection for durability.
  CHECK `state IN ('available','busy','paused')`.
- `courier_dispatch.city_config` → `courier.city_config`
  (configuration snapshot).
- `courier_dispatch.outbox` → `courier.outbox`.
- `courier_dispatch.inbox` → `courier.inbox`.

**REST endpoints (now mounted on `courier-service`):**

| Method | URI | Auth | Purpose |
|--------|-----|------|---------|
| POST | `/v1/dispatches` | bearer (service) | start a dispatch for a `food_order_id` |
| GET  | `/v1/dispatches/{id}` | bearer | read a dispatch attempt |
| GET  | `/v1/dispatches?order_id=…` | bearer | list attempts for an order |
| POST | `/v1/dispatches/{id}/offers` | bearer (internal) | record an offer attempt |
| POST | `/v1/dispatches/{id}/accept` | bearer (courier) | courier accepts an offer |
| POST | `/v1/dispatches/{id}/reject` | bearer (courier) | courier rejects an offer |
| POST | `/v1/dispatches/{id}/cancel` | bearer (service / admin) | cancel a dispatch (compensates) |
| POST | `/v1/dispatches/{id}/reassign` | bearer (service / admin) | force reassignment |
| GET  | `/v1/dispatches/metrics` | bearer (admin) | operational counters |

**Events produced** (same topic + schema version, by `courier-service`):

- `delivery.courier.assigned.v1` — courier accepted; assignment committed.
- `delivery.dispatch.no_courier.v1` — offer window expired, no acceptance.
- `delivery.dispatch.offer.expired.v1` — single offer window expired.
- `delivery.dispatch.reassigned.v1` — courier cancelled / failed; delivery re-offered.
- `courier_dispatch.audit.assignment_committed.v1` — audit.

**Events consumed** (in `courier-service` consumer groups):

- `food.order.ready.v1` (from `food-order-service`) — primary trigger.
- `courier.availability.online.v1` / `offline.v1` (from `courier-service` itself; online flag is now part of the courier profile) — pool refresh.
- `courier.location.updated.v1` (from `courier-service` itself; location stream is absorbed) — pool re-rank.
- `courier.shift.ended.v1` (from `courier-service`) — pool removal.
- `delivery.courier.cancelled.v1` (from `delivery-service`) — reassignment.
- `configuration.updated.v1` (from `configuration-service`) — config refresh.

**Configuration keys** (now under `courier.*` namespace):

- `courier.offer_window_seconds` (int, default 30, per-city override).
- `courier.max_offer_attempts` (int, default 6, per-zone override).
- `courier.batch_max_size` (int, default 3).
- `courier.no_courier_backoff_seconds` (int, default 60).
- `courier.pool_max_radius_meters` (int, default 3000).
- `courier.feature.batched_dispatch` (bool).
- `courier.feature.zone_surge_aware` (bool).

**Non-functional targets** (carried into `courier-service`):

- P50 time-to-assignment from `food.order.ready.v1` ≤ 45 s.
- P95 time-to-assignment ≤ 90 s.
- P95 pool-search latency ≤ 200 ms.
- 50 dispatches/s/region sustained; 200 rps burst.
- 99.95% / 30 days SLO.
- RPO 5 min; RTO 30 min.
- Test coverage ≥ 80% line, ≥ 70% branch; 100% on matching and state machine.

**Degraded mode:** if the embedded location stream is unreachable
(internal sub-call, no cross-service hop), the service continues
with stale locations and a wider radius (×1.5). Mitigations live
in `architecture/SERVICE_ISOLATION.md` (CRITICAL/DEGRADABLE).

**Workflows:** see [`workflows/COURIER_WORKFLOWS.md`](workflows/COURIER_WORKFLOWS.md)
— courier shifts, dispatch, delivery.

---

### 3.2 courier-tracking

**Absorbed by:** `courier-service`.

**Bounded context:** high-frequency courier location stream.

**Schema rename:** `courier_tracking` → `courier`.

**Tables absorbed:**

- `courier_tracking.current_location` → `courier.current_location`
  (UPSERT by `courier_id`; hot path).
- `courier_tracking.locations` → `courier.location_trail`
  (RANGE on `recorded_at`, monthly; 12 months pre-created; 30-day
  retention hot; cold tier 1 year).

**REST endpoints (now on `courier-service`):**

| Method | URI | Auth | Purpose |
|--------|-----|------|---------|
| POST | `/v1/couriers/{id}/locations` | bearer (courier) | ingest a GPS ping (up to 5 Hz, target 1 Hz) |
| GET  | `/v1/couriers/{id}/location` | bearer | last-known location (SLO 30 ms p99) |
| GET  | `/v1/couriers/{id}/locations/recent?minutes=N` | bearer | recent trail |

**Events produced:** `courier.location.updated.v1` (curated, default
1 Hz per courier; suppressed for stale couriers unless read).

**Events consumed:**

- `courier.availability.online.v1` (from `courier-service`) — begin
  ingesting for that courier.
- `courier.availability.offline.v1` (from `courier-service`) — stop
  ingesting; keep last-known for 60 s before marking stale.

**Stale rule:** no ping in 60 s → `is_stale = true`.

**Non-functional targets:**

- 5 Hz ingestion per courier, sustained.
- P95 GET latency ≤ 30 ms (cached current_location).
- Partitioning: monthly on `recorded_at`; pre-create 12 months.

**Degraded mode:** if upstream `courier-service` is down, write
buffer is held in process for up to 5 minutes; backfill on
recovery (inbox dedup).

---

### 3.3 courier-earnings

**Absorbed by:** `payment-service`.

**Bounded context:** courier earnings ledger and withdrawals.

**Schema rename:** `courier_earnings` → `payment`.

**Tables absorbed:**

- `courier_earnings.earnings` → `payment.courier_earnings`
  (RANGE on `accrued_at`, monthly; 3-year retention).
  CHECK `type IN ('delivery_fee','tip','bonus','guaranteed_topup','penalty')`.
- `courier_earnings.balances` → `payment.courier_balances`
  (running totals — withdrawable, pending, lifetime).
- `courier_earnings.withdrawals` → `payment.courier_withdrawals`
  (state: `requested`, `processing`, `paid`, `failed`).
- `courier_earnings.bank_details` → `payment.courier_bank_details`
  (tokenised references only — no PAN).

**Events produced:**

- `courier.earning.accrued.v1` — earning row inserted.
- `courier.withdrawal.requested.v1` — withdrawal enqueued.
- `courier.withdrawal.completed.v1` — withdrawal paid.

**Events consumed:**

- `delivery.completed.v1` (from `delivery-service`) — accrue delivery fee.
- `food.payment.completed.v1` (from `payment-service` itself — same service) — accrue tip + bonus.
- `courier.incentive.earned.v1` (from `courier-service`) — accrue incentive.

**Idempotency keys** (preserved):

- `courier:{courier_id}:delivery:{delivery_id}:earning` for delivery fee.
- `courier:{courier_id}:tip:{delivery_id}` for tip.
- `courier:{courier_id}:withdrawal:{withdrawal_id}` for withdrawal.

**Accounting four-layer model preserved:**

- Layer 1 (customer wallet) — unaffected (customer funds).
- Layer 2 (provider side) — captured by `payment-service` against the
  same gateway registry.
- Layer 3 (double-entry ledger) — `ledger-service` still owns the
  chart of accounts and posts every earning / withdrawal as a
  double-entry posting.
- Layer 4 (settlement) — payout runs move through the absorbing
  `payment-service` against `payment-service.payout_methods`.

**Non-functional targets:**

- P95 accrual latency ≤ 200 ms from `delivery.completed.v1`.
- P95 withdrawal enqueue latency ≤ 100 ms.
- 99.95% / 30 days SLO.

---

### 3.4 dispatch

**Absorbed by:** `driver-service`.

**Bounded context:** ride matching, match-attempt ledger, offer
flow.

**Schema rename:** `dispatch` → `driver`.

**Tables absorbed:**

- `dispatch.match_attempts` → `driver.match_attempts`
  (RANGE on `started_at`, monthly; 3-year retention).
- `dispatch.match_offers` → `driver.match_offers`
  (append-only; INSERT only).
- `dispatch.city_config` → `driver.dispatch_city_config`.

**REST endpoints (now on `driver-service`):**

| Method | URI | Auth | Purpose |
|--------|-----|------|---------|
| POST | `/v1/match` | bearer (service) | start a match for a `ride_request_id` |
| GET  | `/v1/match/{id}` | bearer | read a match attempt |
| POST | `/v1/match/{id}/accept` | bearer (driver) | driver accepts an offer |
| POST | `/v1/match/{id}/reject` | bearer (driver) | driver rejects an offer |
| POST | `/v1/match/{id}/cancel` | bearer (service / admin) | cancel a match |
| POST | `/v1/match/{id}/reassign` | bearer (admin) | force reassignment |
| GET  | `/v1/match/metrics` | bearer (admin) | operational counters |

**Events produced:**

- `dispatch.matched.v1` — driver accepted.
- `dispatch.no_driver.v1` — exhausted attempts.
- `dispatch.offer.expired.v1` — single offer window expired.

**Events consumed:**

- `ride.request.created.v1` (from `ride-request-service`).
- `driver.location.updated.v1` (from `driver-service`).
- `driver.availability.online.v1` (from `driver-service`).
- `driver.availability.busy.v1` (from `driver-service`).

**Fairness:** fairness score = `offers_last_hour` ascending then
`rejections_last_hour` ascending, then ETA ascending. Counter
windows are 60 minutes rolling.

**Offer window:** 15 s (configurable via `driver.match.offer_window_seconds`).
Max attempts: 6 (configurable).

**Non-functional targets:**

- P50 time-to-match from `ride.request.created.v1` ≤ 30 s.
- P95 ≤ 60 s.
- 99.95% / 30 days SLO.

---

### 3.5 driver-availability

**Absorbed by:** `driver-service`.

**Bounded context:** driver online state machine.

**Schema rename:** `driver_availability` → `driver`.

**Tables absorbed:**

- `driver_availability.online_state` → `driver.online_state`
  (state: `offline`, `online`, `busy`, `paused`).
- `driver_availability.shifts` → `driver.shifts`
  (planned / actual start, planned end, break intervals).
- `driver_availability.accepted_ride_types` → `driver.accepted_ride_types`.

**REST endpoints (now on `driver-service`):**

| Method | URI | Auth | Purpose |
|--------|-----|------|---------|
| POST | `/v1/drivers/{id}/online` | bearer (driver) | go online |
| POST | `/v1/drivers/{id}/offline` | bearer (driver) | go offline |
| POST | `/v1/drivers/{id}/shift` | bearer (driver) | open / close shift |
| POST | `/v1/drivers/{id}/accepted-types` | bearer (driver) | set accepted ride types |
| GET  | `/v1/drivers/{id}/availability` | bearer | read current availability |

**Events produced:**

- `driver.availability.online.v1`.
- `driver.availability.offline.v1`.
- `driver.availability.busy.v1`.
- `driver.availability.zone.changed.v1`.

**Events consumed:**

- `driver.approved.v1` (from `driver-service`).
- `driver.suspended.v1` (from `driver-service`) — force offline.
- `dispatch.matched.v1` (from `driver-service`) — busy.
- `trip.completed.v1` / `trip.cancelled.v1` (from `trip-service`) — back to online.

**Validation:**

- Refuse offline if `busy` (active trip) — return `BUSY_REFUSE_OFFLINE`.
- Driver MUST be approved in the requested zone to go online there.

---

### 3.6 driver-location

**Absorbed by:** `driver-service`.

**Bounded context:** high-frequency driver location stream.

**Schema rename:** `driver_location` → `driver`.

**Tables absorbed:**

- `driver_location.current_location` → `driver.current_location`.
- `driver_location.locations` → `driver.location_trail`
  (RANGE on `recorded_at`, monthly; pre-create 12 months;
  30-day hot retention, 1-year cold).

**REST endpoints (now on `driver-service`):**

| Method | URI | Auth | Purpose |
|--------|-----|------|---------|
| POST | `/v1/drivers/{id}/locations` | bearer (driver) | ingest a GPS ping |
| GET  | `/v1/drivers/{id}/location` | bearer | last-known location |
| GET  | `/v1/drivers/{id}/locations/recent?minutes=N` | bearer | recent trail |

**Events produced:** `driver.location.updated.v1` (curated 1 Hz per
driver).

**Events consumed:** `driver.availability.online.v1` / `offline.v1`.

**Non-functional targets:**

- 5 Hz ingestion per driver; curated 1 Hz outbound.
- P95 GET latency ≤ 30 ms (cached current_location).

---

### 3.7 driver-incentive

**Absorbed by:** `driver-service`. (Note: `pricing-service` still
owns surge **pricing**; this absorbed capability is the operational
quests / bonuses / guarantees program.)

**Bounded context:** driver incentives — quests, bonuses, surge
guarantees, eligibility.

**Schema rename:** `driver_incentive` → `driver`.

**Tables absorbed:**

- `driver_incentive.quests` → `driver.quests`.
- `driver_incentive.bonuses` → `driver.bonuses`.
- `driver_incentive.guarantees` → `driver.guarantees`.
- `driver_incentive.eligibility_rules` → `driver.incentive_eligibility`.
- `driver_incentive.accruals` → `driver.incentive_accruals`
  (RANGE on `accrued_at`, monthly).

**REST endpoints (now on `driver-service`):**

| Method | URI | Auth | Purpose |
|--------|-----|------|---------|
| POST | `/v1/incentives/quests` | bearer (admin) | create a quest |
| POST | `/v1/incentives/bonuses` | bearer (admin) | create a bonus |
| POST | `/v1/incentives/guarantees` | bearer (admin) | create a guarantee |
| GET  | `/v1/drivers/{id}/incentives` | bearer | list eligible / earned |
| GET  | `/v1/incentives/metrics` | bearer (admin) | operational counters |

**Events produced:**

- `driver.incentive.earned.v1` — accrual written, posted to
  `payment-service` (the absorbing service) for ledger entry.

**Events consumed:**

- `trip.completed.v1` (from `trip-service`) — evaluate eligibility
  for each completed trip.

**Out of scope (unchanged):** surge **pricing** remains owned by
`pricing-service`; this capability only consumes the resulting
surge value.

---

### 3.8 driver-earnings

**Absorbed by:** `payment-service`.

**Bounded context:** driver earnings ledger, withdrawals.

**Schema rename:** `driver_earnings` → `payment`.

**Tables absorbed:**

- `driver_earnings.earnings` → `payment.driver_earnings`
  (RANGE on `accrued_at`, monthly).
  CHECK `type IN ('trip_fare','tip','bonus','guaranteed_topup','penalty','incentive')`.
- `driver_earnings.balances` → `payment.driver_balances`.
- `driver_earnings.withdrawals` → `payment.driver_withdrawals`.
- `driver_earnings.bank_details` → `payment.driver_bank_details`.

**Events produced:**

- `driver.earning.accrued.v1`.
- `driver.withdrawal.requested.v1`.
- `driver.withdrawal.completed.v1`.

**Events consumed:**

- `ride.payment.completed.v1` (from `payment-service`) — accrue.
- `trip.completed.v1` (from `trip-service`) — accrue tips.
- `trip.reward.granted.v1` (from `trip-service`) — accrue guaranteed top-up
  with idempotency key `trip:{trip_id}:reward:driver:grant`.
- `trip.reward.reversed.v1` (from `trip-service`) — reverse top-up.
- `driver.incentive.earned.v1` (from `driver-service`) — accrue incentive.

**Penalty path:** `ride-payment-integration-service` (now absorbed
into `payment-service`) may post penalty entries against the same
ledger with idempotency key
`trip:{trip_id}:penalty:driver:{penalty_id}`.

---

### 3.9 restaurant-order-mgmt

**Absorbed by:** `food-order-service`.

**Bounded context:** restaurant-side queue, accept/reject timer,
prep state.

**Schema rename:** `restaurant_order_mgmt` → `food_order`.

**Tables absorbed:**

- `restaurant_order_mgmt.queue` → `food_order.queue`
  (state: `pending_accept`, `accepted`, `preparing`, `ready`,
  `rejected`).
- `restaurant_order_mgmt.timers` → `food_order.queue_timers`
  (accept-window timer per order; default 5 minutes).
- `restaurant_order_mgmt.rejections` → `food_order.queue_rejections`.

**REST endpoints (now on `food-order-service`):**

| Method | URI | Auth | Purpose |
|--------|-----|------|---------|
| POST | `/v1/orders/{id}/accept` | bearer (operator) | accept |
| POST | `/v1/orders/{id}/reject` | bearer (operator) | reject |
| POST | `/v1/orders/{id}/preparing` | bearer (operator) | mark preparing |
| POST | `/v1/orders/{id}/ready` | bearer (operator) | mark ready |
| GET  | `/v1/queue?branch_id=…` | bearer (operator) | read queue |

**Events produced:**

- `food.order.accepted.v1`.
- `food.order.rejected.v1`.
- `food.order.preparing.v1`.
- `food.order.ready.v1` (consumed by `courier-service` to start
  dispatch).

**Events consumed:**

- `food.order.placed.v1` (from `food-order-service` itself) — add to queue.

**Auto-reject:** timer expiry (default 5 min) → emit
`food.order.rejected.v1` with reason `TIMER_EXPIRED`.

---

### 3.10 restaurant-staff

**Absorbed by:** `restaurant-service`.

**Bounded context:** staff invitations, role assignments, devices.

**Schema rename:** `restaurant_staff` → `restaurant`.

**Tables absorbed:**

- `restaurant_staff.staff` → `restaurant.staff`
  (linked to Keycloak `kc_sub` via UUID, no FK).
- `restaurant_staff.invitations` → `restaurant.staff_invitations`.
- `restaurant_staff.roles` → `restaurant.staff_roles`
  (per restaurant / per branch: `manager`, `cashier`, `kitchen`, `dispatcher`).
- `restaurant_staff.devices` → `restaurant.staff_devices`.

**REST endpoints (now on `restaurant-service`):**

| Method | URI | Auth | Purpose |
|--------|-----|------|---------|
| POST | `/v1/restaurants/{id}/staff/invite` | bearer (manager) | invite staff |
| POST | `/v1/staff/activate` | bearer (invitee) | activate with invitation token |
| POST | `/v1/restaurants/{id}/staff/{staff_id}/roles` | bearer (manager) | assign roles |
| POST | `/v1/restaurants/{id}/staff/{staff_id}/devices` | bearer (staff) | register device |
| POST | `/v1/restaurants/{id}/staff/{staff_id}/deactivate` | bearer (manager) | deactivate |

**Events produced:**

- `staff.invited.v1`.
- `staff.activated.v1`.
- `staff.deactivated.v1`.

**Events consumed:** `restaurant.created.v1` (from `restaurant-service`).

---

### 3.11 restaurant-settlement

**Absorbed by:** `payment-service`.

**Bounded context:** merchant payable, payout schedule, payout runs,
disputes.

**Schema rename:** `restaurant_settlement` → `payment`.

**Tables absorbed:**

- `restaurant_settlement.payables` → `payment.merchant_payables`.
- `restaurant_settlement.payouts` → `payment.merchant_payouts`
  (RANGE on `scheduled_for`, monthly; pre-create 3 months).
  State: `scheduled`, `processing`, `paid`, `failed`, `on_hold`.
- `restaurant_settlement.disputes` → `payment.merchant_disputes`.
- `restaurant_settlement.commissions` → `payment.merchant_commissions`.

**REST endpoints (now on `payment-service`):**

| Method | URI | Auth | Purpose |
|--------|-----|------|---------|
| GET  | `/v1/merchants/{id}/payable` | bearer (merchant) | read payable balance |
| GET  | `/v1/merchants/{id}/statement` | bearer (merchant) | statement |
| POST | `/v1/merchants/{id}/payouts/schedule` | bearer (admin) | schedule a payout |
| POST | `/v1/merchants/{id}/disputes` | bearer (admin) | open a dispute |

**Events produced:**

- `merchant.settlement.accrued.v1`.
- `merchant.payout.scheduled.v1`.
- `merchant.payout.completed.v1`.
- `merchant.dispute.opened.v1` / `merchant.dispute.resolved.v1`.

**Events consumed:**

- `food.payment.completed.v1` (from `payment-service` itself).
- `merchant.suspended.v1` (from `merchant-service`) — hold payouts.

**Reconciliation:** daily reconciliation against `ledger-service`.

---

### 3.12 food-payment-integration

**Absorbed by:** `payment-service`.

**Bounded context:** food payment saga orchestration.

**Schema rename:** `food_payment_integration` → `payment`.

**Tables absorbed:**

- `food_payment_integration.sagas` → `payment.food_sagas`
  (state: `started`, `authorized`, `captured`, `merchant_accrued`,
  `courier_accrued`, `completed`, `failed`).
- `food_payment_integration.idempotency_keys` → `payment.food_idempotency`.

**REST endpoints (now on `payment-service`):**

| Method | URI | Auth | Purpose |
|--------|-----|------|---------|
| GET  | `/v1/food-payment/sagas/{food_order_id}` | bearer (admin / support) | read saga state |
| POST | `/v1/food-payment/sagas/{food_order_id}/retry` | bearer (admin) | manual retry |
| POST | `/v1/food-payment/sagas/{food_order_id}/compensate` | bearer (admin) | manual compensation |

**Events produced:**

- `food.payment.completed.v1`.
- `food.payment.failed.v1`.
- `merchant.settlement.created.v1`.

**Events consumed:**

- `delivery.completed.v1` (from `delivery-service`).
- `payment.captured.v1` (from `payment-service` itself).

**Saga steps:** authorize → capture → courier earning accrual →
merchant settlement accrual → ledger posting → tip accrual.

**Compensation:** partial / full / post-delivery; coordinate with
embedded wallet, ledger, and merchant payouts.

---

### 3.13 ride-payment-integration

**Absorbed by:** `payment-service`.

**Bounded context:** ride payment saga orchestration.

**Schema rename:** `ride_payment_integration` → `payment`.

**Tables absorbed:**

- `ride_payment_integration.sagas` → `payment.ride_sagas`
  (keyed by `trip_id`).
- `ride_payment_integration.idempotency_keys` → `payment.ride_idempotency`.

**REST endpoints (now on `payment-service`):**

| Method | URI | Auth | Purpose |
|--------|-----|------|---------|
| GET  | `/v1/ride-payment/sagas/{trip_id}` | bearer (admin / support) | read saga state |
| POST | `/v1/ride-payment/sagas/{trip_id}/retry` | bearer (admin) | manual retry |
| POST | `/v1/ride-payment/sagas/{trip_id}/compensate` | bearer (admin) | manual compensation |

**Events produced:**

- `ride.payment.completed.v1`.
- `ride.payment.failed.v1`.

**Events consumed:** `trip.completed.v1` (from `trip-service`).

**Saga steps:** capture → driver earning accrual → ledger posting.

**Compensation:** void authorization, refund any capture, release
earning, open support ticket.

---

### 3.14 wallet

**Absorbed by:** `payment-service`.

**Bounded context:** customer wallet balance, holds, top-ups.

**Schema rename:** `wallet` → `payment`.

**Tables absorbed:**

- `wallet.balances` → `payment.wallet_balances`.
- `wallet.holds` → `payment.wallet_holds`
  (state: `held`, `released`, `captured`, `expired`).
- `wallet.topups` → `payment.wallet_topups`.
- `wallet.ledger_entries` → `payment.wallet_entries`
  (RANGE on `created_at`, monthly; pre-create 12 months;
  3-year retention).

**REST endpoints (now on `payment-service`):**

| Method | URI | Auth | Purpose |
|--------|-----|------|---------|
| GET  | `/v1/wallets/me` | bearer (customer) | read balance |
| GET  | `/v1/wallets/me/statement` | bearer (customer) | statement |
| POST | `/v1/wallets/me/topup` | bearer (customer) | top up |
| POST | `/v1/wallets/{id}/holds` | bearer (service) | place a hold |
| POST | `/v1/wallets/{id}/holds/{hold_id}/capture` | bearer (service) | capture a hold |
| POST | `/v1/wallets/{id}/holds/{hold_id}/release` | bearer (service) | release a hold |

**Events produced:**

- `wallet.credited.v1`.
- `wallet.debited.v1`.
- `wallet.held.v1`.
- `wallet.released.v1`.

**Events consumed:**

- `payment.captured.v1` (from `payment-service`).
- `payment.refund.completed.v1` (from `payment-service`).
- `trip.reward.granted.v1` (from `trip-service`) — when
  `trip.reward.user.kind = wallet_credit`.
- `trip.reward.reversed.v1` (from `trip-service`).

**Reconciliation:** daily against `ledger-service`.

**Accounting layer 1 preserved:** the customer-wallet layer remains
inside `payment-service` (this capability), distinct from layer 3
(`ledger-service`).

---

## 4. Cross-cutting compliance (preserved invariants)

- **46-gateway registry**: still owned by `payment-service`. File:
  [`services/payment-service/GATEWAYS.md`](services/payment-service/GATEWAYS.md).
- **Double-entry ledger**: still owned by `ledger-service`. Every
  payment, earning, payout, and wallet entry is a posting.
- **Accounting four-layer model**: layer 1 (customer wallet) inside
  `payment-service`; layer 2 (provider) inside `payment-service`;
  layer 3 (double-entry) inside `ledger-service`; layer 4
  (settlement) inside `payment-service`.
- **SUPER_ADMIN break-glass**: unchanged. `admin-service` keeps the
  role and the break-glass path now calls the survivor service.
- **Immutable notification snapshot chain**: `notification-service`
  continues to subscribe to every topic listed in this hub;
  producers change from removed services to survivors but topics
  and schemas are preserved for the compatibility window.
- **Partitioning conventions**: every RANGE-partitioned table
  listed in §3 carries the same `recorded_at` / `accrued_at` /
  `assigned_at` / `started_at` / `scheduled_for` / `created_at`
  monthly partition key with 12-month pre-creation (3-month for
  payout schedules); retention windows are unchanged.

## 5. Compatibility window

| Concern | Policy | Window |
|---------|--------|--------|
| Old event topics | same topic + same schema version, published by survivor | ≥ 6 months from 2026-08-05 |
| Old REST paths | 301 redirect to canonical survivor path | ≥ 6 months |
| Old schema names | readable view in survivor's schema | ≥ 6 months |
| Old metrics labels | `dispatch_*`, `courier_dispatch_*`, `wallet_*`, `courier_tracking_*`, `driver_location_*`, `driver_earnings_*`, `courier_earnings_*`, `restaurant_settlement_*`, `restaurant_order_mgmt_*`, `ride_payment_integration_*`, `food_payment_integration_*` retained | ≥ 6 months |

## 6. Validation checklist

- `git grep` for any of the 14 removed service names returns hits
  only in this hub, in the absorbing service's "Removed predecessor
  capability" appendix, and in narrative architecture context. No
  operational reference (config key, topic name, RBAC role, REST
  path, schema reference) points at a removed service as if it
  were still running.
- `MICROSERVICES_MAP.md` §"Service Count Summary" reads 44.
- `ADR_INDEX.md` includes ADR-0016.
- All five absorbing services (`courier-service`, `driver-service`,
  `food-order-service`, `restaurant-service`, `payment-service`)
  carry a "Removed predecessor capability" appendix that mirrors
  the corresponding row in §2 above.
- The platform-wide shared docs
  ([`shared/PLATFORM_BASELINE.md`](shared/PLATFORM_BASELINE.md),
  [`shared/CONVENTIONS.md`](shared/CONVENTIONS.md),
  [`shared/DEAL_FEATURE.md`](shared/DEAL_FEATURE.md),
  [`shared/LOOKUPS.md`](shared/LOOKUPS.md),
  [`shared/OSS_DEPENDENCIES.md`](shared/OSS_DEPENDENCIES.md),
  [`shared/INTEGRATION.md`](shared/INTEGRATION.md),
  [`shared/MODULES.md`](shared/MODULES.md)) reflect the 44-service
  catalog and the survivor's responsibilities.
- Workflows ([`workflows/`](workflows/)) reference the survivor
  services only.
- Master plans and indexes reference 44 services only.

## 7. Related

- [ADR-0016: Service Domain Consolidation](architecture/adrs/0016-service-domain-consolidation.md).
- [`architecture/MICROSERVICES_MAP.md`](architecture/MICROSERVICES_MAP.md).
- [`architecture/DATA_OWNERSHIP.md`](architecture/DATA_OWNERSHIP.md).
- [`architecture/EVENT_ARCHITECTURE.md`](architecture/EVENT_ARCHITECTURE.md).
- [`architecture/SERVICE_ISOLATION.md`](architecture/SERVICE_ISOLATION.md).
- [`architecture/DATABASE_ARCHITECTURE.md`](architecture/DATABASE_ARCHITECTURE.md).
- [`services/courier-service/`](services/courier-service/README.md).
- [`services/driver-service/`](services/driver-service/README.md).
- [`services/food-order-service/`](services/food-order-service/README.md).
- [`services/restaurant-service/`](services/restaurant-service/README.md).
- [`services/payment-service/`](services/payment-service/README.md).