# menu-service — Integration Contract

## 1. Inbound APIs

### 1.1 `POST /v1/restaurants/{restaurant_id}/menus`

- **Purpose**: Create a menu under an approved restaurant.
- **Auth**: Bearer JWT (role: `merchant_owner` of the parent
  restaurant).
- **Idempotency**: `Idempotency-Key` header **required**.
- **Request**:
  ```json
  {
    "name": "Dinner"
  }
  ```
- **Response (201)**:
  ```json
  {
    "id": "01HZX...",
    "restaurant_id": "01HZX...",
    "name": "Dinner",
    "state": "draft",
    "created_at": "2026-07-29T10:42:11.183Z"
  }
  ```
- **Errors**:
  - 400 `VALIDATION_FAILED`
  - 401 `UNAUTHENTICATED`
  - 403 `FORBIDDEN` (not the owner)
  - 409 `RESTAURANT_NOT_APPROVED`
  - 409 `RESTAURANT_SUSPENDED`
  - 422 `IDEMPOTENCY_KEY_REUSED`
  - 429 `RATE_LIMITED`
  - 503 `DEPENDENCY_TIMEOUT` / `CIRCUIT_OPEN`

### 1.2 `GET /v1/menus/{id}`

- **Purpose**: Read a menu (with categories, products,
  modifiers, add-ons).
- **Auth**: any authenticated user; customers can only see
  `published` menus.
- **Response (200)**: full menu tree.

### 1.3 `PATCH /v1/menus/{id}`

- **Purpose**: Update menu metadata.
- **Auth**: `merchant_owner`, `restaurant_manager`, or admin.
- **Idempotency**: required.

### 1.4 `POST /v1/menus/{id}/publish` and `POST /v1/menus/{id}/unpublish`

- **Purpose**: Publish or unpublish.
- **Auth**: `merchant_owner`, `restaurant_manager`, or admin.
- **Idempotency**: required.
- **Side effects**: emits `menu.published.v1` or
  `menu.unpublished.v1`.

### 1.5 `POST /v1/menus/{id}/categories` and CRUD on categories

- Standard CRUD; auth: `merchant_owner`, `restaurant_manager`,
  or admin.
- **Side effects**: emits `menu.updated.v1`.

### 1.6 `POST /v1/menus/{id}/categories/{cid}/products` and CRUD on products

- Standard CRUD; auth: `merchant_owner`, `restaurant_manager`,
  or admin.
- **Side effects**: emits `menu.updated.v1`.

### 1.7 `POST /v1/menus/{id}/products/{pid}/price`

- **Purpose**: Change a product's price.
- **Auth**: `merchant_owner`, `restaurant_manager`, or admin.
- **Idempotency**: required.
- **Request**:
  ```json
  {
    "price_minor": 1295,
    "currency": "EUR",
    "effective_at": "2026-08-01T00:00:00Z",
    "reason": "cost increase"
  }
  ```
  `effective_at` is optional; if absent, the change is
  immediate.
- **Side effects**: writes to `product_price_history`; emits
  `menu.item.price.changed.v1`.

### 1.8 `POST /v1/menus/{id}/products/{pid}/86` and `DELETE /v1/menus/{id}/products/{pid}/86`

- **Purpose**: 86 / un-86 a product.
- **Auth**: `merchant_owner`, `restaurant_manager`,
  `kitchen` staff, or admin.
- **Idempotency**: required.
- **Request (POST)**: `{"reason_code": "out_of_ingredient",
  "reason_text": "..."}`.
- **Side effects**: emits `menu.item.unavailable.v1` or
  `menu.item.available.v1`.

### 1.9 `POST /v1/menus/{id}/products/{pid}/modifiers` and CRUD

- Standard CRUD for modifiers and their options.

### 1.10 `POST /v1/menus/{id}/products/{pid}/addons` and CRUD

- Standard CRUD for add-ons.

### 1.11 `GET /v1/restaurants/{restaurant_id}/menu`

- **Purpose**: Read the published menu for a restaurant.
- **Auth**: any authenticated user.
- **Cached**: 60 s TTL in Redis, key
  `menu:by_restaurant:{restaurant_id}`.
- **Response (200)**: the published menu tree (or empty if
  none).

### 1.12 `GET /v1/menus/products/{pid}/availability`

- **Purpose**: Fast availability check.
- **Auth**: `client_credentials`.
- **Cached**: 30 s TTL in Redis, key
  `menu:product_availability:{pid}`.
- **Response (200)**:
  ```json
  { "product_id": "...", "available": true, "expires_at": "..." }
  ```

### 1.13 `GET /v1/menus/by-restaurant/{restaurant_id}`

- **Purpose**: List menus for a restaurant.
- **Auth**: `client_credentials`.

## 2. Outbound APIs

| Target | Method | URI | Purpose | Timeout | Retry | Circuit |
|--------|--------|-----|---------|---------|-------|---------|
| `restaurant-service` | GET | /v1/restaurants/{id} | verify parent | 1 s | 3 | yes |
| `tax-service` | GET | /v1/tax/codes/{code} | resolve tax rate | 1 s | 3 | yes |
| `inventory-service` | GET | /v1/inventory/items/{id} | check stock | 1 s | 3 | yes |
| `file-service` | GET | /v1/files/{id} | verify photo | 1 s | 3 | yes |
| `configuration-service` | GET | /v1/configurations/{key} | read menu config | 1 s | 3 | yes |
| `notification-service` | POST | /v1/notifications | trigger lifecycle | 1 s | 3 | yes |

