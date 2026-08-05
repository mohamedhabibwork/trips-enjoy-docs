# ride-payment-integration-service — Integration Contract

## 1. Inbound APIs

### 1.1 `GET /v1/ride-payment-sagas/{trip_id}`

- **Purpose**: Read a saga.
- **Auth**: Bearer JWT (admin / support).
- **Response (200)**:
  ```json
  {
    "id": "...",
    "trip_id": "...",
    "state": "accruing",
    "fare_amount_minor": 4400,
    "currency": "AED",
    "earning_amount_minor": 3520,
    "commission_amount_minor": 880,
    "payment_intent_id": "...",
    "driver_earning_id": null,
    "ledger_posting_id": null,
    "started_at": "...",
    "attempt_count": 1
  }
  ```
- **Errors**: 401, 403, 404.

### 1.2 `POST /v1/ride-payment-sagas/{trip_id}/retry`

- **Purpose**: Force-retry a failed or stuck saga.
- **Auth**: Bearer JWT (admin) with `X-Audit-Reason`.
- **Request**: empty body.
- **Response (200)**: the saga with the resumed state.
- **Errors**: 401, 403, 404, 409 `STATE_INVALID` (already
  `completed` or `failed` and not retryable).

### 1.3 `GET /v1/ride-payment-sagas`

- **Purpose**: List sagas (paginated).
- **Auth**: Bearer JWT (admin).
- **Query params**: `cursor`, `limit` (default 20, max 100),
  `state` (optional filter).
- **Response (200)**:
  ```json
  {
    "items": [ { "...": "..." } ],
    "next_cursor": "eyJ…",
    "has_more": false
  }
  ```

## 2. Outbound APIs

| Target | Method | URI | Purpose | Timeout | Retry | Circuit |
|--------|--------|-----|---------|---------|-------|---------|
| `payment-service` | POST | `/v1/payment-intents` + `/v1/payment-intents/{id}/capture` | create + capture | 1s | 2 | yes |
| `payment-service` | POST | `/v1/payment-intents/{id}/refund` | refund | 1s | 2 | yes |
| `payment-service` | POST | `/v1/payment-intents/{id}/void` | void | 500ms | 2 | yes |
| `driver-earnings-service` | POST | /v1/earnings/accrue | accrue | 500ms | 2 | yes |
| `ledger-service` | POST | /v1/ledger/postings | post | 500ms | 3 | yes |

## 3. Produced Events

### 3.1 `ride.payment.completed.v1`

- **Topic**: `ride.payment.completed`.
- **Partition key**: `trip_id`.
- **Consumers**: `driver-earnings-service` (ack), `ride-history-service`,
  `audit-service`, `customer-service` (history).
- **Schema**:
  ```json
  {
    "event_id": "...",
    "event_name": "ride.payment.completed.v1",
    "aggregate_id": "<trip_id>",
    "data": {
      "saga_id": "...",
      "trip_id": "...",
      "customer_id": "...",
      "driver_id": "...",
      "fare_amount_minor": 4400,
      "currency": "AED",
      "earning_amount_minor": 3520,
      "commission_amount_minor": 880,
      "payment_intent_id": "...",
      "driver_earning_id": "...",
      "ledger_posting_id": "...",
      "completed_at": "..."
    }
  }
  ```
- **Retry**: outbox, 3; DLQ.

### 3.2 `ride.payment.failed.v1`

- **Topic**: `ride.payment.failed`.
- **Partition key**: `trip_id`.
- **Consumers**: `support-service`, `notification-service`,
  `audit-service`.
- **Schema**:
  ```json
  {
    "event_id": "...",
    "event_name": "ride.payment.failed.v1",
    "aggregate_id": "<trip_id>",
    "data": {
      "saga_id": "...",
      "trip_id": "...",
      "failure_step": "capture",
      "failure_reason": "card_declined",
      "compensated": true
    }
  }
  ```

### 3.3 `ride.payment.completed.v1`

