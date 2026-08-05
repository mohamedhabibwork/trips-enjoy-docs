# restaurant-settlement-service

## 1. Purpose

`restaurant-settlement-service` is the **source of truth for what
the platform owes a restaurant/merchant**: the merchant's payable
balance, the schedule of payouts, and the actual bank transfer.
It owns the payout runs and reconciles them against the ledger.

## 2. Bounded Context

Bounded context: **Restaurant / Merchant Settlement**.

- **In scope**: merchant payable accrual, commission calculation,
  payout schedule, payout runs, bank transfer orchestration via
  `payment-service`, dispute handling, reconciliation against
  `ledger-service`.
- **Out of scope**: merchant onboarding (owned by
  `merchant-service`), the food payment saga (owned by
  `food-payment-integration-service`), payment provider
  integration (owned by `payment-service`), the chart of accounts
  (owned by `ledger-service`).

## 3. Responsibilities

- Receive `food.payment.completed.v1` and accrue the merchant's
  share to the payable balance.
- Receive `merchant.settlement.created.v1` (from the saga) and
  apply additional adjustments (e.g. quality debits).
- Compute the merchant's payable on the configured cadence
  (default weekly).
- Schedule a payout run; orchestrate the bank transfer via
  `payment-service`.
- Handle disputes (chargebacks, quality disputes) by debiting the
  payable balance.
- Reconcile against `ledger-service` daily.
- Provide the merchant's statement view.

## 4. Explicitly NOT Owned

- Merchant profile / KYC / bank details — owned by
  `merchant-service` and `payment-service`.
- The food payment saga — owned by
  `food-payment-integration-service`.
- The actual money movement (provider) — owned by
  `payment-service`.
- The chart of accounts — owned by `ledger-service`.

## 5. Actors

| Actor | Type | Access |
|-------|------|--------|
| Merchant (operator) | human | read statements, configure payout schedule |
| `food-payment-integration-service` | system | triggers accrual (write) |
| `payment-service` | system | executes payout (read / write) |
| `ledger-service` | system | records postings (write) |
| `support-service` / `admin-service` | system | force debit, manual payout, dispute (admin) |

## 6. Dependencies

### Synchronous (REST)

- `merchant-service` — `GET /v1/merchants/{id}` to enrich (bank
  reference) — circuit breaker: yes.
- `payment-service` — `POST /v1/payouts` for the bank transfer —
  circuit breaker: yes.
- `ledger-service` — `POST /v1/postings` for the payable entries
  — circuit breaker: yes.

### Asynchronous (events consumed)

- `food.payment.completed.v1` from
  `food-payment-integration-service` — accrue merchant payable —
  dedup: inbox.
- `food.payment.partial_refund.v1` / `food.payment.full_refund.v1`
  — apply proportional debit to payable — dedup: inbox.
- `merchant.suspended.v1` from `merchant-service` — pause
  payouts — dedup: inbox.
- `merchant.payout.scheduled.v1` from `payment-service` — payout
  accepted — dedup: inbox.
- `merchant.payout.completed.v1` from `payment-service` — payout
  finished — dedup: inbox.
- `configuration.updated.v1` — reload.

## 7. Technology Assumptions

- Runtime: Node 20 (TypeScript).
- Database: PostgreSQL 18 (per-service schema
  `restaurant_settlement`).
- Cache: Redis (per-service) for the merchant's payable balance
  cache.
- Event broker: Kafka.

## 8. Database Ownership

- Schema: `restaurant_settlement`
- Migrations: `services/restaurant-settlement-service/migrations/`.
- Soft delete: no.
- Partitioning: `payouts` is range-partitioned by month.

## 9. API Overview

| Method | URI | Auth | Purpose |
|--------|-----|------|---------|
| POST | `/v1/merchant-payouts/accrue` | bearer (service) | accrue merchant payable |
| GET | `/v1/merchant-payouts/balance/{merchant_id}` | bearer | current balance |
| GET | `/v1/merchant-payouts?merchant_id=…&from=…&to=…` | bearer | list accruals |
| POST | `/v1/payout-runs` | bearer (admin) | trigger a payout run |
| GET | `/v1/payout-runs/{id}` | bearer | read a payout run |
| POST | `/v1/payout-runs/{id}/force` | bearer (admin) | force a payout |
| POST | `/v1/payout-runs/{id}/cancel` | bearer (admin) | cancel a pending payout |
| GET | `/v1/merchant-statements/{merchant_id}?period=…` | bearer | statement |
| POST | `/v1/disputes` | bearer (service / support) | open a dispute (debit) |
| GET | `/v1/disputes?merchant_id=…` | bearer | list disputes |

(Full contracts in `INTEGRATION.md`.)

## 10. Events Produced

| Event | Trigger | Consumers |
|-------|---------|-----------|
| `merchant.settlement.accrued.v1` | payable accrued | `merchant-service` (UI), `audit-service` |
| `merchant.payout.scheduled.v1` | payout run created | `payment-service`, `audit-service` |
| `merchant.payout.completed.v1` | payout succeeded | `merchant-service`, `notification-service`, `audit-service` |
| `merchant.payout.failed.v1` | payout failed after retries | `support-service`, `notification-service`, `audit-service` |
| `merchant.dispute.opened.v1` | dispute opened | `merchant-service`, `audit-service` |
| `merchant.dispute.resolved.v1` | dispute resolved | `merchant-service`, `audit-service` |
| `restaurant_settlement.audit.ledger_posted.v1` | every ledger row | `audit-service` |

(Full contracts in `INTEGRATION.md`.)

## 11. Events Consumed

