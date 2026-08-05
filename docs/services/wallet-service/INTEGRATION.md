# wallet-service — Integration Contract

## 1. Inbound APIs

### 1.1 `GET /v1/wallets/{user_id}`

- **Auth**: Bearer JWT — the user (`user_id == sub`) OR service OR
  admin.
- **Response (200)**:
  ```json
  {
    "user_id": "01HZX7C2X1X0M4K6P8F2V1T7YDH",
    "currency": "EUR",
    "available_minor": 12450,
    "held_minor": 0,
    "lifetime_credited_minor": 245000,
    "lifetime_debited_minor": 232550,
    "state": "active",
    "as_of": "2026-07-29T11:09:00.000Z"
  }
  ```
- **Errors**: 401, 403, 404.

### 1.2 `POST /v1/wallets/{user_id}/topup`

- **Auth**: Bearer JWT — the user.
- **Idempotency**: required
  (`wallet:<user_id>:topup:<request_id>`).
- **Request**:
  ```json
  {
    "amount_minor": 5000,
    "currency": "EUR",
    "payment_method_token": "pm_…"
  }
  ```
- **Response (202)**:
  ```json
  {
    "topup_id": "01HZX…",
    "payment_intent_id": "01HZX…",
    "state": "initiated"
  }
  ```
- **Errors**: 401, 403, 404, 422 (`BELOW_MIN_TOPUP`,
  `ABOVE_MAX_TOPUP`).

### 1.3 `POST /v1/wallets/{user_id}/hold`

- **Auth**: Bearer JWT — service (`wallet.write`).
- **Idempotency**: required
  (`wallet:<user_id>:hold:<request_id>`).
- **Request**:
  ```json
  {
    "amount_minor": 2350,
    "currency": "EUR",
    "hold_ttl_minutes": 60,
    "related_payment_intent_id": "01HZX…"
  }
  ```
- **Response (201)**:
  ```json
  {
    "hold_id": "01HZX…",
    "state": "active",
    "amount_minor": 2350,
    "expires_at": "2026-07-29T12:09:00.000Z"
  }
  ```
- **Errors**: 401, 403, 404, 409 (duplicate payment intent), 422
  (`INSUFFICIENT_BALANCE`).

### 1.4 `POST /v1/wallets/{user_id}/holds/{hold_id}/capture`

- **Auth**: Bearer JWT — service (`wallet.write`).
- **Idempotency**: required
  (`wallet:<user_id>:hold:<hold_id>:capture`).
- **Request**: `{}`
- **Response (200)**:
  ```json
  {
    "hold_id": "01HZX…",
    "state": "captured",
    "transaction_id": "01HZX…",
    "captured_at": "2026-07-29T11:08:21.183Z"
  }
  ```
- **Errors**: 401, 403, 404, 409 (`HOLD_NOT_ACTIVE`).

### 1.5 `POST /v1/wallets/{user_id}/holds/{hold_id}/release`

- **Auth**: Bearer JWT — service (`wallet.write`).
- **Idempotency**: required
  (`wallet:<user_id>:hold:<hold_id>:release`).
- **Request**: `{}`
- **Response (200)**:
  ```json
  {
    "hold_id": "01HZX…",
    "state": "released"
  }
  ```
- **Errors**: 401, 403, 404, 409.

### 1.6 `POST /v1/wallets/{user_id}/credit`

- **Auth**: Bearer JWT — service (`wallet.write`).
- **Idempotency**: required.
- **Request**:
  ```json
  {
    "amount_minor": 500,
    "currency": "EUR",
    "reference": "refund:01HZX…",
    "reason": "refund"
  }
  ```
- **Response (201)**:
  ```json
  {
    "transaction_id": "01HZX…",
    "available_minor": 12950
  }
  ```
- **Errors**: 401, 403, 404, 422.

### 1.7 `POST /v1/wallets/{user_id}/debit`

- **Auth**: Bearer JWT — service (`wallet.write`).
- **Idempotency**: required.
- **Request**:
  ```json
  {
    "amount_minor": 500,
    "currency": "EUR",
    "reference": "...",
    "reason": "..."
  }
  ```
- **Response (201)**: same as 1.6.
- **Errors**: 401, 403, 404, 422 (`INSUFFICIENT_BALANCE`).

### 1.8 `GET /v1/wallets/{user_id}/transactions?from=…&to=…`

