# configuration-service — Implementation Plan

**Domain:** Platform Foundation
**Tier:** 0 (position 1 of 21; `DEPLOYMENT_ORDER.md` §2)
**Technology:** Kotlin + Spring Boot 4
**Criticality:** T1 (99.95% SLO)
**DB Schema:** `configuration`
**Cache:** Redis — long-poll / push-invalidate
**HPA:** CPU 60%, 2–5, p99 < 50ms

---

## Purpose

`configuration-service` is the platform's single source of truth for business rules and numerical values (fares, fees, taxes, zones, ride types, eligibility thresholds). Every other service reads its operating parameters from this service at startup or via long-poll/event push, enabling operators to change business rules without redeploying any service.

---

## Tasks

### Phase 1 — Database & Domain Model

| ID | Task | Status | Depends-On | Required Role(s) | Approver Role | Co-Signer Role | Break-Glass? |
|---|---|---|---|---|---|---|---|
| T-CFG-01 | Create schema `configuration`: tables `documents` (partitioned by scope_type hash), `history` (partitioned by month), `snapshots`, `outbox`, `inbox` | pending | — | config.admin | config.admin | — | — |
| T-CFG-02 | Key columns: `documents(id UUID, key TEXT, scope_type TEXT, scope_id TEXT, value JSONB, version INT, active BOOL, created_by UUID, created_at TIMESTAMPTZ)` | pending | T-CFG-01 | config.admin | config.admin | — | — |
| T-CFG-03 | Write Flyway migrations (forward-only) | pending | T-CFG-02 | config.admin | config.admin | — | — |
| T-CFG-04 | Implement `ConfigDocument` aggregate, hierarchical scope resolution, version immutability | pending | T-CFG-03 | config.admin | config.admin | — | — |
### Phase 2 — REST API

| ID | Task | Status | Depends-On | Required Role(s) | Approver Role | Co-Signer Role | Break-Glass? |
|---|---|---|---|---|---|---|---|
| T-CFG-01 | `GET /v1/configurations` — list keys (paged, filtered) | pending | — | config.admin | config.admin | — | — |
| T-CFG-02 | `GET /v1/configurations/{key}` — read latest resolved value | pending | T-CFG-01 | config.admin | config.admin | — | — |
| T-CFG-03 | `GET /v1/configurations/{key}/versions` — read version history | pending | T-CFG-02 | config.admin | config.admin | — | — |
| T-CFG-04 | `GET /v1/configurations/{key}/versions/{version}` — read specific version | pending | T-CFG-03 | config.admin | config.admin | — | — |
| T-CFG-05 | `POST /v1/configurations` — create new key (admin, `X-Audit-Reason`) | pending | T-CFG-04 | config.admin | config.admin | — | — |
| T-CFG-06 | `PUT /v1/configurations/{key}/versions` — create new version (admin, `X-Audit-Reason`) | pending | T-CFG-05 | config.admin | config.admin | — | — |
| T-CFG-07 | `POST /v1/configurations/{key}/rollback` — revert to prior version (admin) | pending | T-CFG-06 | config.admin | config.admin | — | — |
| T-CFG-08 | `GET /v1/configurations/stream` — long-poll update stream | pending | T-CFG-07 | config.admin | config.admin | — | — |
| T-CFG-09 | `GET /v1/configurations/snapshot` — bulk read of a service's known keys | pending | T-CFG-08 | config.admin | config.admin | — | — |
| T-CFG-10 | `GET /v1/channels/{channel}/configurations` — filtered client subset (mobile) | pending | T-CFG-09 | config.admin | config.admin | — | — |
### Phase 3 — Event Publishing

