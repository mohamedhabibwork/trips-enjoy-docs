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
- [ ] Create schema `admin`: tables `action_log` (partitioned by month, append-only), `permission_cache`, `outbox`, `inbox`
- [ ] Key columns: `action_log(id UUID, actor_id UUID, target_service TEXT, action TEXT, target_resource_id UUID, target_user_id UUID, result TEXT, reason TEXT, signature TEXT, break_glass BOOL, created_at TIMESTAMPTZ)`
- [ ] Add `super_admin_grant` (partitioned by month, append-only, REVOKE UPDATE/DELETE): one row per `POST/DELETE /v1/admin/identity/(grant|revoke)-super-admin` call; tracks the 59-role fan-out via `source_request_id` (joined with `identity-service.role_assignment_history`)
- [ ] Write Flyway migrations (forward-only)
- [ ] Implement `AdminAction` aggregate and `ActionLogRepository`
- [ ] Implement `SuperAdminGrant` aggregate and `SuperAdminGrantRepository`

### Phase 2 — REST API
- [ ] `POST /v1/admin/{service}/{action}` — dispatch action to target service (requires `X-Audit-Reason`, `Idempotency-Key`)
- [ ] `GET /v1/admin/actions` — search action log (paged, cursor-based)
- [ ] `GET /v1/admin/actions/{id}` — read action detail
- [ ] `POST /v1/admin/actions/{id}/break-glass` — co-sign a pending break-glass request
- [ ] `GET /v1/admin/permissions` — list current user's scopes
- [ ] `GET /v1/admin/services` — service catalog: 58 services × accepted admin scopes × `SUPER_ADMIN` preset membership (see `admin-service/INTEGRATION.md` §1.12)
- [ ] `GET /v1/admin/presets` — list permission presets (currently `SUPER_ADMIN`)
- [ ] `GET /v1/admin/identity/permissions/{user_id}` — read a user's roles + preset membership (forwards to `identity-service`)
- [ ] `POST /v1/admin/identity/grant-super-admin` — grant the `SUPER_ADMIN` preset (requires break-glass + signature + MFA + super-admin IP allowlist; emits `admin.super_admin.granted.v1`; pages security)
- [ ] `DELETE /v1/admin/identity/revoke-super-admin` — revoke the `SUPER_ADMIN` preset (same gates as grant)

### Phase 3 — Event Publishing
- [ ] Implement transactional outbox table
- [ ] Publish `admin.action.performed.v1` → topic `admin.action.performed` (every action, success or failed)
- [ ] Publish `admin.action.dispatched.v1` → topic `admin.action.dispatched` (before target service responds)
- [ ] Publish `admin.action.failed.v1` → topic `admin.action.failed` (on 4xx/5xx from target)
- [ ] Publish `admin.user.suspended.v1` → topic `platform.admin`
- [ ] Publish `admin.user.disabled.v1` → topic `platform.admin`
- [ ] Publish `admin.user.reinstated.v1` → topic `platform.admin`
- [ ] Publish `admin.configuration.changed.v1` → topic `platform.admin`
- [ ] Publish `admin.super_admin.granted.v1` → topic `admin.super_admin.granted` (every successful SUPER_ADMIN preset grant; consumers: `audit-service`, `notification-service` for paging security, `analytics-service`)
- [ ] Publish `admin.super_admin.revoked.v1` → topic `admin.super_admin.revoked` (same consumers)
- [ ] Outbox poller (200ms interval, DLQ)

### Phase 4 — Event Consumption
- [ ] Implement inbox table for deduplication
- [ ] Consume `identity.session.revoked.v1` → invalidate operator's in-memory permission cache
- [ ] Consume `identity.role.granted.v1` → upsert the `super_admin_grant` view keyed by `source_request_id`; invalidate operator-UI permission cache for any operator whose visible role set changed
- [ ] Consume `identity.role.revoked.v1` → same as grant
- [ ] Consume `customer.suspended.v1` → render in support console timeline; add `customer_id` to Redis `blocked_targets`
- [ ] Consume `driver.suspended.v1` → render in timeline
- [ ] Consume `courier.suspended.v1` → render in timeline
- [ ] Consume `configuration.updated.v1` → invalidate admin permission cache; reload config
- [ ] Consume `trip.completed.v1` → upsert `admin.trip_cache`
- [ ] Consume `payment.failed.v1` → upsert `admin.payment_failure_cache`

