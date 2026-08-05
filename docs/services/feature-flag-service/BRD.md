# Feature Flag Service — Business Requirements Document

## 1. Document Purpose

Read by product managers, the experimentation team, the
feature-flag-service engineering team, and the security team. It
informs the design of flag semantics (release / operational /
experiment / permission), the rollout mechanism, and the audit
trail. Implementation details are in `SRS.md` and `INTEGRATION.md`.

## 2. Business Context

The platform runs 50+ services. Without a centralized flag system,
every team would reinvent its own — leading to inconsistent
semantics, untracked kill switches, and unsafe production rollouts.
`feature-flag-service` centralizes:

- **Release flags** — long-lived, on for most users
  (e.g. `new_pricing_v2`).
- **Operational flags** — kill switches
  (e.g. `disable_cash_payments`).
- **Experiment flags** — A/B tests, paired with a metric.
- **Permission flags** — gated features for partner-only access.

The service exists to give product and engineering a single, audited
mechanism for changing application behavior in production without a
redeploy.

## 3. Objectives

| ID | Objective | Metric |
|----|-----------|--------|
| BR--001 | Reach 99.95% availability on the evaluation path so flags never block a request. | Availability SLO; P99 evaluation latency < 30ms (cached) / < 200ms (uncached). |
| BR--002 | Allow any product manager to roll out a flag to 10% of users in under 60 seconds. | Time from rule change to consumer pickup. |
| BR--003 | Make every change attributable to a user with a reason. | 100% write attribution. |
| BR--004 | Provide sticky variant assignment for A/B tests so a user always sees the same variant. | Determinism across requests. |
| BR--005 | Allow kill switches to propagate within 5 seconds. | Median propagation latency. |

## 4. Stakeholders

| Stakeholder | Role | Interest |
|-------------|------|----------|
| Product Manager | owner | Can release a feature to 10% in 60s |
| Engineering lead | consumer | Stable SDK; safe defaults |
| Data / Experimentation | operator | Sticky assignments, clean variants |
| Security | auditor | Full audit log; signed kill switches |
| Support | consumer | Read flag values for incident diagnosis |

## 5. Actors / Personas

- **Operator (admin)** — opens the admin console, creates a flag,
  defines rules, sets the reason.
- **Experiment owner** — a data analyst who creates experiment flags
  with a metric and a duration.
- **Internal service** — calls the evaluation API on every request
  path that depends on a flag.
- **Mobile / web client** — downloads its filtered flag subset on
  launch; the SDK caches locally.
- **Auditor** — reads the history of a flag.

## 6. Business Capabilities

- Boolean and multivariate flag types.
- Rules matching on user id, segment, region, country, app version,
  custom attribute.
- Percentage rollouts with consistent hashing.
- Time-windowed rules (active during a date range).
- Long-poll and event push delivery.
- Per-channel filtered subset for edge clients.
- Sticky variant assignment (deterministic on a stable id).
- Audit log of every change.
- One-click rollback to a prior rule set.
- Kill switch (globally disable a flag without redeploy).

## 7. Business Requirements

| ID | Requirement | Priority | Source |
|----|-------------|----------|--------|
| BR--010 | Operators MUST be able to create a flag and its rules without code change. | MUST | Engineering |
| BR--011 | A change MUST propagate to all consumers within 5 seconds. | MUST | Operations |
| BR--012 | Every change MUST be attributed to a user and carry a reason. | MUST | Compliance |
| BR--013 | Percentage rollouts MUST be sticky on a stable id (same id → same variant). | MUST | Experimentation |
| BR--014 | A kill switch MUST take effect globally within 5 seconds. | MUST | Operations |
| BR--015 | Per-channel client subsets MUST be computed server-side; clients MUST NOT see flags they do not need. | MUST | Security |
| BR--016 | The service MUST support experiment flags with a metric and a duration. | MUST | Experimentation |
| BR--017 | The service MUST support one-click rollback to a prior rule set. | SHOULD | Operations |
| BR--018 | The service MUST emit `feature_flag.experiment.started.v1` and `feature_flag.experiment.stopped.v1` for experiment tracking. | MUST | Analytics |
| BR--019 | The service MUST export daily assignment snapshots for analytics. | SHOULD | Experimentation |
| BR--020 | The service MUST keep the full history of every flag for at least 1 year (7 years for financial-impact flags). | MUST | Compliance |
| BR--021 | A consumer SDK MUST fail to start on a type mismatch with the server-side flag type. | MUST | Engineering |
| BR--022 | Operators MUST be able to set up a time-windowed rule (active during a date range). | SHOULD | Operations |
| BR--023 | The service MUST support multivariate flags (more than 2 variants). | MUST | Experimentation |

