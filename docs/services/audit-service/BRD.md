# Audit Service — Business Requirements Document

## 1. Document Purpose

Read by the security team, the compliance team, the legal team, and
the audit-service engineering team. It informs the design of the
immutable log, the cryptographic hash chain, the retention policy,
and the search RBAC.

## 2. Business Context

The platform operates in many jurisdictions, each with its own
audit, financial, and privacy regulations. The platform must:

- Persist every audit-relevant event for at least 7 years
  (financial) / 1 year (the rest).
- Provide a tamper-evident log (cryptographic hash chain).
- Provide a strict-RBAC search API for compliance and security.
- Allow offline export for external auditors.

This service exists so that **the audit log is a single, immutable,
tamper-evident source of truth** — and so that "who did what and
when" can be answered in seconds.

## 3. Objectives

| ID | Objective | Metric |
|----|-----------|--------|
| BR--001 | Persist every audit-relevant event within 5 seconds of production. | Consumer lag. |
| BR--002 | Maintain a cryptographic hash chain so any tampering is detectable. | Hash chain integrity. |
| BR--003 | Reject UPDATE / DELETE on the audit schema at the database grant level. | Immutability. |
| BR--004 | Provide a strict-RBAC search API. | Search latency < 1s. |
| BR--005 | Retain financial events for 7 years, others for 1 year. | Retention enforcement. |
| BR--006 | Export the audit log to S3 daily. | Export success rate. |

## 4. Stakeholders

| Stakeholder | Role | Interest |
|-------------|------|----------|
| Security | primary user | Investigate incidents |
| Compliance | primary user | Audit, regulatory |
| Legal | secondary user | Litigation hold |
| Engineering (consumers) | consumer | Read access for incident response |
| External auditor | secondary user | Offline export |

## 5. Actors / Personas

- **Security on-call** — searches the log during an incident.
- **Compliance auditor** — searches the log for a regulatory
  review.
- **External auditor** — receives a daily export.
- **Reconciliation job** — verifies the log against other services.

## 6. Business Capabilities

- Subscribe to every audit-relevant topic.
- Persist events in an append-only table.
- Cryptographic hash chain.
- Strict-RBAC search API.
- Retention policy enforcement.
- Daily export to S3.
- Read access logging (every read is itself audited).

## 7. Business Requirements

| ID | Requirement | Priority | Source |
|----|-------------|----------|--------|
| BR--010 | The service MUST subscribe to every `*.audit.*` topic. | MUST | Security |
| BR--011 | The service MUST persist every event in an append-only table. | MUST | Security |
| BR--012 | The service MUST maintain a cryptographic hash chain. | MUST | Security |
| BR--013 | The service MUST reject UPDATE / DELETE on the audit schema. | MUST | Security |
| BR--014 | The service MUST expose a strict-RBAC search API. | MUST | Compliance |
| BR--015 | The service MUST retain financial events for 7 years, others for 1 year. | MUST | Compliance |
| BR--016 | The service MUST export the audit log to S3 daily. | MUST | Compliance |
| BR--017 | The service MUST log every read access. | MUST | Compliance |
| BR--018 | The service MUST support a "litigation hold" flag that overrides retention. | MUST | Legal |
| BR--019 | The service MUST support per-tenant isolation. | MUST | Compliance |
| BR--020 | The service MUST support a "verify hash chain" endpoint. | MUST | Security |
| BR--021 | The service MUST support a "verify event" endpoint (event id + hash). | MUST | Security |
| BR--022 | The service MUST emit operational metrics on consumer lag. | MUST | Operations |

## 8. Business Rules

| ID | Rule | Notes |
|----|------|-------|
| BR--030 | Every event is persisted in the same DB transaction that updates the hash chain. | Atomicity. |
| BR--031 | The hash chain is a sequence of `hash = sha256(prev_hash || canonical(event))`. | Standard. |
| BR--032 | Every read access is logged in `audit.read_log` with `actor_id` and `query`. | Audit the auditors. |
| BR--033 | A litigation hold overrides the retention policy for the affected events. | Legal. |
| BR--034 | UPDATE / DELETE is rejected at the database grant level. | Standard. |

## 9. Assumptions

- The number of events per day is bounded at < 100M.
- The retention is enforced by a daily purge job.

## 10. Constraints

- The service must not allow UPDATE / DELETE on the audit schema.
- The service must be hot-reloadable (a configuration change is
  live in 5 seconds).
- The service must not silently drop events; every event is either
  persisted or routed to DLQ.

## 11. Dependencies

| Dependency | Type | Notes |
|------------|------|-------|
| Every service (event) | producer | audit-relevant events |
| PostgreSQL 19 | database | Per-service schema `audit` |
| Kafka | broker | source of events |
| AWS S3 | storage | daily export |
| HashiCorp Vault | secrets | DB credentials |

## 12. Business Workflows

- Consume an event (workflow 1).
- Search the log (workflow 2).
- Verify the hash chain (workflow 3).
- Export to S3 (workflow 4).

## 13. Exception Workflows

- **Poison event** — DLQ; alert.
- **DB unavailable** — DLQ; alert.
- **Read with insufficient role** — 403.
- **Retention purge** — daily job; events with a litigation hold
  are skipped.

## 14. Success Criteria

- Consumer lag < 5 seconds in steady state.
- Hash chain integrity 100% (verified daily).
- Read access logged 100%.
- Export success rate 100%.

## 15. KPIs

| KPI | Target | Measurement |
|-----|--------|-------------|
| Consumer lag | < 5s | Kafka consumer lag |
| Hash chain integrity | 100% | daily verification job |
| Read access coverage | 100% | audit log |
| Export success rate | 100% | S3 object count |

## 16. Acceptance Criteria

- Every audit-relevant event is persisted within 5 seconds.
- The hash chain is verifiable.
- UPDATE / DELETE is rejected at the DB level.
- A read with insufficient role is rejected.
- A litigation hold overrides retention.

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

