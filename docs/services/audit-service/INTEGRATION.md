# Audit Service — Integration Contract

## 1. Inbound APIs

All endpoints are versioned under `/v1`. Auth: bearer JWT. Errors
use the standard envelope.

### 1.1 `POST /v1/audit/search`

- **Purpose**: Search the audit log.
- **Auth**: Bearer JWT. Required role: `audit.read`.
- **Request**:
  ```json
  {
    "query": {
      "topic": "trip.completed",
      "subject_type": "customer",
      "subject_id": "01HZX…",
      "tenant_id": "global",
      "from": "2026-07-01T00:00:00Z",
      "to": "2026-07-29T23:59:59Z",
      "correlation_id": null
    },
    "limit": 20,
    "cursor": null,
    "reason": "Incident response: customer reported missing item"
  }
  ```
- **Response (200)**:
  ```json
  {
    "items": [
      {
        "id": "01HZX…",
        "event_id": "01HZX…",
        "event_name": "trip.completed.v1",
        "occurred_at": "2026-07-29T10:42:11.183Z",
        "producer": "trip-service",
        "tenant_id": "global",
        "correlation_id": "01HZX…",
        "aggregate_type": "Trip",
        "aggregate_id": "01HZX…",
        "subject_type": "customer",
        "subject_id": "01HZX…",
        "hash": "abc…"
      }
    ],
    "next_cursor": "eyJ…",
    "has_more": true
  }
  ```
- **Errors**: 401 / 403 / 400.

### 1.2 `GET /v1/audit/events/{id}`

- **Purpose**: Read a single event.
- **Auth**: Bearer JWT. Required role: `audit.read`.
- **Response (200)**: the full event including `data`, `headers`,
  `hash`, `prev_hash`.
- **Errors**: 401 / 403 / 404.

### 1.3 `GET /v1/audit/verify/{id}`

- **Purpose**: Verify the hash chain up to (and including) the
  event with `id`.
- **Auth**: Bearer JWT. Required role: `audit.admin`.
- **Response (200)**:
  ```json
  {
    "verified": true,
    "verified_at": "2026-07-29T10:42:11.183Z",
    "chain_length": 1234567
  }
  ```
- **Errors**: 401 / 403 / 404 / 422 `HASH_MISMATCH`.

### 1.4 `POST /v1/audit/litigation-hold`

- **Purpose**: Create a litigation hold.
- **Auth**: Bearer JWT. Required role: `audit.admin`. Required
  header: `X-Audit-Reason`.
- **Idempotency**: `Idempotency-Key` required.
- **Request**:
  ```json
  {
    "tenant_id": null,
    "subject_type": "customer",
    "subject_id": "01HZX…",
    "topic": null,
    "reason": "Pending litigation: case #12345",
    "effective_from": "2026-07-29T10:00:00Z"
  }
  ```
- **Response (201)**: the litigation hold.
- **Errors**: 401 / 403 / 400.

## 2. Outbound APIs

The service does not call other services synchronously. The only
outbound is the S3 export.

| Target | Method | URI | Purpose | Timeout | Retry | Circuit |
|--------|--------|-----|---------|---------|-------|---------|
| AWS S3 | PUT | `s3://trips-enjoy-platform-audit/audit/exports/<yyyy>/<mm>/<dd>/` | daily export | 60s | 3 | n/a |

## 3. Produced Events

The service does not produce business events. It MAY emit
operational events:

### 3.1 `audit.export.completed.v1`

- **Producer**: `audit-service`.
- **Topic**: `audit.export.completed`.
- **Trigger**: nightly export success.
- **Schema version**: 1.
- **Partition key**: `tenant_id`.
- **Schema**:
  ```json
  {
    "event_id": "01HZX9C8W6K0G3V2Y5N1Q4R7PB",
    "event_name": "audit.export.completed.v1",
    "occurred_at": "2026-07-29T03:00:00.000Z",
    "schema_version": 1,
    "producer": "audit-service",
    "tenant_id": "global",
    "correlation_id": "01HZX9C7T0XK2P9F0V6E4B1MZA",
    "aggregate_type": "AuditExport",
    "aggregate_id": "2026-07-29",
    "data": {
      "s3_path": "s3://trips-enjoy-platform-audit/audit/exports/2026/07/29/global.json",
      "event_count": 1234567,
      "size_bytes": 987654321
    }
  }
  ```
