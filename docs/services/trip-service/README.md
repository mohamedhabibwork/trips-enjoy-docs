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

- The ride request itself — `ride-request-service`.
- The match attempt — `dispatch-service`.
- Driver online state — `driver-availability-service`.
- Driver location stream ownership — `driver-location-service`
  (we consume).
- Pricing — `pricing-service` (we re-quote on completion only).
- Payment — `ride-payment-integration-service` + `payment-service`.
- Driver earnings — `driver-earnings-service`.

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
- Cooperate with `ride-safety-service` for SOS and live location.
- **Evaluate guaranteed-reward eligibility at `state=completed`**
  and emit `trip.reward.granted.v1` (or `trip.reward.reversed.v1` on
  reversal): per-trip driver minimum, hourly driver floor, daily
  driver floor, per-trip user credit. Both sides are
  configurable per city and ride_type.
- **Capture the reward `config_snapshot`** with the trip so the
  decision is reproducible for audit and dispute resolution.
- Cooperate with `driver-earnings-service` and `wallet-service` to
  settle the granted rewards as separate balanced postings (driver
  top-up uses chart-of-account `6302_guaranteed_minimum`; user
  credit uses `2100_customer_credit_liability`).

## 4. Explicitly NOT Owned

- Driver online state and zone — `driver-availability-service`.
- Driver location history beyond the trip's trail (the broader
  stream is `driver-location-service`).
- Pricing logic — `pricing-service`.
- The ride request aggregate — `ride-request-service`.
- The driver earning balance and `driver_payable` ledger postings —
  `driver-earnings-service` (this service only emits the
  `trip.reward.granted.v1` event; the accrual and the ledger postings
  are owned downstream).
- The customer wallet balance and `customer_credit_liability` ledger
  postings — `wallet-service`.
- Reward program configuration — `configuration-service` (plain
  numeric thresholds) and `admin-service` (per-city / per-OD-pair
  variants). This service only **consumes** the config snapshot.

## 5. Actors

| Actor | Type | Access |
|-------|------|--------|
| Driver app | system | write (state transitions + location); read own trip |
| Customer app | system | read own trip; write (cancel before pickup, add stop) |
| `ride-request-service` | system | creates the trip via `ride.request.matched.v1` |
| `driver-location-service` | system | emits curated location stream (we consume for tracking) |
| `ride-payment-integration-service` | system | reads trip to settle |
| `ride-safety-service` | system | reads trip context for SOS |
| `pricing-service` | system | provides the final fare quote |
| `notification-service` | system | consumer of trip events |
| `admin-service` | system | read + force-cancel with audit |

## 6. Dependencies

### Synchronous (REST)

- `ride-request-service` — read the request to get the
  `price_quote` and customer/driver refs — SLO 100ms — circuit
  breaker: yes.
- `driver-service` — read driver profile (name, photo, rating) — SLO
  100ms — circuit breaker: yes.
- `customer-service` — read customer profile (display name, phone) —
  SLO 100ms — circuit breaker: yes.
- `eta-routing-service` — recompute ETA / route on completion — SLO
  300ms — circuit breaker: yes.
- `pricing-service` — re-quote on completion — SLO 300ms — circuit
  breaker: yes.
- `ride-request-service` — for re-issuing dispatch on driver cancel
  (we emit `trip.cancelled.v1`; the request service triggers a new
  search).

### Asynchronous (events consumed)

- `ride.request.matched.v1` from `ride-request-service` — create the
  trip — duplicate handling: inbox dedup.
- `driver.location.updated.v1` (curated) from `driver-location-service`
  — drive the trip tracking view and the auto-arrival geofence —
  duplicate handling: inbox dedup; idempotent by `trip_id` +
  `event_id`.
- `dispatch.arrived.v1` from `dispatch-service` — informational
  cross-check; the driver app is the source of truth for "arrived".
- `configuration.updated.v1` from `configuration-service` — reload
  cancellation penalties, route thresholds.

## 7. Technology Assumptions

- Runtime: Node 20 (TypeScript).
- Database: PostgreSQL 18, per-service schema `trip`.
- Cache: Redis for the customer's "active trip" lookup and the
  driver's "active trip" lookup.
