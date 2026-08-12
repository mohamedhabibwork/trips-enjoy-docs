# admin-service — Implementation Plan

**Domain:** Platform & Operations
**Tier:** 1
**Technology:** Kotlin + Spring Boot 4
**Criticality:** T1 (99.95% SLO)
**DB Schema:** `admin`
**Cache:** —
**HPA:** CPU 60%, 2–5, p99 < 500ms

---

## Purpose

`admin-service` is the platform's operations console and single entry point for every high-value mutation across all services (e.g., issue a refund, suspend a customer, roll back a config). It enforces RBAC, request signing, audit reason, and break-glass co-signature for super-admin actions.

---

## Tasks

### Phase 1 — Database & Domain Model

| ID | Task | Status | Depends-On | Required Role(s) | Approver Role | Co-Signer Role | Break-Glass? |
|---|---|---|---|---|---|---|---|
| T-ADM-01 | Create schema `admin`: tables `action_log` (partitioned by month, append-only), `permission_cache`, `outbox`, `inbox` | pending | — | platform.admin | platform.admin | — | — |
| T-ADM-02 | Key columns: `action_log(id UUID, actor_id UUID, target_service TEXT, action TEXT, target_resource_id UUID, target_user_id UUID, result TEXT, reason TEXT, signature TEXT, break_glass BOOL, created_at TIMESTAMPTZ)` | pending | T-ADM-01 | platform.admin | platform.admin | — | yes |
| T-ADM-03 | Add `super_admin_grant` (partitioned by month, append-only, REVOKE UPDATE/DELETE): one row per `POST/DELETE /v1/admin/identity/(grant | revoke)-super-admin` call; tracks the 21-role fan-out via `source_request_id` (joined with `identity-service.role_assignment_history`) | pending | platform.admin | platform.admin | — | — | platform.admin | platform.admin | — | — |
| T-ADM-04 | Write Flyway migrations (forward-only) | pending | T-ADM-03 | platform.admin | platform.admin | — | — |
| T-ADM-05 | Implement `AdminAction` aggregate and `ActionLogRepository` | pending | T-ADM-04 | platform.admin | platform.admin | — | — |
| T-ADM-06 | Implement `SuperAdminGrant` aggregate and `SuperAdminGrantRepository` | pending | T-ADM-05 | platform.admin | platform.admin | — | — |
### Phase 2 — REST API

| ID | Task | Status | Depends-On | Required Role(s) | Approver Role | Co-Signer Role | Break-Glass? |
|---|---|---|---|---|---|---|---|
| T-ADM-01 | `POST /v1/admin/{service}/{action}` — dispatch action to target service (requires `X-Audit-Reason`, `Idempotency-Key`) | pending | — | platform.admin | platform.admin | — | — |
| T-ADM-02 | `GET /v1/admin/actions` — search action log (paged, cursor-based) | pending | T-ADM-01 | platform.admin | platform.admin | — | — |
| T-ADM-03 | `GET /v1/admin/actions/{id}` — read action detail | pending | T-ADM-02 | platform.admin | platform.admin | — | — |
| T-ADM-04 | `POST /v1/admin/actions/{id}/break-glass` — co-sign a pending break-glass request | pending | T-ADM-03 | platform.admin | platform.admin | — | yes |
| T-ADM-05 | `GET /v1/admin/permissions` — list current user's scopes | pending | T-ADM-04 | platform.admin | platform.admin | — | — |
| T-ADM-06 | `GET /v1/admin/services` — service catalog: 20 services × accepted admin scopes × `SUPER_ADMIN` preset membership (see `admin-service/INTEGRATION.md` 1.12) | pending | T-ADM-05 | platform.admin | platform.admin | — | — |
| T-ADM-07 | `GET /v1/admin/presets` — list permission presets (currently `SUPER_ADMIN`) | pending | T-ADM-06 | platform.admin | platform.admin | — | — |
| T-ADM-08 | `GET /v1/admin/identity/permissions/{user_id}` — read a user's roles + preset membership (forwards to `identity-service`) | pending | T-ADM-07 | platform.admin | platform.admin | — | — |
| T-ADM-09 | `POST /v1/admin/identity/grant-super-admin` — grant the `SUPER_ADMIN` preset (requires break-glass + signature + MFA + super-admin IP allowlist; emits `admin.super_admin.granted.v1`; pages security) | pending | T-ADM-08 | platform.admin | platform.admin | — | yes |
| T-ADM-10 | `DELETE /v1/admin/identity/revoke-super-admin` — revoke the `SUPER_ADMIN` preset (same gates as grant) | pending | T-ADM-09 | platform.admin | platform.admin | — | — |
### Phase 3 — Event Publishing

