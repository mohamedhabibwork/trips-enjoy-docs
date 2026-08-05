# Promotion Service — Integration Contract

## 1. Inbound APIs

All endpoints are versioned under `/v1`. Auth: bearer JWT (RS256,
Keycloak JWKS). Errors use the standard envelope.

### 1.1 `POST /v1/promotions`

- **Purpose**: Create a new promotion.
- **Auth**: Bearer JWT. Required role: `promotion.admin`. Required
  header: `X-Audit-Reason`. High-value (large cap / long duration):
  `X-Signature`.
- **Idempotency**: `Idempotency-Key` required.
- **Request**:
  ```json
  {
    "code": "SUMMER25",
    "name": "Summer 25% off",
    "type": "PERCENT_OFF",
    "discount": { "percent": 25 },
    "currency": "EUR",
    "min_cart_value_minor": 2000,
    "max_discount_minor": 1000,
    "per_user_cap": 1,
    "overall_cap": 10000,
    "eligible_segments": ["all"],
    "eligible_regions": ["eu-west"],
    "eligible_branches": [],
    "eligible_products": [],
    "automatic": false,
    "starts_at": "2026-07-01T00:00:00Z",
    "ends_at": "2026-08-01T00:00:00Z",
    "reason": "Summer campaign"
  }
  ```
- **Response (201)**:
  ```json
  {
    "id": "01HZX9C5S3B1L7K0P2F8V4T6YDA",
    "code": "SUMMER25",
    "type": "PERCENT_OFF",
    "starts_at": "2026-07-01T00:00:00Z",
    "ends_at": "2026-08-01T00:00:00Z",
    "correlation_id": "01HZX9C7T0XK2P9F0V6E4B1MZA"
  }
  ```
- **Errors**:
  - 400 `VALIDATION_FAILED` / `AUDIT_REASON_REQUIRED`.
  - 401 / 403.
  - 409 `PROMOTION_EXISTS`.
  - 422 `VALIDATION_FAILED` (schema mismatch).

### 1.2 `GET /v1/promotions/{code}`

- **Purpose**: Read a promotion by code.
- **Auth**: Bearer JWT. Required scope: `promotion.read`.
- **Response (200)**: the promotion JSON.
- **Errors**: 401 / 403 / 404.

### 1.3 `POST /v1/promotions/{code}/disable`

- **Purpose**: Disable a promotion.
- **Auth**: Bearer JWT. Required role: `promotion.admin`.
- **Idempotency**: `Idempotency-Key` required.
- **Request**: `{ "reason": "Abuse detected" }`.
- **Response (200)**: confirmation.
- **Errors**: 401 / 403 / 404.

### 1.4 `POST /v1/promotions/validate`

- **Purpose**: Check whether a code applies to a cart context.
- **Auth**: Bearer JWT. Required scope: `promotion.read`.
- **Idempotency**: not required (read-only).
- **Request**:
  ```json
  {
    "code": "SUMMER25",
    "customer_id": "01HZX…",
    "cart": {
      "id": "01HZX…",
      "branch_id": "01HZX…",
      "items": [{ "product_id": "01HZX…", "quantity": 2 }],
      "total_minor": 3500,
      "currency": "EUR"
    }
  }
  ```
- **Response (200)**:
  ```json
  {
    "valid": true,
    "promotion": { "id": "01HZX…", "code": "SUMMER25", "type": "PERCENT_OFF" },
    "discount_minor": 875,
    "currency": "EUR",
    "line": { "code": "SUMMER25", "label": "Summer 25% off", "amount_minor": 875 }
  }
  ```
- **Errors**:
  - 404 `PROMOTION_NOT_FOUND`.
  - 410 `PROMOTION_EXPIRED`.
  - 409 `PROMOTION_NOT_STARTED` / `PROMOTION_CAP_REACHED`.
  - 422 `PROMOTION_CURRENCY_MISMATCH` / `PROMOTION_MIN_CART_VALUE` /
    `PROMOTION_PRODUCT_INELIGIBLE`.

