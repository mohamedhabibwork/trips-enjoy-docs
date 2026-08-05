# Analytics Service

## 1. Purpose

`analytics-service` is the platform's **event ingestion pipeline
for the data lake / warehouse**. It consumes every domain event
from Kafka, applies schema evolution and PII handling, and lands
the data in the data lake (Parquet on S3) and the OLAP warehouse
(Snowflake / BigQuery / Redshift). It is the bridge between the
operational event stream and the analytical store.

## 2. Bounded Context

**Bounded context**: Event ingestion for analytics. In scope:

- Kafka consumer for all domain events.
- Schema registry integration (Avro / Protobuf).
- Schema evolution (forward / backward compatibility).
- PII handling (tokenization, hashing, masking).
- Data lake landing (Parquet on S3, partitioned by date).
- OLAP warehouse load (Snowflake / BigQuery / Redshift).
- Lag and throughput metrics.

Out of scope:

- The OLAP warehouse itself (this service is the pipeline; the
  warehouse is the destination).
- Operational dashboards (owned by `reporting-service`).
- The audit log (owned by `audit-service`; this service consumes
  the same events but lands them differently).

## 3. Responsibilities

- Consume every domain event.
- Apply schema evolution rules.
- Apply PII handling (tokenization, hashing, masking).
- Land in the data lake (Parquet, partitioned).
- Load to the OLAP warehouse.
- Track lag and throughput.
- Support a "replay" mode for backfills.

## 4. Explicitly NOT Owned

- **The OLAP warehouse** — downstream sink.
- **Operational dashboards** — `reporting-service`.
- **The audit log** — `audit-service`.

## 5. Actors

| Actor | Type | Access |
|-------|------|--------|
| Data / BI team | human | read (the warehouse) |
| Data engineer | human | replay, schema management |
| Every service (event) | system | producer of consumed events |

## 6. Dependencies

### Synchronous (REST)

- n/a (the service is consumer-only).

### Asynchronous (events consumed)

- Every domain event.

## 7. Technology Assumptions

- Runtime: Python 3.12 (rich ecosystem for data engineering).
- Database: PostgreSQL 18 (per-service schema `analytics`; control
  plane only — no domain data).
- Cache: Redis (consumer offset cache).
- Event broker: Kafka.
- Schema registry: Confluent / Karapace.
- Object storage: AWS S3 (data lake).
- OLAP: Snowflake / BigQuery / Redshift (configurable).

## 8. Database Ownership

- Schema: `analytics` (control plane only: schema registry state,
  replay jobs, consumer offsets).
- Migrations: `services/analytics-service/migrations/`.
- Soft delete: no.
- Partitioning: `analytics.replay_jobs` by month.

## 9. API Overview

| Method | URI | Auth | Purpose |
|--------|-----|------|---------|
| GET | `/v1/schemas` | bearer (admin) | list schemas |
| GET | `/v1/schemas/{name}/versions` | bearer (admin) | list versions |
| POST | `/v1/replays` | bearer (admin) | start a replay |
| GET | `/v1/replays/{id}` | bearer (admin) | replay status |
| GET | `/v1/consumer/lag` | bearer (admin) | consumer lag |

## 10. Events Produced

The service does not produce business events. It MAY emit
operational events:

- `analytics.consumer.lag.v1` — periodic metric.
- `analytics.replay.completed.v1` — replay success.

## 11. Events Consumed

The service consumes every domain event. Each event is deserialized
against the schema registry, PII fields are handled, and the row
is written to the data lake and OLAP warehouse. Recent additions
to the consumed set:

- `trip.reward.granted.v1` — per-trip guaranteed reward (driver
  top-up + customer credit). Used for incentive dashboards and
  segment analytics.
- `trip.reward.reversed.v1` — per-trip reversal; used to track
  reversal rate and dispute impact.
- `pricing.rating_density.applied.v1` — quote composition audit
  (rating-density surcharge). Used by the pricing-effectiveness
  dashboards.
- `pricing.loyalty_discount.applied.v1` — quote composition audit
  (loyalty discount). Used by the loyalty-effectiveness dashboards.
- `pricing.geo_config.updated.v1` — operator audit; the
  authoritative geo-config CRUD is in `admin-service`. Used by
  the operator-action dashboards.

## 12. External Integrations

- **HashiCorp Vault** — DB credentials, warehouse credentials.
- **AWS S3** — data lake at
  `s3://trips-enjoy-platform-analytics/datalake/<topic>/<yyyy>/<mm>/<dd>/`.
- **Snowflake / BigQuery / Redshift** — OLAP sink.
- **Confluent Schema Registry** — schema evolution.

## 13. Configuration

Operational parameters from env:

| Key | Type | Source | Notes |
|-----|------|--------|-------|
| `DB_URL` | string | env | |
| `KAFKA_BROKERS` | string | env | |
| `SCHEMA_REGISTRY_URL` | string | env | |
| `S3_BUCKET` | string | env | |
| `OLAP_URL` | string | env | |
| `OLAP_KIND` | string | env | `snowflake` / `bigquery` / `redshift` |
| `PII_HASH_SALT` | string | env | KMS-wrapped |

## 14. Security

- AuthN: JWT bearer (admin realm `platform-internal`).
- AuthZ: `analytics.read` for read; `analytics.admin` for replay
  and schema management.
- Secrets: Vault (DB, warehouse, S3, schema registry).
- PII: tokenized (HMAC-SHA256) before landing; the salt is in
  Vault.
