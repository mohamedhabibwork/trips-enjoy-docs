# wallet-service

## 1. Purpose

`wallet-service` is the **source of truth for the customer's
wallet balance**: a real-time, in-memory-fast, ACID-strong store
of how much money the customer has, what's on hold, and what's
been spent. It is the platform's internal ledger of customer
funds — separate from the bank's ledger (which is the
`ledger-service`'s job).

## 2. Bounded Context

Bounded context: **Customer Wallet**.

- **In scope**: wallet balance per user, holds (reservations),
  release of holds, top-up history, statement view, reconciliation
  against `ledger-service`.
- **Out of scope**: payment provider integration (owned by
  `payment-service`), the platform's double-entry ledger
  (`ledger-service`), the food / ride payment sagas.

## 3. Responsibilities

- Maintain the wallet balance per user (in minor units + ISO 4217
  currency).
- Apply holds (reservations) for pending charges; release holds
  on cancellation or completion.
- Credit / debit the wallet on `payment.captured.v1` /
  `payment.refund.completed.v1`.
- Provide a top-up flow (charge the customer's payment method
  via `payment-service`, credit the wallet on success).
- Consume `trip.reward.granted.v1` to apply the per-trip
  customer credit when `trip.reward.user.kind = wallet_credit`,
  and consume `trip.reward.reversed.v1` to reverse it.
- Provide a statement view.
- Reconcile against `ledger-service` daily.

## 4. Explicitly NOT Owned

- Payment provider integration — owned by `payment-service`.
- The platform's double-entry ledger — owned by `ledger-service`.
- The food / ride payment sagas — owned by the integration
  services.

## 5. Actors

| Actor | Type | Access |
|-------|------|--------|
| Customer | human (mobile) | view balance, top up (read / write) |
| `payment-service` | system | credits / debits on capture / refund (write) |
| `ride-payment-integration-service` | system | holds / releases (write) |
| `food-payment-integration-service` | system | holds / releases (write) |
| `customer-service` | system | reads wallet ref (read) |
| `support-service` / `admin-service` | system | manual credit / debit (admin) |

## 6. Dependencies

### Synchronous (REST)

- `customer-service` — `GET /v1/customers/{id}` to enrich —
  circuit breaker: yes.
- `payment-service` — `POST /v1/payment-intents` for top-up —
  circuit breaker: yes.
- `ledger-service` — `GET /v1/accounts/wallet/sum` for
  reconciliation — circuit breaker: yes.

### Asynchronous (events consumed)

- `payment.captured.v1` from `payment-service` — credit on
  top-up — dedup: inbox.
- `payment.refund.completed.v1` from `payment-service` — debit
  on refund — dedup: inbox.
- `customer.suspended.v1` from `customer-service` — block
  transactions — dedup: inbox.
- `configuration.updated.v1` — reload.

## 7. Technology Assumptions

- Runtime: Node 20 (TypeScript).
- Database: PostgreSQL 18 (per-service schema `wallet`).
- Cache: Redis (per-service) for the hot balance.
- Event broker: Kafka.

## 8. Database Ownership

- Schema: `wallet`
- Migrations: `services/wallet-service/migrations/`.
- Soft delete: no (transactions are immutable).
- Partitioning: `transactions` is range-partitioned by month.

## 9. API Overview

| Method | URI | Auth | Purpose |
|--------|-----|------|---------|
| GET | `/v1/wallets/{user_id}` | bearer (user / service) | read balance |
| POST | `/v1/wallets/{user_id}/topup` | bearer (user) | top up |
| POST | `/v1/wallets/{user_id}/hold` | bearer (service) | create a hold |
| POST | `/v1/wallets/{user_id}/holds/{hold_id}/release` | bearer (service) | release a hold |
| POST | `/v1/wallets/{user_id}/holds/{hold_id}/capture` | bearer (service) | capture a hold (commit) |
| POST | `/v1/wallets/{user_id}/credit` | bearer (service / admin) | credit (e.g. refund) |
| POST | `/v1/wallets/{user_id}/debit` | bearer (service / admin) | debit |
| GET | `/v1/wallets/{user_id}/transactions?from=…&to=…` | bearer | list transactions |
| GET | `/v1/wallets/{user_id}/statement?period=…` | bearer | statement |
| POST | `/v1/wallets/{user_id}/admin-adjust` | bearer (admin) | manual adjustment |

