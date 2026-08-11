# audit-service — Implementation Plan

**Domain:** Platform & Operations
**Tier:** 2
**Technology:** Kotlin + Spring Boot 4 + Spring Kafka
**Criticality:** T2 (99.9% SLO)
**DB Schema:** `audit`
**Cache:** —
**HPA:** Kafka consumer lag, 2–8, 20k evt/s

---

## Purpose

`audit-service` is the platform's immutable audit log. It consumes every audit-relevant event from every service, persists them in an append-only, cryptographically hash-chained store, and exposes a strict-RBAC search API for compliance and security teams.

---

## Tasks

### Phase 1 — Database & Domain Model

| ID | Task | Status | Depends-On | Required Role(s) | Approver Role | Co-Signer Role | Break-Glass? |
|---|---|---|---|---|---|---|---|
| T-AUD-01 | Create schema `audit`: tables `events` (append-only, partitioned by month), `litigation_holds`, `outbox`, `inbox` | pending | — | audit.admin | audit.admin | — | — |
| T-AUD-02 | Key columns: `events(id UUID, event_id UUID UNIQUE, event_name TEXT, occurred_at TIMESTAMPTZ, producer TEXT, tenant_id TEXT, aggregate_type TEXT, aggregate_id UUID, subject_type TEXT, subject_id UUID, hash TEXT, prev_hash TEXT, data JSONB)` | pending | T-AUD-01 | audit.admin | audit.admin | — | — |
| T-AUD-03 | Write Flyway migrations (forward-only); DB grants: no UPDATE/DELETE on `audit.events` | pending | T-AUD-02 | audit.events | audit.events | — | — |
| T-AUD-04 | Implement `AuditEvent` aggregate (append-only), hash chain computation | pending | T-AUD-03 | audit.admin | audit.admin | — | — |
### Phase 2 — REST API

| ID | Task | Status | Depends-On | Required Role(s) | Approver Role | Co-Signer Role | Break-Glass? |
|---|---|---|---|---|---|---|---|
| T-AUD-01 | `POST /v1/audit/search` — search audit log (requires `audit.read`, `reason` param) | pending | — | audit.read | audit.read | — | — |
| T-AUD-02 | `GET /v1/audit/events/{id}` — read single event including hash and prev_hash | pending | T-AUD-01 | audit.admin | audit.admin | — | — |
| T-AUD-03 | `GET /v1/audit/verify/{id}` — verify hash chain up to event (requires `audit.admin`) | pending | T-AUD-02 | audit.admin | audit.admin | — | — |
| T-AUD-04 | `POST /v1/audit/litigation-hold` — create litigation hold (requires `audit.admin`, `Idempotency-Key`) | pending | T-AUD-03 | audit.admin | audit.admin | — | — |
### Phase 3 — Event Publishing

| ID | Task | Status | Depends-On | Required Role(s) | Approver Role | Co-Signer Role | Break-Glass? |
|---|---|---|---|---|---|---|---|
| T-AUD-01 | Implement transactional outbox table | pending | — | audit.admin | audit.admin | — | — |
| T-AUD-02 | Publish `audit.export.completed.v1` → topic `audit.export.completed` (nightly export success) | pending | T-AUD-01 | audit.export.completed | audit.export.completed | — | — |
| T-AUD-03 | Publish `audit.consumer.lag.v1` → topic `audit.consumer.lag` (periodic, every minute) | pending | T-AUD-02 | audit.consumer.lag | audit.consumer.lag | — | — |
| T-AUD-04 | Publish `audit.hash_chain.verified.v1` → topic `audit.hash_chain.verified` (daily verification job) | pending | T-AUD-03 | audit.hash_chain.verified | audit.hash_chain.verified | — | — |
| T-AUD-05 | Publish `audit.security.compliance_violation.v1` → topic `platform.audit.security` | pending | T-AUD-04 | platform.audit.security | platform.audit.security | — | — |
| T-AUD-06 | Publish `audit.security.break_glass_used.v1` → topic `platform.audit.security` | pending | T-AUD-05 | platform.audit.security | platform.audit.security | — | yes |
| T-AUD-07 | Publish `audit.retention.purge_completed.v1` → topic `platform.audit.retention` | pending | T-AUD-06 | platform.audit.retention | platform.audit.retention | — | — |
| T-AUD-08 | Outbox poller (200ms interval, DLQ) | pending | T-AUD-07 | audit.admin | audit.admin | — | — |
### Phase 4 — Event Consumption

