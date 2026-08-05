# courier-earnings-service — Integration Contract

## 1. Inbound APIs

### 1.1 `POST /v1/courier-earnings/accrue`

- **Purpose**: Accrue a base or tip earning. Called by
  `food-payment-integration-service` (system actor).
- **Auth**: Bearer JWT — service-to-service
  (`courier-earnings.write`).
- **Idempotency**: `Idempotency-Key` required
  (`delivery:<delivery_id>:courier:<courier_id>:type:<type>`).
- **Request**:
  ```json
  {
    "courier_id": "01HZX8D2Y1X1M5K7P9F3V2T8YDG",
    "delivery_id": "01HZX9C8N3B3L9K2P4F0V6T8YDF",
    "food_order_id": "01HZX9C5S3B1L7K0P2F8V4T6YDA",
    "branch_id": "01HZX9B4X2C1N5K7P0F6V3T8YDB",
    "restaurant_id": "01HZX8A2Z1X0M4K6P8F2V1T7YDC",
    "city_id": "01HZX7Y0X9W8M3K5P7F1V0T6YDD",
    "type": "base",
    "amount_minor": 1850,
    "commission_minor": 450,
    "gross_minor": 2300,
    "currency": "EUR",
    "commission_rate": 0.1957,
    "accrued_at": "2026-07-29T11:08:21.183Z"
  }
  ```
- **Response (201)**:
  ```json
  {
    "earning_id": "01HZX9D3A7G7L4K7P9F5V1T3YDM",
    "available_balance_minor": 12450,
    "currency": "EUR"
  }
  ```
- **Errors**:
  - 400 `VALIDATION_FAILED`
  - 401, 403
  - 409 `EARNING_ALREADY_EXISTS` (idempotency replay with same key)
  - 422 `IDEMPOTENCY_KEY_REUSED`

### 1.2 `POST /v1/courier-earnings/tip`

- **Purpose**: Accrue a tip.
- **Auth**: Bearer JWT — service-to-service
  (`courier-earnings.write`).
- **Idempotency**: `Idempotency-Key` required
  (`delivery:<delivery_id>:courier:<courier_id>:type:tip`).
- **Request**:
  ```json
  {
    "courier_id": "01HZX8D2Y1X1M5K7P9F3V2T8YDG",
    "delivery_id": "01HZX9C8N3B3L9K2P4F0V6T8YDF",
    "amount_minor": 500,
    "commission_minor": 0,
    "gross_minor": 500,
    "currency": "EUR",
    "accrued_at": "..."
  }
  ```
- **Response (201)**: same as 1.1.
- **Errors**: same as 1.1.

### 1.3 `GET /v1/courier-earnings/balance/{courier_id}`

- **Auth**: Bearer JWT — service OR the courier (`courier_id == sub`).
- **Response (200)**:
  ```json
  {
    "courier_id": "01HZX8D2Y1X1M5K7P9F3V2T8YDG",
    "available_minor": 12450,
    "pending_minor": 5000,
    "lifetime_minor": 245000,
    "withdrawn_minor": 227550,
    "currency": "EUR",
    "as_of": "2026-07-29T11:09:00.000Z"
  }
  ```

### 1.4 `GET /v1/courier-earnings?courier_id=…&from=…&to=…`

- **Auth**: Bearer JWT — service OR the courier.
- **Query params**: cursor pagination.
- **Response (200)**: list of earnings with `next_cursor`.

### 1.5 `POST /v1/courier-withdrawals`

- **Auth**: Bearer JWT (Keycloak `platform-courier`).
- **Idempotency**: required.
- **Request**:
  ```json
  {
    "amount_minor": 5000,
    "currency": "EUR",
    "destination": "bank",
    "payment_method_token": "01HZX9D4B8H8L5K8P0F6V2T4YDN"
  }
  ```
- **Response (202)**:
  ```json
  {
    "withdrawal_id": "01HZX9D5C9I9L6K9P1F7V3T5YDO",
    "state": "initiated",
    "amount_minor": 5000,
    "currency": "EUR",
    "destination": "bank",
    "created_at": "2026-07-29T11:10:00.000Z"
  }
  ```
- **Errors**:
  - 400, 401, 403
  - 409 `WITHDRAWAL_ALREADY_PENDING`
  - 422 `INSUFFICIENT_BALANCE`, `BELOW_MIN_WITHDRAWAL`,
    `ABOVE_MAX_WITHDRAWAL`

### 1.6 `GET /v1/courier-withdrawals/{id}`

