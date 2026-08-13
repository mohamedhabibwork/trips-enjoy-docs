# fraud-risk-service — Implementation Plan

**Domain:** Platform Support
**Tier:** 2
**Technology:** Python/FastAPI
**Criticality:** T2 (99.9%)
**DB Schema:** `fraud_risk`
**Cache:** Redis — feature cache
**HPA:** CPU 70%, 2–6, p99 < 150ms

---

## Purpose

**Phase 1 — Platform Foundation.** This service is on the critical path; ship it before any consumer starts.

This PLAN.md is the source of truth for **how** `fraud-risk-service` is built. The 10-phase
backbone below mirrors the locked Phase 1-7 layout in `IMPLEMENTATION_PHASES.md`
plus the Phase 7 cross-cutting and Phase 7.5 Make-a-Deal addenda where this
service participates.

---

## Tasks

### Phase 1 — Database & Domain Model

| ID | Task | Status | Depends-On | Required Role(s) | Approver Role | Co-Signer Role | Break-Glass? |
|---|---|---|---|---|---|---|---|
| T-FRD-01 | Create schema `fraud_risk`: tables per `ERD.md` (partitioned by time/zone/hash per data shape) | pending | — | fraud_risk.admin | fraud_risk.admin | — | — |
| T-FRD-02 | Write Flyway/migrations (forward-only); install `pg_partman` if the parent table is time-partitioned | pending | T-FRD-01 | fraud_risk.admin | fraud_risk.admin | — | — |
| T-FRD-03 | Implement the aggregate root, immutability invariants, and append-only audit constraints | pending | T-FRD-02 | fraud_risk.admin | fraud_risk.admin | — | — |
| T-FRD-04 | Add `fraud_risk.outbox` and `fraud_risk.inbox` for reliable eventing | pending | T-FRD-03 | fraud_risk.outbox, fraud_risk.inbox | fraud_risk.outbox | — | — |
### Phase 2 — REST API

| ID | Task | Status | Depends-On | Required Role(s) | Approver Role | Co-Signer Role | Break-Glass? |
|---|---|---|---|---|---|---|---|
| T-FRD-01 | CRUD endpoints per `INTEGRATION.md` (versioned `/v1/...`) | pending | — | fraud_risk.admin | fraud_risk.admin | — | — |
| T-FRD-02 | Idempotency-Key middleware on every mutating route | pending | T-FRD-01 | fraud_risk.admin | fraud_risk.admin | — | — |
| T-FRD-03 | Pagination + filtering on every list endpoint | pending | T-FRD-02 | fraud_risk.admin | fraud_risk.admin | — | — |
| T-FRD-04 | Health endpoints: `/actuator/health`, `/ready`, `/started` | pending | T-FRD-03 | fraud_risk.admin | fraud_risk.admin | — | — |
### Phase 3 — Event Publishing

| ID | Task | Status | Depends-On | Required Role(s) | Approver Role | Co-Signer Role | Break-Glass? |
|---|---|---|---|---|---|---|---|
| T-FRD-01 | Transactional outbox + poller (200 ms interval, DLQ) | pending | — | fraud_risk.admin | fraud_risk.admin | — | — |
| T-FRD-02 | Publish events per the integration map below | pending | T-FRD-01 | fraud_risk.admin | fraud_risk.admin | — | — |
| T-FRD-03 | Avro schema registered in Schema Registry on first publish | pending | T-FRD-02 | fraud_risk.admin | fraud_risk.admin | — | — |
### Phase 4 — Event Consumption

| ID | Task | Status | Depends-On | Required Role(s) | Approver Role | Co-Signer Role | Break-Glass? |
|---|---|---|---|---|---|---|---|
| T-FRD-01 | Idempotent inbox; LSN/offset dedup window 7 days | pending | — | fraud_risk.admin | fraud_risk.admin | — | — |
| T-FRD-02 | Single consumer per partition; pause-on-error with backoff | pending | T-FRD-01 | fraud_risk.admin | fraud_risk.admin | — | — |
| T-FRD-03 | Dead-letter topic after N retries | pending | T-FRD-02 | fraud_risk.admin | fraud_risk.admin | — | — |
### Phase 5 — Caching