(Full contracts in `INTEGRATION.md`.)

## 10. Events Produced

| Event | Trigger | Consumers |
|-------|---------|-----------|
| `wallet.credited.v1` | credit applied | `ledger-service`, `customer-service` (history), `audit-service` |
| `wallet.debited.v1` | debit applied | `ledger-service`, `customer-service` (history), `audit-service` |
| `wallet.held.v1` | hold created | `ledger-service`, `audit-service` |
| `wallet.released.v1` | hold released | `ledger-service`, `audit-service` |
| `wallet.captured.v1` | hold captured (committed) | `ledger-service`, `audit-service` |
| `wallet.audit.transaction_logged.v1` | every transaction | `audit-service` |
| `wallet.audit.reconciliation_drift.v1` | daily reconciliation drift | `admin-service`, `support-service` |

## 11. Events Consumed

| Event | Producer | Reason | Handler |
|-------|----------|--------|---------|
| `payment.captured.v1` | `payment-service` | top-up captured | credit wallet |
| `payment.refund.completed.v1` | `payment-service` | refund applied | debit wallet |
| `trip.reward.granted.v1` | `trip-service` | per-trip customer credit (when `trip.reward.user.kind = wallet_credit`) | credit wallet with idempotency key `trip:{trip_id}:reward:user:grant` |
| `trip.reward.reversed.v1` | `trip-service` | reverse the per-trip customer credit | debit wallet (no-op on redelivery) |
| `customer.suspended.v1` | `customer-service` | block | mark `transactions_blocked=true` |
| `configuration.updated.v1` | `configuration-service` | reload | refresh in-memory |

## 12. External Integrations

None direct. The payment provider is reached via `payment-service`.

## 13. Configuration

| Key | Type | Source | Notes |
|-----|------|--------|-------|
| `wallet.max_balance_minor` | int | `configuration-service` | default 10,000,000 (100,000.00) |
| `wallet.min_topup_minor` | int | `configuration-service` | default 100 |
| `wallet.max_topup_minor` | int | `configuration-service` | default 1,000,000 |
| `wallet.hold_ttl_minutes` | int | `configuration-service` | default 60; auto-release after |

## 14. Security

- AuthN: JWT bearer (Keycloak `platform-customer` for users,
  `platform-services` for service-to-service,
  `platform-internal` for support).
- AuthZ: a user may only read their own wallet
  (`user_id == sub`); admins require `wallet.admin`.
- Secrets: no PII beyond `user_id`.
- PII: not stored.

## 15. Observability

- Logs: JSON to stdout with `correlation_id`, `wallet_id`,
  `user_id`, `transaction_id`.
- Metrics: `wallet_credit_total{currency}`,
  `wallet_debit_total{currency}`,
  `wallet_hold_total{currency}`,
  `wallet_balance_total{currency}`,
  `wallet_topup_total{currency,method}`,
  `wallet_reconciliation_drift`.
- Traces: OpenTelemetry; root span per transaction.
- Health: `/health`, `/ready`, `/started`.

## 16. Scalability

- Replicas: 8 (default) — HPA on `kafka_consumer_lag` and
  `wallet_transactions_total`.
- Hot path: `GET /v1/wallets/{user_id}` (cached) and
  `POST /v1/wallets/{user_id}/hold` (write).

## 17. Local Development

- `docker compose up wallet-service` brings up the service,
  PostgreSQL, Kafka.
- Tests: `pnpm test`, `pnpm test:e2e`.

## 18. Deployment

