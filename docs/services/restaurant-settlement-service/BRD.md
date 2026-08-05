# restaurant-settlement-service — Business Requirements Document

## 1. Document Purpose

This BRD is the source of truth for **what** the
restaurant-settlement service does. It is read by finance,
product, operations, support, and engineering.

## 2. Business Context

When a customer pays for a food order, the platform splits the
money three ways: the merchant gets the bulk (less the
commission), the courier gets the delivery fee, and the platform
keeps the commission. The merchant's share is the largest single
flow out of the platform's wallet.

The merchant's trust in the platform is built on three things:
the **accuracy** of the amount owed, the **timeliness** of the
payout, and the **clarity** of the statement. The
`restaurant-settlement-service` exists to make all three reliable.

## 3. Objectives

| ID | Objective | Metric |
|----|-----------|--------|
| BR--001 | Accrue the merchant's payable within 5 minutes of `food.payment.completed.v1` | accrual latency |
| BR--002 | Pay every merchant on the configured cadence (default weekly) | on-time payout rate |
| BR--003 | Make every accrual immutable and audit-traceable | 100% ledger rows audited |
| BR--004 | Surface failed payouts to ops within 5 minutes | time-to-ticket |
| BR--005 | Reconcile against `ledger-service` daily with zero drift | reconciliation diff = 0 |
| BR--006 | Provide a merchant-facing statement that is always up to date | statement freshness ≤ 1 minute |

## 4. Stakeholders

| Stakeholder | Role | Interest |
|-------------|------|----------|
| Merchants | downstream consumer | accurate, timely payouts; clear statements |
| Finance | downstream consumer | correct commission; reconciliation |
| Product (Food) | owns the food marketplace | merchant satisfaction |
| Operations | city ops | failed payout handling |
| Support | tier-2 | dispute handling; statement queries |
| Engineering (Financial Domain) | implements | reliability; observability |

## 5. Actors / Personas

- **Merchant owner** — sees the statement; updates bank details
  (in `merchant-service`); disputes a charge.
- **Customer** — sees nothing here directly; their refund flows
  affect the merchant.
- **Support agent** — opens disputes; force-pays a stuck payout.
- **Finance** — reviews reconciliation reports.

## 6. Business Capabilities

- Accrue the merchant's payable on `food.payment.completed.v1`.
- Apply proportional debits on refunds (`partial_refund`,
  `full_refund`).
- Hold merchant payouts when `merchant.suspended.v1` is received.
- Schedule a payout run on the configured cadence.
- Orchestrate the bank transfer via `payment-service`.
- Retry failed payouts with backoff; surface to ops on
  exhaustion.
- Open / resolve disputes (quality, chargeback).
- Provide a daily / weekly / monthly statement.
- Reconcile against `ledger-service` daily.

## 7. Business Requirements

| ID | Requirement | Priority | Source |
|----|-------------|----------|--------|
| BR--010 | The service MUST accrue the merchant's payable on `food.payment.completed.v1`. | MUST | Finance |
| BR--011 | The service MUST be idempotent on the accrual trigger. | MUST | Architecture |
| BR--012 | The service MUST NOT allow an accrual to be modified once posted. | MUST | Audit |
| BR--013 | The service MUST compute the payout on the configured cadence. | MUST | Finance |
| BR--014 | The service MUST initiate a payout via `payment-service` when the merchant's payable ≥ `min_payout_minor`. | MUST | Finance |
| BR--015 | The service MUST retry a failed payout up to `payout_max_retries` with backoff. | MUST | Operations |
| BR--016 | The service MUST surface a failed payout to `support-service` after retries are exhausted. | MUST | Operations |
| BR--017 | The service MUST provide a statement view to the merchant. | MUST | Product |
| BR--018 | The service MUST emit `merchant.settlement.accrued.v1` on every accrual. | MUST | Reporting |
| BR--019 | The service MUST reconcile against `ledger-service` daily. | MUST | Finance |
| BR--020 | The service MUST respect per-merchant payout schedule overrides. | SHOULD | Finance |
| BR--021 | The service MUST allow an admin to force a manual payout (with audit note). | MUST | Operations |
| BR--022 | The service MUST support per-city commission overrides. | SHOULD | Finance |
| BR--023 | The service MUST apply proportional debits on refunds. | MUST | Finance |
| BR--024 | The service MUST pause payouts on `merchant.suspended.v1`. | MUST | Risk |

## 8. Business Rules

