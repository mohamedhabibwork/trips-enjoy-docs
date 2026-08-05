# courier-dispatch-service — Integration Contract

## 1. Inbound APIs

### 1.1 `POST /v1/dispatches`

- **Purpose**: Start a dispatch for a `food_order_id`. Typically
  called by the `food-order-service` consumer after
  `food.order.ready.v1`; also exposed for admin to manually start
  a re-dispatch.
- **Auth**: Bearer JWT — service-to-service (`courier-dispatch.write`)
  OR admin (`courier.admin`).
- **Idempotency**: `Idempotency-Key` header required.
- **Request**:
  ```json
  {
    "food_order_id": "01HZX9C5S3B1L7K0P2F8V4T6YDA",
    "branch_id": "01HZX9B4X2C1N5K7P0F6V3T8YDB",
    "restaurant_id": "01HZX8A2Z1X0M4K6P8F2V1T7YDC",
    "city_id": "01HZX7Y0X9W8M3K5P7F1V0T6YDD",
    "pickup": {
      "lat": 52.370216,
      "lng": 4.895168,
      "address": "Damrak 1, Amsterdam"
    },
    "batched": false,
    "batch_id": null
  }
  ```
- **Response (201)**:
  ```json
  {
    "dispatch_id": "01HZX9C6T2B2L8K1P3F9V5T7YDE",
    "state": "initiated",
    "attempt_number": 1,
    "started_at": "2026-07-29T10:42:11.183Z",
    "correlation_id": "01HZX9C7T0XK2P9F0V6E4B1MZA"
  }
  ```
- **Errors**:
  - 400 `VALIDATION_FAILED`
  - 401 `UNAUTHENTICATED`
  - 403 `FORBIDDEN`
  - 404 `FOOD_ORDER_NOT_FOUND`
  - 409 `DISPATCH_ALREADY_ACTIVE`
  - 422 `BUSINESS_RULE_VIOLATION` (e.g. branch closed, restaurant
    offline)
  - 422 `IDEMPOTENCY_KEY_REUSED`
- **Validation**: `pickup.lat` and `pickup.lng` are required and
  must be valid coordinates; `food_order_id` is a UUID.

### 1.2 `POST /v1/dispatches/{dispatch_id}/accept`

- **Purpose**: Courier accepts the current offer.
- **Auth**: Bearer JWT (Keycloak `platform-courier`).
- **Idempotency**: `Idempotency-Key` header required
  (`dispatch:<dispatch_id>:accept:<courier_id>` is the natural key).
- **Request**:
  ```json
  {}
  ```
- **Response (200)**:
  ```json
  {
    "dispatch_id": "01HZX9C6T2B2L8K1P3F9V5T7YDE",
    "state": "committed",
    "delivery_id": "01HZX9C8N3B3L9K2P4F0V6T8YDF",
    "courier_id": "01HZX8D2Y1X1M5K7P9F3V2T8YDG",
    "committed_at": "2026-07-29T10:42:31.450Z"
  }
  ```
- **Errors**:
  - 401 `UNAUTHENTICATED`
  - 403 `OFFER_NOT_FOR_THIS_COURIER`
  - 404 `DISPATCH_NOT_FOUND`
  - 409 `OFFER_NOT_ACTIVE`
  - 410 `OFFER_EXPIRED`
  - 422 `IDEMPOTENCY_KEY_REUSED`

### 1.3 `POST /v1/dispatches/{dispatch_id}/reject`

- **Purpose**: Courier rejects the current offer. Triggers the
  next-best candidate offer.
- **Auth**: Bearer JWT (Keycloak `platform-courier`).
- **Idempotency**: `Idempotency-Key` header required
  (`dispatch:<dispatch_id>:reject:<courier_id>`).
- **Request**:
  ```json
  { "reason": "too_far" }
  ```
- **Response (202)**: empty body; the dispatch is offered to the
  next candidate asynchronously.
- **Errors**: 401, 403, 404, 409, 410, 422 as above.

### 1.4 `POST /v1/dispatches/{dispatch_id}/cancel`

- **Purpose**: Service or admin cancels a dispatch (compensation).
  Used when `delivery-service` reports an unrecoverable issue
  before pickup.
- **Auth**: Bearer JWT — service-to-service
  (`courier-dispatch.write`) OR admin (`courier.admin`).
