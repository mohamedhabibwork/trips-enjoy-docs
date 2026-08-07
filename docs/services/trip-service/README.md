# trip-service

## 1. Purpose

`trip-service` owns the **trip aggregate**: the actual ride from
driver acceptance through to completion (or cancellation). It is the
authoritative record of where the driver and the customer were, when
the trip moved between states, and what the final fare was. It is the
source of truth that downstream services — payment, earnings, safety,
history — react to.

## 2. Bounded Context

Bounded context: **Trip Aggregate**.

In scope:

- The trip state machine: `assigned → en_route_pickup → arrived →
  in_progress → completed | cancelled`.
- Live trip location updates (driver streaming GPS every 4–6s).
- Mid-trip changes (additional stops, change of dropoff within
  policy).
- The "complete trip" handshake with the driver app and the
  final-fare computation against the original quote.
- The "started" / "completed" / "cancelled" events that downstream
  services consume.
- A real-time read API used by the customer and driver apps to show
  trip status.

Out of scope (explicitly):

- The ride request itself — ``trip-service` (ride-request)`.
- The match attempt — ``driver-service` (dispatch)`.
- Driver online state — ``driver-service` (availability)`.
- Driver location stream ownership — ``driver-service` (location)`
  (we consume).
- Pricing — `pricing-service` (we re-quote on completion only).
- Payment — ``payment-service` (ride saga)` + `payment-service`.
- Driver earnings — ``payment-service` (driver earnings)`.

## 3. Responsibilities

- Create the `trip` aggregate when `ride.request.matched.v1` is
  received.
- Maintain the trip's state machine; reject invalid transitions.
- Accept high-frequency location updates from the driver app and
  persist a recent trail (with TTL).
- Detect "arrived at pickup" automatically via geofence (with manual
  override).
- Compute the final fare on completion (re-quote with the actual
  route) and emit `trip.completed.v1`.
- Handle mid-trip customer-driven additions and changes.
- Support driver-cancellation (with a penalty policy that calls
  `pricing-service`).
- Cooperate with ``trip-service` (safety)` for SOS and live location.
- **Evaluate guaranteed-reward eligibility at `state=completed`**
  and emit `trip.reward.granted.v1` (or `trip.reward.reversed.v1` on
  reversal): per-trip driver minimum, hourly driver floor, daily
  driver floor, per-trip user credit. Both sides are
  configurable per city and ride_type.
- **Capture the reward `config_snapshot`** with the trip so the
  decision is reproducible for audit and dispute resolution.
- Cooperate with ``payment-service` (driver earnings)` and ``payment-service` (wallet)` to
  settle the granted rewards as separate balanced postings (driver
  top-up uses chart-of-account `6302_guaranteed_minimum`; user
  credit uses `2100_customer_credit_liability`).

## 4. Explicitly NOT Owned

- Driver online state and zone — ``driver-service` (availability)`.
- Driver location history beyond the trip's trail (the broader
  stream is ``driver-service` (location)`).
- Pricing logic — `pricing-service`.
- The ride request aggregate — ``trip-service` (ride-request)`.
- The driver earning balance and `driver_payable` ledger postings —
  ``payment-service` (driver earnings)` (this service only emits the
  `trip.reward.granted.v1` event; the accrual and the ledger postings
  are owned downstream).
- The customer wallet balance and `customer_credit_liability` ledger
  postings — ``payment-service` (wallet)`.
- Reward program configuration — `configuration-service` (plain
  numeric thresholds) and `admin-service` (per-city / per-OD-pair
  variants). This service only **consumes** the config snapshot.

## 5. Actors

| Actor | Type | Access |
|-------|------|--------|
| Driver app | system | write (state transitions + location); read own trip |
| Customer app | system | read own trip; write (cancel before pickup, add stop) |
| ``trip-service` (ride-request)` | system | creates the trip via `ride.request.matched.v1` |
| ``driver-service` (location)` | system | emits curated location stream (we consume for tracking) |
| ``payment-service` (ride saga)` | system | reads trip to settle |
| ``trip-service` (safety)` | system | reads trip context for SOS |
| `pricing-service` | system | provides the final fare quote |
| `notification-service` | system | consumer of trip events |
| `admin-service` | system | read + force-cancel with audit |

