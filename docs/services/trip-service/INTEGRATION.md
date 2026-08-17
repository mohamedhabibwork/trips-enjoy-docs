# trip-service — Integration Contract

## 1. Inbound APIs

### 1.1 `POST /v1/trips`

- **Purpose**: Internal — create a trip from a matched ride request.
- **Auth**: Service-to-service JWT (only callable by
  ``trip-service` (ride-request)` via the in-cluster gateway).
- **Idempotency**: `Idempotency-Key` required (the
  `request_id` itself, since creation is one-per-request).
- **Request**:
  ```json
  {
    "request_id": "01HZX9C8W6K0G3V2Y5N1Q4R7PB",
    "service": "trip",
    "customer_id": "01HZX9C5S3B1L7K0P2F8V4T6YDA",
    "driver_id": "01HZX9C8K4D2H1A8N5J7V3R0Q9",
    "city_id": "01HZX9C8X1N4M5K7B8V3R0Q9D2H",
    "zone_id": "01HZX9C8W6K0G3V2Y5N1Q4R7PB",
    "ride_type": "economy",
    "pickup": { "lat": 25.2048, "lon": 55.2708, "address": "Dubai Mall" },
    "dropoff": { "lat": 25.1419, "lon": 55.2282, "address": "Burj Al Arab" },
    "price_quote": { "quote_id": "...", "amount_minor": 4250, "currency": "AED" },
    "correlation_id": "01HZX9C7T0XK2P9F0V6E4B1MZA"
  }
  ```
- **Response (201)**:
  ```json
  {
    "id": "01HZX9C5S3B1L7K0P2F8V4T6YDA",
    "state": "assigned",
    "assigned_at": "2026-07-29T10:42:45.000Z"
  }
  ```
- **Errors**:
  - 400 `VALIDATION_FAILED`
  - 401 `UNAUTHENTICATED`
  - 409 `RIDE_REQUEST_ALREADY_HAS_TRIP` — duplicate create.
  - 503 `DEPENDENCY_TIMEOUT` — downstream profile read failed.

### 1.2 `GET /v1/trips/{id}`

- **Purpose**: Read a trip.
- **Auth**: Bearer JWT (customer, driver, support, safety, admin).
- **Response (200)**:
  ```json
  {
    "id": "01HZX9C5S3B1L7K0P2F8V4T6YDA",
    "state": "in_progress",
    "customer_id": "...",
    "driver_id": "...",
    "ride_type": "economy",
    "pickup": { "...": "..." },
    "dropoff": { "...": "..." },
    "original_dropoff": { "...": "..." },
    "stop": { "lat": 25.17, "lon": 55.25, "address": "..." } | null,
    "price_quote": { "...": "..." },
    "final_fare": null,
    "arrived_at": "2026-07-29T10:48:00.000Z",
    "started_at": "2026-07-29T10:49:00.000Z",
    "completed_at": null,
    "cancelled_at": null
  }
  ```
- **Errors**: 401, 403, 404 standard.
- **Note**: the response redacts precise GPS from `pickup` and
  `dropoff` for the customer after `completed_at + 2h` (NEAREST 100m).

### 1.3 `GET /v1/trips/active`

- **Purpose**: Return the caller's active trip.
- **Auth**: Bearer JWT (customer or driver).
- **Response (200)**: a single trip, or 404 if none.

### 1.4 `POST /v1/trips/{id}/arrive`

- **Purpose**: Driver marks arrival at pickup.
- **Auth**: Bearer JWT (driver).
- **Idempotency**: `Idempotency-Key` required.
- **Response (200)**: trip with `state=arrived`, `arrived_at` set.
- **Errors**: 401, 403, 404, 409 `STATE_INVALID` (not in
  `en_route_pickup`), 409 `NOT_ASSIGNED_DRIVER`.

### 1.5 `POST /v1/trips/{id}/start`

- **Purpose**: Driver starts the trip.
- **Auth**: Bearer JWT (driver).
- **Idempotency**: `Idempotency-Key` required.
- **Response (200)**: trip with `state=in_progress`,
  `started_at` set, `trip.started.v1` emitted (in event consumer
  result; the response is the row only).
- **Errors**: 401, 403, 404, 409.

### 1.6 `POST /v1/trips/{id}/location`

- **Purpose**: Driver pushes a GPS point.
- **Auth**: Bearer JWT (driver); per-driver rate limit 10/s at
  gateway, 5/s internally.
