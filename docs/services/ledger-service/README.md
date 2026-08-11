# ledger-service

## 1. Purpose

`ledger-service` is the **platform's authoritative double-entry
financial ledger**. It is the source of truth for the chart of
accounts and for every money movement that happens on the
platform. Every payment, refund, earning, settlement, payout, and
fee is recorded here as a balanced debit / credit pair. The
ledger never "moves" money; it records the *facts* about money
movement, so that reconciliation against providers, banks, and
wallets is always possible.

## 2. Bounded Context

Bounded context: **Double-Entry Financial Ledger**.

- **In scope**: chart of accounts, postings (debit / credit
  pairs), account types (asset / liability / equity / revenue /
  expense), posting immutability, retention, reconciliation jobs,
  reporting.
- **Out of scope**: the payment provider integration (owned by
  `payment-service`), the wallet's runtime balance (owned by
  ``payment-service` (wallet)`), merchant settlement mechanics (owned by
  ``payment-service` (merchant settlement)`), driver / courier earnings
  (owned by their respective services).

## 3. Responsibilities

- Maintain the **chart of accounts** (a hierarchical tree of
  accounts, each with a type and a currency).
- Accept **postings**: a posting is a pair of ledger entries
  (debit + credit) that together represent a single money fact.
  Sum of debits = sum of credits per posting.
- Record postings immutably: no UPDATE, no DELETE.
- Emit `ledger.posted.v1` for every posted posting.
- Provide per-account balance reads (with optional date range).
- Reconcile against the platform's wallet, earnings, and
  settlement services daily.
- Generate the financial reports (trial balance, balance sheet,
  income statement) on demand.
- Support multi-currency: every account has a single currency;
  conversions are explicit operations (separate journal entry).

## 4. Explicitly NOT Owned

- Payment provider integration — owned by `payment-service`.
- Wallet runtime balance — owned by ``payment-service` (wallet)`.
- Settlement scheduling — owned by ``payment-service` (merchant settlement)`.
- Earnings / withdrawal orchestration — owned by
  ``payment-service` (courier earnings)` / ``payment-service` (driver earnings)`.
- The platform's product state (rides, food orders) — those
  services emit events; this service records the resulting
  postings.

## 5. Actors

| Actor | Type | Access |
|-------|------|--------|
| `payment-service` | system | posts on capture / refund (write) |
| ``payment-service` (wallet)` | system | posts on credit / debit (write) |
| ``payment-service` (merchant settlement)` | system | posts on accrual / payout (write) |
| ``payment-service` (courier earnings)` | system | posts on accrual / payout (write) |
| ``payment-service` (driver earnings)` | system | posts on accrual / payout (write) |
| ``payment-service` (food saga)` | system | posts on capture / refund (write) |
| ``payment-service` (ride saga)` | system | posts on capture / refund (write) |
| `admin-service` | system | reads; manual journal entries (admin) |
| ``admin-service` (support module)` | system | reads; investigation (admin) |
| `reporting-service` | system | reads for reports (read) |

## 6. Dependencies

### Synchronous (REST)

- None directly. The ledger is a pure persistence layer; it does
  not call other services.

### Asynchronous (events consumed)

- `payment.captured.v1` from `payment-service` — post the
  payment-capture fact — dedup: inbox.
- `payment.refund.completed.v1` from `payment-service` — post
  the refund fact — dedup: inbox.
- `wallet.credited.v1` / `wallet.debited.v1` /
  `wallet.held.v1` / `wallet.released.v1` / `wallet.captured.v1`
  from ``payment-service` (wallet)` — post the wallet facts — dedup: inbox.
- `merchant.settlement.accrued.v1` /
  `merchant.payout.completed.v1` from
  ``payment-service` (merchant settlement)` — post the merchant facts —
  dedup: inbox.
- `courier.earning.accrued.v1` /
  `courier.withdrawal.completed.v1` from
  ``payment-service` (courier earnings)` — post the courier facts — dedup:
  inbox.
- `driver.earning.accrued.v1` /
  `driver.withdrawal.completed.v1` from
  ``payment-service` (driver earnings)` — post the driver facts — dedup:
  inbox.
- `configuration.updated.v1` — reload.

## 7. Technology Assumptions

- Runtime: Node 20 (TypeScript).
- Database: PostgreSQL 19 (per-service schema `ledger`).
- Cache: Redis (per-service) for hot account balances.
- Event broker: Kafka.

## 8. Database Ownership

