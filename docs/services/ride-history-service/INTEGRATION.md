# ride-history-service — Integration Contract

## 1. Inbound APIs

### 1.1 `GET /v1/history/trips`

- **Purpose**: The caller's trip history.
- **Auth**: Bearer JWT (customer).
- **Query params**: `cursor`, `limit` (default 20, max 100),
  `date_from`, `date_to`, `ride_type`, `status` (optional).
- **Response (200)**:
  ```json
  {
    "items": [
      {
        "trip_id": "01HZX9C5S3B1L7K0P2F8V4T6YDA",
        "city_id": "01HZX9C8X1N4M5K7B8V3R0Q9D2H",
        "ride_type": "economy",
        "pickup": { "lat": ..., "lon": ..., "address": "..." },
        "dropoff": { "lat": ..., "lon": ..., "address": "..." },
        "distance_meters": 12400,
        "duration_seconds": 720,
        "fare_amount_minor": 4400,
        "currency": "AED",
        "payment_status": "paid",
        "rating": 5,
        "review_comment": "Great ride!",
        "trip_started_at": "2026-07-29T10:48:00.000Z",
        "trip_completed_at": "2026-07-29T11:01:00.000Z",
        "driver_name_cached": "Ahmed K."
      }
    ],
    "next_cursor": "eyJ…",
    "has_more": false
  }
  ```

### 1.2 `GET /v1/history/trips/{id}`

- **Purpose**: One trip entry.
- **Auth**: Bearer JWT (owner / driver / admin).
- **Response (200)**: same shape as a single item above.

### 1.3 `GET /v1/drivers/{driver_id}/trips`

- **Purpose**: The driver's trip history.
- **Auth**: Bearer JWT (driver / admin).
- **Query params**: same as `GET /v1/history/trips`.

### 1.4 `GET /v1/admin/trips`

- **Purpose**: All trips (admin).
- **Auth**: Bearer JWT (admin).
- **Query params**: `cursor`, `limit`, `customer_id` (optional),
  `driver_id` (optional), `date_from`, `date_to`, `ride_type`,
  `status`.

## 2. Outbound APIs

| Target | Method | URI | Purpose | Timeout | Retry | Circuit |
|--------|--------|-----|---------|---------|-------|---------|
| `customer-service` | GET | /v1/customers/{id} | name (cached) | 200ms | 1 | yes |
| `driver-service` | GET | /v1/drivers/{id} | name (cached) | 200ms | 1 | yes |

## 3. Produced Events

None (read-only service).

### 3.1 `ride_history.audit.read_model_rebuilt.v1`

- **Producer**: this service.
- **Topic**: `platform.ride_history.audit`.
- **Trigger**: The read model is rebuilt (e.g. after a backfill or schema migration).
- **Schema version**: 1.
- **Partition key**: `rebuild_id`.
- **Consumers**: `admin-service`, `audit-service`.
- **Schema**:

  ```json
  {
    "event_id": "01HZX9C8W6K0G3V2Y5N1Q4R7PB",
    "event_name": "ride_history.audit.read_model_rebuilt.v1",
    "occurred_at": "2026-07-29T10:42:11.183Z",
    "schema_version": 1,
    "producer": "this-service",
    "tenant_id": "global",
    "correlation_id": "01HZX9C7T0XK2P9F0V6E4B1MZA",
    "aggregate_type": "Aggregate",
    "aggregate_id": "01HZX…",
    "data": { }
  }
  ```

- **Retry**: outbox, 3 attempts.
- **DLQ**: `platform.ride_history.audit.dlq`.


### 3.2 `ride_history.export.requested.v1`

- **Producer**: this service.
- **Topic**: `platform.ride_history.export`.
- **Trigger**: A user requests an export of their ride history (GDPR-style).
- **Schema version**: 1.
- **Partition key**: `export_id`.
- **Consumers**: `notification-service` (notify when ready), `audit-service`.
- **Schema**:

  ```json
  {
    "event_id": "01HZX9C8W6K0G3V2Y5N1Q4R7PB",
    "event_name": "ride_history.export.requested.v1",
    "occurred_at": "2026-07-29T10:42:11.183Z",
    "schema_version": 1,
    "producer": "this-service",
    "tenant_id": "global",
    "correlation_id": "01HZX9C7T0XK2P9F0V6E4B1MZA",
    "aggregate_type": "Aggregate",
    "aggregate_id": "01HZX…",
    "data": { }
  }
  ```

- **Retry**: outbox, 3 attempts.
- **DLQ**: `platform.ride_history.export.dlq`.

### 3.3 `ride_history.cache.invalidated.v1`

