# courier-earnings-service — Implementation Plan

**Domain:** Food Delivery & Couriers
**Tier:** 4
**Technology:** Kotlin + Spring Boot 4
**Criticality:** T1 (99.95% SLO)
**DB Schema:** `courier_earnings`
**Cache:** —
**HPA:** CPU 60%, 2–5, p99 < 1s

---

## Purpose

`courier-earnings-service` is the source of truth for what a courier has earned, what they have available to withdraw, and what they have been paid. It owns the courier earnings ledger, accrues tips, processes withdrawal requests, and provides courier earning statements.

---

## Tasks

### Phase 1 — Database & Domain Model
- [ ] Create schema `courier_earnings`: tables `earnings` (append-only), `tips`, `withdrawals`, `statements`, `outbox`, `inbox`
- [ ] Key columns: `earnings(id UUID, courier_id UUID, delivery_id UUID UNIQUE, amount_minor BIGINT, currency TEXT, status TEXT, accrued_at TIMESTAMPTZ)`, `withdrawals(id UUID, courier_id UUID, amount_minor BIGINT, status TEXT, payout_ref UUID, requested_at TIMESTAMPTZ)`
- [ ] Write Flyway migrations (forward-only)
- [ ] Implement `CourierEarning` aggregate, `Withdrawal` aggregate, append-only repository

### Phase 2 — REST API
- [ ] `POST /v1/courier-earnings/accrue` — accrue an earning (service auth)
- [ ] `POST /v1/courier-earnings/tip` — accrue a tip (service auth)
- [ ] `GET /v1/courier-earnings?courier_id=…&from=…&to=…` — list earnings (cursor pagination)
- [ ] `GET /v1/courier-earnings/balance/{courier_id}` — current available balance
- [ ] `POST /v1/courier-withdrawals` — request withdrawal (courier auth)
- [ ] `GET /v1/courier-withdrawals?courier_id=…` — list withdrawals
- [ ] `GET /v1/courier-withdrawals/{id}` — read withdrawal
- [ ] `POST /v1/courier-withdrawals/{id}/cancel` — cancel pending withdrawal
- [ ] `GET /v1/courier-statements/{courier_id}?period=…` — courier earnings statement

### Phase 3 — Event Publishing
- [ ] Implement transactional outbox table
- [ ] Publish `courier.earning.accrued.v1` → on earning row inserted
- [ ] Publish `courier.withdrawal.requested.v1` → on withdrawal requested
- [ ] Publish `courier.withdrawal.completed.v1` → on payout completed
- [ ] Publish `courier.withdrawal.failed.v1` → on payout failed after retries
- [ ] Outbox poller (200ms interval, DLQ)

### Phase 4 — Event Consumption
- [ ] Implement inbox table for deduplication
- [ ] Consume `delivery.completed.v1` → insert earning row (base earning + delivery tip)
- [ ] Consume `food.payment.completed.v1` → mark accrual as final
- [ ] Consume `payment.payout.completed.v1` → mark withdrawal as completed
- [ ] Consume `payment.payout.failed.v1` → retry payout or surface to support
- [ ] Consume `ledger.posted.v1` → reconciliation verification
- [ ] Consume `configuration.updated.v1` → reload commission rate, min/max withdrawal

### Phase 5 — Caching
- [ ] Balance cached in Redis with short TTL for fast reads (invalidated on each accrual)

### Phase 6 — External Integrations
- [ ] `courier-service` — `GET /v1/couriers/{id}` to enrich earnings records
- [ ] `payment-service` — `POST /v1/payouts` for withdrawal execution
- [ ] `wallet-service` — credit/debit wallet on earnings/withdrawals
- [ ] Circuit breakers on all outbound calls

### Phase 7 — Security
- [ ] JWT bearer auth via Keycloak (Spring Security 7)
- [ ] Required scopes/roles: couriers may only read their own earnings; `courier.admin` for admin
- [ ] Secrets via HashiCorp Vault

### Phase 8 — Observability
- [ ] Structured JSON logs with `correlation_id`, `courier_id`, `earning_id`, `withdrawal_id`
- [ ] Metrics: `courier_earnings_accrued_total{city_id}`, `courier_withdrawal_requested_total`, `courier_withdrawal_completed_total`, `courier_withdrawal_failed_total{reason}`
- [ ] OpenTelemetry traces with child spans per downstream call
- [ ] Health endpoints: `/actuator/health`, `/ready`, `/started`

### Phase 9 — Testing
- [ ] Unit tests: earning accrual logic, commission calculation, withdrawal state machine
- [ ] Integration tests: Testcontainers (PostgreSQL, Kafka)
- [ ] E2E tests: delivery completed → accrue, withdrawal request → payout

### Phase 10 — Deployment
- [ ] Kubernetes manifests: Deployment, Service, HPA (CPU 60%, 2–5 replicas), PDB
- [ ] Pre-upgrade Job for database migrations
- [ ] Resource limits per DEPLOYMENT_ARCHITECTURE.md

---

## Integration Map

### Sync Dependencies (outbound calls)
| Target | Endpoint | Purpose | Circuit Breaker |
|--------|----------|---------|----------------|
| `courier-service` | `GET /v1/couriers/{id}` | Enrich earning records | Yes |
| `payment-service` | `POST /v1/payouts` | Execute withdrawal payout | Yes |
| `wallet-service` | `POST /v1/wallets/{id}/credit` | Credit/debit wallet | Yes |

### Events Published
| Event | Topic | Trigger | Key Consumers |
|-------|-------|---------|--------------|
| `courier.earning.accrued.v1` | `courier.earning.accrued` | Earning row inserted | `reporting-service`, `audit-service` |
| `courier.withdrawal.requested.v1` | `courier.withdrawal.requested` | Withdrawal requested | `payment-service`, `audit-service` |
| `courier.withdrawal.completed.v1` | `courier.withdrawal.completed` | Payout completed | `notification-service`, `audit-service` |
| `courier.withdrawal.failed.v1` | `courier.withdrawal.failed` | Payout failed after retries | `support-service`, `notification-service`, `audit-service` |

### Events Consumed
| Event | Producer | Handler |
|-------|----------|---------|
| `delivery.completed.v1` | `delivery-service` | Insert earning row (base + delivery tip) |
| `food.payment.completed.v1` | `food-payment-integration-service` | Mark accrual as final |
| `payment.payout.completed.v1` | `payment-service` | Mark withdrawal completed |
| `payment.payout.failed.v1` | `payment-service` | Retry payout or surface to support |
| `ledger.posted.v1` | `ledger-service` | Reconciliation verification |
| `configuration.updated.v1` | `configuration-service` | Reload commission rate, withdrawal limits |

---

## Acceptance Criteria
- [ ] All REST endpoints return correct status codes
- [ ] p99 latency within SLO targets (< 1s)
- [ ] All events published reliably via outbox pattern
- [ ] Zero data leakage across service boundaries
- [ ] 80%+ unit test coverage

---

## Related Docs
- [README](README.md) · [BRD](BRD.md) · [SRS](SRS.md) · [ERD](ERD.md) · [INTEGRATION](INTEGRATION.md) · [WORKFLOWS](WORKFLOWS.md) · [TECH](TECH.md)
- [Master Plan](../../MASTER_SERVICE_PLAN.md)
