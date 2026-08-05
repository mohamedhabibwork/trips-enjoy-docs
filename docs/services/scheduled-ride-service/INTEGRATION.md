# scheduled-ride-service — Integration Contract

## 1. Inbound APIs

### 1.1 `POST /v1/scheduled-rides`

- **Purpose**: Create a scheduled ride.
- **Auth**: Bearer JWT (customer).
- **Idempotency**: `Idempotency-Key` header required.
- **Request**:
  ```json
  {
    "pickup": { "lat": 25.2048, "lon": 55.2708, "address": "Dubai Mall" },
    "dropoff": { "lat": 25.1419, "lon": 55.2282, "address": "Burj Al Arab" },
    "ride_type": "economy",
    "payment_method_id": "01HZX9C5S3B1L7K0P2F8V4T6YDA",
    "scheduled_for": "2026-07-30T07:00:00.000Z",
    "contact_phone": "+971 50 123 4567",
    "notes": "Airport pickup"
  }
  ```
- **Response (201)**:
  ```json
  {
    "id": "01HZX9C8W6K0G3V2Y5N1Q4R7PB",
    "state": "pending",
    "scheduled_for": "2026-07-30T07:00:00.000Z",
    "lead_time_minutes": 15,
    "pre_quote": { "amount_minor": 4250, "currency": "AED" } | null,
    "created_at": "2026-07-29T10:42:11.183Z"
  }
  ```
- **Errors**:
  - 400 `VALIDATION_FAILED`
  - 401 `UNAUTHENTICATED`
  - 422 `OUTSIDE_TIME_WINDOW`
  - 422 `ZONE_UNSERVED`
  - 422 `IDEMPOTENCY_KEY_REUSED`

### 1.2 `GET /v1/scheduled-rides/{id}`

- **Purpose**: Read a scheduled ride.
- **Auth**: Bearer JWT (owner or admin).
- **Response (200)**: same shape as POST.

### 1.3 `GET /v1/scheduled-rides`

- **Purpose**: List the caller's upcoming scheduled rides.
- **Auth**: Bearer JWT (customer).
- **Query params**: `cursor`, `limit` (default 20, max 100).
- **Response (200)**:
  ```json
  {
    "items": [ { "...": "..." } ],
    "next_cursor": "eyJ…",
    "has_more": false
  }
  ```

### 1.4 `POST /v1/scheduled-rides/{id}/cancellation`

- **Purpose**: Cancel a scheduled ride.
- **Auth**: Bearer JWT (owner).
- **Idempotency**: `Idempotency-Key` header required.
- **Request**:
  ```json
  { "reason": "changed_plans" }
  ```
- **Response (200)**:
  ```json
  {
    "id": "...",
    "state": "cancelled",
    "cancelled_at": "..."
  }
  ```
- **Errors**: 401, 403, 404, 409 `STATE_INVALID`, 409
  `OUTSIDE_FREE_WINDOW`, 422 `IDEMPOTENCY_KEY_REUSED`.

### 1.5 `PATCH /v1/scheduled-rides/{id}`

- **Purpose**: Limited update (notes, contact phone).
- **Auth**: Bearer JWT (owner).
- **Idempotency**: `Idempotency-Key` header required.
- **Request**:
  ```json
  { "notes": "New note", "contact_phone": "+971 50 ..." }
  ```
- **Response (200)**: the updated job.
- **Errors**: 401, 403, 404, 409 `STATE_INVALID`.

## 2. Outbound APIs

| Target | Method | URI | Purpose | Timeout | Retry | Circuit |
|--------|--------|-----|---------|---------|-------|---------|
| `customer-service` | GET | /v1/customers/{id} | validate customer | 500ms | 1 | yes |
| `pricing-service` | POST | /v1/quotes | pre-quote | 1s | 2 | yes (best-effort) |
| `zone-service` | POST | /v1/zones/coverage | validate zone | 500ms | 2 | yes |

## 3. Produced Events

### 3.1 `scheduled_ride.due.v1`

- **Topic**: `scheduled_ride.due`.
- **Partition key**: `scheduled_ride_id`.
- **Consumers**: `ride-request-service`.
- **Schema**:
  ```json
  {
    "event_id": "...",
    "event_name": "scheduled_ride.due.v1",
    "aggregate_id": "<scheduled_ride_id>",
    "data": {
      "scheduled_ride_id": "...",
      "customer_id": "...",
      "city_id": "...",
      "zone_id": "...",
      "ride_type": "economy",
      "pickup": { "...": "..." },
      "dropoff": { "...": "..." },
      "scheduled_for": "...",
      "payment_method_id": "..."
    }
  }
  ```
- **Retry**: outbox, 3; DLQ.

### 3.2 `scheduled_ride.failed.v1`

- **Topic**: `scheduled_ride.failed`.
- **Partition key**: `scheduled_ride_id`.
- **Consumers**: `notification-service`, `support-service`.
- **Schema**:
  ```json
  {
    "event_id": "...",
    "event_name": "scheduled_ride.failed.v1",
    "aggregate_id": "<scheduled_ride_id>",
    "data": {
      "scheduled_ride_id": "...",
      "customer_id": "...",
      "reason": "customer_suspended" | "no_driver" | "internal_error",
      "attempts": 3
    }
  }
  ```