| ID | Task | Status | Depends-On | Required Role(s) | Approver Role | Co-Signer Role | Break-Glass? |
|---|---|---|---|---|---|---|---|
| T-ADM-01 | Implement transactional outbox table | pending | — | platform.admin | platform.admin | — | — |
| T-ADM-02 | Publish `admin.action.performed.v1` → topic `admin.action.performed` (every action, success or failed) | pending | T-ADM-01 | admin.action.performed | admin.action.performed | — | — |
| T-ADM-03 | Publish `admin.action.dispatched.v1` → topic `admin.action.dispatched` (before target service responds) | pending | T-ADM-02 | admin.action.dispatched | admin.action.dispatched | — | — |
| T-ADM-04 | Publish `admin.action.failed.v1` → topic `admin.action.failed` (on 4xx/5xx from target) | pending | T-ADM-03 | admin.action.failed | admin.action.failed | — | — |
| T-ADM-05 | Publish `admin.user.suspended.v1` → topic `platform.admin` | pending | T-ADM-04 | platform.admin | platform.admin | — | — |
| T-ADM-06 | Publish `admin.user.disabled.v1` → topic `platform.admin` | pending | T-ADM-05 | platform.admin | platform.admin | — | — |
| T-ADM-07 | Publish `admin.user.reinstated.v1` → topic `platform.admin` | pending | T-ADM-06 | platform.admin | platform.admin | — | — |
| T-ADM-08 | Publish `admin.configuration.changed.v1` → topic `platform.admin` | pending | T-ADM-07 | platform.admin | platform.admin | — | — |
| T-ADM-09 | Publish `admin.super_admin.granted.v1` → topic `admin.super_admin.granted` (every successful SUPER_ADMIN preset grant; consumers: `audit-service`, `notification-service` for paging security, ``reporting-service` (data lake)`) | pending | T-ADM-08 | admin.super_admin.granted | admin.super_admin.granted | — | — |
| T-ADM-10 | Publish `admin.super_admin.revoked.v1` → topic `admin.super_admin.revoked` (same consumers) | pending | T-ADM-09 | admin.super_admin.revoked | admin.super_admin.revoked | — | — |
| T-ADM-11 | Outbox poller (200ms interval, DLQ) | pending | T-ADM-10 | platform.admin | platform.admin | — | — |
### Phase 4 — Event Consumption

| ID | Task | Status | Depends-On | Required Role(s) | Approver Role | Co-Signer Role | Break-Glass? |
|---|---|---|---|---|---|---|---|
| T-ADM-01 | Implement inbox table for deduplication | pending | — | platform.admin | platform.admin | — | — |
| T-ADM-02 | Consume `identity.session.revoked.v1` → invalidate operator's in-memory permission cache | pending | T-ADM-01 | platform.admin | platform.admin | — | — |
| T-ADM-03 | Consume `identity.role.granted.v1` → upsert the `super_admin_grant` view keyed by `source_request_id`; invalidate operator-UI permission cache for any operator whose visible role set changed | pending | T-ADM-02 | platform.admin | platform.admin | — | — |
| T-ADM-04 | Consume `identity.role.revoked.v1` → same as grant | pending | T-ADM-03 | platform.admin | platform.admin | — | — |
| T-ADM-05 | Consume `customer.suspended.v1` → render in support console timeline; add `customer_id` to Redis `blocked_targets` | pending | T-ADM-04 | platform.admin | platform.admin | — | — |
| T-ADM-06 | Consume `driver.suspended.v1` → render in timeline | pending | T-ADM-05 | platform.admin | platform.admin | — | — |
| T-ADM-07 | Consume `courier.suspended.v1` → render in timeline | pending | T-ADM-06 | platform.admin | platform.admin | — | — |
| T-ADM-08 | Consume `configuration.updated.v1` → invalidate admin permission cache; reload config | pending | T-ADM-07 | platform.admin | platform.admin | — | — |
| T-ADM-09 | Consume `trip.completed.v1` → upsert `admin.trip_cache` | pending | T-ADM-08 | admin.trip_cache | admin.trip_cache | — | — |
| T-ADM-10 | Consume `payment.failed.v1` → upsert `admin.payment_failure_cache` | pending | T-ADM-09 | admin.payment_failure_cache | admin.payment_failure_cache | — | — |
### Phase 5 — Caching

