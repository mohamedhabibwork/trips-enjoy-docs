# Reporting Service

## 1. Purpose

`reporting-service` is the platform's **read-model + dashboard**
service. It materializes domain events into queryable read models,
serves dashboard APIs, runs export jobs (CSV / Parquet), and feeds
the BI / data warehouse. It is the single source of truth for "the
state of the platform" for internal users (ops, finance, growth).

## 2. Bounded Context

**Bounded context**: Read models for dashboards and exports. In
scope:

- Materialized read models derived from domain events.
- Dashboard APIs (per role / per scope).
- Export jobs (CSV / Parquet) to S3.
- Reconciliation jobs (drift detection).
- Per-tenant isolation.

Out of scope:

- The OLAP data warehouse (the data lake is a downstream sink;
  this service is the read model for the operational dashboards).
- Business event production (every other service).
- Customer-facing reporting (the customer's own data lives in
  `customer-service` and ``trip-service` (history)`).

## 3. Responsibilities

- Consume domain events and update read models.
- Serve dashboard APIs (per role).
- Run export jobs on a schedule.
- Run reconciliation jobs (drift detection between services).
- Per-tenant isolation.
- Emit `reconciliation.drift.found.v1` on drift.

## 4. Explicitly NOT Owned

- **The OLAP data warehouse** — ``reporting-service` (data lake)` is the
  pipeline; this service is the operational read model.
- **Business event production** — every other service.
- **Customer-facing reporting** — ``trip-service` (history)`,
  `customer-service`.

## 5. Actors

| Actor | Type | Access |
|-------|------|--------|
| Operator (admin) | human | read dashboards, run exports |
| BI / Data team | human | read dashboards, run exports |
| Reconciliation job | system | read |
| Every service (event) | system | producer of consumed events |

## 6. Dependencies

### Synchronous (REST)

- Every service (read) — for dashboard drill-downs (e.g. fetch a
  trip detail by id).

### Asynchronous (events consumed)

- Every domain event (read model derivation).

## 7. Technology Assumptions

- Runtime: **Python 3.14 + FastAPI** for the read-model + analytics + export jobs (per `TECH.md`); the platform's backend apps are Go, Kotlin, or Python only — no Node.js / TypeScript on the backend.
- Database: PostgreSQL 19 (per-service schema `reporting`; own
  schemas per read model).
- Cache: Redis cluster.
- Event broker: Kafka.
- Object storage: AWS S3 (exports).

## 8. Database Ownership

- Schema: `reporting` (with sub-schemas per read model:
  `reporting_trips`, `reporting_orders`, `reporting_payments`, …).
- Migrations: `services/reporting-service/migrations/`.
- Soft delete: no (read models are recomputed, not edited).
- Partitioning: per read model, by date.

## 9. API Overview

| Method | URI | Auth | Purpose |
|--------|-----|------|---------|
| GET | `/v1/dashboards/{name}` | bearer (admin) | dashboard data |
| GET | `/v1/views/{view_name}` | bearer (admin) | read model view |
| POST | `/v1/exports/{name}/run` | bearer (admin) | run export |
| GET | `/v1/exports/{name}/status` | bearer (admin) | export status |
| GET | `/v1/reconciliation/drift` | bearer (admin) | drift findings |
| GET | `/v1/read-models` | bearer (admin) | list read models |

## 10. Events Produced

| Event | Trigger | Consumers |
|-------|---------|-----------|
| `reconciliation.drift.found.v1` | drift detected | `admin-service`, ``admin-service` (support module)` |
| `reporting.export.completed.v1` | export job success | `admin-service` |
| `reporting.view.refreshed.v1` | read model refreshed (operational) | ``reporting-service` (data lake)` |

## 11. Events Consumed

The service consumes a comprehensive list of domain events to
build read models. Each event is handled by a dedicated projector
that updates the relevant read model.

## 12. External Integrations

- **HashiCorp Vault** — DB credentials, signing keys.
- **AWS S3** — export storage at
  `s3://trips-enjoy-platform-reporting/exports/<yyyy>/<mm>/<dd>/`.

## 13. Configuration

Operational parameters from env:

| Key | Type | Source | Notes |
|-----|------|--------|-------|
| `DB_URL` | string | env | |
| `KAFKA_BROKERS` | string | env | |
| `REDIS_URL` | string | env | |
| `S3_BUCKET` | string | env | |
| `RECONCILIATION_CRON` | string | env | `0 4 * * *` |

Runtime configuration keys read from `configuration-service`:

| Key | Type | Source | Notes |
|-----|------|--------|-------|
| `reporting.export.cron.{export_id}` | string | configuration-service | per-export cron schedule |
| `reporting.warehouse.snowflake.enabled` | bool | configuration-service | sink toggle |
| `reporting.warehouse.bigquery.enabled` | bool | configuration-service | sink toggle |
| `reporting.warehouse.redshift.enabled` | bool | configuration-service | sink toggle |
| `reporting.dashboards.refresh_seconds` | int | configuration-service | default 30 |
| `reporting.query_cache.ttl_seconds` | int | configuration-service | default 60 |
| `reporting.parquet.row_group_size` | int | configuration-service | default 100 000 |

> **Canonical key index.** See
> [`../configuration-service/INTEGRATION.md` 10.17](../configuration-service/INTEGRATION.md#1017-reporting-service)
> for the full `reporting.*` key family.

## 14. Security

- AuthN: JWT bearer.
- AuthZ: per-dashboard scopes; per-export scopes; per-tenant
  isolation.
- Secrets: Vault.
- PII: masked in non-admin reads.
- Read access is logged.

## 15. Observability

- Logs: JSON to stdout; standard fields.
- Metrics: RED per route + `reporting_view_lag{view_name}`,
  `reporting_export_seconds`, `reporting_drift_findings_total`.
- Traces: OpenTelemetry; one root span per event projection.
- Health: `/health`, `/ready`, `/started`.

## 16. Scalability

- Replicas: default 6; HPA on consumer lag.
- Hot path: event projection (high throughput).

## 17. Local Development

```bash
docker compose -f deploy/compose/reporting-service.yml up -d db kafka
make -C services/reporting-service migrate-up
pnpm --filter @platform/reporting-service dev
```

## 18. Deployment

- Image: `ghcr.io/trips-enjoy-platform/reporting-service:<sha>`.
- Replicas: 6 in production.
- Migrations: `pre-upgrade` Job.

## 19. Disaster Recovery

- RPO: 5 minutes.
- RTO: 30 minutes.
- The read models are derivable from the event stream; recovery is
  from the latest offset.

## 20. Accounting impact

`reporting-service` is the **reporting layer of the accounting
model**. It consumes `ledger.posted.v1` and the money-movement event
streams to produce the trial balance, balance sheet, income
statement, tax filings, and reconciliation reports.

- **What money facts it owns:** read models — trial balance,
  income statement, balance sheet, per-jurisdiction tax summary,
  promotion / incentive / chargeback summaries.
- **Reconciliation jobs:** all six daily reconciliation jobs are
  scheduled here:
  - `payment-service` 02:00 UTC (vs provider report),
  - ``payment-service` (wallet)` 03:00 UTC (vs ledger wallet account),
  - ``payment-service` (courier earnings)` 03:00 UTC (vs courier_payable),
  - `ledger-service` 04:00 UTC (cross-checks all operational
    layers),
  - ``payment-service` (driver earnings)` and ``payment-service` (merchant settlement)`
    daily (per their own schedules).
- **Drift detection:** each job compares the operational layer
  against `ledger-service` and emits
  `reconciliation.drift.found.v1` on mismatch; opens a P1 ticket
  via ``admin-service` (support module)`; escalates P0 if drift > 24h.
- **Period close:** at month end, regenerates the trial balance
  from the ledger for the closed period and produces the
  income statement and balance sheet for downstream regulatory
  export.
- **Tax filings:** generates the per-jurisdiction `tax_collected`
  and `tax_remitted` summaries that `admin-service` uses to file
  returns.
- **Human operator path:** scheduled reports + ad-hoc queries via
  `reporting.viewer` role; no direct ledger writes.

See [`../../workflows/ACCOUNTING_WORKFLOWS.md`](../../workflows/ACCOUNTING_WORKFLOWS.md)
for the cross-service view.

## 21. On-Call Runbook

### 21.1 View Lag Exceeded

1. Check the consumer lag per topic; the lag may be due to a
   downstream service slowdown.
2. If a single topic is lagging, restart the projector; the
   projection is idempotent.
3. If multiple topics are lagging, scale out the consumer pool.

### 21.2 Drift Detected

1. The daily job emits `reconciliation.drift.found.v1` and opens
   a `support.ticket`.
2. The on-call reviews the drift; common causes are:
   - The source service's API returned a different value
     (e.g. cache vs DB).
   - An event was missed (consumer lag > retention).
   - A manual fix in the source service.
3. Do NOT auto-repair; the audit trail must be preserved.

### 21.3 Export Stuck

1. Check the export job in `reporting.export_jobs`; if `status`
   is `running` for > 1 hour, the worker is stuck.
2. Cancel the job (set `status='failed'`, `error='cancelled'`).
3. Re-run with a fresh `Idempotency-Key`.

### 21.4 Rebuild a View

1. When a view schema changes, the on-call may need to rebuild.
2. Run the rebuild command: it replays the event stream from
   the beginning (or from a checkpoint) and re-projects.
3. The rebuild is idempotent; the read model is the same after
   the rebuild.

### 21.5 PII Masking Failure

1. The view is exposing unmasked PII.
2. Roll back the view to the prior schema; the projection code
   is the source of the mask.
3. Open a P1 ticket; the security team is paged.


---

## Appendix A — Removed predecessor capability

The capability that used to live in ``reporting-service` (data lake)` (event
ingestion pipeline for the data lake) is now absorbed into this
service. The canonical source is
[`../../MIGRATION_HUB.md`](../../MIGRATION_HUB.md) 3.37.

### A.1 Bounded context (post-merger)

Materialised read models + dashboards + exports + data warehouse
ingestion. The service is the **only** writer of the `reporting`
schema.

### A.2 Absorbed responsibilities (from `reporting-service` (data lake))

- Subscribe to every domain event for the data lake.
- Maintain `reporting.ingest_state` (cursor per topic).
- Maintain `reporting.materialised_views` (per-domain read
  models).

### A.3 Absorbed REST endpoints

| Method | URI | Auth | Purpose |
|--------|-----|------|---------|
| GET  | `/v1/reports/{id}` | bearer (admin) | read report |
| POST | `/v1/reports/{id}/export` | bearer (admin) | export |
| GET  | `/v1/exports/{id}.csv` | bearer (admin) | download |

### A.4 Compatibility window

For at least six calendar months from 2026-08-05:

- `/v1/reports/{id}`, `/v1/reports/{id}/export`,
  `/v1/exports/{id}.csv` continue to be served from this service.
- Old schema name `analytics.*` remains readable as a view in the
  `reporting` schema.

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
- [`PLAN.md`](./PLAN.md) — implementation tasks, phases, dependencies

### Related services

- **Depends on**: [`admin-service`](../admin-service/README.md), [``reporting-service` (data lake)`](../reporting-service/README.md), [`customer-service`](../customer-service/README.md), [``trip-service` (history)`](../trip-service/README.md), [``admin-service` (support module)`](../admin-service/README.md)
- **Depended on by**: [``reporting-service` (data lake)`](../reporting-service/README.md), [`configuration-service`](../configuration-service/README.md), [``payment-service` (courier earnings)`](../payment-service/README.md), [``payment-service` (driver earnings)`](../payment-service/README.md), [``driver-service` (incentives)`](../driver-service/README.md), [``driver-service` (location)`](../driver-service/README.md), [`fraud-risk-service`](../fraud-risk-service/README.md), [`geolocation-service`](../geolocation-service/README.md), [`ledger-service`](../ledger-service/README.md)

> Full dependency map in [`../README.md`](../README.md) and [`../../architecture/MICROSERVICES_MAP.md`](../../architecture/MICROSERVICES_MAP.md).

### Platform-wide

- [`../../shared/README.md`](../../shared/README.md) — `platform-spring-boot-starter` shared library (the single source of cross-cutting code for all Spring Boot services in the platform)
- [`../../shared/PLATFORM_BASELINE.md`](../../shared/PLATFORM_BASELINE.md) — single source for PostgreSQL 19, Kafka, Keycloak, Redis, OpenTelemetry, Vault, deployment, DR (do not restate these in this README)
- [`../../architecture/SERVICE_ISOLATION.md`](../../architecture/SERVICE_ISOLATION.md) — **how this service behaves when a downstream is down** (timeout / bulkhead / circuit / retry / fallback, by class: CRITICAL / DEGRADABLE / BEST-EFFORT)
- [`../../architecture/DOWNSTREAM_ERROR_CATALOG.md`](../../architecture/DOWNSTREAM_ERROR_CATALOG.md) — **canonical error-code catalog + propagation rules** (the `downstream` block, forward/translate/degrade/reject)
- [`../RECOMMENDATIONS.md`](../RECOMMENDATIONS.md) — platform-wide technology map (language, framework, version baseline, admin/RBAC pattern)
- [`../../README.md`](../../README.md) — services overview (the catalog of all 20 services)
- [`../../../main.md`](../../../main.md) — top-level platform specification (architecture, Keycloak, PostgreSQL 19, messaging, observability baseline)
- [`../../shared/OSS_DEPENDENCIES.md`](../../shared/OSS_DEPENDENCIES.md) — **open-source dependencies & license attribution** (platform-wide OSS projects + per-language OSS libraries with SPDX IDs; per-service bundle index; license compatibility matrix)

### Workflows this service participates in

- [`../../workflows/ACCOUNTING_WORKFLOWS.md`](../../workflows/ACCOUNTING_WORKFLOWS.md) — accounting view (reconciliation, period close, tax filings, regulatory reports)
