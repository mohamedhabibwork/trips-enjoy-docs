# Reporting Service — Business Requirements Document

## 1. Document Purpose

Read by the operations team, the finance team, the growth team,
the BI team, and the reporting-service engineering team. It informs
the design of the read model derivation, the dashboard APIs, the
export jobs, and the reconciliation logic.

## 2. Business Context

The platform operates 20 services, each with its own data. The ops,
finance, and growth teams need a **single place** to answer
questions like "how many trips today", "what is the GMV this
month", "which restaurants are churning". Without a centralized
reporting service:

- Every team would build its own dashboards (duplication, drift).
- Exports would be a manual SQL exercise.
- Reconciliation would be a manual audit.

`reporting-service` centralizes this:

- Materialized read models derived from domain events.
- Dashboard APIs with per-role scopes.
- Export jobs (CSV / Parquet) on a schedule.
- Reconciliation jobs (drift detection).

This service exists so that **the state of the platform is a
single, queryable, exportable read model** — and so that drift
between services is detected and surfaced within hours, not weeks.

## 3. Objectives

| ID | Objective | Metric |
|----|-----------|--------|
| BR--001 | Reach 99.9% availability on the read path so dashboards always load. | Availability SLO; P99 read latency < 1s. |
| BR--002 | Materialize every domain event into the relevant read model within 5 minutes. | View lag. |
| BR--003 | Support per-tenant isolation. | Read scoping. |
| BR--004 | Run scheduled export jobs. | Export success rate. |
| BR--005 | Detect and report drift between services within 24 hours. | Reconciliation latency. |

## 4. Stakeholders

| Stakeholder | Role | Interest |
|-------------|------|----------|
| Operations | primary user | Operational dashboards |
| Finance | primary user | Revenue, GMV, refunds |
| Growth | primary user | Funnels, conversion |
| BI / Data | primary user | Exports, schemas |
| Engineering (consumers) | consumer | Drift alerts |

## 5. Actors / Personas

- **Operator (admin)** — opens a dashboard, drills down, runs an
  export.
- **BI analyst** — runs an export, loads it into the BI tool.
- **Reconciliation job** — detects drift, emits an event.

## 6. Business Capabilities

- Materialized read models (per entity: trips, orders, payments,
  etc.).
- Dashboard APIs (per role / per scope).
- Export jobs (CSV / Parquet) to S3.
- Reconciliation jobs (drift detection).
- Per-tenant isolation.
- Read access logging.

## 7. Business Requirements

| ID | Requirement | Priority | Source |
|----|-------------|----------|--------|
| BR--010 | The service MUST consume every domain event and update the relevant read model. | MUST | Operations |
| BR--011 | The service MUST expose dashboard APIs per role. | MUST | Operations |
| BR--012 | The service MUST support scheduled export jobs. | MUST | BI |
| BR--013 | The service MUST support per-tenant isolation. | MUST | Compliance |
| BR--014 | The service MUST run reconciliation jobs and emit `reconciliation.drift.found.v1`. | MUST | Operations |
| BR--015 | The service MUST support ad-hoc exports. | MUST | BI |
| BR--016 | The service MUST support Parquet and CSV exports. | MUST | BI |
| BR--017 | The service MUST keep the read model history for at least 2 years. | MUST | Compliance |
| BR--018 | The service MUST support a "preview" mode for exports (top 100 rows). | SHOULD | BI |
| BR--019 | The service MUST support per-export scope (e.g. `reporting.export.revenue`). | MUST | Security |
| BR--020 | The service MUST log every read access for compliance. | MUST | Compliance |

## 8. Business Rules

| ID | Rule | Notes |
|----|------|-------|
| BR--030 | A read model is the result of applying a projection function to a stream of events. | Standard. |
| BR--031 | Reconciliation compares the read model against the source service's state via API. | Standard. |
| BR--032 | Drift findings open a `support.ticket` and emit `reconciliation.drift.found.v1`. | Standard. |
| BR--033 | A read model is recomputable from the event stream (idempotent projection). | Standard. |

## 9. Assumptions

- The number of read models is bounded at < 100.
- The number of events per day is bounded at < 100M.
- The reconciliation cadence is daily.

## 10. Constraints

- The service must not write to other services' databases.
- The service must be hot-reloadable (a config change is live in 5
  seconds).

## 11. Dependencies

| Dependency | Type | Notes |
|------------|------|-------|
| Every service (event) | producer | source of events |
| Every service (read) | target | for reconciliation |
| PostgreSQL 18 | database | Per-service schema `reporting` |
| Redis | cache | Dashboard cache |
| Kafka | broker | source of events |
| AWS S3 | storage | exports |
| HashiCorp Vault | secrets | DB credentials |

## 12. Business Workflows

- Consume an event and project (workflow 1).
- Run an export (workflow 2).
- Run a reconciliation (workflow 3).

## 13. Exception Workflows

- **Projection failure** — DLQ; alert.
- **Export failure** — retry; alert.
- **Drift found** — `support.ticket` + event.

## 14. Success Criteria

- 99.9% read availability.
- View lag < 5 minutes in steady state.
- Export success rate 100%.
- Drift detection latency < 24 hours.

## 15. KPIs

| KPI | Target | Measurement |
|-----|--------|-------------|
| Read availability | 99.9% | Synthetic probes |
| P99 read latency | 1s | RED metrics |
| View lag | < 5 min | Kafka consumer lag |
| Export success rate | 100% | job history |
| Drift detection latency | < 24h | job history |

## 16. Acceptance Criteria

- A dashboard loads in < 1s P99.
- A read model reflects the source event within 5 minutes.
- An export runs on schedule and lands in S3.
- A drift is detected within 24 hours and a ticket is opened.
- Per-tenant isolation is enforced.

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