- **Retry / DLQ**: outbox / `audit.export.completed.dlq`.

### 3.2 `audit.security.compliance_violation.v1`

- **Producer**: this service.
- **Topic**: `platform.audit.security`.
- **Trigger**: A compliance violation is detected (e.g. PII access without reason code).
- **Schema version**: 1.
- **Partition key**: `violation_id`.
- **Consumers**: `fraud-risk-service`, `admin-service`.
- **Schema**:

  ```json
  {
    "event_id": "01HZX9C8W6K0G3V2Y5N1Q4R7PB",
    "event_name": "audit.security.compliance_violation.v1",
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
- **DLQ**: `platform.audit.security.dlq`.


### 3.3 `audit.security.break_glass_used.v1`

- **Producer**: this service.
- **Topic**: `platform.audit.security`.
- **Trigger**: A break-glass admin action is performed.
- **Schema version**: 1.
- **Partition key**: `actor_id`.
- **Consumers**: `fraud-risk-service`, `notification-service` (pages security on-call).
- **Schema**:

  ```json
  {
    "event_id": "01HZX9C8W6K0G3V2Y5N1Q4R7PB",
    "event_name": "audit.security.break_glass_used.v1",
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
- **DLQ**: `platform.audit.security.dlq`.


### 3.4 `audit.retention.purge_completed.v1`

- **Producer**: this service.
- **Topic**: `platform.audit.retention`.
- **Trigger**: A retention purge job completes.
- **Schema version**: 1.
- **Partition key**: `job_id`.
- **Consumers**: `admin-service`, `compliance`.
- **Schema**:

  ```json
  {
    "event_id": "01HZX9C8W6K0G3V2Y5N1Q4R7PB",
    "event_name": "audit.retention.purge_completed.v1",
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
- **DLQ**: `platform.audit.retention.dlq`.



## 4. Consumed Events

The service subscribes to a comprehensive list. Each topic is
handled by a dedicated consumer; the handler is idempotent on
`event_id` via the inbox.

### 4.1 Topics

- `admin.action.performed`
- `payment.attempted`, `payment.authorized`, `payment.captured`,
  `payment.failed`, `payment.refund.initiated`,
  `payment.refund.completed`
- `wallet.credited`, `wallet.debited`, `wallet.held`,
  `wallet.released`
- `ledger.posted`
- `trip.started`, `trip.arrived`, `trip.completed`, `trip.cancelled`
- `trip.reward.granted`, `trip.reward.reversed` (per-trip
  guaranteed reward; 7-year retention)
- `ride.request.created`, `ride.request.matched`,
  `ride.request.cancelled`, `ride.request.expired`
- `dispatch.matched`, `dispatch.no_driver`
- `food.order.placed`, `food.order.accepted`, `food.order.rejected`,
  `food.order.preparing`, `food.order.ready`, `food.order.cancelled`
- `delivery.pickup`, `delivery.in_transit`, `delivery.completed`,
  `delivery.failed`
- `identity.user.created`, `identity.user.suspended`,
  `identity.user.disabled`
- `customer.created`, `customer.updated`, `customer.suspended`
- `driver.created`, `driver.approved`, `driver.suspended`
- `courier.created`, `courier.approved`, `courier.suspended`
- `merchant.created`, `merchant.approved`, `merchant.suspended`
- `restaurant.created`, `restaurant.approved`, `restaurant.online`,
  `restaurant.offline`, `restaurant.suspended`
- `configuration.updated`
- `feature_flag.updated`
- `promotion.created`, `promotion.disabled`, `promotion.redeemed`
- `loyalty.points.earned`, `loyalty.points.burned`,
  `loyalty.tier.changed`
- `review.submitted`, `review.aggregated`
- `tax.calculated`, `tax.rule.updated`
- `pricing.quote.created`
- `pricing.rating_density.applied`, `pricing.loyalty_discount.applied`
- `pricing.geo_config.updated` (operator audit)
- `notification.sent`, `notification.failed`
- `comms.sms.sent`, `comms.email.sent`, `comms.push.sent`
- `support.ticket.opened`, `support.ticket.resolved`
- `fraud.risk.scored`, `fraud.account.blocked`
- `file.uploaded`, `file.scanned`, `file.deleted`

### 4.2 Handler

- **Reason**: every audit-relevant event must be persisted.
- **Handler**: insert into `audit.events` with the next hash
  chain value.
- **Deduplication**: inbox on `event_id`.
- **Retry**: 3 with backoff.
- **Failure**: DLQ.

### 4.3 `customer.suspended.v1`

- **Producer**: `customer-service`.
- **Reason**: Audit customer state changes.
- **Handler**: Append immutable row.
- **Deduplication / Retry / Failure**: inbox keyed by
  `event_id` / 3 with exponential backoff / DLQ.


### 4.4 `driver.suspended.v1`

- **Producer**: `driver-service`.
- **Reason**: Audit driver state changes.
- **Handler**: Append immutable row.
- **Deduplication / Retry / Failure**: inbox keyed by
  `event_id` / 3 with exponential backoff / DLQ.


### 4.5 `courier.suspended.v1`

- **Producer**: `courier-service`.
- **Reason**: Audit courier state changes.
- **Handler**: Append immutable row.
- **Deduplication / Retry / Failure**: inbox keyed by
  `event_id` / 3 with exponential backoff / DLQ.


### 4.6 `merchant.suspended.v1`

- **Producer**: ``restaurant-service` (merchant)`.
- **Reason**: Audit merchant state changes.
- **Handler**: Append immutable row.
- **Deduplication / Retry / Failure**: inbox keyed by
  `event_id` / 3 with exponential backoff / DLQ.


### 4.7 `trip.completed.v1`

- **Producer**: `trip-service`.
- **Reason**: Audit trip state changes.
- **Handler**: Append immutable row.
- **Deduplication / Retry / Failure**: inbox keyed by
  `event_id` / 3 with exponential backoff / DLQ.


### 4.8 `food.order.delivered.v1`

- **Producer**: ``courier-service` (delivery)`.
- **Reason**: Audit order state changes.
- **Handler**: Append immutable row.
- **Deduplication / Retry / Failure**: inbox keyed by
  `event_id` / 3 with exponential backoff / DLQ.


### 4.9 `payment.captured.v1`

- **Producer**: `payment-service`.
- **Reason**: Audit payment events.
- **Handler**: Append immutable row.
- **Deduplication / Retry / Failure**: inbox keyed by
  `event_id` / 3 with exponential backoff / DLQ.


### 4.10 `ledger.posted.v1`

- **Producer**: `ledger-service`.
- **Reason**: Audit money movement.
- **Handler**: Append immutable row.
- **Deduplication / Retry / Failure**: inbox keyed by
  `event_id` / 3 with exponential backoff / DLQ.


### 4.11 `admin.action.performed.v1`

- **Producer**: `admin-service`.
- **Reason**: Audit admin actions.
- **Handler**: Append immutable row.
- **Deduplication / Retry / Failure**: inbox keyed by
  `event_id` / 3 with exponential backoff / DLQ.



## 5. Reliability

- **Timeouts**: HTTP 1s; DB 30s; S3 PUT 60s; Kafka publish 5s.
- **Retries**: bounded 3 with exponential backoff + jitter.
- **Circuit breakers**: not required (no synchronous outbound).
- **Bulkheads**: separate consumer pool per topic group.
- **Outbox**: yes (for the export event).
- **Inbox**: yes (for every consumed event).
- **DLQ**: every topic has a paired DLQ.
- **Reconciliation**: daily verification job; daily export; daily
  purge.

## 6. Correlation IDs

Every event carries `correlation_id`; the service propagates it in
the read log and the export event.

## 7. Distributed Tracing

OpenTelemetry: one root span per ingested event; child spans for
DB, hash computation. `traceparent` propagated through Kafka
headers. Sample rate 100% for errors, 10% for successes.

### 3.2 `audit.consumer.lag.v1`

- **Producer**: `audit-service`.
- **Topic**: `audit.consumer.lag`.
- **Trigger**: periodic (every minute); the lag is emitted as an
  operational event for downstream alerting.
- **Schema version**: 1.
- **Partition key**: `topic`.
- **Schema**:
  ```json
  {
    "event_id": "01HZX9C8W6K0G3V2Y5N1Q4R7PB",
    "event_name": "audit.consumer.lag.v1",
    "occurred_at": "2026-07-29T10:42:00.000Z",
    "schema_version": 1,
    "producer": "audit-service",
    "tenant_id": "global",
    "correlation_id": "01HZX9C7T0XK2P9F0V6E4B1MZA",
    "aggregate_type": "ConsumerLag",
    "aggregate_id": "trip.completed",
    "data": {
      "topic": "trip.completed",
      "lag_seconds": 12,
      "lag_messages": 1000
    }
  }
  ```
- **Retry / DLQ**: outbox.

### 3.3 `audit.hash_chain.verified.v1`

- **Producer**: `audit-service`.
- **Topic**: `audit.hash_chain.verified`.
- **Trigger**: the daily verification job completes.
- **Schema version**: 1.
- **Partition key**: `tenant_id`.
- **Schema**:
  ```json
  {
    "event_id": "01HZX9C8W6K0G3V2Y5N1Q4R7PB",
    "event_name": "audit.hash_chain.verified.v1",
    "occurred_at": "2026-07-29T04:00:00.000Z",
    "schema_version": 1,
    "producer": "audit-service",
    "tenant_id": "global",
    "correlation_id": "01HZX9C7T0XK2P9F0V6E4B1MZA",
    "aggregate_type": "HashChainVerification",
    "aggregate_id": "2026-07-29",
    "data": {
      "verified": true,
      "chain_length": 1234567,
      "verified_at": "2026-07-29T04:00:00.000Z"
    }
  }
  ```
- **Retry / DLQ**: outbox.

### 4.3 `payment.captured.v1`

Concrete example of an event this service consumes. (See 4.1 for
the full list; this entry shows the contract shape.)

- **Producer**: `payment-service`.
- **Reason**: every payment capture is a financial event that must
  be persisted for 7 years.
- **Handler**: insert into `audit.events`; the row is part of the
  hash chain.
- **Schema (envelope)**:
  ```json
  {
    "event_id": "01HZX…",
    "event_name": "payment.captured.v1",
    "occurred_at": "2026-07-29T10:42:11.183Z",
    "schema_version": 1,
    "producer": "payment-service",
    "tenant_id": "global",
    "correlation_id": "01HZX…",
    "aggregate_type": "PaymentIntent",
    "aggregate_id": "01HZX…",
    "data": {
      "amount_minor": 1704,
      "currency": "EUR",
      "payment_method": "card"
    }
  }
  ```
- **Deduplication**: inbox on `event_id`.
- **Retry**: 3.
- **Failure**: DLQ.

### 4.4 `trip.completed.v1`

- **Producer**: `trip-service`.
- **Reason**: every trip completion is a high-value aggregate event.
- **Handler**: insert into `audit.events`; the `subject_type` and
  `subject_id` are denormalized for search (customer, driver).
- **Schema (envelope)**: same shape as 4.3 with
  `event_name = "trip.completed.v1"`.
- **Deduplication / Retry / Failure**: inbox / 3 / DLQ.

### 4.5 `customer.suspended.v1`

- **Producer**: `customer-service`.
- **Reason**: every account suspension is a security event.
- **Handler**: insert into `audit.events`; the `subject_type` is
  `customer`.
- **Schema (envelope)**: same shape with
  `event_name = "customer.suspended.v1"`.
- **Deduplication / Retry / Failure**: inbox / 3 / DLQ.


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
| [`search-service`](../search-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |

### Downstream services that depend on this service

| Downstream | Class (from its perspective) |
|---|---|
| [``customer-service` (addresses)`](../customer-service/README.md) | _see [`SERVICE_ISOLATION.md` 2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`admin-service`](../admin-service/README.md) | _see [`SERVICE_ISOLATION.md` 2](../../architecture/SERVICE_ISOLATION.md)_ |
| [``reporting-service` (data lake)`](../reporting-service/README.md) | _see [`SERVICE_ISOLATION.md` 2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`api-gateway`](../api-gateway/README.md) | _see [`SERVICE_ISOLATION.md` 2](../../architecture/SERVICE_ISOLATION.md)_ |
| [``restaurant-service` (branch)`](../restaurant-service/README.md) | _see [`SERVICE_ISOLATION.md` 2](../../architecture/SERVICE_ISOLATION.md)_ |
| [``food-order-service` (cart)`](../food-order-service/README.md) | _see [`SERVICE_ISOLATION.md` 2](../../architecture/SERVICE_ISOLATION.md)_ |
| [``food-order-service` (checkout)`](../food-order-service/README.md) | _see [`SERVICE_ISOLATION.md` 2](../../architecture/SERVICE_ISOLATION.md)_ |
| [``notification-service` (provider ACL)`](../notification-service/README.md) | _see [`SERVICE_ISOLATION.md` 2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`configuration-service`](../configuration-service/README.md) | _see [`SERVICE_ISOLATION.md` 2](../../architecture/SERVICE_ISOLATION.md)_ |
| [``courier-service` (dispatch)`](../courier-service/README.md) | _see [`SERVICE_ISOLATION.md` 2](../../architecture/SERVICE_ISOLATION.md)_ |
| [``payment-service` (courier earnings)`](../payment-service/README.md) | _see [`SERVICE_ISOLATION.md` 2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`courier-service`](../courier-service/README.md) | _see [`SERVICE_ISOLATION.md` 2](../../architecture/SERVICE_ISOLATION.md)_ |
| [``courier-service` (tracking)`](../courier-service/README.md) | _see [`SERVICE_ISOLATION.md` 2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`customer-service`](../customer-service/README.md) | _see [`SERVICE_ISOLATION.md` 2](../../architecture/SERVICE_ISOLATION.md)_ |
| [``courier-service` (delivery)`](../courier-service/README.md) | _see [`SERVICE_ISOLATION.md` 2](../../architecture/SERVICE_ISOLATION.md)_ |
| [``payment-service` (driver earnings)`](../payment-service/README.md) | _see [`SERVICE_ISOLATION.md` 2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`driver-service`](../driver-service/README.md) | _see [`SERVICE_ISOLATION.md` 2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`file-service`](../file-service/README.md) | _see [`SERVICE_ISOLATION.md` 2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`food-order-service`](../food-order-service/README.md) | _see [`SERVICE_ISOLATION.md` 2](../../architecture/SERVICE_ISOLATION.md)_ |
| [``payment-service` (food saga)`](../payment-service/README.md) | _see [`SERVICE_ISOLATION.md` 2](../../architecture/SERVICE_ISOLATION.md)_ |
| _…and 25 more_ | |

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
| `wf.phase7.reward_grant.v1` | audit_service_reward_row | `trip:{trip_id}:reward:audit:row` |
| `wf.phase7.reward_reversal.v1` | audit_service_reward_reversal_row | `trip:{trip_id}:reward:audit:reverse` |
| `wf.onboarding.driver.v1` | audit_service_onboarding_row (read-only) | `driver:{id}:onboarding:audit` |
| `wf.onboarding.courier.v1` | audit_service_onboarding_row (read-only) | `courier:{id}:onboarding:audit` |
| `wf.phase75.deal_rider.v1` | audit_service_deal_transition | `deal:{deal_id}:audit:transition` |
| `wf.phase75.deal_driver.v1` | audit_service_deal_transition | `deal:{deal_id}:audit:transition` |
| `wf.phase75.deal_food.v1` | audit_service_deal_transition | `deal:{deal_id}:audit:transition` |


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
| (no compensation — terminal states only, or compensation is no-op) | – | – |


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