- **Auth**: Bearer JWT — service OR the courier OR admin.
- **Response (200)**: full withdrawal with state history.

### 1.7 `POST /v1/courier-withdrawals/{id}/cancel`

- **Auth**: Bearer JWT — the courier (within 30s of creation) OR
  admin.
- **Idempotency**: required.
- **Response (200)**: `{ "state": "cancelled", "cancelled_at": "..." }`
- **Errors**: 403, 409 `STATE_INVALID`, 422.

### 1.8 `POST /v1/courier-withdrawals/{id}/force_payout`

- **Auth**: Bearer JWT — admin (`courier.admin`).
- **Idempotency**: required.
- **Request**:
  ```json
  { "reason": "manual_override", "audit_note": "..." }
  ```
- **Response (202)**: `{ "withdrawal_id": "...", "state": "payout_inflight" }`
- **Errors**: 401, 403, 409, 422.

### 1.9 `GET /v1/courier-statements/{courier_id}?period=daily|weekly|monthly`

- **Auth**: Bearer JWT — service OR the courier OR admin.
- **Query params**: `from`, `to` (RFC3339), `period`.
- **Response (200)**:
  ```json
  {
    "courier_id": "...",
    "period": "weekly",
    "from": "2026-07-22T00:00:00Z",
    "to": "2026-07-29T00:00:00Z",
    "totals": {
      "deliveries": 47,
      "earnings_minor": 86450,
      "tips_minor": 12000,
      "commission_minor": 21050,
      "withdrawals_minor": 50000
    },
    "items": [
      { "date": "2026-07-22", "earnings_minor": 12500, "tips_minor": 1500 },
      ...
    ]
  }
  ```

## 2. Outbound APIs

| Target | Method | URI | Purpose | Timeout | Retry | Circuit |
|--------|--------|-----|---------|---------|-------|---------|
| `courier-service` | GET | `/v1/couriers/{id}` | enrich | 1s | 3 | yes |
| `food-order-service` | GET | `/v1/orders/{id}` | enrich | 1s | 3 | yes |
| `payment-service` | POST | `/v1/payouts` | execute payout | 2s | per-withdrawal retry | yes |
| `payment-service` | GET | `/v1/payouts/{id}` | poll status | 1s | 3 | yes |
| `ledger-service` | GET | `/v1/accounts/courier_payable/sum` | reconciliation | 5s | 3 | yes |
| `notification-service` | POST | `/v1/pushes` | notify courier | 1s | 3 | yes |
| `support-service` | POST | `/v1/tickets` | open ticket on failure | 1s | 3 | yes |

## 3. Produced Events

### 3.1 `courier.earning.accrued.v1`

- **Topic**: `courier.earning.accrued`
- **Trigger**: every earning insert.
- **Partition key**: `courier_id`
- **Schema**:
  ```json
  {
    "event_id": "01HZX9C8W6K0G3V2Y5N1Q4R7PB",
    "event_name": "courier.earning.accrued.v1",
    "occurred_at": "2026-07-29T11:08:21.183Z",
    "aggregate_type": "Earning",
    "aggregate_id": "01HZX9D3A7G7L4K7P9F5V1T3YDM",
    "data": {
      "earning_id": "01HZX9D3A7G7L4K7P9F5V1T3YDM",
      "courier_id": "01HZX8D2Y1X1M5K7P9F3V2T8YDG",
      "delivery_id": "01HZX9C8N3B3L9K2P4F0V6T8YDF",
      "food_order_id": "01HZX9C5S3B1L7K0P2F8V4T6YDA",
      "type": "base",
      "amount_minor": 1850,
      "commission_minor": 450,
      "gross_minor": 2300,
      "currency": "EUR",
      "available_balance_minor": 12450,
      "accrued_at": "..."
    }
  }
  ```
- **Consumers**: `reporting-service`, `audit-service`.
- **DLQ**: `courier.earning.accrued.dlq`.

### 3.2 `courier.earning.tip_accrued.v1`

Same envelope as `courier.earning.accrued.v1` with `type: "tip"`.

### 3.3 `courier.withdrawal.requested.v1`

- **Topic**: `courier.withdrawal.requested`
- **Trigger**: withdrawal created.
- **Partition key**: `courier_id`
- **Schema**:
  ```json
  {
    "event_id": "...",
    "event_name": "courier.withdrawal.requested.v1",
    "data": {
      "withdrawal_id": "01HZX9D5C9I9L6K9P1F7V3T5YDO",
      "courier_id": "...",
      "amount_minor": 5000,
      "currency": "EUR",
      "destination": "bank",
      "requested_at": "..."
    }
  }
  ```
