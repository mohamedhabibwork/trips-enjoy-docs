# restaurant-service — Implementation Plan

**Domain:** Food Marketplace
**Tier:** 3
**Technology:** Kotlin/Spring
**Criticality:** T2 (99.9%)
**DB Schema:** `restaurant`
**Cache:** Redis — restaurant profile
**HPA:** CPU 60%, 2–4, p99 < 100ms

---

## Purpose

**Phase 4 — Food Marketplace.** Begin only after Phase 2 merchant/identity are live.

This PLAN.md is the source of truth for **how** `restaurant-service` is built. The 10-phase
backbone below mirrors the locked Phase 1-7 layout in `IMPLEMENTATION_PHASES.md`
plus the Phase 7 cross-cutting and Phase 7.5 Make-a-Deal addenda where this
service participates.

---

## Tasks

### Phase 1 — Database & Domain Model

| ID | Task | Status | Depends-On | Required Role(s) | Approver Role | Co-Signer Role | Break-Glass? |
|---|---|---|---|---|---|---|---|
| T-RES-01 | Create schema `restaurant`: tables per `ERD.md` (partitioned by time/zone/hash per data shape) | pending | — | restaurant.admin | restaurant.admin | — | — |
| T-RES-02 | Write Flyway/migrations (forward-only); install `pg_partman` if the parent table is time-partitioned | pending | T-RES-01 | restaurant.admin | restaurant.admin | — | — |
| T-RES-03 | Implement the aggregate root, immutability invariants, and append-only audit constraints | pending | T-RES-02 | restaurant.admin | restaurant.admin | — | — |
| T-RES-04 | Add `restaurant.outbox` and `restaurant.inbox` for reliable eventing | pending | T-RES-03 | restaurant.outbox, restaurant.inbox | restaurant.outbox | — | — |
### Phase 2 — REST API

| ID | Task | Status | Depends-On | Required Role(s) | Approver Role | Co-Signer Role | Break-Glass? |
|---|---|---|---|---|---|---|---|
| T-RES-01 | CRUD endpoints per `INTEGRATION.md` (versioned `/v1/...`) | pending | — | restaurant.admin | restaurant.admin | — | — |
| T-RES-02 | Idempotency-Key middleware on every mutating route | pending | T-RES-01 | restaurant.admin | restaurant.admin | — | — |
| T-RES-03 | Pagination + filtering on every list endpoint | pending | T-RES-02 | restaurant.admin | restaurant.admin | — | — |
| T-RES-04 | Health endpoints: `/actuator/health`, `/ready`, `/started` | pending | T-RES-03 | restaurant.admin | restaurant.admin | — | — |
### Phase 3 — Event Publishing

| ID | Task | Status | Depends-On | Required Role(s) | Approver Role | Co-Signer Role | Break-Glass? |
|---|---|---|---|---|---|---|---|
| T-RES-01 | Transactional outbox + poller (200 ms interval, DLQ) | pending | — | restaurant.admin | restaurant.admin | — | — |
| T-RES-02 | Publish events per the integration map below | pending | T-RES-01 | restaurant.admin | restaurant.admin | — | — |
| T-RES-03 | Avro schema registered in Schema Registry on first publish | pending | T-RES-02 | restaurant.admin | restaurant.admin | — | — |
### Phase 4 — Event Consumption

| ID | Task | Status | Depends-On | Required Role(s) | Approver Role | Co-Signer Role | Break-Glass? |
|---|---|---|---|---|---|---|---|
| T-RES-01 | Idempotent inbox; LSN/offset dedup window 7 days | pending | — | restaurant.admin | restaurant.admin | — | — |
| T-RES-02 | Single consumer per partition; pause-on-error with backoff | pending | T-RES-01 | restaurant.admin | restaurant.admin | — | — |
| T-RES-03 | Dead-letter topic after N retries | pending | T-RES-02 | restaurant.admin | restaurant.admin | — | — |
### Phase 5 — Caching

| ID | Task | Status | Depends-On | Required Role(s) | Approver Role | Co-Signer Role | Break-Glass? |
|---|---|---|---|---|---|---|---|
| T-RES-01 | Redis — restaurant profile | pending | — | restaurant.admin | restaurant.admin | — | — |
| T-RES-02 | Push-invalidate on every write that affects the cache key | pending | T-RES-01 | restaurant.admin | restaurant.admin | — | — |
| T-RES-03 | Stampede protection on hot keys (single-flight) | pending | T-RES-02 | restaurant.admin | restaurant.admin | — | — |
### Phase 6 — External Integrations

| ID | Task | Status | Depends-On | Required Role(s) | Approver Role | Co-Signer Role | Break-Glass? |
|---|---|---|---|---|---|---|---|
| T-RES-01 | Sync dependencies: `restaurant-service` (merchant), geolocation-service | pending | — | restaurant.admin | restaurant.admin | — | — |
| T-RES-02 | Circuit breakers on every outbound call (Resilience4j / polly) | pending | T-RES-01 | restaurant.admin | restaurant.admin | — | — |
| T-RES-03 | OAuth2 client credentials + mTLS for service-to-service | pending | T-RES-02 | restaurant.admin | restaurant.admin | — | — |
| T-RES-04 | HashiCorp Vault for DB credentials and signing keys | pending | T-RES-03 | restaurant.admin | restaurant.admin | — | — |
### Phase 7 — Security