- Image: `registry.platform.io/wallet-service:{version}`.
- Replicas: 8 (per region).
- Resource limits: 1 vCPU / 1 GiB.
- Migrations: separate job.

## 19. Cross-Service Coordination Notes

This service participates in the platform's
cross-service choreography. The following notes summarize
how it fits with the broader event-driven architecture
(see `architecture/EVENT_ARCHITECTURE.md`):

- **Idempotency**: every non-idempotent write is
  protected by an `Idempotency-Key` header and the
  platform-standard idempotency store. A retried
  request with the same key and body returns the
  stored response.
- **Outbox**: every state change that needs to be
  published to Kafka is written to the local outbox
  table in the same database transaction as the
  state change. A separate poller publishes to Kafka
  with `acks=all` and retries on failure. Outbox rows
  are purged 24 h after a successful publish.
- **Inbox**: every consumed event is recorded in the
  local inbox table keyed by `event_id` with a 24 h
  TTL, so re-deliveries are de-duplicated.
- **Cross-service references**: every cross-service
  reference (e.g. `identity_id`, `customer_id`,
  `driver_id`, `courier_id`, `vehicle_id`,
  `address_id`, `payment_method_id`) is stored as a
  UUID column WITHOUT database FK. The owning
  service is the source of truth; this service
  validates the reference exists and is current
  before persisting.
- **Distributed tracing**: OpenTelemetry
  `traceparent` is propagated to every downstream
  call. The platform's `correlation_id` is enriched on
  every span and emitted in every event's envelope.
- **Graceful degradation**: when a non-critical
  dependency is unavailable, the service degrades
  to a safe fallback (e.g. cached read, degraded
  write). The fallback is documented in the
  relevant workflow's `WORKFLOWS.md`.


## 20. Accounting impact

`wallet-service` is **not a chart-of-accounts service**; it tracks the
customer's running balance and emits the events that `ledger-service`
consumes to derive its own double-entry postings. From an accounting
perspective the wallet is a **customer-facing cache of the underlying
ledger postings**, kept ACID-fast for runtime use.

- **What money facts it owns:** customer wallet balance (per
  `user_id` + `currency`), holds (reservations), top-up history,
  statement view. Invariant: `available + held = credits − debits`.
- **Postings:** every credit / debit / hold / release / capture
  emits a `wallet.*.v1` event consumed by `ledger-service`, which
  posts the corresponding balanced entry on the `wallet_*`
  asset / liability accounts. For the per-trip customer credit
  (consumed from `trip.reward.granted.v1` when
  `trip.reward.user.kind = wallet_credit`) the wallet credit
  is paired with a `2100_customer_credit_liability` ledger
  entry on the consumer side (the operational sub-account of
  `2100_customer_credit_liability` in the chart of accounts);
  the reversal on `trip.reward.reversed.v1` produces a new
  reversing row (never an UPDATE / DELETE).
- **Closed-loop refund:** a refund that originated from a wallet
  top-up debits the wallet and produces a separate
  `ledger.posted.v1` for the refund; the original capture's posting
  is not modified.
- **Reconciliation:** daily at 03:00 UTC against
  `ledger-service` via `GET /v1/accounts/wallet/sum`. Drift opens a
  P1 ticket via `wallet.audit.reconciliation_drift.v1`.
- **Human operator path:** manual `admin-adjust` via admin console;
  requires `wallet.admin` role; emits `admin.action.performed.v1`.

See [`../../workflows/ACCOUNTING_WORKFLOWS.md`](../../workflows/ACCOUNTING_WORKFLOWS.md)
for the cross-service view.


---

## See also

### Sibling docs for this service

- [`README.md`](./README.md) — purpose, bounded context, responsibilities
- [`BRD.md`](./BRD.md) — business requirements
- [`SRS.md`](./SRS.md) — functional + non-functional requirements
- [`ERD.md`](./ERD.md) — data model (entities, relationships)
- [`INTEGRATION.md`](./INTEGRATION.md) — inter-service contracts (APIs, events, sagas)
- [`WORKFLOWS.md`](./WORKFLOWS.md) — operational workflows (happy paths, failure modes)
- [`TECH.md`](./TECH.md) — technology profile (runtime, libraries, data layer, admin endpoints, RBAC)