- **Idempotency**: not required (high frequency; we accept the small
  chance of a duplicate on retry).
- **Request**:
  ```json
  {
    "lat": 25.2048,
    "lon": 55.2708,
    "bearing": 87.0,
    "speed_mps": 12.4,
    "accuracy_m": 5.0,
    "recorded_at": "2026-07-29T10:48:30.000Z"
  }
  ```
- **Response (202)**: `{ "accepted": true, "id": "..." }`.
- **Errors**: 401, 403, 404, 429 `RATE_LIMITED`, 400
  `VALIDATION_FAILED` (bad coordinates).

### 1.7 `POST /v1/trips/{id}/stops`

- **Purpose**: Customer adds a mid-trip stop.
- **Auth**: Bearer JWT (customer).
- **Idempotency**: `Idempotency-Key` required.
- **Request**:
  ```json
  {
    "location": { "lat": 25.17, "lon": 55.25, "address": "..." }
  }
  ```
- **Response (201)**: trip with `stop` set.
- **Errors**: 401, 403, 404, 409 `STOP_ALREADY_ADDED`,
  409 `STATE_INVALID` (not `in_progress`).

### 1.8 `POST /v1/trips/{id}/dropoff`

- **Purpose**: Customer changes the dropoff.
- **Auth**: Bearer JWT (customer).
- **Idempotency**: `Idempotency-Key` required.
- **Request**:
  ```json
  {
    "location": { "lat": 25.16, "lon": 55.24, "address": "..." }
  }
  ```
- **Response (200)**: trip with new `dropoff`.
- **Errors**: 401, 403, 404, 422 `DROPOFF_TOO_FAR`,
  409 `STATE_INVALID`.

### 1.9 `POST /v1/trips/{id}/complete`

- **Purpose**: Driver ends the trip; final fare is recomputed.
- **Auth**: Bearer JWT (driver).
- **Idempotency**: `Idempotency-Key` required.
- **Response (200)**:
  ```json
  {
    "id": "...",
    "state": "completed",
    "final_fare": {
      "fare_id": "...",
      "amount_minor": 4400,
      "currency": "AED",
      "breakdown": [ { "label": "base", "amount_minor": 500 },
                     { "label": "distance", "amount_minor": 3200 },
                     { "label": "time", "amount_minor": 700 } ],
      "recompute_eta_seconds": 1100,
      "recompute_distance_meters": 12400
    },
    "completed_at": "2026-07-29T11:01:00.000Z"
  }
  ```
- **Errors**: 401, 403, 404, 409 `STATE_INVALID`,
  503 `DEPENDENCY_TIMEOUT` (``geolocation-service` (ETA/routing)` or
  `pricing-service`).

### 1.10 `POST /v1/trips/{id}/cancel`

- **Purpose**: Driver or admin cancels the trip.
- **Auth**: Bearer JWT (driver, support, admin).
- **Idempotency**: `Idempotency-Key` required.
- **Request**:
  ```json
  {
    "reason": "vehicle_breakdown",
    "no_show": false
  }
  ```
- **Response (200)**:
  ```json
  {
    "id": "...",
    "state": "cancelled",
    "cancellation_actor": "driver",
    "cancellation_penalty": {
      "amount_minor": 2000,
      "currency": "AED",
      "payment_intent_id": "..."
    }
  }
  ```
- **Errors**: 401, 403, 404, 409 `STATE_INVALID`, 503
  `DEPENDENCY_TIMEOUT` (penalty calculation).

### 1.11 `GET /v1/trips/{id}/track`

- **Purpose**: Live tracking view for the apps.
- **Auth**: Bearer JWT (customer, driver, safety).
- **Response (200)**:
  ```json
  {
    "state": "in_progress",
    "driver_location": {
      "lat": 25.2050,
      "lon": 55.2710,
      "recorded_at": "2026-07-29T10:55:30.000Z"
    },
    "remaining_route_polyline": "...",
    "remaining_eta_seconds": 480,
    "last_updated_at": "2026-07-29T10:55:30.000Z"
  }
  ```
- **Errors**: 401, 403, 404.

### 1.13 `GET /v1/trips/{id}/reward`

- **Purpose**: Read the granted rewards (driver + user) for a trip.
- **Auth**: Bearer JWT. Customer (own trip), driver (own trip), or
  `pricing.admin`. The admin role is required to see the `decision_reason`
  field; customer and driver JWTs see only their own line.
