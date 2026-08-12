# identity-service — Implementation Plan

**Domain:** Platform Foundation
**Tier:** 1
**Technology:** Node/TS
**Criticality:** T0 (99.99%)
**DB Schema:** `identity`
**Cache:** Redis — session+token
**HPA:** CPU 70%, 3–8, p99 < 80ms

---

## Purpose

**Phase 1 — Platform Foundation.** This service is on the critical path; ship it before any consumer starts.

This PLAN.md is the source of truth for **how** `identity-service` is built. The 10-phase
backbone below mirrors the locked Phase 1-7 layout in `IMPLEMENTATION_PHASES.md`
plus the Phase 7 cross-cutting and Phase 7.5 Make-a-Deal addenda where this
service participates.

---

## Tasks

### Phase 1 — Database & Domain Model

| ID | Task | Status | Depends-On | Required Role(s) | Approver Role | Co-Signer Role | Break-Glass? |
|---|---|---|---|---|---|---|---|
| T-IDN-01 | Create schema `identity`: tables per `ERD.md` (partitioned by time/zone/hash per data shape) | pending | — | identity.admin | identity.admin | — | — |
| T-IDN-02 | Write Flyway/migrations (forward-only); install `pg_partman` if the parent table is time-partitioned | pending | T-IDN-01 | identity.admin | identity.admin | — | — |
| T-IDN-03 | Implement the aggregate root, immutability invariants, and append-only audit constraints | pending | T-IDN-02 | identity.admin | identity.admin | — | — |
| T-IDN-04 | Add `identity.outbox` and `identity.inbox` for reliable eventing | pending | T-IDN-03 | identity.outbox, identity.inbox | identity.outbox | — | — |
### Phase 2 — REST API

| ID | Task | Status | Depends-On | Required Role(s) | Approver Role | Co-Signer Role | Break-Glass? |
|---|---|---|---|---|---|---|---|
| T-IDN-01 | CRUD endpoints per `INTEGRATION.md` (versioned `/v1/...`) | pending | — | identity.admin | identity.admin | — | — |
| T-IDN-02 | Idempotency-Key middleware on every mutating route | pending | T-IDN-01 | identity.admin | identity.admin | — | — |
| T-IDN-03 | Pagination + filtering on every list endpoint | pending | T-IDN-02 | identity.admin | identity.admin | — | — |
| T-IDN-04 | Health endpoints: `/actuator/health`, `/ready`, `/started` | pending | T-IDN-03 | identity.admin | identity.admin | — | — |
### Phase 3 — Event Publishing

| ID | Task | Status | Depends-On | Required Role(s) | Approver Role | Co-Signer Role | Break-Glass? |
|---|---|---|---|---|---|---|---|
| T-IDN-01 | Transactional outbox + poller (200 ms interval, DLQ) | pending | — | identity.admin | identity.admin | — | — |
| T-IDN-02 | Publish events per the integration map below | pending | T-IDN-01 | identity.admin | identity.admin | — | — |
| T-IDN-03 | Avro schema registered in Schema Registry on first publish | pending | T-IDN-02 | identity.admin | identity.admin | — | — |
### Phase 4 — Event Consumption

| ID | Task | Status | Depends-On | Required Role(s) | Approver Role | Co-Signer Role | Break-Glass? |
|---|---|---|---|---|---|---|---|
| T-IDN-01 | Idempotent inbox; LSN/offset dedup window 7 days | pending | — | identity.admin | identity.admin | — | — |
| T-IDN-02 | Single consumer per partition; pause-on-error with backoff | pending | T-IDN-01 | identity.admin | identity.admin | — | — |
| T-IDN-03 | Dead-letter topic after N retries | pending | T-IDN-02 | identity.admin | identity.admin | — | — |
### Phase 5 — Caching

