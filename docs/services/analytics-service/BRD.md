# Analytics Service — Business Requirements Document

## 1. Document Purpose

Read by the data / BI team, the data engineering team, the security
team, and the analytics-service engineering team. It informs the
design of the event ingestion pipeline, the schema evolution model,
the PII handling, the data lake landing, and the OLAP load.

## 2. Business Context

The platform produces millions of events per day. The data / BI
team needs **a single, queryable, evolvable schema for every
event** — so that analysts can build dashboards, run A/B tests, and
compute metrics without needing to read code.

Without a centralized analytics service:

- Every team would build its own pipeline (duplication, drift).
- Schema changes would break downstream consumers silently.
- PII handling would be inconsistent (compliance risk).

`analytics-service` centralizes this:

- A single Kafka consumer for every event.
- A schema registry with evolution rules.
- PII handling (tokenization, hashing, masking).
- A data lake (Parquet on S3) and an OLAP warehouse load.

This service exists so that **the data lake is a single, evolvable,
PII-safe source of truth** — and so that the BI team can answer
"how many X happened" in seconds.

## 3. Objectives

| ID | Objective | Metric |
|----|-----------|--------|
| BR--001 | Reach 99.5% availability on the consumer path. | Availability SLO. |
| BR--002 | Lag < 5 minutes in steady state. | Consumer lag. |
| BR--003 | Apply schema evolution rules automatically. | Backward compat. |
| BR--004 | Handle PII consistently. | PII handling coverage. |
| BR--005 | Land in the data lake within 5 minutes of production. | Lag. |
| BR--006 | Load to the OLAP warehouse within 1 hour. | Load lag. |

## 4. Stakeholders

| Stakeholder | Role | Interest |
|-------------|------|----------|
| Data / BI team | primary user | Dashboards, A/B tests |
| Data engineering | primary user | Schema, replay |
| Security | secondary user | PII handling |
| Product | consumer | Metrics |

## 5. Actors / Personas

- **Data / BI analyst** — reads the warehouse.
- **Data engineer** — manages schemas, runs replays.
- **Security on-call** — verifies PII handling.

## 6. Business Capabilities

- Kafka consumer for every event.
- Schema registry integration.
- Schema evolution (forward / backward compatibility).
- PII handling (tokenization, hashing, masking).
- Data lake landing (Parquet on S3).
- OLAP warehouse load.
- Replay (backfill).
- Lag and throughput metrics.

## 7. Business Requirements

| ID | Requirement | Priority | Source |
|----|-------------|----------|--------|
| BR--010 | The service MUST consume every domain event. | MUST | BI |
| BR--011 | The service MUST apply schema evolution rules. | MUST | Data |
| BR--012 | The service MUST handle PII consistently. | MUST | Security |
| BR--013 | The service MUST land in the data lake within 5 minutes. | MUST | BI |
| BR--014 | The service MUST load to the OLAP warehouse within 1 hour. | MUST | BI |
| BR--015 | The service MUST support replay (backfill). | MUST | Data |
| BR--016 | The service MUST track consumer lag. | MUST | Operations |
| BR--017 | The service MUST support a "schema registry" integration. | MUST | Data |
| BR--018 | The service MUST support per-tenant PII handling. | MUST | Security |
| BR--019 | The service MUST support a "dry-run" mode for replays. | SHOULD | Data |
| BR--020 | The service MUST support a "schema compatibility check" endpoint. | MUST | Data |

## 8. Business Rules

| ID | Rule | Notes |
|----|------|-------|
| BR--030 | A schema change is forward-compatible if a new field has a default. | Standard. |
| BR--031 | A schema change is backward-compatible if a removed field is unused. | Standard. |
| BR--032 | PII is tokenized (HMAC-SHA256) before landing; the salt is in Vault. | Standard. |
| BR--033 | The data lake is partitioned by date. | Standard. |
| BR--034 | The OLAP warehouse is loaded from the lake (not directly from Kafka). | Standard. |

## 9. Assumptions

- The number of events per day is bounded at < 100M.
- The schema registry is Confluent / Karapace.
- The data lake is S3 (Parquet).
- The OLAP warehouse is Snowflake / BigQuery / Redshift.

## 10. Constraints

- The service must not write raw PII to the data lake.
- The service must be hot-reloadable (a config change is live in 5
  seconds).
- The service must be replayable (idempotent landing).

## 11. Dependencies

| Dependency | Type | Notes |
|------------|------|-------|
| Every service (event) | producer | source of events |
| Schema registry | provider | Confluent / Karapace |
| AWS S3 | storage | data lake |
| OLAP warehouse | sink | Snowflake / BigQuery / Redshift |
| HashiCorp Vault | secrets | DB, warehouse, salt |
| PostgreSQL 18 | database | Per-service schema `analytics` (control plane) |
| Kafka | broker | source of events |

## 12. Business Workflows

- Consume an event (workflow 1).
- Replay a topic (workflow 2).
- Schema evolution (workflow 3).

## 13. Exception Workflows

- **Poison event** — DLQ; alert.
- **Schema incompatibility** — reject; alert.
- **Lake write failure** — retry; alert.
- **OLAP load failure** — retry; alert.

## 14. Success Criteria

- 99.5% consumer availability.
- Lag < 5 minutes in steady state.
- 100% of PII is tokenized.
- 100% of schema changes are validated.

## 15. KPIs

| KPI | Target | Measurement |
|-----|--------|-------------|
| Consumer availability | 99.5% | Synthetic probes |
| Consumer lag | < 5 min | Kafka consumer lag |
| Lake write success rate | 100% | job history |
| OLAP load lag | < 1h | job history |
| PII handling coverage | 100% | schema check |

## 16. Acceptance Criteria

- An event is consumed and landed in the lake within 5 minutes.
- A schema change is validated before deployment.
- A PII field is tokenized before landing.
- A replay backfills the lake and OLAP correctly.
- The OLAP warehouse is loaded from the lake.

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