- **Idempotency**: `Idempotency-Key` header required.
- **Request**:
  ```json
  { "reason": "restaurant_closed" }
  ```
- **Response (202)**: empty body.
- **Errors**: 401, 403, 404, 409, 422.

### 1.5 `POST /v1/dispatches/{dispatch_id}/reassign`

- **Purpose**: Admin force-reassign. The current assignment is
  released and a new dispatch is created with
  `reassigned_from = current.id`.
- **Auth**: Bearer JWT — admin (`courier.admin`).
- **Idempotency**: `Idempotency-Key` header required.
- **Request**:
  ```json
  { "reason": "manual_override", "audit_note": "..." }
  ```
- **Response (201)**:
  ```json
  {
    "new_dispatch_id": "01HZX9C9P4B4L0K3P5F1V7T9YDH",
    "previous_dispatch_id": "01HZX9C6T2B2L8K1P3F9V5T7YDE",
    "state": "initiated"
  }
  ```
- **Errors**: 401, 403, 404, 409, 422.

### 1.6 `GET /v1/dispatches/{dispatch_id}`

- **Purpose**: Read a dispatch (status, ledger, attempts).
- **Auth**: Bearer JWT — service-to-service
  (`courier-dispatch.read`) OR admin OR the courier who is currently
  offered/assigned.
- **Response (200)**:
  ```json
  {
    "dispatch_id": "01HZX9C6T2B2L8K1P3F9V5T7YDE",
    "food_order_id": "01HZX9C5S3B1L7K0P2F8V4T6YDA",
    "state": "committed",
    "attempt_number": 1,
    "delivery_id": "01HZX9C8N3B3L9K2P4F0V6T8YDF",
    "courier_id": "01HZX8D2Y1X1M5K7P9F3V2T8YDG",
    "started_at": "2026-07-29T10:42:11.183Z",
    "ended_at": "2026-07-29T10:42:31.450Z",
    "ledger": [
      { "courier_id": "01HZX…", "sequence": 1, "outcome": "rejected",
        "offered_at": "…", "responded_at": "…" },
      { "courier_id": "01HZX…", "sequence": 2, "outcome": "accepted",
        "offered_at": "…", "responded_at": "…" }
    ]
  }
  ```
- **Errors**: 401, 403, 404.

### 1.7 `GET /v1/dispatches/metrics`

- **Purpose**: Operational counters (admin only).
- **Auth**: Bearer JWT — admin (`courier.admin`).
- **Query params**: `?city_id=…&since=…&until=…`.
- **Response (200)**:
  ```json
  {
    "dispatches_started_total": 12345,
    "dispatches_committed_total": 12000,
    "no_courier_total": 200,
    "median_assignment_seconds": 32.1,
    "p95_assignment_seconds": 78.4
  }
  ```

## 2. Outbound APIs

| Target | Method | URI | Purpose | Timeout | Retry | Circuit |
|--------|--------|-----|---------|---------|-------|---------|
| `courier-service` | GET | `/v1/couriers/{id}` | enrich assignment | 1s | 3 (exp backoff) | yes |
| `courier-tracking-service` | GET | `/v1/couriers/{id}/location` | read last point | 500ms | 3 | yes |
| `geolocation-service` | GET | `/v1/distance?from=…&to=…` | courier→pickup | 200ms | 2 | yes |
| `eta-routing-service` | GET | `/v1/eta?from=…&to=…` | pickup ETA | 500ms | 2 | yes |
| `notification-service` | POST | `/v1/pushes` | push offer | 1s | 3 | yes |

## 3. Produced Events

### 3.1 `delivery.courier.assigned.v1`

- **Producer**: `courier-dispatch-service`
- **Topic**: `delivery.courier.assigned`
- **Trigger**: a courier accepts an offer and the assignment is
  committed.
- **Schema version**: 1
- **Partition key**: `dispatch_id`
- **Consumers**: `delivery-service`, `food-order-service`,
  `notification-service`, `audit-service`
