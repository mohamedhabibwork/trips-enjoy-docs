# delivery-service — Integration Contract

## 1. Inbound APIs

### 1.1 `POST /v1/deliveries`

- **Purpose**: Create a delivery from a `delivery.courier.assigned.v1`
  event. Typically called by the consumer of
  `delivery.courier.assigned.v1` (this service itself), but exposed
  for the dispatcher to call synchronously when needed.
- **Auth**: Bearer JWT — service-to-service (`delivery.write`).
- **Idempotency**: `Idempotency-Key` required
  (`dispatch:<dispatch_id>:delivery` is the natural key).
- **Request**:
  ```json
  {
    "dispatch_id": "01HZX9C6T2B2L8K1P3F9V5T7YDE",
    "food_order_id": "01HZX9C5S3B1L7K0P2F8V4T6YDA",
    "courier_id": "01HZX8D2Y1X1M5K7P9F3V2T8YDG",
    "customer_id": "01HZX7C2X1X0M4K6P8F2V1T7YDH",
    "branch_id": "01HZX9B4X2C1N5K7P0F6V3T8YDB",
    "restaurant_id": "01HZX8A2Z1X0M4K6P8F2V1T7YDC",
    "city_id": "01HZX7Y0X9W8M3K5P7F1V0T6YDD",
    "pickup": { "lat": 52.37, "lng": 4.89, "address": "..." },
    "dropoff": { "lat": 52.36, "lng": 4.90, "address": "..." },
    "batch_id": null,
    "correlation_id": "01HZX9C7T0XK2P9F0V6E4B1MZA"
  }
  ```
- **Response (201)**:
  ```json
  {
    "delivery_id": "01HZX9C8N3B3L9K2P4F0V6T8YDF",
    "state": "assigned",
    "started_at": "2026-07-29T10:42:31.450Z"
  }
  ```
- **Errors**: 400, 401, 403, 409 (duplicate), 422.

### 1.2 `POST /v1/deliveries/{delivery_id}/en_route_pickup`

- **Auth**: Bearer JWT — courier.
- **Idempotency**: required.
- **Request**: `{ "attempt_id": "01HZX…", "lat": 52.37, "lng": 4.89 }`
- **Response (200)**: `{ "delivery_id": "...", "state": "en_route_pickup", "transitioned_at": "..." }`
- **Errors**: 401, 403 (`NOT_ASSIGNED_COURIER`), 409 (`STATE_INVALID`).

### 1.3 `POST /v1/deliveries/{delivery_id}/arrived_pickup`

- **Auth**: Bearer JWT — courier.
- **Idempotency**: required.
- **Request**: `{ "attempt_id": "01HZX…", "lat": 52.37, "lng": 4.89 }`
- **Response (200)**: `{ "state": "arrived_pickup", "transitioned_at": "..." }`
- **Errors**: 401, 403, 409.

### 1.4 `POST /v1/deliveries/{delivery_id}/pickup`

- **Auth**: Bearer JWT — courier.
- **Idempotency**: required.
- **Request**: `{ "attempt_id": "01HZX…", "lat": 52.37, "lng": 4.89 }`
- **Response (200)**: `{ "state": "picked_up", "transitioned_at": "..." }`
- **Errors**: 401, 403, 409.

### 1.5 `POST /v1/deliveries/{delivery_id}/en_route_dropoff`

- **Auth**: Bearer JWT — courier.
- **Idempotency**: required.
- **Request**: `{ "attempt_id": "01HZX…", "lat": 52.36, "lng": 4.90 }`
- **Response (200)**: `{ "state": "en_route_dropoff", "transitioned_at": "..." }`
- **Errors**: 401, 403, 409.

### 1.6 `POST /v1/deliveries/{delivery_id}/complete`

- **Auth**: Bearer JWT — courier.
- **Idempotency**: required.
- **Request**:
  ```json
  {
    "attempt_id": "01HZX…",
    "proof": {
      "type": "photo",
      "file_id": "01HZX9D0X4D4L1K4P6F2V8T0YDJ"
    },
    "lat": 52.36,
    "lng": 4.90
  }
  ```
  or `{"type":"signature","signature_b64":"…"}` or
  `{"type":"pin","pin":"482915"}`.
