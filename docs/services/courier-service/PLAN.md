# courier-service — Implementation Plan

**Domain:** Identity & Profile
**Tier:** 1
**Technology:** Kotlin + Spring Boot 4
**Criticality:** T1 (99.95% SLO)
**DB Schema:** `courier`
**Cache:** Redis — claim cache (TTL 5m)
**HPA:** CPU 60%, 2–5, p99 < 200ms

---

## Purpose

`courier-service` is the platform's source of truth for the courier profile — KYC documents, vehicle type, shift schedule, and the courier state machine (`pending_review`, `approved`, `rejected`, `suspended`, `inactive`, `erased`). It is the canonical source of `courier_id` for the platform.

---

## Tasks

### Phase 1 — Database & Domain Model

| ID | Task | Status | Depends-On | Required Role(s) | Approver Role | Co-Signer Role | Break-Glass? |
|---|---|---|---|---|---|---|---|
| T-COUR-01 | Create schema `courier`: tables `couriers`, `courier_documents`, `courier_eligibility`, `courier_shifts`, `courier_rating_history` (monthly partition), `outbox`, `inbox` | pending | — | courier.admin | courier.admin | — | — |
| T-COUR-02 | Key columns: `couriers(id UUID, identity_id UUID UNIQUE, state TEXT, vehicle_type TEXT, rating DECIMAL, city_id TEXT, deleted_at TIMESTAMPTZ)`, `courier_documents(id UUID, courier_id UUID, type TEXT, expires_at DATE, status TEXT)` | pending | T-COUR-01 | courier.admin | courier.admin | — | — |
| T-COUR-03 | Write Flyway migrations (forward-only); column-level encryption for PII fields | pending | T-COUR-02 | courier.admin | courier.admin | — | — |
| T-COUR-04 | Implement `Courier` aggregate state machine (`pending_review → approved/rejected, approved → suspended/inactive, suspended → reinstated/disabled`) | pending | T-COUR-03 | courier.admin | courier.admin | — | — |
### Phase 2 — REST API

| ID | Task | Status | Depends-On | Required Role(s) | Approver Role | Co-Signer Role | Break-Glass? |
|---|---|---|---|---|---|---|---|
| T-COUR-01 | `GET /v1/couriers/{courier_id}` — get courier profile | pending | — | courier.admin | courier.admin | — | — |
| T-COUR-02 | `POST /v1/couriers` — create courier (idempotent on `identity_id`) | pending | T-COUR-01 | courier.admin | courier.admin | — | — |
| T-COUR-03 | `PATCH /v1/couriers/{courier_id}` — update profile (self or admin) | pending | T-COUR-02 | courier.admin | courier.admin | — | — |
| T-COUR-04 | `GET /v1/couriers/{courier_id}/documents` — list documents | pending | T-COUR-03 | courier.admin | courier.admin | — | — |
| T-COUR-05 | `POST /v1/couriers/{courier_id}/documents` — upload document | pending | T-COUR-04 | courier.admin | courier.admin | — | — |
| T-COUR-06 | `PUT /v1/couriers/{courier_id}/vehicle-type` — set vehicle type | pending | T-COUR-05 | courier.admin | courier.admin | — | — |
| T-COUR-07 | `GET /v1/couriers/{courier_id}/eligibility` — per-city eligibility | pending | T-COUR-06 | courier.admin | courier.admin | — | — |
| T-COUR-08 | `POST /v1/couriers/{courier_id}/eligibility/cities/{city_id}` — request city eligibility | pending | T-COUR-07 | courier.admin | courier.admin | — | — |
| T-COUR-09 | `GET/POST/DELETE /v1/couriers/{courier_id}/shifts` — manage shift schedule | pending | T-COUR-08 | courier.admin | courier.admin | — | — |
| T-COUR-10 | `POST /v1/couriers/{courier_id}/approve` — approve (admin) | pending | T-COUR-09 | courier.admin | courier.admin | — | — |
| T-COUR-11 | `POST /v1/couriers/{courier_id}/suspend` — suspend (admin) | pending | T-COUR-10 | courier.admin | courier.admin | — | — |
| T-COUR-12 | `POST /v1/couriers/{courier_id}/reinstate` — reinstate (admin) | pending | T-COUR-11 | courier.admin | courier.admin | — | — |
| T-COUR-13 | `POST /v1/couriers/{courier_id}/disable` — disable (admin) | pending | T-COUR-12 | courier.admin | courier.admin | — | — |
| T-COUR-14 | `POST /v1/couriers/{courier_id}/erase` — GDPR erasure (admin) | pending | T-COUR-13 | courier.admin | courier.admin | — | — |
### Phase 3 — Event Publishing

