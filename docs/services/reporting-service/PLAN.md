# reporting-service — Implementation Plan

**Domain:** Analytics
**Tier:** 6
**Technology:** Python/FastAPI
**Criticality:** T3 (99.5%)
**DB Schema:** `reporting`
**Cache:** Redis — query cache
**HPA:** CPU 70%, 2–4, p99 < 500ms

---

## Purpose

**Phase 6 — Analytics & Enhancements.** Begin once the upstream event streams are stable.

This PLAN.md is the source of truth for **how** `reporting-service` is built. The 10-phase
backbone below mirrors the locked Phase 1-7 layout in `IMPLEMENTATION_PHASES.md`
plus the Phase 7 cross-cutting and Phase 7.5 Make-a-Deal addenda where this
service participates.

---

## Tasks

### Phase 1 — Database & Domain Model

| ID | Task | Status | Depends-On | Required Role(s) | Approver Role | Co-Signer Role | Break-Glass? |
|---|---|---|---|---|---|---|---|
| T-RPT-01 | Create schema `reporting`: tables per `ERD.md` (partitioned by time/zone/hash per data shape) | pending | — | reporting.admin | reporting.admin | — | — |
| T-RPT-02 | Write Flyway/migrations (forward-only); install `pg_partman` if the parent table is time-partitioned | pending | T-RPT-01 | reporting.admin | reporting.admin | — | — |
| T-RPT-03 | Implement the aggregate root, immutability invariants, and append-only audit constraints | pending | T-RPT-02 | reporting.admin | reporting.admin | — | — |
| T-RPT-04 | Add `reporting.outbox` and `reporting.inbox` for reliable eventing | pending | T-RPT-03 | reporting.outbox, reporting.inbox | reporting.outbox | — | — |
### Phase 2 — REST API

| ID | Task | Status | Depends-On | Required Role(s) | Approver Role | Co-Signer Role | Break-Glass? |
|---|---|---|---|---|---|---|---|
| T-RPT-01 | CRUD endpoints per `INTEGRATION.md` (versioned `/v1/...`) | pending | — | reporting.admin | reporting.admin | — | — |
| T-RPT-02 | Idempotency-Key middleware on every mutating route | pending | T-RPT-01 | reporting.admin | reporting.admin | — | — |
| T-RPT-03 | Pagination + filtering on every list endpoint | pending | T-RPT-02 | reporting.admin | reporting.admin | — | — |
| T-RPT-04 | Health endpoints: `/actuator/health`, `/ready`, `/started` | pending | T-RPT-03 | reporting.admin | reporting.admin | — | — |
### Phase 3 — Event Publishing

| ID | Task | Status | Depends-On | Required Role(s) | Approver Role | Co-Signer Role | Break-Glass? |
|---|---|---|---|---|---|---|---|
| T-RPT-01 | Transactional outbox + poller (200 ms interval, DLQ) | pending | — | reporting.admin | reporting.admin | — | — |
| T-RPT-02 | Publish events per the integration map below | pending | T-RPT-01 | reporting.admin | reporting.admin | — | — |
| T-RPT-03 | Avro schema registered in Schema Registry on first publish | pending | T-RPT-02 | reporting.admin | reporting.admin | — | — |
### Phase 4 — Event Consumption

| ID | Task | Status | Depends-On | Required Role(s) | Approver Role | Co-Signer Role | Break-Glass? |
|---|---|---|---|---|---|---|---|
| T-RPT-01 | Idempotent inbox; LSN/offset dedup window 7 days | pending | — | reporting.admin | reporting.admin | — | — |
| T-RPT-02 | Single consumer per partition; pause-on-error with backoff | pending | T-RPT-01 | reporting.admin | reporting.admin | — | — |
| T-RPT-03 | Dead-letter topic after N retries | pending | T-RPT-02 | reporting.admin | reporting.admin | — | — |
### Phase 5 — Caching

| ID | Task | Status | Depends-On | Required Role(s) | Approver Role | Co-Signer Role | Break-Glass? |
|---|---|---|---|---|---|---|---|
| T-RPT-01 | Redis — query cache | pending | — | reporting.admin | reporting.admin | — | — |
| T-RPT-02 | Push-invalidate on every write that affects the cache key | pending | T-RPT-01 | reporting.admin | reporting.admin | — | — |
| T-RPT-03 | Stampede protection on hot keys (single-flight) | pending | T-RPT-02 | reporting.admin | reporting.admin | — | — |
### Phase 6 — External Integrations

