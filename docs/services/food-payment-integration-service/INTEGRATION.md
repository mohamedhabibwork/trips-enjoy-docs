# food-payment-integration-service — Integration Contract

## 1. Inbound APIs

### 1.1 `POST /v1/food-sagas`

- **Purpose**: Create a saga at checkout. (In practice, this
  service consumes the `checkout.completed.v1` event and creates
  the saga row; the API is exposed for admin / manual creation.)
- **Auth**: Bearer JWT — service-to-service
  (`food-payment-integration.write`) OR admin.
- **Idempotency**: `Idempotency-Key` required
  (`food:<order_id>:create`).
- **Request**:
  ```json
  {
    "food_order_id": "01HZX9C5S3B1L7K0P2F8V4T6YDA",
    "customer_id": "01HZX7C2X1X0M4K6P8F2V1T7YDH",
    "restaurant_id": "01HZX8A2Z1X0M4K6P8F2V1T7YDC",
    "branch_id": "01HZX9B4X2C1N5K7P0F6V3T8YDB",
    "city_id": "01HZX7Y0X9W8M3K5P7F1V0T6YDD",
    "gross_minor": 2350,
    "commission_minor": 470,
    "merchant_net_minor": 1410,
    "courier_net_minor": 470,
    "tip_minor": 0,
    "currency": "EUR",
    "payment_intent_id": "01HZX9E0D0J0L7K0P2F8V4T6YDA",
    "correlation_id": "01HZX9C7T0XK2P9F0V6E4B1MZA"
  }
  ```
- **Response (201)**:
  ```json
  {
    "saga_id": "01HZX9C5S3B1L7K0P2F8V4T6YDA",
    "state": "awaiting_capture",
    "started_at": "2026-07-29T10:42:11.183Z"
  }
  ```
- **Errors**: 400, 401, 403, 409 (duplicate), 422.

### 1.2 `POST /v1/food-sagas/{saga_id}/capture`

- **Purpose**: Manually trigger the capture step. Typically
  driven by the `delivery.completed.v1` consumer; exposed for
  admin retries.
- **Auth**: Bearer JWT — service-to-service
  (`food-payment-integration.write`) OR admin.
- **Idempotency**: `Idempotency-Key` required
  (`food:<order_id>:capture:<attempt>`).
- **Request**: `{}`
- **Response (202)**:
  ```json
  {
    "saga_id": "...",
    "state": "capturing",
    "capture_attempt": 1
  }
  ```
- **Errors**: 401, 403, 404, 409, 422.

### 1.3 `POST /v1/food-sagas/{saga_id}/refund`

- **Purpose**: Apply a full or partial refund.
- **Auth**: Bearer JWT — service-to-service
  (`food-payment-integration.write`) OR admin/support.
- **Idempotency**: required
  (`food:<order_id>:refund:<reason>:<attempt>`).
- **Request**:
  ```json
  {
    "kind": "full",
    "reason": "cancellation",
    "amount_minor": 2350,
    "currency": "EUR"
  }
  ```
  or
  ```json
  {
    "kind": "partial",
    "reason": "quality",
    "amount_minor": 500,
    "currency": "EUR",
    "actor_id": "01HZX…"
  }
  ```
- **Response (202)**:
  ```json
  {
    "saga_id": "...",
    "refund_id": "01HZX…",
    "state": "refunding"
  }
  ```
- **Errors**: 401, 403, 404, 409, 422.

### 1.4 `POST /v1/food-sagas/{saga_id}/force-compensate`

- **Auth**: Bearer JWT — admin (`food_payment.admin`).
- **Idempotency**: required
  (`food:<order_id>:force-compensate:<admin_id>:<timestamp>`).
- **Request**:
  ```json
  {
    "audit_note": "SAGA STUCK FOR 2H; MANUAL REFUND",
    "reason": "manual_override"
  }
  ```
- **Response (202)**: `{ "saga_id": "...", "state": "compensating" }`
- **Errors**: 401, 403, 404, 409, 422.

### 1.5 `GET /v1/food-sagas/{saga_id}`

