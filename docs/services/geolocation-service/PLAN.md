# geolocation-service — Implementation Plan

**Domain:** Platform Foundation
**Tier:** 1
**Technology:** Go
**Criticality:** T1 (99.95%)
**DB Schema:** `geolocation`
**Cache:** Redis — geocode+place cache
**HPA:** CPU 70%, 2–6, p99 < 100ms

---

## Purpose

**Phase 1 — Platform Foundation.** This service is on the critical path; ship it before any consumer starts.

This PLAN.md is the source of truth for **how** `geolocation-service` is built. The 10-phase
backbone below mirrors the locked Phase 1-7 layout in `IMPLEMENTATION_PHASES.md`
plus the Phase 7 cross-cutting and Phase 7.5 Make-a-Deal addenda where this
service participates.

---

## Tasks

### Phase 1 — Database & Domain Model

| ID | Task | Status | Depends-On | Required Role(s) | Approver Role | Co-Signer Role | Break-Glass? |
|---|---|---|---|---|---|---|---|
| T-GEO-01 | Create schema `geolocation`: tables per `ERD.md` (partitioned by time/zone/hash per data shape) | pending | — | geolocation.admin | geolocation.admin | — | — |
| T-GEO-02 | Write Flyway/migrations (forward-only); install `pg_partman` if the parent table is time-partitioned | pending | T-GEO-01 | geolocation.admin | geolocation.admin | — | — |
| T-GEO-03 | Implement the aggregate root, immutability invariants, and append-only audit constraints | pending | T-GEO-02 | geolocation.admin | geolocation.admin | — | — |
| T-GEO-04 | Add `geolocation.outbox` and `geolocation.inbox` for reliable eventing | pending | T-GEO-03 | geolocation.outbox, geolocation.inbox | geolocation.outbox | — | — |
### Phase 2 — REST API

| ID | Task | Status | Depends-On | Required Role(s) | Approver Role | Co-Signer Role | Break-Glass? |
|---|---|---|---|---|---|---|---|
| T-GEO-01 | CRUD endpoints per `INTEGRATION.md` (versioned `/v1/...`) | pending | — | geolocation.admin | geolocation.admin | — | — |
| T-GEO-02 | Idempotency-Key middleware on every mutating route | pending | T-GEO-01 | geolocation.admin | geolocation.admin | — | — |
| T-GEO-03 | Pagination + filtering on every list endpoint | pending | T-GEO-02 | geolocation.admin | geolocation.admin | — | — |
| T-GEO-04 | Health endpoints: `/actuator/health`, `/ready`, `/started` | pending | T-GEO-03 | geolocation.admin | geolocation.admin | — | — |
### Phase 3 — Event Publishing

| ID | Task | Status | Depends-On | Required Role(s) | Approver Role | Co-Signer Role | Break-Glass? |
|---|---|---|---|---|---|---|---|
| T-GEO-01 | Transactional outbox + poller (200 ms interval, DLQ) | pending | — | geolocation.admin | geolocation.admin | — | — |
| T-GEO-02 | Publish events per the integration map below | pending | T-GEO-01 | geolocation.admin | geolocation.admin | — | — |
| T-GEO-03 | Avro schema registered in Schema Registry on first publish | pending | T-GEO-02 | geolocation.admin | geolocation.admin | — | — |
### Phase 4 — Event Consumption

| ID | Task | Status | Depends-On | Required Role(s) | Approver Role | Co-Signer Role | Break-Glass? |
|---|---|---|---|---|---|---|---|
| T-GEO-01 | Idempotent inbox; LSN/offset dedup window 7 days | pending | — | geolocation.admin | geolocation.admin | — | — |
| T-GEO-02 | Single consumer per partition; pause-on-error with backoff | pending | T-GEO-01 | geolocation.admin | geolocation.admin | — | — |
| T-GEO-03 | Dead-letter topic after N retries | pending | T-GEO-02 | geolocation.admin | geolocation.admin | — | — |
### Phase 5 — Caching

| ID | Task | Status | Depends-On | Required Role(s) | Approver Role | Co-Signer Role | Break-Glass? |
|---|---|---|---|---|---|---|---|
| T-GEO-01 | Redis — geocode+place cache | pending | — | geolocation.admin | geolocation.admin | — | — |
| T-GEO-02 | Push-invalidate on every write that affects the cache key | pending | T-GEO-01 | geolocation.admin | geolocation.admin | — | — |
| T-GEO-03 | Stampede protection on hot keys (single-flight) | pending | T-GEO-02 | geolocation.admin | geolocation.admin | — | — |
### Phase 6 — External Integrations

| ID | Task | Status | Depends-On | Required Role(s) | Approver Role | Co-Signer Role | Break-Glass? |
|---|---|---|---|---|---|---|---|
| T-GEO-01 | Sync dependencies: Map Provider | pending | — | geolocation.admin | geolocation.admin | — | — |
| T-GEO-02 | Circuit breakers on every outbound call (Resilience4j / polly) | pending | T-GEO-01 | geolocation.admin | geolocation.admin | — | — |
| T-GEO-03 | OAuth2 client credentials + mTLS for service-to-service | pending | T-GEO-02 | geolocation.admin | geolocation.admin | — | — |
| T-GEO-04 | HashiCorp Vault for DB credentials and signing keys | pending | T-GEO-03 | geolocation.admin | geolocation.admin | — | — |
### Phase 7 — Security