- Read access logged.

## 15. Observability

- Logs: JSON to stdout; standard fields.
- Metrics: RED per route + `analytics_consumer_lag{topic,
  partition}`, `analytics_datalake_writes_total{topic}`,
  `analytics_olap_loads_total{table}`.
- Traces: OpenTelemetry; one root span per event.
- Health: `/health`, `/ready`, `/started`.

## 16. Scalability

- Replicas: default 6; HPA on consumer lag.
- Hot path: event consumption + landing.

## 17. Local Development

```bash
docker compose -f deploy/compose/analytics-service.yml up -d db kafka s3
make -C services/analytics-service migrate-up
poetry run python -m analytics_service
```

## 18. Deployment

- Image: `ghcr.io/trips-enjoy-platform/analytics-service:<sha>`.
- Replicas: 6 in production.
- Migrations: `pre-upgrade` Job.

## 19. Disaster Recovery

- RPO: 5 minutes (consumer lag).
- RTO: 30 minutes.
- The data lake is the source of truth; the OLAP warehouse is
  recomputable from the lake.

## 21. On-Call Runbook

### 21.1 Consumer Lag Spiking

1. Check the OLAP warehouse's load; if it's slow, the lake writes
   may be backpressured.
2. Check the S3 throttling metrics; a hot partition may be
   throttled.
3. Scale out the consumer pool; the service is stateless.

### 21.2 Schema Incompatibility Detected

1. The producer published a schema that is not forward / backward
   compatible.
2. The consumer rejects the event and routes it to DLQ.
3. The on-call coordinates with the data engineer to publish a
   compatible schema (new version, no breaking changes).

### 21.3 PII Tokenization Failure

1. The salt in Vault is unreachable; the consumer pauses.
2. Check the Vault policy; the salt may have been rotated.
3. Once the salt is restored, the consumer resumes; the lag is
   the recovery signal.

### 21.4 Lake Write Failure

1. Check S3 permissions; the service account may have lost the
   `s3:PutObject` permission.
2. Check the bucket policy; a recent change may have restricted
   the prefix.
3. Once restored, the consumer retries from the offset; the
   landing is idempotent.

### 21.5 Replay Job Stuck

1. The replay worker is iterating but not committing.
2. Cancel the job; inspect the lag and the OLAP warehouse's
   load.
3. Re-run with a smaller range; the worker is idempotent.


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

### Related services

- **Depends on**: [`audit-service`](../audit-service/README.md), [`reporting-service`](../reporting-service/README.md)
- **Depended on by**: [`address-service`](../address-service/README.md), [`api-gateway`](../api-gateway/README.md), [`cart-service`](../cart-service/README.md), [`communication-gateway-service`](../communication-gateway-service/README.md), [`courier-service`](../courier-service/README.md), [`customer-service`](../customer-service/README.md), [`driver-service`](../driver-service/README.md), [`eta-routing-service`](../eta-routing-service/README.md), [`feature-flag-service`](../feature-flag-service/README.md), [`food-order-service`](../food-order-service/README.md), [`fraud-risk-service`](../fraud-risk-service/README.md), [`geolocation-service`](../geolocation-service/README.md), [`identity-service`](../identity-service/README.md), [`loyalty-service`](../loyalty-service/README.md), [`merchant-service`](../merchant-service/README.md), [`notification-service`](../notification-service/README.md), [`pricing-service`](../pricing-service/README.md), [`promotion-service`](../promotion-service/README.md), [`reporting-service`](../reporting-service/README.md), [`review-rating-service`](../review-rating-service/README.md)

> Full dependency map in [`../README.md`](../README.md) and [`../../architecture/MICROSERVICES_MAP.md`](../../architecture/MICROSERVICES_MAP.md).

### Platform-wide

- [`../../shared/README.md`](../../shared/README.md) — `platform-spring-boot-starter` shared library (the single source of cross-cutting code for all Spring Boot services in the platform)
- [`../../shared/PLATFORM_BASELINE.md`](../../shared/PLATFORM_BASELINE.md) — single source for PostgreSQL 18, Kafka, Keycloak, Redis, OpenTelemetry, Vault, deployment, DR (do not restate these in this README)
- [`../../architecture/SERVICE_ISOLATION.md`](../../architecture/SERVICE_ISOLATION.md) — **how this service behaves when a downstream is down** (timeout / bulkhead / circuit / retry / fallback, by class: CRITICAL / DEGRADABLE / BEST-EFFORT)
- [`../../architecture/DOWNSTREAM_ERROR_CATALOG.md`](../../architecture/DOWNSTREAM_ERROR_CATALOG.md) — **canonical error-code catalog + propagation rules** (the `downstream` block, forward/translate/degrade/reject)
- [`../RECOMMENDATIONS.md`](../RECOMMENDATIONS.md) — platform-wide technology map (language, framework, version baseline, admin/RBAC pattern)
- [`../../README.md`](../../README.md) — services overview (the catalog of all 58 services)
- [`../../../main.md`](../../../main.md) — top-level platform specification (architecture, Keycloak, PostgreSQL 18, messaging, observability baseline)
- [`../../shared/OSS_DEPENDENCIES.md`](../../shared/OSS_DEPENDENCIES.md) — **open-source dependencies & license attribution** (platform-wide OSS projects + per-language OSS libraries with SPDX IDs; per-service bundle index; license compatibility matrix)