- **Response (200)**:
  ```json
  {
    "trip_id": "01HZX…",
    "grants": [
      { "kind": "driver_per_trip_topup", "amount_minor": 320, "currency": "AED", "grant_event_id": "01HZX…" },
      { "kind": "user_per_trip_credit",  "amount_minor": 100, "currency": "AED", "grant_event_id": "01HZX…" }
    ],
    "reversals": [],
    "captured_at": "2026-07-29T10:47:11.183Z"
  }
  ```
- **Errors**: 404 `TRIP_NOT_FOUND`, 403 `FORBIDDEN`.

### 1.14 `POST /v1/trips/{id}/reward/re-evaluate`

- **Purpose**: Force re-evaluation of the reward (e.g. after a config
  update). The new grant references the prior grant via
  `replaces_grant_id`; the outbox writes a single new
  `trip.reward.granted.v1` (with the prior grant's reversal attached
  in the same transaction).
- **Auth**: Bearer JWT. Required scope: `pricing.admin`.
- **Idempotency**: `Idempotency-Key` required.
- **Response (200)**: the new `grants[]` array (same shape as
  `1.13`).
- **Errors**: 403 `FORBIDDEN`, 404 `TRIP_NOT_FOUND`, 409
  `STATE_NOT_COMPLETED`, 422 `IDEMPOTENCY_KEY_REUSED`.

### 1.15 `POST /v1/trips/{id}/reward/reverse`

- **Purpose**: Reverse a previously-granted reward. Emits
  `trip.reward.reversed.v1`; the ledger postings are new rows — never
  `UPDATE` or `DELETE` on `trip.trip_reward` (mirrors the reversal
  rule on `ledger.postings`).
- **Auth**: Bearer JWT. Required scope: `pricing.admin`.
- **Idempotency**: `Idempotency-Key` required.
- **Request body**:
  ```json
  {
    "grant_event_id": "01HZX…",
    "reason": "trip disputed by customer — supporting ticket T-12345"
  }
  ```
- **Response (200)**: the new reversal row (same shape as a `grants[]`
  element but with `actor_type` and `actor_id` set, and a
  `reversal_event_id` linking to the outbox event).
- **Errors**: 403 `FORBIDDEN`, 404 `TRIP_NOT_FOUND` /
  `GRANT_NOT_FOUND`, 422 `REVERSAL_REASON_TOO_SHORT`,
  422 `IDEMPOTENCY_KEY_REUSED`.

## 2. Outbound APIs

| Target | Method | URI | Purpose | Timeout | Retry | Circuit |
|--------|--------|-----|---------|---------|-------|---------|
| ``trip-service` (ride-request)` | GET | /v1/rides/{id} | fetch request | 500ms | 1 | yes |
| `driver-service` | GET | /v1/drivers/{id} | driver profile | 500ms | 1 | yes |
| `customer-service` | GET | /v1/customers/{id} | customer profile | 500ms | 1 | yes |
| ``geolocation-service` (ETA/routing)` | POST | /v1/routing/route | actual route | 1s | 2 | yes |
| ``geolocation-service` (ETA/routing)` | GET | /v1/routing/eta | current ETA | 500ms | 2 | yes |
| `pricing-service` | POST | /v1/quotes | final fare recompute | 1s | 2 | yes |
| `pricing-service` | POST | /v1/penalties/calculate | driver cancel penalty | 500ms | 2 | yes |
| ``trip-service` (safety)` | POST | /v1/safety/incidents | open P1 ticket (heartbeat loss) | 1s | 1 | yes |
| ``payment-service` (driver earnings)` | GET | `/v1/drivers/{id}/period-eligible-earnings?window=hourly\|daily` | eligible earnings for the period-floor evaluation (A3 SRS FR--023) | 800ms | 2 | yes |
| ``pricing-service` (loyalty rules) / `customer-service` (account)` | POST | `/v1/accounts/{customer_id}/credit-trip` | user reward when `trip.reward.user.kind = loyalty_points` (A3 SRS FR--026) | 800ms | 2 | yes |
| **`chat-service`** *(Phase 7.7)* | GET | `/v1/chat/threads?kind=trip_chat&context_id={trip_id}` | read the chat thread id for the trip detail view (the rider / driver app opens the chat via the thread id) | 500ms | 2 | yes |