| ID | Task | Status | Depends-On | Required Role(s) | Approver Role | Co-Signer Role | Break-Glass? |
|---|---|---|---|---|---|---|---|
| T-RPT-01 | Sync dependencies: all services | pending | — | reporting.admin | reporting.admin | — | — |
| T-RPT-02 | Circuit breakers on every outbound call (Resilience4j / polly) | pending | T-RPT-01 | reporting.admin | reporting.admin | — | — |
| T-RPT-03 | OAuth2 client credentials + mTLS for service-to-service | pending | T-RPT-02 | reporting.admin | reporting.admin | — | — |
| T-RPT-04 | HashiCorp Vault for DB credentials and signing keys | pending | T-RPT-03 | reporting.admin | reporting.admin | — | — |
### Phase 7 — Security

| ID | Task | Status | Depends-On | Required Role(s) | Approver Role | Co-Signer Role | Break-Glass? |
|---|---|---|---|---|---|---|---|
| T-RPT-01 | JWT bearer auth via Keycloak, realm `platform-internal` | pending | — | reporting.admin | reporting.admin | — | — |
| T-RPT-02 | Required scopes/roles per `INTEGRATION.md` | pending | T-RPT-01 | reporting.admin | reporting.admin | — | — |
| T-RPT-03 | `X-Audit-Reason` header required on admin mutations | pending | T-RPT-02 | reporting.admin | reporting.admin | — | — |
| T-RPT-04 | Field-level encryption for PII (driver license, payment method) | pending | T-RPT-03 | reporting.admin | reporting.admin | — | — |
### Phase 8 — Observability

| ID | Task | Status | Depends-On | Required Role(s) | Approver Role | Co-Signer Role | Break-Glass? |
|---|---|---|---|---|---|---|---|
| T-RPT-01 | Structured JSON logs with `correlation_id`, `user_id`, `tenant_id` | pending | — | reporting.admin | reporting.admin | — | — |
| T-RPT-02 | Metrics: RED per route + business counters specific to this service | pending | T-RPT-01 | reporting.admin | reporting.admin | — | — |
| T-RPT-03 | OpenTelemetry traces with child spans; long-poll spans open until response | pending | T-RPT-02 | reporting.admin | reporting.admin | — | — |
| T-RPT-04 | Alerts in Grafana: p99 latency, error rate, consumer lag | pending | T-RPT-03 | reporting.admin | reporting.admin | — | — |
### Phase 9 — Testing

| ID | Task | Status | Depends-On | Required Role(s) | Approver Role | Co-Signer Role | Break-Glass? |
|---|---|---|---|---|---|---|---|
| T-RPT-01 | Unit tests: 80%+ branch coverage on the aggregate | pending | — | reporting.admin | reporting.admin | — | — |
| T-RPT-02 | Integration tests: Testcontainers (PostgreSQL, Kafka, Redis) | pending | T-RPT-01 | reporting.admin | reporting.admin | — | — |
| T-RPT-03 | Contract tests: Producer Avro schemas pinned in CI | pending | T-RPT-02 | reporting.admin | reporting.admin | — | — |
| T-RPT-04 | E2E test per major user journey in `WORKFLOWS.md` | pending | T-RPT-03 | reporting.admin | reporting.admin | — | — |
### Phase 10 — Deployment

| ID | Task | Status | Depends-On | Required Role(s) | Approver Role | Co-Signer Role | Break-Glass? |
|---|---|---|---|---|---|---|---|
| T-RPT-01 | Kubernetes manifests: Deployment, Service, HPA, PDB | pending | — | reporting.admin | reporting.admin | — | — |
| T-RPT-02 | Pre-upgrade Job for migrations | pending | T-RPT-01 | reporting.admin | reporting.admin | — | — |
| T-RPT-03 | Resource limits per `DEPLOYMENT_ARCHITECTURE.md` | pending | T-RPT-02 | reporting.admin | reporting.admin | — | — |
| T-RPT-04 | Smoke test in staging before production rollout | pending | T-RPT-03 | reporting.admin | reporting.admin | — | — |
---

## Integration Map

### Sync Dependencies (outbound calls)
| Target | Endpoint | Purpose | Circuit Breaker |
|--------|----------|---------|----------------|
| `all services` | per `INTEGRATION.md` | sync dependency | Yes |

### Events Published
| Event | Topic | Trigger | Key Consumers |
|-------|-------|---------|--------------|
| `report.snapshot.exported` | derived from name | see INTEGRATION.md | see INTEGRATION.md |

### Events Consumed
| Event | Producer | Handler |
|-------|----------|---------|
| `all service domain events` | see INTEGRATION.md | see INTEGRATION.md |

