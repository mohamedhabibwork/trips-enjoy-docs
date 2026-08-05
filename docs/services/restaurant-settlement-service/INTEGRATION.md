# restaurant-settlement-service — Integration Contract

## 1. Inbound APIs

### 1.1 `POST /v1/merchant-payouts/accrue`

- **Purpose**: Accrue the merchant's payable for a food order
  (or a manual adjustment). Called by
  `food-payment-integration-service`.
- **Auth**: Bearer JWT — service-to-service
  (`restaurant-settlement.write`).
- **Idempotency**: `Idempotency-Key` required
  (`merchant:<merchant_id>:order:<food_order_id>:kind:<kind>`).
- **Request**:
  ```json
  {
    "merchant_id": "01HZX7B1Y0W0M3K5P7F1V0T6YDD",
    "restaurant_id": "01HZX8A2Z1X0M4K6P8F2V1T7YDC",
    "branch_id": "01HZX9B4X2C1N5K7P0F6V3T8YDB",
    "city_id": "01HZX7Y0X9W8M3K5P7F1V0T6YDD",
    "food_order_id": "01HZX9C5S3B1L7K0P2F8V4T6YDA",
    "kind": "order",
    "gross_minor": 2350,
    "commission_minor": 470,
    "net_minor": 1880,
    "currency": "EUR",
    "correlation_id": "01HZX9C7T0XK2P9F0V6E4B1MZA"
  }
  ```
- **Response (201)**:
  ```json
  {
    "accrual_id": "01HZX9F1E1K1L8K1P3F9V5T7YDE",
    "merchant_id": "01HZX7B1Y0W0M3K5P7F1V0T6YDD",
    "available_minor": 1880,
    "currency": "EUR"
  }
  ```
- **Errors**: 400, 401, 403, 409 (duplicate), 422.

### 1.2 `GET /v1/merchant-payouts/balance/{merchant_id}`

- **Auth**: Bearer JWT — service OR the merchant's operator.
- **Response (200)**:
  ```json
  {
    "merchant_id": "01HZX7B1Y0W0M3K5P7F1V0T6YDD",
    "available_minor": 188000,
    "pending_minor": 0,
    "lifetime_minor": 1234500,
    "paid_out_minor": 1046500,
    "currency": "EUR",
    "payouts_paused": false,
    "as_of": "2026-07-29T11:09:00.000Z"
  }
  ```

### 1.3 `GET /v1/merchant-payouts?merchant_id=…&from=…&to=…`

- **Auth**: Bearer JWT — service OR the merchant's operator.
- **Query params**: cursor pagination.
- **Response (200)**: list of accruals with `next_cursor`.

### 1.4 `POST /v1/payout-runs`

- **Purpose**: Trigger a payout run manually (admin / scheduled
  job).
- **Auth**: Bearer JWT — admin OR service (the scheduled job).
- **Idempotency**: required
  (`payout-run:<run_date>:<cadence>`).
- **Request**:
  ```json
  { "run_date": "2026-07-29", "cadence": "weekly" }
  ```
- **Response (201)**:
  ```json
  {
    "payout_run_id": "01HZX9F2F2L2L9K2P4F0V6T8YDF",
    "merchant_count": 1234,
    "total_minor": 123450000,
    "currency": "EUR"
  }
  ```
- **Errors**: 400, 401, 403, 409 (already running), 422.

### 1.5 `GET /v1/payout-runs/{id}`

- **Auth**: Bearer JWT — admin OR the merchant's operator (if
  it's their run).
- **Response (200)**: full run with payout list.

### 1.6 `POST /v1/payout-runs/{id}/force`

- **Auth**: Bearer JWT — admin (`merchant.admin`).
- **Idempotency**: required.
- **Request**:
  ```json
  { "audit_note": "..." }
  ```
- **Response (202)**: `{ "payout_run_id": "...", "state": "running" }`

### 1.7 `POST /v1/payout-runs/{id}/cancel`

- **Auth**: Bearer JWT — admin.
- **Idempotency**: required.
- **Request**:
  ```json
  { "audit_note": "..." }
  ```
- **Response (200)**: `{ "payout_run_id": "...", "state": "cancelled" }`

### 1.8 `POST /v1/disputes`

