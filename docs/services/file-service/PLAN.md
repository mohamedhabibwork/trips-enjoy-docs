# file-service — Implementation Plan

**Domain:** Platform Foundation
**Tier:** 1
**Technology:** Go
**Criticality:** T2 (99.9%)
**DB Schema:** `file`
**Cache:** Redis — presigned URL cache
**HPA:** CPU 60%, 2–4, p99 < 200ms

---

## Purpose

**Phase 1 — Platform Foundation.** This service is on the critical path; ship it before any consumer starts.

This PLAN.md is the source of truth for **how** `file-service` is built. The 10-phase
backbone below mirrors the locked Phase 1-7 layout in `IMPLEMENTATION_PHASES.md`
plus the Phase 7 cross-cutting and Phase 7.5 Make-a-Deal addenda where this
service participates.

---

## Tasks

### Phase 1 — Database & Domain Model

| ID | Task | Status | Depends-On | Required Role(s) | Approver Role | Co-Signer Role | Break-Glass? |
|---|---|---|---|---|---|---|---|
| T-FILE-01 | Create schema `file`: tables per `ERD.md` (partitioned by time/zone/hash per data shape) | pending | — | file.admin | file.admin | — | — |
| T-FILE-02 | Write Flyway/migrations (forward-only); install `pg_partman` if the parent table is time-partitioned | pending | T-FILE-01 | file.admin | file.admin | — | — |
| T-FILE-03 | Implement the aggregate root, immutability invariants, and append-only audit constraints | pending | T-FILE-02 | file.admin | file.admin | — | — |
| T-FILE-04 | Add `file.outbox` and `file.inbox` for reliable eventing | pending | T-FILE-03 | file.outbox, file.inbox | file.outbox | — | — |
### Phase 2 — REST API

| ID | Task | Status | Depends-On | Required Role(s) | Approver Role | Co-Signer Role | Break-Glass? |
|---|---|---|---|---|---|---|---|
| T-FILE-01 | CRUD endpoints per `INTEGRATION.md` (versioned `/v1/...`) | pending | — | file.admin | file.admin | — | — |
| T-FILE-02 | Idempotency-Key middleware on every mutating route | pending | T-FILE-01 | file.admin | file.admin | — | — |
| T-FILE-03 | Pagination + filtering on every list endpoint | pending | T-FILE-02 | file.admin | file.admin | — | — |
| T-FILE-04 | Health endpoints: `/actuator/health`, `/ready`, `/started` | pending | T-FILE-03 | file.admin | file.admin | — | — |
### Phase 3 — Event Publishing

| ID | Task | Status | Depends-On | Required Role(s) | Approver Role | Co-Signer Role | Break-Glass? |
|---|---|---|---|---|---|---|---|
| T-FILE-01 | Transactional outbox + poller (200 ms interval, DLQ) | pending | — | file.admin | file.admin | — | — |
| T-FILE-02 | Publish events per the integration map below | pending | T-FILE-01 | file.admin | file.admin | — | — |
| T-FILE-03 | Avro schema registered in Schema Registry on first publish | pending | T-FILE-02 | file.admin | file.admin | — | — |
### Phase 4 — Event Consumption

| ID | Task | Status | Depends-On | Required Role(s) | Approver Role | Co-Signer Role | Break-Glass? |
|---|---|---|---|---|---|---|---|
| T-FILE-01 | Idempotent inbox; LSN/offset dedup window 7 days | pending | — | file.admin | file.admin | — | — |
| T-FILE-02 | Single consumer per partition; pause-on-error with backoff | pending | T-FILE-01 | file.admin | file.admin | — | — |
| T-FILE-03 | Dead-letter topic after N retries | pending | T-FILE-02 | file.admin | file.admin | — | — |
### Phase 5 — Caching

| ID | Task | Status | Depends-On | Required Role(s) | Approver Role | Co-Signer Role | Break-Glass? |
|---|---|---|---|---|---|---|---|
| T-FILE-01 | Redis — presigned URL cache | pending | — | file.admin | file.admin | — | — |
| T-FILE-02 | Push-invalidate on every write that affects the cache key | pending | T-FILE-01 | file.admin | file.admin | — | — |
| T-FILE-03 | Stampede protection on hot keys (single-flight) | pending | T-FILE-02 | file.admin | file.admin | — | — |
### Phase 6 — External Integrations

| ID | Task | Status | Depends-On | Required Role(s) | Approver Role | Co-Signer Role | Break-Glass? |
|---|---|---|---|---|---|---|---|
| T-FILE-01 | Sync dependencies: S3, ClamAV | pending | — | file.admin | file.admin | — | — |
| T-FILE-02 | Circuit breakers on every outbound call (Resilience4j / polly) | pending | T-FILE-01 | file.admin | file.admin | — | — |
| T-FILE-03 | OAuth2 client credentials + mTLS for service-to-service | pending | T-FILE-02 | file.admin | file.admin | — | — |
| T-FILE-04 | HashiCorp Vault for DB credentials and signing keys | pending | T-FILE-03 | file.admin | file.admin | — | — |
### Phase 7 — Security