| ID | Task | Status | Depends-On | Required Role(s) | Approver Role | Co-Signer Role | Break-Glass? |
|---|---|---|---|---|---|---|---|
| T-COUR-01 | Implement transactional outbox table | pending | — | courier.admin | courier.admin | — | — |
| T-COUR-02 | Publish `courier.created.v1`, `courier.approved.v1`, `courier.rejected.v1` | pending | T-COUR-01 | courier.admin | courier.admin | — | — |
| T-COUR-03 | Publish `courier.suspended.v1`, `courier.reinstated.v1`, `courier.disabled.v1`, `courier.erased.v1` | pending | T-COUR-02 | courier.admin | courier.admin | — | — |
| T-COUR-04 | Publish `courier.shift.scheduled.v1`, `courier.shift.started.v1`, `courier.shift.ended.v1` | pending | T-COUR-03 | courier.admin | courier.admin | — | — |
| T-COUR-05 | Publish `courier.document.expiring.v1`, `courier.document.expired.v1` | pending | T-COUR-04 | courier.admin | courier.admin | — | — |
| T-COUR-06 | Outbox poller (200ms interval, DLQ) | pending | T-COUR-05 | courier.admin | courier.admin | — | — |
### Phase 4 — Event Consumption

| ID | Task | Status | Depends-On | Required Role(s) | Approver Role | Co-Signer Role | Break-Glass? |
|---|---|---|---|---|---|---|---|
| T-COUR-01 | Implement inbox table for deduplication | pending | — | courier.admin | courier.admin | — | — |
| T-COUR-02 | Consume `identity.user.created.v1` → ensure courier row exists | pending | T-COUR-01 | courier.admin | courier.admin | — | — |
| T-COUR-03 | Consume `identity.user.suspended.v1` → mark courier suspended | pending | T-COUR-02 | courier.admin | courier.admin | — | — |
| T-COUR-04 | Consume `identity.user.disabled.v1` → mark courier disabled | pending | T-COUR-03 | courier.admin | courier.admin | — | — |
| T-COUR-05 | Consume `identity.user.reinstated.v1` → clear suspension | pending | T-COUR-04 | courier.admin | courier.admin | — | — |
| T-COUR-06 | Consume `identity.user.erased.v1` → GDPR erasure | pending | T-COUR-05 | courier.admin | courier.admin | — | — |
| T-COUR-07 | Consume `vehicle.registered.v1` → link to primary vehicle | pending | T-COUR-06 | courier.admin | courier.admin | — | — |
| T-COUR-08 | Consume `vehicle.insurance.expired.v1` → auto-suspend if no replacement | pending | T-COUR-07 | courier.admin | courier.admin | — | — |
| T-COUR-09 | Consume `review.aggregated.v1` → update courier rating snapshot | pending | T-COUR-08 | courier.admin | courier.admin | — | — |
| T-COUR-10 | Consume `configuration.updated.v1` → reload KYC rules, document expiry windows | pending | T-COUR-09 | courier.admin | courier.admin | — | — |
### Phase 5 — Caching

| ID | Task | Status | Depends-On | Required Role(s) | Approver Role | Co-Signer Role | Break-Glass? |
|---|---|---|---|---|---|---|---|
| T-COUR-01 | Redis: courier profile cache (TTL 5m, event-invalidated) | pending | — | courier.admin | courier.admin | — | — |
| T-COUR-02 | Redis: eligibility projection per city | pending | T-COUR-01 | courier.admin | courier.admin | — | — |
| T-COUR-03 | Nightly cron: scan documents for expiring soon (30, 7, 1 day warnings) | pending | T-COUR-02 | courier.admin | courier.admin | — | — |
### Phase 6 — External Integrations