- **Auth**: Bearer JWT — service OR admin OR the customer
  (`saga.customer_id == sub`) OR the merchant
  (`saga.restaurant_id` is in the merchant's group).
- **Response (200)**:
  ```json
  {
    "saga_id": "01HZX9C5S3B1L7K0P2F8V4T6YDA",
    "state": "completed",
    "gross_minor": 2350,
    "commission_minor": 470,
    "merchant_net_minor": 1410,
    "courier_net_minor": 470,
    "tip_minor": 0,
    "currency": "EUR",
    "started_at": "...",
    "ended_at": "...",
    "steps": [
      { "step": "authorize", "attempt": 1, "outcome": "succeeded",
        "occurred_at": "..." },
      { "step": "capture", "attempt": 1, "outcome": "succeeded",
        "occurred_at": "..." },
      { "step": "post_ledger", "attempt": 1, "outcome": "succeeded",
        "occurred_at": "..." },
      { "step": "accrue_courier", "attempt": 1, "outcome": "succeeded",
        "occurred_at": "..." },
      { "step": "accrue_merchant", "attempt": 1, "outcome": "succeeded",
        "occurred_at": "..." }
    ],
    "refunds": [],
    "compensations": []
  }
  ```

### 1.6 `GET /v1/food-sagas?order_id=…`

- **Auth**: same as 1.5.
- **Query params**: cursor pagination.
- **Response (200)**: list of sagas with `next_cursor`.

### 1.7 `GET /v1/food-sagas/metrics`

- **Auth**: Bearer JWT — admin.
- **Response (200)**:
  ```json
  {
    "started_last_24h": 12345,
    "completed_last_24h": 12290,
    "compensating_last_24h": 32,
    "stuck_sagas": 4,
    "p99_capture_seconds": 42.1
  }
  ```

## 2. Outbound APIs

| Target | Method | URI | Purpose | Timeout | Retry | Circuit |
|--------|--------|-----|---------|---------|-------|---------|
| `payment-service` | POST | `/v1/payment-intents` | create intent (with `capture_mode=manual`) | 2s | 3 | yes |
| `payment-service` | POST | `/v1/payment-intents/{id}/capture` | capture | 2s | 3 | yes |
| `payment-service` | POST | `/v1/payment-intents/{id}/refund` | refund | 2s | 3 | yes |
| `payment-service` | POST | `/v1/payment-intents/{id}/void` | void (when not captured) | 2s | 3 | yes |
| `wallet-service` | POST | `/v1/wallets/{id}/credit` | closed-loop wallet refund | 1s | 3 | yes |
| `ledger-service` | POST | `/v1/postings` | double-entry | 1s | 3 | yes |
| `courier-earnings-service` | POST | `/v1/courier-earnings/accrue` | courier base | 1s | 3 | yes |
| `courier-earnings-service` | POST | `/v1/courier-earnings/tip` | courier tip | 1s | 3 | yes |
| `restaurant-settlement-service` | POST | `/v1/merchant-payouts/accrue` | merchant payable | 1s | 3 | yes |
| `food-order-service` | GET | `/v1/orders/{id}` | enrich | 1s | 3 | yes |
| `customer-service` | GET | `/v1/customers/{id}` | contact (read) | 1s | 3 | yes |
| `notification-service` | POST | `/v1/pushes` | customer-facing | 1s | 3 | yes |
| `support-service` | POST | `/v1/tickets` | open ticket on stuck | 1s | 3 | yes |

## 3. Produced Events

### 3.1 `food.payment.completed.v1`

- **Topic**: `food.payment.completed`
- **Trigger**: all downstream steps (`post_ledger`, `accrue_courier`,
  `accrue_merchant`) succeeded.
- **Partition key**: `saga_id` (the `food_order_id`).
- **Schema**:
  ```json
  {
    "event_id": "01HZX9C8W6K0G3V2Y5N1Q4R7PB",
    "event_name": "food.payment.completed.v1",
    "occurred_at": "2026-07-29T11:08:21.183Z",
    "aggregate_type": "Saga",
    "aggregate_id": "01HZX9C5S3B1L7K0P2F8V4T6YDA",
    "data": {
      "saga_id": "01HZX9C5S3B1L7K0P2F8V4T6YDA",
      "food_order_id": "01HZX9C5S3B1L7K0P2F8V4T6YDA",
      "customer_id": "01HZX7C2X1X0M4K6P8F2V1T7YDH",
      "courier_id": "01HZX8D2Y1X1M5K7P9F3V2T8YDG",
      "restaurant_id": "01HZX8A2Z1X0M4K6P8F2V1T7YDC",
      "branch_id": "01HZX9B4X2C1N5K7P0F6V3T8YDB",
      "city_id": "01HZX7Y0X9W8M3K5P7F1V0T6YDD",
      "gross_minor": 2350,
      "commission_minor": 470,
      "merchant_net_minor": 1410,
      "courier_net_minor": 470,
      "tip_minor": 0,
      "currency": "EUR",
      "completed_at": "..."
    }
  }
  ```
- **Consumers**: `customer-service` (history),
  `restaurant-settlement-service`, `courier-earnings-service`,
  `audit-service`.
- **DLQ**: `food.payment.completed.dlq`.

### 3.2 `food.payment.failed.v1`

- **Topic**: `food.payment.failed`
- **Trigger**: capture step failed and not retriable.
- **Partition key**: `saga_id`
- **Consumers**: `support-service`, `notification-service`,
  `audit-service`.

### 3.3 `food.payment.partial_refund.v1`

- **Topic**: `food.payment.partial_refund`
- **Trigger**: partial refund applied.
- **Partition key**: `saga_id`
- **Schema**:
  ```json
  {
    "event_id": "...",
    "event_name": "food.payment.partial_refund.v1",
    "data": {
      "saga_id": "...",
      "refund_id": "...",
      "amount_minor": 500,
      "currency": "EUR",
      "merchant_debit_minor": 300,
      "courier_debit_minor": 100,
      "commission_debit_minor": 100,
      "reason": "quality",
      "occurred_at": "..."
    }
  }
  ```
- **Consumers**: `restaurant-settlement-service`,
  `courier-earnings-service`, `audit-service`.

### 3.4 `food.payment.full_refund.v1`

- **Topic**: `food.payment.full_refund`
- **Trigger**: full refund applied.
- **Partition key**: `saga_id`
- **Consumers**: `restaurant-settlement-service`,
  `courier-earnings-service`, `audit-service`,
  `notification-service`.

### 3.5 `customer.tip.added.v1`

- **Topic**: `customer.tip.added`
- **Trigger**: tip added (before or after delivery).
- **Partition key**: `saga_id`
- **Consumers**: `courier-earnings-service`.

### 3.6 `merchant.settlement.created.v1`

- **Topic**: `merchant.settlement.created`
- **Trigger**: merchant payable accrued.
- **Partition key**: `saga_id`
- **Consumers**: `restaurant-settlement-service`.

### 3.7 `food_payment_integration.audit.saga_advanced.v1`

- **Topic**: `food_payment_integration.audit.saga_advanced`
- **Trigger**: every step.
- **Partition key**: `saga_id`
- **Consumers**: `audit-service`.

### 3.8 `food_payment_integration.audit.saga_compensated.v1`

- **Topic**: `food_payment_integration.audit.saga_compensated`
- **Trigger**: every compensation.
- **Partition key**: `saga_id`
- **Consumers**: `audit-service`.

## 4. Consumed Events

### 4.1 `delivery.completed.v1`

- **Producer**: `delivery-service`.
- **Reason**: start the capture step.
- **Handler**: advance saga to `capturing`; call
  `payment-service.capture`.
- **Deduplication**: inbox on `event_id`.

### 4.2 `payment.captured.v1`

- **Producer**: `payment-service`.
- **Reason**: capture confirmed; advance.
- **Handler**: advance to `posting_ledger`; post ledger; trigger
  downstream; on success, mark `completed`.

### 4.3 `payment.failed.v1`

- **Producer**: `payment-service`.
- **Reason**: capture failed.
- **Handler**: increment retry; on exhaustion, mark
  `compensating`.

### 4.4 `payment.refund.completed.v1`

- **Producer**: `payment-service`.
- **Reason**: refund applied.
- **Handler**: mark refund `applied`; advance saga to `refunded`.

### 4.5 `food.order.cancelled.v1`

- **Producer**: `food-order-service`.
- **Reason**: order cancelled; refund needed.
- **Handler**: start a refund saga; if not captured, void; if
  captured, refund.

### 4.6 `configuration.updated.v1`

- **Producer**: `configuration-service`.
- **Reason**: reload thresholds.
- **Handler**: refresh in-memory config.

## 5. Reliability

- **Timeouts**: outbound 1s default; `payment-service` 2s.
- **Retries**: 3 with exponential backoff (1s, 4s, 16s) for
  capture and refund. The saga is retried at the saga level too.
- **Circuit breakers**: every outbound call wrapped.
- **Bulkheads**: per-downstream connection pool.
- **Outbox**: yes.
- **Inbox**: yes.
- **DLQ**: every topic has a paired DLQ.
- **Reconciliation**: a daily job at 03:00 UTC compares the
  saga totals against `ledger-service`; drift opens a P1 ticket.

## 6. Correlation IDs

All requests carry `X-Correlation-Id`; emitted events carry the
same in the envelope. Logs and traces are correlated.

## 7. Distributed Tracing

OpenTelemetry; one root span per saga; child spans per step.
`traceparent` propagated through Kafka headers.


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
| [`courier-earnings-service`](../courier-earnings-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [`customer-service`](../customer-service/README.md) | CRITICAL | 503 `DEPENDENCY_UNAVAILABLE` |
| [`delivery-service`](../delivery-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [`food-order-service`](../food-order-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [`ledger-service`](../ledger-service/README.md) | CRITICAL | 503 `DEPENDENCY_UNAVAILABLE` |
| [`notification-service`](../notification-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [`payment-service`](../payment-service/README.md) | CRITICAL | 503 `DEPENDENCY_UNAVAILABLE` |
| [`restaurant-settlement-service`](../restaurant-settlement-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [`support-service`](../support-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [`wallet-service`](../wallet-service/README.md) | CRITICAL | 503 `DEPENDENCY_UNAVAILABLE` |

### Downstream services that depend on this service

| Downstream | Class (from its perspective) |
|---|---|
| [`courier-dispatch-service`](../courier-dispatch-service/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`courier-earnings-service`](../courier-earnings-service/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`customer-service`](../customer-service/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`delivery-service`](../delivery-service/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`food-order-service`](../food-order-service/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`ledger-service`](../ledger-service/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`payment-service`](../payment-service/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`promotion-service`](../promotion-service/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`restaurant-order-mgmt-service`](../restaurant-order-mgmt-service/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`restaurant-settlement-service`](../restaurant-settlement-service/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`support-service`](../support-service/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`wallet-service`](../wallet-service/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |

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