## 3. Produced Events

### 3.1 `trip.started.v1`

- **Topic**: `trip.started`.
- **Partition key**: `trip_id`.
- **Consumers**: ``payment-service` (ride saga)`, ``pricing-service` (loyalty rules) / `customer-service` (account)`,
  ``trip-service` (safety)`, `notification-service`, ``trip-service` (history)`.
- **Schema**:
  ```json
  {
    "event_id": "...",
    "event_name": "trip.started.v1",
    "aggregate_id": "<request_id>",
    "data": {
      "request_id": "...",
      "service": "trip",
      "customer_id": "...",
      "driver_id": "...",
      "city_id": "...",
      "started_at": "..."
    }
  }
  ```
- **Retry**: outbox, 3; DLQ.

### 3.2 `trip.arrived.v1`

- **Topic**: `trip.arrived`.
- **Partition key**: `trip_id`.
- **Consumers**: `notification-service`, `customer-service` (history).
- **Schema**:
  ```json
  {
    "event_id": "...",
    "event_name": "trip.arrived.v1",
    "aggregate_id": "<trip_id>",
    "data": { "arrived_at": "..." }
  }
  ```

### 3.3 `trip.completed.v1`

- **Topic**: `trip.completed`.
- **Partition key**: `trip_id`.
- **Consumers**: ``payment-service` (ride saga)`,
  ``payment-service` (driver earnings)`, ``driver-service` (incentives)`,
  ``pricing-service` (loyalty rules) / `customer-service` (account)`, ``trip-service` / `food-order-service` / `search-service` (review projections)`, ``trip-service` (history)`,
  `notification-service`, `audit-service`.
- **Schema**:
  ```json
  {
    "event_id": "...",
    "event_name": "trip.completed.v1",
    "aggregate_id": "<request_id>",
    "data": {
      "request_id": "...",
      "service": "trip",
      "customer_id": "...",
      "driver_id": "...",
      "city_id": "...",
      "started_at": "...",
      "completed_at": "...",
      "final_fare": { "amount_minor": 4400, "currency": "AED", "breakdown": [...] },
      "original_quote": { "amount_minor": 4250, "currency": "AED" },
      "distance_meters": 12400,
      "duration_seconds": 720
    }
  }
  ```

### 3.4 `trip.cancelled.v1`

- **Topic**: `trip.cancelled`.
- **Partition key**: `trip_id`.
- **Consumers**: ``payment-service` (ride saga)`,
  `notification-service`, `audit-service`.
- **Schema**:
  ```json
  {
    "event_id": "...",
    "event_name": "trip.cancelled.v1",
    "aggregate_id": "<trip_id>",
    "data": {
      "actor": "driver",
      "reason": "vehicle_breakdown",
      "cancellation_penalty": { "amount_minor": 2000, "currency": "AED" } | null,
      "no_show": false
    }
  }
  ```

### 3.5 `trip.location.updated.v1`

- **Topic**: `trip.location.updated`.
- **Partition key**: `trip_id`.
- **Consumers**: ``trip-service` (safety)` (curated), ``geolocation-service` (ETA/routing)`
  (curated).
- **Schema**:
  ```json
  {
    "event_id": "...",
    "event_name": "trip.location.updated.v1",
    "aggregate_id": "<trip_id>",
    "data": {
      "driver_id": "...",
      "lat": 25.2050,
      "lon": 55.2710,
      "bearing": 87.0,
      "speed_mps": 12.4,
      "recorded_at": "..."
    }
  }
  ```
- **Retry**: outbox, 3; DLQ. Throttled at the producer to avoid
  flooding (1Hz to the curated topic).

### 3.6 `trip.reward.granted.v1`

- **Topic**: `trip.reward.granted`.
- **Partition key**: `trip_id`.
- **Consumers**: ``payment-service` (driver earnings)` (driver top-up accrual,
  with idempotency-key `request:{request_id}:reward:driver:grant`),
  ``payment-service` (wallet)` (user credit, with idempotency-key
  `request:{request_id}:reward:user:grant`), `ledger-service`
  (informational consumer — the operational postings flow through
  the downstream services), `notification-service`
  (driver + customer notification), `audit-service` (7-year
  retention).