### 3.3 `scheduled_ride.cancelled.v1`

- **Topic**: `scheduled_ride.cancelled`.
- **Partition key**: `scheduled_ride_id`.
- **Consumers**: `notification-service`, `audit-service`.
- **Schema**:
  ```json
  {
    "event_id": "...",
    "event_name": "scheduled_ride.cancelled.v1",
    "aggregate_id": "<scheduled_ride_id>",
    "data": {
      "actor": "customer" | "admin" | "system" | "safety",
      "reason": "..."
    }
  }
  ```

## 4. Consumed Events

### 4.1 `customer.suspended.v1`

- **Producer**: `customer-service`.
- **Reason**: auto-cancel.
- **Handler**: find the customer's `pending` jobs; transition to
  `cancelled` with `cancellation_actor='safety'`; emit
  `scheduled_ride.cancelled.v1`.
- **Deduplication**: inbox on `event_id`.
- **Retry**: 3; failure → DLQ.

### 4.2 `configuration.updated.v1`

- **Producer**: `configuration-service`.
- **Reason**: reload config.
- **Handler**: cache invalidation.
- **Deduplication**: inbox on `event_id`.
- **Retry**: 3; failure → DLQ.

### 4.3 `configuration.updated.v1`

- **Producer**: `configuration-service`.
- **Reason**: Schedule rules changed.
- **Handler**: Reload config.
- **Deduplication / Retry / Failure**: inbox keyed by
  `event_id` / 3 with exponential backoff / DLQ.


### 4.4 `scheduled_ride.due.v1`

- **Producer**: `internal`.
- **Reason**: A scheduled ride is now due (cron trigger).
- **Handler**: Re-emit to dispatch.
- **Deduplication / Retry / Failure**: inbox keyed by
  `event_id` / 3 with exponential backoff / DLQ.



## 5. Reliability

- **Timeouts**: outbound 500ms–1s; DB 30s.
- **Retries**: bounded 3, exponential backoff with jitter.
- **Circuit breakers**: per downstream.
- **Bulkheads**: per downstream connection pool.
- **Outbox**: `scheduled_ride.outbox` table.
- **Inbox**: `scheduled_ride.inbox` table.
- **DLQ**: per topic.
- **Reconciliation**: a daily job in `reporting-service` checks
  for `pending` jobs past `scheduled_for + grace` and marks them
  `expired`.

## 6. Correlation IDs

Every request carries `X-Correlation-Id`. The service:
- Logs the id on every line within the request scope.
- Propagates it to outbound calls.
- Embeds it in every emitted event and Kafka header.
- Reads it from the inbound event envelope and uses the same id
  for the resulting state changes.

## 7. Distributed Tracing

OpenTelemetry. One root span per request. The scheduler sweep
emits a parent span with child spans per fired job. `traceparent`
is propagated. Sample rate: 100% for errors, 10% for successes
in production.


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
[`DOWNSTREAM_ERROR_CATALOG.md` §5](../../architecture/DOWNSTREAM_ERROR_CATALOG.md)
(forward verbatim, translate, degrade, or reject) and includes
a `downstream` block identifying the original source.

### Upstream services this service depends on

| Upstream | Class | Behavior on failure |
|---|---|---|
| [`admin-service`](../admin-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [`audit-service`](../audit-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [`configuration-service`](../configuration-service/README.md) | DEGRADABLE | degrade (cache / default / flag) |
| [`customer-service`](../customer-service/README.md) | CRITICAL | 503 `DEPENDENCY_UNAVAILABLE` |
| [`dispatch-service`](../dispatch-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [`notification-service`](../notification-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [`pricing-service`](../pricing-service/README.md) | CRITICAL | 503 `DEPENDENCY_UNAVAILABLE` |
| [`ride-request-service`](../ride-request-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [`support-service`](../support-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [`zone-service`](../zone-service/README.md) | DEGRADABLE | degrade (cache / default / flag) |

### Downstream services that depend on this service

| Downstream | Class (from its perspective) |
|---|---|
| [`ride-request-service`](../ride-request-service/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |

### Per-downstream configuration

Per-downstream timeout / bulkhead / circuit / retry / fallback
configuration lives in the service's application config
(Kotlin: `application.yml` under `platform.outbounds.*`;
Go: `internal/outbounds/manifest.yaml`). The shared library
(`platform-spring-boot-bulkhead` for Kotlin, `internal/bulkhead`
for Go) reads the manifest and wires up the isolation pattern.

### Error envelope

Every error response uses the platform envelope defined in
[`../../shared/CONVENTIONS.md` §1](../../shared/CONVENTIONS.md)
(RFC 7807 + `downstream` block). The codes this service emits
are in §1 of this document; the canonical catalog is in
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
- [`../../README.md`](../../README.md) — services overview (the catalog of all 58 services)
- [`../../../main.md`](../../../main.md) — top-level platform specification (architecture, Keycloak, PostgreSQL 18, messaging, observability baseline)

