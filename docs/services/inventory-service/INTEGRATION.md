# inventory-service — Integration Contract

## 1. Inbound APIs

### 1.1 `POST /v1/inventory/items`

- **Purpose**: Create an inventory item.
- **Auth**: Bearer JWT (role: `merchant_owner` of the parent
  restaurant).
- **Idempotency**: `Idempotency-Key` header **required**.
- **Request**:
  ```json
  {
    "product_id": "01HZX...",
    "restaurant_id": "01HZX...",
    "initial_stock": 100,
    "low_stock_threshold": 10,
    "out_of_stock_threshold": 0
  }
  ```
- **Response (201)**:
  ```json
  {
    "id": "01HZX...",
    "product_id": "01HZX...",
    "restaurant_id": "01HZX...",
    "current_stock": 100,
    "unavailable": false,
    "created_at": "2026-07-29T10:42:11.183Z"
  }
  ```
- **Errors**:
  - 400 `VALIDATION_FAILED`
  - 401 `UNAUTHENTICATED`
  - 403 `FORBIDDEN`
  - 404 `PRODUCT_NOT_FOUND` (menu-service)
  - 404 `RESTAURANT_NOT_FOUND`
  - 409 `RESTAURANT_NOT_APPROVED`
  - 409 `INVENTORY_ITEM_EXISTS`
  - 422 `IDEMPOTENCY_KEY_REUSED`
  - 429 `RATE_LIMITED`
  - 503 `DEPENDENCY_TIMEOUT` / `CIRCUIT_OPEN`

### 1.2 `GET /v1/inventory/items/{id}`

- **Purpose**: Read an inventory item.
- **Auth**: any authenticated user.

### 1.3 `PATCH /v1/inventory/items/{id}`

- **Purpose**: Update thresholds.
- **Auth**: `merchant_owner`, `restaurant_manager`, or admin.
- **Idempotency**: required.

### 1.4 `POST /v1/inventory/items/{id}/restock`

- **Purpose**: Restock.
- **Auth**: `merchant_owner`, `restaurant_manager`, or admin.
- **Idempotency**: required.
- **Request**: `{"quantity": 50, "reason_code": "delivery",
  "reason_text": "..."}`.
- **Side effects**: writes to `stock_movements`; emits
  `inventory.item.restocked.v1` if it crosses the threshold
  upward.

### 1.5 `POST /v1/inventory/items/{id}/adjust`

- **Purpose**: Adjust (waste, count correction).
- **Auth**: `platform_admin` only.
- **Idempotency**: required.
- **Request**: `{"delta": -3, "reason_code": "waste",
  "reason_text": "..."}`.
- **Side effects**: writes to `stock_movements`; emits
  `inventory.item.restocked.v1` or
  `inventory.item.out_of_stock.v1` as appropriate.

### 1.6 `POST /v1/inventory/items/{id}/86` and `DELETE /v1/inventory/items/{id}/86`

- **Purpose**: 86 / un-86.
- **Auth**: `merchant_owner`, `restaurant_manager`, `kitchen`,
  or admin.
- **Idempotency**: required.
- **Request (POST)**: `{"reason_code": "out_of_ingredient",
  "reason_text": "..."}`.
- **Side effects**: emits `inventory.item.unavailable.v1` or
  `inventory.item.available.v1`.

### 1.7 `POST /v1/inventory/items/{id}/availability-windows` and CRUD

- **Purpose**: Add a time-bound availability window.
- **Auth**: `merchant_owner` or `restaurant_manager`.
- **Idempotency**: required.
- **Request**: `{"day_of_week": 1, "start_time": "11:00",
  "end_time": "14:00"}`.

### 1.8 `POST /v1/inventory/items/{id}/restock-schedules` and CRUD

- **Purpose**: Add an auto-restock schedule.
- **Auth**: `merchant_owner` or `restaurant_manager`.
- **Idempotency**: required.
- **Request**: `{"cron": "0 6 * * *", "quantity": 100}`.

### 1.9 `GET /v1/inventory/items`

- **Purpose**: List inventory items.
- **Auth**: any authenticated user.
- **Query params**: `restaurant_id`, `product_id`, `state`
  (`available`, `unavailable`, `low_stock`, `out_of_stock`),
  `cursor`, `limit`.

### 1.10 `GET /v1/inventory/items/by-product/{product_id}`

- **Purpose**: Lookup by product.
- **Auth**: `client_credentials`.

### 1.11 `GET /v1/inventory/items/{id}/availability`

- **Purpose**: Fast availability check.
- **Auth**: `client_credentials`.
- **Cached**: 30 s TTL in Redis, key
  `inventory:availability:{id}`.
- **Response (200)**:
  ```json
  { "available": true, "current_stock": 42, "expires_at": "..." }
  ```

### 1.12 `GET /v1/inventory/items/{id}/stock`

- **Purpose**: Current stock count.
- **Auth**: `client_credentials`.
- **Response (200)**: `{"inventory_item_id": "...",
  "current_stock": 42, "low_stock_threshold": 10,
  "out_of_stock_threshold": 0}`.

## 2. Outbound APIs

| Target | Method | URI | Purpose | Timeout | Retry | Circuit |
|--------|--------|-----|---------|---------|-------|---------|
| `menu-service` | GET | /v1/menus/products/{id} | verify product | 1 s | 3 | yes |
| `restaurant-service` | GET | /v1/restaurants/{id} | verify parent | 1 s | 3 | yes |
| `configuration-service` | GET | /v1/configurations/{key} | read defaults | 1 s | 3 | yes |
| `notification-service` | POST | /v1/notifications | low-stock alerts | 1 s | 3 | yes |

## 3. Produced Events