- Schema: `ledger`
- Migrations: `services/ledger-service/migrations/`.
- Soft delete: no (the ledger is append-only; corrections are
  new postings).
- Partitioning: `postings` is range-partitioned by month.

## 9. API Overview

| Method | URI | Auth | Purpose |
|--------|-----|------|---------|
| POST | `/v1/postings` | bearer (service) | post a balanced posting |
| GET | `/v1/postings/{id}` | bearer (service / admin) | read |
| GET | `/v1/accounts` | bearer (service / admin) | list accounts |
| GET | `/v1/accounts/{code}` | bearer (service / admin) | read |
| GET | `/v1/accounts/{code}/balance` | bearer (service / admin) | current balance |
| GET | `/v1/accounts/{code}/balance?from=…&to=…` | bearer (service / admin) | balance over a range |
| GET | `/v1/postings?account=…&from=…&to=…` | bearer (service / admin) | list postings |
| GET | `/v1/reports/trial-balance?date=…` | bearer (admin) | trial balance |
| GET | `/v1/reports/balance-sheet?date=…` | bearer (admin) | balance sheet |
| GET | `/v1/reports/income-statement?from=…&to=…` | bearer (admin) | P&L |
| POST | `/v1/journal-entries` | bearer (admin) | manual journal entry (admin) |

(Full contracts in `INTEGRATION.md`.)

## 10. Events Produced

| Event | Trigger | Consumers |
|-------|---------|-----------|
| `ledger.posted.v1` | every posting | `reporting-service`, `audit-service`, all money-movement services (for reconciliation) |
| `ledger.audit.journal_entry_logged.v1` | every posting | `audit-service` |
| `ledger.audit.reconciliation_drift.v1` | daily reconciliation drift | `admin-service`, ``admin-service` (support module)` |

## 11. Events Consumed

All money-movement events from the financial services. The
`POST /v1/postings` API is the primary write path; the events
are an alternative for async propagation.

## 12. External Integrations

None. The ledger is a closed system.

## 13. Configuration

| Key | Type | Source | Notes |
|-----|------|--------|-------|
| `ledger.retention_years` | int | `configuration-service` | default 10 (regulatory) |
| `ledger.partition_precreate_months` | int | `configuration-service` | default 12 |
| `ledger.reconciliation_window_days` | int | `configuration-service` | default 1 |

## 14. Security

- AuthN: JWT bearer (Keycloak `platform-services` for
  service-to-service, `platform-internal` for admin).
- AuthZ: only authorised services may post; admins may post
  manual journal entries (with audit note).
- Secrets: no provider credentials; no PII.
- PII: none.

## 15. Observability

- Logs: JSON to stdout with `correlation_id`, `posting_id`,
  `account_code`, `tenant_id`.
- Metrics: `ledger_postings_total{account_type,currency}`,
  `ledger_posting_seconds`,
  `ledger_balance_total{account_type,currency}`,
  `ledger_reconciliation_drift`.
- Traces: OpenTelemetry; root span per posting.
- Health: `/health`, `/ready`, `/started`.

## 16. Scalability

- Replicas: 6 (default) — HPA on `kafka_consumer_lag` and
  `ledger_postings_total` rate.
- Hot path: `POST /v1/postings` and `GET /v1/accounts/{code}/balance`.

## 17. Local Development

- `docker compose up ledger-service` brings up the service,
  PostgreSQL, Kafka, and a seed script with the default chart of
  accounts.
- Tests: `pnpm test`, `pnpm test:e2e`.

## 18. Deployment

- Image: `registry.platform.io/ledger-service:{version}`.
- Replicas: 6 (per region).
- Resource limits: 1 vCPU / 1 GiB.
- Migrations: separate job; the chart of accounts is seeded in
  the same job.
- Retention: 10 years (regulatory).

## 19. Accounting impact

`ledger-service` is the **audit layer for every accounting fact** on the
platform. It is the system of record for the chart of accounts, balanced
postings (debit = credit), tax and revenue recognition, expense
recognition, payable accruals, multi-currency conversions, and period
close. It does not compute tax (``pricing-service` (tax)` does) and does not move
money at the provider level (`payment-service` does); it records the
immutable double-entry trace of every money fact that crosses a
financial service boundary.

- **What money facts it owns:** the chart of accounts, postings,
  account balances, journal entries, trial balance, balance sheet,
  income statement.