---

## Acceptance Criteria
- [ ] All REST endpoints return correct status codes
- [ ] p99 latency within SLO target (T3 (99.5%))
- [ ] All events published reliably via outbox pattern
- [ ] Zero data leakage across service boundaries
- [ ] 80%+ unit test coverage
- [ ] OpenAPI 3.x spec published and validated
- [ ] `INTEGRATION.md` is the source of truth for endpoints and events

---

## Related Docs
- [README](README.md) · [BRD](BRD.md) · [SRS](SRS.md) · [ERD](ERD.md) · [INTEGRATION](INTEGRATION.md) · [WORKFLOWS](WORKFLOWS.md) · [TECH](TECH.md)
- [Master Plan](../../MASTER_PLAN.md)
- [Implementation Phases](../../IMPLEMENTATION_PHASES.md)
- [Service Integration Matrix](../../SERVICE_INTEGRATION_MATRIX.md)

### Phase 7.6 — Conductor Workers

This service runs Conductor workers for the following workflows per
[ADR-0018](../../architecture/adrs/0018-workflow-engine-conductor.md)
and [`shared/CONDUCTOR_WORKFLOWS.md`](../../shared/CONDUCTOR_WORKFLOWS.md).
The full worker contract (task names, idempotency-key namespaces,
Kafka signal mapping, compensation responsibilities) is in
[`INTEGRATION.md`](./INTEGRATION.md) "Conductor Workers".

| ID | Task | Status | Depends-On | Required Role(s) | Approver Role | Co-Signer Role | Break-Glass? |
|---|---|---|---|---|---|---|---|
| T-RPT-P76-01 | Register Conductor worker for `wf.phase7.reward_grant.v1` — Read-only consumer (worker — reporting_service_reward_fact) | pending | — | reporting.admin | reporting.admin | — | — |
| T-RPT-P76-02 | Register Conductor worker for `wf.phase7.reward_reversal.v1` — Read-only consumer (worker — reporting_service_reward_reversal_fact) | pending | — | reporting.admin | reporting.admin | — | — |

### Phase 7.0 — Cross-cutting: Guaranteed Rewards & Rating-Based Pricing

This service participates in Phase 7 (cross-cutting) per
[`MASTER_PLAN.md`](../../MASTER_PLAN.md) "Phase 7 — Cross-cutting".
See canonical scope there; this block lists only the cross-cutting
tasks this service owns. Full audit history lives in
[`MASTER_TASK.md`](../../MASTER_TASK.md).

| ID | Task | Status | Depends-On | Required Role(s) | Approver Role | Co-Signer Role | Break-Glass? |
|---|---|---|---|---|---|---|---|
| T-RPT-P70-01 | Implement rewards fact table mirror (data-lake worker) — Mirror per [`MASTER_PLAN.md`](../../MASTER_PLAN.md) Phase 7 table row 137 | pending | — | reporting.admin | reporting.admin | — | — |
| T-RPT-P70-02 | Verify idempotency-key namespace matches the per-flow convention in [`shared/CONDUCTOR_WORKFLOWS.md`](../../shared/CONDUCTOR_WORKFLOWS.md) 4 | pending | T-RPT-P70-01 | reporting.admin | reporting.admin | — | — |

---

## Role Mapping (back-reference)

This service's tasks map to platform roles per [`MASTER_TASK.md`](../../MASTER_TASK.md) 11 "Role Mapping (back-reference)". The columns `Required Role(s) | Approver Role | Co-Signer Role | Break-Glass?` added to every task table above come from that appendix.

| ID prefix | Required Role(s) | Approver Role | Co-Signer Role | Break-Glass? |
|---|---|---|---|---|
| T-RPT-NN (Phase 1-10) | per task | per task | per task | per task |
| T-RPT-P70-NN | platform.admin / pricing.admin / customer.admin | platform.admin | platform.super_admin | no (Phase 7.0 cross-cutting) |
| T-RPT-P75-NN | platform.admin / pricing.admin | platform.admin | platform.super_admin | no (Phase 7.5 Make-a-Deal) |
| T-RPT-P76-NN | platform.admin + Conductor UI role | platform.admin | platform.super_admin | yes (workflow worker registration) |

For the canonical SUPER_ADMIN preset (1 × `platform.super_admin` + 20 × `<service>.admin`), see [`shared/TIME_BOUNDED_ALIASES.md`](../shared/TIME_BOUNDED_ALIASES.md) for time-bounded aliases and `admin-service/INTEGRATION.md` 1.13 for the canonical role list.
