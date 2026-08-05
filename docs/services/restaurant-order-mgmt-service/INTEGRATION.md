# restaurant-order-mgmt-service — Integration Contract

## 1. Inbound APIs

### 1.1 `GET /v1/queue`

- **Purpose**: List the queue (for the operator console).
- **Auth**: Bearer JWT (role: `manager`, `dispatcher`,
  `kitchen`, `platform_admin`).
- **Query params**: `branch_id` (required), `state`,
  `cursor`, `limit`.
- **Cached**: 5 s TTL in Redis, key
  `queue:by_branch:{branch_id}`.

### 1.2 `GET /v1/queue/{order_id}`

- **Purpose**: Read a queue item.
- **Auth**: Bearer JWT (the staff of the order's branch).
- **Response (200)**: queue item with state, timestamps, and
  the food order reference.

### 1.3 `POST /v1/queue/{order_id}/accept`

- **Purpose**: Operator accepts the order.
- **Auth**: Bearer JWT (role: `manager` or `dispatcher`).
- **Idempotency**: `Idempotency-Key` header **required**.
- **Request**: empty body or `{"note": "..."}`.
- **Side effects**: state → `accepted`; emits
  `food.order.accepted.v1`.
- **Errors**:
  - 401 `UNAUTHENTICATED`
  - 403 `FORBIDDEN`
  - 404 `QUEUE_ITEM_NOT_FOUND`
  - 409 `STATE_INVALID` (not in `placed` state)
  - 409 `ACCEPT_TIMER_EXPIRED` (auto-reject already fired)
  - 422 `IDEMPOTENCY_KEY_REUSED`
  - 429 `RATE_LIMITED`
  - 503 `DEPENDENCY_TIMEOUT` / `CIRCUIT_OPEN`

### 1.4 `POST /v1/queue/{order_id}/reject`

- **Purpose**: Operator rejects the order.
- **Auth**: Bearer JWT (role: `manager` or `dispatcher`).
- **Idempotency**: required.
- **Request**: `{"reason_code": "out_of_ingredient",
  "reason_text": "..."}`. `reason_code` is required.
- **Side effects**: state → `rejected`; emits
  `food.order.rejected.v1`.
- **Errors**: same as 1.3.

### 1.5 `POST /v1/queue/{order_id}/preparing`

- **Purpose**: Kitchen marks the order preparing.
- **Auth**: Bearer JWT (role: `kitchen` or `manager`).
- **Idempotency**: required.
- **Request**: empty body.
- **Side effects**: state → `preparing`; emits
  `food.order.preparing.v1`.

### 1.6 `POST /v1/queue/{order_id}/ready`

- **Purpose**: Kitchen marks the order ready.
- **Auth**: Bearer JWT (role: `kitchen` or `manager`).
- **Idempotency**: required.
- **Side effects**: state → `ready`; emits
  `food.order.ready.v1`. Consumed by
  `courier-dispatch-service`.

### 1.7 `GET /v1/queue/by-restaurant/{restaurant_id}`

- **Purpose**: List queue items for a restaurant.
- **Auth**: `client_credentials`.

### 1.8 `GET /v1/queue/by-branch/{branch_id}`

- **Purpose**: List queue items for a branch.
- **Auth**: `client_credentials`.

## 2. Outbound APIs

| Target | Method | URI | Purpose | Timeout | Retry | Circuit |
|--------|--------|-----|---------|---------|-------|---------|
| `menu-service` | GET | /v1/menus/products/{id} | read product for view | 1 s | 3 | yes |
| `restaurant-service` | GET | /v1/restaurants/{id} | verify parent | 1 s | 3 | yes |
| `branch-service` | GET | /v1/branches/{id} | verify parent | 1 s | 3 | yes |
| `customer-service` | GET | /v1/customers/{id} | read customer | 1 s | 3 | yes |
| `notification-service` | POST | /v1/notifications | alert operator | 1 s | 3 | yes |

## 3. Produced Events

### 3.1 `food.order.accepted.v1`

- **Producer**: `restaurant-order-mgmt-service`.
- **Topic**: `restaurant_order_mgmt.food_order.accepted`.
- **Trigger**: `POST /accept`.
- **Schema version**: 1.
- **Partition key**: `order.id`.
- **Consumers**: `food-order-service` (state transition),
  `notification-service`, `audit-service`.
- **Schema**:
  ```json
  {
    "event_id": "01HZX...",
    "event_name": "food.order.accepted.v1",
    "occurred_at": "2026-07-29T10:42:11.183Z",
    "schema_version": 1,
    "producer": "restaurant-order-mgmt-service",
    "tenant_id": "global",
    "correlation_id": "01HZX...",
    "aggregate_type": "FoodOrder",
    "aggregate_id": "01HZX...",
    "data": {
      "order_id": "01HZX...",
      "restaurant_id": "01HZX...",
      "branch_id": "01HZX...",
      "accepted_at": "2026-07-29T10:42:11.183Z",
      "accepted_by_kc_sub": "..."
    }
  }
  ```
- **DLQ**: `restaurant_order_mgmt.food_order.accepted.dlq`.

### 3.2 `food.order.rejected.v1`

Same envelope, `data.rejected_at`,
`data.reason_code`, `data.reason_text`,
`data.rejected_by_kc_sub`, `data.cause` (`operator` or
`auto_reject`).

### 3.3 `food.order.preparing.v1`

Same envelope, `data.preparing_at`,
`data.preparing_by_kc_sub`.

### 3.4 `food.order.ready.v1`

Same envelope, `data.ready_at`, `data.ready_by_kc_sub`.
Consumed by `courier-dispatch-service`.

## 4. Consumed Events

### 4.1 `food.order.placed.v1`

- **Producer**: `food-order-service`.
- **Reason**: add to queue, start accept timer.
- **Handler**: insert `queue` row with
  `accept_timer_expires_at = now() + 5 minutes`; emit
  (via the operator console or a separate `notification-service`
  call) a sound / push to the operator.
- **Deduplication**: inbox on `event_id`.

### 4.2 `food.order.cancelled.v1`

- **Producer**: `food-order-service`.
- **Reason**: customer cancelled; remove from queue.
- **Handler**: set `state = 'cancelled'`, `cancelled_at =
  now()`; emit no further events (the food order is already
  cancelled).
- **Deduplication**: inbox on `event_id`.

### 4.3 `food.order.placed.v1`

- **Producer**: `food-order-service`.
- **Reason**: A new order for the restaurant.
- **Handler**: Add to queue.
- **Deduplication / Retry / Failure**: inbox keyed by
  `event_id` / 3 with exponential backoff / DLQ.


### 4.4 `food.order.cancelled.v1`

- **Producer**: `food-order-service`.
- **Reason**: A cancellation.
- **Handler**: Remove from queue.
- **Deduplication / Retry / Failure**: inbox keyed by
  `event_id` / 3 with exponential backoff / DLQ.


### 4.5 `configuration.updated.v1`

- **Producer**: `configuration-service`.
- **Reason**: Accept-timer / capacity rules changed.
- **Handler**: Reload config.
- **Deduplication / Retry / Failure**: inbox keyed by
  `event_id` / 3 with exponential backoff / DLQ.



## 5. Reliability

- **Timeouts**: HTTP 1 s; DB 30 s; Kafka 5 s.
- **Retries**: 3 attempts with exponential backoff and jitter;
  Idempotency-Key on all non-idempotent calls.
- **Circuit breakers**: standard 5/30 s.
- **Bulkheads**: per-downstream connection pools.
- **Outbox**: yes, `restaurant_order_mgmt.outbox`.
- **Inbox**: yes, `restaurant_order_mgmt.inbox`.
- **DLQ**: every topic has a paired `.dlq`; 30-day retention.
- **Reconciliation**: daily job in `reporting-service` checks
  for queue items in `placed` for more than 10 minutes (the
  timer should have fired) and for queue items in `ready` for
  more than 30 minutes (the courier should have picked up).

## 6. Correlation IDs

All requests carry `X-Correlation-Id`; the service propagates
it to outbound calls and embeds it in the event envelope.

## 7. Distributed Tracing

OpenTelemetry SDK; one root span per request; named
`POST /v1/queue/{id}/accept`, etc. Propagated through Kafka.
Sample 100% on errors, 10% on success in production.

## 8. Threat Surface (per `SECURITY_ARCHITECTURE.md` §18)

| Threat | Mitigation |
|--------|------------|
| Spoofing | mTLS + JWT |
| Tampering (admin action) | HMAC-SHA256 signature |
| Repudiation | audit log via events |
| Information disclosure | no PII beyond the customer's id |
| Denial of service | rate limits; circuit breakers |
| Elevation of privilege | RBAC at gateway; resource-level checks |


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
| [`audit-service`](../audit-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [`branch-service`](../branch-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [`configuration-service`](../configuration-service/README.md) | DEGRADABLE | degrade (cache / default / flag) |
| [`courier-dispatch-service`](../courier-dispatch-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [`customer-service`](../customer-service/README.md) | CRITICAL | 503 `DEPENDENCY_UNAVAILABLE` |
| [`delivery-service`](../delivery-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [`dispatch-service`](../dispatch-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [`feature-flag-service`](../feature-flag-service/README.md) | DEGRADABLE | degrade (cache / default / flag) |
| [`food-order-service`](../food-order-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [`food-payment-integration-service`](../food-payment-integration-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [`menu-service`](../menu-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [`notification-service`](../notification-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [`restaurant-service`](../restaurant-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |

### Downstream services that depend on this service

| Downstream | Class (from its perspective) |
|---|---|
| [`branch-service`](../branch-service/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`food-order-service`](../food-order-service/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`inventory-service`](../inventory-service/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`menu-service`](../menu-service/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`restaurant-service`](../restaurant-service/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`restaurant-staff-service`](../restaurant-staff-service/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |

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