- **Schema**:
  ```json
  {
    "event_id": "01HZX9C8W6K0G3V2Y5N1Q4R7PB",
    "event_name": "delivery.courier.assigned.v1",
    "occurred_at": "2026-07-29T10:42:31.450Z",
    "schema_version": 1,
    "producer": "courier-dispatch-service",
    "tenant_id": "global",
    "correlation_id": "01HZX9C7T0XK2P9F0V6E4B1MZA",
    "causation_id": null,
    "aggregate_type": "Dispatch",
    "aggregate_id": "01HZX9C6T2B2L8K1P3F9V5T7YDE",
    "data": {
      "dispatch_id": "01HZX9C6T2B2L8K1P3F9V5T7YDE",
      "delivery_id": "01HZX9C8N3B3L9K2P4F0V6T8YDF",
      "food_order_id": "01HZX9C5S3B1L7K0P2F8V4T6YDA",
      "courier_id": "01HZX8D2Y1X1M5K7P9F3V2T8YDG",
      "branch_id": "01HZX9B4X2C1N5K7P0F6V3T8YDB",
      "restaurant_id": "01HZX8A2Z1X0M4K6P8F2V1T7YDC",
      "city_id": "01HZX7Y0X9W8M3K5P7F1V0T6YDD",
      "batched": false,
      "batch_id": null,
      "pickup": {
        "lat": 52.370216,
        "lng": 4.895168,
        "address": "Damrak 1, Amsterdam"
      },
      "assigned_at": "2026-07-29T10:42:31.450Z",
      "offer_attempts": 2
    }
  }
  ```
- **Retry**: outbox pattern, 3 attempts; permanent failure → DLQ.
- **DLQ**: `delivery.courier.assigned.dlq`.

### 3.2 `delivery.dispatch.no_courier.v1`

- **Producer**: `courier-dispatch-service`
- **Topic**: `delivery.dispatch.no_courier`
- **Trigger**: max offer attempts reached with no acceptance.
- **Schema version**: 1
- **Partition key**: `food_order_id`
- **Consumers**: `food-order-service`, `notification-service`,
  `support-service`
- **Schema**:
  ```json
  {
    "event_id": "...",
    "event_name": "delivery.dispatch.no_courier.v1",
    "occurred_at": "...",
    "aggregate_id": "01HZX9C5S3B1L7K0P2F8V4T6YDA",
    "data": {
      "food_order_id": "01HZX9C5S3B1L7K0P2F8V4T6YDA",
      "branch_id": "01HZX9B4X2C1N5K7P0F6V3T8YDB",
      "city_id": "01HZX7Y0X9W8M3K5P7F1V0T6YDD",
      "pool_size": 4,
      "offers_sent": 12,
      "reasons": ["all_rejected", "offers_expired"]
    }
  }
  ```
- **Retry**: outbox; DLQ.

### 3.3 `delivery.dispatch.offer.expired.v1`

- **Producer**: `courier-dispatch-service`
- **Topic**: `delivery.dispatch.offer.expired`
- **Trigger**: an individual offer expired without a response.
- **Partition key**: `dispatch_id`
- **Consumers**: `audit-service`
- **Schema**:
  ```json
  {
    "event_id": "...",
    "event_name": "delivery.dispatch.offer.expired.v1",
    "data": {
      "dispatch_id": "...",
      "courier_id": "...",
      "offered_at": "...",
      "expired_at": "...",
      "sequence": 3
    }
  }
  ```
- **DLQ**: `delivery.dispatch.offer.expired.dlq`.

### 3.4 `delivery.dispatch.reassigned.v1`

- **Producer**: `courier-dispatch-service`
- **Topic**: `delivery.dispatch.reassigned`
- **Trigger**: admin force-reassign or auto re-dispatch after
  courier cancel.
- **Partition key**: `food_order_id`
- **Consumers**: `notification-service`, `audit-service`
- **DLQ**: `delivery.dispatch.reassigned.dlq`.

### 3.5 `courier_dispatch.audit.assignment_committed.v1`

- **Producer**: `courier-dispatch-service`
- **Topic**: `courier_dispatch.audit.assignment_committed`
- **Trigger**: every committed assignment.
- **Partition key**: `dispatch_id`
- **Consumers**: `audit-service`
- **Schema**: includes full ledger snapshot, config version, and
  correlation_id.

## 4. Consumed Events

### 4.1 `food.order.ready.v1`

- **Producer**: `food-order-service` (via `restaurant-order-mgmt-service`).
- **Reason**: primary trigger for dispatch.
- **Handler**: enqueue a dispatch job; query the pool; offer to the
  top candidates.