| ID | Task | Status | Depends-On | Required Role(s) | Approver Role | Co-Signer Role | Break-Glass? |
|---|---|---|---|---|---|---|---|
| T-COUR-01 | `identity-service` — read claims on creation | pending | — | courier.admin | courier.admin | — | — |
| T-COUR-02 | ``driver-service` (vehicles)` — read vehicle metadata | pending | T-COUR-01 | courier.admin | courier.admin | — | — |
| T-COUR-03 | `geolocation-service` / ``geolocation-service` (zones)` — city lookup for eligibility | pending | T-COUR-02 | courier.admin | courier.admin | — | — |
| T-COUR-04 | KYC provider (e.g. Onfido) — document verification; credentials in Vault | pending | T-COUR-03 | courier.admin | courier.admin | — | — |
| T-COUR-05 | Background-check provider (e.g. Checkr) — credentials in Vault | pending | T-COUR-04 | courier.admin | courier.admin | — | — |
| T-COUR-06 | Circuit breakers on all outbound calls | pending | T-COUR-05 | courier.admin | courier.admin | — | — |
### Phase 7 — Security

| ID | Task | Status | Depends-On | Required Role(s) | Approver Role | Co-Signer Role | Break-Glass? |
|---|---|---|---|---|---|---|---|
| T-COUR-01 | JWT bearer auth via Keycloak (Spring Security 7) | pending | — | courier.admin | courier.admin | — | — |
| T-COUR-02 | Required scopes/roles: self-service with `courier.read/write`; cross-courier reads require `courier.read.any` | pending | T-COUR-01 | courier.read.any | courier.read.any | — | — |
| T-COUR-03 | Column-level PII encryption (`pgcrypto`) | pending | T-COUR-02 | courier.admin | courier.admin | — | — |
| T-COUR-04 | GDPR erasure: anonymize PII, preserve `courier_id` | pending | T-COUR-03 | courier.admin | courier.admin | — | — |
| T-COUR-05 | Secrets via HashiCorp Vault | pending | T-COUR-04 | courier.admin | courier.admin | — | — |
### Phase 8 — Observability

| ID | Task | Status | Depends-On | Required Role(s) | Approver Role | Co-Signer Role | Break-Glass? |
|---|---|---|---|---|---|---|---|
| T-COUR-01 | Structured JSON logs with `correlation_id`, `courier_id` | pending | — | courier.admin | courier.admin | — | — |
| T-COUR-02 | Metrics: RED per endpoint + `courier_state_distribution{state}`, `courier_kyc_documents_expiring_total{type,days}`, `courier_suspension_reasons_total{reason}` | pending | T-COUR-01 | courier.admin | courier.admin | — | — |
| T-COUR-03 | OpenTelemetry traces with child spans per downstream call | pending | T-COUR-02 | courier.admin | courier.admin | — | — |
| T-COUR-04 | Health endpoints: `/actuator/health`, `/ready`, `/started` | pending | T-COUR-03 | courier.admin | courier.admin | — | — |
### Phase 9 — Testing

| ID | Task | Status | Depends-On | Required Role(s) | Approver Role | Co-Signer Role | Break-Glass? |
|---|---|---|---|---|---|---|---|
| T-COUR-01 | Unit tests: state machine transitions, KYC expiry cron, GDPR erasure | pending | — | courier.admin | courier.admin | — | — |
| T-COUR-02 | Integration tests: Testcontainers (PostgreSQL, Kafka, Redis) | pending | T-COUR-01 | courier.admin | courier.admin | — | — |
| T-COUR-03 | E2E tests: full onboarding flow, document expiry auto-suspension, GDPR erasure | pending | T-COUR-02 | courier.admin | courier.admin | — | — |
### Phase 10 — Deployment