## 6. Dependencies

### Synchronous (REST)

- ``trip-service` (ride-request)` — read the request to get the
  `price_quote` and customer/driver refs — SLO 100ms — circuit
  breaker: yes.
- `driver-service` — read driver profile (name, photo, rating) — SLO
  100ms — circuit breaker: yes.
- `customer-service` — read customer profile (display name, phone) —
  SLO 100ms — circuit breaker: yes.
- ``geolocation-service` (ETA/routing)` — recompute ETA / route on completion — SLO
  300ms — circuit breaker: yes.
- `pricing-service` — re-quote on completion — SLO 300ms — circuit
  breaker: yes.
- ``trip-service` (ride-request)` — for re-issuing dispatch on driver cancel
  (we emit `trip.cancelled.v1`; the request service triggers a new
  search).

### Asynchronous (events consumed)

- `ride.request.matched.v1` from ``trip-service` (ride-request)` — create the
  trip — duplicate handling: inbox dedup.
- `driver.location.updated.v1` (curated) from ``driver-service` (location)`
  — drive the trip tracking view and the auto-arrival geofence —
  duplicate handling: inbox dedup; idempotent by `trip_id` +
  `event_id`.
- `dispatch.arrived.v1` from ``driver-service` (dispatch)` — informational
  cross-check; the driver app is the source of truth for "arrived".
- `configuration.updated.v1` from `configuration-service` — reload
  cancellation penalties, route thresholds.

## 7. Technology Assumptions

- Runtime: Node 20 (TypeScript).
- Database: PostgreSQL 18, per-service schema `trip`.
- Cache: Redis for the customer's "active trip" lookup and the
  driver's "active trip" lookup.
- Event broker: Kafka.
- Maps: via ``geolocation-service` (ETA/routing)` only; we do not call the map
  provider directly.
- Real-time push to apps: via `notification-service` (push) plus a
  websocket gateway in front of this service for "live tracking".

## 8. Database Ownership

- Schema: `trip` (one schema, owned exclusively by this service).
- Migrations: `services/trip-service/migrations/`.
- Soft delete: **no** for active trips (state is the source of
  truth); yes for archived snapshot rows beyond retention.
- Partitioning: yes — `trip.location_points` is range-partitioned by
  day.

## 9. API Overview

| Method | URI | Auth | Purpose |
|--------|-----|------|---------|
| POST | /v1/trips | system | create from `ride.request.matched.v1` (internal) |
| GET | /v1/trips/{id} | bearer (owner/driver/admin) | read |
| GET | /v1/trips/active | bearer (customer or driver) | the caller's active trip |
| POST | /v1/trips/{id}/arrive | bearer (driver) | driver at pickup |
| POST | /v1/trips/{id}/start | bearer (driver) | trip begins |
| POST | /v1/trips/{id}/location | bearer (driver) | push GPS point |
| POST | /v1/trips/{id}/stops | bearer (customer) | add a stop mid-trip |
| POST | /v1/trips/{id}/dropoff | bearer (customer or driver) | change dropoff |
| POST | /v1/trips/{id}/complete | bearer (driver) | trip ends |
| POST | /v1/trips/{id}/cancel | bearer (driver or admin) | cancel mid-trip |
| GET | /v1/trips/{id}/track | bearer (owner) | live tracking view (short-poll) |
| GET | /v1/trips/{id}/reward | bearer (admin or owner) | read the granted rewards for the trip (driver + user lines) |
| POST | /v1/trips/{id}/reward/re-evaluate | bearer (`pricing.admin`) | force re-evaluation (e.g. after config update) |
| POST | /v1/trips/{id}/reward/reverse | bearer (`pricing.admin`) | emit `trip.reward.reversed.v1` with required `reason` |

Full contracts in `INTEGRATION.md`.

## 10. Events Produced