| Event | Producer | Reason | Handler |
|-------|----------|--------|---------|
| `food.payment.completed.v1` | `food-payment-integration-service` | accrue payable | insert accrual row |
| `food.payment.partial_refund.v1` | `food-payment-integration-service` | proportional debit | insert adjustment row |
| `food.payment.full_refund.v1` | `food-payment-integration-service` | reverse payable | insert adjustment row |
| `merchant.suspended.v1` | `merchant-service` | pause payouts | mark merchant `paused` |
| `payment.payout.completed.v1` | `payment-service` | payout done | mark payout `completed` |
| `payment.payout.failed.v1` | `payment-service` | payout failed | retry or surface |
| `configuration.updated.v1` | `configuration-service` | reload | refresh in-memory |

## 12. External Integrations

- Bank transfer via `payment-service` (provider integration).

## 13. Configuration

| Key | Type | Source | Notes |
|-----|------|--------|-------|
| `restaurant_settlement.commission_rate_default` | decimal | `configuration-service` | default 0.20 |
| `restaurant_settlement.payout_schedule` | enum (`daily`,`weekly`,`biweekly`,`monthly`) | `configuration-service` | default `weekly` |
| `restaurant_settlement.min_payout_minor` | int | `configuration-service` | default 1000 |
| `restaurant_settlement.payout_max_retries` | int | `configuration-service` | default 3 |

## 14. Security

- AuthN: JWT bearer (Keycloak `platform-staff` for merchants,
  `platform-services` for service-to-service,
  `platform-internal` for support).
- AuthZ: merchants may only read their own statement
  (`merchant_id` is in their group); admins require
  `merchant.admin`.
- Secrets: bank account details NOT stored here; the
  `payment-service` tokenised reference is stored as a UUID.
- PII: merchant name and tax id NOT stored here.

## 15. Observability

- Logs: JSON to stdout with `correlation_id`, `merchant_id`,
  `payout_id`, `accrual_id`, `tenant_id`.
- Metrics: `merchant_payable_accrued_total{city_id}`,
  `merchant_payout_scheduled_total`,
  `merchant_payout_completed_total`,
  `merchant_payout_failed_total{reason}`,
  `merchant_payout_seconds`,
  `merchant_payout_run_size`.
- Traces: OpenTelemetry; root span per accrual / payout.
- Health: `/health`, `/ready`, `/started`.

## 16. Scalability

- Replicas: 6 (default) — HPA on `kafka_consumer_lag` and
  `merchant_payout_run_size`.
- Hot path: accrual on `food.payment.completed.v1` and payout
  execution.

## 17. Local Development

- `docker compose up restaurant-settlement-service` brings up
  the service, PostgreSQL, Kafka, and a synthetic
  `food-payment-integration-service`.
- Tests: `pnpm test`, `pnpm test:e2e`.

## 18. Deployment

- Image: `registry.platform.io/restaurant-settlement-service:{version}`.
- Replicas: 6 (per region).
- Resource limits: 1 vCPU / 1 GiB.
- Migrations: separate job.


## 19. Accounting impact

`restaurant-settlement-service` is the **operational owner of merchant
payable accounting on the food-delivery side**. It accrues merchant
payables on `merchant.settlement.accrued.v1` and orchestrates
per-merchant payout runs on `merchant.payout.completed.v1`. The
financial saga (`food-payment-integration-service`) posts the
payable + customer receivable; this service writes an accrual row
and the ledger entry is the saga's.

- **What money facts it owns:** merchant accruals (`kind = order
  | refund_partial | refund_full | dispute_debit`), merchant
  balances, payout runs, disputes, statements.
- **Postings at accrual:** `merchant_receivable` ↔
  `merchant_payable` + `commission_revenue` + `tax_payable_marketplace`.
- **Postings at payout:** `merchant_payable` ↔ `cash` (bank).
- **Disputes:** opening a dispute applies the debit immediately
  (`merchant_receivable` debited); resolution affects the dispute
  state, not the ledger, unless explicitly reversed by an admin
  journal entry.
- **Reconciliation:** daily against `ledger-service`; drift opens
  a P1 ticket via
  `restaurant_settlement.audit.reconciliation_drift.v1`.
- **Human operator path:** admin adjustments via `support-service`
  / `admin-service`; force-payout requires
  `restaurant-settlement.admin` role.

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

- **Depends on**: [`admin-service`](../admin-service/README.md), [`audit-service`](../audit-service/README.md), [`configuration-service`](../configuration-service/README.md), [`food-payment-integration-service`](../food-payment-integration-service/README.md), [`ledger-service`](../ledger-service/README.md), [`merchant-service`](../merchant-service/README.md), [`notification-service`](../notification-service/README.md), [`payment-service`](../payment-service/README.md), [`support-service`](../support-service/README.md)
- **Depended on by**: [`food-payment-integration-service`](../food-payment-integration-service/README.md), [`ledger-service`](../ledger-service/README.md), [`merchant-service`](../merchant-service/README.md), [`payment-service`](../payment-service/README.md)

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

- [`../../workflows/FOOD_ORDER_WORKFLOWS.md`](../../workflows/FOOD_ORDER_WORKFLOWS.md) — end-to-end order/delivery flows
- [`../../workflows/PAYMENT_WORKFLOWS.md`](../../workflows/PAYMENT_WORKFLOWS.md) — authorize/capture/refund/settlement
- [`../../workflows/MERCHANT_WORKFLOWS.md`](../../workflows/MERCHANT_WORKFLOWS.md) — merchant onboarding, menu ops
- [`../../workflows/ACCOUNTING_WORKFLOWS.md`](../../workflows/ACCOUNTING_WORKFLOWS.md) — accounting view (marketplace VAT, merchant payable, payout, dispute debit)
