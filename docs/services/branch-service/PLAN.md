# branch-service — Implementation Plan

**Domain:** Food Marketplace
**Tier:** 2
**Technology:** Kotlin + Spring Boot 4
**Criticality:** T1 (99.95% SLO)
**DB Schema:** `branch`
**Cache:** Redis — country/region tree (TTL 24h)
**HPA:** CPU 60%, 2–5, p99 < 100ms

---

## Purpose

`branch-service` is the canonical owner of the branch aggregate — a physical location of a restaurant. It owns the branch's address, geocoded coordinates, weekly opening hours, special hours, prep capacity, busy state, and temporary closure status.

---

## Tasks

### Phase 1 — Database & Domain Model
- [ ] Create schema `branch`: tables `branches`, `branch_hours`, `branch_special_hours`, `branch_temporary_closures`, `outbox`, `inbox`
- [ ] Key columns: `branches(id UUID, restaurant_id UUID, name TEXT, slug TEXT UNIQUE, state TEXT, location POINT, timezone TEXT, phone TEXT, email TEXT, prep_capacity INT, busy BOOL, deleted_at TIMESTAMPTZ)`
- [ ] Write Flyway migrations (forward-only); PostGIS extension for `location` column
- [ ] Implement `Branch` aggregate, state machine (open, busy, temporarily_closed, closed), repository

### Phase 2 — REST API
- [ ] `POST /v1/branches` — create branch (requires `merchant_owner`, `Idempotency-Key`)
- [ ] `GET /v1/branches/{id}` — read branch with hours, special hours, busy state
- [ ] `PATCH /v1/branches/{id}` — update profile fields
- [ ] `PUT /v1/branches/{id}/hours` — replace weekly hours
- [ ] `POST /v1/branches/{id}/special-hours` / `DELETE .../special-hours/{sid}` — manage holidays
- [ ] `POST /v1/branches/{id}/busy` / `DELETE .../busy` — mark/clear busy
- [ ] `POST /v1/branches/{id}/temporary-closure` / `DELETE .../temporary-closure` — set/clear closure
- [ ] `POST /v1/branches/{id}/close` — permanent close (admin, break-glass)
- [ ] `POST /v1/branches/{id}/open` — re-open after temporary closure
- [ ] `GET /v1/branches` — list branches (admin/search)
- [ ] `GET /v1/branches/by-restaurant/{restaurant_id}` — list for a restaurant
- [ ] `GET /v1/branches/{id}/open` — fast open flag (Redis-cached 30s)
- [ ] `GET /v1/branches/{id}/busy` — fast busy flag (Redis-cached 15s)
- [ ] `GET /v1/branches/{id}/prep-capacity` — read prep capacity

### Phase 3 — Event Publishing
- [ ] Implement transactional outbox table
- [ ] Publish `branch.created.v1` → topic `branch.branch.created`
- [ ] Publish `branch.updated.v1` → topic `branch.branch.updated`
- [ ] Publish `branch.hours.changed.v1` → topic `branch.branch.hours.changed`
- [ ] Publish `branch.busy.v1` → topic `branch.branch.busy`
- [ ] Publish `branch.closed.v1` → topic `branch.branch.closed`
- [ ] Publish `branch.temporary_closure.v1` → topic `branch.branch.temporary_closure`
- [ ] Outbox poller (200ms interval, DLQ)

### Phase 4 — Event Consumption
- [ ] Implement inbox table for deduplication
- [ ] Consume `restaurant.created.v1` → log only (parent eligible for branches)
- [ ] Consume `restaurant.suspended.v1` → cascade temporary closure to all non-terminal branches
- [ ] Consume `restaurant.reinstated.v1` → clear parent_suspended temporary closure; set state = open
- [ ] Consume `restaurant.closed.v1` → close all non-terminal branches; emit `branch.closed.v1`
- [ ] Consume `zone.updated.v1` → recheck each non-terminal branch location against serving zones; auto-close if out of zone

### Phase 5 — Caching
- [ ] Redis: `branch:open:{id}` (TTL 30s) for fast open-flag lookup
- [ ] Redis: `branch:busy:{id}` (TTL 15s) for fast busy-flag lookup
- [ ] Cache invalidation on busy toggle, hours change, temporary closure

### Phase 6 — External Integrations
- [ ] `restaurant-service` — verify parent restaurant is approved before branch creation
- [ ] `geolocation-service` — geocode branch address (synchronous on create, cache result)
- [ ] `zone-service` — verify branch is within a serving zone
- [ ] `configuration-service` — read hours/capacity defaults
- [ ] `notification-service` — trigger lifecycle messages
- [ ] Circuit breakers on all outbound calls

