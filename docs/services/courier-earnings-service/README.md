# courier-earnings-service

## 1. Purpose

`courier-earnings-service` is the **source of truth for what a
courier has earned, what they have available to withdraw, and what
they have actually been paid**. It owns the courier earnings ledger,
handles tips, processes withdrawal requests, and provides the
courier's statement of earnings.

## 2. Bounded Context

Bounded context: **Courier Earnings**.

- **In scope**: earning accrual on delivery completion, tip
  accrual, withdrawal requests, the earnings ledger, the
  statement view, retries on payout failure, reconciliation against
  the ledger service.
- **Out of scope**: courier profile / KYC (owned by
  `courier-service`), the delivery state machine (owned by
  `delivery-service`), the actual money movement (owned by
  `payment-service` / `wallet-service` / `ledger-service`).

## 3. Responsibilities

- Accrue courier earnings on `delivery.completed.v1`.
- Accrue courier tips on tip events from the financial saga.
- Maintain the earnings ledger (append-only).
- Accept withdrawal requests and orchestrate the payout via
  `payment-service`.
- Provide the courier's daily / weekly / monthly statement.
- Retry failed payouts with backoff; surface a `courier.admin`
  action when retries are exhausted.
- Reconcile earnings against `ledger-service` postings daily.

## 4. Explicitly NOT Owned

- Courier profile / KYC / vehicle — owned by `courier-service`.
- Delivery state — owned by `delivery-service`.
- Wallet balance — owned by `wallet-service`.
- Bank transfer execution — owned by `payment-service` (payout
  provider integration).
- The platform's chart of accounts — owned by `ledger-service`.

## 5. Actors

| Actor | Type | Access |
|-------|------|--------|
| Courier | human (mobile) | view earnings, request withdrawal (read / write) |
| `food-payment-integration-service` | system | triggers earning accrual (write) |
| `payment-service` | system | executes payout (read / write) |
| `wallet-service` | system | credits / debits (read / write) |
| `ledger-service` | system | records postings (write) |
| `support-service` / `admin-service` | system | force accrual, manual payout, refund (admin) |

## 6. Dependencies

### Synchronous (REST)

- `courier-service` — `GET /v1/couriers/{id}` to enrich — circuit
  breaker: yes, SLO 50ms p99.
- `food-order-service` — `GET /v1/orders/{id}` to enrich — circuit
  breaker: yes, SLO 50ms p99.
- `payment-service` — `POST /v1/payouts` for withdrawal — circuit
  breaker: yes, SLO 1s p99.
- `wallet-service` — `POST /v1/wallets/{id}/debit` and `/credit` —
  circuit breaker: yes.

### Asynchronous (events consumed)

- `delivery.completed.v1` from `delivery-service` — primary
  accrual trigger — dedup: inbox.
- `food.payment.completed.v1` from `food-payment-integration-service`
  — confirms the financial saga completed; triggers a tip
  accrual if a tip was applied — dedup: inbox.
- `customer.tip.added.v1` from `food-payment-integration-service`
  — tip added after delivery — dedup: inbox.
- `payment.payout.completed.v1` from `payment-service` — payout
  finished; mark withdrawal `completed` — dedup: inbox.
- `payment.payout.failed.v1` from `payment-service` — payout
  failed; retry or surface — dedup: inbox.
- `configuration.updated.v1` from `configuration-service` — reload
  commission, min withdrawal — dedup: inbox.

## 7. Technology Assumptions

- Runtime: Node 20 (TypeScript).
- Database: PostgreSQL 18 (per-service schema `courier_earnings`).
- Cache: Redis (per-service) for hot balance and statement cache.
- Event broker: Kafka.

## 8. Database Ownership

- Schema: `courier_earnings`
- Migrations: `services/courier-earnings-service/migrations/` —
  versioned, forward-only.
- Soft delete: no (ledger rows are append-only).
- Partitioning: no (volume is bounded; we can partition by year if
  needed later).

## 9. API Overview

