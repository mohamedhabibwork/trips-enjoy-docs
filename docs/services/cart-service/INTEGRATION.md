# cart-service — Integration Contract

## 1. Inbound APIs

### 1.1 `POST /v1/carts`

- **Purpose**: Create a new cart.
- **Auth**: Bearer JWT (role: `customer`).
- **Idempotency**: `Idempotency-Key` header **required**.
- **Request**:
  ```json
  {
    "branch_id": "01HZX...",
    "address_id": "01HZX...",
    "tip_minor": 200
  }
  ```
- **Response (201)**:
  ```json
  {
    "id": "01HZX...",
    "customer_id": "01HZX...",
    "branch_id": "01HZX...",
    "restaurant_id": "01HZX...",
    "state": "active",
    "subtotal_minor": 0,
    "currency": "EUR",
    "tip_minor": 200,
    "total_minor": 200,
    "items": [],
    "created_at": "2026-07-29T10:42:11.183Z"
  }
  ```
- **Errors**:
  - 400 `VALIDATION_FAILED`
  - 401 `UNAUTHENTICATED`
  - 403 `FORBIDDEN`
  - 404 `BRANCH_NOT_FOUND` / `CUSTOMER_NOT_FOUND`
  - 409 `BRANCH_CLOSED` (warning, not hard block — the cart is
    created but `checkout_blocked = true`)
  - 422 `IDEMPOTENCY_KEY_REUSED`
  - 429 `RATE_LIMITED`
  - 503 `DEPENDENCY_TIMEOUT` / `CIRCUIT_OPEN`

### 1.2 `GET /v1/carts/{id}`

- **Purpose**: Read a cart.
- **Auth**: Bearer JWT (the customer of the cart, or
  `client_credentials` for `checkout-service`).
- **Response (200)**: full cart including items, modifiers,
  add-ons, applied promotion, subtotal, total.
- **Cached**: 30 s TTL in Redis, key `cart:{id}`.

### 1.3 `PATCH /v1/carts/{id}`

- **Purpose**: Update tip or address.
- **Auth**: the customer of the cart.
- **Idempotency**: required.
- **Request**: `{"tip_minor": 300, "address_id": "..."}`.

### 1.4 `DELETE /v1/carts/{id}`

- **Purpose**: Abandon the cart.
- **Auth**: the customer of the cart.
- **Idempotency**: required.
- **Side effects**: state → `abandoned`; emits
  `cart.abandoned.v1`.

### 1.5 `POST /v1/carts/{id}/items`

- **Purpose**: Add an item.
- **Auth**: the customer of the cart.
- **Idempotency**: required.
- **Request**:
  ```json
  {
    "product_id": "01HZX...",
    "quantity": 2,
    "modifier_option_ids": ["01HZX...", "01HZX..."],
    "addon_ids": ["01HZX..."],
    "special_instructions": "no onions"
  }
  ```
- **Side effects**: re-quote via `pricing-service`; emits
  `cart.updated.v1`.

### 1.6 `PATCH /v1/carts/{id}/items/{iid}`

- **Purpose**: Update item quantity.
- **Auth**: the customer of the cart.
- **Idempotency**: required.
- **Request**: `{"quantity": 3}`.

### 1.7 `DELETE /v1/carts/{id}/items/{iid}`

- **Purpose**: Remove an item.
- **Auth**: the customer of the cart.
- **Idempotency**: required.

### 1.8 `POST /v1/carts/{id}/promotions`

- **Purpose**: Apply a promotion.
- **Auth**: the customer of the cart.
- **Idempotency**: required (the
  `promotion_idempotency_key` is derived as
  `cart:{cart_id}:promo:{code}`).
- **Request**: `{"code": "SUMMER20"}`.
- **Side effects**: re-quote; emits `cart.updated.v1`.

### 1.9 `DELETE /v1/carts/{id}/promotions`

- **Purpose**: Remove the applied promotion.
- **Auth**: the customer of the cart.
- **Idempotency**: required.

### 1.10 `POST /v1/carts/{id}/re-quote`

- **Purpose**: Re-quote (internal; called by event handlers).
- **Auth**: `client_credentials`.
- **Idempotency**: required.

### 1.11 `POST /v1/carts/{id}/checkout`

- **Purpose**: Create a checkout session.
- **Auth**: the customer of the cart.
- **Idempotency**: required.
- **Side effects**: synchronous call to `checkout-service` to
  create the session; on success, state → `checked_out`;
  emits `cart.checked_out.v1`.
- **Errors**:
  - 409 `CHECKOUT_BLOCKED` (restaurant offline)
  - 409 `CART_EMPTY`
  - 409 `STATE_INVALID` (cart already checked out or
    abandoned)

### 1.12 `GET /v1/carts/by-customer/{customer_id}`

- **Purpose**: List active carts for a customer.
- **Auth**: the customer, or `client_credentials`.

## 2. Outbound APIs

| Target | Method | URI | Purpose | Timeout | Retry | Circuit |
|--------|--------|-----|---------|---------|-------|---------|
| `customer-service` | GET | /v1/customers/{id} | verify customer | 1 s | 3 | yes |
| `menu-service` | GET | /v1/menus/products/{id} | verify product / price | 1 s | 3 | yes |
| `restaurant-service` | GET | /v1/restaurants/{id} | verify online | 1 s | 3 | yes |
| `branch-service` | GET | /v1/branches/{id} | verify open | 1 s | 3 | yes |
| `promotion-service` | POST | /v1/promotions/validate | validate / apply | 1 s | 3 | yes |
| `pricing-service` | POST | /v1/quote | sub-quote | 1 s | 3 | yes |
| `checkout-service` | POST | /v1/checkouts | create checkout session | 2 s | 3 | yes |
| `notification-service` | POST | /v1/notifications | notify customer | 1 s | 3 | yes |