| ID | Task | Status | Depends-On | Required Role(s) | Approver Role | Co-Signer Role | Break-Glass? |
|---|---|---|---|---|---|---|---|
| T-FRD-01 | Redis — feature cache | pending | — | fraud_risk.admin | fraud_risk.admin | — | — |
| T-FRD-02 | Push-invalidate on every write that affects the cache key | pending | T-FRD-01 | fraud_risk.admin | fraud_risk.admin | — | — |
| T-FRD-03 | Stampede protection on hot keys (single-flight) | pending | T-FRD-02 | fraud_risk.admin | fraud_risk.admin | — | — |
### Phase 6 — External Integrations

| ID | Task | Status | Depends-On | Required Role(s) | Approver Role | Co-Signer Role | Break-Glass? |
|---|---|---|---|---|---|---|---|
| T-FRD-01 | Sync dependencies: identity-service | pending | — | fraud_risk.admin | fraud_risk.admin | — | — |
| T-FRD-02 | Circuit breakers on every outbound call (Resilience4j / polly) | pending | T-FRD-01 | fraud_risk.admin | fraud_risk.admin | — | — |
| T-FRD-03 | OAuth2 client credentials + mTLS for service-to-service | pending | T-FRD-02 | fraud_risk.admin | fraud_risk.admin | — | — |
| T-FRD-04 | HashiCorp Vault for DB credentials and signing keys | pending | T-FRD-03 | fraud_risk.admin | fraud_risk.admin | — | — |
### Phase 7 — Security

| ID | Task | Status | Depends-On | Required Role(s) | Approver Role | Co-Signer Role | Break-Glass? |
|---|---|---|---|---|---|---|---|
| T-FRD-01 | JWT bearer auth via Keycloak, realm `platform-internal` | pending | — | fraud_risk.admin | fraud_risk.admin | — | — |
| T-FRD-02 | Required scopes/roles per `INTEGRATION.md` | pending | T-FRD-01 | fraud_risk.admin | fraud_risk.admin | — | — |
| T-FRD-03 | `X-Audit-Reason` header required on admin mutations | pending | T-FRD-02 | fraud_risk.admin | fraud_risk.admin | — | — |
| T-FRD-04 | Field-level encryption for PII (driver license, payment method) | pending | T-FRD-03 | fraud_risk.admin | fraud_risk.admin | — | — |
### Phase 8 — Observability

| ID | Task | Status | Depends-On | Required Role(s) | Approver Role | Co-Signer Role | Break-Glass? |
|---|---|---|---|---|---|---|---|
| T-FRD-01 | Structured JSON logs with `correlation_id`, `user_id`, `tenant_id` | pending | — | fraud_risk.admin | fraud_risk.admin | — | — |
| T-FRD-02 | Metrics: RED per route + business counters specific to this service | pending | T-FRD-01 | fraud_risk.admin | fraud_risk.admin | — | — |
| T-FRD-03 | OpenTelemetry traces with child spans; long-poll spans open until response | pending | T-FRD-02 | fraud_risk.admin | fraud_risk.admin | — | — |
| T-FRD-04 | Alerts in Grafana: p99 latency, error rate, consumer lag | pending | T-FRD-03 | fraud_risk.admin | fraud_risk.admin | — | — |
### Phase 9 — Testing

| ID | Task | Status | Depends-On | Required Role(s) | Approver Role | Co-Signer Role | Break-Glass? |
|---|---|---|---|---|---|---|---|
| T-FRD-01 | Unit tests: 80%+ branch coverage on the aggregate | pending | — | fraud_risk.admin | fraud_risk.admin | — | — |
| T-FRD-02 | Integration tests: Testcontainers (PostgreSQL, Kafka, Redis) | pending | T-FRD-01 | fraud_risk.admin | fraud_risk.admin | — | — |
| T-FRD-03 | Contract tests: Producer Avro schemas pinned in CI | pending | T-FRD-02 | fraud_risk.admin | fraud_risk.admin | — | — |
| T-FRD-04 | E2E test per major user journey in `WORKFLOWS.md` | pending | T-FRD-03 | fraud_risk.admin | fraud_risk.admin | — | — |
### Phase 10 — Deployment