| ID | Task | Status | Depends-On | Required Role(s) | Approver Role | Co-Signer Role | Break-Glass? |
|---|---|---|---|---|---|---|---|
| T-AUD-01 | Implement inbox table for deduplication (keyed by `event_id`) | pending | — | audit.admin | audit.admin | — | — |
| T-AUD-02 | Consume `admin.action.performed.v1` → append immutable row | pending | T-AUD-01 | audit.admin | audit.admin | — | — |
| T-AUD-03 | Consume `payment.*` events → append immutable rows (7-year retention) | pending | T-AUD-02 | audit.admin | audit.admin | — | — |
| T-AUD-04 | Consume `wallet.*`, `ledger.posted.v1` → append immutable rows (7-year retention) | pending | T-AUD-03 | audit.admin | audit.admin | — | — |
| T-AUD-05 | Consume `trip.*`, `ride.request.*`, `dispatch.*` → append immutable rows | pending | T-AUD-04 | audit.admin | audit.admin | — | — |
| T-AUD-06 | Consume `food.order.*`, `delivery.*` → append immutable rows | pending | T-AUD-05 | audit.admin | audit.admin | — | — |
| T-AUD-07 | Consume `identity.user.*`, `customer.*`, `driver.*`, `courier.*` → append immutable rows | pending | T-AUD-06 | audit.admin | audit.admin | — | — |
| T-AUD-08 | Consume `merchant.*`, `restaurant.*`, `configuration.updated.v1`, `feature_flag.updated.v1` → append | pending | T-AUD-07 | audit.admin | audit.admin | — | — |
| T-AUD-09 | Consume `promotion.*`, `loyalty.*`, `review.*`, `tax.*`, `pricing.quote.created.v1` → append | pending | T-AUD-08 | audit.admin | audit.admin | — | — |
| T-AUD-10 | Consume `notification.*`, `comms.*`, `support.ticket.*`, `fraud.*`, `file.*`, `zone.*` → append | pending | T-AUD-09 | audit.admin | audit.admin | — | — |
### Phase 5 — Caching

| ID | Task | Status | Depends-On | Required Role(s) | Approver Role | Co-Signer Role | Break-Glass? |
|---|---|---|---|---|---|---|---|
| T-AUD-01 | No caching (read path is direct from DB) | pending | — | audit.admin | audit.admin | — | — |
| T-AUD-02 | In-process daily verification result cache | pending | T-AUD-01 | audit.admin | audit.admin | — | — |
### Phase 6 — External Integrations

| ID | Task | Status | Depends-On | Required Role(s) | Approver Role | Co-Signer Role | Break-Glass? |
|---|---|---|---|---|---|---|---|
| T-AUD-01 | AWS S3 — nightly export to `s3://trips-enjoy-platform-audit/audit/exports/<yyyy>/<mm>/<dd>/` | pending | — | audit.admin | audit.admin | — | — |
| T-AUD-02 | HashiCorp Vault — DB credentials | pending | T-AUD-01 | audit.admin | audit.admin | — | — |
| T-AUD-03 | Circuit breakers not required (no synchronous outbound) | pending | T-AUD-02 | audit.admin | audit.admin | — | — |
### Phase 7 — Security

| ID | Task | Status | Depends-On | Required Role(s) | Approver Role | Co-Signer Role | Break-Glass? |
|---|---|---|---|---|---|---|---|
| T-AUD-01 | JWT bearer auth via Keycloak (Spring Security 7), realm `platform-internal` | pending | — | audit.admin | audit.admin | — | — |
| T-AUD-02 | Required scopes/roles: `audit.read` for compliance, `audit.admin` for security | pending | T-AUD-01 | audit.read, audit.admin | audit.admin | — | — |
| T-AUD-03 | Column-level encryption for sensitive PII fields (`pgcrypto`) | pending | T-AUD-02 | audit.admin | audit.admin | — | — |
| T-AUD-04 | No UPDATE/DELETE grants on `audit.events` table at DB level | pending | T-AUD-03 | audit.events | audit.events | — | — |
| T-AUD-05 | Secrets via HashiCorp Vault | pending | T-AUD-04 | audit.admin | audit.admin | — | — |
### Phase 8 — Observability

