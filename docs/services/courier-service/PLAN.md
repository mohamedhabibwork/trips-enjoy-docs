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
- [ ] Create schema `courier`: tables `couriers`, `courier_documents`, `courier_eligibility`, `courier_shifts`, `courier_rating_history` (monthly partition), `outbox`, `inbox`
- [ ] Key columns: `couriers(id UUID, identity_id UUID UNIQUE, state TEXT, vehicle_type TEXT, rating DECIMAL, city_id TEXT, deleted_at TIMESTAMPTZ)`, `courier_documents(id UUID, courier_id UUID, type TEXT, expires_at DATE, status TEXT)`
- [ ] Write Flyway migrations (forward-only); column-level encryption for PII fields
- [ ] Implement `Courier` aggregate state machine (`pending_review → approved/rejected, approved → suspended/inactive, suspended → reinstated/disabled`)

### Phase 2 — REST API
- [ ] `GET /v1/couriers/{courier_id}` — get courier profile
- [ ] `POST /v1/couriers` — create courier (idempotent on `identity_id`)
- [ ] `PATCH /v1/couriers/{courier_id}` — update profile (self or admin)
- [ ] `GET /v1/couriers/{courier_id}/documents` — list documents
- [ ] `POST /v1/couriers/{courier_id}/documents` — upload document
- [ ] `PUT /v1/couriers/{courier_id}/vehicle-type` — set vehicle type
- [ ] `GET /v1/couriers/{courier_id}/eligibility` — per-city eligibility
- [ ] `POST /v1/couriers/{courier_id}/eligibility/cities/{city_id}` — request city eligibility
- [ ] `GET/POST/DELETE /v1/couriers/{courier_id}/shifts` — manage shift schedule
- [ ] `POST /v1/couriers/{courier_id}/approve` — approve (admin)
- [ ] `POST /v1/couriers/{courier_id}/suspend` — suspend (admin)
- [ ] `POST /v1/couriers/{courier_id}/reinstate` — reinstate (admin)
- [ ] `POST /v1/couriers/{courier_id}/disable` — disable (admin)
- [ ] `POST /v1/couriers/{courier_id}/erase` — GDPR erasure (admin)

### Phase 3 — Event Publishing
- [ ] Implement transactional outbox table
- [ ] Publish `courier.created.v1`, `courier.approved.v1`, `courier.rejected.v1`
- [ ] Publish `courier.suspended.v1`, `courier.reinstated.v1`, `courier.disabled.v1`, `courier.erased.v1`
- [ ] Publish `courier.shift.scheduled.v1`, `courier.shift.started.v1`, `courier.shift.ended.v1`
- [ ] Publish `courier.document.expiring.v1`, `courier.document.expired.v1`
- [ ] Outbox poller (200ms interval, DLQ)

### Phase 4 — Event Consumption
- [ ] Implement inbox table for deduplication
- [ ] Consume `identity.user.created.v1` → ensure courier row exists
- [ ] Consume `identity.user.suspended.v1` → mark courier suspended
- [ ] Consume `identity.user.disabled.v1` → mark courier disabled
- [ ] Consume `identity.user.reinstated.v1` → clear suspension
- [ ] Consume `identity.user.erased.v1` → GDPR erasure
- [ ] Consume `vehicle.registered.v1` → link to primary vehicle
- [ ] Consume `vehicle.insurance.expired.v1` → auto-suspend if no replacement
- [ ] Consume `review.aggregated.v1` → update courier rating snapshot
- [ ] Consume `configuration.updated.v1` → reload KYC rules, document expiry windows

### Phase 5 — Caching
- [ ] Redis: courier profile cache (TTL 5m, event-invalidated)
- [ ] Redis: eligibility projection per city
- [ ] Nightly cron: scan documents for expiring soon (30, 7, 1 day warnings)

### Phase 6 — External Integrations
- [ ] `identity-service` — read claims on creation
- [ ] `vehicle-service` — read vehicle metadata
- [ ] `geolocation-service` / `zone-service` — city lookup for eligibility
- [ ] KYC provider (e.g. Onfido) — document verification; credentials in Vault
- [ ] Background-check provider (e.g. Checkr) — credentials in Vault
- [ ] Circuit breakers on all outbound calls

### Phase 7 — Security
- [ ] JWT bearer auth via Keycloak (Spring Security 7)
- [ ] Required scopes/roles: self-service with `courier.read/write`; cross-courier reads require `courier.read.any`
- [ ] Column-level PII encryption (`pgcrypto`)
- [ ] GDPR erasure: anonymize PII, preserve `courier_id`
- [ ] Secrets via HashiCorp Vault

### Phase 8 — Observability
- [ ] Structured JSON logs with `correlation_id`, `courier_id`
- [ ] Metrics: RED per endpoint + `courier_state_distribution{state}`, `courier_kyc_documents_expiring_total{type,days}`, `courier_suspension_reasons_total{reason}`
- [ ] OpenTelemetry traces with child spans per downstream call
- [ ] Health endpoints: `/actuator/health`, `/ready`, `/started`

### Phase 9 — Testing
- [ ] Unit tests: state machine transitions, KYC expiry cron, GDPR erasure
- [ ] Integration tests: Testcontainers (PostgreSQL, Kafka, Redis)
- [ ] E2E tests: full onboarding flow, document expiry auto-suspension, GDPR erasure

### Phase 10 — Deployment
- [ ] Kubernetes manifests: Deployment, Service, HPA (CPU 60%, 2–5 replicas), PDB
- [ ] Pre-upgrade Job for database migrations
- [ ] Resource limits per DEPLOYMENT_ARCHITECTURE.md

---

## Integration Map

### Sync Dependencies (outbound calls)
| Target | Endpoint | Purpose | Circuit Breaker |
|--------|----------|---------|----------------|
| `identity-service` | `GET /v1/identities/{id}` | Read claims on creation | Yes |
| `vehicle-service` | `GET /v1/vehicles/{id}` | Read vehicle metadata | Yes |
| `geolocation-service` | city lookup | City for eligibility | Yes |
| KYC provider | verification API | Document KYC verification | Yes |

### Events Published
| Event | Topic | Trigger | Key Consumers |
|-------|-------|---------|--------------|
| `courier.created.v1` | `courier.created` | New courier row | `audit-service`, `analytics-service` |
| `courier.approved.v1` | `courier.approved` | Courier approved | `courier-dispatch-service`, `courier-tracking-service`, `notification-service` |
| `courier.suspended.v1` | `courier.suspended` | Courier suspended | `courier-dispatch-service`, `delivery-service`, `notification-service`, `fraud-risk-service` |
| `courier.shift.scheduled.v1` | `courier.shift.scheduled` | Shift scheduled | `notification-service` |
| `courier.document.expiring.v1` | `courier.document.expiring` | Document expiry warning | `notification-service` |
| `courier.document.expired.v1` | `courier.document.expired` | Document expired | `courier-dispatch-service`, `notification-service` |

### Events Consumed
| Event | Producer | Handler |
|-------|----------|---------|
| `identity.user.created.v1` | `identity-service` | Ensure courier row exists |
| `identity.user.suspended.v1` | `identity-service` | Mark courier suspended |
| `vehicle.registered.v1` | `vehicle-service` | Link to primary vehicle |
| `vehicle.insurance.expired.v1` | `vehicle-service` | Auto-suspend courier |
| `review.aggregated.v1` | `review-rating-service` | Update courier rating snapshot |
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