| ID | Task | Status | Depends-On | Required Role(s) | Approver Role | Co-Signer Role | Break-Glass? |
|---|---|---|---|---|---|---|---|
| T-ADM-01 | Cache operator permission sets with short TTL, event-invalidated on `identity.session.revoked.v1` | pending | — | platform.admin | platform.admin | — | — |
| T-ADM-02 | Cache invalidation on `configuration.updated.v1` and `identity.session.revoked.v1` | pending | T-ADM-01 | platform.admin | platform.admin | — | — |
| T-ADM-03 | Cache `GET /v1/admin/services` response (TTL 5m; push-invalidate on `configuration.updated.v1`) | pending | T-ADM-02 | platform.admin | platform.admin | — | — |
### Phase 6 — External Integrations

| ID | Task | Status | Depends-On | Required Role(s) | Approver Role | Co-Signer Role | Break-Glass? |
|---|---|---|---|---|---|---|---|
| T-ADM-01 | Integrate with every target service via REST (dynamic dispatch based on `{service}/{action}`) | pending | — | platform.admin | platform.admin | — | — |
| T-ADM-02 | HashiCorp Vault — DB credentials, HMAC-SHA256 signing keys for high-value actions | pending | T-ADM-01 | platform.admin | platform.admin | — | — |
| T-ADM-03 | Keycloak admin realm (`platform-internal`) for admin token validation | pending | T-ADM-02 | platform.admin | platform.admin | — | — |
| T-ADM-04 | Circuit breakers on all outbound calls (one bulkhead pool per target service) | pending | T-ADM-03 | platform.admin | platform.admin | — | — |
| T-ADM-05 | `identity-service` — new outbound calls (3): `GET /admin/v1/identities/{id}/roles`, `POST /admin/v1/identities/{id}/roles/{role}` (fanned 21× per grant), `DELETE /admin/v1/identities/{id}/roles/{role}` (fanned 21× per revoke) | pending | T-ADM-04 | platform.admin | platform.admin | — | — |
### Phase 7 — Security

| ID | Task | Status | Depends-On | Required Role(s) | Approver Role | Co-Signer Role | Break-Glass? |
|---|---|---|---|---|---|---|---|
| T-ADM-01 | JWT bearer auth via Keycloak (Spring Security 7), realm `platform-internal`, MFA mandatory | pending | — | platform.admin | platform.admin | — | — |
| T-ADM-02 | Required scopes/roles: per-action RBAC (`payment.refund`, `configuration.write`, etc.), `admin.read`, `admin.break_glass`, `admin.super_admin.grant`, `admin.super_admin.revoke` | pending | T-ADM-01 | payment.refund, admin.read, admin.break_glass, admin.super_admin.grant, admin.super_admin.revoke | admin.super_admin.grant | — | yes |
| T-ADM-03 | HMAC-SHA256 request signing for high-value actions (`X-Signature`) | pending | T-ADM-02 | platform.admin | platform.admin | — | — |
| T-ADM-04 | Step-up MFA for super-admin / off-hours actions | pending | T-ADM-03 | platform.admin | platform.admin | — | — |
| T-ADM-05 | IP allowlist enforcement for super-admin (regular and SUPER_ADMIN preset grants) | pending | T-ADM-04 | platform.admin | platform.admin | — | — |
| T-ADM-06 | Super-admin IP allowlist (separate): `IP_ALLOWLIST_SUPER_ADMIN` env, distinct from the regular admin allowlist | pending | T-ADM-05 | platform.admin | platform.admin | — | — |
| T-ADM-07 | Break-glass co-signature requirement (`X-Break-Glass-Cosigner`) — **never optional** for `SUPER_ADMIN` preset grants/revokes | pending | T-ADM-06 | platform.admin | platform.admin | platform.super_admin | yes |
| T-ADM-08 | Secrets via HashiCorp Vault | pending | T-ADM-07 | platform.admin | platform.admin | — | — |
### Phase 8 — Observability

| ID | Task | Status | Depends-On | Required Role(s) | Approver Role | Co-Signer Role | Break-Glass? |
|---|---|---|---|---|---|---|---|
| T-ADM-01 | Structured JSON logs with `correlation_id`, `actor_id`, `action`, `target_service`, `target_resource_id`, `result` | pending | — | platform.admin | platform.admin | — | — |
| T-ADM-02 | Metrics: RED per route + `admin_actions_total{service,action,result}` | pending | T-ADM-01 | platform.admin | platform.admin | — | — |
| T-ADM-03 | OpenTelemetry traces with child spans per downstream call (one root span per action) | pending | T-ADM-02 | platform.admin | platform.admin | — | — |
| T-ADM-04 | Health endpoints: `/actuator/health`, `/ready`, `/started` | pending | T-ADM-03 | platform.admin | platform.admin | — | — |
### Phase 9 — Testing