| Event | Trigger | Consumers |
|-------|---------|-----------|
| `trip.started.v1` | on `state=in_progress` | ``payment-service` (ride saga)`, ``pricing-service` (loyalty rules) / `customer-service` (account)`, ``trip-service` (safety)`, `notification-service`, ``trip-service` (history)` |
| `trip.arrived.v1` | on `state=arrived` | `notification-service`, `customer-service` (history) |
| `trip.completed.v1` | on `state=completed` | ``payment-service` (ride saga)`, ``payment-service` (driver earnings)`, ``driver-service` (incentives)`, ``pricing-service` (loyalty rules) / `customer-service` (account)`, ``trip-service` / `food-order-service` / `search-service` (review projections)`, ``trip-service` (history)`, `notification-service`, `audit-service` |
| `trip.cancelled.v1` | on `state=cancelled` (any) | ``payment-service` (ride saga)`, `notification-service`, `audit-service` |
| `trip.location.updated.v1` | every accepted location point | ``trip-service` (safety)` (curated), ``geolocation-service` (ETA/routing)` (curated) |
| `trip.reward.granted.v1` | on `state=completed` when at least one reward (driver top-up OR user credit) qualifies | ``payment-service` (driver earnings)` (driver top-up accrual), ``payment-service` (wallet)` (user credit), `ledger-service` (informational), `notification-service` (driver + customer notice), `audit-service` (7-year retention) |
| `trip.reward.reversed.v1` | on admin re-evaluation or trip correction that reverses a previously-granted reward | same as above (downstream services treat the reversal as a NEW posting — never UPDATE/DELETE on `ledger.postings`, per the accounting four-layer truth model) |

## 11. Events Consumed

| Event | Producer | Reason | Handler |
|-------|----------|--------|---------|
| `ride.request.matched.v1` | ``trip-service` (ride-request)` | create the trip | persist `state=assigned` |
| `driver.location.updated.v1` | ``driver-service` (location)` | tracking + auto-arrival | persist point; check geofence |
| `dispatch.arrived.v1` | ``driver-service` (dispatch)` | cross-check | none (informational) |
| `configuration.updated.v1` | `configuration-service` | reload config | cache invalidation |

## 12. External Integrations

- ``geolocation-service` (ETA/routing)` for the final-fare recompute.
- `pricing-service` for the final fare.
- No direct map provider.

## 13. Configuration

| Key | Type | Source | Notes |
|-----|------|--------|-------|
| `trip.arrival.geofence_meters` | int | configuration-service | default 50 |
| `trip.location.stream_sample_hz` | float | configuration-service | default 0.2 (every 5s) |
| `trip.cancellation.driver_penalty_minor.{currency}` | money | configuration-service | per-city |
| `trip.cancellation.driver_penalty_window_seconds` | int | configuration-service | default 120s |
| `trip.location.retention_seconds` | int | configuration-service | default 7200 (2h) |
| `trip.mid_trip.max_stops` | int | configuration-service | default 1 |
| `trip.mid_trip.dropoff_change_radius_meters` | int | configuration-service | default 5000 |
| `trip.fare.recompute_allow_pct` | int | configuration-service | default 5 (% deviation allowed vs original) |
| `trip.reward.driver.per_trip_minor.{currency}` | money | configuration-service | default `800` minor units; the per-trip driver floor |
| `trip.reward.driver.hourly_floor_minor.{currency}` | money | configuration-service | default `5000` minor units per rolling 60-min window |
| `trip.reward.driver.daily_floor_minor.{currency}` | money | configuration-service | default `40000` minor units per rolling 24-h window |
| `trip.reward.driver.min_window_minutes` | int | configuration-service | default `60` (the hourly-window size) |
| `trip.reward.user.per_trip_minor.{currency}` | money | configuration-service | default `100` minor units; the per-trip user credit |
| `trip.reward.user.kind` | enum | configuration-service | `wallet_credit` (default) / `loyalty_points` / `none` |
| `trip.reward.driver.eligibility.min_rating` | float | configuration-service | default `4.0`; driver-side reward eligibility |
| `trip.reward.driver.eligibility.min_completed_trips` | int | configuration-service | default `5`; driver-side reward eligibility within the window |
| `trip.reward.user.eligibility.min_completed_trips` | int | configuration-service | default `0` (user-reward eligibility is opt-in by default) |
| `trip.reward.eval.timeout_ms` | int | configuration-service | default `500`; the reward-evaluation budget |
| `trip.reward.user.kind.{city_id}` | enum | configuration-service | per-city override for the user reward kind |