- **Response (200)**:
  ```json
  {
    "delivery_id": "01HZX9C8N3B3L9K2P4F0V6T8YDF",
    "state": "delivered",
    "delivered_at": "2026-07-29T11:08:21.183Z",
    "proof_id": "01HZX9D1Y5E5L2K5P7F3V9T1YDK"
  }
  ```
- **Errors**:
  - 401, 403, 409, 422 (`PROOF_INVALID`).
  - 422 (`IDEMPOTENCY_KEY_REUSED`).

### 1.7 `POST /v1/deliveries/{delivery_id}/failed`

- **Auth**: Bearer JWT — courier (reason `customer_unreachable`,
  `restaurant_closed`, `other`) OR admin.
- **Idempotency**: required.
- **Request**:
  ```json
  { "reason": "customer_unreachable", "notes": "..." }
  ```
- **Response (202)**: empty body; for `customer_unreachable`, a
  timer is started; for others, the delivery is closed immediately.
- **Errors**: 401, 403, 409, 422.

### 1.8 `POST /v1/deliveries/{delivery_id}/cancel`

- **Auth**: Bearer JWT — service (`delivery.write`) OR courier
  (pre-pickup) OR admin.
- **Idempotency**: required.
- **Request**: `{ "reason": "courier_cancelled" | "food.order.cancelled" | "force_cancel" }`
- **Response (202)**: empty body; emits `delivery.courier.cancelled.v1`
  if reason is `courier_cancelled`.
- **Errors**: 401, 403, 409 (cannot cancel post-pickup), 422.

### 1.9 `POST /v1/deliveries/{delivery_id}/cash-collected`

- **Auth**: Bearer JWT — courier.
- **Idempotency**: required.
- **Request**:
  ```json
  {
    "amount_minor": 2395,
    "currency": "EUR",
    "collected_at": "2026-07-29T11:08:21.183Z"
  }
  ```
- **Response (200)**: `{ "collection_id": "...", "delivery_id": "...", "amount_minor": 2395, "currency": "EUR" }`
- **Errors**: 401, 403, 409, 422 (COD not enabled for merchant).

### 1.10 `POST /v1/deliveries/{delivery_id}/redeliver`

- **Auth**: Bearer JWT — admin (`delivery.admin`).
- **Idempotency**: required.
- **Request**:
  ```json
  { "reason": "customer_complaint", "audit_note": "..." }
  ```
- **Response (202)**:
  ```json
  {
    "new_dispatch_id": "01HZX9D2Z6F6L3K6P8F4V0T2YDL",
    "previous_delivery_id": "01HZX9C8N3B3L9K2P4F0V6T8YDF",
    "state": "redispatching"
  }
  ```
- **Errors**: 401, 403, 409.

### 1.11 `GET /v1/deliveries/{delivery_id}`

- **Auth**: Bearer JWT — service OR admin OR the assigned courier
  OR the customer.
- **Response (200)**: full delivery record with state history.

### 1.12 `GET /v1/deliveries?courier_id=…&state=…`

- **Auth**: Bearer JWT — service OR admin OR the courier
  (filtered to their own).
- **Query params**: cursor pagination.
- **Response (200)**: list of deliveries with `next_cursor`.

## 2. Outbound APIs

| Target | Method | URI | Purpose | Timeout | Retry | Circuit |
|--------|--------|-----|---------|---------|-------|---------|
| `courier-service` | GET | `/v1/couriers/{id}` | enrich | 1s | 3 | yes |
| `food-order-service` | GET | `/v1/orders/{id}` | enrich | 1s | 3 | yes |
| `customer-service` | GET | `/v1/customers/{id}` | contact (read) | 1s | 3 | yes |
| `courier-dispatch-service` | POST | `/v1/dispatches/{id}/cancel` | reassign | 1s | 3 | yes |
| `file-service` | GET | `/v1/files/{id}` | verify proof photo | 500ms | 2 | yes |
| `notification-service` | POST | `/v1/pushes` | customer notifications | 1s | 3 | yes |

## 3. Produced Events

### 3.1 `delivery.pickup.v1`

