# Analytics Service — Integration Contract

## 1. Inbound APIs

All endpoints are versioned under `/v1`. Auth: bearer JWT. Errors
use the standard envelope.

### 1.1 `GET /v1/schemas`

- **Purpose**: List schemas.
- **Auth**: Bearer JWT. Required scope: `analytics.read`.
- **Response (200)**:
  ```json
  {
    "items": [
      { "name": "trip.completed", "versions": [1, 2], "latest_version": 2 }
    ]
  }
  ```
- **Errors**: 401 / 403.

### 1.2 `GET /v1/schemas/{name}/versions`

- **Purpose**: List versions of a schema.
- **Auth**: Bearer JWT. Required scope: `analytics.read`.
- **Response (200)**:
  ```json
  {
    "items": [
      { "version": 2, "compatibility": "backward", "created_at": "..." }
    ]
  }
  ```
- **Errors**: 401 / 403 / 404.

### 1.3 `POST /v1/replays`

- **Purpose**: Start a replay (backfill).
- **Auth**: Bearer JWT. Required role: `analytics.admin`. Required
  header: `X-Audit-Reason`.
- **Idempotency**: `Idempotency-Key` required.
- **Request**:
  ```json
  {
    "topic": "trip.completed",
    "partition": null,
    "from_offset": 1234567,
    "to_offset": null,
    "dry_run": false,
    "reason": "Backfill after schema change"
  }
  ```
- **Response (202)**:
  ```json
  {
    "job_id": "01HZX…",
    "status": "pending",
    "correlation_id": "01HZX…"
  }
  ```
- **Errors**: 401 / 403 / 400 / 409 `REPLAY_IN_PROGRESS`.

### 1.4 `GET /v1/replays/{id}`

- **Purpose**: Read replay status.
- **Auth**: Bearer JWT. Required role: `analytics.admin`.
- **Response (200)**: the job.
- **Errors**: 401 / 403 / 404.

### 1.5 `GET /v1/consumer/lag`

- **Purpose**: Read consumer lag per topic / partition.
- **Auth**: Bearer JWT. Required role: `analytics.admin`.
- **Response (200)**:
  ```json
  {
    "items": [
      { "topic": "trip.completed", "partition": 0, "offset": 1234567, "lag_seconds": 12 }
    ]
  }
  ```
- **Errors**: 401 / 403.

## 2. Outbound APIs

| Target | Method | URI | Purpose | Timeout | Retry | Circuit |
|--------|--------|-----|---------|---------|-------|---------|
| Schema registry | GET | per schema | read schema | 1s | 3 | yes |
| AWS S3 | PUT | per event | write to data lake | 30s | 3 | n/a |
| OLAP warehouse | per kind | per load | load to warehouse | 60s | 3 | yes |
| HashiCorp Vault | GET | per secret | read credentials | 1s | 3 | yes |

## 3. Produced Events

The service does not produce business events. It MAY emit
operational events:

### 3.1 `analytics.replay.completed.v1`

- **Producer**: `analytics-service`.
- **Topic**: `analytics.replay.completed`.
- **Trigger**: replay success.
- **Schema version**: 1.
- **Partition key**: `topic`.
- **Schema**:
  ```json
  {
    "event_id": "01HZX9C8W6K0G3V2Y5N1Q4R7PB",
    "event_name": "analytics.replay.completed.v1",
    "occurred_at": "2026-07-29T10:42:11.183Z",
    "schema_version": 1,
    "producer": "analytics-service",
    "tenant_id": "global",
    "correlation_id": "01HZX9C7T0XK2P9F0V6E4B1MZA",
    "aggregate_type": "ReplayJob",
    "aggregate_id": "01HZX9C5S3B1L7K0P2F8V4T6YDA",
    "data": {
      "topic": "trip.completed",
      "events_processed": 1234567,
      "started_at": "2026-07-29T10:00:00Z",
      "completed_at": "2026-07-29T10:42:11.183Z"
    }
  }
  ```
- **Retry / DLQ**: outbox / `analytics.replay.completed.dlq`.

### 3.2 `analytics.ingest.batch_completed.v1`