### 3.1 `inventory.item.created.v1`

- **Producer**: `inventory-service`.
- **Topic**: `inventory.inventory_item.created`.
- **Trigger**: `POST /v1/inventory/items`.
- **Schema version**: 1.
- **Partition key**: `inventory_item.id`.
- **Consumers**: `menu-service`, `cart-service`, `audit-service`.
- **Schema**:
  ```json
  {
    "event_id": "01HZX...",
    "event_name": "inventory.item.created.v1",
    "occurred_at": "2026-07-29T10:42:11.183Z",
    "schema_version": 1,
    "producer": "inventory-service",
    "tenant_id": "global",
    "correlation_id": "01HZX...",
    "aggregate_type": "InventoryItem",
    "aggregate_id": "01HZX...",
    "data": {
      "inventory_item_id": "01HZX...",
      "product_id": "01HZX...",
      "restaurant_id": "01HZX...",
      "current_stock": 100
    }
  }
  ```
- **DLQ**: `inventory.inventory_item.created.dlq`.

### 3.2 `inventory.item.out_of_stock.v1`

Same envelope, `data.previous_stock`, `data.current_stock = 0`,
`data.threshold`.

### 3.3 `inventory.item.restocked.v1`

Same envelope, `data.previous_stock`, `data.current_stock`,
`data.delta`.

### 3.4 `inventory.item.unavailable.v1` and `inventory.item.available.v1`

Same envelope, `data.cause` (`manual`, `kitchen`, `cascade`,
`menu_mirror`).

### 3.5 `inventory.item.low_stock.v1`

Same envelope, `data.current_stock`,
`data.low_stock_threshold`.

## 4. Consumed Events

### 4.1 `menu.item.unavailable.v1`

- **Producer**: `menu-service`.
- **Reason**: mirror in inventory 86 list.
- **Handler**: query inventory item by `product_id`; set
  `unavailable = true` with `reason_code = "menu_86"`; emit
  `inventory.item.unavailable.v1` with `data.cause =
  "menu_mirror"`.

### 4.2 `food.order.placed.v1`

- **Producer**: `food-order-service`.
- **Reason**: decrement stock.
- **Handler**: for each line item with an `inventory_item_id`,
  decrement stock atomically (row-level lock); if the resulting
  stock is at or below the out-of-stock threshold, emit
  `inventory.item.out_of_stock.v1`; if below the low-stock
  threshold, emit `inventory.item.low_stock.v1`. If the
  decrement would make stock negative, the order placement is
  rejected upstream (`food-order-service` validates first via
  the API).

### 4.3 `food.order.cancelled.v1`

- **Producer**: `food-order-service`.
- **Reason**: re-credit stock.
- **Handler**: for each cancelled line item, re-credit stock;
  if the new stock crosses the out-of-stock threshold upward,
  emit `inventory.item.restocked.v1`.

### 4.4 `restaurant.suspended.v1` and `restaurant.closed.v1`

- **Producer**: `restaurant-service`.
- **Reason**: cascade 86 all items of the restaurant.
- **Handler**: query all non-deleted items of the restaurant;
  set `unavailable = true` with
  `reason_code = "parent_suspended"`; emit
  `inventory.item.unavailable.v1` with `data.cause = "cascade"`.

## 5. Reliability

- **Timeouts**: HTTP 1 s; DB 30 s; Kafka 5 s.
- **Retries**: 3 attempts with exponential backoff and jitter;
  Idempotency-Key on all non-idempotent calls.
- **Circuit breakers**: standard 5/30 s.
- **Bulkheads**: per-downstream connection pools.
- **Outbox**: yes, `inventory.outbox`.
- **Inbox**: yes, `inventory.inbox`.
- **DLQ**: every topic has a paired `.dlq`; 30-day retention.
- **Reconciliation**: daily job in `reporting-service` checks
  for inventory items with `current_stock < 0` (should never
  happen) and for items with `unavailable = true` but no
  `unavailable_reason_code`; opens tickets if drift is found.

## 6. Correlation IDs

All requests carry `X-Correlation-Id`; the service propagates
it to outbound calls and embeds it in the event envelope.

## 7. Distributed Tracing

OpenTelemetry SDK; one root span per request; named
`POST /v1/inventory/items/{id}/restock`, etc. Propagated
through Kafka. Sample 100% on errors, 10% on success in
production.

## 8. Threat Surface (per `SECURITY_ARCHITECTURE.md` §18)

| Threat | Mitigation |
|--------|------------|
| Spoofing | mTLS + JWT |
| Tampering (admin adjust) | HMAC-SHA256 signature; break-glass co-sign |
| Repudiation | audit log with actor, signature, correlation |
| Information disclosure | no PII |
| Denial of service | rate limits; circuit breakers |
| Elevation of privilege | resource-level ownership checks |


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
| [`cart-service`](../cart-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [`checkout-service`](../checkout-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [`configuration-service`](../configuration-service/README.md) | DEGRADABLE | degrade (cache / default / flag) |
| [`feature-flag-service`](../feature-flag-service/README.md) | DEGRADABLE | degrade (cache / default / flag) |
| [`food-order-service`](../food-order-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [`menu-service`](../menu-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [`notification-service`](../notification-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [`payment-service`](../payment-service/README.md) | CRITICAL | 503 `DEPENDENCY_UNAVAILABLE` |
| [`restaurant-order-mgmt-service`](../restaurant-order-mgmt-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [`restaurant-service`](../restaurant-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [`search-service`](../search-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |

### Downstream services that depend on this service

| Downstream | Class (from its perspective) |
|---|---|
| [`cart-service`](../cart-service/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`menu-service`](../menu-service/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |

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

