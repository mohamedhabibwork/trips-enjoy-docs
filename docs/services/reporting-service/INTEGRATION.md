# Reporting Service — Integration Contract

## 1. Inbound APIs

All endpoints are versioned under `/v1`. Auth: bearer JWT. Errors
use the standard envelope.

### 1.1 `GET /v1/dashboards/{name}`

- **Purpose**: Read a dashboard.
- **Auth**: Bearer JWT. Required scope: per-dashboard (e.g.
  `reporting.dashboard.operations`).
- **Query params**: `from`, `to`, `tenant_id` (or derived from
  token), `granularity`.
- **Response (200)**:
  ```json
  {
    "name": "operations",
    "as_of": "2026-07-29T10:42:11.183Z",
    "panels": [
      {
        "name": "trips_per_hour",
        "data": [ { "ts": "2026-07-29T10:00:00Z", "value": 1234 } ]
      }
    ]
  }
  ```
- **Errors**: 401 / 403 / 400.

### 1.2 `GET /v1/views/{view_name}`

- **Purpose**: Read a view (read model).
- **Auth**: Bearer JWT. Required scope: per-view.
- **Query params**: `cursor`, `limit`, filters.
- **Response (200)**: paginated list.
- **Errors**: 401 / 403 / 400.

### 1.3 `POST /v1/exports/{name}/run`

- **Purpose**: Run an export.
- **Auth**: Bearer JWT. Required scope: `reporting.export.{name}`.
- **Idempotency**: `Idempotency-Key` required.
- **Request**:
  ```json
  {
    "format": "parquet",
    "from": "2026-07-01T00:00:00Z",
    "to": "2026-07-29T23:59:59Z",
    "tenant_id": "global",
    "reason": "Monthly revenue export"
  }
  ```
- **Response (202)**:
  ```json
  {
    "job_id": "01HZX…",
    "status": "queued",
    "correlation_id": "01HZX…"
  }
  ```
- **Errors**: 401 / 403 / 400 / 409 `EXPORT_IN_PROGRESS`.

### 1.4 `GET /v1/exports/{name}/status?job_id=...`

- **Purpose**: Read export job status.
- **Auth**: Bearer JWT. Required scope: `reporting.export.{name}`.
- **Response (200)**: the job.
- **Errors**: 401 / 403 / 404.

### 1.5 `GET /v1/reconciliation/drift`

- **Purpose**: Read drift findings.
- **Auth**: Bearer JWT. Required role: `reporting.admin`.
- **Query params**: `view_name`, `severity`, `status`, `from`, `to`.
- **Response (200)**: paginated list.
- **Errors**: 401 / 403.

### 1.6 `GET /v1/read-models`

- **Purpose**: List read models and their lag.
- **Auth**: Bearer JWT. Required role: `reporting.admin`.
- **Response (200)**:
  ```json
  {
    "items": [
      { "name": "reporting_trips.trips", "lag_seconds": 12, "row_count": 1234567 }
    ]
  }
  ```
- **Errors**: 401 / 403.

## 2. Outbound APIs

| Target | Method | URI | Purpose | Timeout | Retry | Circuit |
|--------|--------|-----|---------|---------|-------|---------|
| Every service | GET | per service | drill-down | 1s | 3 | yes |
| AWS S3 | PUT | per export path | export storage | 60s | 3 | n/a |

## 3. Produced Events

### 3.1 `reconciliation.drift.found.v1`

- **Producer**: `reporting-service`.
- **Topic**: `reconciliation.drift.found`.
- **Trigger**: a drift is detected.
- **Schema version**: 1.
- **Partition key**: `view_name`.
- **Schema**:
  ```json
  {
    "event_id": "01HZX9C8W6K0G3V2Y5N1Q4R7PB",
    "event_name": "reconciliation.drift.found.v1",
    "occurred_at": "2026-07-29T10:42:11.183Z",
    "schema_version": 1,
    "producer": "reporting-service",
    "tenant_id": "global",
    "correlation_id": "01HZX9C7T0XK2P9F0V6E4B1MZA",
    "aggregate_type": "DriftFinding",
    "aggregate_id": "01HZX9C5S3B1L7K0P2F8V4T6YDA",
    "data": {
      "view_name": "reporting_trips.trips",
      "drift_type": "missing",
      "entity_id": "01HZX…",
      "details": { "source_service": "trip-service" },
      "severity": "high"
    }
  }
  ```