- **Producer**: this service.
- **Topic**: `platform.ride_history.cache`.
- **Trigger**: a hot-cache entry is invalidated (e.g. on
  GDPR erasure of a customer / driver, or on a data
  correction).
- **Schema version**: 1.
- **Partition key**: `identity_id` (the affected user).
- **Consumers**: `customer-service`, `driver-service`,
  `notification-service`, `audit-service`,
  `analytics-service`.
- **Schema**:

  ```json
  {
    "event_id": "01HZX9C8W6K0G3V2Y5N1Q4R7PB",
    "event_name": "ride_history.cache.invalidated.v1",
    "occurred_at": "2026-07-29T10:42:11.183Z",
    "schema_version": 1,
    "producer": "ride-history-service",
    "tenant_id": "global",
    "correlation_id": "01HZX9C7T0XK2P9F0V6E4B1MZA",
    "aggregate_type": "RideHistoryCache",
    "aggregate_id": "cache:01HZX…",
    "data": {
      "identity_id": "01HZX…",
      "reason": "gdpr_erasure" | "data_correction" | "manual_invalidation",
      "scope": "customer" | "driver" | "all"
    }
  }
  ```

- **Retry**: outbox, 3 attempts.
- **DLQ**: `platform.ride_history.cache.dlq`.



## 4. Consumed Events

### 4.1 `trip.completed.v1`

- **Producer**: `trip-service`.
- **Reason**: project the trip.
- **Handler**: upsert the entry with `payment_status='pending'`.
- **Deduplication**: inbox on `event_id`; UNIQUE on `trip_id`.
- **Retry**: 3; failure → DLQ.

### 4.2 `ride.payment.completed.v1`

- **Producer**: `ride-payment-integration-service`.
- **Reason**: add the fare.
- **Handler**: update the entry's `fare_amount_minor`, `currency`,
  `payment_status='paid'`, `payment_completed_at`.
- **Deduplication**: inbox on `event_id`.
- **Retry**: 3; failure → DLQ.

### 4.3 `review.submitted.v1`

- **Producer**: `review-rating-service`.
- **Reason**: add the rating.
- **Handler**: update the entry's `rating`, `review_comment`,
  `review_submitted_at`.
- **Deduplication**: inbox on `event_id`.
- **Retry**: 3; failure → DLQ.

### 4.4 `configuration.updated.v1`

- **Producer**: `configuration-service`.
- **Reason**: reload config.
- **Handler**: cache invalidation.
- **Deduplication**: inbox on `event_id`.
- **Retry**: 3; failure → DLQ.

## 5. Reliability

- **Timeouts**: outbound 200ms; DB 30s.
- **Retries**: bounded 3, exponential backoff with jitter.
- **Circuit breakers**: per downstream.
- **Bulkheads**: per downstream connection pool.
- **Outbox**: not used (we don't produce events); the read model
  is the output.
- **Inbox**: `ride_history.inbox` table; on every consumed event.
- **DLQ**: per topic.
- **Reconciliation**: a daily job in `reporting-service` checks
  for entries with `payment_status='pending'` older than 1 hour
  (anomalous) and pages on-call.

## 6. Correlation IDs

Every request carries `X-Correlation-Id`. The service:
- Logs the id on every line within the request scope.
- Propagates it to outbound calls.
- Reads it from the inbound event envelope and uses the same id
  for the resulting projection.

## 7. Distributed Tracing

OpenTelemetry. One root span per request. The projection
handlers are spans. `traceparent` is propagated. Sample rate: 100%
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
[`DOWNSTREAM_ERROR_CATALOG.md` §5](../../architecture/DOWNSTREAM_ERROR_CATALOG.md)
(forward verbatim, translate, degrade, or reject) and includes
a `downstream` block identifying the original source.

### Upstream services this service depends on

| Upstream | Class | Behavior on failure |
|---|---|---|
| [`admin-service`](../admin-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [`configuration-service`](../configuration-service/README.md) | DEGRADABLE | degrade (cache / default / flag) |
| [`customer-service`](../customer-service/README.md) | CRITICAL | 503 `DEPENDENCY_UNAVAILABLE` |
| [`driver-service`](../driver-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [`payment-service`](../payment-service/README.md) | CRITICAL | 503 `DEPENDENCY_UNAVAILABLE` |
| [`review-rating-service`](../review-rating-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [`ride-payment-integration-service`](../ride-payment-integration-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [`ride-request-service`](../ride-request-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [`trip-service`](../trip-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |

### Downstream services that depend on this service

| Downstream | Class (from its perspective) |
|---|---|
| [`customer-service`](../customer-service/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`driver-earnings-service`](../driver-earnings-service/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`reporting-service`](../reporting-service/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`ride-payment-integration-service`](../ride-payment-integration-service/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`trip-service`](../trip-service/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |

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