## 14. Security

- AuthN: Bearer JWT.
- AuthZ: customer can read only their own trip (`customer_id == sub`).
  Driver can read only their own trip (`driver_id == sub`). Admin
  override requires `X-Audit-Reason`.
- The driver's `POST /v1/trips/{id}/location` is rate-limited per
  driver.
- Secrets: Vault at `secret/trip/{env}/*`.
- PII: pickup/dropoff/address stored on the trip; location trail
  beyond the trip's end is deleted by the partition drop.
- **Reward evaluation** (`POST /v1/trips/{id}/reward/re-evaluate`
  and `POST /v1/trips/{id}/reward/reverse`) requires the
  `pricing.admin` role — driver or customer JWTs are rejected with
  403 `FORBIDDEN`. The reward config snapshot is cached client-side
  only with admin-scoped tokens.
- The `trip.reward.granted.v1` payload MUST NOT include the
  customer's contact info or the driver's GPS trail; only the
  trip_id, actor type (`driver` / `customer`), `amount_minor`,
  `currency`, `decision_reason` (configurable enumeration), and the
  captured `config_snapshot_id`.

## 15. Observability

- Logs: JSON to stdout with `correlation_id`, `trip_id`,
  `driver_id`, `customer_id`, `route`, `latency_ms`, `status`.
- Metrics: `trips_created_total{city}`,
  `trips_completed_total{city, ride_type}`,
  `trips_cancelled_total{actor, reason}`,
  `trip_match_to_arrive_seconds` (histogram),
  `trip_arrive_to_start_seconds` (histogram),
  `trip_duration_seconds` (histogram),
  `trip_distance_meters` (histogram),
  `trip_location_points_total`, `trip_state_transitions_total`,
  `trip_rewards_granted_total{actor,kind}` (counter),
  `trip_reward_grant_minor_total{actor,kind}` (counter),
  `trip_reward_reversals_total{actor,reason}` (counter),
  `trip_reward_eval_seconds` (histogram).
- Traces: OpenTelemetry, root span per request; the driver's
  location stream uses a span per batch.
- Health: `/health`, `/ready` (DB + Kafka + Redis + downstream
  readiness), `/started`.

## 16. Scalability

- Replicas: 8 (default); HPA on `trip_location_points_total` and on
  CPU.
- Hot path: `POST /v1/trips/{id}/location` is the highest-frequency
  write. We rate-limit at the gateway per driver (10/s) and
  internally (5/s); the in-memory counter absorbs bursts.
- Read replicas: 1 read replica in the same region for the customer's
  "active trip" lookup.

## 17. Local Development

```bash
docker compose up trip-service postgres kafka redis
bun run --filter trip-service dev
```

Seed data: a default city, a fake ``geolocation-service` (ETA/routing)` returning
`{eta_seconds, distance_meters, route_polyline}` for a hard-coded
path.

## 18. Deployment

- Image: `registry.uber.io/trip-service:<sha>`.
- Replicas: 8 (HPA to 40).
- Resource limits: see `architecture/DEPLOYMENT_ARCHITECTURE.md`.
- Migrations: K8s Job before rolling deploy.
- The `trip.location_points` partition maintenance runs nightly.


---

## Appendix A — Removed predecessor capability