- **Schema**:
  ```json
  {
    "event_id": "...",
    "event_name": "trip.reward.granted.v1",
    "aggregate_id": "<trip_id>",
    "data": {
      "trip_id": "...",
      "customer_id": "...",
      "driver_id": "...",
      "city_id": "...",
      "currency": "AED",
      "decision_reason": "per_trip_eligible",
      "grants": [
        {
          "kind": "driver_per_trip_topup",
          "amount_minor": 320,
          "currency": "AED",
          "config_snapshot_id": "..."
        },
        {
          "kind": "user_per_trip_credit",
          "amount_minor": 100,
          "currency": "AED",
          "user_kind": "wallet_credit",
          "config_snapshot_id": "..."
        }
      ],
      "config_snapshot": {
        "id": "...",
        "values": {
          "trip.reward.driver.per_trip_minor.AED": 800,
          "trip.reward.user.per_trip_minor.AED": 100,
          "trip.reward.user.kind": "wallet_credit"
        }
      },
      "captured_at": "2026-07-29T10:47:11.183Z"
    }
  }
  ```
- **Retry**: outbox, 3; DLQ. Throttled-less (one per trip
  completion).
- **Idempotency**: key on `event_id` (inbox dedup). The event id is
  stamped on the `trip_reward.grant_event_id` column so a redelivery
  matches the persisted row.

### 3.7 `trip.reward.reversed.v1`

- **Topic**: `trip.reward.reversed`.
- **Partition key**: `trip_id`.
- **Consumers**: same set as 3.6.
- **Schema**:
  ```json
  {
    "event_id": "...",
    "event_name": "trip.reward.reversed.v1",
    "aggregate_id": "<trip_id>",
    "data": {
      "trip_id": "...",
      "grant_event_id": "01HZX…",
      "reversal_of_event_id": "01HZX…",
      "actor_id": "...",
      "actor_type": "admin",
      "reason": "trip disputed by customer — ticket T-12345",
      "amount_minor": 320,
      "currency": "AED",
      "captured_at": "2026-07-29T11:12:00.000Z"
    }
  }
  ```
- **Retry**: outbox, 3; DLQ.
- **Idempotency**: key on `event_id` (inbox dedup); the downstream
  services treat a redelivered reversal as a no-op (the new row in
  `trip_reward_reversal` is the authoritative record).

## 4. Consumed Events

### 4.1 `ride.request.matched.v1`

- **Producer**: ``trip-service` (request)`.
- **Reason**: create the trip.
- **Handler**: row-lock; if no trip exists for the `request_id`,
  insert; else no-op (idempotent).
- **Deduplication**: inbox on `event_id`; the UNIQUE constraint on
  `request_id` is the second line of defense.
- **Retry**: 3 with backoff; failure → DLQ.

### 4.2 `driver.location.updated.v1` (curated)

- **Producer**: ``driver-service` (location)`.
- **Reason**: tracking; auto-arrival.
- **Handler**: if trip is `en_route_pickup` and the point is within
  the geofence for ≥ 5s (debounce), transition to `arrived`; emit
  `trip.arrived.v1`.
- **Deduplication**: inbox on `event_id`; the same point can be
  re-emitted safely because the state transition is idempotent.
- **Retry**: 3; failure → DLQ.

### 4.3 `dispatch.arrived.v1`

- **Producer**: ``driver-service` (dispatch)`.
- **Reason**: cross-check.
- **Handler**: none (informational). Logged for observability.
- **Deduplication**: inbox on `event_id`.
- **Retry**: 3; failure → DLQ.

### 4.4 `configuration.updated.v1`

- **Producer**: `configuration-service`.
- **Reason**: reload config.
- **Handler**: cache invalidation.
- **Deduplication**: inbox on `event_id`.
- **Retry**: 3; failure → DLQ.

### 4.5 `customer.suspended.v1`

- **Producer**: `customer-service`.
- **Reason**: an existing trip's customer has been suspended after
  `state=completed` but before the reward event was processed.
  Triggers a reward re-evaluation that zeroes the user-side grant.
- **Handler**: re-evaluate eligibility filter (FR--025 (d)) against
  the latest customer status; emit a new `trip.reward.granted.v1`
  that references the prior via `replaces_grant_id`.
- **Deduplication**: inbox on `event_id`.
- **Retry**: 3; failure → DLQ.

### 4.6 `payment.captured.v1` (informational)

- **Producer**: `payment-service` via ``payment-service` (ride saga)`.
- **Reason**: confirm the trip's payment capture completed. No
  side-effect; the existing `trip.completed.v1` outbox remains the
  primary grant trigger.