| ID | Task | Status | Depends-On | Required Role(s) | Approver Role | Co-Signer Role | Break-Glass? |
|---|---|---|---|---|---|---|---|
| T-IDN-01 | Redis — session+token | pending | — | identity.admin | identity.admin | — | — |
| T-IDN-02 | Push-invalidate on every write that affects the cache key | pending | T-IDN-01 | identity.admin | identity.admin | — | — |
| T-IDN-03 | Stampede protection on hot keys (single-flight) | pending | T-IDN-02 | identity.admin | identity.admin | — | — |
### Phase 6 — External Integrations

| ID | Task | Status | Depends-On | Required Role(s) | Approver Role | Co-Signer Role | Break-Glass? |
|---|---|---|---|---|---|---|---|
| T-IDN-01 | Sync dependencies: Keycloak | pending | — | identity.admin | identity.admin | — | — |
| T-IDN-02 | Circuit breakers on every outbound call (Resilience4j / polly) | pending | T-IDN-01 | identity.admin | identity.admin | — | — |
| T-IDN-03 | OAuth2 client credentials + mTLS for service-to-service | pending | T-IDN-02 | identity.admin | identity.admin | — | — |
| T-IDN-04 | HashiCorp Vault for DB credentials and signing keys | pending | T-IDN-03 | identity.admin | identity.admin | — | — |
### Phase 7 — Security

| ID | Task | Status | Depends-On | Required Role(s) | Approver Role | Co-Signer Role | Break-Glass? |
|---|---|---|---|---|---|---|---|
| T-IDN-01 | JWT bearer auth via Keycloak, realm `platform-internal` | pending | — | identity.admin | identity.admin | — | — |
| T-IDN-02 | Required scopes/roles per `INTEGRATION.md` | pending | T-IDN-01 | identity.admin | identity.admin | — | — |
| T-IDN-03 | `X-Audit-Reason` header required on admin mutations | pending | T-IDN-02 | identity.admin | identity.admin | — | — |
| T-IDN-04 | Field-level encryption for PII (driver license, payment method) | pending | T-IDN-03 | identity.admin | identity.admin | — | — |
### Phase 8 — Observability

| ID | Task | Status | Depends-On | Required Role(s) | Approver Role | Co-Signer Role | Break-Glass? |
|---|---|---|---|---|---|---|---|
| T-IDN-01 | Structured JSON logs with `correlation_id`, `user_id`, `tenant_id` | pending | — | identity.admin | identity.admin | — | — |
| T-IDN-02 | Metrics: RED per route + business counters specific to this service | pending | T-IDN-01 | identity.admin | identity.admin | — | — |
| T-IDN-03 | OpenTelemetry traces with child spans; long-poll spans open until response | pending | T-IDN-02 | identity.admin | identity.admin | — | — |
| T-IDN-04 | Alerts in Grafana: p99 latency, error rate, consumer lag | pending | T-IDN-03 | identity.admin | identity.admin | — | — |
### Phase 9 — Testing

| ID | Task | Status | Depends-On | Required Role(s) | Approver Role | Co-Signer Role | Break-Glass? |
|---|---|---|---|---|---|---|---|
| T-IDN-01 | Unit tests: 80%+ branch coverage on the aggregate | pending | — | identity.admin | identity.admin | — | — |
| T-IDN-02 | Integration tests: Testcontainers (PostgreSQL, Kafka, Redis) | pending | T-IDN-01 | identity.admin | identity.admin | — | — |
| T-IDN-03 | Contract tests: Producer Avro schemas pinned in CI | pending | T-IDN-02 | identity.admin | identity.admin | — | — |
| T-IDN-04 | E2E test per major user journey in `WORKFLOWS.md` | pending | T-IDN-03 | identity.admin | identity.admin | — | — |
### Phase 10 — Deployment