### 1.5 `POST /v1/promotions/redeem`

- **Purpose**: Record a redemption with idempotency.
- **Auth**: Bearer JWT. Required scope: `promotion.redeem`.
- **Idempotency**: `Idempotency-Key` required.
- **Request**:
  ```json
  {
    "code": "SUMMER25",
    "cart_id": "01HZX…",
    "customer_id": "01HZX…",
    "cart_total_minor": 3500,
    "currency": "EUR",
    "fraud_context": { "device_id": "…", "ip": "…" }
  }
  ```
- **Response (200)**:
  ```json
  {
    "redemption_id": "01HZX…",
    "promotion": { "id": "01HZX…", "code": "SUMMER25" },
    "discount_minor": 875,
    "currency": "EUR",
    "line": { "code": "SUMMER25", "label": "Summer 25% off", "amount_minor": 875 }
  }
  ```
- **Errors**:
  - 409 `PROMOTION_ALREADY_REDEEMED` (idempotent replay returns 200).
  - 410 `PROMOTION_EXPIRED`.
  - 409 `PROMOTION_CAP_REACHED`.
  - 422 `PROMOTION_CURRENCY_MISMATCH` / `PROMOTION_MIN_CART_VALUE`.
  - 403 `USER_SUSPENDED` / `PROMOTION_FRAUD_BLOCKED`.
  - 422 `IDEMPOTENCY_KEY_REUSED`.

### 1.6 `GET /v1/promotions/{code}/redemptions`

- **Purpose**: List redemptions for a promotion (admin).
- **Auth**: Bearer JWT. Required role: `promotion.admin`.
- **Query params**: `cursor`, `limit`, `from`, `to`.
- **Response (200)**: paginated list.
- **Errors**: 401 / 403 / 404.

## 2. Outbound APIs

| Target | Method | URI | Purpose | Timeout | Retry | Circuit |
|--------|--------|-----|---------|---------|-------|---------|
| `customer-service` | GET | `/v1/customers/{id}` | read segment | 1s | 3 | yes |
| `configuration-service` | GET | `/v1/configurations/{key}` | read rules | 1s | 3 | yes |
| `fraud-risk-service` | POST | `/v1/risk/score` | risk score | 500ms | 1 | yes |

On `CIRCUIT_OPEN` for `fraud-risk-service`, default to `allow` with
a warning log.

## 3. Produced Events

### 3.1 `promotion.created.v1`

- **Producer**: `promotion-service`.
- **Topic**: `promotion.created`.
- **Trigger**: a new promotion is committed.
- **Schema version**: 1.
- **Partition key**: `tenant_id`.
- **Schema**:
  ```json
  {
    "event_id": "01HZX9C8W6K0G3V2Y5N1Q4R7PB",
    "event_name": "promotion.created.v1",
    "occurred_at": "2026-07-29T10:42:11.183Z",
    "schema_version": 1,
    "producer": "promotion-service",
    "tenant_id": "global",
    "correlation_id": "01HZX9C7T0XK2P9F0V6E4B1MZA",
    "aggregate_type": "Promotion",
    "aggregate_id": "01HZX9C5S3B1L7K0P2F8V4T6YDA",
    "data": {
      "code": "SUMMER25",
      "type": "PERCENT_OFF",
      "starts_at": "2026-07-01T00:00:00Z",
      "ends_at": "2026-08-01T00:00:00Z",
      "currency": "EUR"
    }
  }
  ```
- **Retry / DLQ**: outbox / `promotion.created.dlq`.

### 3.2 `promotion.disabled.v1`

Same shape as 3.1 with `data.reason` and the disable time.

### 3.3 `promotion.redeemed.v1`

