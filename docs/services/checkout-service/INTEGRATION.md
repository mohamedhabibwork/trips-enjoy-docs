# checkout-service — Integration Contract

## 1. Inbound APIs

### 1.1 `POST /v1/checkouts`

- **Purpose**: Create a checkout session from a cart.
- **Auth**: Bearer JWT (role: `customer`).
- **Idempotency**: `Idempotency-Key` header **required**.
- **Request**:
  ```json
  {
    "cart_id": "01HZX...",
    "address_id": "01HZX...",
    "slot": {
      "start_at": "2026-07-29T12:00:00Z",
      "end_at": "2026-07-29T12:30:00Z"
    },
    "payment_method_id": "01HZX...",
    "tip_minor": 200
  }
  ```
- **Response (201)**:
  ```json
  {
    "id": "01HZX...",
    "customer_id": "01HZX...",
    "cart_id": "01HZX...",
    "state": "pending",
    "subtotal_minor": 2595,
    "tax_minor": 545,
    "delivery_fee_minor": 299,
    "tip_minor": 200,
    "total_minor": 3639,
    "currency": "EUR",
    "expires_at": "2026-07-29T11:00:00Z"
  }
  ```
- **Errors**:
  - 400 `VALIDATION_FAILED` (slot, address, payment method)
  - 401 `UNAUTHENTICATED`
  - 403 `FORBIDDEN`
  - 404 `CART_NOT_FOUND` / `ADDRESS_NOT_FOUND` /
    `PAYMENT_METHOD_NOT_FOUND`
  - 409 `CART_NOT_ACTIVE`
  - 409 `CHECKOUT_BLOCKED` (restaurant offline)
  - 422 `SLOT_INVALID` (lead time)
  - 422 `IDEMPOTENCY_KEY_REUSED`
  - 429 `RATE_LIMITED`
  - 503 `DEPENDENCY_TIMEOUT` / `CIRCUIT_OPEN`

### 1.2 `GET /v1/checkouts/{id}`

- **Purpose**: Read a session.
- **Auth**: the customer of the session, or
  `client_credentials`.
- **Cached**: 30 s TTL in Redis, key `checkout:{id}`.

### 1.3 `PATCH /v1/checkouts/{id}`

- **Purpose**: Update address, slot, tip, payment method.
- **Auth**: the customer.
- **Idempotency**: required.
- **Request**: any subset of the create body.
- **Side effects**: re-quote on address change; emits
  `checkout.*.v1` (via outbox; no specific event for updates
  — the next state change emits the event).

### 1.4 `POST /v1/checkouts/{id}/pay`

- **Purpose**: Authorize payment and create the order.
- **Auth**: the customer.
- **Idempotency**: required.
- **Side effects**:
  - Authorize via `payment-service` with
    `Idempotency-Key: checkout:{session_id}:pay`.
  - On success, create the food order via `food-order-service`
    with `Idempotency-Key: checkout:{session_id}:order`.
  - On success, mark session `completed` and emit
    `checkout.completed.v1`.
  - On failure, mark session `failed` and emit
    `checkout.failed.v1`.
- **Errors**:
  - 409 `CHECKOUT_BLOCKED` (pay_blocked = true)
  - 409 `STATE_INVALID` (session not pending)
  - 422 `PAYMENT_FAILED` (provider declined)
  - 422 `IDEMPOTENCY_KEY_REUSED` (different body)
  - 503 `DEPENDENCY_TIMEOUT` / `CIRCUIT_OPEN`

### 1.5 `DELETE /v1/checkouts/{id}`

- **Purpose**: Cancel a pending session.
- **Auth**: the customer.
- **Idempotency**: required.
- **State transition**: `pending → cancelled`; the cart is
  re-enabled.

## 2. Outbound APIs

| Target | Method | URI | Purpose | Timeout | Retry | Circuit |
|--------|--------|-----|---------|---------|-------|---------|
| `cart-service` | GET | /v1/carts/{id} | read cart contents | 1 s | 3 | yes |
| `cart-service` | POST | /v1/carts/{id}/re-quote | re-quote on update | 1 s | 3 | yes |
| `pricing-service` | POST | /v1/quote | final quote | 1 s | 3 | yes |
| `address-service` | GET | /v1/addresses/{id} | verify address | 1 s | 3 | yes |
| `payment-service` | POST | /v1/payments/authorize | authorize | 2 s | 3 | yes |
| `food-order-service` | POST | /v1/orders | create order | 2 s | 3 | yes |
| `customer-service` | GET | /v1/customers/{id}/default-payment-method | default PM | 1 s | 3 | yes |
| `restaurant-service` | GET | /v1/restaurants/{id}/online | online check | 1 s | 3 | yes |
| `branch-service` | GET | /v1/branches/{id}/open | open check | 1 s | 3 | yes |
| `notification-service` | POST | /v1/notifications | notify customer | 1 s | 3 | yes |

## 3. Produced Events

### 3.1 `checkout.completed.v1`

- **Producer**: `checkout-service`.
- **Topic**: `checkout.checkout.completed`.
- **Trigger**: payment authorized and order created.
- **Schema version**: 1.
- **Partition key**: `checkout_session.id`.
- **Consumers**: `cart-service` (clear), `food-order-service`
  (read), `notification-service`, `audit-service`.