### Phase 5 — Caching
- [ ] Cache operator permission sets with short TTL, event-invalidated on `identity.session.revoked.v1`
- [ ] Cache invalidation on `configuration.updated.v1` and `identity.session.revoked.v1`
- [ ] Cache `GET /v1/admin/services` response (TTL 5m; push-invalidate on `configuration.updated.v1`)

### Phase 6 — External Integrations
- [ ] Integrate with every target service via REST (dynamic dispatch based on `{service}/{action}`)
- [ ] HashiCorp Vault — DB credentials, HMAC-SHA256 signing keys for high-value actions
- [ ] Keycloak admin realm (`platform-internal`) for admin token validation
- [ ] Circuit breakers on all outbound calls (one bulkhead pool per target service)
- [ ] `identity-service` — new outbound calls (3): `GET /admin/v1/identities/{id}/roles`, `POST /admin/v1/identities/{id}/roles/{role}` (fanned 59× per grant), `DELETE /admin/v1/identities/{id}/roles/{role}` (fanned 59× per revoke)

### Phase 7 — Security
- [ ] JWT bearer auth via Keycloak (Spring Security 7), realm `platform-internal`, MFA mandatory
- [ ] Required scopes/roles: per-action RBAC (`payment.refund`, `configuration.write`, etc.), `admin.read`, `admin.break_glass`, `admin.super_admin.grant`, `admin.super_admin.revoke`
- [ ] HMAC-SHA256 request signing for high-value actions (`X-Signature`)
- [ ] Step-up MFA for super-admin / off-hours actions
- [ ] IP allowlist enforcement for super-admin (regular and SUPER_ADMIN preset grants)
- [ ] Super-admin IP allowlist (separate): `IP_ALLOWLIST_SUPER_ADMIN` env, distinct from the regular admin allowlist
- [ ] Break-glass co-signature requirement (`X-Break-Glass-Cosigner`) — **never optional** for `SUPER_ADMIN` preset grants/revokes
- [ ] Secrets via HashiCorp Vault

### Phase 8 — Observability
- [ ] Structured JSON logs with `correlation_id`, `actor_id`, `action`, `target_service`, `target_resource_id`, `result`
- [ ] Metrics: RED per route + `admin_actions_total{service,action,result}`
- [ ] OpenTelemetry traces with child spans per downstream call (one root span per action)
- [ ] Health endpoints: `/actuator/health`, `/ready`, `/started`

### Phase 9 — Testing
- [ ] Unit tests: domain model, RBAC rules, action routing, break-glass logic
- [ ] Integration tests: Testcontainers (PostgreSQL, Kafka, Redis)
- [ ] E2E tests: dispatch action happy path, break-glass flow, off-hours restriction

### Phase 10 — Deployment
- [ ] Kubernetes manifests: Deployment, Service, HPA (CPU 60%, 2–5 replicas), PDB
- [ ] Pre-upgrade Job for database migrations
- [ ] Resource limits per DEPLOYMENT_ARCHITECTURE.md

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
| `admin.user.disabled.v1` | `platform.admin` | admin disables a user | `audit-service`, `support-service` |
| `admin.user.reinstated.v1` | `platform.admin` | admin reinstates a user | `audit-service`, `notification-service` |
| `admin.configuration.changed.v1` | `platform.admin` | admin changes config | `audit-service`, `analytics-service` |
| `admin.super_admin.granted.v1` | `admin.super_admin.granted` | SUPER_ADMIN preset granted (pages security) | `audit-service`, `notification-service`, `analytics-service` |
| `admin.super_admin.revoked.v1` | `admin.super_admin.revoked` | SUPER_ADMIN preset revoked (pages security) | `audit-service`, `notification-service`, `analytics-service` |

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