## 8. Business Rules

| ID | Rule | Notes |
|----|------|-------|
| BR--030 | Rules are evaluated in order; the first match wins. | Documented in admin console. |
| BR--031 | A percentage rollout is hashed with `murmur3(stable_id + flag_key) % 100`; the resulting bucket is mapped to a variant. | Determinism. |
| BR--032 | A kill switch overrides all rules and returns the disabled default. | Highest precedence. |
| BR--033 | A flag without rules returns the default. | Standard. |
| BR--034 | A change to a rule creates a new rule version; the old rule is retained. | History. |
| BR--035 | The matched rule id is returned in every evaluation response for audit. | Audit. |

## 9. Assumptions

- The number of distinct flags is bounded at < 10,000.
- A typical evaluation path must be sub-30ms (cached) — the SDK
  reads from local memory and only calls the server on a miss.
- The platform has a single primary region per environment.

## 10. Constraints

- The service must be deployable without a code change for any new
  flag.
- The service must be hot-reloadable (a flag change must be live in
  5 seconds without a restart).
- The service must not silently fall back to defaults on a server
  error; an explicit `flag_evaluation_error` must be returned so the
  caller can decide.

## 11. Dependencies

| Dependency | Type | Notes |
|------------|------|-------|
| `identity-service` | service | Admin token validation |
| `customer-service` | async (segment changes) | segment-aware evaluation |
| Keycloak | provider | JWKS |
| PostgreSQL 18 | database | Per-service schema `feature_flag` |
| Redis | cache | Evaluation cache |
| Kafka | broker | Publishes `feature_flag.*.v1` |
| HashiCorp Vault | secrets | DB credentials, signing key |
| AWS S3 | storage | Daily assignment snapshot |

## 12. Business Workflows

- Operator creates a new flag (workflow 1).
- Operator rolls out to 10% via a percentage rule (workflow 2).
- Operator triggers a kill switch (workflow 3).
- Operator starts an experiment (workflow 4).
- Consumer service evaluates a flag (workflow 5).

## 13. Exception Workflows

- **Evaluation service down** — consumer SDK returns
  `flag_evaluation_error`; the caller falls back to its default
  branch.
- **Type mismatch at startup** — SDK refuses to start; operator
  must revert the flag type change.
- **Long-poll connection storm** — bounded; excess clients get
  503 with `Retry-After`.

## 14. Success Criteria

- 99.95% evaluation availability in steady state.
- P99 evaluation latency < 30ms (cache hit) / 200ms (cache miss).
- 100% of writes attributed to a user with a reason.
- A kill switch is effective globally within 5 seconds.
- A user always sees the same variant of an experiment during the
  experiment window.

## 15. KPIs

| KPI | Target | Measurement |
|-----|--------|-------------|
| Evaluation availability | 99.95% | Synthetic probes per region |
| P99 evaluation latency | 30ms cached | RED metrics |
| Median propagation latency | 2s | Event publish to consumer ack |
| Cache hit rate | ≥ 95% | Redis hit ratio |
| Write attribution coverage | 100% | Audit completeness |
| Stickiness accuracy | 100% (same id → same variant) | Reconciliation job |
| Kill switch propagation | < 5s | Operator console timer |

## 16. Acceptance Criteria

- A flag can be created and rolled out to 10% in under 60 seconds.
- A kill switch takes effect within 5 seconds globally.
- A user always sees the same variant of an experiment for the
  duration of the experiment.
- Every rule change is recorded with `actor_id` and `reason`.
- The SDK refuses to start on a type mismatch.
- Per-channel client subsets only contain the keys the channel
  declared.

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