| ID | Task | Status | Depends-On | Required Role(s) | Approver Role | Co-Signer Role | Break-Glass? |
|---|---|---|---|---|---|---|---|
| T-FILE-01 | JWT bearer auth via Keycloak, realm `platform-internal` | pending | — | file.admin | file.admin | — | — |
| T-FILE-02 | Required scopes/roles per `INTEGRATION.md` | pending | T-FILE-01 | file.admin | file.admin | — | — |
| T-FILE-03 | `X-Audit-Reason` header required on admin mutations | pending | T-FILE-02 | file.admin | file.admin | — | — |
| T-FILE-04 | Field-level encryption for PII (driver license, payment method) | pending | T-FILE-03 | file.admin | file.admin | — | — |
### Phase 8 — Observability

| ID | Task | Status | Depends-On | Required Role(s) | Approver Role | Co-Signer Role | Break-Glass? |
|---|---|---|---|---|---|---|---|
| T-FILE-01 | Structured JSON logs with `correlation_id`, `user_id`, `tenant_id` | pending | — | file.admin | file.admin | — | — |
| T-FILE-02 | Metrics: RED per route + business counters specific to this service | pending | T-FILE-01 | file.admin | file.admin | — | — |
| T-FILE-03 | OpenTelemetry traces with child spans; long-poll spans open until response | pending | T-FILE-02 | file.admin | file.admin | — | — |
| T-FILE-04 | Alerts in Grafana: p99 latency, error rate, consumer lag | pending | T-FILE-03 | file.admin | file.admin | — | — |
### Phase 9 — Testing

| ID | Task | Status | Depends-On | Required Role(s) | Approver Role | Co-Signer Role | Break-Glass? |
|---|---|---|---|---|---|---|---|
| T-FILE-01 | Unit tests: 80%+ branch coverage on the aggregate | pending | — | file.admin | file.admin | — | — |
| T-FILE-02 | Integration tests: Testcontainers (PostgreSQL, Kafka, Redis) | pending | T-FILE-01 | file.admin | file.admin | — | — |
| T-FILE-03 | Contract tests: Producer Avro schemas pinned in CI | pending | T-FILE-02 | file.admin | file.admin | — | — |
| T-FILE-04 | E2E test per major user journey in `WORKFLOWS.md` | pending | T-FILE-03 | file.admin | file.admin | — | — |
### Phase 10 — Deployment

| ID | Task | Status | Depends-On | Required Role(s) | Approver Role | Co-Signer Role | Break-Glass? |
|---|---|---|---|---|---|---|---|
| T-FILE-01 | Kubernetes manifests: Deployment, Service, HPA, PDB | pending | — | file.admin | file.admin | — | — |
| T-FILE-02 | Pre-upgrade Job for migrations | pending | T-FILE-01 | file.admin | file.admin | — | — |
| T-FILE-03 | Resource limits per `DEPLOYMENT_ARCHITECTURE.md` | pending | T-FILE-02 | file.admin | file.admin | — | — |
| T-FILE-04 | Smoke test in staging before production rollout | pending | T-FILE-03 | file.admin | file.admin | — | — |
---

## Integration Map

### Sync Dependencies (outbound calls)
| Target | Endpoint | Purpose | Circuit Breaker |
|--------|----------|---------|----------------|
| `S3` | per `INTEGRATION.md` | sync dependency | Yes |
| `ClamAV` | per `INTEGRATION.md` | sync dependency | Yes |

### Events Published
| Event | Topic | Trigger | Key Consumers |
|-------|-------|---------|--------------|
| `file.uploaded` | derived from name | see INTEGRATION.md | see INTEGRATION.md |
| `file.scanned` | derived from name | see INTEGRATION.md | see INTEGRATION.md |
| `file.deleted` | derived from name | see INTEGRATION.md | see INTEGRATION.md |

### Events Consumed
| Event | Producer | Handler |
|-------|----------|---------|


---

## Acceptance Criteria
- [ ] All REST endpoints return correct status codes
- [ ] p99 latency within SLO target (T2 (99.9%))
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


---

## Role Mapping (back-reference)

This service's tasks map to platform roles per [`MASTER_TASK.md`](../../MASTER_TASK.md) 11 "Role Mapping (back-reference)". The columns `Required Role(s) | Approver Role | Co-Signer Role | Break-Glass?` added to every task table above come from that appendix.

| ID prefix | Required Role(s) | Approver Role | Co-Signer Role | Break-Glass? |
|---|---|---|---|---|
| T-FILE-NN (Phase 1-10) | per task | per task | per task | per task |
| T-FILE-P70-NN | platform.admin / pricing.admin / customer.admin | platform.admin | platform.super_admin | no (Phase 7.0 cross-cutting) |
| T-FILE-P75-NN | platform.admin / pricing.admin | platform.admin | platform.super_admin | no (Phase 7.5 Make-a-Deal) |
| T-FILE-P76-NN | platform.admin + Conductor UI role | platform.admin | platform.super_admin | yes (workflow worker registration) |

For the canonical SUPER_ADMIN preset (1 × `platform.super_admin` + 20 × `<service>.admin`), see [`shared/TIME_BOUNDED_ALIASES.md`](../shared/TIME_BOUNDED_ALIASES.md) for time-bounded aliases and `admin-service/INTEGRATION.md` 1.13 for the canonical role list.