| ID | Task | Status | Depends-On | Required Role(s) | Approver Role | Co-Signer Role | Break-Glass? |
|---|---|---|---|---|---|---|---|
| T-AUD-01 | Structured JSON logs with `correlation_id` | pending | — | audit.admin | audit.admin | — | — |
| T-AUD-02 | Metrics: RED per route + `audit_events_ingested_total{topic}`, `audit_consumer_lag{topic,partition}`, `audit_export_seconds`, `audit_hash_chain_status` | pending | T-AUD-01 | audit.admin | audit.admin | — | — |
| T-AUD-03 | OpenTelemetry traces with child spans per event for DB insert, hash computation | pending | T-AUD-02 | audit.admin | audit.admin | — | — |
| T-AUD-04 | Health endpoints: `/actuator/health`, `/ready`, `/started` | pending | T-AUD-03 | audit.admin | audit.admin | — | — |
### Phase 9 — Testing

| ID | Task | Status | Depends-On | Required Role(s) | Approver Role | Co-Signer Role | Break-Glass? |
|---|---|---|---|---|---|---|---|
| T-AUD-01 | Unit tests: hash chain computation, inbox deduplication, retention policy | pending | — | audit.admin | audit.admin | — | — |
| T-AUD-02 | Integration tests: Testcontainers (PostgreSQL, Kafka) | pending | T-AUD-01 | audit.admin | audit.admin | — | — |
| T-AUD-03 | E2E tests: ingest event, search, verify hash chain, litigation hold | pending | T-AUD-02 | audit.admin | audit.admin | — | — |
### Phase 10 — Deployment

| ID | Task | Status | Depends-On | Required Role(s) | Approver Role | Co-Signer Role | Break-Glass? |
|---|---|---|---|---|---|---|---|
| T-AUD-01 | Kubernetes manifests: Deployment, Service, HPA (Kafka consumer lag, 2–8 replicas), PDB | pending | — | audit.admin | audit.admin | — | — |
| T-AUD-02 | Pre-upgrade Job for database migrations | pending | T-AUD-01 | audit.admin | audit.admin | — | — |
| T-AUD-03 | Resource limits per DEPLOYMENT_ARCHITECTURE.md | pending | T-AUD-02 | audit.admin | audit.admin | — | — |
---

## Integration Map

### Sync Dependencies (outbound calls)
| Target | Endpoint | Purpose | Circuit Breaker |
|--------|----------|---------|----------------|
| AWS S3 | PUT | Nightly export | No (managed retry) |

### Events Published
| Event | Topic | Trigger | Key Consumers |
|-------|-------|---------|--------------|
| `audit.export.completed.v1` | `audit.export.completed` | Nightly export success | `reporting-service` |
| `audit.consumer.lag.v1` | `audit.consumer.lag` | Periodic (every minute) | Monitoring |
| `audit.hash_chain.verified.v1` | `audit.hash_chain.verified` | Daily verification | `admin-service` |
| `audit.security.compliance_violation.v1` | `platform.audit.security` | Compliance violation detected | `fraud-risk-service`, `admin-service` |
| `audit.security.break_glass_used.v1` | `platform.audit.security` | Break-glass admin action | `fraud-risk-service`, `notification-service` |
| `audit.retention.purge_completed.v1` | `platform.audit.retention` | Retention purge job | `admin-service` |

### Events Consumed
| Event | Producer | Handler |
|-------|----------|---------|
| `admin.action.performed.v1` | `admin-service` | Append immutable row |
| `payment.captured.v1` | `payment-service` | Append immutable row (7-year retention) |
| `ledger.posted.v1` | `ledger-service` | Append immutable row (7-year retention) |
| `trip.completed.v1` | `trip-service` | Append immutable row |
| `customer.suspended.v1` | `customer-service` | Append immutable row |
| `food.order.delivered.v1` | ``courier-service` (delivery)` | Append immutable row |
| All `*.audit.*` topics | All services | Append immutable row |

---

## Acceptance Criteria
- [ ] All REST endpoints return correct status codes
- [ ] p99 latency within SLO targets
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
| T-AUD-P76-01 | Register Conductor worker for `wf.phase7.reward_grant.v1` — Read-only consumer (worker — audit_service_reward_row) | pending | — | audit.admin | audit.admin | — | — |
| T-AUD-P76-02 | Register Conductor worker for `wf.phase7.reward_reversal.v1` — Read-only consumer (worker — audit_service_reward_reversal_row) | pending | — | audit.admin | audit.admin | — | — |
| T-AUD-P76-03 | Register Conductor worker for `wf.onboarding.driver.v1` — Read-only consumer | pending | — | audit.admin | audit.admin | — | — |
| T-AUD-P76-04 | Register Conductor worker for `wf.onboarding.courier.v1` — Read-only consumer | pending | — | audit.admin | audit.admin | — | — |
| T-AUD-P76-05 | Register Conductor worker for `wf.phase75.deal_rider.v1` — Worker — audit_service_deal_transition (audit.deal_transition.v1) | pending | — | audit.admin | audit.admin | — | — |
| T-AUD-P76-06 | Register Conductor worker for `wf.phase75.deal_driver.v1` — Worker — audit_service_deal_transition | pending | — | audit.admin | audit.admin | — | — |
| T-AUD-P76-07 | Register Conductor worker for `wf.phase75.deal_food.v1` — Worker — audit_service_deal_transition | pending | — | audit.admin | audit.admin | — | — |

