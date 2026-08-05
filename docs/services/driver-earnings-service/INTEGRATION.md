# driver-earnings-service — Integration Contract

## 1. Inbound APIs

### 1.1 `GET /v1/earnings/balance`

- **Purpose**: Read the current withdrawable balance.
- **Auth**: Bearer JWT (driver).
- **Response (200)**:
  ```json
  {
    "driver_id": "...",
    "currency": "AED",
    "available_minor": 125000,
    "held_minor": 0,
    "lifetime_minor": 1500000
  }
  ```

### 1.2 `GET /v1/earnings/today`

- **Purpose**: Today's earnings.
- **Auth**: Bearer JWT (driver).
- **Response (200)**:
  ```json
  {
    "date": "2026-07-29",
    "currency": "AED",
    "total_minor": 12500,
    "trip_count": 5
  }
  ```

### 1.3 `GET /v1/earnings/week`

- **Purpose**: This week's earnings.
- **Auth**: Bearer JWT (driver).
- **Response (200)**:
  ```json
  {
    "week_start": "2026-07-27",
    "currency": "AED",
    "total_minor": 87500,
    "trip_count": 32
  }
  ```

### 1.4 `GET /v1/earnings/statement?from=…&to=…`

- **Purpose**: Paginated statement.
- **Auth**: Bearer JWT (driver).
- **Query params**: `from` (RFC3339), `to` (RFC3339), `cursor`,
  `limit` (default 50, max 200).
- **Response (200)**:
  ```json
  {
    "items": [
      { "id": "...", "type": "fare", "amount_minor": 4400, "currency": "AED", "accrued_at": "...", "trip_id": "..." }
    ],
    "next_cursor": "eyJ…",
    "has_more": false
  }
  ```

### 1.5 `POST /v1/earnings/withdrawals`

- **Purpose**: Request a withdrawal.
- **Auth**: Bearer JWT (driver).
- **Idempotency**: `Idempotency-Key` required.
- **Request**:
  ```json
  {
    "amount_minor": 50000,
    "currency": "AED",
    "bank_detail_id": "01HZX9C8K4D2H1A8N5J7V3R0Q9"
  }
  ```
- **Response (202)**:
  ```json
  {
    "id": "01HZX9C8W6K0G3V2Y5N1Q4R7PB",
    "state": "requested",
    "amount_minor": 50000,
    "currency": "AED",
    "requested_at": "2026-07-29T10:42:11.183Z"
  }
  ```
- **Errors**:
  - 400 `VALIDATION_FAILED`
  - 401 `UNAUTHENTICATED`
  - 403 `FORBIDDEN`
  - 409 `WITHDRAWAL_COOLDOWN`
  - 422 `INSUFFICIENT_BALANCE`
  - 422 `IDEMPOTENCY_KEY_REUSED`

### 1.6 `GET /v1/earnings/withdrawals/{id}`

- **Purpose**: Read a withdrawal.
- **Auth**: Bearer JWT (driver, admin, support).
- **Response (200)**: same shape as POST, with current state.

### 1.7 `GET /v1/earnings/bank`

- **Purpose**: List bank details.
- **Auth**: Bearer JWT (driver).
- **Response (200)**:
  ```json
  {
    "items": [
      { "id": "...", "bank_name": "...", "account_holder": "...", "iban_last4": "1234", "is_default": true }
    ]
  }
  ```
- **Note**: the full IBAN is never returned; only the last 4.

### 1.8 `PATCH /v1/earnings/bank`

- **Purpose**: Add or update a bank detail.
- **Auth**: Bearer JWT (driver).
- **Idempotency**: `Idempotency-Key` required.
- **Request**:
  ```json
  {
    "id": "01HZX9C8K4D2H1A8N5J7V3R0Q9" | null,
    "bank_name": "Emirates NBD",
    "account_holder": "Ahmed K.",
    "iban": "AE070331234567890123456",
    "is_default": true
  }
  ```
- **Response (200)**: the saved bank detail (without the full IBAN).
- **Errors**: 400, 401, 403, 422 `INVALID_IBAN`, 422
  `MAX_BANK_DETAILS_REACHED`.

### 1.9 `GET /v1/drivers/{id}/period-eligible-earnings?window=hourly|daily`

- **Purpose**: Period-eligible earnings read used by
  `trip-service` to evaluate the hourly / daily floor
  (`max(0, floor − eligible_earnings_in_window)`). The window math
  matches `trip.reward.driver.min_window_minutes` (default 60 min)
  for `window=hourly` and the rolling 24-h window for
  `window=daily`.