- **Handler**: refresh the trip's `state_reason` to `paid` for
  observability; no outbox write.
- **Deduplication**: inbox on `event_id`.
- **Retry**: 3; failure → DLQ.

### 4.7 `driver.suspended.v1`

- **Producer**: `driver-service`.
- **Reason**: a trip's driver has been suspended (consumed by
  ``driver-service` (incentives)` already; this service consumes for the
  same eligibility-filter consistency on the driver side).
- **Handler**: re-evaluate eligibility filter (FR--025 (a)(b)) against
  the latest driver status; emit `trip.reward.reversed.v1` for any
  pending driver-side grants.
- **Deduplication**: inbox on `event_id`.
- **Retry**: 3; failure → DLQ.

### 4.8 `chat.message.reported.v1` *(Phase 7.7 — In-App Chat)*

- **Producer**: `chat-service`.
- **Reason**: a rider or driver reported a message in the trip chat.
  When the reason is `safety` or `abuse`, this service must escalate
  to a SOS-style P1 safety ticket (the chat payload becomes evidence
  for the existing ride-safety flow).
- **Handler**: when `data.reason IN ('safety', 'abuse')`, call
  ``trip-service` (safety)`` `POST /v1/safety/incidents` to open a P1
  ticket (or attach the chat-message evidence to an existing one
  for the same `trip_id`); the chat message remains in the immutable
  audit chain.
- **Deduplication**: inbox on `event_id`.
- **Retry**: 3; failure → DLQ.

### 4.9 `chat.thread.closed.v1` *(Phase 7.7 — In-App Chat)*

- **Producer**: `chat-service`.
- **Reason**: informational. The chat thread for this trip closed
  (on `trip.completed.v1` / `trip.cancelled.v1`). This service
  consumes for observability; no state change.
- **Handler**: log only.
- **Deduplication**: inbox on `event_id`.
- **Retry**: 3; failure → DLQ.

## 5. Reliability

- **Timeouts**: outbound 500ms–1s; DB 30s.
- **Retries**: bounded 3, exponential backoff with jitter.
- **Circuit breakers**: per downstream.
- **Bulkheads**: per downstream connection pool.
- **Outbox**: `trip.outbox` table; poller publishes with 3 attempts
  before DLQ.
- **Inbox**: `trip.inbox` table on every consumed event.
- **DLQ**: per topic (e.g. `trip.started.dlq`).
- **Reconciliation**: a daily job in `reporting-service` checks for
  trips in `in_progress` for more than 12 hours (anomalous) and
  trips with no `completed_at` despite the driver being offline.

## 6. Correlation IDs

Every request carries `X-Correlation-Id`. The service:
- Logs the id on every line within the request scope.
- Propagates it to outbound calls.
- Embeds it in every emitted event and Kafka header.
- Reads it from the inbound event envelope and uses the same id for
  the resulting state changes.

## 7. Distributed Tracing

OpenTelemetry. One root span per inbound request. Each outbound HTTP
call, DB query, and Kafka publish is a child span. `traceparent` is
propagated. The location stream is sampled at 1% to keep the trace
volume manageable while preserving observability. Sample rate: 100%
for errors, 10% for successes in production.


## Downstream isolation

This section describes how this service handles failures in
its upstream and downstream services. The platform-wide
isolation playbook — including the per-class (CRITICAL /
DEGRADABLE / BEST-EFFORT) behavior, the dependency matrix,
and the configuration knobs — is in
[`../../architecture/SERVICE_ISOLATION.md`](../../architecture/SERVICE_ISOLATION.md).
The canonical error-code catalog and propagation rules are in
[`../../architecture/DOWNSTREAM_ERROR_CATALOG.md`](../../architecture/DOWNSTREAM_ERROR_CATALOG.md).

When this service's own code fails unexpectedly, it returns
`500 INTERNAL_ERROR`. When an error originates from another
service, this service follows the propagation rules in
[`DOWNSTREAM_ERROR_CATALOG.md` 5](../../architecture/DOWNSTREAM_ERROR_CATALOG.md)
(forward verbatim, translate, degrade, or reject) and includes
a `downstream` block identifying the original source.

### Upstream services this service depends on

| Upstream | Class | Behavior on failure |
|---|---|---|
| [`admin-service`](../admin-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [`audit-service`](../audit-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [`configuration-service`](../configuration-service/README.md) | DEGRADABLE | degrade (cache / default / flag) |
| [`customer-service`](../customer-service/README.md) | CRITICAL | 503 `DEPENDENCY_UNAVAILABLE` |
| [``driver-service` (dispatch)`](../driver-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [``driver-service` (availability)`](../driver-service/README.md) | CRITICAL | 503 `DEPENDENCY_UNAVAILABLE` |
| [``payment-service` (driver earnings)`](../payment-service/README.md) | **CRITICAL** (reward grant) / BEST-EFFORT (profile read) | on reward-grant path → 503 `DEPENDENCY_UNAVAILABLE` if the period-earnings read fails after retry; on profile read → log WARN |
| [``driver-service` (incentives)`](../driver-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [``driver-service` (location)`](../driver-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [`driver-service`](../driver-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [``geolocation-service` (ETA/routing)`](../geolocation-service/README.md) | DEGRADABLE | degrade (cache / default / flag) |
| [`pricing-service`](../pricing-service/README.md) · [`customer-service`](../customer-service/README.md) (loyalty rules / account) | **CRITICAL** (when `trip.reward.user.kind = loyalty_points`) / BEST-EFFORT (otherwise) | on the loyalty-points user reward path → 503 `DEPENDENCY_UNAVAILABLE` if the credit-trip call fails after retry |
| [`notification-service`](../notification-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [``payment-service` (wallet)`](../payment-service/README.md) | **CRITICAL** (when `trip.reward.user.kind = wallet_credit`) / BEST-EFFORT (otherwise) | on the wallet-credit user reward path → 503 `DEPENDENCY_UNAVAILABLE` if the credit call fails after retry |
| [`payment-service`](../payment-service/README.md) | CRITICAL | 503 `DEPENDENCY_UNAVAILABLE` |
| [`pricing-service`](../pricing-service/README.md) | CRITICAL | 503 `DEPENDENCY_UNAVAILABLE` |
| [`trip-service`](../trip-service/README.md) · [`food-order-service`](../food-order-service/README.md) · [`search-service`](../search-service/README.md) (review projections) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [``trip-service` (history)`](../trip-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [``payment-service` (ride saga)`](../payment-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [``trip-service` (ride-request)`](../trip-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [``trip-service` (safety)`](../trip-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |

### Downstream services that depend on this service

| Downstream | Class (from its perspective) |
|---|---|
| [``customer-service` (addresses)`](../customer-service/README.md) | _see [`SERVICE_ISOLATION.md` 2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`api-gateway`](../api-gateway/README.md) | _see [`SERVICE_ISOLATION.md` 2](../../architecture/SERVICE_ISOLATION.md)_ |
| [``driver-service` (dispatch)`](../driver-service/README.md) | _see [`SERVICE_ISOLATION.md` 2](../../architecture/SERVICE_ISOLATION.md)_ |
| [``driver-service` (availability)`](../driver-service/README.md) | _see [`SERVICE_ISOLATION.md` 2](../../architecture/SERVICE_ISOLATION.md)_ |
| [``payment-service` (driver earnings)`](../payment-service/README.md) | _see [`SERVICE_ISOLATION.md` 2](../../architecture/SERVICE_ISOLATION.md)_ |
| [``driver-service` (incentives)`](../driver-service/README.md) | _see [`SERVICE_ISOLATION.md` 2](../../architecture/SERVICE_ISOLATION.md)_ |
| [``driver-service` (location)`](../driver-service/README.md) | _see [`SERVICE_ISOLATION.md` 2](../../architecture/SERVICE_ISOLATION.md)_ |
| [``geolocation-service` (ETA/routing)`](../geolocation-service/README.md) | _see [`SERVICE_ISOLATION.md` 2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`fraud-risk-service`](../fraud-risk-service/README.md) | _see [`SERVICE_ISOLATION.md` 2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`geolocation-service`](../geolocation-service/README.md) | _see [`SERVICE_ISOLATION.md` 2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`pricing-service`](../pricing-service/README.md) · [`customer-service`](../customer-service/README.md) (loyalty rules / account) | _see [`SERVICE_ISOLATION.md` 2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`notification-service`](../notification-service/README.md) | _see [`SERVICE_ISOLATION.md` 2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`trip-service`](../trip-service/README.md) · [`food-order-service`](../food-order-service/README.md) · [`search-service`](../search-service/README.md) (review projections) | _see [`SERVICE_ISOLATION.md` 2](../../architecture/SERVICE_ISOLATION.md)_ |
| [``trip-service` (history)`](../trip-service/README.md) | _see [`SERVICE_ISOLATION.md` 2](../../architecture/SERVICE_ISOLATION.md)_ |
| [``payment-service` (ride saga)`](../payment-service/README.md) | _see [`SERVICE_ISOLATION.md` 2](../../architecture/SERVICE_ISOLATION.md)_ |
| [``trip-service` (ride-request)`](../trip-service/README.md) | _see [`SERVICE_ISOLATION.md` 2](../../architecture/SERVICE_ISOLATION.md)_ |
| [``trip-service` (safety)`](../trip-service/README.md) | _see [`SERVICE_ISOLATION.md` 2](../../architecture/SERVICE_ISOLATION.md)_ |
| [``geolocation-service` (zones)`](../geolocation-service/README.md) | _see [`SERVICE_ISOLATION.md` 2](../../architecture/SERVICE_ISOLATION.md)_ |

### Per-downstream configuration

Per-downstream timeout / bulkhead / circuit / retry / fallback
configuration lives in the service's application config
(Kotlin: `application.yml` under `platform.outbounds.*`;
Go: `internal/outbounds/manifest.yaml`). The shared library
(`platform-spring-boot-bulkhead` for Kotlin, `internal/bulkhead`
for Go) reads the manifest and wires up the isolation pattern.

### Error envelope

Every error response uses the platform envelope defined in
[`../../shared/CONVENTIONS.md` 1](../../shared/CONVENTIONS.md)
(RFC 7807 + `downstream` block). The codes this service emits
are in 1 of this document; the canonical catalog is in
[`DOWNSTREAM_ERROR_CATALOG.md`](../../architecture/DOWNSTREAM_ERROR_CATALOG.md).


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

## Conductor Workers

This service runs Conductor workers for the following workflows per
[ADR-0018](../../architecture/adrs/0018-workflow-engine-conductor.md) and
[`shared/CONDUCTOR_WORKFLOWS.md`](../../shared/CONDUCTOR_WORKFLOWS.md).
Workers are colocated in this service's binary; SDK: **conductor-kotlin v3.x**.

| Workflow ID | Tasks owned | Idempotency-Key namespace |
|---|---|---|
| Workflow ID | Tasks owned | Idempotency-Key namespace |
|---|---|---|
| `wf.phase7.reward_grant.v1` | reward_grant_emit_event | `request:{request_id}:reward:producer:grant` |
| `wf.phase7.reward_reversal.v1` | reward_reversal_emit_event | `request:{request_id}:reward:producer:reverse` |
| `wf.phase75.deal_rider.v1` | rider_deal_offer_initiate + rider_deal_state_transition | `deal:{deal_id}:rider:*` |


### Kafka signal mapping

| Topic | Signal | Triggers |
|---|---|---|
| `conductor.workflow.completed.v1` | `Conductor terminal` | (none — trip-service is the orchestrator) |


### Compensation responsibilities

This service implements the following compensation tasks; see
[`shared/CONDUCTOR_WORKFLOWS.md`](../../shared/CONDUCTOR_WORKFLOWS.md) 4 for
ordering rules.

| Forward task | Compensation task | Reversibility |
|---|---|---|
| `reward_grant_emit_event` | `reward_grant_publish_kafka_void` | idempotent — outbox absorbs duplicate publish |


### Configuration keys

- `conductor.server.url` — set by Helm per env (e.g. `https://conductor.prod.trips-enjoy.com`)
- `conductor.task.<task_name>.timeout_seconds` — default 30s
- `conductor.task.<task_name>.retry_count` — default 3
- `conductor.worker.heartbeat_interval_seconds` — default 5s
- `conductor.kafka.bridge.url` — for `conductor-kafka-bridge` integration

### Operational references

- Runbook: [`shared/CONDUCTOR_WORKFLOWS.md`](../../shared/CONDUCTOR_WORKFLOWS.md) 8
- Observability: [`shared/CONDUCTOR_WORKFLOWS.md`](../../shared/CONDUCTOR_WORKFLOWS.md) 7
- Master task registry: [`MASTER_TASK.md`](../../MASTER_TASK.md) 7-9
