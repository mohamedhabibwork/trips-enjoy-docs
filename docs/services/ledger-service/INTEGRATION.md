# ledger-service — Integration Contract

## 1. Inbound APIs

### 1.1 `POST /v1/postings`

- **Purpose**: Post a balanced double-entry posting. The
  primary write path of the ledger.
- **Auth**: Bearer JWT — service-to-service (`ledger.write`).
- **Idempotency**: `Idempotency-Key` required
  (e.g. `payment:<id>:capture`, `wallet:<id>:credit`).
- **Request**:
  ```json
  {
    "posted_at": "2026-07-29T11:08:21.183Z",
    "description": "Payment captured for order 12345",
    "source_event_id": "01HZX…",
    "source_event_name": "payment.captured.v1",
    "entries": [
      { "account_code": "1100_cash_eur", "side": "debit", "amount_minor": 2350 },
      { "account_code": "2100_customer_receivable", "side": "credit", "amount_minor": 2350 }
    ]
  }
  ```
- **Response (201)**:
  ```json
  {
    "posting_id": "01HZX…",
    "posted_at": "2026-07-29T11:08:21.183Z",
    "entries": [
      { "account_code": "1100_cash_eur", "side": "debit", "amount_minor": 2350 },
      { "account_code": "2100_customer_receivable", "side": "credit", "amount_minor": 2350 }
    ]
  }
  ```
- **Errors**:
  - 400 `VALIDATION_FAILED`
  - 401, 403
  - 409 (duplicate idempotency)
  - 422 `UNBALANCED_POSTING`, `ACCOUNT_NOT_FOUND`,
    `CURRENCY_MISMATCH`, `TIMESTAMP_OUT_OF_BOUNDS`
  - 422 `IDEMPOTENCY_KEY_REUSED`

### 1.2 `POST /v1/journal-entries`

- **Purpose**: Manual journal entry (admin).
- **Auth**: Bearer JWT — admin (`ledger.admin`).
- **Idempotency**: required.
- **Request**:
  ```json
  {
    "description": "Manual correction for order 12345",
    "audit_note": "Correcting a misclassified commission",
    "entries": [
      { "account_code": "5100_commission_revenue", "side": "debit", "amount_minor": 50 },
      { "account_code": "2100_customer_receivable", "side": "credit", "amount_minor": 50 }
    ]
  }
  ```
- **Response (201)**: same as 1.1.
- **Errors**: 401, 403, 409, 422 (`AUDIT_NOTE_REQUIRED` if
  `audit_note` is too short).

### 1.3 `GET /v1/postings/{id}`

- **Auth**: Bearer JWT — service OR admin.
- **Response (200)**: full posting with entries.

### 1.4 `GET /v1/postings?account=…&from=…&to=…`

- **Auth**: Bearer JWT — service OR admin.
- **Query params**: cursor pagination.
- **Response (200)**: list of postings with `next_cursor`.

### 1.5 `GET /v1/accounts`

- **Auth**: Bearer JWT — service OR admin.
- **Response (200)**: list of accounts (current version).

### 1.6 `GET /v1/accounts/{code}`

- **Auth**: Bearer JWT — service OR admin.
- **Response (200)**:
  ```json
  {
    "code": "1100_cash_eur",
    "name": "Cash (EUR)",
    "type": "asset",
    "currency": "EUR",
    "parent_code": "1000_assets",
    "version": 3,
    "valid_from": "2026-01-01T00:00:00Z",
    "valid_to": null
  }
  ```

### 1.7 `GET /v1/accounts/{code}/balance`

- **Auth**: Bearer JWT — service OR admin.
- **Response (200)**:
  ```json
  {
    "account_code": "1100_cash_eur",
    "currency": "EUR",
    "debit_total_minor": 1234500,
    "credit_total_minor": 980000,
    "balance_minor": 254500,
    "as_of": "2026-07-29T11:09:00.000Z"
  }
  ```

### 1.8 `GET /v1/accounts/{code}/balance?from=…&to=…`

- **Auth**: Bearer JWT — service OR admin.
- **Query params**: `from`, `to` (RFC3339).
- **Response (200)**:
  ```json
  {
    "account_code": "1100_cash_eur",
    "currency": "EUR",
    "debit_total_minor": 50000,
    "credit_total_minor": 30000,
    "balance_minor": 20000,
    "from": "2026-07-22T00:00:00Z",
    "to": "2026-07-29T00:00:00Z"
  }
  ```

### 1.9 `GET /v1/reports/trial-balance?date=…`