| Method | URI | Auth | Purpose |
|--------|-----|------|---------|
| POST | `/v1/courier-earnings/accrue` | bearer (service) | accrue an earning |
| POST | `/v1/courier-earnings/tip` | bearer (service) | accrue a tip |
| GET | `/v1/courier-earnings?courier_id=…&from=…&to=…` | bearer | list earnings (with cursor) |
| GET | `/v1/courier-earnings/balance/{courier_id}` | bearer | current balance |
| POST | `/v1/courier-withdrawals` | bearer (courier) | request withdrawal |
| GET | `/v1/courier-withdrawals?courier_id=…` | bearer | list withdrawals |
| GET | `/v1/courier-withdrawals/{id}` | bearer | read withdrawal |
| POST | `/v1/courier-withdrawals/{id}/cancel` | bearer (courier / admin) | cancel pending |
| GET | `/v1/courier-statements/{courier_id}?period=…` | bearer (courier / admin) | statement |

(Full contracts in `INTEGRATION.md`.)

## 10. Events Produced

| Event | Trigger | Consumers |
|-------|---------|-----------|
| `courier.earning.accrued.v1` | earning row inserted | `reporting-service`, `audit-service` |
| `courier.earning.tip_accrued.v1` | tip row inserted | `reporting-service`, `audit-service` |
| `courier.withdrawal.requested.v1` | withdrawal requested | `payment-service` (payout), `audit-service` |
| `courier.withdrawal.completed.v1` | payout completed | `notification-service`, `audit-service` |
| `courier.withdrawal.failed.v1` | payout failed after retries | `support-service`, `notification-service`, `audit-service` |
| `courier_earnings.audit.ledger_posted.v1` | every ledger row | `audit-service` |

(Full contracts in `INTEGRATION.md`.)

## 11. Events Consumed

| Event | Producer | Reason | Handler |
|-------|----------|--------|---------|
| `delivery.completed.v1` | `delivery-service` | accrue base earning + delivery tip | insert earning row |
| `food.payment.completed.v1` | `food-payment-integration-service` | saga done; mark accrual as final | update earning row |
| `customer.tip.added.v1` | `food-payment-integration-service` | tip added after delivery | insert tip row |
| `payment.payout.completed.v1` | `payment-service` | payout finished | mark withdrawal `completed` |
| `payment.payout.failed.v1` | `payment-service` | payout failed | retry or surface |
| `ledger.posted.v1` | `ledger-service` | ledger confirmed | reconciliation |
| `configuration.updated.v1` | `configuration-service` | reload | refresh in-memory |

## 12. External Integrations

- Bank transfer via `payment-service` (provider integration).

## 13. Configuration

| Key | Type | Source | Notes |
|-----|------|--------|-------|
| `courier_earnings.commission_rate` | decimal | `configuration-service` | platform cut; default 0.20 |
| `courier_earnings.min_withdrawal_minor` | int | `configuration-service` | default 1000 (= 10.00) |
| `courier_earnings.withdrawal_window` | enum (`daily`,`weekly`,`anytime`) | `configuration-service` | default `anytime` |
| `courier_earnings.max_withdrawal_minor` | int | `configuration-service` | default 1,000,000 |
| `courier_earnings.payout_max_retries` | int | `configuration-service` | default 3 |
| `courier_earnings.tip_hold_hours` | int | `configuration-service` | default 0 (tip is immediate) |

## 14. Security

- AuthN: JWT bearer (Keycloak `platform-courier` for couriers,
  `platform-services` for service-to-service,
  `platform-internal` for support).
- AuthZ: couriers may only read their own earnings
  (`courier_id == sub`); admins require `courier.admin`.
- Secrets: bank-account details are NOT stored here; the
  `payment-service` tokenised reference is stored as a UUID.
- PII: courier name and email are NOT stored; only `courier_id`.

## 15. Observability

- Logs: JSON to stdout with `correlation_id`, `courier_id`,
  `earning_id`, `withdrawal_id`, `tenant_id`.