- **Consumers**: `payment-service` (triggers payout), `audit-service`.

### 3.4 `courier.withdrawal.completed.v1`

- **Topic**: `courier.withdrawal.completed`
- **Trigger**: `payment.payout.completed.v1` received.
- **Partition key**: `courier_id`
- **Consumers**: `notification-service`, `audit-service`.

### 3.5 `courier.withdrawal.failed.v1`

- **Topic**: `courier.withdrawal.failed`
- **Trigger**: retries exhausted.
- **Partition key**: `courier_id`
- **Consumers**: `support-service`, `notification-service`,
  `audit-service`.

### 3.6 `courier_earnings.audit.ledger_posted.v1`

- **Topic**: `courier_earnings.audit.ledger_posted`
- **Trigger**: every earning insert.
- **Partition key**: `courier_id`
- **Consumers**: `audit-service`.

### 3.7 `courier_earnings.audit.reconciliation_drift.v1`

- **Topic**: `courier_earnings.audit.reconciliation_drift`
- **Trigger**: daily reconciliation reports drift.
- **Consumers**: `admin-service`, `support-service`, `audit-service`.

## 4. Consumed Events

### 4.1 `delivery.completed.v1`

- **Producer**: `delivery-service`.
- **Reason**: accrue base earning.
- **Handler**: insert earning row; update balance.
- **Deduplication**: inbox on `event_id`.

### 4.2 `food.payment.completed.v1`

- **Producer**: `food-payment-integration-service`.
- **Reason**: saga completed; the courier's base is now final.
- **Handler**: no-op on the ledger (the earning is already
  accrued); the event is used for cross-checks.

### 4.3 `customer.tip.added.v1`

- **Producer**: `food-payment-integration-service`.
- **Reason**: tip added after delivery.
- **Handler**: insert tip earning row.
- **Deduplication**: inbox on `event_id`.

### 4.4 `payment.payout.completed.v1`

- **Producer**: `payment-service`.
- **Reason**: payout finished.
- **Handler**: mark withdrawal `completed`; update balance.

### 4.5 `payment.payout.failed.v1`

- **Producer**: `payment-service`.
- **Reason**: payout failed.
- **Handler**: increment retry; reschedule or surface.

### 4.6 `ledger.posted.v1`

- **Producer**: `ledger-service`.
- **Reason**: reconciliation check.
- **Handler**: cross-check the courier payable account against
  the earnings ledger.

### 4.7 `configuration.updated.v1`

- **Producer**: `configuration-service`.
- **Reason**: reload thresholds.
- **Handler**: refresh in-memory config.

## 5. Reliability

- **Timeouts**: outbound 1s default; `payment-service` 2s.
- **Retries**: 3 with exponential backoff on outbound.
- **Circuit breakers**: every outbound call wrapped.
- **Bulkheads**: per-downstream connection pool.
- **Outbox**: yes.
- **Inbox**: yes.
- **DLQ**: every topic has a paired DLQ.
- **Reconciliation**: a daily job at 03:00 UTC compares the
  earnings ledger against the ledger-service courier payable
  account; drift opens a P1 ticket.

## 6. Correlation IDs

All requests carry `X-Correlation-Id`; emitted events carry the
same in the envelope. Logs and traces are correlated.

## 7. Distributed Tracing

OpenTelemetry; one root span per accrual / withdrawal; child spans
for DB writes, downstream calls, outbox publish. `traceparent`
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
| [`delivery-service`](../delivery-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [`food-order-service`](../food-order-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [`food-payment-integration-service`](../food-payment-integration-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [`ledger-service`](../ledger-service/README.md) | CRITICAL | 503 `DEPENDENCY_UNAVAILABLE` |
| [`notification-service`](../notification-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [`payment-service`](../payment-service/README.md) | CRITICAL | 503 `DEPENDENCY_UNAVAILABLE` |
| [`reporting-service`](../reporting-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [`support-service`](../support-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [`wallet-service`](../wallet-service/README.md) | CRITICAL | 503 `DEPENDENCY_UNAVAILABLE` |

### Downstream services that depend on this service

| Downstream | Class (from its perspective) |
|---|---|
| [`courier-service`](../courier-service/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`delivery-service`](../delivery-service/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`food-payment-integration-service`](../food-payment-integration-service/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`ledger-service`](../ledger-service/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
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

