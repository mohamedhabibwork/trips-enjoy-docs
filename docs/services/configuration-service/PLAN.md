# configuration-service — Implementation Plan

**Domain:** Platform Foundation
**Tier:** 1
**Technology:** Kotlin + Spring Boot 4
**Criticality:** T1 (99.95% SLO)
**DB Schema:** `configuration`
**Cache:** Redis — long-poll / push-invalidate
**HPA:** CPU 60%, 2–5, p99 < 50ms

---

## Purpose

`configuration-service` is the platform's single source of truth for business rules and numerical values (fares, fees, taxes, zones, ride types, eligibility thresholds). Every other service reads its operating parameters from this service at startup or via long-poll/event push, enabling operators to change business rules without redeploying any service.

---

## Tasks

### Phase 1 — Database & Domain Model
- [ ] Create schema `configuration`: tables `documents` (partitioned by scope_type hash), `history` (partitioned by month), `snapshots`, `outbox`, `inbox`
- [ ] Key columns: `documents(id UUID, key TEXT, scope_type TEXT, scope_id TEXT, value JSONB, version INT, active BOOL, created_by UUID, created_at TIMESTAMPTZ)`
- [ ] Write Flyway migrations (forward-only)
- [ ] Implement `ConfigDocument` aggregate, hierarchical scope resolution, version immutability

### Phase 2 — REST API
- [ ] `GET /v1/configurations` — list keys (paged, filtered)
- [ ] `GET /v1/configurations/{key}` — read latest resolved value
- [ ] `GET /v1/configurations/{key}/versions` — read version history
- [ ] `GET /v1/configurations/{key}/versions/{version}` — read specific version
- [ ] `POST /v1/configurations` — create new key (admin, `X-Audit-Reason`)
- [ ] `PUT /v1/configurations/{key}/versions` — create new version (admin, `X-Audit-Reason`)
- [ ] `POST /v1/configurations/{key}/rollback` — revert to prior version (admin)
- [ ] `GET /v1/configurations/stream` — long-poll update stream
- [ ] `GET /v1/configurations/snapshot` — bulk read of a service's known keys
- [ ] `GET /v1/channels/{channel}/configurations` — filtered client subset (mobile)

### Phase 3 — Event Publishing
- [ ] Implement transactional outbox table
- [ ] Publish `configuration.updated.v1` → every service (cache invalidation)
- [ ] Publish `configuration.rolled_back.v1` → every service
- [ ] Publish `configuration.key.deprecated.v1` → consumer services depending on deprecated key
- [ ] Publish `configuration.snapshot.exported.v1` → `reporting-service`, `audit-service`
- [ ] Outbox poller (200ms interval, DLQ)

### Phase 4 — Event Consumption
- [ ] No domain events consumed (source of truth)
- [ ] Optionally consume `customer.segment.changed.v1` → invalidate per-user override caches

### Phase 5 — Caching
- [ ] Redis: `config:{key}` hot cache (TTL 5min, push-invalidate on every write)
- [ ] Long-poll connection registry (in-process)
- [ ] Atomic in-memory config swap for hot-reload in consumers

### Phase 6 — External Integrations
- [ ] `identity-service` — validate admin token for write endpoints
- [ ] HashiCorp Vault — DB credentials, JWT signing key
- [ ] AWS S3 — version snapshots (`s3://trips-enjoy-platform-audit/configuration/snapshots/...`)
- [ ] Circuit breakers on `identity-service` outbound call

### Phase 7 — Security
- [ ] JWT bearer auth via Keycloak (Spring Security 7), realm `platform-internal`
- [ ] Required scopes/roles: `config.admin` for writes; `bearer` for reads
- [ ] `X-Audit-Reason` header required on all mutations
- [ ] HMAC-SHA256 request signing for production rollouts and mass rollbacks
- [ ] Secrets via HashiCorp Vault

### Phase 8 — Observability
- [ ] Structured JSON logs with `correlation_id`, `user_id`, `key`, `version`
- [ ] Metrics: RED per route + `config_writes_total{key,scope_type}`, `config_reads_total{key,cache_hit}`, `config_longpoll_connections`
- [ ] OpenTelemetry traces with child spans; long-poll spans open until response or timeout
- [ ] Health endpoints: `/actuator/health`, `/ready`, `/started`

### Phase 9 — Testing
- [ ] Unit tests: scope resolution hierarchy, version immutability, rollback logic
- [ ] Integration tests: Testcontainers (PostgreSQL, Kafka, Redis)
- [ ] E2E tests: create version, long-poll update stream, rollback, snapshot export

### Phase 10 — Deployment
- [ ] Kubernetes manifests: Deployment, Service, HPA (CPU 60% + long-poll connections > 1000, 2–5 replicas), PDB
- [ ] Pre-upgrade Job for database migrations
- [ ] Resource limits per DEPLOYMENT_ARCHITECTURE.md

---

## Integration Map

### Sync Dependencies (outbound calls)
| Target | Endpoint | Purpose | Circuit Breaker |
|--------|----------|---------|----------------|
| `identity-service` | token validation | Validate admin JWT for write endpoints | Yes |

### Events Published
| Event | Topic | Trigger | Key Consumers |
|-------|-------|---------|--------------|
| `configuration.updated.v1` | `configuration.updated` | Any new version commit | Every service (cache invalidation) |
| `configuration.rolled_back.v1` | `configuration.rolled_back` | Explicit rollback | Every service |
| `configuration.key.deprecated.v1` | `configuration.key.deprecated` | Key marked deprecated | Consumer services |
| `configuration.snapshot.exported.v1` | `configuration.snapshot.exported` | Snapshot job writes to S3 | `reporting-service`, `audit-service` |

### Events Consumed
| Event | Producer | Handler |
|-------|----------|---------|
| `customer.segment.changed.v1` (optional) | `customer-service` | Invalidate per-user override caches |

---

## Acceptance Criteria
- [ ] All REST endpoints return correct status codes
- [ ] p99 latency within SLO targets (< 50ms on cache hit)
- [ ] All events published reliably via outbox pattern
- [ ] Zero data leakage across service boundaries
- [ ] 80%+ unit test coverage

---

## Related Docs
- [README](README.md) · [BRD](BRD.md) · [SRS](SRS.md) · [ERD](ERD.md) · [INTEGRATION](INTEGRATION.md) · [WORKFLOWS](WORKFLOWS.md) · [TECH](TECH.md)
- [Master Plan](../../MASTER_SERVICE_PLAN.md)
