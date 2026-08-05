# trip-service — Implementation Plan

**Domain:** Ride-Hailing
**Tier:** 4
**Technology:** Kotlin/Spring
**Criticality:** T0 (99.99%)
**DB Schema:** `trip`
**Cache:** Redis — active trip state
**HPA:** CPU 70%, 3–8, p99 < 80ms

---

## Purpose

**Phase 3 — Ride-Hailing.** Begin once Phase 3 pricing + dispatch are live.

This PLAN.md is the source of truth for **how** `trip-service` is built. The 10-phase
backbone below mirrors the locked Phase 1-7 layout in `IMPLEMENTATION_PHASES.md`
plus the Phase 7 cross-cutting and Phase 7.5 Make-a-Deal addenda where this
service participates.

---

## Tasks

### Phase 1 — Database & Domain Model
- [ ] Create schema `trip`: tables per `ERD.md` (partitioned by time/zone/hash per data shape)
- [ ] Write Flyway/migrations (forward-only); install `pg_partman` if the parent table is time-partitioned
- [ ] Implement the aggregate root, immutability invariants, and append-only audit constraints
- [ ] Add `trip.outbox` and `trip.inbox` for reliable eventing

### Phase 2 — REST API
- [ ] CRUD endpoints per `INTEGRATION.md` (versioned `/v1/...`)
- [ ] Idempotency-Key middleware on every mutating route
- [ ] Pagination + filtering on every list endpoint
- [ ] Health endpoints: `/actuator/health`, `/ready`, `/started`

### Phase 3 — Event Publishing
- [ ] Transactional outbox + poller (200 ms interval, DLQ)
- [ ] Publish events per the integration map below
- [ ] Avro schema registered in Schema Registry on first publish

### Phase 4 — Event Consumption
- [ ] Idempotent inbox; LSN/offset dedup window 7 days
- [ ] Single consumer per partition; pause-on-error with backoff
- [ ] Dead-letter topic after N retries

### Phase 5 — Caching
- [ ] Redis — active trip state
- [ ] Push-invalidate on every write that affects the cache key
- [ ] Stampede protection on hot keys (single-flight)

### Phase 6 — External Integrations
- [ ] Sync dependencies: driver-service, courier-service, ride-request-service, eta-routing-service
- [ ] Circuit breakers on every outbound call (Resilience4j / polly)
- [ ] OAuth2 client credentials + mTLS for service-to-service
- [ ] HashiCorp Vault for DB credentials and signing keys

### Phase 7 — Security
- [ ] JWT bearer auth via Keycloak, realm `platform-internal`
- [ ] Required scopes/roles per `INTEGRATION.md`
- [ ] `X-Audit-Reason` header required on admin mutations
- [ ] Field-level encryption for PII (driver license, payment method)

### Phase 8 — Observability
- [ ] Structured JSON logs with `correlation_id`, `user_id`, `tenant_id`
- [ ] Metrics: RED per route + business counters specific to this service
- [ ] OpenTelemetry traces with child spans; long-poll spans open until response
- [ ] Alerts in Grafana: p99 latency, error rate, consumer lag

### Phase 9 — Testing
- [ ] Unit tests: 80%+ branch coverage on the aggregate
- [ ] Integration tests: Testcontainers (PostgreSQL, Kafka, Redis)
- [ ] Contract tests: Producer Avro schemas pinned in CI
- [ ] E2E test per major user journey in `WORKFLOWS.md`

### Phase 10 — Deployment
- [ ] Kubernetes manifests: Deployment, Service, HPA, PDB
- [ ] Pre-upgrade Job for migrations
- [ ] Resource limits per `DEPLOYMENT_ARCHITECTURE.md`
- [ ] Smoke test in staging before production rollout


### Phase 7.0 — Cross-cutting: Guaranteed Rewards & Rating-Based Pricing

**Guaranteed rewards producer.** POST /v1/trips/{id}/reward/{re-evaluate|reverse} + GET .../reward.
- Idempotency key: `trip:{trip_id}:reward:{grant|reversal}`.
- Tables: trip.trip_reward (append-only, REVOKE UPDATE/DELETE), trip.trip_reward_reversal (append-only).

---

## Integration Map

### Sync Dependencies (outbound calls)
| Target | Endpoint | Purpose | Circuit Breaker |
|--------|----------|---------|----------------|
| `driver-service` | per `INTEGRATION.md` | sync dependency | Yes |
| `courier-service` | per `INTEGRATION.md` | sync dependency | Yes |
| `ride-request-service` | per `INTEGRATION.md` | sync dependency | Yes |
| `eta-routing-service` | per `INTEGRATION.md` | sync dependency | Yes |

### Events Published
| Event | Topic | Trigger | Key Consumers |
|-------|-------|---------|--------------|
| `trip.started` | derived from name | see INTEGRATION.md | see INTEGRATION.md |
| `trip.arrived` | derived from name | see INTEGRATION.md | see INTEGRATION.md |
| `trip.completed` | derived from name | see INTEGRATION.md | see INTEGRATION.md |
| `trip.cancelled` | derived from name | see INTEGRATION.md | see INTEGRATION.md |
| `trip.reward.granted.v1` | derived from name | see INTEGRATION.md | see INTEGRATION.md |
| `trip.reward.reversed.v1` | derived from name | see INTEGRATION.md | see INTEGRATION.md |

### Events Consumed
| Event | Producer | Handler |
|-------|----------|---------|
| `ride.request.matched` | see INTEGRATION.md | see INTEGRATION.md |
| `driver.location.updated` | see INTEGRATION.md | see INTEGRATION.md |

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
