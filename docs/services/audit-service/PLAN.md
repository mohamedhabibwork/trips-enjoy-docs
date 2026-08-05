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
- [ ] Create schema `audit`: tables `events` (append-only, partitioned by month), `litigation_holds`, `outbox`, `inbox`
- [ ] Key columns: `events(id UUID, event_id UUID UNIQUE, event_name TEXT, occurred_at TIMESTAMPTZ, producer TEXT, tenant_id TEXT, aggregate_type TEXT, aggregate_id UUID, subject_type TEXT, subject_id UUID, hash TEXT, prev_hash TEXT, data JSONB)`
- [ ] Write Flyway migrations (forward-only); DB grants: no UPDATE/DELETE on `audit.events`
- [ ] Implement `AuditEvent` aggregate (append-only), hash chain computation

### Phase 2 — REST API
- [ ] `POST /v1/audit/search` — search audit log (requires `audit.read`, `reason` param)
- [ ] `GET /v1/audit/events/{id}` — read single event including hash and prev_hash
- [ ] `GET /v1/audit/verify/{id}` — verify hash chain up to event (requires `audit.admin`)
- [ ] `POST /v1/audit/litigation-hold` — create litigation hold (requires `audit.admin`, `Idempotency-Key`)

### Phase 3 — Event Publishing
- [ ] Implement transactional outbox table
- [ ] Publish `audit.export.completed.v1` → topic `audit.export.completed` (nightly export success)
- [ ] Publish `audit.consumer.lag.v1` → topic `audit.consumer.lag` (periodic, every minute)
- [ ] Publish `audit.hash_chain.verified.v1` → topic `audit.hash_chain.verified` (daily verification job)
- [ ] Publish `audit.security.compliance_violation.v1` → topic `platform.audit.security`
- [ ] Publish `audit.security.break_glass_used.v1` → topic `platform.audit.security`
- [ ] Publish `audit.retention.purge_completed.v1` → topic `platform.audit.retention`
- [ ] Outbox poller (200ms interval, DLQ)

### Phase 4 — Event Consumption
- [ ] Implement inbox table for deduplication (keyed by `event_id`)
- [ ] Consume `admin.action.performed.v1` → append immutable row
- [ ] Consume `payment.*` events → append immutable rows (7-year retention)
- [ ] Consume `wallet.*`, `ledger.posted.v1` → append immutable rows (7-year retention)
- [ ] Consume `trip.*`, `ride.request.*`, `dispatch.*` → append immutable rows
- [ ] Consume `food.order.*`, `delivery.*` → append immutable rows
- [ ] Consume `identity.user.*`, `customer.*`, `driver.*`, `courier.*` → append immutable rows
- [ ] Consume `merchant.*`, `restaurant.*`, `configuration.updated.v1`, `feature_flag.updated.v1` → append
- [ ] Consume `promotion.*`, `loyalty.*`, `review.*`, `tax.*`, `pricing.quote.created.v1` → append
- [ ] Consume `notification.*`, `comms.*`, `support.ticket.*`, `fraud.*`, `file.*`, `zone.*` → append

### Phase 5 — Caching
- [ ] No caching (read path is direct from DB)
- [ ] In-process daily verification result cache

### Phase 6 — External Integrations
- [ ] AWS S3 — nightly export to `s3://trips-enjoy-platform-audit/audit/exports/<yyyy>/<mm>/<dd>/`
- [ ] HashiCorp Vault — DB credentials
- [ ] Circuit breakers not required (no synchronous outbound)

### Phase 7 — Security
- [ ] JWT bearer auth via Keycloak (Spring Security 7), realm `platform-internal`
- [ ] Required scopes/roles: `audit.read` for compliance, `audit.admin` for security
- [ ] Column-level encryption for sensitive PII fields (`pgcrypto`)
- [ ] No UPDATE/DELETE grants on `audit.events` table at DB level
- [ ] Secrets via HashiCorp Vault

### Phase 8 — Observability
- [ ] Structured JSON logs with `correlation_id`
- [ ] Metrics: RED per route + `audit_events_ingested_total{topic}`, `audit_consumer_lag{topic,partition}`, `audit_export_seconds`, `audit_hash_chain_status`
- [ ] OpenTelemetry traces with child spans per event for DB insert, hash computation
- [ ] Health endpoints: `/actuator/health`, `/ready`, `/started`

### Phase 9 — Testing
- [ ] Unit tests: hash chain computation, inbox deduplication, retention policy
- [ ] Integration tests: Testcontainers (PostgreSQL, Kafka)
- [ ] E2E tests: ingest event, search, verify hash chain, litigation hold

### Phase 10 — Deployment
- [ ] Kubernetes manifests: Deployment, Service, HPA (Kafka consumer lag, 2–8 replicas), PDB
- [ ] Pre-upgrade Job for database migrations
- [ ] Resource limits per DEPLOYMENT_ARCHITECTURE.md

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