- **Retry / DLQ**: outbox / `reconciliation.drift.found.dlq`.

### 3.2 `reporting.export.completed.v1`

Same envelope with `aggregate_type: "ExportJob"`, `data: { job_id,
s3_path, row_count, size_bytes }`.

### 3.3 `reporting.view.refreshed.v1`

Operational metric; not consumed by other services directly.

## 4. Consumed Events

The service consumes a comprehensive list. Each event is handled by
a dedicated projector.

### 4.1 Topics

- `trip.*` → `reporting_trips.trips`
- `ride.payment.*` → `reporting_payments.intents` (subset)
- `food.order.*` → `reporting_orders.orders`
- `delivery.*` → `reporting_orders.deliveries`
- `payment.*` → `reporting_payments.intents`
- `wallet.*` → `reporting_payments.wallets`
- `ledger.posted` → `reporting_ledger.postings`
- `customer.*` → `reporting_customers.customers`
- `driver.*` → `reporting_drivers.drivers`
- `courier.*` → `reporting_couriers.couriers`
- `merchant.*` → `reporting_merchants.merchants`
- `restaurant.*` → `reporting_restaurants.restaurants`
- `promotion.redeemed` → `reporting_promotions.redemptions`
- `loyalty.*` → `reporting_loyalty.accounts`
- `review.submitted` → `reporting_reviews.reviews`

### 4.2 Handler

- **Reason**: every domain event updates the relevant read model.
- **Handler**: idempotent projection on `event_id` via inbox.
- **Deduplication**: inbox.
- **Retry**: 3 with backoff.
- **Failure**: DLQ.

### 4.3 `ledger.posted.v1`

- **Producer**: `ledger-service`.
- **Reason**: Money movement for financial dashboards.
- **Handler**: Increment fact table.
- **Deduplication / Retry / Failure**: inbox keyed by
  `event_id` / 3 with exponential backoff / DLQ.


### 4.4 `configuration.updated.v1`

- **Producer**: `configuration-service`.
- **Reason**: Report schema changed.
- **Handler**: Rebuild view.
- **Deduplication / Retry / Failure**: inbox keyed by
  `event_id` / 3 with exponential backoff / DLQ.


### 4.5 `analytics.export.completed.v1`

- **Producer**: ``reporting-service` (data lake)`.
- **Reason**: Bulk export completed.
- **Handler**: Mark as ready.
- **Deduplication / Retry / Failure**: inbox keyed by
  `event_id` / 3 with exponential backoff / DLQ.



## 5. Reliability

- **Timeouts**: HTTP 1s; DB 30s; S3 PUT 60s; Kafka publish 5s.
- **Retries**: bounded 3 with exponential backoff + jitter.
- **Circuit breakers**: every outbound; on `CIRCUIT_OPEN`, the
  reconciliation pauses for the affected service.
- **Bulkheads**: separate consumer pool per topic group.
- **Outbox**: yes.
- **Inbox**: yes.
- **DLQ**: every topic has a paired DLQ.
- **Reconciliation**: daily job; on drift, emit
  `reconciliation.drift.found.v1` and open a `support.ticket`.

## 6. Correlation IDs

Every request carries `X-Correlation-Id`; the service returns it in
the response header and embeds it in the event envelope and the
read access log.

## 7. Distributed Tracing

OpenTelemetry: one root span per event projection; child spans for
DB, Redis, Kafka. `traceparent` propagated through Kafka headers.
Sample rate 100% for errors, 10% for successes.

### 4.3 `trip.completed.v1`

Concrete example of an event this service consumes.

- **Producer**: `trip-service`.
- **Reason**: every trip completion updates the
  `reporting_trips.trips` read model.
- **Handler**: project the event into the trips read model
  (UPSERT on `id`, update `last_event_at`).
- **Deduplication**: inbox on `event_id`.
- **Retry**: 3 with backoff.
- **Failure**: DLQ.

### 4.4 `food.order.placed.v1`

- **Producer**: `food-order-service`.
- **Reason**: every order placement updates the
  `reporting_orders.orders` read model.
- **Handler**: project the event into the orders read model.
- **Deduplication / Retry / Failure**: inbox / 3 / DLQ.

### 4.5 `payment.captured.v1`

- **Producer**: `payment-service`.
- **Reason**: every payment capture updates the
  `reporting_payments.intents` read model.
