# wallet-service — Implementation Plan

**Domain:** Financial Core
**Tier:** 3
**Technology:** Kotlin/Spring
**Criticality:** T1 (99.95%)
**DB Schema:** `wallet`
**Cache:** Redis — balance snapshot
**HPA:** CPU 60%, 2–5, p99 < 100ms

---

## Purpose

**Phase 3.** Coordinate start with the platform team.

This PLAN.md is the source of truth for **how** `wallet-service` is built. The 10-phase
backbone below mirrors the locked Phase 1-7 layout in `IMPLEMENTATION_PHASES.md`
plus the Phase 7 cross-cutting and Phase 7.5 Make-a-Deal addenda where this
service participates.

---

## Tasks

### Phase 1 — Database & Domain Model
- [ ] Create schema `wallet`: tables per `ERD.md` (partitioned by time/zone/hash per data shape)
- [ ] Write Flyway/migrations (forward-only); install `pg_partman` if the parent table is time-partitioned
- [ ] Implement the aggregate root, immutability invariants, and append-only audit constraints
- [ ] Add `wallet.outbox` and `wallet.inbox` for reliable eventing

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
- [ ] Redis — balance snapshot
- [ ] Push-invalidate on every write that affects the cache key
- [ ] Stampede protection on hot keys (single-flight)

### Phase 6 — External Integrations
- [ ] Sync dependencies: payment-service
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

**Customer credit consumer.** Idempotency key: `trip:{trip_id}:reward:user:grant`. Credit/debit the customer wallet for the user-side journey.

---

## Integration Map

### Sync Dependencies (outbound calls)
| Target | Endpoint | Purpose | Circuit Breaker |
|--------|----------|---------|----------------|
| `payment-service` | per `INTEGRATION.md` | sync dependency | Yes |

### Events Published
| Event | Topic | Trigger | Key Consumers |
|-------|-------|---------|--------------|
| `wallet.credited` | derived from name | see INTEGRATION.md | see INTEGRATION.md |
| `wallet.debited` | derived from name | see INTEGRATION.md | see INTEGRATION.md |
| `wallet.held` | derived from name | see INTEGRATION.md | see INTEGRATION.md |
| `wallet.released` | derived from name | see INTEGRATION.md | see INTEGRATION.md |

### Events Consumed
| Event | Producer | Handler |
|-------|----------|---------|
| `payment.captured` | see INTEGRATION.md | see INTEGRATION.md |
| `trip.reward.granted` | see INTEGRATION.md | see INTEGRATION.md |
| `trip.reward.reversed` | see INTEGRATION.md | see INTEGRATION.md |
| `trip.reward.granted.v1` | see INTEGRATION.md | see INTEGRATION.md |
| `trip.reward.reversed.v1` | see INTEGRATION.md | see INTEGRATION.md |

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
