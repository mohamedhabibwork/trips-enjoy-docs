# Configuration Service — Business Requirements Document

## 1. Document Purpose

This BRD is read by product owners, platform operators, the
configuration-service engineering team, and the security/compliance
team. It informs the build/buy decision, the rollout plan, and the
operational runbook. It does not describe the implementation in code
detail (see `SRS.md` and `INTEGRATION.md`).

## 2. Business Context

The platform supports ride-hailing, food delivery, and a shared layer
of configuration that affects every business rule (fares, fees,
cancellation policies, eligibility thresholds, restaurant operating
rules, driver eligibility). Historically, every change required a
redeploy of the consuming service — making policy changes slow,
error-prone, and unauditable. `configuration-service` centralizes this:

- **No redeploy to change a rule.** Operators edit a value; every
  consumer picks it up within seconds.
- **Per-tenant, per-region, per-city, per-merchant, per-restaurant
  overrides.** Configuration is hierarchical with explicit
  precedence.
- **Audit and review.** Every change is versioned, attributed, and
  can be rolled back.
- **Safe rollout.** Changes can be staged to a cohort (region,
  merchant) before global.

## 3. Objectives

| ID | Objective | Metric |
|----|-----------|--------|
| BR--001 | Reach 99.95% availability for the read path so consumers never see a stale value beyond 5 seconds. | Availability SLO; P99 read latency < 200ms. |
| BR--002 | Allow any operator to roll out a new business rule in under 60 seconds from console click to consumer pickup. | Time from `POST /v1/configurations/{key}/versions` to 100% of consumers reloaded. |
| BR--003 | Make every change attributable to an admin identity with a reason. | 100% of writes have `actor_id` and `reason` populated. |
| BR--004 | Allow safe rollback to any prior version in one click. | Rollback operation completes in < 5 seconds. |
| BR--005 | Support hierarchical overrides without code changes. | Add a new scope level without service deploy. |

## 4. Stakeholders

| Stakeholder | Role | Interest |
|-------------|------|----------|
| Head of Operations | owner | Can change policy in minutes, not weeks |
| Regional GM | operator | Per-city / per-country overrides |
| Finance | consumer | Reproducible historical quotes for audits |
| Compliance | auditor | Full change history with reason and identity |
| Engineering (consumers) | consumer | Stable API; safe rollouts |
| Security | auditor | Signed mutations; immutable history |

## 5. Actors / Personas

- **Operator (admin)** — opens the admin console, edits a value,
  chooses the scope, sets the reason, and saves. Sees a preview of
  which services will reload.
- **Mobile / web client** — at app launch, downloads its filtered
  subset of configuration; on `configuration.updated.v1`, refreshes.
- **Internal service** — at startup, loads its known keys; subscribes
  to the long-poll stream; on a new version, invalidates the in-memory
  cache.
- **Auditor** — searches the history view by key, scope, actor, or
  time window; sees every version with reason and diff.

## 6. Business Capabilities

- Hierarchical configuration with explicit precedence.
- Versioning with one-click rollback.
- Long-poll and event push delivery.
- Per-channel filtered subset for mobile / web clients.
- Historical snapshots exported for audit.
- Type-safe client SDK that fails to start on misconfiguration.
- Read-your-writes: a service that captures a "configuration
  snapshot" can prove which version of each key it used.

## 7. Business Requirements

| ID | Requirement | Priority | Source |
|----|-------------|----------|--------|
| BR--010 | Operators MUST be able to create a new version of any key without code change. | MUST | Operations |
| BR--011 | A change MUST propagate to all consumers within 5 seconds under steady-state load. | MUST | Operations |
| BR--012 | Every change MUST be attributed to an admin identity and carry a reason. | MUST | Compliance |
| BR--013 | Rollback to a prior version MUST be a single click and complete in < 5 seconds. | MUST | Operations |
| BR--014 | Configuration lookup MUST follow the documented precedence order; the matched scope MUST be returned alongside the value. | MUST | Engineering |
| BR--015 | Per-channel client subsets MUST be computed server-side; the client MUST NOT see keys it does not need. | MUST | Security |
| BR--016 | The service MUST keep every version of every key for at least 7 years. | MUST | Finance / Compliance |
| BR--017 | The service MUST support schema-validated writes (each key has a declared type). | MUST | Engineering |
| BR--018 | A consumer's typed client MUST fail to start on type mismatch with the server-side value. | MUST | Engineering |
| BR--019 | The service MUST export daily snapshots to S3 for offline audit. | SHOULD | Compliance |
| BR--020 | The service MUST provide a "preview impact" view in the admin console listing which services will reload. | SHOULD | Operations |
| BR--021 | The service MUST support staged rollouts (a change applies first to a cohort of regions/merchants). | SHOULD | Operations |
| BR--022 | The service MUST support time-windowed overrides (a key resolves differently during a date range). | SHOULD | Operations |

