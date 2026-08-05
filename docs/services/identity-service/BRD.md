# identity-service — Business Requirements Document

## 1. Document Purpose

This BRD is read by the platform's identity team, the security
team, the compliance team, and the SRE on-call. It captures
*why* the `identity-service` exists, the business capabilities
it provides, the business rules it enforces, and the KPIs
against which it is evaluated. It is the input to the SRS, ERD,
and INTEGRATION docs in this folder.

## 2. Business Context

The platform federates authentication to Keycloak. But every
downstream service needs a stable internal identifier
(`identity_id`, a UUIDv7) and a normalized view of the user's
canonical claims. Without a thin adapter, every service would
have to integrate with Keycloak directly, repeat the
mapping from `kc_sub` to its own internal id, and re-implement
suspension propagation. The `identity-service`:

- **Reduces coupling** between business services and Keycloak.
- **Provides a stable internal id** that survives Keycloak
  realm migrations, social-identity linking, and account
  merges.
- **Centralizes suspension / disable / erasure propagation** so
  the platform can react to a single Keycloak event in a
  coordinated way.
- **Reduces compliance risk** by owning the GDPR right-to-erasure
  flow and the audit trail for it.

## 3. Objectives

| ID | Objective | Metric |
|----|-----------|--------|
| BR--001 | Provide a stable internal `identity_id` for every platform user, regardless of Keycloak realm or social-identity link. | 100% of profile services reference `identity_id`, not `kc_sub`. |
| BR--002 | Propagate Keycloak lifecycle events (created, suspended, disabled, reinstated, erased) to every dependent service within 10 seconds. | P99 propagation lag. |
| BR--003 | Provide a low-latency cached-claim read for downstream services. | P99 claim lookup ≤ 30 ms. |
| BR--004 | Implement GDPR right-to-erasure consistently across the platform. | 100% of erasure requests complete within SLA (default 30 days; expedited 24 h). |
| BR--005 | Meet the Tier-1 SLO of 99.95% availability and P99 ≤ 100 ms on the read path. | SLO burn rate < 1x over 30 days. |
| BR--006 | Be the only service authorized to call Keycloak's admin API. | 0 other services with admin API credentials. |
| BR--007 | Maintain a complete audit trail for every state change. | 100% of state changes emit an `identity.*.v1` event consumed by `audit-service`. |

## 4. Stakeholders

| Stakeholder | Role | Interest |
|-------------|------|----------|
| Identity team | owner | correctness, completeness of identity model |
| Security team | approver | MFA, suspension, breach response |
| Compliance | approver | GDPR, PDPL, NDMO, audit trail |
| All profile services | consumer | stable `identity_id`, claim lookup, event flow |
| SRE on-call | operator | alerts, MTTR |
| Keycloak team | peer | SPI integration, realm design |
| `fraud-risk-service` | consumer | `identity.session.*.v1`, `identity.user.suspended.v1` |
| `notification-service` | consumer | `identity.session.revoked.v1` for "new device" alerts |
| Support / legal | user | suspend, disable, reinstate, erase actions |

## 5. Actors / Personas

- **Customer** — implicit: their Keycloak account drives
  `identity.user.created.v1` and the suspension flow.
- **Driver / courier** — same shape, with a driver- or
  courier-realm identity.
- **Restaurant / merchant staff** — staff-realm identity.
- **Internal admin / support agent** — uses
  `admin-service` to invoke suspend, disable, reinstate, or
  erase on a user's identity.
- **Compliance officer** — invokes GDPR erasure through
  `admin-service`.
- **Keycloak** (system) — emits lifecycle events that the
  service consumes.
- **Downstream services** (system) — read claim caches via
  REST and react to events.

## 6. Business Capabilities

- **Identity normalization** — `kc_sub` + realm →
  `identity_id`.
- **Claim caching** — name, email, phone, locale, MFA status
  cached in PostgreSQL + Redis for low-latency reads.
- **Lifecycle event fan-out** — created, updated, suspended,
  disabled, reinstated, erased.
- **Session revocation** — emitted on every Keycloak
  logout, theft, or forced logout.
- **Force-logout** — admin action to revoke all sessions for
  a user.
- **GDPR erasure** — anonymize the row, emit erasure event,
  preserve `identity_id` for referential integrity.
- **Admin surface** — REST endpoints for suspend / disable /
  reinstate / erase / force-logout, with audit and reason
  codes.
- **Suspension reason taxonomy** — fraud, payment_failure,
  manual_review, security, legal, with consistent codes
  propagated to all consumers.