| ID | Task | Status | Depends-On | Required Role(s) | Approver Role | Co-Signer Role | Break-Glass? |
|---|---|---|---|---|---|---|---|
| T-CFG-01 | Implement transactional outbox table | pending | — | config.admin | config.admin | — | — |
| T-CFG-02 | Publish `configuration.updated.v1` → every service (cache invalidation) | pending | T-CFG-01 | config.admin | config.admin | — | — |
| T-CFG-03 | Publish `configuration.rolled_back.v1` → every service | pending | T-CFG-02 | config.admin | config.admin | — | — |
| T-CFG-04 | Publish `configuration.key.deprecated.v1` → consumer services depending on deprecated key | pending | T-CFG-03 | config.admin | config.admin | — | — |
| T-CFG-05 | Publish `configuration.snapshot.exported.v1` → `reporting-service`, `audit-service` | pending | T-CFG-04 | config.admin | config.admin | — | — |
| T-CFG-06 | Outbox poller (200ms interval, DLQ) | pending | T-CFG-05 | config.admin | config.admin | — | — |
### Phase 4 — Event Consumption

| ID | Task | Status | Depends-On | Required Role(s) | Approver Role | Co-Signer Role | Break-Glass? |
|---|---|---|---|---|---|---|---|
| T-CFG-01 | No domain events consumed (source of truth) | pending | — | config.admin | config.admin | — | — |
| T-CFG-02 | Optionally consume `customer.segment.changed.v1` → invalidate per-user override caches | pending | T-CFG-01 | config.admin | config.admin | — | — |
### Phase 5 — Caching

| ID | Task | Status | Depends-On | Required Role(s) | Approver Role | Co-Signer Role | Break-Glass? |
|---|---|---|---|---|---|---|---|
| T-CFG-01 | Redis: `config:{key}` hot cache (TTL 5min, push-invalidate on every write) | pending | — | config.admin | config.admin | — | — |
| T-CFG-02 | Long-poll connection registry (in-process) | pending | T-CFG-01 | config.admin | config.admin | — | — |
| T-CFG-03 | Atomic in-memory config swap for hot-reload in consumers | pending | T-CFG-02 | config.admin | config.admin | — | — |
### Phase 6 — External Integrations

| ID | Task | Status | Depends-On | Required Role(s) | Approver Role | Co-Signer Role | Break-Glass? |
|---|---|---|---|---|---|---|---|
| T-CFG-01 | `identity-service` — validate admin token for write endpoints | pending | — | config.admin | config.admin | — | — |
| T-CFG-02 | HashiCorp Vault — DB credentials, JWT signing key | pending | T-CFG-01 | config.admin | config.admin | — | — |
| T-CFG-03 | AWS S3 — version snapshots (`s3://trips-enjoy-platform-audit/configuration/snapshots/...`) | pending | T-CFG-02 | config.admin | config.admin | — | — |
| T-CFG-04 | Circuit breakers on `identity-service` outbound call | pending | T-CFG-03 | config.admin | config.admin | — | — |
### Phase 7 — Security

| ID | Task | Status | Depends-On | Required Role(s) | Approver Role | Co-Signer Role | Break-Glass? |
|---|---|---|---|---|---|---|---|
| T-CFG-01 | JWT bearer auth via Keycloak (Spring Security 7), realm `platform-internal` | pending | — | config.admin | config.admin | — | — |
| T-CFG-02 | Required scopes/roles: `config.admin` for writes; `bearer` for reads | pending | T-CFG-01 | config.admin | config.admin | — | — |
| T-CFG-03 | `X-Audit-Reason` header required on all mutations | pending | T-CFG-02 | config.admin | config.admin | — | — |
| T-CFG-04 | HMAC-SHA256 request signing for production rollouts and mass rollbacks | pending | T-CFG-03 | config.admin | config.admin | — | — |
| T-CFG-05 | Secrets via HashiCorp Vault | pending | T-CFG-04 | config.admin | config.admin | — | — |
### Phase 8 — Observability

| ID | Task | Status | Depends-On | Required Role(s) | Approver Role | Co-Signer Role | Break-Glass? |
|---|---|---|---|---|---|---|---|
| T-CFG-01 | Structured JSON logs with `correlation_id`, `user_id`, `key`, `version` | pending | — | config.admin | config.admin | — | — |
| T-CFG-02 | Metrics: RED per route + `config_writes_total{key,scope_type}`, `config_reads_total{key,cache_hit}`, `config_longpoll_connections` | pending | T-CFG-01 | config.admin | config.admin | — | — |
| T-CFG-03 | OpenTelemetry traces with child spans; long-poll spans open until response or timeout | pending | T-CFG-02 | config.admin | config.admin | — | — |
| T-CFG-04 | Health endpoints: `/actuator/health`, `/ready`, `/started` | pending | T-CFG-03 | config.admin | config.admin | — | — |
### Phase 9 — Testing