- **Auth**: Bearer JWT — admin.
- **Query params**: `date` (RFC3339 date).
- **Response (200)**:
  ```json
  {
    "as_of": "2026-07-29T00:00:00Z",
    "currency": "EUR",
    "accounts": [
      { "code": "1100_cash_eur", "name": "Cash (EUR)",
        "type": "asset", "debit_minor": 1234500, "credit_minor": 980000,
        "balance_minor": 254500 }
    ],
    "totals": {
      "debit_minor": 5000000,
      "credit_minor": 5000000,
      "balanced": true,
      "drift_minor": 0
    }
  }
  ```

### 1.10 `GET /v1/reports/balance-sheet?date=…`

- **Auth**: Bearer JWT — admin.
- **Response (200)**:
  ```json
  {
    "as_of": "2026-07-29T00:00:00Z",
    "assets": [{ "code": "1100_cash_eur", "balance_minor": 254500 }],
    "liabilities": [{ "code": "2100_customer_receivable", "balance_minor": 100000 }],
    "equity": [{ "code": "3100_platform_equity", "balance_minor": 154500 }],
    "totals": {
      "assets_minor": 254500,
      "liabilities_plus_equity_minor": 254500,
      "balanced": true
    }
  }
  ```

### 1.11 `GET /v1/reports/income-statement?from=…&to=…`

- **Auth**: Bearer JWT — admin.
- **Response (200)**:
  ```json
  {
    "from": "2026-07-01T00:00:00Z",
    "to": "2026-07-29T00:00:00Z",
    "currency": "EUR",
    "revenue": [
      { "code": "4100_commission_revenue", "amount_minor": 500000 }
    ],
    "expenses": [
      { "code": "6100_payment_processing_fees", "amount_minor": 30000 }
    ],
    "totals": {
      "revenue_minor": 500000,
      "expense_minor": 30000,
      "net_income_minor": 470000
    }
  }
  ```

## 2. Outbound APIs

None. The ledger is a closed system; it does not call other
services.

## 3. Produced Events

### 3.1 `ledger.posted.v1`

- **Topic**: `ledger.posted`
- **Trigger**: every posting.
- **Partition key**: `posting_id`
- **Schema**:
  ```json
  {
    "event_id": "01HZX9C8W6K0G3V2Y5N1Q4R7PB",
    "event_name": "ledger.posted.v1",
    "occurred_at": "2026-07-29T11:08:21.183Z",
    "aggregate_type": "Posting",
    "aggregate_id": "01HZX…",
    "data": {
      "posting_id": "01HZX…",
      "posted_at": "2026-07-29T11:08:21.183Z",
      "description": "Payment captured for order 12345",
      "source_event_id": "01HZX…",
      "source_event_name": "payment.captured.v1",
      "entries": [
        { "account_code": "1100_cash_eur", "side": "debit", "amount_minor": 2350 },
        { "account_code": "2100_customer_receivable", "side": "credit", "amount_minor": 2350 }
      ],
      "correlation_id": "01HZX9C7T0XK2P9F0V6E4B1MZA"
    }
  }
  ```
- **Consumers**: `reporting-service`, `audit-service`, the
  financial services (for reconciliation).
- **DLQ**: `ledger.posted.dlq`.

### 3.2 `ledger.audit.journal_entry_logged.v1`

- **Topic**: `ledger.audit.journal_entry_logged`
- **Trigger**: every posting (1/100 sampled for normal; 100% for
  manual).
- **Partition key**: `posting_id`
- **Consumers**: `audit-service`.

### 3.3 `ledger.audit.reconciliation_drift.v1`

- **Topic**: `ledger.audit.reconciliation_drift`
- **Trigger**: daily reconciliation reports drift.
- **Consumers**: `admin-service`, ``admin-service` (support module)`.

## 4. Consumed Events

The ledger consumes money-movement events to post
asynchronously. The primary write path is `POST /v1/postings`;
events are an alternative. The full list of consumed events is
in `WORKFLOWS.md` (each workflow documents the events).

### 4.1 `payment.captured.v1`

- **Producer**: `payment-service`.
- **Reason**: Money in: customer → platform.
- **Handler**: Post double-entry.
- **Deduplication / Retry / Failure**: inbox keyed by
  `event_id` / 3 with exponential backoff / DLQ.


### 4.2 `payment.refund.completed.v1`

- **Producer**: `payment-service`.
- **Reason**: Money out: platform → customer.
- **Handler**: Post double-entry.
- **Deduplication / Retry / Failure**: inbox keyed by
  `event_id` / 3 with exponential backoff / DLQ.


### 4.3 `wallet.held.v1`

- **Producer**: ``payment-service` (wallet)`.
- **Reason**: Wallet hold created.
- **Handler**: Post double-entry.
- **Deduplication / Retry / Failure**: inbox keyed by
  `event_id` / 3 with exponential backoff / DLQ.