- **Auth**: Bearer JWT — service OR support.
- **Idempotency**: required
  (`dispute:<food_order_id>:<reason>:<attempt>`).
- **Request**:
  ```json
  {
    "merchant_id": "01HZX7B1Y0W0M3K5P7F1V0T6YDD",
    "food_order_id": "01HZX9C5S3B1L7K0P2F8V4T6YDA",
    "amount_minor": 500,
    "currency": "EUR",
    "reason": "quality",
    "evidence": { "ticket_id": "..." }
  }
  ```
- **Response (201)**:
  ```json
  {
    "dispute_id": "01HZX9F3G3M3L0K3P5F1V7T9YDG",
    "state": "open",
    "available_minor": 187500
  }
  ```
- **Errors**: 400, 401, 403, 409 (duplicate), 422.

### 1.9 `POST /v1/disputes/{id}/resolve`

- **Auth**: Bearer JWT — admin.
- **Idempotency**: required.
- **Request**:
  ```json
  { "resolution": "won" | "lost", "audit_note": "..." }
  ```
- **Response (200)**: `{ "dispute_id": "...", "state": "resolved_won" }`

### 1.10 `GET /v1/merchant-statements/{merchant_id}?period=daily|weekly|monthly`

- **Auth**: Bearer JWT — service OR the merchant's operator.
- **Query params**: `from`, `to`, `period`.
- **Response (200)**:
  ```json
  {
    "merchant_id": "01HZX7B1Y0W0M3K5P7F1V0T6YDD",
    "period": "weekly",
    "from": "2026-07-22T00:00:00Z",
    "to": "2026-07-29T00:00:00Z",
    "totals": {
      "orders": 234,
      "gross_minor": 549900,
      "commission_minor": 109980,
      "net_minor": 439920,
      "refunds_minor": 12000,
      "paid_out_minor": 380000
    },
    "items": [
      { "date": "2026-07-22", "orders": 32, "net_minor": 60100 }
    ]
  }
  ```

## 2. Outbound APIs

| Target | Method | URI | Purpose | Timeout | Retry | Circuit |
|--------|--------|-----|---------|---------|-------|---------|
| `merchant-service` | GET | `/v1/merchants/{id}` | enrich (bank ref) | 1s | 3 | yes |
| `payment-service` | POST | `/v1/payouts` | execute payout | 2s | per-payout retry | yes |
| `payment-service` | GET | `/v1/payouts/{id}` | poll status | 1s | 3 | yes |
| `ledger-service` | POST | `/v1/postings` | double-entry | 1s | 3 | yes |
| `ledger-service` | GET | `/v1/accounts/merchant_payable/sum` | reconciliation | 5s | 3 | yes |
| `notification-service` | POST | `/v1/pushes` | merchant statement | 1s | 3 | yes |
| `support-service` | POST | `/v1/tickets` | open ticket on failure | 1s | 3 | yes |

## 3. Produced Events

### 3.1 `merchant.settlement.accrued.v1`

- **Topic**: `merchant.settlement.accrued`
- **Trigger**: every accrual insert.
- **Partition key**: `merchant_id`
- **Schema**:
  ```json
  {
    "event_id": "01HZX9C8W6K0G3V2Y5N1Q4R7PB",
    "event_name": "merchant.settlement.accrued.v1",
    "occurred_at": "2026-07-29T11:08:21.183Z",
    "aggregate_type": "Accrual",
    "aggregate_id": "01HZX9F1E1K1L8K1P3F9V5T7YDE",
    "data": {
      "accrual_id": "01HZX9F1E1K1L8K1P3F9V5T7YDE",
      "merchant_id": "01HZX7B1Y0W0M3K5P7F1V0T6YDD",
      "food_order_id": "01HZX9C5S3B1L7K0P2F8V4T6YDA",
      "kind": "order",
      "gross_minor": 2350,
      "commission_minor": 470,
      "net_minor": 1880,
      "currency": "EUR",
      "available_minor": 1880,
      "accrued_at": "..."
    }
  }
  ```
- **Consumers**: `merchant-service` (UI), `audit-service`.
- **DLQ**: `merchant.settlement.accrued.dlq`.

### 3.2 `merchant.payout.scheduled.v1`