| ID | Task | Status | Depends-On | Required Role(s) | Approver Role | Co-Signer Role | Break-Glass? |
|---|---|---|---|---|---|---|---|
| T-FRD-01 | Kubernetes manifests: Deployment, Service, HPA, PDB | pending | — | fraud_risk.admin | fraud_risk.admin | — | — |
| T-FRD-02 | Pre-upgrade Job for migrations | pending | T-FRD-01 | fraud_risk.admin | fraud_risk.admin | — | — |
| T-FRD-03 | Resource limits per `DEPLOYMENT_ARCHITECTURE.md` | pending | T-FRD-02 | fraud_risk.admin | fraud_risk.admin | — | — |
| T-FRD-04 | Smoke test in staging before production rollout | pending | T-FRD-03 | fraud_risk.admin | fraud_risk.admin | — | — |
### Phase 7.7 — In-App Chat (cross-cutting)

This service participates in Phase 7.7 (in-app chat kernel added 2026-08-12)
as an **abuse-signal consumer**. Single source of truth:
[`services/chat-service/PLAN.md`](../chat-service/PLAN.md).

| ID | Task | Status | Depends-On | Required Role(s) | Approver Role | Co-Signer Role | Break-Glass? |
|---|---|---|---|---|---|---|---|
| T-FRD-P77-01 | Consume `chat.message.reported.v1` from `chat-service`; feed the report into the existing risk-score model as an `abuse_signal` feature per [`README.md`](./README.md) §"Abuse signals" (the existing fraud-risk feature pipeline) | pending | — | fraud_risk.admin | fraud_risk.admin | — | — |
| T-FRD-P77-02 | Consume `chat.attachment.shared.v1` from `chat-service`; for attachments flagged as suspicious (file type, size, scan-failure), bump the actor's risk score by `+0.15` per the existing risk-score thresholds in [`README.md`](./README.md) | pending | T-FRD-P77-01 | fraud_risk.admin | fraud_risk.admin | — | — |
| T-FRD-P77-03 | When an actor's `chat.abuse_score` crosses the **block threshold** (e.g. `>= 0.85`), call `POST /v1/chat/users/{user_id}/ban` on `chat-service` per [`services/chat-service/INTEGRATION.md`](../chat-service/INTEGRATION.md) §3.3 (uses `chat.admin` scope) | pending | T-FRD-P77-01 | fraud_risk.admin | fraud_risk.admin | — | yes (P0 abuse escalation) |
| T-FRD-P77-04 | Idempotency-key namespace `chat:abuse:{user_id}:{message_id}` (for the report consumer) and `chat:abuse:ban:{user_id}:{ts}` (for the ban action) | pending | T-FRD-P77-01 | fraud_risk.admin | fraud_risk.admin | — | — |
| T-FRD-P77-05 | Outbox + DLQ for `chat-service` calls per the platform outbox pattern in [`architecture/FAILURE_HANDLING.md`](../../architecture/FAILURE_HANDLING.md) — chat-service is **CRITICAL** (T1) per [`architecture/SERVICE_ISOLATION.md`](../../architecture/SERVICE_ISOLATION.md); fraud-risk calls are **DEGRADABLE** (T2) | pending | T-FRD-P77-01 | fraud_risk.admin | fraud_risk.admin | — | no |

---

## Integration Map

### Sync Dependencies (outbound calls)
| Target | Endpoint | Purpose | Circuit Breaker |
|--------|----------|---------|----------------|
| `identity-service` | per `INTEGRATION.md` | sync dependency | Yes |

### Events Published
| Event | Topic | Trigger | Key Consumers |
|-------|-------|---------|--------------|
| `fraud.risk.scored` | derived from name | see INTEGRATION.md | see INTEGRATION.md |
| `fraud.account.blocked` | derived from name | see INTEGRATION.md | see INTEGRATION.md |

