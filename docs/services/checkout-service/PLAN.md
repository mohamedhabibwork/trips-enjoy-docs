# checkout-service — Implementation Plan

**Domain:** Food Marketplace
**Tier:** 3
**Technology:** Kotlin + Spring Boot 4 + jOOQ
**Criticality:** T1 (99.95% SLO)
**DB Schema:** `checkout`
**Cache:** Redis — idempotency, distributed lock
**HPA:** CPU 60%, 3–15, p99 < 500ms

---

## Purpose

`checkout-service` is the canonical owner of the checkout session aggregate — the customer's pre-payment state. It bridges the cart and payment authorization: it validates cart contents, freezes the final quote, authorizes payment via `payment-service`, and creates the food order in `food-order-service` on success.

---

## Tasks

### Phase 1 — Database & Domain Model
- [ ] Create schema `checkout`: tables `checkout_sessions`, `checkout_session_items`, `outbox`, `inbox`
- [ ] Key columns: `checkout_sessions(id UUID, customer_id UUID, cart_id UUID, address_id UUID, payment_intent_id UUID, food_order_id UUID, state TEXT, subtotal_minor BIGINT, tax_minor BIGINT, delivery_fee_minor BIGINT, tip_minor BIGINT, total_minor BIGINT, currency TEXT, pay_blocked BOOL, expires_at TIMESTAMPTZ)`
- [ ] Write Flyway / jOOQ migrations (forward-only)
- [ ] Implement `CheckoutSession` aggregate with state machine (pending, completed, failed, expired, cancelled)

### Phase 2 — REST API
- [ ] `POST /v1/checkouts` — create checkout session from cart (`Idempotency-Key` required)
- [ ] `GET /v1/checkouts/{id}` — read session (Redis-cached 30s)
- [ ] `PATCH /v1/checkouts/{id}` — update address, slot, tip, payment method
- [ ] `POST /v1/checkouts/{id}/pay` — authorize payment and create food order
- [ ] `DELETE /v1/checkouts/{id}` — cancel pending session

### Phase 3 — Event Publishing
- [ ] Implement transactional outbox table
- [ ] Publish `checkout.completed.v1` → topic `checkout.checkout.completed` (payment authorized + order created)
- [ ] Publish `checkout.failed.v1` → topic `checkout.checkout.failed` (payment failed or expired)
- [ ] Outbox poller (200ms interval, DLQ)

### Phase 4 — Event Consumption
- [ ] Implement inbox table for deduplication
- [ ] Consume `cart.updated.v1` → re-quote or expire session if cart abandoned/checked-out
- [ ] Consume `restaurant.offline.v1` → set `pay_blocked = true`
- [ ] Consume `payment.authorized.v1` → create food order; mark session completed; emit `checkout.completed.v1`
- [ ] Consume `payment.failed.v1` → mark session failed; emit `checkout.failed.v1`

### Phase 5 — Caching
- [ ] Redis: `checkout:{id}` (TTL 30s) for fast session reads
- [ ] Redis idempotency key storage: `checkout:idem:{key}` for duplicate detection
- [ ] Redis distributed lock for `POST /pay` to prevent concurrent payment attempts

### Phase 6 — External Integrations
- [ ] `cart-service` — read cart contents and re-quote
- [ ] `pricing-service` — request final frozen quote
- [ ] `address-service` — verify saved address
- [ ] `payment-service` — authorize payment
- [ ] `customer-service` — read default payment method
- [ ] `food-order-service` — create order on success
- [ ] `restaurant-service` / `branch-service` — online/open check
- [ ] `notification-service` — notify customer on failure
- [ ] Circuit breakers on all outbound calls

### Phase 7 — Security
- [ ] JWT bearer auth via Keycloak (Spring Security 7)
- [ ] Required scopes/roles: `customer` for checkout CRUD; `client_credentials` for internal reads
- [ ] Resource-level ownership: `checkout_session.customer_id == JWT sub`
- [ ] Secrets via HashiCorp Vault

### Phase 8 — Observability
- [ ] Structured JSON logs with `correlation_id`, `checkout_session_id`, `customer_id`, `state`
- [ ] Metrics: RED per route + `checkouts_created_total`, `checkouts_completed_total`, `checkouts_failed_total{reason}`, `checkouts_expired_total`, `checkout_quote_seconds`
- [ ] OpenTelemetry traces with child spans per downstream call
- [ ] Health endpoints: `/actuator/health`, `/ready`, `/started`

### Phase 9 — Testing
- [ ] Unit tests: checkout state machine, idempotency, expiry cron
- [ ] Integration tests: Testcontainers (PostgreSQL, Kafka, Redis)
- [ ] E2E tests: full checkout + pay flow; payment failure; cart abandoned invalidation

### Phase 10 — Deployment
- [ ] Kubernetes manifests: Deployment, Service, HPA (CPU 60%, 3–15 replicas), PDB
- [ ] Pre-upgrade Job for database migrations
- [ ] Resource limits per DEPLOYMENT_ARCHITECTURE.md

---

## Integration Map

### Sync Dependencies (outbound calls)
| Target | Endpoint | Purpose | Circuit Breaker |
|--------|----------|---------|----------------|
| `cart-service` | `GET /v1/carts/{id}` | Read cart contents | Yes |
| `pricing-service` | `POST /v1/quote` | Final frozen quote | Yes |
| `address-service` | `GET /v1/addresses/{id}` | Verify address | Yes |
| `payment-service` | `POST /v1/payments/authorize` | Authorize payment | Yes |
| `customer-service` | `GET /v1/customers/{id}/default-payment-method` | Default PM | Yes |
| `food-order-service` | `POST /v1/orders` | Create food order | Yes |
| `restaurant-service` | `GET /v1/restaurants/{id}/online` | Online check | Yes |
| `branch-service` | `GET /v1/branches/{id}/open` | Open check | Yes |
| `notification-service` | `POST /v1/notifications` | Notify customer | Yes |

### Events Published
| Event | Topic | Trigger | Key Consumers |
|-------|-------|---------|--------------|
| `checkout.completed.v1` | `checkout.checkout.completed` | Payment authorized + order created | `food-order-service`, `cart-service`, `notification-service`, `audit-service` |
| `checkout.failed.v1` | `checkout.checkout.failed` | Payment failed or session expired | `cart-service`, `notification-service`, `audit-service` |

### Events Consumed
| Event | Producer | Handler |
|-------|----------|---------|
| `cart.updated.v1` | `cart-service` | Re-quote or expire session if cart abandoned |
| `restaurant.offline.v1` | `restaurant-service` | Set pay_blocked = true |
| `payment.authorized.v1` | `payment-service` | Create food order; mark completed; emit checkout.completed.v1 |
| `payment.failed.v1` | `payment-service` | Mark session failed; emit checkout.failed.v1 |

---

## Acceptance Criteria
- [ ] All REST endpoints return correct status codes
- [ ] p99 latency within SLO targets (< 500ms)
- [ ] All events published reliably via outbox pattern
- [ ] Zero data leakage across service boundaries
- [ ] 80%+ unit test coverage

---

## Related Docs
- [README](README.md) · [BRD](BRD.md) · [SRS](SRS.md) · [ERD](ERD.md) · [INTEGRATION](INTEGRATION.md) · [WORKFLOWS](WORKFLOWS.md) · [TECH](TECH.md)
- [Master Plan](../../MASTER_SERVICE_PLAN.md)