## 7. Business Requirements

| ID | Requirement | Priority | Source |
|----|-------------|----------|--------|
| BR--010 | Every platform user MUST have a stable `identity_id` (UUIDv7). | MUST | architecture |
| BR--011 | The service MUST be the only writer of the `identity.identities` table. | MUST | data ownership |
| BR--012 | The service MUST emit `identity.user.created.v1` when a new mapping is created. | MUST | event architecture |
| BR--013 | The service MUST emit `identity.user.suspended.v1` on suspension, with the reason code. | MUST | event architecture |
| BR--014 | The service MUST emit `identity.user.disabled.v1` on disablement (permanent). | MUST | event architecture |
| BR--015 | The service MUST emit `identity.user.reinstated.v1` on re-instatement. | MUST | event architecture |
| BR--016 | The service MUST emit `identity.user.erased.v1` on GDPR erasure. | MUST | GDPR |
| BR--017 | The service MUST emit `identity.session.revoked.v1` on every Keycloak session revocation. | MUST | security |
| BR--018 | A suspension MUST propagate to all dependent services within 10 seconds (P99). | MUST | SLO |
| BR--019 | The service MUST provide a low-latency claim lookup endpoint (P99 ≤ 30 ms). | MUST | SLO |
| BR--020 | The service MUST be the only service authorized to call Keycloak's admin API. | MUST | security |
| BR--021 | The service MUST anonymize PII on GDPR erasure while preserving `identity_id` for referential integrity. | MUST | GDPR |
| BR--022 | The service MUST support force-logout (revoke all sessions) for a user. | MUST | security |
| BR--023 | The service MUST soft-delete the `identities` row on erasure; the soft delete tombstone is retained for `legal_hold_years` (default 7). | MUST | legal |
| BR--024 | The service MUST record a reason code for every state change. | MUST | audit |
| BR--025 | The service SHOULD support a `region` claim on the `identities` row for multi-region admin scoping. | SHOULD | architecture |
| BR--026 | The service SHOULD expose a `/v1/identities/{id}/sessions` endpoint that reads through to Keycloak. | SHOULD | admin |
| BR--027 | The service MAY surface a fraud-risk `risk_score` column (denormalized from `fraud-risk-service`) for the suspension flow. | MAY | fraud |

## 8. Business Rules

| ID | Rule | Notes |
|----|------|-------|
| BR--030 | A suspended user cannot log in; their current sessions are revoked. | Enforced by Keycloak + this service's events. |
| BR--031 | A disabled user cannot log in; their `identity_id` is permanent. | Enforced by Keycloak. |
| BR--032 | An erased user has PII redacted; the `identity_id` is preserved for referential integrity. | Soft delete + tombstone. |
| BR--033 | A suspension reason MUST be one of: `fraud`, `payment_failure`, `manual_review`, `security`, `legal`. | Taxonomy; free-text disallowed. |
| BR--034 | Re-instatement from `suspended` to active requires an admin action with a reason. | Audit trail. |
| BR--035 | The `identity_id` is never recycled, even on erasure. | Stability. |
| BR--036 | An `identity.user.created.v1` event MUST be emitted before any dependent service can reference the new `identity_id`. | Ordering: identity event first, profile event after. |
| BR--037 | The cached claim TTL MUST NOT exceed 1 hour, even if Keycloak has not signaled an update. | Bounded staleness. |
| BR--038 | A `POST /v1/identities/{id}/erase` is idempotent on `idempotency_key`. | Replay-safe. |

## 9. Assumptions

- Keycloak is reachable and the SPI plugin is deployed in
  every realm.
- The PostgreSQL `identity` schema is sized for one row per
  user (no partitioning needed).
- Downstream services consume `identity.*.v1` events with
  the standard envelope.
- The `api-gateway` consumes `identity.session.revoked.v1`
  and `identity.user.suspended.v1` to maintain its
  revocation set.
- The admin realm (`platform-internal`) has the roles
  `identity.admin` and `super_admin` for the admin surface.

## 10. Constraints

- The service MUST NOT store passwords, OTPs, MFA factors, or
  refresh tokens.
- The service MUST NOT bypass Keycloak for authentication
  decisions.
- The service MUST use the standard event envelope from
  `architecture/EVENT_ARCHITECTURE.md`.
- The service MUST use the standard error envelope from
  `architecture/API_STANDARDS.md`.
- The service MUST NOT call other services' databases
  directly.