- **Producer**: `promotion-service`.
- **Topic**: `promotion.redeemed`.
- **Trigger**: a successful redemption.
- **Schema version**: 1.
- **Partition key**: `promotion_id`.
- **Schema**:
  ```json
  {
    "event_id": "01HZX9C8W6K0G3V2Y5N1Q4R7PB",
    "event_name": "promotion.redeemed.v1",
    "occurred_at": "2026-07-29T10:42:11.183Z",
    "schema_version": 1,
    "producer": "promotion-service",
    "tenant_id": "global",
    "correlation_id": "01HZX9C7T0XK2P9F0V6E4B1MZA",
    "aggregate_type": "Promotion",
    "aggregate_id": "01HZX9C5S3B1L7K0P2F8V4T6YDA",
    "data": {
      "code": "SUMMER25",
      "redemption_id": "01HZX…",
      "cart_id": "01HZX…",
      "customer_id": "01HZX…",
      "discount_minor": 875,
      "currency": "EUR",
      "result": "success"
    }
  }
  ```
- **Retry / DLQ**: outbox / `promotion.redeemed.dlq`.

## 4. Consumed Events

### 4.1 `customer.segment.changed.v1`

- **Producer**: `customer-service`.
- **Reason**: segment rules may now match.
- **Handler**: invalidate per-segment cache.
- **Deduplication**: inbox.
- **Retry / DLQ**: 3 / DLQ.

### 4.2 `customer.suspended.v1`

- **Producer**: `customer-service`.
- **Reason**: suspended customers cannot redeem.
- **Handler**: add `customer_id` to a Redis `blocked_users` set with
  TTL = 24h or until `customer.reinstated.v1`.
- **Deduplication**: inbox.
- **Retry / DLQ**: 3 / DLQ.

### 4.3 `customer.created.v1`

- **Producer**: `customer-service`.
- **Reason**: pre-warm per-user caches.
- **Handler**: optional; cache a sentinel.
- **Deduplication**: inbox.
- **Retry / DLQ**: 3 / DLQ.

## 5. Reliability

- **Timeouts**: HTTP 1s; DB 30s; Kafka publish 5s.
- **Retries**: bounded 3 with exponential backoff + jitter.
- **Circuit breakers**: every outbound; on `CIRCUIT_OPEN`, fall back
  to cached values; on cache miss, the redeem endpoint returns 503
  unless the call is a duplicate.
- **Bulkheads**: separate outbound pool per dependency.
- **Outbox**: yes.
- **Inbox**: yes.
- **DLQ**: every topic above has a paired DLQ.
- **Reconciliation**: daily job verifies per-user and overall caps
  from `redemptions`; drift opens a `support.ticket`.

## 6. Correlation IDs

Every request carries `X-Correlation-Id`; the service returns it in
the response header and embeds it in the event envelope and the
`audit_log` row.

## 7. Distributed Tracing

OpenTelemetry: one root span per request; child spans for DB, Redis,
Kafka, customer-service, configuration-service, fraud-risk-service.
`traceparent` propagated through Kafka headers. Sample rate 100% for
errors, 10% for successes.


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
| [`cart-service`](../cart-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [`checkout-service`](../checkout-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [`configuration-service`](../configuration-service/README.md) | DEGRADABLE | degrade (cache / default / flag) |
| [`customer-service`](../customer-service/README.md) | CRITICAL | 503 `DEPENDENCY_UNAVAILABLE` |
| [`food-order-service`](../food-order-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [`food-payment-integration-service`](../food-payment-integration-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [`fraud-risk-service`](../fraud-risk-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [`notification-service`](../notification-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [`pricing-service`](../pricing-service/README.md) | CRITICAL | 503 `DEPENDENCY_UNAVAILABLE` |

### Downstream services that depend on this service

| Downstream | Class (from its perspective) |
|---|---|
| [`cart-service`](../cart-service/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`communication-gateway-service`](../communication-gateway-service/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`configuration-service`](../configuration-service/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`customer-service`](../customer-service/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`loyalty-service`](../loyalty-service/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`notification-service`](../notification-service/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`pricing-service`](../pricing-service/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |

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