- **Producer**: this service.
- **Topic**: `platform.analytics`.
- **Trigger**: A batch ingestion job completes.
- **Schema version**: 1.
- **Partition key**: `batch_id`.
- **Consumers**: `reporting-service`, `admin-service`.
- **Schema**:

  ```json
  {
    "event_id": "01HZX9C8W6K0G3V2Y5N1Q4R7PB",
    "event_name": "analytics.ingest.batch_completed.v1",
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
- **DLQ**: `platform.analytics.dlq`.


### 3.3 `analytics.export.completed.v1`

- **Producer**: this service.
- **Topic**: `platform.analytics`.
- **Trigger**: A scheduled export completes.
- **Schema version**: 1.
- **Partition key**: `export_id`.
- **Consumers**: `admin-service`, `reporting-service`.
- **Schema**:

  ```json
  {
    "event_id": "01HZX9C8W6K0G3V2Y5N1Q4R7PB",
    "event_name": "analytics.export.completed.v1",
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
- **DLQ**: `platform.analytics.dlq`.


### 3.4 `analytics.drift.detected.v1`

- **Producer**: this service.
- **Topic**: `platform.analytics`.
- **Trigger**: A reconciliation drift is detected in the data warehouse.
- **Schema version**: 1.
- **Partition key**: `drift_id`.
- **Consumers**: `admin-service`, `support-service`.
- **Schema**:

  ```json
  {
    "event_id": "01HZX9C8W6K0G3V2Y5N1Q4R7PB",
    "event_name": "analytics.drift.detected.v1",
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
- **DLQ**: `platform.analytics.dlq`.



## 4. Consumed Events

The service subscribes to every domain event. Each event is
deserialized against the schema registry, PII fields are
tokenized, and the row is written to the data lake.

### 4.1 Topics

The service subscribes to the same set as `audit-service` (see
`audit-service/INTEGRATION.md` §4.1) plus the operational topics.

### 4.2 Handler

- **Reason**: every domain event must be landed in the lake and
  loaded to the warehouse.
- **Handler**: deserialize, tokenize PII, write to lake, commit
  offset.
- **Deduplication**: offset (no duplicates in normal operation).
- **Retry**: 3 with backoff.
- **Failure**: DLQ.

### 4.3 `ride.payment.completed.v1`

- **Producer**: `ride-payment-integration-service`.
- **Reason**: Revenue / GMV / commission metrics.
- **Handler**: Increment fact table.
- **Deduplication / Retry / Failure**: inbox keyed by
  `event_id` / 3 with exponential backoff / DLQ.


### 4.4 `food.payment.completed.v1`

- **Producer**: `food-payment-integration-service`.
- **Reason**: GMV / merchant revenue / courier pay.
- **Handler**: Increment fact table.
- **Deduplication / Retry / Failure**: inbox keyed by
  `event_id` / 3 with exponential backoff / DLQ.


### 4.5 `trip.completed.v1`

- **Producer**: `trip-service`.
- **Reason**: Trip count / distance / duration.
- **Handler**: Increment fact table.
- **Deduplication / Retry / Failure**: inbox keyed by
  `event_id` / 3 with exponential backoff / DLQ.


### 4.6 `food.order.delivered.v1`

- **Producer**: `delivery-service`.
- **Reason**: Order count / prep time / delivery time.
- **Handler**: Increment fact table.
- **Deduplication / Retry / Failure**: inbox keyed by
  `event_id` / 3 with exponential backoff / DLQ.


### 4.7 `ledger.posted.v1`

- **Producer**: `ledger-service`.
- **Reason**: All money movement (doubles every other source).
- **Handler**: Increment fact table.
- **Deduplication / Retry / Failure**: inbox keyed by
  `event_id` / 3 with exponential backoff / DLQ.



## 5. Reliability

- **Timeouts**: HTTP 1s; S3 PUT 30s; OLAP 60s; Kafka publish 5s.
- **Retries**: bounded 3 with exponential backoff + jitter.
- **Circuit breakers**: every outbound; on `CIRCUIT_OPEN`, the
  consumer pauses for the affected target.
- **Bulkheads**: separate consumer pool per topic group.
- **Outbox**: yes.
- **Inbox**: yes.
- **DLQ**: every topic has a paired DLQ.
- **Reconciliation**: daily job verifies the lake row count vs the
  consumer offset; drift opens a `support.ticket`.

## 6. Correlation IDs

Every request carries `X-Correlation-Id`; the service returns it in
the response header and embeds it in the event envelope.

## 7. Distributed Tracing

OpenTelemetry: one root span per ingested event; child spans for
schema fetch, PII tokenization, lake write, OLAP load. `traceparent`
propagated through Kafka headers. Sample rate 100% for errors, 10%
for successes.

### 3.2 `analytics.consumer.lag.v1`

- **Producer**: `analytics-service`.
- **Topic**: `analytics.consumer.lag`.
- **Trigger**: periodic (every minute).
- **Schema version**: 1.
- **Partition key**: `topic`.
- **Schema**:
  ```json
  {
    "event_id": "01HZX9C8W6K0G3V2Y5N1Q4R7PB",
    "event_name": "analytics.consumer.lag.v1",
    "occurred_at": "2026-07-29T10:42:00.000Z",
    "schema_version": 1,
    "producer": "analytics-service",
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

### 3.3 `analytics.schema.registered.v1`

- **Producer**: `analytics-service`.
- **Topic**: `analytics.schema.registered`.
- **Trigger**: a new schema version is registered.
- **Schema version**: 1.
- **Partition key**: `name`.
- **Schema**:
  ```json
  {
    "event_id": "01HZX9C8W6K0G3V2Y5N1Q4R7PB",
    "event_name": "analytics.schema.registered.v1",
    "occurred_at": "2026-07-29T10:42:11.183Z",
    "schema_version": 1,
    "producer": "analytics-service",
    "tenant_id": "global",
    "correlation_id": "01HZX9C7T0XK2P9F0V6E4B1MZA",
    "aggregate_type": "SchemaVersion",
    "aggregate_id": "trip.completed.v2",
    "data": {
      "name": "trip.completed",
      "version": 2,
      "compatibility": "backward",
      "actor_id": "01HZX…"
    }
  }
  ```
- **Retry / DLQ**: outbox.

### 4.3 `payment.captured.v1`

Concrete example of an event this service consumes. (See §4.1 for
the full list; this entry shows the contract shape.)

- **Producer**: `payment-service`.
- **Reason**: every payment capture is a financial event that must
  be landed in the data lake for OLAP analysis.
- **Handler**: deserialize, tokenize PII, write to lake, load to
  warehouse.
- **Schema (envelope)**: standard envelope.
- **Deduplication**: offset (no duplicates in normal operation).
- **Retry**: 3.
- **Failure**: DLQ.

### 4.4 `trip.completed.v1`

- **Producer**: `trip-service`.
- **Reason**: every trip completion is a high-volume event that
  drives the operational dashboards.
- **Handler**: deserialize, tokenize PII, write to lake, load to
  warehouse.
- **Schema (envelope)**: standard envelope.
- **Deduplication / Retry / Failure**: offset / 3 / DLQ.

### 4.5 `customer.created.v1`

- **Producer**: `customer-service`.
- **Reason**: customer creation is a low-volume event used for
  cohort analysis.
- **Handler**: deserialize, tokenize PII (name, email, phone),
  write to lake.
- **Schema (envelope)**: standard envelope.
- **Deduplication / Retry / Failure**: offset / 3 / DLQ.


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
| [`audit-service`](../audit-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [`reporting-service`](../reporting-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |

### Downstream services that depend on this service

| Downstream | Class (from its perspective) |
|---|---|
| [`address-service`](../address-service/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`api-gateway`](../api-gateway/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`cart-service`](../cart-service/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`communication-gateway-service`](../communication-gateway-service/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`courier-service`](../courier-service/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`customer-service`](../customer-service/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`driver-service`](../driver-service/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`eta-routing-service`](../eta-routing-service/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`feature-flag-service`](../feature-flag-service/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`food-order-service`](../food-order-service/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`fraud-risk-service`](../fraud-risk-service/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`geolocation-service`](../geolocation-service/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`identity-service`](../identity-service/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`loyalty-service`](../loyalty-service/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`merchant-service`](../merchant-service/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`notification-service`](../notification-service/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`pricing-service`](../pricing-service/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`promotion-service`](../promotion-service/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`reporting-service`](../reporting-service/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`review-rating-service`](../review-rating-service/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| _…and 6 more_ | |

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