### Phase 7 — Security
- [ ] JWT bearer auth via Keycloak (Spring Security 7)
- [ ] Required scopes/roles: `merchant_owner`, `merchant_ops`, `restaurant_staff`, `platform_admin`
- [ ] Resource-level ownership checks (`branch.restaurant_id` belongs to caller)
- [ ] HMAC-SHA256 signature for permanent close (break-glass)
- [ ] Secrets via HashiCorp Vault

### Phase 8 — Observability
- [ ] Structured JSON logs with `correlation_id`, `branch_id`, `restaurant_id`, `state`
- [ ] Metrics: RED per route + `branches_created_total{country}`, `branches_open_total`, `branches_busy_total`, `branches_temporary_closure_total{reason}`, `branch_geocode_seconds`, `branch_open_lookups_total{cache_hit}`
- [ ] OpenTelemetry traces with child spans per downstream call
- [ ] Health endpoints: `/actuator/health`, `/ready`, `/started`

### Phase 9 — Testing
- [ ] Unit tests: branch state machine, hours computation, cascade logic
- [ ] Integration tests: Testcontainers (PostgreSQL with PostGIS, Kafka, Redis)
- [ ] E2E tests: create branch, busy toggle, zone-out auto-closure, restaurant suspension cascade

### Phase 10 — Deployment
- [ ] Kubernetes manifests: Deployment, Service, HPA (CPU 60%, 2–5 replicas), PDB
- [ ] Pre-upgrade Job for database migrations
- [ ] Resource limits per DEPLOYMENT_ARCHITECTURE.md

---

## Integration Map

### Sync Dependencies (outbound calls)
| Target | Endpoint | Purpose | Circuit Breaker |
|--------|----------|---------|----------------|
| `restaurant-service` | `GET /v1/restaurants/{id}` | Verify parent restaurant is approved | Yes |
| `geolocation-service` | `GET /v1/geocode` | Geocode branch address | Yes |
| `zone-service` | `POST /v1/zones/contains` | Check branch is within serving zone | Yes |
| `configuration-service` | `GET /v1/configurations/{key}` | Read hours/capacity defaults | Yes |
| `identity-service` | `GET /v1/users/{kc_sub}` | Verify caller subject | Yes |
| `notification-service` | `POST /v1/notifications` | Trigger lifecycle messages | Yes |
| `food-order-service` | `GET /v1/orders/in-flight?branch_id=` | Count in-flight for capacity | Yes |

### Events Published
| Event | Topic | Trigger | Key Consumers |
|-------|-------|---------|--------------|
| `branch.created.v1` | `branch.branch.created` | `POST /v1/branches` | `menu-service`, `cart-service`, `courier-dispatch-service`, `search-service`, `audit-service` |
| `branch.updated.v1` | `branch.branch.updated` | Profile change | `cart-service`, `courier-dispatch-service`, `search-service`, `audit-service` |
| `branch.hours.changed.v1` | `branch.branch.hours.changed` | Hours/special hours change | `cart-service`, `courier-dispatch-service`, `search-service`, `audit-service` |
| `branch.busy.v1` | `branch.branch.busy` | Busy toggle | `courier-dispatch-service`, `cart-service`, `audit-service` |
| `branch.closed.v1` | `branch.branch.closed` | Permanent close | `menu-service`, `cart-service`, `notification-service`, `audit-service` |
| `branch.temporary_closure.v1` | `branch.branch.temporary_closure` | Temporary closure toggle | `cart-service`, `courier-dispatch-service`, `audit-service` |

### Events Consumed
| Event | Producer | Handler |
|-------|----------|---------|
| `restaurant.created.v1` | `restaurant-service` | Log only — parent eligible |
| `restaurant.suspended.v1` | `restaurant-service` | Cascade temporary closure to all non-terminal branches |
| `restaurant.reinstated.v1` | `restaurant-service` | Clear parent_suspended closure; set state = open |
| `restaurant.closed.v1` | `restaurant-service` | Close all non-terminal branches |
| `zone.updated.v1` | `zone-service` | Recheck branch locations; auto-close if out of zone |

---

## Acceptance Criteria
- [ ] All REST endpoints return correct status codes
- [ ] p99 latency within SLO targets (< 100ms)
- [ ] All events published reliably via outbox pattern
- [ ] Zero data leakage across service boundaries
- [ ] 80%+ unit test coverage

---

## Related Docs
- [README](README.md) · [BRD](BRD.md) · [SRS](SRS.md) · [ERD](ERD.md) · [INTEGRATION](INTEGRATION.md) · [WORKFLOWS](WORKFLOWS.md) · [TECH](TECH.md)
- [Master Plan](../../MASTER_SERVICE_PLAN.md)