### 4.4 `wallet.released.v1`

- **Producer**: ``payment-service` (wallet)`.
- **Reason**: Wallet hold released.
- **Handler**: Post double-entry.
- **Deduplication / Retry / Failure**: inbox keyed by
  `event_id` / 3 with exponential backoff / DLQ.

### 4.5 `trip.reward.granted.v1` (informational)

- **Producer**: `trip-service`.
- **Topic**: `trip.reward.granted`.
- **Reason**: A per-trip guaranteed reward was granted. The
  operational postings flow through ``payment-service` (driver earnings)`
  (driver top-up → `6302_guaranteed_minimum`) and ``payment-service` (wallet)`
  (customer credit → `2100_customer_credit_liability`); the
  ledger is an informational consumer that persists the event
  for audit and runs the daily reconciliation against the
  operational layer.
- **Handler**: append-only insert into the audit ledger; no
  balancing posting is created from this event alone (the
  operational services own the canonical postings).
- **Deduplication / Retry / Failure**: inbox keyed by
  `event_id` / 3 with exponential backoff / DLQ.

### 4.6 `trip.reward.reversed.v1` (informational)

- **Producer**: `trip-service`.
- **Topic**: `trip.reward.reversed`.
- **Reason**: A previously granted per-trip reward was reversed
  (e.g. trip disputed). The downstream operational services
  (``payment-service` (driver earnings)`, ``payment-service` (wallet)`) post the reversing
  rows; the ledger persists the event for audit and the
  reconciliation job detects the closing position.
- **Handler**: append-only insert; no balancing posting.
- **Deduplication / Retry / Failure**: inbox keyed by
  `event_id` / 3 with exponential backoff / DLQ.



## 5. Reliability

- **Timeouts**: 1s default; chart-of-accounts read 100ms.
- **Retries**: 3 with exponential backoff.
- **Circuit breakers**: N/A (no outbound calls).
- **Bulkheads**: N/A.
- **Outbox**: yes.
- **Inbox**: yes.
- **DLQ**: every topic has a paired DLQ.
- **Reconciliation**: a daily job at 04:00 UTC compares the
  operational layers' totals against the ledger's; drift opens a
  P1 ticket.

## 6. Correlation IDs

All requests carry `X-Correlation-Id`; emitted events carry the
same in the envelope.

## 7. Distributed Tracing

OpenTelemetry; root span per posting. `traceparent` propagated
through Kafka headers.


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
[`DOWNSTREAM_ERROR_CATALOG.md` 5](../../architecture/DOWNSTREAM_ERROR_CATALOG.md)
(forward verbatim, translate, degrade, or reject) and includes
a `downstream` block identifying the original source.

### Upstream services this service depends on

