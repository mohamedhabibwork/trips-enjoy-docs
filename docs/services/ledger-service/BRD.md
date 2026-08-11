# ledger-service — Business Requirements Document

## 1. Document Purpose

This BRD is the source of truth for the **platform's financial
ledger** — the chart of accounts, the postings, the
reconciliation, the reports. It is read by finance, audit,
regulatory, product, operations, and engineering.

## 2. Business Context

Every monetary event in the platform must be recorded in a
**double-entry** ledger. Money is never created or destroyed
outside documented flows. The platform's auditors, regulators,
and finance team rely on this ledger to:

- Verify that the platform's books balance (sum of debits = sum
  of credits per posting).
- Reconcile against the payment provider, the wallet service,
  the merchant settlement service, and the driver / courier
  earnings services.
- Produce the trial balance, balance sheet, and income statement
  at any point in time.
- Provide an immutable audit trail of every money movement for
  at least 10 years.

The `ledger-service` exists to be this authoritative record. It
is append-only; it never "moves" money; it records the *facts*
about money movement. The wallet, the settlement service, the
earnings services, and the payment service are all
*operational* layers; the ledger is the *audit* layer.

## 3. Objectives

| ID | Objective | Metric |
|----|-----------|--------|
| BR--001 | Record every money movement as a balanced double-entry posting | 100% of money events have a matching `ledger.posted.v1` |
| BR--002 | Make the ledger append-only and immutable | no UPDATE / DELETE on postings |
| BR--003 | Reconcile against operational layers daily with zero drift | reconciliation diff = 0 |
| BR--004 | Provide the chart of accounts as a hierarchical, versioned tree | 100% of accounts versioned |
| BR--005 | Support multi-currency: each account has a single currency; conversions are explicit | 100% |
| BR--006 | Retain postings for at least 10 years | partition drop only after 10y |
| BR--007 | Provide financial reports (trial balance, balance sheet, P&L) on demand | reports within 5s |
| BR--008 | Make every posting auditable and traceable to its source event | 100% of postings have `source_event_id` |

## 4. Stakeholders

| Stakeholder | Role | Interest |
|-------------|------|----------|
| Finance | downstream consumer | accurate, timely reports; reconciliation |
| Audit / Compliance | governance | immutability; retention; audit trail |
| Product (Ride / Food) | marketplace | correct money flow |
| Operations | city ops | reconciliation drift |
| Engineering (Financial Domain) | implements | reliability; observability |

## 5. Actors / Personas

- **Finance** — runs the reports; reviews the reconciliation.
- **Auditor** — reviews the chart of accounts; verifies the
  immutability; checks sample postings.
- **Engineering (Financial Domain)** — implements the postings
  in each service; ensures the ledger is updated.

## 6. Business Capabilities

- Maintain the chart of accounts (a hierarchical tree of
  accounts, each with a type and a currency).
- Accept postings (debit + credit pairs) and verify they balance.
- Emit `ledger.posted.v1` for every posting.
- Provide per-account balance reads (with optional date range).
- Reconcile against the wallet, earnings, and settlement
  services daily.
- Generate financial reports (trial balance, balance sheet, P&L).
- Support multi-currency.
- Support manual journal entries (admin only, with audit note).

## 7. Business Requirements

| ID | Requirement | Priority | Source |
|----|-------------|----------|--------|
| BR--010 | The service MUST be the source of truth for the chart of accounts. | MUST | Finance |
| BR--011 | The service MUST be append-only: no UPDATE, no DELETE on postings. | MUST | Audit |
| BR--012 | The service MUST verify that every posting is balanced (sum of debits = sum of credits). | MUST | Finance |
| BR--013 | The service MUST emit `ledger.posted.v1` for every posting. | MUST | Architecture |
| BR--014 | The service MUST provide a per-account balance read API. | MUST | Finance |
| BR--015 | The service MUST provide a trial balance report. | MUST | Finance |
| BR--016 | The service MUST provide a balance sheet report. | MUST | Finance |
| BR--017 | The service MUST provide an income statement (P&L) report. | MUST | Finance |
| BR--018 | The service MUST support multi-currency (each account has a single currency). | MUST | Finance |
| BR--019 | The service MUST retain postings for at least 10 years. | MUST | Audit / Regulatory |
| BR--020 | The service MUST support manual journal entries (admin; with audit note). | MUST | Finance |
| BR--021 | The service MUST reconcile against the wallet, earnings, and settlement services daily. | MUST | Finance |
| BR--022 | The service MUST be Tier-1 SLO (99.95%). | MUST | Architecture |
| BR--023 | The service MUST be a closed system: it does not call other services. | MUST | Architecture |