| ID | Task | Status | Depends-On | Required Role(s) | Approver Role | Co-Signer Role | Break-Glass? |
|---|---|---|---|---|---|---|---|
| T-CFG-01 | Unit tests: scope resolution hierarchy, version immutability, rollback logic | pending | — | config.admin | config.admin | — | — |
| T-CFG-02 | Integration tests: Testcontainers (PostgreSQL, Kafka, Redis) | pending | T-CFG-01 | config.admin | config.admin | — | — |
| T-CFG-03 | E2E tests: create version, long-poll update stream, rollback, snapshot export | pending | T-CFG-02 | config.admin | config.admin | — | — |
### Phase 10 — Deployment

| ID | Task | Status | Depends-On | Required Role(s) | Approver Role | Co-Signer Role | Break-Glass? |
|---|---|---|---|---|---|---|---|
| T-CFG-01 | Kubernetes manifests: Deployment, Service, HPA (CPU 60% + long-poll connections > 1000, 2–5 replicas), PDB | pending | — | config.admin | config.admin | — | — |
| T-CFG-02 | Pre-upgrade Job for database migrations | pending | T-CFG-01 | config.admin | config.admin | — | — |
| T-CFG-03 | Resource limits per DEPLOYMENT_ARCHITECTURE.md | pending | T-CFG-02 | config.admin | config.admin | — | — |
---

## Integration Map

### Sync Dependencies (outbound calls)
| Target | Endpoint | Purpose | Circuit Breaker |
|--------|----------|---------|----------------|
| `identity-service` | token validation | Validate admin JWT for write endpoints | Yes |

### Events Published
| Event | Topic | Trigger | Key Consumers |
|-------|-------|---------|--------------|
| `configuration.updated.v1` | `configuration.updated` | Any new version commit | Every service (cache invalidation) |
| `configuration.rolled_back.v1` | `configuration.rolled_back` | Explicit rollback | Every service |
| `configuration.key.deprecated.v1` | `configuration.key.deprecated` | Key marked deprecated | Consumer services |
| `configuration.snapshot.exported.v1` | `configuration.snapshot.exported` | Snapshot job writes to S3 | `reporting-service`, `audit-service` |

### Events Consumed
| Event | Producer | Handler |
|-------|----------|---------|
| `customer.segment.changed.v1` (optional) | `customer-service` | Invalidate per-user override caches |

---

## Acceptance Criteria
- [ ] All REST endpoints return correct status codes
- [ ] p99 latency within SLO targets (< 50ms on cache hit)
- [ ] All events published reliably via outbox pattern
- [ ] Zero data leakage across service boundaries
- [ ] 80%+ unit test coverage

---

## Related Docs
- [README](README.md) · [BRD](BRD.md) · [SRS](SRS.md) · [ERD](ERD.md) · [INTEGRATION](INTEGRATION.md) · [WORKFLOWS](WORKFLOWS.md) · [TECH](TECH.md)
- [Master Plan](../../MASTER_PLAN.md)

### Phase 7.0 — Cross-cutting: Guaranteed Rewards & Rating-Based Pricing

This service participates in Phase 7 (cross-cutting) per
[`MASTER_PLAN.md`](../../MASTER_PLAN.md) "Phase 7 — Cross-cutting".
See canonical scope there; this block lists only the cross-cutting
tasks this service owns. Full audit history lives in
[`MASTER_TASK.md`](../../MASTER_TASK.md).

| ID | Task | Status | Depends-On | Required Role(s) | Approver Role | Co-Signer Role | Break-Glass? |
|---|---|---|---|---|---|---|---|
| T-CFG-P70-01 | Implement Phase 7.0 hooks per [MASTER_PLAN.md](../../MASTER_PLAN.md) Phase 7 table for this service | pending | — | config.admin | config.admin | — | — |
| T-CFG-P70-02 | Wire Kafka signal adapter → Conductor signal per [shared/CONDUCTOR_WORKFLOWS.md](../../shared/CONDUCTOR_WORKFLOWS.md) 6 | pending | T-CFG-P70-01 | config.admin | config.admin | — | — |
| T-CFG-P70-03 | Verify idempotency-key namespace matches the per-flow convention in [shared/CONDUCTOR_WORKFLOWS.md](../../shared/CONDUCTOR_WORKFLOWS.md) 4 | pending | T-CFG-P70-02 | config.admin | config.admin | — | — |