| ID | Rule | Notes |
|----|------|-------|
| BR--030 | The merchant's payable is the sum of `accrued` rows minus the sum of `paid_out` rows. | |
| BR--031 | Refunds debit the merchant's payable proportionally. | |
| BR--032 | A merchant may not have two pending payouts at the same time. | |
| BR--033 | The payout schedule is per-merchant (default weekly). | |
| BR--034 | The commission rate is per-merchant (default per city). | |
| BR--035 | The minimum payout is `min_payout_minor` (default 1000). | |
| BR--036 | The earnings ledger is append-only; corrections are new rows. | |
| BR--037 | Payouts are retried with exponential backoff: 5m, 30m, 2h. | |

## 9. Assumptions

- The merchant's bank details are stored in `payment-service` as
  a tokenised reference; this service does NOT see them.
- Payouts are processed by `payment-service` (provider bank
  transfer).
- The merchant's "available payable" is the sum of all
  `accrued` rows minus the sum of all `paid_out` rows minus
  any pending debits.
- A suspended merchant's payouts are paused but their payable
  continues to accrue.

## 10. Constraints

- The service MUST be Tier-1 SLO (99.95%).
- The service MUST accrue in p99 ≤ 5 minutes of
  `food.payment.completed.v1`.
- The service MUST NOT store merchant PII (name, email, tax id,
  bank account).
- The service MUST NOT store the platform's chart of accounts.

## 11. Dependencies

| Dependency | Type | Notes |
|------------|------|-------|
| `merchant-service` | service | merchant profile (read); suspended events |
| `food-payment-integration-service` | consumer | subscribes to events |
| `payment-service` | service | executes payouts |
| `ledger-service` | service | double-entry |
| `notification-service` | service | merchant statements |
| `support-service` / `admin-service` | service | admin tools |
| `configuration-service` | service | tuning |
| `reporting-service` | consumer | subscribes to events |

## 12. Business Workflows

- Accrual on `food.payment.completed.v1`.
- Proportional debit on refund.
- Payout run (scheduled).
- Payout retry on failure.
- Dispute (debit) handling.
- Daily reconciliation against `ledger-service`.

## 13. Exception Workflows

- **Payout fails persistently**: opens a P1 ticket; an admin
  can force a manual payout via `payment-service`.
- **Merchant's bank details are invalid**: the payout is
  rejected; the merchant is prompted to update.
- **Reconciliation drift detected**: a P1 ticket is opened; the
  ops team investigates.
- **Merchant is suspended mid-payout**: the payout is paused;
  the merchant is informed.

## 14. Success Criteria

- 99.99% of `food.payment.completed.v1` events result in an
  accrual within 5 minutes.
- 100% of payouts are processed within 1 business day of the
  scheduled date.
- Reconciliation diff = 0 every day for 30 consecutive days.
- 0 ledger rows modified after insert (verified by audit).

## 15. KPIs

| KPI | Target | Measurement |
|-----|--------|-------------|
| Accrual p99 | ≤ 5 min | from event to `merchant.settlement.accrued.v1` |
| On-time payout rate | ≥ 99% | payouts processed on schedule |
| Payout success rate | ≥ 99% | completed / scheduled |
| Payout retries (avg) | ≤ 1.5 | per payout |
| Reconciliation diff | 0 | per day |
| Statement freshness | ≤ 1 min | from event to API response |

## 16. Acceptance Criteria

- An accrual happens within 5 minutes of every
  `food.payment.completed.v1` (verified by integration test).
- A payout succeeds end-to-end in staging.
- A failed payout is retried up to `payout_max_retries` and
  surfaced to support after exhaustion.
- The reconciliation job reports zero drift over 7 days.
- The statement view is up to date within 1 minute.
- The admin force-payout is audit-logged.
- A merchant's payouts are paused on `merchant.suspended.v1`.

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

### Platform-wide

- [`../../shared/README.md`](../../shared/README.md) — `platform-spring-boot-starter` shared library (the single source of cross-cutting code for all Spring Boot services in the platform)
- [`../RECOMMENDATIONS.md`](../RECOMMENDATIONS.md) — platform-wide technology map (language, framework, version baseline, admin/RBAC pattern)
- [`../../README.md`](../../README.md) — services overview (the catalog of all 58 services)
- [`../../../main.md`](../../../main.md) — top-level platform specification (architecture, Keycloak, PostgreSQL 18, messaging, observability baseline)

