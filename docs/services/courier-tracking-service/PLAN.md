# courier-tracking-service — Implementation Plan

**Domain:** Food Delivery & Couriers
**Tier:** 2
**Technology:** Go + chi (WebSocket)
**Criticality:** T1 (99.95% SLO)
**DB Schema:** `courier_tracking`
**Cache:** Redis — courier geo index, last-N trail
**HPA:** RPS, 3–30, p99 < 5ms

---

## Purpose

`courier-tracking-service` is the high-frequency location stream for couriers. It ingests position pings at up to 5 Hz per courier, persists the current location and trail, and emits a curated `courier.location.updated.v1` stream at 1 Hz for dispatch, delivery, and ETA services.

---

## Tasks

### Phase 1 — Database & Domain Model
- [ ] Create schema `courier_tracking`: tables `current_locations` (one row per courier, UPSERT), `locations` (range-partitioned by day), `outbox`, `inbox`
- [ ] Key columns: `current_locations(courier_id UUID PRIMARY KEY, lat DECIMAL, lng DECIMAL, accuracy FLOAT, updated_at TIMESTAMPTZ, stale BOOL)`, `locations(id UUID, courier_id UUID, lat DECIMAL, lng DECIMAL, recorded_at TIMESTAMPTZ)` — PostGIS geometry column
- [ ] Write golang-migrate migrations (forward-only); PostGIS extension
- [ ] Implement upsert logic for current location and trail insert

### Phase 2 — REST API
- [ ] `POST /v1/couriers/{id}/location` — ingest a location ping (courier auth)
- [ ] `GET /v1/couriers/{id}/location` — read last-known location (service auth, Redis-cached)
- [ ] `GET /v1/couriers/{id}/trail?from=…&to=…` — recent location trail (service/admin)
- [ ] `GET /v1/locations/recent?city_id=…&bbox=…` — nearby couriers in bounding box
- [ ] `GET /v1/health/metrics` — operational counters (admin)

### Phase 3 — Event Publishing
- [ ] Implement transactional outbox table
- [ ] Publish `courier.location.updated.v1` → curated stream at 1 Hz per online courier
- [ ] Publish `courier_tracking.audit.location_ingested.v1` → sampled 1/1000 (audit)
- [ ] Outbox poller (200ms interval, DLQ)

### Phase 4 — Event Consumption
- [ ] Implement inbox table for deduplication
- [ ] Consume `courier.availability.online.v1` → mark courier as live; init current_locations row
- [ ] Consume `courier.availability.offline.v1` → mark courier offline; stop emitting curated events
- [ ] Consume `configuration.updated.v1` → reload curated rate, stale threshold

### Phase 5 — Caching
- [ ] Redis GEO: `courier_geo:{city_id}` — Redis GEO sorted set of online courier positions
- [ ] Redis: `courier:location:{courier_id}` — last-known position (TTL 5min) for synchronous reads
- [ ] Stale detection: mark couriers with no ping in 60s as stale; suppress curated stream

### Phase 6 — External Integrations
- [ ] `courier-service` — `GET /v1/couriers/{id}` to enrich curated stream (vehicle type, KYC)
- [ ] Circuit breakers on outbound calls

### Phase 7 — Security
- [ ] JWT bearer auth via `coreos/go-oidc v3`
- [ ] Required scopes/roles: couriers may only ping their own location; `client_credentials` for service reads
- [ ] GPS location treated as PII; short retention (30 days); trail partitions dropped automatically
- [ ] Secrets via HashiCorp Vault

### Phase 8 — Observability
- [ ] Structured JSON logs with `correlation_id`, `courier_id`, `city_id`
- [ ] Metrics: `courier_location_ingested_total{city_id}`, `courier_location_curated_emitted_total{city_id}`, `courier_location_stale_count{city_id}`, `courier_location_pool_size{city_id}`
- [ ] OpenTelemetry traces (sampled 1/1000 for ingest; 100% for errors)
- [ ] Health endpoints: `/health`, `/ready`, `/started`

### Phase 9 — Testing
- [ ] Unit tests: upsert logic, curated rate limiting, stale detection
- [ ] Integration tests: Testcontainers (PostgreSQL with PostGIS, Kafka, Redis)
- [ ] E2E tests: ping ingestion, trail query, stale detection, curated stream rate

### Phase 10 — Deployment
- [ ] Kubernetes manifests: Deployment, Service, HPA (RPS, 3–30 replicas), PDB
- [ ] Pre-upgrade Job for database migrations
- [ ] Resource limits per DEPLOYMENT_ARCHITECTURE.md

---

## Integration Map

### Sync Dependencies (outbound calls)
| Target | Endpoint | Purpose | Circuit Breaker |
|--------|----------|---------|----------------|
| `courier-service` | `GET /v1/couriers/{id}` | Enrich curated stream with vehicle/KYC | Yes |

### Events Published
| Event | Topic | Trigger | Key Consumers |
|-------|-------|---------|--------------|
| `courier.location.updated.v1` | `courier.location.updated` | Curated 1 Hz per online courier | `courier-dispatch-service`, `delivery-service`, `eta-routing-service` |
| `courier_tracking.audit.location_ingested.v1` | `courier_tracking.audit` | Every ping (sampled 1/1000) | `audit-service` |

### Events Consumed
| Event | Producer | Handler |
|-------|----------|---------|
| `courier.availability.online.v1` | `courier-service` | Mark courier as live; init current_locations row |
| `courier.availability.offline.v1` | `courier-service` | Mark courier offline; stop curated stream |
| `configuration.updated.v1` | `configuration-service` | Reload curated rate and stale threshold |

---

## Acceptance Criteria
- [ ] All REST endpoints return correct status codes
- [ ] p99 latency within SLO targets (< 5ms)
- [ ] All events published reliably via outbox pattern
- [ ] Zero data leakage across service boundaries
- [ ] 80%+ unit test coverage

---

## Related Docs
- [README](README.md) · [BRD](BRD.md) · [SRS](SRS.md) · [ERD](ERD.md) · [INTEGRATION](INTEGRATION.md) · [WORKFLOWS](WORKFLOWS.md) · [TECH](TECH.md)
- [Master Plan](../../MASTER_SERVICE_PLAN.md)