- **Handler**: project the event into the payments read model.
- **Deduplication / Retry / Failure**: inbox / 3 / DLQ.

### 4.6 `promotion.redeemed.v1`

- **Producer**: ``pricing-service` (promotion)`.
- **Reason**: every redemption updates the
  `reporting_promotions.redemptions` read model.
- **Handler**: project the event into the redemptions read model.
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
| [``reporting-service` (data lake)`](../reporting-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [`customer-service`](../customer-service/README.md) | CRITICAL | 503 `DEPENDENCY_UNAVAILABLE` |
| [``trip-service` (history)`](../trip-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [``admin-service` (support module)`](../admin-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |

### Downstream services that depend on this service

| Downstream | Class (from its perspective) |
|---|---|
| [``reporting-service` (data lake)`](../reporting-service/README.md) | _see [`SERVICE_ISOLATION.md` 2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`configuration-service`](../configuration-service/README.md) | _see [`SERVICE_ISOLATION.md` 2](../../architecture/SERVICE_ISOLATION.md)_ |
| [``payment-service` (courier earnings)`](../payment-service/README.md) | _see [`SERVICE_ISOLATION.md` 2](../../architecture/SERVICE_ISOLATION.md)_ |
| [``payment-service` (driver earnings)`](../payment-service/README.md) | _see [`SERVICE_ISOLATION.md` 2](../../architecture/SERVICE_ISOLATION.md)_ |
| [``driver-service` (incentives)`](../driver-service/README.md) | _see [`SERVICE_ISOLATION.md` 2](../../architecture/SERVICE_ISOLATION.md)_ |
| [``driver-service` (location)`](../driver-service/README.md) | _see [`SERVICE_ISOLATION.md` 2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`fraud-risk-service`](../fraud-risk-service/README.md) | _see [`SERVICE_ISOLATION.md` 2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`geolocation-service`](../geolocation-service/README.md) | _see [`SERVICE_ISOLATION.md` 2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`ledger-service`](../ledger-service/README.md) | _see [`SERVICE_ISOLATION.md` 2](../../architecture/SERVICE_ISOLATION.md)_ |

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
- [`../../../main.md`](../../../main.md) — top-level platform specification (architecture, Keycloak, PostgreSQL 19, messaging, observability baseline)

## Conductor Workers

This service runs Conductor workers for the following workflows per
[ADR-0018](../../architecture/adrs/0018-workflow-engine-conductor.md) and
[`shared/CONDUCTOR_WORKFLOWS.md`](../../shared/CONDUCTOR_WORKFLOWS.md).
Workers are colocated in this service's binary; SDK: **conductor-kotlin v3.x**.

| Workflow ID | Tasks owned | Idempotency-Key namespace |
|---|---|---|
| Workflow ID | Tasks owned | Idempotency-Key namespace |
|---|---|---|
| `wf.phase7.reward_grant.v1` | reporting_service_reward_fact | `request:{request_id}:reward:reporting:fact` |
| `wf.phase7.reward_reversal.v1` | reporting_service_reward_reversal_fact | `request:{request_id}:reward:reporting:reverse` |


### Kafka signal mapping

| Topic | Signal | Triggers |
|---|---|---|
| `conductor.workflow.history.v1` | `Conductor history export` | append to data lake fact table |


### Compensation responsibilities

This service implements the following compensation tasks; see
[`shared/CONDUCTOR_WORKFLOWS.md`](../../shared/CONDUCTOR_WORKFLOWS.md) 4 for
ordering rules.

| Forward task | Compensation task | Reversibility |
|---|---|---|
| (no compensation — terminal states only, or compensation is no-op) | – | – |


### Configuration keys

- `conductor.server.url` — set by Helm per env (e.g. `https://conductor.prod.trips-enjoy.com`)
- `conductor.task.<task_name>.timeout_seconds` — default 30s
- `conductor.task.<task_name>.retry_count` — default 3
- `conductor.worker.heartbeat_interval_seconds` — default 5s
- `conductor.kafka.bridge.url` — for `conductor-kafka-bridge` integration

### Operational references

- Runbook: [`shared/CONDUCTOR_WORKFLOWS.md`](../../shared/CONDUCTOR_WORKFLOWS.md) 8
- Observability: [`shared/CONDUCTOR_WORKFLOWS.md`](../../shared/CONDUCTOR_WORKFLOWS.md) 7
- Master task registry: [`MASTER_TASK.md`](../../MASTER_TASK.md) 7-9