- **Topic**: `delivery.pickup`
- **Trigger**: state `picked_up`.
- **Partition key**: `delivery_id`
- **Schema**:
  ```json
  {
    "event_id": "01HZX9C8W6K0G3V2Y5N1Q4R7PB",
    "event_name": "delivery.pickup.v1",
    "occurred_at": "...",
    "aggregate_type": "Delivery",
    "aggregate_id": "01HZX9C8N3B3L9K2P4F0V6T8YDF",
    "data": {
      "delivery_id": "01HZX9C8N3B3L9K2P4F0V6T8YDF",
      "food_order_id": "01HZX9C5S3B1L7K0P2F8V4T6YDA",
      "courier_id": "01HZX8D2Y1X1M5K7P9F3V2T8YDG",
      "customer_id": "01HZX7C2X1X0M4K6P8F2V1T7YDH",
      "picked_up_at": "..."
    }
  }
  ```
- **Consumers**: `notification-service`, `customer-service`
  (history).
- **DLQ**: `delivery.pickup.dlq`.

### 3.2 `delivery.in_transit.v1`

- **Topic**: `delivery.in_transit`
- **Trigger**: state `en_route_dropoff`.
- **Partition key**: `delivery_id`
- **Schema**: similar to pickup; includes `eta_seconds`.
- **Consumers**: `notification-service`, `customer-service`
  (history).

### 3.3 `delivery.arrived.v1`