## 8. Business Rules

| ID | Rule | Notes |
|----|------|-------|
| BR--030 | Every account has a type: `asset`, `liability`, `equity`, `revenue`, `expense`. | |
| BR--031 | A posting has at least two entries (debit + credit). | |
| BR--032 | The sum of debits = the sum of credits per posting. | Enforced at insert. |
| BR--033 | A currency conversion is a separate journal entry (two postings, one in each currency). | |
| BR--034 | The chart of accounts is hierarchical: parent / child relationships are allowed. | |
| BR--035 | The chart of accounts is versioned: changes are new versions; old versions are kept. | |
| BR--036 | A manual journal entry requires an `audit_note` ≥ 10 characters. | |
| BR--037 | The sum of all asset balances = the sum of all liability + equity balances. | Enforced by the trial balance. |

## 9. Assumptions

- The platform's books are tracked in a single currency per
  account; multi-currency support is via explicit conversion
  postings.
- The chart of accounts is seeded at service start; new accounts
  are added by finance via a migration.
- The reconciliation window is 1 day; the job runs at 04:00 UTC.
- The 10-year retention is enforced by a partition drop job
  (regulatory).

## 10. Constraints

- The service MUST be Tier-1 SLO (99.95%).
- The service MUST NOT call any other service.
- The service MUST NOT delete postings; corrections are new
  postings.
- The service MUST be within the platform's audit scope.

## 11. Dependencies

| Dependency | Type | Notes |
|------------|------|-------|
| `payment-service` | consumer | subscribes to `payment.*.v1` |
| ``payment-service` (wallet)` | consumer | subscribes to `wallet.*.v1` |
| ``payment-service` (merchant settlement)` | consumer | subscribes to `merchant.*.v1` |
| ``payment-service` (courier earnings)` | consumer | subscribes to `courier.*.v1` |
| ``payment-service` (driver earnings)` | consumer | subscribes to `driver.*.v1` |
| ``payment-service` (food saga)` | consumer | subscribes to its own events |
| ``payment-service` (ride saga)` | consumer | subscribes to its own events |
| `reporting-service` | consumer | subscribes to `ledger.posted.v1` |
| `audit-service` | consumer | subscribes to `ledger.audit.*.v1` |
| `configuration-service` | service | tuning |

## 12. Business Workflows

- Posting a payment capture.
- Posting a refund.
- Posting a wallet credit / debit.
- Posting a merchant settlement accrual.
- Posting a courier / driver earning accrual.
- Posting a payout.
- Manual journal entry (admin).
- Daily reconciliation against operational layers.
- Trial balance / balance sheet / P&L report.

## 13. Exception Workflows

- **Unbalanced posting**: rejected at insert (422
  `UNBALANCED_POSTING`).
- **Account not found**: rejected at insert (422
  `ACCOUNT_NOT_FOUND`).
- **Currency mismatch**: rejected at insert (422
  `CURRENCY_MISMATCH`).
- **Reconciliation drift**: a P1 ticket is opened; finance
  investigates.

## 14. Success Criteria

- 100% of money events have a matching `ledger.posted.v1` within
  1 minute.
- 0 unbalanced postings.
- 0 untracked reconciliation drift over 30 consecutive days.
- The trial balance reports zero drift every day for 30
  consecutive days.

## 15. KPIs

| KPI | Target | Measurement |
|-----|--------|-------------|
| Posting p99 | ≤ 100ms | from request to commit |
| Posting throughput | 500 rps | sustained |
| Reconciliation lag p99 | ≤ 5 min | from event to `ledger.posted.v1` to reconciliation |
| Trial balance diff | 0 | per day |
| Reports p99 | ≤ 5s | per report |

## 16. Acceptance Criteria

- A balanced posting is accepted; an unbalanced posting is
  rejected.
- A per-account balance is correct at any point in time.
- The trial balance reports zero drift over 7 days.
- The ledger retains postings for 10 years (verified by
  partition management).
- A manual journal entry is audit-logged.
- The reconciliation job detects drift if a posting is missed.

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
- [`../../README.md`](../../README.md) — services overview (the catalog of all 20 services)
- [`../../../main.md`](../../../main.md) — top-level platform specification (architecture, Keycloak, PostgreSQL 19, messaging, observability baseline)