- **Auth**: Bearer JWT — the user OR service OR admin.
- **Query params**: cursor pagination.
- **Response (200)**: list of transactions with `next_cursor`.

### 1.9 `GET /v1/wallets/{user_id}/statement?period=daily|weekly|monthly`

- **Auth**: Bearer JWT — the user OR service OR admin.
- **Query params**: `from`, `to`, `period`.
- **Response (200)**:
  ```json
  {
    "user_id": "...",
    "period": "weekly",
    "from": "2026-07-22T00:00:00Z",
    "to": "2026-07-29T00:00:00Z",
    "totals": {
      "credited_minor": 5000,
      "debited_minor": 2350,
      "held_minor": 0,
      "net_minor": 2650
    },
    "items": [
      { "date": "2026-07-22", "credited_minor": 1000, "debited_minor": 0 }
    ]
  }
  ```

### 1.10 `POST /v1/wallets/{user_id}/admin-adjust`

- **Auth**: Bearer JWT — admin (`wallet.admin`).
- **Idempotency**: required.
- **Request**:
  ```json
  {
    "amount_minor": 500,
    "currency": "EUR",
    "direction": "credit" | "debit",
    "audit_note": "..."
  }
  ```
- **Response (201)**: same as 1.6.
- **Errors**: 401, 403, 404, 422.

## 2. Outbound APIs

| Target | Method | URI | Purpose | Timeout | Retry | Circuit |
|--------|--------|-----|---------|---------|-------|---------|
| `payment-service` | POST | `/v1/payment-intents` | top-up charge | 5s | 3 | yes |
| `payment-service` | GET | `/v1/payment-intents/{id}` | poll | 1s | 3 | yes |
| `customer-service` | GET | `/v1/customers/{id}` | enrich | 1s | 3 | yes |
| `ledger-service` | GET | `/v1/accounts/wallet/sum` | reconciliation | 5s | 3 | yes |
| `notification-service` | POST | `/v1/pushes` | notify user | 1s | 3 | yes |

## 3. Produced Events

### 3.1 `wallet.credited.v1`

- **Topic**: `wallet.credited`
- **Trigger**: every credit.
- **Partition key**: `user_id`
- **Schema**:
  ```json
  {
    "event_id": "01HZX9C8W6K0G3V2Y5N1Q4R7PB",
    "event_name": "wallet.credited.v1",
    "occurred_at": "2026-07-29T11:08:21.183Z",
    "aggregate_type": "Wallet",
    "aggregate_id": "01HZX…",
    "data": {
      "wallet_id": "01HZX…",
      "user_id": "01HZX…",
      "transaction_id": "01HZX…",
      "amount_minor": 5000,
      "currency": "EUR",
      "reason": "topup",
      "reference": "refund:01HZX…",
      "available_minor": 12950,
      "credited_at": "..."
    }
  }
  ```
- **Consumers**: `ledger-service`, `customer-service` (history),
  `audit-service`.
- **DLQ**: `wallet.credited.dlq`.

### 3.2 `wallet.debited.v1`

- **Topic**: `wallet.debited`
- **Trigger**: every debit.
- **Partition key**: `user_id`
- **Schema**: similar to 3.1 with `kind: "debit"`.

### 3.3 `wallet.held.v1`

- **Topic**: `wallet.held`
- **Trigger**: hold created.
- **Partition key**: `user_id`
- **Schema**:
  ```json
  {
    "event_id": "...",
    "event_name": "wallet.held.v1",
    "data": {
      "wallet_id": "...",
      "user_id": "...",
      "hold_id": "...",
      "amount_minor": 2350,
      "currency": "EUR",
      "expires_at": "..."
    }
  }
  ```
- **Consumers**: `ledger-service`, `audit-service`.

### 3.4 `wallet.released.v1`

- **Topic**: `wallet.released`
- **Trigger**: hold released.
- **Partition key**: `user_id`

### 3.5 `wallet.captured.v1`

- **Topic**: `wallet.captured`
- **Trigger**: hold captured.
- **Partition key**: `user_id`

### 3.6 `wallet.audit.transaction_logged.v1`

- **Topic**: `wallet.audit.transaction_logged`
- **Trigger**: every transaction.
- **Partition key**: `user_id`
- **Consumers**: `audit-service`.