- **Topic**: `delivery.arrived`
- **Trigger**: courier arrives at dropoff (separate event from
  `picked_up`'s `arrived_pickup`).
- **Partition key**: `delivery_id`
- **Consumers**: `notification-service`.

### 3.4 `delivery.completed.v1`

- **Topic**: `delivery.completed`
- **Trigger**: state `delivered` (with valid proof).
- **Partition key**: `delivery_id`
- **Schema**:
  ```json
  {
    "event_id": "...",
    "event_name": "delivery.completed.v1",
    "data": {
      "delivery_id": "...",
      "food_order_id": "...",
      "courier_id": "...",
      "customer_id": "...",
      "branch_id": "...",
      "restaurant_id": "...",
      "city_id": "...",
      "delivered_at": "...",
      "proof_type": "photo",
      "proof_id": "01HZX…",
      "pickup_to_delivered_seconds": 1234,
      "batched": false,
      "batch_id": null
    }
  }
  ```
- **Consumers**: `food-payment-integration-service`,
  `courier-earnings-service`, `customer-service` (history),
  `notification-service`, `review-rating-service`,
  `audit-service`.
- **DLQ**: `delivery.completed.dlq`.

### 3.5 `delivery.failed.v1`

- **Topic**: `delivery.failed`
- **Trigger**: state `failed`.
- **Partition key**: `delivery_id`
- **Schema**:
  ```json
  {
    "event_id": "...",
    "event_name": "delivery.failed.v1",
    "data": {
      "delivery_id": "...",
      "food_order_id": "...",
      "courier_id": "...",
      "reason": "customer_unreachable",
      "failed_at": "...",
      "elapsed_seconds": 1234
    }
  }
  ```
- **Consumers**: `food-order-service`,
  `food-payment-integration-service` (refund),
  `notification-service`.

### 3.6 `delivery.courier.cancelled.v1`

- **Topic**: `delivery.courier.cancelled`
- **Trigger**: courier cancels pre-pickup.
- **Partition key**: `delivery_id`
- **Consumers**: `courier-dispatch-service` (reassign).

### 3.7 `delivery.audit.state_changed.v1`

- **Topic**: `delivery.audit.state_changed`
- **Trigger**: every state transition.
- **Partition key**: `delivery_id`
- **Consumers**: `audit-service`.

### 3.8 `cash.collected.v1`

- **Topic**: `cash.collected`
- **Trigger**: `POST /v1/deliveries/{id}/cash-collected`.
- **Partition key**: `delivery_id`
- **Schema**:
  ```json
  {
    "event_id": "...",
    "event_name": "cash.collected.v1",
    "data": {
      "delivery_id": "...",
      "courier_id": "...",
      "amount_minor": 2395,
      "currency": "EUR",
      "collected_at": "..."
    }
  }
  ```
- **Consumers**: `food-payment-integration-service`,
  `restaurant-settlement-service` (reduce payable).

## 4. Consumed Events

### 4.1 `delivery.courier.assigned.v1`

- **Producer**: `courier-dispatch-service`.
- **Reason**: create the delivery aggregate.
- **Handler**: insert `delivery` row in `state=assigned`.
- **Deduplication**: inbox on `event_id`.

### 4.2 `courier.location.updated.v1`

- **Producer**: `courier-tracking-service`.
- **Reason**: update `last_known_*` and recompute ETA.
- **Handler**: update columns; throttled to 1 update per second per
  delivery.

### 4.3 `food.order.cancelled.v1`

- **Producer**: `food-order-service`.
- **Reason**: customer cancelled the order.
- **Handler**: if delivery not yet `picked_up`, transition to
  `cancelled`.

### 4.4 `customer.suspended.v1`

- **Producer**: `customer-service`.
- **Reason**: customer is suspended.
- **Handler**: set `at_risk=true` on all active deliveries for the
  customer; courier mobile app is informed.

### 4.5 `configuration.updated.v1`

- **Producer**: `configuration-service`.
- **Reason**: reload thresholds.
- **Handler**: refresh in-memory config snapshot.

## 5. Reliability

- **Timeouts**: outbound 1s default; outbound to `courier-dispatch`
  for reassign 2s.
- **Retries**: 3 with exponential backoff. Idempotency-Key sent on
  every retry.
- **Circuit breakers**: every outbound call wrapped.
- **Bulkheads**: per-downstream connection pool.
- **Outbox**: yes (`delivery.outbox`).
- **Inbox**: yes (`delivery.inbox`).
- **DLQ**: every topic has a paired DLQ; retention 30 days.
- **Reconciliation**: a daily job in `reporting-service` reconciles
  `deliveries` against `delivery.courier.assigned.v1` and
  `delivery.completed.v1`; orphans open tickets.

## 6. Correlation IDs

All requests carry `X-Correlation-Id`; emitted events carry the
same in the envelope. Logs and traces are correlated.

## 7. Distributed Tracing

OpenTelemetry; one root span per state transition; child spans for
DB writes, downstream calls, outbox publish. `traceparent`
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
| [`courier-dispatch-service`](../courier-dispatch-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [`courier-earnings-service`](../courier-earnings-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [`courier-service`](../courier-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [`courier-tracking-service`](../courier-tracking-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [`customer-service`](../customer-service/README.md) | CRITICAL | 503 `DEPENDENCY_UNAVAILABLE` |
| [`dispatch-service`](../dispatch-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [`eta-routing-service`](../eta-routing-service/README.md) | DEGRADABLE | degrade (cache / default / flag) |
| [`feature-flag-service`](../feature-flag-service/README.md) | DEGRADABLE | degrade (cache / default / flag) |
| [`file-service`](../file-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [`food-order-service`](../food-order-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [`food-payment-integration-service`](../food-payment-integration-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [`geolocation-service`](../geolocation-service/README.md) | DEGRADABLE | degrade (cache / default / flag) |
| [`ledger-service`](../ledger-service/README.md) | CRITICAL | 503 `DEPENDENCY_UNAVAILABLE` |
| [`notification-service`](../notification-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [`payment-service`](../payment-service/README.md) | CRITICAL | 503 `DEPENDENCY_UNAVAILABLE` |
| [`review-rating-service`](../review-rating-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [`support-service`](../support-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |

### Downstream services that depend on this service

| Downstream | Class (from its perspective) |
|---|---|
| [`address-service`](../address-service/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`api-gateway`](../api-gateway/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`courier-dispatch-service`](../courier-dispatch-service/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`courier-earnings-service`](../courier-earnings-service/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`courier-service`](../courier-service/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`courier-tracking-service`](../courier-tracking-service/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`eta-routing-service`](../eta-routing-service/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`food-order-service`](../food-order-service/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`food-payment-integration-service`](../food-payment-integration-service/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`geolocation-service`](../geolocation-service/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`loyalty-service`](../loyalty-service/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`notification-service`](../notification-service/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`restaurant-order-mgmt-service`](../restaurant-order-mgmt-service/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`review-rating-service`](../review-rating-service/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |

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