- Metrics: `courier_earnings_accrued_total{city_id}`,
  `courier_tip_accrued_total{city_id}`,
  `courier_withdrawal_requested_total`,
  `courier_withdrawal_completed_total`,
  `courier_withdrawal_failed_total{reason}`,
  `courier_earnings_ledger_size`.
- Traces: OpenTelemetry; one root span per accrual / withdrawal.
- Health: `/health`, `/ready`, `/started`.

## 16. Scalability

- Replicas: 6 (default) — HPA on `kafka_consumer_lag` and
  `courier_earnings_accrued_total` rate.
- Hot path: accrual on `delivery.completed.v1` and tip on
  `customer.tip.added.v1`. Throughput is bounded by deliveries
  completed per second per region.

## 17. Local Development

- `docker compose up courier-earnings-service` brings up the
  service, PostgreSQL, Kafka, and a synthetic delivery stream.
- Tests: `pnpm test`, `pnpm test:e2e`.

## 18. Deployment

- Image: `registry.platform.io/courier-earnings-service:{version}`.
- Replicas: 6 (per region).
- Resource limits: 1 vCPU / 1 GiB.
- Migrations: separate job.


## 19. Accounting impact

`courier-earnings-service` is the **operational owner of courier
payable accounting on the food-delivery side**. It mirrors the
driver-earnings pattern but applies to couriers: computes
gross-to-net, accrues the payable / commission / withholding
entries on `courier.earning.accrued.v1`, and orchestrates
withdrawals to bank or wallet on `courier.withdrawal.completed.v1`.

- **What money facts it owns:** courier earnings rows (`type = base
  | tip | bonus | adjustment`), courier balances, withdrawals,
  statements.
- **Postings:** `courier_payable` (liability) ↔ `commission_revenue`
  + `tax_withheld_payable`; at payout, `courier_payable` ↔ `cash`
  (bank) or `wallet` (closed-loop).
- **Tips:** commission-free by default; a 24-hour tip window
  applies — after that the tip is recorded as a customer credit
  instead.
- **Reversals:** always a new `adjustment` row with `reversal_of`
  pointer; no UPDATE / DELETE on financial ledgers.
- **Reconciliation:** daily at 03:00 UTC against `ledger-service`
  (courier_payable account); drift opens a P1 ticket via
  `courier_earnings.audit.reconciliation_drift.v1`.
- **Human operator path:** admin adjustments via `support-service`
  / `admin-service`.

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

- **Depends on**: [`admin-service`](../admin-service/README.md), [`audit-service`](../audit-service/README.md), [`configuration-service`](../configuration-service/README.md), [`courier-service`](../courier-service/README.md), [`delivery-service`](../delivery-service/README.md), [`food-order-service`](../food-order-service/README.md), [`food-payment-integration-service`](../food-payment-integration-service/README.md), [`ledger-service`](../ledger-service/README.md), [`notification-service`](../notification-service/README.md), [`payment-service`](../payment-service/README.md), [`reporting-service`](../reporting-service/README.md), [`support-service`](../support-service/README.md), [`wallet-service`](../wallet-service/README.md)
- **Depended on by**: [`courier-service`](../courier-service/README.md), [`delivery-service`](../delivery-service/README.md), [`food-payment-integration-service`](../food-payment-integration-service/README.md), [`ledger-service`](../ledger-service/README.md), [`pricing-service`](../pricing-service/README.md)

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

- [`../../workflows/COURIER_WORKFLOWS.md`](../../workflows/COURIER_WORKFLOWS.md) — courier shifts, dispatch, delivery
- [`../../workflows/PAYMENT_WORKFLOWS.md`](../../workflows/PAYMENT_WORKFLOWS.md) — authorize/capture/refund/settlement
- [`../../workflows/ACCOUNTING_WORKFLOWS.md`](../../workflows/ACCOUNTING_WORKFLOWS.md) — accounting view (gross-to-net, tip window, payable, payout)