### Phase 7.0 — Cross-cutting: Guaranteed Rewards & Rating-Based Pricing

This service participates in Phase 7 (cross-cutting) per
[`MASTER_PLAN.md`](../../MASTER_PLAN.md) "Phase 7 — Cross-cutting".
See canonical scope there; this block lists only the cross-cutting
tasks this service owns. Full audit history lives in
[`MASTER_TASK.md`](../../MASTER_TASK.md).

| ID | Task | Status | Depends-On | Required Role(s) | Approver Role | Co-Signer Role | Break-Glass? |
|---|---|---|---|---|---|---|---|
| T-AUD-P70-01 | Implement `audit.trip_reward.v1` row writer that consumes `trip.reward.granted.v1` and `trip.reward.reversed.v1` from [`trip-service`](../../services/trip-service/PLAN.md) per [`MASTER_PLAN.md`](../../MASTER_PLAN.md) Phase 7 table row 136 | pending | — | audit.admin | audit.admin | — | — |
| T-AUD-P70-02 | Verify idempotency-key namespace matches the per-flow convention in [`shared/CONDUCTOR_WORKFLOWS.md`](../../shared/CONDUCTOR_WORKFLOWS.md) 4 | pending | T-AUD-P70-01 | audit.admin | audit.admin | — | — |

### Phase 7.5 — Make-a-Deal Kernel

This service participates in Phase 7.5 (Make-a-Deal kernel) per
[`MASTER_PLAN.md`](../../MASTER_PLAN.md) "Phase 7.5" and the canonical
contract in [`shared/DEAL_FEATURE.md`](../../shared/DEAL_FEATURE.md).
See canonical scope there; this block lists only the deal-flow tasks
this service owns.

| ID | Task | Status | Depends-On | Required Role(s) | Approver Role | Co-Signer Role | Break-Glass? |
|---|---|---|---|---|---|---|---|
| T-AUD-P75-01 | Implement deal-transition consumer that consumes all 12 `*.deal.*.v1` events and writes `audit.deal_transition.v1` per [`shared/DEAL_FEATURE.md`](../../shared/DEAL_FEATURE.md) 4.1 | pending | — | audit.admin | audit.admin | — | — |
| T-AUD-P75-02 | Wire TTL-driven deal-expired transitions to Conductor signal per [`shared/CONDUCTOR_WORKFLOWS.md`](../../shared/CONDUCTOR_WORKFLOWS.md) 3.2 | pending | T-AUD-P75-01 | audit.admin | audit.admin | — | — |

---

## Role Mapping (back-reference)

This service's tasks map to platform roles per [`MASTER_TASK.md`](../../MASTER_TASK.md) 11 "Role Mapping (back-reference)". The columns `Required Role(s) | Approver Role | Co-Signer Role | Break-Glass?` added to every task table above come from that appendix.

| ID prefix | Required Role(s) | Approver Role | Co-Signer Role | Break-Glass? |
|---|---|---|---|---|
| T-AUD-NN (Phase 1-10) | per task | per task | per task | per task |
| T-AUD-P70-NN | platform.admin / pricing.admin / customer.admin | platform.admin | platform.super_admin | no (Phase 7.0 cross-cutting) |
| T-AUD-P75-NN | platform.admin / pricing.admin | platform.admin | platform.super_admin | no (Phase 7.5 Make-a-Deal) |
| T-AUD-P76-NN | platform.admin + Conductor UI role | platform.admin | platform.super_admin | yes (workflow worker registration) |

For the canonical SUPER_ADMIN preset (1 × `platform.super_admin` + 20 × `<service>.admin`), see [`shared/TIME_BOUNDED_ALIASES.md`](../shared/TIME_BOUNDED_ALIASES.md) for time-bounded aliases and `admin-service/INTEGRATION.md` 1.13 for the canonical role list.