- **Auth**: Service-to-service JWT (only `trip-service` calls
  this). The endpoint sits on the internal port.
- **Response (200)**:
  ```json
  {
    "driver_id": "01HZX…",
    "window": "hourly",
    "eligible_minor": 4200,
    "currency": "AED",
    "excludes_reversal_rows": true,
    "computed_at": "2026-07-29T10:47:11.183Z"
  }
  ```
  The `eligible_minor` covers every `type = fare | tip | incentive |
  guaranteed_topup` row in the window, EXCLUDING `type = penalty`
  and `type = correction` (i.e. the gross-to-net eligible side per
  `ACCOUNTING_WORKFLOWS.md` §"Workflow: Driver / Courier Income
  (Gross-to-Net)"). The result is cached for 30s per `(driver_id,
  window)` to cover the burst of `trip.reward.granted.v1`
  evaluations on a single driver.
- **Errors**: 401, 404 `DRIVER_NOT_FOUND`.

## 2. Outbound APIs

| Target | Method | URI | Purpose | Timeout | Retry | Circuit |
|--------|--------|-----|---------|---------|-------|---------|
| `payment-service` | POST | /v1/payouts | payout to bank | 1s | 2 | yes |
| `wallet-service` | POST | /v1/wallets/hold | hold amount | 300ms | 2 | yes |
| `wallet-service` | POST | /v1/wallets/release | release hold | 300ms | 2 | yes |
| `ledger-service` | POST | /v1/ledger/postings | post | 300ms | 3 | yes |
| `driver-service` | GET | /v1/drivers/{id} | validate driver | 200ms | 1 | yes |

## 3. Produced Events

### 3.1 `driver.earning.accrued.v1`

- **Topic**: `driver.earning.accrued`.
- **Partition key**: `driver_id`.
- **Consumers**: `ride-history-service`, `reporting-service`.
- **Schema**:
  ```json
  {
    "event_id": "...",
    "event_name": "driver.earning.accrued.v1",
    "aggregate_id": "<driver_id>",
    "data": {
      "earning_id": "...",
      "driver_id": "...",
      "trip_id": "..." | null,
      "type": "fare",
      "amount_minor": 3520,
      "currency": "AED",
      "accrued_at": "..."
    }
  }
  ```
- **Retry**: outbox, 3; DLQ.

### 3.2 `driver.withdrawal.requested.v1`

- **Topic**: `driver.withdrawal.requested`.
- **Partition key**: `driver_id`.
- **Consumers**: `payment-service` (payout), `audit-service`.
- **Schema**:
  ```json
  {
    "event_id": "...",
    "event_name": "driver.withdrawal.requested.v1",
    "aggregate_id": "<driver_id>",
    "data": {
      "withdrawal_id": "...",
      "driver_id": "...",
      "amount_minor": 50000,
      "currency": "AED",
      "bank_detail_id": "...",
      "requested_at": "..."
    }
  }
  ```

### 3.3 `driver.withdrawal.completed.v1`

- **Topic**: `driver.withdrawal.completed`.
- **Partition key**: `driver_id`.
- **Consumers**: `audit-service`, `notification-service`.
- **Schema**:
  ```json
  {
    "event_id": "...",
    "event_name": "driver.withdrawal.completed.v1",
    "aggregate_id": "<driver_id>",
    "data": {
      "withdrawal_id": "...",
      "driver_id": "...",
      "payout_id": "...",
      "amount_minor": 50000,
      "currency": "AED",
      "completed_at": "..."
    }
  }
  ```

### 3.4 `driver.withdrawal.failed.v1`

- **Topic**: `driver.withdrawal.failed`.
- **Partition key**: `driver_id`.
- **Consumers**: `support-service`, `notification-service`.
- **Schema**:
  ```json
  {
    "event_id": "...",
    "event_name": "driver.withdrawal.failed.v1",
    "aggregate_id": "<driver_id>",
    "data": {
      "withdrawal_id": "...",
      "driver_id": "...",
      "amount_minor": 50000,
      "currency": "AED",
      "failure_reason": "bank_rejected"
    }
  }
  ```

## 4. Consumed Events

### 4.1 `ride.payment.completed.v1`

- **Producer**: `ride-payment-integration-service`.
- **Reason**: accrue the driver earning.
- **Handler**: insert earning row; update `driver_balance`;
  emit `driver.earning.accrued.v1`. Idempotent.
- **Deduplication**: inbox on `event_id`; UNIQUE on
  `idempotency_key`.
- **Retry**: 3; failure → DLQ.

### 4.2 `trip.completed.v1`

- **Producer**: `trip-service`.
- **Reason**: accrue tips / bonuses attached to the trip.
- **Handler**: same as 4.1.
- **Deduplication**: inbox on `event_id`; UNIQUE on
  `idempotency_key`.
- **Retry**: 3; failure → DLQ.

### 4.3 `payment.failed.v1`

- **Producer**: `ride-payment-integration-service`.
- **Reason**: informational; the saga failed; we do not accrue.
- **Handler**: log only.
- **Deduplication**: inbox on `event_id`.
- **Retry**: 3; failure → DLQ.

### 4.4 `configuration.updated.v1`

- **Producer**: `configuration-service`.
- **Reason**: reload config.
- **Handler**: cache invalidation.
- **Deduplication**: inbox on `event_id`.
- **Retry**: 3; failure → DLQ.

### 4.5 `trip.reward.granted.v1`

- **Producer**: `trip-service`.
- **Topic**: `trip.reward.granted`.
- **Reason**: a guaranteed driver top-up was granted at trip
  completion (per-trip floor, hourly floor, or daily floor). The
  envelope carries one `grant` line with `kind = driver_*_topup`.
- **Handler**: insert a new `type=guaranteed_topup` row into
  `driver_earnings.earnings` (append-only); the row carries the
  grant's `grant_event_id` for dedup and `correlation_id` for
  end-to-end trace. The receivable side (`6302_guaranteed_minimum`)
  is posted to `ledger-service` on the accrual in the standard
  way.
- **Idempotency-key**: `trip:{trip_id}:reward:driver:grant`.
- **Deduplication**: inbox on `event_id`.
- **Retry**: 3; failure → DLQ.

### 4.6 `trip.reward.reversed.v1`

- **Producer**: `trip-service`.
- **Reason**: a previously-granted driver top-up was reversed.
- **Handler**: insert a new `type=correction` earning row (with
  `amount_minor` equal to the original grant) and the `grant_event_id`
  of the prior grant. The corresponding ledger posting is a
  new balanced entry — never UPDATE/DELETE on `ledger.postings`.
- **Idempotency-key**: `trip:{trip_id}:reward:driver:reversal`.
- **Deduplication**: inbox on `event_id`.
- **Retry**: 3; failure → DLQ.

## 5. Reliability

- **Timeouts**: outbound 200ms–1s; DB 30s.
- **Retries**: bounded 3, exponential backoff with jitter.
- **Circuit breakers**: per downstream.
- **Bulkheads**: per downstream connection pool.
- **Outbox**: `driver_earnings.outbox` table.
- **Inbox**: `driver_earnings.inbox` table.
- **DLQ**: per topic.
- **Reconciliation**: a daily job in `reporting-service` checks
  for `driver_balance` drift vs the sum of earnings / holds.

## 6. Correlation IDs

Every request carries `X-Correlation-Id`. The service:
- Logs the id on every line within the request scope.
- Propagates it to outbound calls.
- Embeds it in every emitted event and Kafka header.
- Reads it from the inbound event envelope and uses the same id
  for the resulting state changes.

## 7. Distributed Tracing

OpenTelemetry. One root span per request. `traceparent` is
propagated. Sample rate: 100% for errors, 10% for successes in
production.


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
| [`driver-service`](../driver-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [`ledger-service`](../ledger-service/README.md) | CRITICAL | 503 `DEPENDENCY_UNAVAILABLE` |
| [`notification-service`](../notification-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [`payment-service`](../payment-service/README.md) | CRITICAL | 503 `DEPENDENCY_UNAVAILABLE` |
| [`reporting-service`](../reporting-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [`ride-history-service`](../ride-history-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [`ride-payment-integration-service`](../ride-payment-integration-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [`support-service`](../support-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [`trip-service`](../trip-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [`wallet-service`](../wallet-service/README.md) | CRITICAL | 503 `DEPENDENCY_UNAVAILABLE` |

### Downstream services that depend on this service

| Downstream | Class (from its perspective) |
|---|---|
| [`driver-incentive-service`](../driver-incentive-service/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`driver-service`](../driver-service/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`ledger-service`](../ledger-service/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`pricing-service`](../pricing-service/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`ride-payment-integration-service`](../ride-payment-integration-service/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`ride-request-service`](../ride-request-service/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`trip-service`](../trip-service/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |

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