- **Topic**: `merchant.payout.scheduled`
- **Trigger**: payout row created.
- **Partition key**: `merchant_id`
- **Schema**:
  ```json
  {
    "event_id": "...",
    "event_name": "merchant.payout.scheduled.v1",
    "data": {
      "payout_id": "01HZX…",
      "merchant_id": "01HZX…",
      "amount_minor": 188000,
      "currency": "EUR",
      "scheduled_for": "2026-07-29",
      "payout_run_id": "01HZX…"
    }
  }
  ```
- **Consumers**: `payment-service` (triggers bank transfer),
  `audit-service`.

### 3.3 `merchant.payout.completed.v1`

- **Topic**: `merchant.payout.completed`
- **Trigger**: `payment.payout.completed.v1` received.
- **Partition key**: `merchant_id`
- **Consumers**: `merchant-service`, `notification-service`,
  `audit-service`.

### 3.4 `merchant.payout.failed.v1`

- **Topic**: `merchant.payout.failed`
- **Trigger**: retries exhausted.
- **Partition key**: `merchant_id`
- **Consumers**: `support-service`, `notification-service`,
  `audit-service`.

### 3.5 `merchant.dispute.opened.v1`

- **Topic**: `merchant.dispute.opened`
- **Trigger**: dispute created.
- **Partition key**: `merchant_id`
- **Consumers**: `merchant-service`, `audit-service`.

### 3.6 `merchant.dispute.resolved.v1`

- **Topic**: `merchant.dispute.resolved`
- **Trigger**: dispute resolved.
- **Partition key**: `merchant_id`
- **Consumers**: `merchant-service`, `audit-service`.

### 3.7 `restaurant_settlement.audit.ledger_posted.v1`

- **Topic**: `restaurant_settlement.audit.ledger_posted`
- **Trigger**: every accrual insert.
- **Partition key**: `merchant_id`
- **Consumers**: `audit-service`.

## 4. Consumed Events

### 4.1 `food.payment.completed.v1`

- **Producer**: `food-payment-integration-service`.
- **Reason**: accrue merchant payable.
- **Handler**: insert accrual row; update balance.
- **Deduplication**: inbox on `event_id`.

### 4.2 `food.payment.partial_refund.v1`

- **Producer**: `food-payment-integration-service`.
- **Reason**: apply proportional debit.
- **Handler**: insert `refund_partial` accrual.

### 4.3 `food.payment.full_refund.v1`

- **Producer**: `food-payment-integration-service`.
- **Reason**: apply full debit.
- **Handler**: insert `refund_full` accrual.

### 4.4 `merchant.suspended.v1`

- **Producer**: `merchant-service`.
- **Reason**: pause payouts.
- **Handler**: set `merchant_balances.payouts_paused = true`.

### 4.5 `payment.payout.completed.v1`

- **Producer**: `payment-service`.
- **Reason**: payout done.
- **Handler**: mark payout `completed`; update balance.

### 4.6 `payment.payout.failed.v1`

- **Producer**: `payment-service`.
- **Reason**: payout failed.
- **Handler**: increment retry; reschedule or surface.

### 4.7 `configuration.updated.v1`

- **Producer**: `configuration-service`.
- **Reason**: reload.
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
  accruals ledger against the merchant payable account in
  `ledger-service`; drift opens a P1 ticket.

## 6. Correlation IDs

All requests carry `X-Correlation-Id`; emitted events carry the
same in the envelope.

## 7. Distributed Tracing

OpenTelemetry; root span per accrual / payout. `traceparent`
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
| [`food-payment-integration-service`](../food-payment-integration-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [`ledger-service`](../ledger-service/README.md) | CRITICAL | 503 `DEPENDENCY_UNAVAILABLE` |
| [`merchant-service`](../merchant-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [`notification-service`](../notification-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [`payment-service`](../payment-service/README.md) | CRITICAL | 503 `DEPENDENCY_UNAVAILABLE` |
| [`support-service`](../support-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |

### Downstream services that depend on this service

| Downstream | Class (from its perspective) |
|---|---|
| [`food-payment-integration-service`](../food-payment-integration-service/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`ledger-service`](../ledger-service/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`merchant-service`](../merchant-service/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`payment-service`](../payment-service/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |

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