| Upstream | Class | Behavior on failure |
|---|---|---|
| [`admin-service`](../admin-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [`audit-service`](../audit-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [`configuration-service`](../configuration-service/README.md) | DEGRADABLE | degrade (cache / default / flag) |
| [``payment-service` (courier earnings)`](../payment-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [``payment-service` (driver earnings)`](../payment-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [``payment-service` (food saga)`](../payment-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [`payment-service`](../payment-service/README.md) | CRITICAL | 503 `DEPENDENCY_UNAVAILABLE` |
| [`reporting-service`](../reporting-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [``payment-service` (merchant settlement)`](../payment-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [``payment-service` (ride saga)`](../payment-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [``admin-service` (support module)`](../admin-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [``payment-service` (wallet)`](../payment-service/README.md) | CRITICAL | 503 `DEPENDENCY_UNAVAILABLE` |

### Downstream services that depend on this service

| Downstream | Class (from its perspective) |
|---|---|
| [``courier-service` (dispatch)`](../courier-service/README.md) | _see [`SERVICE_ISOLATION.md` 2](../../architecture/SERVICE_ISOLATION.md)_ |
| [``payment-service` (courier earnings)`](../payment-service/README.md) | _see [`SERVICE_ISOLATION.md` 2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`customer-service`](../customer-service/README.md) | _see [`SERVICE_ISOLATION.md` 2](../../architecture/SERVICE_ISOLATION.md)_ |
| [``courier-service` (delivery)`](../courier-service/README.md) | _see [`SERVICE_ISOLATION.md` 2](../../architecture/SERVICE_ISOLATION.md)_ |
| [``payment-service` (driver earnings)`](../payment-service/README.md) | _see [`SERVICE_ISOLATION.md` 2](../../architecture/SERVICE_ISOLATION.md)_ |
| [``payment-service` (food saga)`](../payment-service/README.md) | _see [`SERVICE_ISOLATION.md` 2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`identity-service`](../identity-service/README.md) | _see [`SERVICE_ISOLATION.md` 2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`payment-service`](../payment-service/README.md) | _see [`SERVICE_ISOLATION.md` 2](../../architecture/SERVICE_ISOLATION.md)_ |
| [``payment-service` (merchant settlement)`](../payment-service/README.md) | _see [`SERVICE_ISOLATION.md` 2](../../architecture/SERVICE_ISOLATION.md)_ |
| [``payment-service` (ride saga)`](../payment-service/README.md) | _see [`SERVICE_ISOLATION.md` 2](../../architecture/SERVICE_ISOLATION.md)_ |
| [``payment-service` (wallet)`](../payment-service/README.md) | _see [`SERVICE_ISOLATION.md` 2](../../architecture/SERVICE_ISOLATION.md)_ |

### Per-downstream configuration

Per-downstream timeout / bulkhead / circuit / retry / fallback
configuration lives in the service's application config
(Kotlin: `application.yml` under `platform.outbounds.*`;
Go: `internal/outbounds/manifest.yaml`). The shared library
(`platform-spring-boot-bulkhead` for Kotlin, `internal/bulkhead`
for Go) reads the manifest and wires up the isolation pattern.

### Error envelope

Every error response uses the platform envelope defined in
[`../../shared/CONVENTIONS.md` 1](../../shared/CONVENTIONS.md)
(RFC 7807 + `downstream` block). The codes this service emits
are in 1 of this document; the canonical catalog is in
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
- [`../../README.md`](../../README.md) — services overview (the catalog of all 20 services)
- [`../../../main.md`](../../../main.md) — top-level platform specification (architecture, Keycloak, PostgreSQL 18, messaging, observability baseline)

## Conductor Workers

This service runs Conductor workers for the following workflows per
[ADR-0018](../architecture/adrs/0018-workflow-engine-conductor.md) and
[`shared/CONDUCTOR_WORKFLOWS.md`](../shared/CONDUCTOR_WORKFLOWS.md).
Workers are colocated in this service's binary; SDK: **conductor-kotlin v3.x**.

| Workflow ID | Tasks owned | Idempotency-Key namespace |
|---|---|---|
| Workflow ID | Tasks owned | Idempotency-Key namespace |
|---|---|---|
| `wf.phase7.reward_grant.v1` | ledger_service_posting | `trip:{trip_id}:reward:ledger:posting` |
| `wf.phase7.reward_reversal.v1` | ledger_service_reverse_posting | `trip:{trip_id}:reward:ledger:reverse` |
| `wf.refund.standard.v1` | ledger_service_debit_posting | `refund:{refund_id}:ledger:posting` |
| `wf.refund.partial.v1` | ledger_service_debit_posting | `refund:{refund_id}:ledger:posting` |
| `wf.refund.food_reject.v1` | ledger_service_debit_posting | `refund:{refund_id}:ledger:posting` |
| `wf.refund.cancellation.v1` | ledger_service_debit_posting | `refund:{refund_id}:ledger:posting` |
| `wf.refund.dispute.v1` | ledger_service_debit_posting | `refund:{refund_id}:ledger:posting` |
| `wf.refund.cod_failed.v1` | ledger_service_debit_posting | `refund:{refund_id}:ledger:posting` |


### Kafka signal mapping

| Topic | Signal | Triggers |
|---|---|---|
| (no inbound Kafka signals — REST trigger only or worker is reactive to conductor-kafka-bridge events) | – | – |


### Compensation responsibilities

This service implements the following compensation tasks; see
[`shared/CONDUCTOR_WORKFLOWS.md`](../shared/CONDUCTOR_WORKFLOWS.md) 4 for
ordering rules.

| Forward task | Compensation task | Reversibility |
|---|---|---|
| `ledger_service_posting` | `ledger.reverse_posting` | append-only — reversal is a new posting |
| `ledger_service_debit_posting` | `ledger.reverse_posting` | append-only — reversal is a new posting |


### Configuration keys

- `conductor.server.url` — set by Helm per env (e.g. `https://conductor.prod.uber.io`)
- `conductor.task.<task_name>.timeout_seconds` — default 30s
- `conductor.task.<task_name>.retry_count` — default 3
- `conductor.worker.heartbeat_interval_seconds` — default 5s
- `conductor.kafka.bridge.url` — for `conductor-kafka-bridge` integration

### Operational references

- Runbook: [`shared/CONDUCTOR_WORKFLOWS.md`](../shared/CONDUCTOR_WORKFLOWS.md) 8
- Observability: [`shared/CONDUCTOR_WORKFLOWS.md`](../shared/CONDUCTOR_WORKFLOWS.md) 7
- Master task registry: [`MASTER_TASK.md`](../MASTER_TASK.md) 7-9
