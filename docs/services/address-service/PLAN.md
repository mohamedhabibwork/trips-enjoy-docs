# address-service — Implementation Plan

**Domain:** Identity & Profile  
**Tier:** 2 (depends on geolocation-service)  
**Technology:** Kotlin + Spring Boot 4  
**Criticality:** T2 (99.9% SLO)  
**DB Schema:** `address`  
**Cache:** Redis — by-user list (TTL 1h)  
**HPA:** CPU 60%, 2–10 replicas, p99 < 200ms

---

## Purpose

Stores saved addresses for customers and drivers (home, work, favourites). Geocodes and normalises addresses via `geolocation-service`. Source of truth for `address_id`.

---

## Tasks

### Phase 1 — Database & Domain Model
- [ ] Create schema `address`: tables `addresses`, `address_tags`, `outbox`
- [ ] Columns: `id` (UUIDv7), `user_id`, `label`, `raw_input`, `normalized_line1`, `lat`, `lng`, `city`, `country`, `plus_code`, `is_default`, `deleted_at`
- [ ] Unique constraint: `(user_id, label)`
- [ ] Write Flyway migrations
- [ ] Implement `Address` aggregate + `AddressRepository`

### Phase 2 — REST API
- [ ] `GET /v1/addresses` — list saved addresses for caller (paged)
- [ ] `GET /v1/addresses/{id}` — read single address
- [ ] `POST /v1/addresses` — create & geocode via `geolocation-service`
- [ ] `PATCH /v1/addresses/{id}` — update label / default flag
- [ ] `DELETE /v1/addresses/{id}` — soft delete
- [ ] `POST /v1/addresses/{id}/set-default` — mark as default
- [ ] `POST /v1/addresses/search` — fuzzy search by label or raw input

### Phase 3 — Geocoding Integration
- [ ] On `POST`, call `geolocation-service POST /v1/geocode` with `raw_input`
- [ ] Persist `lat`, `lng`, `normalized_line1`, `city`, `country` from response
- [ ] Circuit breaker on `geolocation-service`; return 503 on open circuit
- [ ] Retry with exponential backoff (3 attempts, 100ms–1s)

### Phase 4 — Caching
- [ ] Cache address list per `user_id` in Redis (TTL 1h)
- [ ] Invalidate cache on create, update, delete
- [ ] Cache single address by `id` (TTL 1h)

### Phase 5 — Events
- [ ] Implement transactional outbox table
- [ ] Publish `address.created.v1` on create
- [ ] Publish `address.updated.v1` on update
- [ ] Publish `address.deleted.v1` on soft delete
- [ ] Outbox poller (200ms interval)

### Phase 6 — Security
- [ ] JWT bearer auth via Keycloak (Spring Security 7)
- [ ] Ownership check: caller can only CRUD their own addresses
- [ ] Admin override: `admin-service` role `platform.ops` can read any
- [ ] Rate limit at gateway: 20 writes/min per user

### Phase 7 — Observability
- [ ] Structured JSON logs: `correlation_id`, `user_id`, `address_id`, `latency_ms`
- [ ] Metrics: `address_created_total`, `address_geocode_seconds`, `address_cache_hit_ratio`
- [ ] OpenTelemetry traces; child span for geocode call
- [ ] Health: `/actuator/health`, `/actuator/ready`

### Phase 8 — Deployment
- [ ] Kubernetes `Deployment`, `Service`, `HPA` manifests
- [ ] Pre-upgrade `Job` for Flyway migrations
- [ ] Liveness: `/actuator/health`; Readiness: DB + Redis + geolocation-service reachable

---

## Integration Map

### Sync Dependencies (outbound calls)
| Target | Endpoint | Purpose | Circuit Breaker |
|--------|----------|---------|----------------|
| `geolocation-service` | `POST /v1/geocode` | geocode raw address | Yes |

### Events Published
| Event | Topic | Trigger | Consumers |
|-------|-------|---------|-----------|
| `address.created.v1` | `address.created` | address saved | checkout-service, analytics |
| `address.updated.v1` | `address.updated` | address modified | checkout-service |
| `address.deleted.v1` | `address.deleted` | soft delete | checkout-service |

### Events Consumed
_None_

---

## Acceptance Criteria
- [ ] Geocode + persist roundtrip < 300ms p99
- [ ] Cache hit rate > 90% for active users
- [ ] Zero data leakage across user boundaries
- [ ] All events published via outbox (no event loss)

---

## Related Docs
- [README](README.md) · [BRD](BRD.md) · [SRS](SRS.md) · [ERD](ERD.md) · [INTEGRATION](INTEGRATION.md) · [WORKFLOWS](WORKFLOWS.md) · [TECH](TECH.md)
- [Master Plan](../../MASTER_SERVICE_PLAN.md)