| ID | Task | Status | Depends-On | Required Role(s) | Approver Role | Co-Signer Role | Break-Glass? |
|---|---|---|---|---|---|---|---|
| T-GEO-01 | JWT bearer auth via Keycloak, realm `platform-internal` | pending | — | geolocation.admin | geolocation.admin | — | — |
| T-GEO-02 | Required scopes/roles per `INTEGRATION.md` | pending | T-GEO-01 | geolocation.admin | geolocation.admin | — | — |
| T-GEO-03 | `X-Audit-Reason` header required on admin mutations | pending | T-GEO-02 | geolocation.admin | geolocation.admin | — | — |
| T-GEO-04 | Field-level encryption for PII (driver license, payment method) | pending | T-GEO-03 | geolocation.admin | geolocation.admin | — | — |
### Phase 8 — Observability

| ID | Task | Status | Depends-On | Required Role(s) | Approver Role | Co-Signer Role | Break-Glass? |
|---|---|---|---|---|---|---|---|
| T-GEO-01 | Structured JSON logs with `correlation_id`, `user_id`, `tenant_id` | pending | — | geolocation.admin | geolocation.admin | — | — |
| T-GEO-02 | Metrics: RED per route + business counters specific to this service | pending | T-GEO-01 | geolocation.admin | geolocation.admin | — | — |
| T-GEO-03 | OpenTelemetry traces with child spans; long-poll spans open until response | pending | T-GEO-02 | geolocation.admin | geolocation.admin | — | — |
| T-GEO-04 | Alerts in Grafana: p99 latency, error rate, consumer lag | pending | T-GEO-03 | geolocation.admin | geolocation.admin | — | — |
### Phase 9 — Testing

| ID | Task | Status | Depends-On | Required Role(s) | Approver Role | Co-Signer Role | Break-Glass? |
|---|---|---|---|---|---|---|---|
| T-GEO-01 | Unit tests: 80%+ branch coverage on the aggregate | pending | — | geolocation.admin | geolocation.admin | — | — |
| T-GEO-02 | Integration tests: Testcontainers (PostgreSQL, Kafka, Redis) | pending | T-GEO-01 | geolocation.admin | geolocation.admin | — | — |
| T-GEO-03 | Contract tests: Producer Avro schemas pinned in CI | pending | T-GEO-02 | geolocation.admin | geolocation.admin | — | — |
| T-GEO-04 | E2E test per major user journey in `WORKFLOWS.md` | pending | T-GEO-03 | geolocation.admin | geolocation.admin | — | — |
### Phase 10 — Deployment

| ID | Task | Status | Depends-On | Required Role(s) | Approver Role | Co-Signer Role | Break-Glass? |
|---|---|---|---|---|---|---|---|
| T-GEO-01 | Kubernetes manifests: Deployment, Service, HPA, PDB | pending | — | geolocation.admin | geolocation.admin | — | — |
| T-GEO-02 | Pre-upgrade Job for migrations | pending | T-GEO-01 | geolocation.admin | geolocation.admin | — | — |
| T-GEO-03 | Resource limits per `DEPLOYMENT_ARCHITECTURE.md` | pending | T-GEO-02 | geolocation.admin | geolocation.admin | — | — |
| T-GEO-04 | Smoke test in staging before production rollout | pending | T-GEO-03 | geolocation.admin | geolocation.admin | — | — |
---

## Integration Map

### Sync Dependencies (outbound calls)
| Target | Endpoint | Purpose | Circuit Breaker |
|--------|----------|---------|----------------|
| `Map Provider` | per `INTEGRATION.md` | sync dependency | Yes |

### Events Published
| Event | Topic | Trigger | Key Consumers |
|-------|-------|---------|--------------|
| `geolocation.geocoded` | derived from name | see INTEGRATION.md | see INTEGRATION.md |
| `geolocation.eta.computed` | derived from name | see INTEGRATION.md | see INTEGRATION.md |

### Events Consumed
| Event | Producer | Handler |
|-------|----------|---------|


---

## Acceptance Criteria
- [ ] All REST endpoints return correct status codes
- [ ] p99 latency within SLO target (T1 (99.95%))
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
| T-GEO-NN (Phase 1-10) | per task | per task | per task | per task |
| T-GEO-P70-NN | platform.admin / pricing.admin / customer.admin | platform.admin | platform.super_admin | no (Phase 7.0 cross-cutting) |
| T-GEO-P75-NN | platform.admin / pricing.admin | platform.admin | platform.super_admin | no (Phase 7.5 Make-a-Deal) |
| T-GEO-P76-NN | platform.admin + Conductor UI role | platform.admin | platform.super_admin | yes (workflow worker registration) |

For the canonical SUPER_ADMIN preset (1 × `platform.super_admin` + 20 × `<service>.admin`), see [`shared/TIME_BOUNDED_ALIASES.md`](../../shared/TIME_BOUNDED_ALIASES.md) for time-bounded aliases and `admin-service/INTEGRATION.md` 1.13 for the canonical role list.
