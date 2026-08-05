# cart-service — Implementation Plan

**Domain:** Food Marketplace
**Tier:** 3
**Technology:** Kotlin + Spring Boot 4
**Criticality:** T2 (99.9% SLO)
**DB Schema:** `cart`
**Cache:** Redis — active cart (TTL 30m)
**HPA:** CPU 60%, 2–10, p99 < 200ms

---

## Purpose

`cart-service` is the canonical owner of the shopping cart aggregate — the customer's in-progress food order before checkout. It owns the cart lifecycle, items (with modifiers and add-ons), applied promotions, computed totals, and re-quotes on price or availability changes.

---

## Tasks

### Phase 1 — Database & Domain Model
- [ ] Create schema `cart`: tables `carts`, `cart_items`, `cart_item_modifiers`, `cart_item_addons`, `cart_promotions`, `outbox`, `inbox`
- [ ] Key columns: `carts(id UUID, customer_id UUID, branch_id UUID, restaurant_id UUID, state TEXT, subtotal_minor BIGINT, tip_minor BIGINT, total_minor BIGINT, currency TEXT, checkout_blocked BOOL, checkout_session_id UUID, created_at TIMESTAMPTZ, last_activity_at TIMESTAMPTZ)`
- [ ] Write Flyway migrations (forward-only)
- [ ] Implement `Cart` aggregate, state machine (active, checked_out, abandoned), repository

### Phase 2 — REST API
- [ ] `POST /v1/carts` — create cart (requires `customer` role, `Idempotency-Key`)
- [ ] `GET /v1/carts/{id}` — read cart with items, modifiers, promotions (Redis-cached 30s)
- [ ] `PATCH /v1/carts/{id}` — update tip or address ref
- [ ] `DELETE /v1/carts/{id}` — abandon cart
- [ ] `POST /v1/carts/{id}/items` — add item (re-quotes on success)
- [ ] `PATCH /v1/carts/{id}/items/{iid}` — update item quantity
- [ ] `DELETE /v1/carts/{id}/items/{iid}` — remove item
- [ ] `POST /v1/carts/{id}/promotions` — apply promotion
- [ ] `DELETE /v1/carts/{id}/promotions` — remove promotion
- [ ] `POST /v1/carts/{id}/re-quote` — re-quote (internal, `client_credentials`)
- [ ] `POST /v1/carts/{id}/checkout` — create checkout session
- [ ] `GET /v1/carts/by-customer/{customer_id}` — list active carts

### Phase 3 — Event Publishing
- [ ] Implement transactional outbox table
- [ ] Publish `cart.created.v1` → topic `cart.cart.created`
- [ ] Publish `cart.updated.v1` → topic `cart.cart.updated`
- [ ] Publish `cart.checked_out.v1` → topic `cart.cart.checked_out`
- [ ] Publish `cart.abandoned.v1` → topic `cart.cart.abandoned` (on 30min idle cron)
- [ ] Outbox poller (200ms interval, DLQ)

### Phase 4 — Event Consumption
- [ ] Implement inbox table for deduplication
- [ ] Consume `menu.item.price.changed.v1` → re-quote affected active carts; emit `cart.updated.v1`
- [ ] Consume `menu.item.unavailable.v1` → remove item from affected carts; re-quote; notify customer
- [ ] Consume `cart.item.unavailable.v1` (from `inventory-service`) → same as above
- [ ] Consume `restaurant.offline.v1` → set `checkout_blocked = true`; emit `cart.updated.v1`

### Phase 5 — Caching
- [ ] Redis: `cart:{id}` (TTL 30m) for fast cart reads
- [ ] Cache invalidation on every cart mutation
- [ ] Redis Spring Data for cart read-through cache

### Phase 6 — External Integrations
- [ ] `customer-service` — verify customer exists
- [ ] `menu-service` — verify product, price, modifiers, availability
- [ ] `branch-service` — verify branch is open
- [ ] `promotion-service` — validate and apply promo code
- [ ] `pricing-service` — request sub-quote
- [ ] `checkout-service` — create checkout session on `POST /checkout`
- [ ] `notification-service` — notify customer on item removal/price change
- [ ] Circuit breakers on all outbound calls

### Phase 7 — Security
- [ ] JWT bearer auth via Keycloak (Spring Security 7)
- [ ] Required scopes/roles: `customer` for cart CRUD; `client_credentials` for internal reads
- [ ] Resource-level ownership check: `cart.customer_id == JWT sub`
- [ ] Secrets via HashiCorp Vault

### Phase 8 — Observability
- [ ] Structured JSON logs with `correlation_id`, `cart_id`, `customer_id`, `state`
- [ ] Metrics: RED per route + `carts_created_total`, `carts_abandoned_total{reason}`, `carts_checked_out_total`, `cart_re_quote_total{reason}`, `cart_quote_seconds`
- [ ] OpenTelemetry traces with child spans per downstream call
- [ ] Health endpoints: `/actuator/health`, `/ready`, `/started`

### Phase 9 — Testing
- [ ] Unit tests: cart state machine, re-quote logic, abandonment cron
- [ ] Integration tests: Testcontainers (PostgreSQL, Kafka, Redis)
- [ ] E2E tests: full add-item, checkout flow; restaurant-offline block; abandonment

### Phase 10 — Deployment
- [ ] Kubernetes manifests: Deployment, Service, HPA (CPU 60%, 2–10 replicas), PDB
- [ ] Pre-upgrade Job for database migrations
- [ ] Resource limits per DEPLOYMENT_ARCHITECTURE.md

---

## Integration Map

### Sync Dependencies (outbound calls)
| Target | Endpoint | Purpose | Circuit Breaker |
|--------|----------|---------|----------------|
| `customer-service` | `GET /v1/customers/{id}` | Verify customer | Yes |
| `menu-service` | `GET /v1/menus/products/{id}` | Verify product/price/availability | Yes |
| `restaurant-service` | `GET /v1/restaurants/{id}` | Verify online status | Yes |
| `branch-service` | `GET /v1/branches/{id}` | Verify branch is open | Yes |
| `promotion-service` | `POST /v1/promotions/validate` | Validate and apply promo | Yes |
| `pricing-service` | `POST /v1/quote` | Sub-quote | Yes |
| `checkout-service` | `POST /v1/checkouts` | Create checkout session | Yes |
| `notification-service` | `POST /v1/notifications` | Notify customer | Yes |

### Events Published
| Event | Topic | Trigger | Key Consumers |
|-------|-------|---------|--------------|
| `cart.created.v1` | `cart.cart.created` | `POST /v1/carts` | `analytics-service`, `customer-service`, `audit-service` |
| `cart.updated.v1` | `cart.cart.updated` | Any item/promo/address change | `analytics-service`, `customer-service` |
| `cart.checked_out.v1` | `cart.cart.checked_out` | Checkout session created | `analytics-service`, `customer-service`, `audit-service` |
| `cart.abandoned.v1` | `cart.cart.abandoned` | 30 min idle | `analytics-service`, `customer-service` |

### Events Consumed
| Event | Producer | Handler |
|-------|----------|---------|
| `menu.item.price.changed.v1` | `menu-service` | Re-quote active carts; emit `cart.updated.v1`; notify customer |
| `menu.item.unavailable.v1` | `menu-service` | Remove item; re-quote; notify customer |
| `cart.item.unavailable.v1` | `inventory-service` | Remove item; re-quote; notify customer |
| `restaurant.offline.v1` | `restaurant-service` | Set checkout_blocked = true; notify customer |

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