- **Deduplication**: inbox on `event_id`.
- **Retry**: 3 with exponential backoff; permanent failure → DLQ.

### 4.2 `courier.availability.online.v1`

- **Producer**: `courier-service`.
- **Reason**: add courier to the available pool.
- **Handler**: upsert `courier_pool_entries` and Redis sorted set.
- **Deduplication**: inbox on `event_id`.

### 4.3 `courier.availability.offline.v1`

- **Producer**: `courier-service`.
- **Reason**: remove courier from the pool.
- **Handler**: mark `state=offline`; remove from Redis.

### 4.4 `courier.location.updated.v1`

- **Producer**: `courier-tracking-service` (curated).
- **Reason**: re-rank the pool by distance.
- **Handler**: update `courier_pool_entries.last_*`; refresh Redis
  score.
- **Throttling**: at most 1 update per courier per second (consumer-side
  dedup).

### 4.5 `delivery.courier.cancelled.v1`

- **Producer**: `delivery-service`.
- **Reason**: courier cancelled after assignment; reassign.
- **Handler**: enqueue reassignment job; emit
  `delivery.dispatch.reassigned.v1`.

### 4.6 `configuration.updated.v1`

- **Producer**: `configuration-service`.
- **Reason**: reload city-level config.
- **Handler**: refresh `city_config` snapshot.

## 5. Reliability

- **Timeouts**: outbound calls default 1s; pool search 200ms.
- **Retries**: 3 with exponential backoff (100ms, 400ms, 1.6s ±
  20% jitter). Idempotency-Key sent on every retry.
- **Circuit breakers**: every outbound call wrapped; opens at 5
  consecutive failures or 50% over 30s.
- **Bulkheads**: per-downstream connection pool.
- **Outbox**: yes (`courier_dispatch.outbox`).
- **Inbox**: yes (`courier_dispatch.inbox`).
- **DLQ**: every topic has a paired DLQ; retention 30 days.
- **Reconciliation**: a daily job in `reporting-service` reconciles
  committed dispatches against `delivery-service` records; orphan
  dispatches open a P3 ticket.

## 6. Correlation IDs

All requests carry `X-Correlation-Id`; emitted events carry the same
in the envelope. Logs and traces are correlated.

## 7. Distributed Tracing

OpenTelemetry; one root span per dispatch; child spans for pool
search, offer push, accept handling, outbox publish. `traceparent` is
propagated through Kafka headers.


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
| [`courier-service`](../courier-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [`courier-tracking-service`](../courier-tracking-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [`delivery-service`](../delivery-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [`dispatch-service`](../dispatch-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [`eta-routing-service`](../eta-routing-service/README.md) | DEGRADABLE | degrade (cache / default / flag) |
| [`feature-flag-service`](../feature-flag-service/README.md) | DEGRADABLE | degrade (cache / default / flag) |
| [`food-order-service`](../food-order-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [`food-payment-integration-service`](../food-payment-integration-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [`geolocation-service`](../geolocation-service/README.md) | DEGRADABLE | degrade (cache / default / flag) |
| [`ledger-service`](../ledger-service/README.md) | CRITICAL | 503 `DEPENDENCY_UNAVAILABLE` |
| [`notification-service`](../notification-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [`payment-service`](../payment-service/README.md) | CRITICAL | 503 `DEPENDENCY_UNAVAILABLE` |
| [`support-service`](../support-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [`wallet-service`](../wallet-service/README.md) | CRITICAL | 503 `DEPENDENCY_UNAVAILABLE` |
| [`zone-service`](../zone-service/README.md) | DEGRADABLE | degrade (cache / default / flag) |

### Downstream services that depend on this service

| Downstream | Class (from its perspective) |
|---|---|
| [`api-gateway`](../api-gateway/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`branch-service`](../branch-service/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`courier-service`](../courier-service/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`courier-tracking-service`](../courier-tracking-service/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`delivery-service`](../delivery-service/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`food-order-service`](../food-order-service/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`restaurant-order-mgmt-service`](../restaurant-order-mgmt-service/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`restaurant-service`](../restaurant-service/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`vehicle-service`](../vehicle-service/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`zone-service`](../zone-service/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |

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

