# Admin Service — Business Requirements Document

## 1. Document Purpose

Read by the operations team, the security team, the SRE team, the
platform product team, and the admin-service engineering team. It
informs the design of the action gateway, the RBAC model, the
break-glass mechanism, and the audit log.

## 2. Business Context

The platform operates in many jurisdictions, with many services, and
many high-value mutations (payouts, refunds, configuration changes,
feature-flag toggles, account suspensions). Without a central admin
service:

- Every team would build its own admin UI, with its own security
  model.
- There would be no unified audit trail.
- A "super admin" action would be hard to co-sign.

`admin-service` centralizes all of this:

- One web UI for operators.
- One RBAC model (delegated to Keycloak; mapped to actions here).
- One request-signing model.
- One break-glass flow.
- One immutable `admin.action.performed.v1` stream that the
  `audit-service` consumes.

This service exists so that **every high-value mutation is
attributed, signed, and audited** — and so that the security team
can answer "who changed X and why?" in seconds.

## 3. Objectives

| ID | Objective | Metric |
|----|-----------|--------|
| BR--001 | Reach 99.95% availability on the action dispatch path so operators are not blocked. | Availability SLO; P99 dispatch latency < 1s. |
| BR--002 | Make every action attributable to an admin with a reason. | 100% write attribution. |
| BR--003 | Enforce request signing for high-value actions. | 100% coverage. |
| BR--004 | Support break-glass with a second admin's co-signature. | 100% of super-admin actions. |
| BR--005 | Provide a unified search over the action log. | Searchable by actor / service / target / time. |

## 4. Stakeholders

| Stakeholder | Role | Interest |
|-------------|------|----------|
| Operations | primary user | Perform actions |
| Security | secondary user | Audit, break-glass |
| SRE | secondary user | Operate the platform |
| Compliance | auditor | Search the action log |
| Engineering (consumers) | consumer | Action API |

## 5. Actors / Personas

- **Operator (admin)** — opens the console, performs an action,
  provides a reason.
- **Super admin** — same as operator, but for high-value / off-hours
  actions; requires break-glass.
- **Security on-call** — paged for break-glass; can co-sign.
- **Compliance auditor** — searches the action log.
- **Target service** — receives the action via REST.

## 6. Business Capabilities

- Unified action API.
- Web UI for operators.
- RBAC scopes (per service / per action).
- Request signing.
- `X-Audit-Reason` enforcement.
- Break-glass co-signature.
- Action log (searchable).
- Time-of-day restriction (super admin off-hours).
- IP allowlist (super admin).
- Step-up MFA (super admin).

## 7. Business Requirements

