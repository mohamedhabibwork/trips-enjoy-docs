# courier-dispatch-service — Implementation Plan

**Domain:** Food Delivery & Couriers
**Tier:** 3
**Technology:** Python + FastAPI + NumPy
**Criticality:** T1 (99.95% SLO)
**DB Schema:** `courier_dispatch`
**Cache:** Redis — match attempts (TTL 5m)
**HPA:** RPS, 2–8, p99 < 200ms

---

## Purpose

`courier-dispatch-service` is the brain of food-delivery matching. It receives food orders marked `ready` and decides which courier should pick them up, owning the courier assignment ledger — the sole system of record for "this courier is committed to this delivery."

---

## Tasks

### Phase 1 — Database & Domain Model
- [ ] Create schema `courier_dispatch`: tables `dispatches`, `offers`, `assignments`, `outbox`, `inbox`
- [ ] Key columns: `dispatches(id UUID, food_order_id UUID UNIQUE, status TEXT, offer_attempts INT, no_courier_at TIMESTAMPTZ, assigned_courier_id UUID, created_at TIMESTAMPTZ)`, `assignments(id UUID, dispatch_id UUID, courier_id UUID, committed_at TIMESTAMPTZ, status TEXT)`
- [ ] Write Alembic migrations (forward-only)
- [ ] Implement `Dispatch` aggregate, offer flow state machine, assignment ledger

### Phase 2 — REST API
- [ ] `POST /v1/dispatches` — start a dispatch for a `food_order_id` (service auth)
- [ ] `GET /v1/dispatches/{id}` — read dispatch attempt
- [ ] `GET /v1/dispatches?order_id=…` — list attempts for an order
- [ ] `POST /v1/dispatches/{id}/offers` — record an offer attempt (internal)
- [ ] `POST /v1/dispatches/{id}/accept` — courier accepts an offer
- [ ] `POST /v1/dispatches/{id}/reject` — courier rejects an offer
- [ ] `POST /v1/dispatches/{id}/cancel` — cancel a dispatch (compensate)
- [ ] `POST /v1/dispatches/{id}/reassign` — force reassignment (admin)
- [ ] `GET /v1/dispatches/metrics` — operational counters (admin)

### Phase 3 — Event Publishing
- [ ] Implement transactional outbox table
- [ ] Publish `delivery.courier.assigned.v1` → courier accepts offer, assignment committed
- [ ] Publish `delivery.dispatch.no_courier.v1` → offer window expires with no acceptance
- [ ] Publish `delivery.dispatch.offer.expired.v1` → individual courier offer window expires
- [ ] Publish `delivery.dispatch.reassigned.v1` → courier cancels/fails; re-offered
- [ ] Publish `courier_dispatch.audit.assignment_committed.v1` → internal audit
- [ ] Outbox poller (200ms interval, DLQ)

### Phase 4 — Event Consumption
- [ ] Implement inbox table for deduplication
- [ ] Consume `food.order.ready.v1` → enqueue dispatch job
- [ ] Consume `courier.availability.online.v1` → upsert courier to available pool
- [ ] Consume `courier.availability.offline.v1` → mark courier pool entry offline
- [ ] Consume `courier.location.updated.v1` → update pool ordering by distance
- [ ] Consume `delivery.courier.cancelled.v1` → enqueue reassignment
- [ ] Consume `configuration.updated.v1` → reload offer window/max attempts config

### Phase 5 — Caching
- [ ] Redis: available courier pool as geo-sorted set (`courier_pool:{city}:{zone}`)
- [ ] Redis: active match attempts with TTL 5m
- [ ] Redis: offer state per dispatch (`dispatch:offer:{dispatch_id}:{courier_id}`, TTL = offer_window_seconds)

### Phase 6 — External Integrations
- [ ] `courier-service` — `GET /v1/couriers/{id}` to enrich assignment records
- [ ] `courier-tracking-service` — `GET /v1/couriers/{id}/location` for last-known location
- [ ] Circuit breakers on both outbound calls

### Phase 7 — Security
- [ ] JWT bearer auth via `authlib` (Keycloak `platform-courier` realm for couriers, `platform-services` for S2S)
- [ ] Required scopes/roles: couriers may only act on their own offers; `courier.admin` for admin endpoints
- [ ] Secrets via HashiCorp Vault

### Phase 8 — Observability
- [ ] Structured JSON logs with `correlation_id`, `dispatch_id`, `order_id`, `courier_id`
- [ ] Metrics: `dispatches_started_total{result}`, `dispatch_offer_seconds{outcome}`, `dispatch_pool_size{city,zone}`, `dispatch_no_courier_total{city,zone}`, `dispatch_assignment_ledger_size`
- [ ] OpenTelemetry traces with child spans for pool search, offer, accept
- [ ] Health endpoints: `/health`, `/ready`, `/started`

### Phase 9 — Testing
- [ ] Unit tests: dispatch matching algorithm, offer flow state machine, pool management
- [ ] Integration tests: Testcontainers (PostgreSQL, Kafka, Redis)
- [ ] E2E tests: full dispatch, offer rejection, timeout no-courier, reassignment

### Phase 10 — Deployment
- [ ] Kubernetes manifests: Deployment, Service, HPA (RPS, 2–8 replicas), PDB
- [ ] Pre-upgrade Job for database migrations
- [ ] Resource limits per DEPLOYMENT_ARCHITECTURE.md

---

## Integration Map

### Sync Dependencies (outbound calls)
| Target | Endpoint | Purpose | Circuit Breaker |
|--------|----------|---------|----------------|
| `courier-service` | `GET /v1/couriers/{id}` | Enrich assignment records (vehicle, KYC) | Yes |
| `courier-tracking-service` | `GET /v1/couriers/{id}/location` | Last-known location for pool ordering | Yes |

### Events Published
| Event | Topic | Trigger | Key Consumers |
|-------|-------|---------|--------------|
| `delivery.courier.assigned.v1` | `delivery.courier.assigned` | Courier accepts offer | `delivery-service`, `food-order-service`, `notification-service`, `audit-service` |
| `delivery.dispatch.no_courier.v1` | `delivery.dispatch.no_courier` | Offer window expires, no acceptance | `food-order-service`, `notification-service`, `support-service` |
| `delivery.dispatch.offer.expired.v1` | `delivery.dispatch.offer.expired` | Individual courier offer timeout | `audit-service` |
| `delivery.dispatch.reassigned.v1` | `delivery.dispatch.reassigned` | Courier cancels/fails; re-offered | `notification-service`, `audit-service` |

### Events Consumed
| Event | Producer | Handler |
|-------|----------|---------|
| `food.order.ready.v1` | `food-order-service` | Enqueue dispatch job |
| `courier.availability.online.v1` | `courier-service` | Upsert courier to available pool |
| `courier.availability.offline.v1` | `courier-service` | Mark courier pool entry offline |
| `courier.location.updated.v1` | `courier-tracking-service` | Update pool ordering by distance |
| `delivery.courier.cancelled.v1` | `delivery-service` | Enqueue reassignment |
| `configuration.updated.v1` | `configuration-service` | Reload offer window/max attempts config |

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