### Phase 7.5 — Make-a-Deal Kernel

This service participates in Phase 7.5 (Make-a-Deal kernel) per
[`MASTER_PLAN.md`](../../MASTER_PLAN.md) "Phase 7.5" and the canonical
contract in [`shared/DEAL_FEATURE.md`](../../shared/DEAL_FEATURE.md).
See canonical scope there; this block lists only the deal-flow tasks
this service owns.

| ID | Task | Status | Depends-On | Required Role(s) | Approver Role | Co-Signer Role | Break-Glass? |
|---|---|---|---|---|---|---|---|
| T-CFG-P75-01 | Implement Phase 7.5 deal state machine hooks per [`shared/DEAL_FEATURE.md`](../../shared/DEAL_FEATURE.md) | pending | — | config.admin | config.admin | — | — |
| T-CFG-P75-02 | Wire TTL-driven timer transitions via Conductor worker (per [shared/CONDUCTOR_WORKFLOWS.md](../../shared/CONDUCTOR_WORKFLOWS.md) 3.2) | pending | T-CFG-P75-01 | config.admin | config.admin | — | — |


---



## Hard service-to-service dependencies

This service's position in the canonical per-service deployment
order is **Tier 0, Position 1** per
[`../../DEPLOYMENT_ORDER.md`](../../DEPLOYMENT_ORDER.md).

| Class | Services |
|---|---|
| **Hard deps** (must be live and reachable before this service can complete its `/ready` health check) | — (PostgreSQL + Redis only) |
| **Soft deps** (this service can start without them; runtime calls fail gracefully with circuit-breaker fallback until the dep is up) | — |

**Deployment scenarios** (per [`../../DEPLOYMENT_ORDER.md` §4](../../DEPLOYMENT_ORDER.md)):

- **Greenfield** — tiers are deployed in order; intra-tier parallelism is allowed.
- **Single-service rollout** — rolling deploy with canary required for Tier 0 (`configuration-service`, `identity-service`, `api-gateway`); optional for Tier 1+; canary required for `chat-service` (Phase 7.7 cross-cutting).
- **Region failover / DR** — full Tier 0 → Tier 1 → Tier 2 → Tier 3 sequence is replayed.

For cross-cutting infra deps (PostgreSQL, Kafka, Redis, Keycloak, Vault, mTLS, OTel, S3) see [`../../DEPLOYMENT_ORDER.md` §3](../../DEPLOYMENT_ORDER.md).

## Role Mapping (back-reference)

This service's tasks map to platform roles per [`MASTER_TASK.md`](../../MASTER_TASK.md) 11 "Role Mapping (back-reference)". The columns `Required Role(s) | Approver Role | Co-Signer Role | Break-Glass?` added to every task table above come from that appendix.

| ID prefix | Required Role(s) | Approver Role | Co-Signer Role | Break-Glass? |
|---|---|---|---|---|
| T-CFG-NN (Phase 1-10) | per task | per task | per task | per task |
| T-CFG-P70-NN | platform.admin / pricing.admin / customer.admin | platform.admin | platform.super_admin | no (Phase 7.0 cross-cutting) |
| T-CFG-P75-NN | platform.admin / pricing.admin | platform.admin | platform.super_admin | no (Phase 7.5 Make-a-Deal) |
| T-CFG-P76-NN | platform.admin + Conductor UI role | platform.admin | platform.super_admin | yes (workflow worker registration) |

For the canonical SUPER_ADMIN preset (1 × `platform.super_admin` + 20 × `<service>.admin`), see [`shared/TIME_BOUNDED_ALIASES.md`](../../shared/TIME_BOUNDED_ALIASES.md) for time-bounded aliases and `admin-service/INTEGRATION.md` 1.13 for the canonical role list.