| ID | Task | Status | Depends-On | Required Role(s) | Approver Role | Co-Signer Role | Break-Glass? |
|---|---|---|---|---|---|---|---|
| T-ADM-01 | Unit tests: domain model, RBAC rules, action routing, break-glass logic | pending | — | platform.admin | platform.admin | — | yes |
| T-ADM-02 | Integration tests: Testcontainers (PostgreSQL, Kafka, Redis) | pending | T-ADM-01 | platform.admin | platform.admin | — | — |
| T-ADM-03 | E2E tests: dispatch action happy path, break-glass flow, off-hours restriction | pending | T-ADM-02 | platform.admin | platform.admin | — | yes |
### Phase 10 — Deployment

| ID | Task | Status | Depends-On | Required Role(s) | Approver Role | Co-Signer Role | Break-Glass? |
|---|---|---|---|---|---|---|---|
| T-ADM-01 | Kubernetes manifests: Deployment, Service, HPA (CPU 60%, 2–5 replicas), PDB | pending | — | platform.admin | platform.admin | — | — |
| T-ADM-02 | Pre-upgrade Job for database migrations | pending | T-ADM-01 | platform.admin | platform.admin | — | — |
| T-ADM-03 | Resource limits per DEPLOYMENT_ARCHITECTURE.md | pending | T-ADM-02 | platform.admin | platform.admin | — | — |
---

## Integration Map

### Sync Dependencies (outbound calls)
| Target | Endpoint | Purpose | Circuit Breaker |
|--------|----------|---------|----------------|
| Every target service | per action | dispatch admin action | Yes |
| `identity-service` | `GET /v1/identities/introspect` | admin token validation | Yes |

### Events Published
| Event | Topic | Trigger | Key Consumers |
|-------|-------|---------|--------------|
| `admin.action.performed.v1` | `admin.action.performed` | every action | `audit-service` |
| `admin.action.dispatched.v1` | `admin.action.dispatched` | high-value action dispatched | `audit-service` |
| `admin.action.failed.v1` | `admin.action.failed` | target returned 4xx/5xx | `audit-service` |
| `admin.user.suspended.v1` | `platform.admin` | admin suspends a user | `audit-service`, `notification-service` |
| `admin.user.disabled.v1` | `platform.admin` | admin disables a user | `audit-service`, ``admin-service` (support module)` |
| `admin.user.reinstated.v1` | `platform.admin` | admin reinstates a user | `audit-service`, `notification-service` |
| `admin.configuration.changed.v1` | `platform.admin` | admin changes config | `audit-service`, ``reporting-service` (data lake)` |
| `admin.super_admin.granted.v1` | `admin.super_admin.granted` | SUPER_ADMIN preset granted (pages security) | `audit-service`, `notification-service`, ``reporting-service` (data lake)` |
| `admin.super_admin.revoked.v1` | `admin.super_admin.revoked` | SUPER_ADMIN preset revoked (pages security) | `audit-service`, `notification-service`, ``reporting-service` (data lake)` |

### Events Consumed
| Event | Producer | Handler |
|-------|----------|---------|
| `identity.session.revoked.v1` | `identity-service` | Invalidate operator's in-memory permission cache |
| `identity.role.granted.v1` | `identity-service` | Upsert super_admin_grant view keyed by source_request_id; invalidate operator-UI permission cache |
| `identity.role.revoked.v1` | `identity-service` | Same as grant |
| `customer.suspended.v1` | `customer-service` | Render in timeline; add to Redis blocked_targets |
| `driver.suspended.v1` | `driver-service` | Render in timeline |
| `courier.suspended.v1` | `courier-service` | Render in timeline |
| `configuration.updated.v1` | `configuration-service` | Invalidate permission cache; reload config; invalidate `/v1/admin/services` catalog cache |
| `trip.completed.v1` | `trip-service` | Upsert admin.trip_cache |
| `payment.failed.v1` | `payment-service` | Upsert admin.payment_failure_cache |

---