| ID | Task | Status | Depends-On | Required Role(s) | Approver Role | Co-Signer Role | Break-Glass? |
|---|---|---|---|---|---|---|---|
| T-IDN-01 | Kubernetes manifests: Deployment, Service, HPA, PDB | pending | — | identity.admin | identity.admin | — | — |
| T-IDN-02 | Pre-upgrade Job for migrations | pending | T-IDN-01 | identity.admin | identity.admin | — | — |
| T-IDN-03 | Resource limits per `DEPLOYMENT_ARCHITECTURE.md` | pending | T-IDN-02 | identity.admin | identity.admin | — | — |
| T-IDN-04 | Smoke test in staging before production rollout | pending | T-IDN-03 | identity.admin | identity.admin | — | — |
### Phase 7.0 — Cross-cutting: Guaranteed Rewards & Rating-Based Pricing


| ID | Task | Status | Depends-On | Required Role(s) | Approver Role | Co-Signer Role | Break-Glass? |
|---|---|---|---|---|---|---|---|
**Config host.** Host the new config-key families (trip.reward.*, pricing.rating_density.*, pricing.loyalty.frequent_rider.*, pricing.geo_overrides.*).

---

## Integration Map

### Sync Dependencies (outbound calls)
| Target | Endpoint | Purpose | Circuit Breaker |
|--------|----------|---------|----------------|
| `Keycloak` | per `INTEGRATION.md` | sync dependency | Yes |

### Events Published
| Event | Topic | Trigger | Key Consumers |
|-------|-------|---------|--------------|
| `identity.user.created` | derived from name | see INTEGRATION.md | see INTEGRATION.md |
| `identity.user.suspended` | derived from name | see INTEGRATION.md | see INTEGRATION.md |
| `identity.session.revoked` | derived from name | see INTEGRATION.md | see INTEGRATION.md |

### Events Consumed
| Event | Producer | Handler |
|-------|----------|---------|
| `customer.created` | see INTEGRATION.md | see INTEGRATION.md |
| `driver.created` | see INTEGRATION.md | see INTEGRATION.md |
| `courier.created` | see INTEGRATION.md | see INTEGRATION.md |
| `merchant.created` | see INTEGRATION.md | see INTEGRATION.md |
| `configuration.updated` | see INTEGRATION.md | see INTEGRATION.md |

---

## Acceptance Criteria
- [ ] All REST endpoints return correct status codes
- [ ] p99 latency within SLO target (T0 (99.99%))
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
| T-IDN-P76-01 | Register Conductor worker for `wf.onboarding.driver.v1` — Worker — identity_service_kyc_start + document_verify | pending | — | identity.admin | identity.admin | — | — |
| T-IDN-P76-02 | Register Conductor worker for `wf.onboarding.courier.v1` — Worker — identity_service_kyc_start + document_verify | pending | — | identity.admin | identity.admin | — | — |


---

## Role Mapping (back-reference)

This service's tasks map to platform roles per [`MASTER_TASK.md`](../../MASTER_TASK.md) 11 "Role Mapping (back-reference)". The columns `Required Role(s) | Approver Role | Co-Signer Role | Break-Glass?` added to every task table above come from that appendix.

| ID prefix | Required Role(s) | Approver Role | Co-Signer Role | Break-Glass? |
|---|---|---|---|---|
| T-IDN-NN (Phase 1-10) | per task | per task | per task | per task |
| T-IDN-P70-NN | platform.admin / pricing.admin / customer.admin | platform.admin | platform.super_admin | no (Phase 7.0 cross-cutting) |
| T-IDN-P75-NN | platform.admin / pricing.admin | platform.admin | platform.super_admin | no (Phase 7.5 Make-a-Deal) |
| T-IDN-P76-NN | platform.admin + Conductor UI role | platform.admin | platform.super_admin | yes (workflow worker registration) |

For the canonical SUPER_ADMIN preset (1 × `platform.super_admin` + 20 × `<service>.admin`), see [`shared/TIME_BOUNDED_ALIASES.md`](../../shared/TIME_BOUNDED_ALIASES.md) for time-bounded aliases and `admin-service/INTEGRATION.md` 1.13 for the canonical role list.