- **Producer**: this service.
- **Topic**: `ride.payment.completed`.
- **Trigger**: A ride's payment saga completes (capture + accrual + ledger).
- **Schema version**: 1.
- **Partition key**: `trip_id`.
- **Consumers**: `driver-earnings-service`, `customer-service` (history), `ride-history-service`, `audit-service`.
- **Schema**:

  ```json
  {
    "event_id": "01HZX9C8W6K0G3V2Y5N1Q4R7PB",
    "event_name": "ride.payment.completed.v1",
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
- **DLQ**: `ride.payment.completed.dlq`.


### 3.4 `ride.payment.failed.v1`

- **Producer**: this service.
- **Topic**: `ride.payment.failed`.
- **Trigger**: A ride's payment saga fails (capture or accrual failure).
- **Schema version**: 1.
- **Partition key**: `trip_id`.
- **Consumers**: `support-service`, `notification-service`, `audit-service`.
- **Schema**:

  ```json
  {
    "event_id": "01HZX9C8W6K0G3V2Y5N1Q4R7PB",
    "event_name": "ride.payment.failed.v1",
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
- **DLQ**: `ride.payment.failed.dlq`.



## 4. Consumed Events

### 4.1 `trip.completed.v1`

- **Producer**: `trip-service`.
- **Reason**: start the saga.
- **Handler**: create the saga (or no-op if it exists); begin
  capture.
- **Deduplication**: inbox on `event_id`; UNIQUE on `trip_id`.
- **Retry**: 3; failure → DLQ.

### 4.2 `payment.captured.v1`

- **Producer**: `payment-service`.
- **Reason**: advance the saga.
- **Handler**: mark `captured`; call `driver-earnings-service.accrue`.
- **Deduplication**: inbox on `event_id`.
- **Retry**: 3; failure → DLQ.

### 4.3 `payment.failed.v1`

- **Producer**: `payment-service`.
- **Reason**: fail the saga.
- **Handler**: mark `failed`; emit `ride.payment.failed.v1`; open a
  support ticket; notify the customer.
- **Deduplication**: inbox on `event_id`.
- **Retry**: 3; failure → DLQ.

### 4.4 `configuration.updated.v1`

- **Producer**: `configuration-service`.
- **Reason**: reload config.
- **Handler**: cache invalidation.
- **Deduplication**: inbox on `event_id`.
- **Retry**: 3; failure → DLQ.

## 5. Reliability

- **Timeouts**: outbound 500ms–1s; DB 30s.
- **Retries**: bounded 3, exponential backoff with jitter.
- **Circuit breakers**: per downstream.
- **Bulkheads**: per downstream connection pool.
- **Outbox**: `ride_payment_integration.outbox` table.
- **Inbox**: `ride_payment_integration.inbox` table.
- **DLQ**: per topic.
- **Reconciliation**: a daily job in `reporting-service` checks for
  sagas in non-terminal states for more than 1 hour and for
  sagas where the ledger post is missing.

## 6. Correlation IDs

Every request carries `X-Correlation-Id`. The service:
- Logs the id on every line within the request scope.
- Propagates it to outbound calls.
- Embeds it in every emitted event and Kafka header.
- Reads it from the inbound event envelope and uses the same id
  for the resulting state changes.

## 7. Distributed Tracing

OpenTelemetry. One root span per saga. Each step is a child span.
`traceparent` is propagated. Sample rate: 100% for errors, 10% for
successes in production.


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
| [`driver-earnings-service`](../driver-earnings-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [`ledger-service`](../ledger-service/README.md) | CRITICAL | 503 `DEPENDENCY_UNAVAILABLE` |
| [`notification-service`](../notification-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [`payment-service`](../payment-service/README.md) | CRITICAL | 503 `DEPENDENCY_UNAVAILABLE` |
| [`ride-history-service`](../ride-history-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [`support-service`](../support-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [`trip-service`](../trip-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |

### Downstream services that depend on this service

| Downstream | Class (from its perspective) |
|---|---|
| [`customer-service`](../customer-service/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`driver-earnings-service`](../driver-earnings-service/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`ledger-service`](../ledger-service/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`payment-service`](../payment-service/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`ride-history-service`](../ride-history-service/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`ride-request-service`](../ride-request-service/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`support-service`](../support-service/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`trip-service`](../trip-service/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
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