| ID | Task | Status | Depends-On | Required Role(s) | Approver Role | Co-Signer Role | Break-Glass? |
|---|---|---|---|---|---|---|---|
| T-COUR-01 | Kubernetes manifests: Deployment, Service, HPA (CPU 60%, 2–5 replicas), PDB | pending | — | courier.admin | courier.admin | — | — |
| T-COUR-02 | Pre-upgrade Job for database migrations | pending | T-COUR-01 | courier.admin | courier.admin | — | — |
| T-COUR-03 | Resource limits per DEPLOYMENT_ARCHITECTURE.md | pending | T-COUR-02 | courier.admin | courier.admin | — | — |
---

## Integration Map

### Sync Dependencies (outbound calls)
| Target | Endpoint | Purpose | Circuit Breaker |
|--------|----------|---------|----------------|
| `identity-service` | `GET /v1/identities/{id}` | Read claims on creation | Yes |
| ``driver-service` (vehicles)` | `GET /v1/vehicles/{id}` | Read vehicle metadata | Yes |
| `geolocation-service` | city lookup | City for eligibility | Yes |
| KYC provider | verification API | Document KYC verification | Yes |

### Events Published
| Event | Topic | Trigger | Key Consumers |
|-------|-------|---------|--------------|
| `courier.created.v1` | `courier.created` | New courier row | `audit-service`, ``reporting-service` (data lake)` |
| `courier.approved.v1` | `courier.approved` | Courier approved | ``courier-service` (dispatch)`, ``courier-service` (tracking)`, `notification-service` |
| `courier.suspended.v1` | `courier.suspended` | Courier suspended | ``courier-service` (dispatch)`, ``courier-service` (delivery)`, `notification-service`, `fraud-risk-service` |
| `courier.shift.scheduled.v1` | `courier.shift.scheduled` | Shift scheduled | `notification-service` |
| `courier.document.expiring.v1` | `courier.document.expiring` | Document expiry warning | `notification-service` |
| `courier.document.expired.v1` | `courier.document.expired` | Document expired | ``courier-service` (dispatch)`, `notification-service` |

### Events Consumed
| Event | Producer | Handler |
|-------|----------|---------|
| `identity.user.created.v1` | `identity-service` | Ensure courier row exists |
| `identity.user.suspended.v1` | `identity-service` | Mark courier suspended |
| `vehicle.registered.v1` | ``driver-service` (vehicles)` | Link to primary vehicle |
| `vehicle.insurance.expired.v1` | ``driver-service` (vehicles)` | Auto-suspend courier |
| `review.aggregated.v1` | ``trip-service` / `food-order-service` / `search-service` (review projections)` | Update courier rating snapshot |
| `configuration.updated.v1` | `configuration-service` | Reload KYC rules and expiry windows |

---

## Acceptance Criteria
- [ ] All REST endpoints return correct status codes
- [ ] p99 latency within SLO targets (< 200ms)
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
| T-COUR-P76-01 | Register Conductor worker for `wf.onboarding.courier.v1` — Orchestrator + activation worker | pending | — | courier.admin | courier.admin | — | — |


---

## Role Mapping (back-reference)

This service's tasks map to platform roles per [`MASTER_TASK.md`](../../MASTER_TASK.md) 11 "Role Mapping (back-reference)". The columns `Required Role(s) | Approver Role | Co-Signer Role | Break-Glass?` added to every task table above come from that appendix.

| ID prefix | Required Role(s) | Approver Role | Co-Signer Role | Break-Glass? |
|---|---|---|---|---|
| T-COUR-NN (Phase 1-10) | per task | per task | per task | per task |
| T-COUR-P70-NN | platform.admin / pricing.admin / customer.admin | platform.admin | platform.super_admin | no (Phase 7.0 cross-cutting) |
| T-COUR-P75-NN | platform.admin / pricing.admin | platform.admin | platform.super_admin | no (Phase 7.5 Make-a-Deal) |
| T-COUR-P76-NN | platform.admin + Conductor UI role | platform.admin | platform.super_admin | yes (workflow worker registration) |

For the canonical SUPER_ADMIN preset (1 × `platform.super_admin` + 20 × `<service>.admin`), see [`shared/TIME_BOUNDED_ALIASES.md`](../shared/TIME_BOUNDED_ALIASES.md) for time-bounded aliases and `admin-service/INTEGRATION.md` 1.13 for the canonical role list.