## 3. Produced Events

### 3.1 `cart.created.v1`

- **Producer**: `cart-service`.
- **Topic**: `cart.cart.created`.
- **Trigger**: `POST /v1/carts`.
- **Schema version**: 1.
- **Partition key**: `cart.id`.
- **Consumers**: `analytics-service`, `customer-service`,
  `audit-service`.
- **Schema**:
  ```json
  {
    "event_id": "01HZX...",
    "event_name": "cart.created.v1",
    "occurred_at": "2026-07-29T10:42:11.183Z",
    "schema_version": 1,
    "producer": "cart-service",
    "tenant_id": "global",
    "correlation_id": "01HZX...",
    "aggregate_type": "Cart",
    "aggregate_id": "01HZX...",
    "data": {
      "cart_id": "01HZX...",
      "customer_id": "01HZX...",
      "branch_id": "01HZX...",
      "restaurant_id": "01HZX..."
    }
  }
  ```
- **DLQ**: `cart.cart.created.dlq`.

### 3.2 `cart.updated.v1`

Same envelope, `data.changed_fields: [...]`,
`data.subtotal_minor`, `data.total_minor`.

### 3.3 `cart.checked_out.v1`

Same envelope, `data.checkout_session_id`.

### 3.4 `cart.abandoned.v1`

Same envelope, `data.last_activity_at`, `data.idle_minutes`.

## 4. Consumed Events

### 4.1 `menu.item.price.changed.v1`

- **Producer**: `menu-service`.
- **Reason**: re-quote.
- **Handler**: query active carts containing the product;
  re-quote each; if the subtotal differs, emit
  `cart.updated.v1` and notify the customer.
- **Deduplication**: inbox on `event_id`.

### 4.2 `menu.item.unavailable.v1`

- **Producer**: `menu-service`.
- **Reason**: remove the item.
- **Handler**: query active carts containing the product;
  remove the item; re-quote; emit `cart.updated.v1`; notify
  the customer.

### 4.3 `cart.item.unavailable.v1`

- **Producer**: `inventory-service`.
- **Reason**: out-of-stock mirror.
- **Handler**: same as 4.2.

### 4.4 `restaurant.offline.v1`

- **Producer**: `restaurant-service`.
- **Reason**: block checkout.
- **Handler**: query active carts of the restaurant; set
  `checkout_blocked = true` with reason "restaurant_offline";
  emit `cart.updated.v1`; notify the customer.

## 5. Reliability

- **Timeouts**: HTTP 1 s; checkout-service 2 s; DB 30 s; Kafka
  5 s.
- **Retries**: 3 attempts with exponential backoff and jitter;
  Idempotency-Key on all non-idempotent calls.
- **Circuit breakers**: standard 5/30 s.
- **Bulkheads**: per-downstream connection pools.
- **Outbox**: yes, `cart.outbox`.
- **Inbox**: yes, `cart.inbox`.
- **DLQ**: every topic has a paired `.dlq`; 30-day retention.
- **Reconciliation**: daily job in `reporting-service` checks
  for `active` carts older than 30 days (the abandonment cron
  should have caught them) and for `active` carts with
  `checkout_blocked = true` for more than 24 hours.

## 6. Correlation IDs

All requests carry `X-Correlation-Id`; the service propagates
it to outbound calls and embeds it in the event envelope.

## 7. Distributed Tracing

OpenTelemetry SDK; one root span per request; named
`POST /v1/carts/{id}/items`, etc. Propagated through Kafka.
Sample 100% on errors, 10% on success in production.

## 8. Threat Surface (per `SECURITY_ARCHITECTURE.md` §18)

| Threat | Mitigation |
|--------|------------|
| Spoofing | mTLS + JWT |
| Tampering | resource-level ownership (`cart.customer_id == sub`) |
| Repudiation | audit log via events |
| Information disclosure | no PII beyond the customer's id |
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
| [`analytics-service`](../analytics-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [`audit-service`](../audit-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [`checkout-service`](../checkout-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [`configuration-service`](../configuration-service/README.md) | DEGRADABLE | degrade (cache / default / flag) |
| [`customer-service`](../customer-service/README.md) | CRITICAL | 503 `DEPENDENCY_UNAVAILABLE` |
| [`feature-flag-service`](../feature-flag-service/README.md) | DEGRADABLE | degrade (cache / default / flag) |
| [`food-order-service`](../food-order-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [`inventory-service`](../inventory-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [`menu-service`](../menu-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [`pricing-service`](../pricing-service/README.md) | CRITICAL | 503 `DEPENDENCY_UNAVAILABLE` |
| [`promotion-service`](../promotion-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [`restaurant-service`](../restaurant-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |

### Downstream services that depend on this service

| Downstream | Class (from its perspective) |
|---|---|
| [`address-service`](../address-service/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`branch-service`](../branch-service/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`checkout-service`](../checkout-service/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`customer-service`](../customer-service/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`food-order-service`](../food-order-service/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`inventory-service`](../inventory-service/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`loyalty-service`](../loyalty-service/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`menu-service`](../menu-service/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`pricing-service`](../pricing-service/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`promotion-service`](../promotion-service/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`restaurant-service`](../restaurant-service/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
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