The capability that used to live in ``trip-service` (ride-request)` (ride
booking aggregate), ``trip-service` (scheduled)` (scheduled rides),
``trip-service` (safety)` (SOS / share / incident reports),
``trip-service` (history)` (denormalised read model of trips, payments,
reviews), and the **trip-review slice** of ``trip-service` / `food-order-service` / `search-service` (review projections)`
is now absorbed into this service. The canonical source is
[`../../MIGRATION_HUB.md`](../../MIGRATION_HUB.md) 3.8 (ride-
request), 3.9 (scheduled-ride), 3.10 (ride-safety),
3.11 (ride-history), 3.12 (review-rating trip projection).

### A.1 Bounded context (post-merger)

Trip aggregate + ride-request aggregate + scheduled-ride jobs +
ride-safety state + trip-history read model + trip review
projection. The service is the **only** writer of the `trip`
schema. Out of scope: pricing (owned by `pricing-service`),
payment intents (owned by `payment-service`), driver online state
(owned by `driver-service`).

### A.2 Absorbed responsibilities (from `trip-service` (ride-request))

- Create and maintain `trip.ride_requests` (state:
  `requested`, `matched`, `cancelled`, `expired`).
- Emit `ride.request.created.v1`, `ride.request.matched.v1`,
  `ride.request.cancelled.v1`, `ride.request.expired.v1`.
- Consume `customer.created.v1` (own consumer via event), and
  `dispatch.matched.v1` (from `driver-service`).

### A.3 Absorbed responsibilities (from `trip-service` (scheduled))

- Maintain `trip.scheduled_rides`.
- Materialise scheduled rides into `trip.ride_requests` at the
  configured lead time.
- Emit `scheduled_ride.due.v1`.

### A.4 Absorbed responsibilities (from `trip-service` (safety))

- Trip safety state, SOS triggers, share-trip links, incident
  reports.
- Emit `ride.safety.sos.v1`, `ride.safety.share.v1`,
  `ride.safety.incident.v1`.

### A.5 Absorbed responsibilities (from `trip-service` (history))

- Denormalised read model of trips, payments, reviews
  (`trip.history_views`).
- Expose `GET /v1/customers/{id}/trips`, `GET /v1/drivers/{id}/trips`,
  `GET /v1/trips/{id}/summary`.

### A.6 Absorbed responsibilities (trip-review projection)

- Owns the trip-review slice: write / read for trip reviews;
  emits `review.submitted.v1` (preserved topic) and a new
  `trip.review.read.v1` for consumers that want the trip-scoped
  slice directly.
- Rating aggregate (`trip.rating_aggregates`) feeds back into the
  driver profile in `driver-service`.

### A.7 Absorbed REST endpoints (highlights)

| Method | URI | Auth | Purpose |
|--------|-----|------|---------|
| POST | `/v1/rides` | bearer (customer) | create ride request |
| GET  | `/v1/rides/{id}` | bearer | read ride request |
| POST | `/v1/rides/{id}/cancel` | bearer (customer) | cancel |
| POST | `/v1/rides/scheduled` | bearer (customer) | schedule |
| GET  | `/v1/rides/scheduled/{id}` | bearer | read |
| DELETE | `/v1/rides/scheduled/{id}` | bearer (customer) | cancel scheduled |
| POST | `/v1/trips/{id}/sos` | bearer (rider / driver) | SOS |
| POST | `/v1/trips/{id}/share` | bearer (rider) | share link |
| POST | `/v1/trips/{id}/incident` | bearer (driver) | incident report |
| GET  | `/v1/trips/{id}/safety` | bearer | safety state |
| GET  | `/v1/customers/{id}/trips` | bearer (customer) | history |
| GET  | `/v1/drivers/{id}/trips` | bearer (driver) | history |
| GET  | `/v1/trips/{id}/summary` | bearer | summary |
| POST | `/v1/trips/{id}/review` | bearer (customer) | submit trip review |
| GET  | `/v1/trips/{id}/reviews` | bearer | read trip reviews |

### A.8 Compatibility window

For at least six calendar months from 2026-08-05:

- `ride.request.*.v1`, `scheduled_ride.due.v1`,
  `ride.safety.*.v1`, `review.submitted.v1`,
  `review.aggregated.v1` are published under the same topic
  names and schema versions by this service.