- **Schema**:
  ```json
  {
    "event_id": "01HZX...",
    "event_name": "checkout.completed.v1",
    "occurred_at": "2026-07-29T10:42:11.183Z",
    "schema_version": 1,
    "producer": "checkout-service",
    "tenant_id": "global",
    "correlation_id": "01HZX...",
    "aggregate_type": "CheckoutSession",
    "aggregate_id": "01HZX...",
    "data": {
      "checkout_session_id": "01HZX...",
      "customer_id": "01HZX...",
      "cart_id": "01HZX...",
      "branch_id": "01HZX...",
      "restaurant_id": "01HZX...",
      "address_id": "01HZX...",
      "payment_intent_id": "01HZX...",
      "food_order_id": "01HZX...",
      "total_minor": 3639,
      "currency": "EUR"
    }
  }
  ```
- **DLQ**: `checkout.checkout.completed.dlq`.

### 3.2 `checkout.failed.v1`

Same envelope, with `data.reason_code`, `data.reason_text`.

### 3.3 `checkout.completed.v1`

- **Producer**: this service.
- **Topic**: `checkout.completed`.
- **Trigger**: A checkout session completes successfully.
- **Schema version**: 1.
- **Partition key**: `session_id`.
- **Consumers**: `food-order-service`, `cart-service`, `payment-service` (saga).
- **Schema**:

  ```json
  {
    "event_id": "01HZX9C8W6K0G3V2Y5N1Q4R7PB",
    "event_name": "checkout.completed.v1",
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
- **DLQ**: `checkout.completed.dlq`.



## 4. Consumed Events

### 4.1 `cart.updated.v1`

- **Producer**: `cart-service`.
- **Reason**: the cart changed; the session may be invalidated.
- **Handler**: if the session is `pending` and the cart was
  modified, re-quote and update the session; if the cart was
  abandoned or already checked out, mark the session `expired`
  and emit `checkout.failed.v1` (no order was created).

### 4.2 `restaurant.offline.v1`

- **Producer**: `restaurant-service`.
- **Reason**: block the session.
- **Handler**: set `pay_blocked = true` with reason
  `restaurant_offline`; if `POST /pay` is called, return 409
  `CHECKOUT_BLOCKED`.

### 4.3 `payment.authorized.v1`

- **Producer**: `payment-service`.
- **Reason**: proceed to order creation.
- **Handler**: create the food order via `food-order-service`
  with `Idempotency-Key: checkout:{session_id}:order`; on
  success, mark session `completed` and emit
  `checkout.completed.v1`.

### 4.4 `payment.failed.v1`

- **Producer**: `payment-service`.
- **Reason**: mark session failed.
- **Handler**: set `state = 'failed'`, `failure_reason_code`,
  `failure_reason_text`; emit `checkout.failed.v1`.

## 5. Reliability

- **Timeouts**: HTTP 1 s; payment-service 2 s; order-service 2
  s; DB 30 s; Kafka 5 s.
- **Retries**: 3 attempts with exponential backoff and jitter;
  Idempotency-Key on all non-idempotent calls.
- **Circuit breakers**: standard 5/30 s.
- **Bulkheads**: per-downstream connection pools.
- **Outbox**: yes, `checkout.outbox`.
- **Inbox**: yes, `checkout.inbox`.
- **DLQ**: every topic has a paired `.dlq`; 30-day retention.
- **Reconciliation**: daily job in `reporting-service` checks
  for `pending` sessions older than 24 hours (the expiration
  cron should have caught them) and for `pending` sessions
  with `pay_blocked = true` for more than 24 hours.

## 6. Correlation IDs

All requests carry `X-Correlation-Id`; the service propagates
it to outbound calls and embeds it in the event envelope.

## 7. Distributed Tracing

OpenTelemetry SDK; one root span per request; named
`POST /v1/checkouts/{id}/pay`, etc. Propagated through Kafka.
Sample 100% on errors, 10% on success in production.

## 8. Threat Surface (per `SECURITY_ARCHITECTURE.md` §18)

| Threat | Mitigation |
|--------|------------|
| Spoofing | mTLS + JWT |
| Tampering | resource-level ownership |
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
| [`address-service`](../address-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [`audit-service`](../audit-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [`branch-service`](../branch-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [`cart-service`](../cart-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [`configuration-service`](../configuration-service/README.md) | DEGRADABLE | degrade (cache / default / flag) |
| [`customer-service`](../customer-service/README.md) | CRITICAL | 503 `DEPENDENCY_UNAVAILABLE` |
| [`feature-flag-service`](../feature-flag-service/README.md) | DEGRADABLE | degrade (cache / default / flag) |
| [`food-order-service`](../food-order-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [`notification-service`](../notification-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [`payment-service`](../payment-service/README.md) | CRITICAL | 503 `DEPENDENCY_UNAVAILABLE` |
| [`pricing-service`](../pricing-service/README.md) | CRITICAL | 503 `DEPENDENCY_UNAVAILABLE` |
| [`restaurant-service`](../restaurant-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |

### Downstream services that depend on this service

| Downstream | Class (from its perspective) |
|---|---|
| [`address-service`](../address-service/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`branch-service`](../branch-service/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`cart-service`](../cart-service/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`food-order-service`](../food-order-service/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`inventory-service`](../inventory-service/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
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