- Event broker: Kafka.
- Maps: via `eta-routing-service` only; we do not call the map
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
| `trip.started.v1` | on `state=in_progress` | `ride-payment-integration-service`, `loyalty-service`, `ride-safety-service`, `notification-service`, `ride-history-service` |
| `trip.arrived.v1` | on `state=arrived` | `notification-service`, `customer-service` (history) |
| `trip.completed.v1` | on `state=completed` | `ride-payment-integration-service`, `driver-earnings-service`, `driver-incentive-service`, `loyalty-service`, `review-rating-service`, `ride-history-service`, `notification-service`, `audit-service` |
| `trip.cancelled.v1` | on `state=cancelled` (any) | `ride-payment-integration-service`, `notification-service`, `audit-service` |
| `trip.location.updated.v1` | every accepted location point | `ride-safety-service` (curated), `eta-routing-service` (curated) |
| `trip.reward.granted.v1` | on `state=completed` when at least one reward (driver top-up OR user credit) qualifies | `driver-earnings-service` (driver top-up accrual), `wallet-service` (user credit), `ledger-service` (informational), `notification-service` (driver + customer notice), `audit-service` (7-year retention) |
| `trip.reward.reversed.v1` | on admin re-evaluation or trip correction that reverses a previously-granted reward | same as above (downstream services treat the reversal as a NEW posting — never UPDATE/DELETE on `ledger.postings`, per the accounting four-layer truth model) |

## 11. Events Consumed

| Event | Producer | Reason | Handler |
|-------|----------|--------|---------|
| `ride.request.matched.v1` | `ride-request-service` | create the trip | persist `state=assigned` |
| `driver.location.updated.v1` | `driver-location-service` | tracking + auto-arrival | persist point; check geofence |
| `dispatch.arrived.v1` | `dispatch-service` | cross-check | none (informational) |
| `configuration.updated.v1` | `configuration-service` | reload config | cache invalidation |

## 12. External Integrations

- `eta-routing-service` for the final-fare recompute.
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

Seed data: a default city, a fake `eta-routing-service` returning
`{eta_seconds, distance_meters, route_polyline}` for a hard-coded
path.

## 18. Deployment

- Image: `registry.uber.io/trip-service:<sha>`.
- Replicas: 8 (HPA to 40).
- Resource limits: see `architecture/DEPLOYMENT_ARCHITECTURE.md`.
- Migrations: K8s Job before rolling deploy.
- The `trip.location_points` partition maintenance runs nightly.


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

- **Depends on**: [`admin-service`](../admin-service/README.md), [`audit-service`](../audit-service/README.md), [`configuration-service`](../configuration-service/README.md), [`customer-service`](../customer-service/README.md), [`dispatch-service`](../dispatch-service/README.md), [`driver-availability-service`](../driver-availability-service/README.md), [`driver-earnings-service`](../driver-earnings-service/README.md), [`driver-incentive-service`](../driver-incentive-service/README.md), [`driver-location-service`](../driver-location-service/README.md), [`driver-service`](../driver-service/README.md), [`eta-routing-service`](../eta-routing-service/README.md), [`loyalty-service`](../loyalty-service/README.md), [`notification-service`](../notification-service/README.md), [`payment-service`](../payment-service/README.md), [`pricing-service`](../pricing-service/README.md), [`review-rating-service`](../review-rating-service/README.md), [`ride-history-service`](../ride-history-service/README.md), [`ride-payment-integration-service`](../ride-payment-integration-service/README.md), [`ride-request-service`](../ride-request-service/README.md), [`ride-safety-service`](../ride-safety-service/README.md)
- **Depended on by**: [`address-service`](../address-service/README.md), [`api-gateway`](../api-gateway/README.md), [`dispatch-service`](../dispatch-service/README.md), [`driver-availability-service`](../driver-availability-service/README.md), [`driver-earnings-service`](../driver-earnings-service/README.md), [`driver-incentive-service`](../driver-incentive-service/README.md), [`driver-location-service`](../driver-location-service/README.md), [`eta-routing-service`](../eta-routing-service/README.md), [`fraud-risk-service`](../fraud-risk-service/README.md), [`geolocation-service`](../geolocation-service/README.md), [`loyalty-service`](../loyalty-service/README.md), [`notification-service`](../notification-service/README.md), [`review-rating-service`](../review-rating-service/README.md), [`ride-history-service`](../ride-history-service/README.md), [`ride-payment-integration-service`](../ride-payment-integration-service/README.md), [`ride-request-service`](../ride-request-service/README.md), [`ride-safety-service`](../ride-safety-service/README.md), [`zone-service`](../zone-service/README.md)

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
- [`../../workflows/ACCOUNTING_WORKFLOWS.md`](../../workflows/ACCOUNTING_WORKFLOWS.md) — accounting view (guaranteed-reward settlement for driver + customer at trip completion; see §"Guaranteed Rewards — Driver Top-Up + Customer Credit")
