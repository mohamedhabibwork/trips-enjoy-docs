# Reporting Service — Software Requirements Specification

## 1. Introduction

This SRS specifies the behavior, performance, and operational
requirements of `reporting-service`. It inherits the platform-wide
standards in `docs/architecture/API_STANDARDS.md`,
`docs/architecture/EVENT_ARCHITECTURE.md`, and
`docs/architecture/SECURITY_ARCHITECTURE.md`.

## 2. Scope

In scope:

- Materialized read models.
- Dashboard APIs.
- Export jobs.
- Reconciliation jobs.

Out of scope:

- The OLAP data warehouse.
- Business event production.
- Customer-facing reporting.

## 3. System Context

```mermaid
flowchart LR
    E[Every service] -- events --> K[Kafka]
    K -- consume --> RPT[reporting-service]
    RPT -- read --> DB[(PostgreSQL reporting)]
    RPT -- export --> S3[(S3)]
    RPT -- reconcile --> E
    ADM[admin-service] -- dashboards --> RPT
    RPT -- reconciliation.drift.found.v1 --> K
    SUP["`admin-service` (support module)] -- consume drift --> K
```

## 4. Actors

- Operator (admin) — human.
- BI / Data team — human.
- Reconciliation job — system.
- Every service (event) — system.

## 5. Functional Requirements

| ID | Requirement | Priority |
|----|-------------|----------|
| FR--001 | The service MUST consume every domain event and update the relevant read model. | MUST |
| FR--002 | The service MUST expose `GET /v1/dashboards/{name}` per role. | MUST |
| FR--003 | The service MUST expose `GET /v1/views/{view_name}`. | MUST |
| FR--004 | The service MUST support `POST /v1/exports/{name}/run`. | MUST |
| FR--005 | The service MUST support CSV and Parquet exports. | MUST |
| FR--006 | The service MUST support per-tenant isolation. | MUST |
| FR--007 | The service MUST run reconciliation jobs and emit `reconciliation.drift.found.v1`. | MUST |
| FR--008 | The service MUST support ad-hoc exports. | MUST |
| FR--009 | The service MUST support a "preview" mode (top 100 rows). | SHOULD |
| FR--010 | The service MUST support per-export scope. | MUST |
| FR--011 | The service MUST log every read access. | MUST |
| FR--012 | The service MUST support a "view lag" metric. | MUST |
| FR--013 | The service MUST emit `reporting.export.completed.v1` on export success. | MUST |
| FR--014 | The service MUST emit `reporting.view.refreshed.v1` on read model refresh. | MUST |
| FR--015 | The service MUST route poison events to DLQ. | MUST |
| FR--016 | The service MUST support a "rebuild" command for a view from the event stream. | MUST |
| FR--017 | The service MUST support per-tenant redaction of PII. | MUST |
| FR--018 | The service MUST support an "explain" mode for dashboards (data lineage). | SHOULD |
| FR--019 | The service MUST support per-dashboard TTL (cache invalidation). | MUST |
| FR--020 | The service MUST support a "rate limit" per dashboard consumer. | MUST |

## 6. Non-Functional Requirements

| ID | Category | Requirement | Target |
|----|----------|-------------|--------|
| NFR--001 | performance | P99 read latency | < 1s |
| NFR--002 | performance | P99 export status latency | < 200ms |
| NFR--003 | availability | uptime | 99.9% over 30d |
| NFR--004 | scalability | horizontal scaling | HPA on consumer lag |
| NFR--005 | durability | zero data loss on regional outage | RPO 5m, RTO 30m |
| NFR--006 | observability | 100% requests have trace and log | enforced in CI |
| NFR--007 | freshness | median view lag | < 5min |
| NFR--008 | idempotency | idempotent projection | enforced in code |

## 7. API Requirements

- Versioned URIs.
- Bearer JWT.
- `Idempotency-Key` for non-idempotent writes (export runs).
- Errors in the standard envelope.
- OpenAPI 3.1 at `/openapi.json`.

## 8. Data Requirements