## 3. Produced Events

### 3.1 `menu.created.v1`

- **Producer**: `menu-service`.
- **Topic**: `menu.menu.created`.
- **Trigger**: `POST /v1/restaurants/{id}/menus`.
- **Schema version**: 1.
- **Partition key**: `menu.id`.
- **Consumers**: `cart-service`, `search-service`,
  `inventory-service`, `audit-service`.
- **Schema**:
  ```json
  {
    "event_id": "01HZX...",
    "event_name": "menu.created.v1",
    "occurred_at": "2026-07-29T10:42:11.183Z",
    "schema_version": 1,
    "producer": "menu-service",
    "tenant_id": "global",
    "correlation_id": "01HZX...",
    "aggregate_type": "Menu",
    "aggregate_id": "01HZX...",
    "data": {
      "menu_id": "01HZX...",
      "restaurant_id": "01HZX...",
      "name": "Dinner",
      "state": "draft"
    }
  }
  ```
- **DLQ**: `menu.menu.created.dlq`.

### 3.2 `menu.updated.v1`

Same envelope, `data.changed_fields: [...]`.

### 3.3 `menu.published.v1` and `menu.unpublished.v1`

Same envelope, `data.state = "published" | "draft"`.

### 3.4 `menu.item.price.changed.v1`

Same envelope, `data.product_id`, `data.old_price_minor`,
`data.new_price_minor`, `data.currency`,
`data.effective_at`.

### 3.5 `menu.item.unavailable.v1` and `menu.item.available.v1`

Same envelope, `data.product_id`,
`data.reason_code`, `data.cause` (`manual`, `stock`,
`kitchen`).

## 4. Consumed Events

### 4.1 `restaurant.created.v1`

- **Producer**: `restaurant-service`.
- **Reason**: parent eligible for menus.
- **Handler**: log only.

### 4.2 `restaurant.suspended.v1`

- **Producer**: `restaurant-service`.
- **Reason**: cascade unpublish.
- **Handler**: query `menus` where `restaurant_id = ? AND state =
  'published'`; transition each to `draft` with
  `reason_code = "parent_suspended"`; emit
  `menu.unpublished.v1`.

### 4.3 `restaurant.closed.v1`

Same as 4.2 with `parent_closed`.

### 4.4 `inventory.item.out_of_stock.v1`

- **Producer**: `inventory-service`.
- **Reason**: stock-driven 86.
- **Handler**: if `menu.86.auto_on_oos` is true, query
  `products` where `inventory_item_id = ? AND unavailable =
  false`; for each, set `unavailable = true`,
  `unavailable_reason_code = "stock"`,
  `unavailable_actor_kc_sub = NULL` (system); emit
  `menu.item.unavailable.v1` with `data.cause = "stock"`.

### 4.5 `inventory.item.restocked.v1`

- **Producer**: `inventory-service`.
- **Reason**: stock restored.
- **Handler**: query `products` where `inventory_item_id = ? AND
  unavailable = true AND unavailable_reason_code = "stock"`;
  clear the 86; emit `menu.item.available.v1`.

### 4.6 `tax.updated.v1` / `configuration.updated.v1`

- **Producer**: `tax-service` / `configuration-service`.
- **Reason**: tax code cache invalidation.
- **Handler**: delete keys matching `tax:code:*` from Redis.

## 5. Reliability

- **Timeouts**: HTTP 1 s; DB 30 s; Kafka 5 s.
- **Retries**: 3 attempts with exponential backoff and jitter;
  Idempotency-Key on all non-idempotent calls.
- **Circuit breakers**: standard 5/30 s.
- **Bulkheads**: per-downstream connection pools.
- **Outbox**: yes, `menu.outbox`.
- **Inbox**: yes, `menu.inbox`.
- **DLQ**: every topic has a paired `.dlq`; 30-day retention.
- **Reconciliation**: daily job in `reporting-service` checks
  for `published` products with `price_minor <= 0`; opens
  tickets if any.

## 6. Correlation IDs

All requests carry `X-Correlation-Id`; the service propagates
it to outbound calls and embeds it in the event envelope.

## 7. Distributed Tracing

OpenTelemetry SDK; one root span per request; named
`POST /v1/menus/{id}/publish`, etc. Propagated through Kafka.
Sample 100% on errors, 10% on success in production.

## 8. Threat Surface (per `SECURITY_ARCHITECTURE.md` §18)

| Threat | Mitigation |
|--------|------------|
| Spoofing | mTLS + JWT |
| Tampering (admin action) | HMAC-SHA256 signature |
| Repudiation | audit log with actor, signature, correlation |
| Information disclosure | menu is public; no PII |
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
| [`file-service`](../file-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [`food-order-service`](../food-order-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [`inventory-service`](../inventory-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [`notification-service`](../notification-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [`restaurant-order-mgmt-service`](../restaurant-order-mgmt-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [`restaurant-service`](../restaurant-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [`search-service`](../search-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [`tax-service`](../tax-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |

### Downstream services that depend on this service

| Downstream | Class (from its perspective) |
|---|---|
| [`api-gateway`](../api-gateway/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`branch-service`](../branch-service/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`cart-service`](../cart-service/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`food-order-service`](../food-order-service/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`inventory-service`](../inventory-service/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`merchant-service`](../merchant-service/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`pricing-service`](../pricing-service/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`restaurant-order-mgmt-service`](../restaurant-order-mgmt-service/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`restaurant-service`](../restaurant-service/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`search-service`](../search-service/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`tax-service`](../tax-service/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |

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