| ID | Task | Status | Depends-On | Required Role(s) | Approver Role | Co-Signer Role | Break-Glass? |
|---|---|---|---|---|---|---|---|
| T-RES-01 | JWT bearer auth via Keycloak, realm `platform-internal` | pending | — | restaurant.admin | restaurant.admin | — | — |
| T-RES-02 | Required scopes/roles per `INTEGRATION.md` | pending | T-RES-01 | restaurant.admin | restaurant.admin | — | — |
| T-RES-03 | `X-Audit-Reason` header required on admin mutations | pending | T-RES-02 | restaurant.admin | restaurant.admin | — | — |
| T-RES-04 | Field-level encryption for PII (driver license, payment method) | pending | T-RES-03 | restaurant.admin | restaurant.admin | — | — |
### Phase 8 — Observability

| ID | Task | Status | Depends-On | Required Role(s) | Approver Role | Co-Signer Role | Break-Glass? |
|---|---|---|---|---|---|---|---|
| T-RES-01 | Structured JSON logs with `correlation_id`, `user_id`, `tenant_id` | pending | — | restaurant.admin | restaurant.admin | — | — |
| T-RES-02 | Metrics: RED per route + business counters specific to this service | pending | T-RES-01 | restaurant.admin | restaurant.admin | — | — |
| T-RES-03 | OpenTelemetry traces with child spans; long-poll spans open until response | pending | T-RES-02 | restaurant.admin | restaurant.admin | — | — |
| T-RES-04 | Alerts in Grafana: p99 latency, error rate, consumer lag | pending | T-RES-03 | restaurant.admin | restaurant.admin | — | — |
### Phase 9 — Testing

| ID | Task | Status | Depends-On | Required Role(s) | Approver Role | Co-Signer Role | Break-Glass? |
|---|---|---|---|---|---|---|---|
| T-RES-01 | Unit tests: 80%+ branch coverage on the aggregate | pending | — | restaurant.admin | restaurant.admin | — | — |
| T-RES-02 | Integration tests: Testcontainers (PostgreSQL, Kafka, Redis) | pending | T-RES-01 | restaurant.admin | restaurant.admin | — | — |
| T-RES-03 | Contract tests: Producer Avro schemas pinned in CI | pending | T-RES-02 | restaurant.admin | restaurant.admin | — | — |
| T-RES-04 | E2E test per major user journey in `WORKFLOWS.md` | pending | T-RES-03 | restaurant.admin | restaurant.admin | — | — |
### Phase 10 — Deployment

| ID | Task | Status | Depends-On | Required Role(s) | Approver Role | Co-Signer Role | Break-Glass? |
|---|---|---|---|---|---|---|---|
| T-RES-01 | Kubernetes manifests: Deployment, Service, HPA, PDB | pending | — | restaurant.admin | restaurant.admin | — | — |
| T-RES-02 | Pre-upgrade Job for migrations | pending | T-RES-01 | restaurant.admin | restaurant.admin | — | — |
| T-RES-03 | Resource limits per `DEPLOYMENT_ARCHITECTURE.md` | pending | T-RES-02 | restaurant.admin | restaurant.admin | — | — |
| T-RES-04 | Smoke test in staging before production rollout | pending | T-RES-03 | restaurant.admin | restaurant.admin | — | — |
---

## Integration Map

### Sync Dependencies (outbound calls)
| Target | Endpoint | Purpose | Circuit Breaker |
|--------|----------|---------|----------------|
| ``restaurant-service` (merchant)` | per `INTEGRATION.md` | sync dependency | Yes |
| `geolocation-service` | per `INTEGRATION.md` | sync dependency | Yes |

### Events Published
| Event | Topic | Trigger | Key Consumers |
|-------|-------|---------|--------------|
| `restaurant.created` | derived from name | see INTEGRATION.md | see INTEGRATION.md |
| `restaurant.approved` | derived from name | see INTEGRATION.md | see INTEGRATION.md |
| `restaurant.online` | derived from name | see INTEGRATION.md | see INTEGRATION.md |
| `restaurant.offline` | derived from name | see INTEGRATION.md | see INTEGRATION.md |

### Events Consumed
| Event | Producer | Handler |
|-------|----------|---------|
| `merchant.approved` | see INTEGRATION.md | see INTEGRATION.md |

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
| T-RES-P76-01 | Register Conductor worker for `wf.refund.food_reject.v1` — Read-only consumer | pending | — | restaurant.admin | restaurant.admin | — | — |


---

## Role Mapping (back-reference)

This service's tasks map to platform roles per [`MASTER_TASK.md`](../../MASTER_TASK.md) 11 "Role Mapping (back-reference)". The columns `Required Role(s) | Approver Role | Co-Signer Role | Break-Glass?` added to every task table above come from that appendix.

| ID prefix | Required Role(s) | Approver Role | Co-Signer Role | Break-Glass? |
|---|---|---|---|---|
| T-RES-NN (Phase 1-10) | per task | per task | per task | per task |
| T-RES-P70-NN | platform.admin / pricing.admin / customer.admin | platform.admin | platform.super_admin | no (Phase 7.0 cross-cutting) |
| T-RES-P75-NN | platform.admin / pricing.admin | platform.admin | platform.super_admin | no (Phase 7.5 Make-a-Deal) |
| T-RES-P76-NN | platform.admin + Conductor UI role | platform.admin | platform.super_admin | yes (workflow worker registration) |

For the canonical SUPER_ADMIN preset (1 × `platform.super_admin` + 20 × `<service>.admin`), see [`shared/TIME_BOUNDED_ALIASES.md`](../shared/TIME_BOUNDED_ALIASES.md) for time-bounded aliases and `admin-service/INTEGRATION.md` 1.13 for the canonical role list.