### Events Consumed
| Event | Producer | Handler |
|-------|----------|---------|
| `identity.session.created` | see INTEGRATION.md | see INTEGRATION.md |
| `payment.attempted` | see INTEGRATION.md | see INTEGRATION.md |

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

### Phase 7.6 — Conductor Workers

This service runs Conductor workers for the following workflows per
[ADR-0018](../../architecture/adrs/0018-workflow-engine-conductor.md)
and [`shared/CONDUCTOR_WORKFLOWS.md`](../../shared/CONDUCTOR_WORKFLOWS.md).
The full worker contract (task names, idempotency-key namespaces,
Kafka signal mapping, compensation responsibilities) is in
[`INTEGRATION.md`](./INTEGRATION.md) "Conductor Workers".

| ID | Task | Status | Depends-On | Required Role(s) | Approver Role | Co-Signer Role | Break-Glass? |
|---|---|---|---|---|---|---|---|
| T-FRD-P76-01 | Register Conductor worker for `wf.onboarding.driver.v1` — Worker — fraud_risk_service_risk_score | pending | — | fraud_risk.admin | fraud_risk.admin | — | — |
| T-FRD-P76-02 | Register Conductor worker for `wf.onboarding.courier.v1` — Worker — fraud_risk_service_risk_score | pending | — | fraud_risk.admin | fraud_risk.admin | — | — |


---



## Hard service-to-service dependencies

This service's position in the canonical per-service deployment
order is **Tier 1, Position 14** per
[`../../DEPLOYMENT_ORDER.md`](../../DEPLOYMENT_ORDER.md).

| Class | Services |
|---|---|
| **Hard deps** (must be live and reachable before this service can complete its `/ready` health check) | [`configuration-service`](../configuration-service/README.md) (risk-model thresholds, device-fingerprint cache TTLs) |
| **Soft deps** (this service can start without them; runtime calls fail gracefully with circuit-breaker fallback until the dep is up) | [`customer-service`](../customer-service/README.md) (user history lookup at scoring time) |

**Deployment scenarios** (per [`../../DEPLOYMENT_ORDER.md` §4](../../DEPLOYMENT_ORDER.md)):

- **Greenfield** — tiers are deployed in order; intra-tier parallelism is allowed.
- **Single-service rollout** — rolling deploy with canary required for Tier 0 (`configuration-service`, `identity-service`, `api-gateway`); optional for Tier 1+; canary required for `chat-service` (Phase 7.7 cross-cutting).
- **Region failover / DR** — full Tier 0 → Tier 1 → Tier 2 → Tier 3 sequence is replayed.

For cross-cutting infra deps (PostgreSQL, Kafka, Redis, Keycloak, Vault, mTLS, OTel, S3) see [`../../DEPLOYMENT_ORDER.md` §3](../../DEPLOYMENT_ORDER.md).

## Role Mapping (back-reference)

This service's tasks map to platform roles per [`MASTER_TASK.md`](../../MASTER_TASK.md) 11 "Role Mapping (back-reference)". The columns `Required Role(s) | Approver Role | Co-Signer Role | Break-Glass?` added to every task table above come from that appendix.

| ID prefix | Required Role(s) | Approver Role | Co-Signer Role | Break-Glass? |
|---|---|---|---|---|
| T-FRD-NN (Phase 1-10) | per task | per task | per task | per task |
| T-FRD-P70-NN | platform.admin / pricing.admin / customer.admin | platform.admin | platform.super_admin | no (Phase 7.0 cross-cutting) |
| T-FRD-P75-NN | platform.admin / pricing.admin | platform.admin | platform.super_admin | no (Phase 7.5 Make-a-Deal) |
| T-FRD-P76-NN | platform.admin + Conductor UI role | platform.admin | platform.super_admin | yes (workflow worker registration) |

For the canonical SUPER_ADMIN preset (1 × `platform.super_admin` + 20 × `<service>.admin`), see [`shared/TIME_BOUNDED_ALIASES.md`](../../shared/TIME_BOUNDED_ALIASES.md) for time-bounded aliases and `admin-service/INTEGRATION.md` 1.13 for the canonical role list.