### Related services

- **Depends on**: [`admin-service`](../admin-service/README.md), [`audit-service`](../audit-service/README.md), [`configuration-service`](../configuration-service/README.md), [`customer-service`](../customer-service/README.md), [`food-payment-integration-service`](../food-payment-integration-service/README.md), [`ledger-service`](../ledger-service/README.md), [`payment-service`](../payment-service/README.md), [`ride-payment-integration-service`](../ride-payment-integration-service/README.md), [`support-service`](../support-service/README.md)
- **Depended on by**: [`api-gateway`](../api-gateway/README.md), [`courier-dispatch-service`](../courier-dispatch-service/README.md), [`courier-earnings-service`](../courier-earnings-service/README.md), [`driver-earnings-service`](../driver-earnings-service/README.md), [`food-payment-integration-service`](../food-payment-integration-service/README.md), [`ledger-service`](../ledger-service/README.md), [`loyalty-service`](../loyalty-service/README.md), [`payment-service`](../payment-service/README.md), [`pricing-service`](../pricing-service/README.md)

> Full dependency map in [`../README.md`](../README.md) and [`../../architecture/MICROSERVICES_MAP.md`](../../architecture/MICROSERVICES_MAP.md).

### Platform-wide

- [`../../shared/README.md`](../../shared/README.md) — `platform-spring-boot-starter` shared library (the single source of cross-cutting code for all Spring Boot services in the platform)
- [`../../shared/PLATFORM_BASELINE.md`](../../shared/PLATFORM_BASELINE.md) — single source for PostgreSQL 18, Kafka, Keycloak, Redis, OpenTelemetry, Vault, deployment, DR (do not restate these in this README)
- [`../../architecture/SERVICE_ISOLATION.md`](../../architecture/SERVICE_ISOLATION.md) — **how this service behaves when a downstream is down** (timeout / bulkhead / circuit / retry / fallback, by class: CRITICAL / DEGRADABLE / BEST-EFFORT)
- [`../../architecture/DOWNSTREAM_ERROR_CATALOG.md`](../../architecture/DOWNSTREAM_ERROR_CATALOG.md) — **canonical error-code catalog + propagation rules** (the `downstream` block, forward/translate/degrade/reject)
- [`../RECOMMENDATIONS.md`](../RECOMMENDATIONS.md) — platform-wide technology map (language, framework, version baseline, admin/RBAC pattern)
- [`../../README.md`](../../README.md) — services overview (the catalog of all 58 services)
- [`../../../main.md`](../../../main.md) — top-level platform specification (architecture, Keycloak, PostgreSQL 18, messaging, observability baseline)
- [`../../shared/OSS_DEPENDENCIES.md`](../../shared/OSS_DEPENDENCIES.md) — **open-source dependencies & license attribution** (platform-wide OSS projects + per-language OSS libraries with SPDX IDs; per-service bundle index; license compatibility matrix)

### Workflows this service participates in

- [`../../workflows/PAYMENT_WORKFLOWS.md`](../../workflows/PAYMENT_WORKFLOWS.md) — authorize/capture/refund/settlement
- [`../../workflows/ACCOUNTING_WORKFLOWS.md`](../../workflows/ACCOUNTING_WORKFLOWS.md) — accounting view (transaction recognition, expense, reconciliation)
- [`../../workflows/ACCOUNTING_WORKFLOWS.md`](../../workflows/ACCOUNTING_WORKFLOWS.md) §"Guaranteed Rewards — Driver Top-Up + Customer Credit" — receives the per-trip customer credit from `trip.reward.granted.v1` (when `trip.reward.user.kind = wallet_credit`); reverses it on `trip.reward.reversed.v1`