- Legal hold for financial records is 7 years; the erasure
  flow preserves the `identity_id` for that long.

## 11. Dependencies

| Dependency | Type | Notes |
|------------|------|-------|
| Keycloak | service | admin API, SPI plugin, JWKS |
| Vault | infra | Keycloak admin secret, DB credentials |
| `configuration-service` | service | configuration hot-reload |
| `customer-service` | producer | `customer.created.v1` (back-channel) |
| `driver-service` | producer | `driver.created.v1` (back-channel) |
| `courier-service` | producer | `courier.created.v1` (back-channel) |
| `merchant-service` | producer | `merchant.created.v1` (back-channel) |
| `restaurant-service` | producer | `restaurant.created.v1` (back-channel) |
| `audit-service` | consumer | consumes `identity.*.v1` |
| `analytics-service` | consumer | consumes `identity.*.v1` |
| `notification-service` | consumer | consumes `identity.session.revoked.v1` |
| `api-gateway` | consumer | consumes `identity.session.revoked.v1` and `identity.user.suspended.v1` |
| `fraud-risk-service` | consumer | consumes `identity.session.*.v1` |
| Redis | infra | revocation projection, claim hot-cache |
| Kafka | infra | event bus |

## 12. Business Workflows

- **User creation in Keycloak → identity mapping** (detailed in
  `WORKFLOWS.md`).
- **Suspension of a user** (detailed in `WORKFLOWS.md`).
- **Force-logout / session revocation** (detailed in
  `WORKFLOWS.md`).
- **GDPR right-to-erasure** (detailed in `WORKFLOWS.md`).
- **Claim refresh from Keycloak** (detailed in `WORKFLOWS.md`).

## 13. Exception Workflows

- **Keycloak unreachable on write path** — the write is
  retried with exponential backoff; on continued failure, a
  circuit breaker opens and the action returns
  `503 SERVICE_UNAVAILABLE`. The on-call is paged.
- **Kafka emit fails** — the service uses the outbox pattern;
  the row is in the DB, the event is in the outbox, the
  poller will publish. A `warn` log is emitted.
- **Concurrent suspension + re-instatement** — the
  `identity_id` row has an optimistic-lock version; the
  second action is rejected with `409 CONFLICT` and the
  client retries.
- **Erasure on a user with active financial records** — the
  service performs the erasure but emits a `warn` log and
  notifies compliance; financial records in
  `ledger-service` and `payment-service` retain the
  `identity_id` reference but their PII columns are
  redacted.

## 14. Success Criteria

- 100% of platform users have an `identity_id`; no service
  references `kc_sub` directly in its database.
- 100% of `identity.*.v1` events are observed by all
  declared consumers within 10 seconds (P99).
- A suspension propagates end-to-end in ≤ 10 seconds (P99).
- A GDPR erasure completes end-to-end in ≤ 24 hours
  (expedited) and is auditable.
- The service's P99 read latency is ≤ 30 ms.
- The service has zero direct Keycloak admin API callers
  other than itself.

## 15. KPIs

| KPI | Target | Measurement |
|-----|--------|-------------|
| Availability | ≥ 99.95% per 30d | uptime / total time per region |
| P99 read latency | ≤ 30 ms | request duration histogram |
| P99 propagation lag | ≤ 10 s | event time → consumer ack |
| Claim cache hit ratio | ≥ 95% | Redis hit / total lookups |
| Erasure SLA | 100% within 24 h (expedited) | support ticket resolution time |
| Suspension event loss | 0 | audit-service count vs. identity-service emit count |
| Force-logout latency | ≤ 30 s P99 | action → Keycloak ack → event published |

## 16. Acceptance Criteria

- A new Keycloak user creation results in an
  `identity.identities` row and an
  `identity.user.created.v1` event.
- A suspension request results in a Keycloak state change,
  an `identity.user.suspended.v1` event with the reason
  code, and the `api-gateway`'s revocation set updated
  within 10 seconds (P99).
- A force-logout request results in every active session
  revoked at Keycloak and an `identity.session.revoked.v1`
  event with the revoked `jti` list.
- A GDPR erasure request results in PII redaction, an
  `identity.user.erased.v1` event, and a
  `support.ticket.opened.v1` for the audit trail.
- A claim lookup by `identity_id` returns the cached claim
  in ≤ 30 ms (P99).
- A claim update from Keycloak results in the cached row
  being updated and `identity.user.updated.v1` being
  emitted within 10 seconds (P99).
- The service is the only one in the platform with
  Keycloak admin API credentials.

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