- **Postings:** every money-movement event from `payment-service`,
  ``payment-service` (wallet)`, ``payment-service` (merchant settlement)`,
  ``payment-service` (driver earnings)`, ``payment-service` (courier earnings)`, the two
  payment-integration sagas, and admin journal entries produces a
  matching balanced posting. The per-trip guaranteed-reward
  flow drives two new events: `trip.reward.granted.v1` (driver
  top-up via `6302_guaranteed_minimum` + customer credit via
  `2100_customer_credit_liability`) and `trip.reward.reversed.v1`
  (new reversing row — never an UPDATE / DELETE). The ledger is
  an informational consumer of both: the operational postings
  flow through the downstream services (``payment-service` (driver earnings)`,
  ``payment-service` (wallet)`); the ledger reconciles against them.
- **Reconciliation:** runs daily at 04:00 UTC against every
  operational financial service; drift opens a P1 ticket via
  `ledger.audit.reconciliation_drift.v1`.
- **Human operator path:** manual journal entries via
  `POST /v1/journal-entries` (admin / `ledger.admin` role;
  `audit_note` ≥ 10 chars; reversible only by another manual
  entry).

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

- **Depends on**: [`admin-service`](../admin-service/README.md), [`audit-service`](../audit-service/README.md), [`configuration-service`](../configuration-service/README.md), [``payment-service` (courier earnings)`](../payment-service/README.md), [``payment-service` (driver earnings)`](../payment-service/README.md), [``payment-service` (food saga)`](../payment-service/README.md), [`payment-service`](../payment-service/README.md), [`reporting-service`](../reporting-service/README.md), [``payment-service` (merchant settlement)`](../payment-service/README.md), [``payment-service` (ride saga)`](../payment-service/README.md), [``admin-service` (support module)`](../admin-service/README.md), [``payment-service` (wallet)`](../payment-service/README.md)
- **Depended on by**: [``courier-service` (dispatch)`](../courier-service/README.md), [``payment-service` (courier earnings)`](../payment-service/README.md), [`customer-service`](../customer-service/README.md), [``courier-service` (delivery)`](../courier-service/README.md), [``payment-service` (driver earnings)`](../payment-service/README.md), [``payment-service` (food saga)`](../payment-service/README.md), [`identity-service`](../identity-service/README.md), [`payment-service`](../payment-service/README.md), [``payment-service` (merchant settlement)`](../payment-service/README.md), [``payment-service` (ride saga)`](../payment-service/README.md), [``payment-service` (wallet)`](../payment-service/README.md)

> Full dependency map in [`../README.md`](../README.md) and [`../../architecture/MICROSERVICES_MAP.md`](../../architecture/MICROSERVICES_MAP.md).

### Platform-wide

- [`../../shared/README.md`](../../shared/README.md) — `platform-spring-boot-starter` shared library (the single source of cross-cutting code for all Spring Boot services in the platform)
- [`../../shared/PLATFORM_BASELINE.md`](../../shared/PLATFORM_BASELINE.md) — single source for PostgreSQL 19, Kafka, Keycloak, Redis, OpenTelemetry, Vault, deployment, DR (do not restate these in this README)
- [`../../architecture/SERVICE_ISOLATION.md`](../../architecture/SERVICE_ISOLATION.md) — **how this service behaves when a downstream is down** (timeout / bulkhead / circuit / retry / fallback, by class: CRITICAL / DEGRADABLE / BEST-EFFORT)
- [`../../architecture/DOWNSTREAM_ERROR_CATALOG.md`](../../architecture/DOWNSTREAM_ERROR_CATALOG.md) — **canonical error-code catalog + propagation rules** (the `downstream` block, forward/translate/degrade/reject)
- [`../RECOMMENDATIONS.md`](../RECOMMENDATIONS.md) — platform-wide technology map (language, framework, version baseline, admin/RBAC pattern)
- [`../../README.md`](../../README.md) — services overview (the catalog of all 20 services)
- [`../../../main.md`](../../../main.md) — top-level platform specification (architecture, Keycloak, PostgreSQL 19, messaging, observability baseline)
- [`../../shared/OSS_DEPENDENCIES.md`](../../shared/OSS_DEPENDENCIES.md) — **open-source dependencies & license attribution** (platform-wide OSS projects + per-language OSS libraries with SPDX IDs; per-service bundle index; license compatibility matrix)

### Workflows this service participates in

- [`../../workflows/PAYMENT_WORKFLOWS.md`](../../workflows/PAYMENT_WORKFLOWS.md) — authorize/capture/refund/settlement
- [`../../workflows/ACCOUNTING_WORKFLOWS.md`](../../workflows/ACCOUNTING_WORKFLOWS.md) — accounting view (tax, expense, payable, remittance, period close)