| ID | Requirement | Priority | Source |
|----|-------------|----------|--------|
| BR--010 | The service MUST provide a unified action API across all services. | MUST | Operations |
| BR--011 | The service MUST enforce RBAC + per-action scopes. | MUST | Security |
| BR--012 | The service MUST enforce request signing for high-value actions. | MUST | Security |
| BR--013 | The service MUST enforce `X-Audit-Reason` on every mutation. | MUST | Compliance |
| BR--014 | The service MUST support break-glass with a second admin's co-signature. | MUST | Security |
| BR--015 | The service MUST emit `admin.action.performed.v1` for every action. | MUST | Audit |
| BR--016 | The service MUST provide a searchable action log. | MUST | Compliance |
| BR--017 | The service MUST enforce time-of-day restriction for super-admin off-hours. | MUST | Security |
| BR--018 | The service MUST enforce IP allowlist for super-admin. | MUST | Security |
| BR--019 | The service MUST require step-up MFA for super-admin. | MUST | Security |
| BR--020 | The service MUST support per-tenant RBAC. | MUST | Operations |
| BR--021 | The service MUST support a "reason" prompt in the UI for every action. | MUST | Compliance |
| BR--022 | The service MUST support a "preview impact" view for actions that affect multiple services. | SHOULD | Operations |
| BR--023 | The service MUST keep the action log for at least 7 years. | MUST | Compliance |
| BR--024 | The service MUST provide CRUD endpoints for per-location and OD-pair pricing geo-config records at `/v1/admin/pricing/geo-config[...]`; only the `pricing.admin` role may create / update / disable / rollback. | MUST | Operations |
| BR--025 | The service MUST validate that every origin and destination `zone_id` in an OD-pair record exists in ``geolocation-service` (zones)` (call `POST /v1/zones/exists` per side) before persisting. | MUST | Pricing |
| BR--026 | The service MUST emit `pricing.geo_config.updated.v1` on every create / update / disable / rollback; partition key `geo_config_id`. | MUST | Pricing |
| BR--027 | The service MUST require break-glass for a `pricing.geo_config` rollback (creating a new history row pointing at a prior version). | MUST | Security |
| BR--028 | The service MUST refuse ambiguous priority / scope combinations (equal-priority records that would create a tie at quote time); the rejection message names the conflicting record. | MUST | Pricing |
| BR--029 | The service MUST expose a `SUPER_ADMIN` permission preset that bundles `platform.super_admin` + the 58 `<service>.admin` scopes (one per service in `docs/services/`, including `api_gateway.admin`). The preset is enumerable at `GET /v1/admin/presets` and its membership is declared per-service in each service's `TECH.md` 10.7. | MUST | Operations |
| BR--030 | Granting the `SUPER_ADMIN` preset (`POST /v1/admin/identity/grant-super-admin`) MUST require: a valid `platform.super_admin` JWT, `X-Audit-Reason` ≥ 8 chars, HMAC `X-Signature`, `X-Break-Glass-Cosigner` (a different admin with `platform.super_admin`), step-up MFA, and the caller's IP on the super-admin IP allowlist. Off-hours (outside `TIME_OF_DAY_RESTRICTION`) the co-signer is mandatory. | MUST | Security |
| BR--031 | Revoking the `SUPER_ADMIN` preset (`DELETE /v1/admin/identity/revoke-super-admin`) MUST require the same gates as BR--030. | MUST | Security |
| BR--032 | Every grant and every revoke MUST write to `admin.super_admin_grant` (append-only) and emit `admin.super_admin.granted.v1` (or `admin.super_admin.revoked.v1`); on partial fan-out failure the service MUST perform compensating revokes for the roles that did succeed and emit a compensating event with the same `source_request_id`. security on-call MUST be paged on every grant and revoke. | MUST | Audit / Security |
| BR--033 | The service MUST expose a service catalog at `GET /v1/admin/services` listing all 20 services with their accepted admin scopes (per each service's `TECH.md` 10.1) and `SUPER_ADMIN` preset membership (per 10.7). The catalog is the source of truth for what the preset grants. | MUST | Operations |

## 8. Business Rules

| ID | Rule | Notes |
|----|------|-------|
| BR--030 | Every action MUST be attributed to an admin. | `actor_id`. |
| BR--031 | Every action MUST carry a reason. | `X-Audit-Reason`. |
| BR--032 | High-value actions MUST be signed. | HMAC-SHA256. |
| BR--033 | Super-admin actions MUST be co-signed. | Break-glass. |
| BR--034 | Off-hours super-admin actions MUST be co-signed. | Time-of-day. |
| BR--035 | The action log is append-only. | No UPDATE / DELETE. |
| BR--036 | Granting or revoking `platform.super_admin` (i.e. touching the `SUPER_ADMIN` preset) is itself a super-admin action and inherits all the gates from BR--030 / BR--031 — co-signature is never optional. | Security |
| BR--037 | The `GET /v1/admin/services` catalog is the source of truth for the `SUPER_ADMIN` preset's role list. If a service is added to the platform, its `<service>.admin` scope MUST be added to the preset and a migration MUST update the catalog atomically before the new service ships. | Operations |

## 9. Assumptions

- The number of admin users is bounded at < 1,000.
- The number of actions per day is bounded at < 100,000.

## 10. Constraints

- The service must not allow direct database access.
- The service must not allow bypass of the request signing model.
- The service must not allow anonymous actions.

## 11. Dependencies

| Dependency | Type | Notes |
|------------|------|-------|
| `identity-service` | service | Token validation |
| Every other service | service (target) | Receives the action |
| Keycloak | provider | Admin realm |
| PostgreSQL 19 | database | Per-service schema `admin` |
| Redis | cache | Permission cache |
| Kafka | broker | Publishes `admin.action.performed.v1` |
| HashiCorp Vault | secrets | DB credentials, signing keys |

## 12. Business Workflows

- Operator performs an action (workflow 1).
- Super admin performs a break-glass action (workflow 2).
- Compliance audits the action log (workflow 3).

## 13. Exception Workflows

- **Missing reason** — 400 `AUDIT_REASON_REQUIRED`.
- **Invalid signature** — 403 `SIGNATURE_INVALID`.
- **Break-glass required** — 403 `BREAK_GLASS_REQUIRED`.
- **Off-hours super-admin without break-glass** — 403.

## 14. Success Criteria

- 99.95% action dispatch availability.
- 100% of actions attributed to a user with a reason.
- 100% of high-value actions signed.
- 100% of super-admin off-hours actions co-signed.

## 15. KPIs

| KPI | Target | Measurement |
|-----|--------|-------------|
| Action dispatch availability | 99.95% | Synthetic probes |
| P99 dispatch latency | 1s | RED metrics |
| Write attribution coverage | 100% | Audit completeness |
| Signature coverage | 100% | Security audit |
| Break-glass coverage | 100% | Security audit |

## 16. Acceptance Criteria

- An operator can perform any action through the console.
- A high-value action requires a signed request.
- A super-admin off-hours action requires a co-signature.
- Every action is recorded in the action log.
- The action log is searchable by actor / service / target / time.
- **Geo-config (BR--024..BR--028):** every CRUD on
  `/v1/admin/pricing/geo-config[...]` succeeds only with the
  `pricing.admin` scope and emits exactly one
  `pricing.geo_config.updated.v1` (partition key `geo_config_id`)
  per action. A rollback emits the event AND records a new row in
  `pricing.rule_bindings_history` (never UPDATE/DELETE on the
  binding — mirrors the reversal rule from the accounting
  four-layer truth model).

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