## 8. Business Rules

| ID | Rule | Notes |
|----|------|-------|
| BR--030 | A new version is committed atomically; partial writes are not allowed. | Tx wraps `documents` + `history` + outbox. |
| BR--031 | The matched scope is returned in every read response so the consumer can audit which rule applied. | Field `matched_scope_type` + `matched_scope_id`. |
| BR--032 | A rollback creates a new version that mirrors the chosen prior version; history is never rewritten. | Append-only semantics. |
| BR--033 | A `tenant_id` is required for any value that affects cross-tenant behavior; missing `tenant_id` ⇒ 400. | Tenant isolation. |
| BR--034 | Schema-validated values that fail validation return 422 with a `code: "VALIDATION_FAILED"`. | JSON Schema per key. |
| BR--035 | A delete is implemented as a "deactivation" version with `value = null`; consumers MUST treat null as missing. | Soft delete. |
| BR--036 | A read in long-poll mode MUST close after `LONGPOLL_MAX_WAIT_SECONDS` even if no change happened. | Avoid idle socket accumulation. |

## 9. Assumptions

- The number of distinct keys is bounded at < 100,000; this allows a
  hot cache of the full key set in Redis.
- The typical change rate is 100s/day, not 1000s/sec.
- All consumer services can re-validate types at startup; they do not
  silently fall back to defaults.
- The platform has a single primary region per environment, with
  cross-region read replicas for DR.

## 10. Constraints

- All configuration must be auditable for at least 7 years.
- Configuration values that include merchant copy or PII must be
  treated as `confidential`.
- The service cannot depend on any other service to start (boot order
  is non-existent).
- No environment variables may carry runtime business values (only
  build-time values).

## 11. Dependencies

| Dependency | Type | Notes |
|------------|------|-------|
| `identity-service` | service | Admin token validation |
| Keycloak | provider | JWKS source |
| PostgreSQL 18 | database | Per-service schema `configuration` |
| Redis | cache | Read cache; hot key set |
| Kafka | broker | Publishes `configuration.updated.v1` |
| HashiCorp Vault | secrets | DB credentials, signing key |
| AWS S3 | storage | Daily snapshot export |

## 12. Business Workflows

- **Operator creates a new version of a key** (see `WORKFLOWS.md`,
  workflow 1).
- **Operator rolls back to a prior version** (workflow 2).
- **Operator stages a rollout to a cohort** (workflow 3).
- **Internal service reloads on update** (workflow 4).
- **Mobile client downloads a filtered subset** (workflow 5).

## 13. Exception Workflows

- **Validation failure on write** — `422 VALIDATION_FAILED` with
  field-level details; nothing is committed.
- **Type mismatch in consumer at startup** — service refuses to start;
  operator must revert the change.
- **Long-poll connection storm** — bounded by `LONGPOLL_MAX_CONNS_PER_POD`;
  excess clients receive 503 with `Retry-After`.
- **Read cache poisoned** — bypass the cache via `?nocache=1`; an
  alert fires when cache hit rate drops below 80%.

## 14. Success Criteria

- 99.95% read-path availability over a 30-day window.
- P99 read latency < 200ms in steady state.
- Median change-to-consumer-pickup < 2 seconds.
- 100% of writes are attributed to an actor and carry a reason.
- Zero untracked configuration changes in production for 90 consecutive
  days.

## 15. KPIs

| KPI | Target | Measurement |
|-----|--------|-------------|
| Read availability | 99.95% | Synthetic probes per region |
| P99 read latency | 200ms | RED metrics |
| Median propagation latency | 2s | Time from `POST /versions` to 100% consumers reloaded |
| Cache hit rate | ≥ 80% | Redis hit ratio |
| Write attribution coverage | 100% | Audit log completeness check |
| Rollback completion | < 5s | Operator console timer |
| Daily snapshot delivery | 100% | S3 object count vs schedule |

## 16. Acceptance Criteria

- A new key can be created and committed in under 5 seconds; the new
  value is picked up by all consumers within 5 seconds.
- Rollback to a prior version restores the prior value across all
  consumers within 5 seconds.
- An admin can preview the impact of a change (which services will
  reload) before committing.
- Every version in history is retrievable by version number.
- The service starts even when its cache layer is unavailable
  (degraded mode with direct DB reads).
- The service rejects a write whose value does not match the key's
  declared schema with a 422 envelope.

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