### 3.7 `wallet.audit.reconciliation_drift.v1`

- **Topic**: `wallet.audit.reconciliation_drift`
- **Trigger**: daily reconciliation drift.
- **Consumers**: `admin-service`, `support-service`.

## 4. Consumed Events

### 4.1 `payment.captured.v1`

- **Producer**: `payment-service`.
- **Reason**: top-up captured; credit the wallet.
- **Handler**: insert `credit` transaction; emit
  `wallet.credited.v1`.
- **Deduplication**: inbox on `event_id`; unique on
  `(reference, kind)` for additional safety.

### 4.2 `payment.refund.completed.v1`

- **Producer**: `payment-service`.
- **Reason**: refund applied; debit the wallet (closed-loop).
- **Handler**: insert `debit` transaction; emit
  `wallet.debited.v1`.
- **Deduplication**: inbox on `event_id`.

### 4.3 `customer.suspended.v1`

- **Producer**: `customer-service`.
- **Reason**: block transactions.
- **Handler**: set `wallets.transactions_blocked=true`.

### 4.4 `configuration.updated.v1`

- **Producer**: `configuration-service`.
- **Reason**: reload.
- **Handler**: refresh in-memory config.

### 4.5 `trip.reward.granted.v1`

- **Producer**: `trip-service`.
- **Topic**: `trip.reward.granted`.
- **Reason**: per-trip customer credit. The wallet is only
  affected when the grant's `user.kind = "wallet_credit"` (other
  `user.kind` values are out of scope for this service).
- **Handler**: insert a `credit` transaction for the
  `trip.reward.user` grant; idempotency key
  `trip:{trip_id}:reward:user:grant`; emit `wallet.credited.v1`.
- **Deduplication**: inbox on `event_id`; UNIQUE on
  `(reference='trip:{trip_id}:reward:user:grant', kind='credit')`.
- **Retry**: 3 with backoff.
- **Failure**: DLQ.

### 4.6 `trip.reward.reversed.v1`

- **Producer**: `trip-service`.
- **Topic**: `trip.reward.reversed`.
- **Reason**: reverse the per-trip customer credit (e.g. trip
  disputed, admin reversal).
- **Handler**: insert a `debit` transaction with reference
  `trip:{trip_id}:reward:user:reversal:{grant_event_id}`; emit
  `wallet.debited.v1`. A redelivered reversal is a no-op (the new
  row in `wallet_transactions` is the authoritative record).
- **Deduplication**: inbox on `event_id`.
- **Retry**: 3 with backoff.
- **Failure**: DLQ.

## 5. Reliability

- **Timeouts**: outbound 1s default; `payment-service` 5s.
- **Retries**: 3 with exponential backoff.
- **Circuit breakers**: every outbound call wrapped.
- **Bulkheads**: per-downstream connection pool.
- **Outbox**: yes.
- **Inbox**: yes.
- **DLQ**: every topic has a paired DLQ.
- **Reconciliation**: a daily job at 03:00 UTC compares the
  wallets' total `available + held` against the wallet account
  in `ledger-service`; drift opens a P1 ticket.

## 6. Correlation IDs

All requests carry `X-Correlation-Id`; emitted events carry the
same in the envelope.

## 7. Distributed Tracing

OpenTelemetry; root span per transaction. `traceparent`
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
| [`customer-service`](../customer-service/README.md) | CRITICAL | 503 `DEPENDENCY_UNAVAILABLE` |
| [`food-payment-integration-service`](../food-payment-integration-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [`ledger-service`](../ledger-service/README.md) | CRITICAL | 503 `DEPENDENCY_UNAVAILABLE` |
| [`payment-service`](../payment-service/README.md) | CRITICAL | 503 `DEPENDENCY_UNAVAILABLE` |
| [`ride-payment-integration-service`](../ride-payment-integration-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [`support-service`](../support-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |

### Downstream services that depend on this service

| Downstream | Class (from its perspective) |
|---|---|
| [`api-gateway`](../api-gateway/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`courier-dispatch-service`](../courier-dispatch-service/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`courier-earnings-service`](../courier-earnings-service/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`driver-earnings-service`](../driver-earnings-service/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`food-payment-integration-service`](../food-payment-integration-service/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`ledger-service`](../ledger-service/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`loyalty-service`](../loyalty-service/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`payment-service`](../payment-service/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
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