| ID | Requirement | Notes |
|----|-------------|-------|
| DATA--001 | Primary keys UUIDv7 or composite. | |
| DATA--002 | Read models are recomputable. | |
| DATA--003 | Read models partitioned by date. | Retention. |
| DATA--004 | Cross-service references are UUID columns without DB FKs. | Rule |
| DATA--005 | Time is RFC3339 UTC. | |
| DATA--006 | Sub-schemas per read model (`reporting_trips`, `reporting_orders`, …). | |

## 9. Validation Rules

- A query MUST include a `tenant_id` (or derive from the token).
- An export MUST include a `format` (`csv` / `parquet`).

## 10. State Transitions

n/a (read models are append / update only).

## 11. Authorization Requirements

- Per-dashboard scope (e.g. `reporting.dashboard.operations`).
- Per-export scope (e.g. `reporting.export.revenue`).
- Per-tenant isolation.

## 12. Configuration Requirements

- `RECONCILIATION_CRON` (env; default `0 4 * * *`).
- `EXPORT_CRON` (env; per export).
- `VIEW_LAG_THRESHOLD_SECONDS` (env; default 300).

## 13. Error Handling

| Error | Response |
|-------|----------|
| Insufficient scope | 403 `FORBIDDEN` |
| Invalid query | 400 `VALIDATION_FAILED` |
| Export in progress | 409 `EXPORT_IN_PROGRESS` |
| View lag exceeded | 503 `VIEW_LAG_EXCEEDED` |

## 14. Concurrency Requirements

- A projection is idempotent on `event_id` (inbox).
- A read model is updated in a single SQL statement per event.

## 15. Idempotency Requirements

- The consumer uses an inbox on `event_id`; duplicate events are
  no-ops.
- `POST /v1/exports/{name}/run` requires `Idempotency-Key`.

## 16. Performance

- Dominant path: event projection.
- P50/P95/P99 projection: 5ms / 20ms / 100ms.
- Read P99: < 1s.

## 17. Scalability

- Horizontal scaling: HPA on consumer lag.
- Vertical scaling: 2 vCPU / 4 GiB production.

## 18. Availability

- SLO: 99.9% over 30 days.
- Error budget: ~44 minutes per 30 days.
- Maintenance window: Sundays 04:00–06:00 UTC.

## 19. Security

| ID | Requirement | Notes |
|----|-------------|-------|
| SEC--001 | All requests JWT-validated. | Standard |
| SEC--002 | Per-dashboard / per-export scopes. | |
| SEC--003 | Per-tenant isolation. | |
| SEC--004 | PII masked in non-admin reads. | |
| SEC--005 | Read access logged. | |
| SEC--006 | DB user has rights only on the `reporting` schema. | Least privilege. |

## 20. Privacy

- PII stored: as in the source event; masked in non-admin reads.
- Retention: 2 years for read models.
- Erasure: per-service (the read model is recomputed).

## 21. Auditability

- Every export run is logged with `actor_id` and `reason`.
- Every drift finding is logged.

## 22. Observability

- Logs: JSON to stdout; standard fields.
- Metrics:
  - `http_requests_total{route, method, status}` (RED)
  - `http_request_duration_seconds{route, method, status}` (RED)
  - `reporting_view_lag{view_name}`
  - `reporting_export_seconds{name, format}`
  - `reporting_drift_findings_total{view_name}`
  - `reporting_projection_seconds{view_name}`
- Traces: OpenTelemetry.
- Alerts:
  - SLO burn rate.
  - View lag > 5 min.
  - Export failure.
  - Drift finding.

## 23. Maintainability

- Code style: TypeScript ESLint config.
- Test coverage: ≥ 85% on handlers, ≥ 95% on projections.
- Documentation: this folder; OpenAPI 3.1 at `/openapi.json`.

## 24. Disaster Recovery

- RPO: 5 minutes.
- RTO: 30 minutes.
- The read models are derivable from the event stream; recovery is
  from the latest offset.

## 25. Acceptance Criteria

- 99.9% read availability for 30 days in production.
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
- [`../../../main.md`](../../../main.md) — top-level platform specification (architecture, Keycloak, PostgreSQL 19, messaging, observability baseline)