## Acceptance Criteria
- [ ] All REST endpoints return correct status codes
- [ ] p99 latency within SLO targets (< 500ms)
- [ ] All events published reliably via outbox pattern
- [ ] Zero data leakage across service boundaries
- [ ] 80%+ unit test coverage

---

## Related Docs
- [README](README.md) · [BRD](BRD.md) · [SRS](SRS.md) · [ERD](ERD.md) · [INTEGRATION](INTEGRATION.md) · [WORKFLOWS](WORKFLOWS.md) · [TECH](TECH.md)
- [Master Plan](../../MASTER_SERVICE_PLAN.md)

### Phase 7.6 — Conductor Workers

This service runs Conductor workers for the following workflows per
[ADR-0018](../../architecture/adrs/0018-workflow-engine-conductor.md)
and [`shared/CONDUCTOR_WORKFLOWS.md`](../../shared/CONDUCTOR_WORKFLOWS.md).
The full worker contract (task names, idempotency-key namespaces,
Kafka signal mapping, compensation responsibilities) is in
[`INTEGRATION.md`](./INTEGRATION.md) "Conductor Workers".

| ID | Task | Status | Depends-On | Required Role(s) | Approver Role | Co-Signer Role | Break-Glass? |
|---|---|---|---|---|---|---|---|
| T-ADM-P76-01 | Register Conductor worker for `wf.onboarding.driver.v1` — Worker — admin_service_manual_approval (HUMAN TASK, 24h SLA) | pending | — | platform.admin | platform.admin | — | — |
| T-ADM-P76-02 | Register Conductor worker for `wf.onboarding.courier.v1` — Worker — admin_service_manual_approval (HUMAN TASK, 24h SLA) | pending | — | platform.admin | platform.admin | — | — |

### Phase 7.0 — Cross-cutting: Guaranteed Rewards & Rating-Based Pricing

This service participates in Phase 7 (cross-cutting) per
[`MASTER_PLAN.md`](../../MASTER_PLAN.md) "Phase 7 — Cross-cutting".
See canonical scope there; this block lists only the cross-cutting
tasks this service owns. Full audit history lives in
[`MASTER_TASK.md`](../../MASTER_TASK.md).

| ID | Task | Status | Depends-On | Required Role(s) | Approver Role | Co-Signer Role | Break-Glass? |
|---|---|---|---|---|---|---|---|
| T-ADM-P70-01 | Implement `/v1/admin/pricing/geo-config[...]` endpoints (create/read/patch/disable/rollback/list) — Producer of `pricing.geo_config.updated.v1` | pending | — | platform.admin | platform.super_admin | — | — |
| T-ADM-P70-02 | Wire geo-config state transitions to Conductor signal per [`shared/CONDUCTOR_WORKFLOWS.md`](../../shared/CONDUCTOR_WORKFLOWS.md) 6 | pending | T-ADM-P70-01 | platform.admin | platform.super_admin | — | — |
| T-ADM-P70-03 | Verify idempotency-key namespace matches the per-flow convention in [`shared/CONDUCTOR_WORKFLOWS.md`](../../shared/CONDUCTOR_WORKFLOWS.md) 4 | pending | T-ADM-P70-02 | platform.admin | platform.super_admin | — | — |

---

## Role Mapping (back-reference)

This service's tasks map to platform roles per [`MASTER_TASK.md`](../../MASTER_TASK.md) 11 "Role Mapping (back-reference)". The columns `Required Role(s) | Approver Role | Co-Signer Role | Break-Glass?` added to every task table above come from that appendix.

| ID prefix | Required Role(s) | Approver Role | Co-Signer Role | Break-Glass? |
|---|---|---|---|---|
| T-ADM-NN (Phase 1-10) | per task | per task | per task | per task |
| T-ADM-P70-NN | platform.admin / pricing.admin / customer.admin | platform.admin | platform.super_admin | no (Phase 7.0 cross-cutting) |
| T-ADM-P75-NN | platform.admin / pricing.admin | platform.admin | platform.super_admin | no (Phase 7.5 Make-a-Deal) |
| T-ADM-P76-NN | platform.admin + Conductor UI role | platform.admin | platform.super_admin | yes (workflow worker registration) |

For the canonical SUPER_ADMIN preset (1 × `platform.super_admin` + 20 × `<service>.admin`), see [`shared/TIME_BOUNDED_ALIASES.md`](../../shared/TIME_BOUNDED_ALIASES.md) for time-bounded aliases and `admin-service/INTEGRATION.md` 1.13 for the canonical role list.