- `/v1/rides*`, `/v1/trips/{id}/safety*`,
  `/v1/customers/{id}/trips`, `/v1/drivers/{id}/trips` continue to
  be served from this service.
- Old schema names `ride_request.*`, `scheduled_ride.*`,
  `ride_safety.*`, `ride_history.*` and the trip slice of
  `review.*` remain readable as views in the `trip` schema.

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

- **Depends on**: [`admin-service`](../admin-service/README.md), [`audit-service`](../audit-service/README.md), [`configuration-service`](../configuration-service/README.md), [`customer-service`](../customer-service/README.md), [`driver-service`](../driver-service/README.md), [`geolocation-service`](../geolocation-service/README.md), [`notification-service`](../notification-service/README.md), [`payment-service`](../payment-service/README.md), [`pricing-service`](../pricing-service/README.md)
- **Depended on by**: [`api-gateway`](../api-gateway/README.md), [`courier-service`](../courier-service/README.md), [`driver-service`](../driver-service/README.md), [`fraud-risk-service`](../fraud-risk-service/README.md), [`geolocation-service`](../geolocation-service/README.md), [`notification-service`](../notification-service/README.md), [`payment-service`](../payment-service/README.md), [`reporting-service`](../reporting-service/README.md), [`restaurant-service`](../restaurant-service/README.md)

> Full dependency map in [`../README.md`](../README.md) and [`../../architecture/MICROSERVICES_MAP.md`](../../architecture/MICROSERVICES_MAP.md).

### Platform-wide

- [`../../shared/README.md`](../../shared/README.md) — `platform-spring-boot-starter` shared library (the single source of cross-cutting code for all Spring Boot services in the platform)
- [`../../shared/PLATFORM_BASELINE.md`](../../shared/PLATFORM_BASELINE.md) — single source for PostgreSQL 18, Kafka, Keycloak, Redis, OpenTelemetry, Vault, deployment, DR (do not restate these in this README)
- [`../../architecture/SERVICE_ISOLATION.md`](../../architecture/SERVICE_ISOLATION.md) — **how this service behaves when a downstream is down** (timeout / bulkhead / circuit / retry / fallback, by class: CRITICAL / DEGRADABLE / BEST-EFFORT)
- [`../../architecture/DOWNSTREAM_ERROR_CATALOG.md`](../../architecture/DOWNSTREAM_ERROR_CATALOG.md) — **canonical error-code catalog + propagation rules** (the `downstream` block, forward/translate/degrade/reject)
- [`../RECOMMENDATIONS.md`](../RECOMMENDATIONS.md) — platform-wide technology map (language, framework, version baseline, admin/RBAC pattern)
- [`../../README.md`](../../README.md) — services overview (the catalog of all 20 services)
- [`../../../main.md`](../../../main.md) — top-level platform specification (architecture, Keycloak, PostgreSQL 18, messaging, observability baseline)
- [`../../shared/OSS_DEPENDENCIES.md`](../../shared/OSS_DEPENDENCIES.md) — **open-source dependencies & license attribution** (platform-wide OSS projects + per-language OSS libraries with SPDX IDs; per-service bundle index; license compatibility matrix)
- [`../../shared/TYPE_CATALOG.md`](../../shared/TYPE_CATALOG.md) — **platform-wide type vocabulary** — ride_type keys (Enjoy Economy / VIP / XL / Comfort / Assist) catalogued in [3](../../shared/TYPE_CATALOG.md#3-ride-types). trip-service echoes `ride_type` from `ride.request.matched.v1`; ownership and validation live in `pricing-service` and `configuration-service`.

### Workflows this service participates in

- [`../../workflows/RIDE_WORKFLOWS.md`](../../workflows/RIDE_WORKFLOWS.md) — end-to-end ride flows
- [`../../workflows/ACCOUNTING_WORKFLOWS.md`](../../workflows/ACCOUNTING_WORKFLOWS.md) — accounting view (guaranteed-reward settlement for driver + customer at trip completion; see "Guaranteed Rewards — Driver Top-Up + Customer Credit")
