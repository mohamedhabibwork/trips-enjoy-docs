# wallet-service — Business Requirements Document

## 1. Document Purpose

This BRD is the source of truth for the wallet business — what
the customer can do with their wallet, when, and how. It is read
by product, finance, operations, support, and engineering.

## 2. Business Context

The wallet is the platform's internal record of customer funds.
It enables:

- **Top-up**: the customer charges their payment method; the
  money is recorded in the wallet.
- **Payment**: the integration services debit the wallet
  (typically via hold → capture) when the customer pays for a
  ride or food order.
- **Refund**: a refund is credited to the wallet (or to the
  original method, depending on policy).
- **Statement**: the customer can see their balance and history.

The wallet is **not** a bank account. It is a platform-internal
ledger that mirrors what the platform owes the customer. The
bank's actual money lives with the payment provider; the
`ledger-service` is the platform's authoritative double-entry
record.

The `wallet-service` exists to make this internal record
**fast, accurate, and reconcilable** — and to ensure the
customer can never spend more than they have.

## 3. Objectives

| ID | Objective | Metric |
|----|-----------|--------|
| BR--001 | Make the balance visible in real time | balance read p99 ≤ 50ms |
| BR--002 | Make every transaction immutable and audit-traceable | 100% ledger rows audited |
| BR--003 | Ensure the customer can never spend more than they have | holds + balance check |
| BR--004 | Reconcile against `ledger-service` daily with zero drift | reconciliation diff = 0 |
| BR--005 | Make the statement always up to date | statement freshness ≤ 1 minute |
| BR--006 | Support closed-loop refunds (refund to wallet) | 100% coverage |
| BR--007 | Provide a top-up flow that is fast and reliable | top-up p99 ≤ 5s |

## 4. Stakeholders

| Stakeholder | Role | Interest |
|-------------|------|----------|
| Customers | end users | fast, accurate, trustworthy balance |
| Product (Ride / Food) | owns the marketplace | smooth payment experience |
| Finance | downstream consumer | reconciliation; refunds |
| Operations | city ops | failed top-up handling |
| Support | tier-2 | statement queries; manual adjustments |
| Engineering (Financial Domain) | implements | reliability; observability |

## 5. Actors / Personas

- **Customer** — top up, view balance, view statement.
- **Integration services** — hold, capture, release.
- **Support agent** — manual adjustments.
- **Finance** — review reconciliation reports.

## 6. Business Capabilities

- Maintain the wallet balance per user.
- Apply holds (reservations) for pending charges.
- Release holds on cancellation.
- Capture holds on completion (commit the money).
- Credit / debit on `payment.captured.v1` /
  `payment.refund.completed.v1`.
- Provide a top-up flow.
- Provide a statement view.
- Reconcile against `ledger-service` daily.

## 7. Business Requirements

| ID | Requirement | Priority | Source |
|----|-------------|----------|--------|
| BR--010 | The service MUST be the source of truth for the wallet balance. | MUST | Architecture |
| BR--011 | The service MUST NOT allow the customer to spend more than they have. | MUST | Risk |
| BR--012 | The service MUST be idempotent on every state-changing operation. | MUST | Architecture |
| BR--013 | The service MUST apply holds atomically (hold + balance update in the same transaction). | MUST | Risk |
| BR--014 | The service MUST release holds on cancellation. | MUST | Risk |
| BR--015 | The service MUST capture holds on completion. | MUST | Risk |
| BR--016 | The service MUST reconcile against `ledger-service` daily. | MUST | Finance |
| BR--017 | The service MUST provide a statement view to the user. | MUST | Product |
| BR--018 | The service MUST emit `wallet.credited.v1`, `wallet.debited.v1`, `wallet.held.v1`, `wallet.released.v1`, `wallet.captured.v1`. | MUST | Architecture |
| BR--019 | The service MUST support closed-loop refunds (refund to wallet). | MUST | Finance |
| BR--020 | The service MUST allow an admin to manually adjust the balance (with audit note). | MUST | Support |
| BR--021 | The service MUST honour `customer.suspended.v1` by blocking new transactions. | MUST | Risk |
| BR--022 | The service MUST auto-release holds older than `hold_ttl_minutes`. | SHOULD | Risk |

## 8. Business Rules

| ID | Rule | Notes |
|----|------|-------|
| BR--030 | A hold's amount MUST NOT exceed the available balance. | |
| BR--031 | A capture's amount MUST equal the hold's amount. | |
| BR--032 | A release of a hold returns the amount to available. | |
| BR--033 | The wallet's currency is fixed at creation; multi-currency conversion is not supported. | |
| BR--034 | The transaction log is append-only. | |
| BR--035 | The balance is `credits - debits - holds`. | |

## 9. Assumptions

- The customer has a wallet per currency (default one per
  user).
- The payment provider is integrated via `payment-service`.
- The integration services use holds for ride/food payments.
- Refunds to the wallet are an alternative to refunds to the
  original method (per the food-payment-integration service's
  policy).

## 10. Constraints

- The service MUST be Tier-1 SLO (99.95%).
- The service MUST complete a hold / capture in p99 ≤ 100ms.
- The service MUST NOT store customer PII (name, email, phone).
- The service MUST be within PCI-DSS SAQ-A scope (no card data).

## 11. Dependencies

| Dependency | Type | Notes |
|------------|------|-------|
| `customer-service` | service | customer profile (read) |
| `payment-service` | service | top-up charge; refund |
| `ledger-service` | service | reconciliation |
| `ride-payment-integration-service` | consumer | holds / captures |
| `food-payment-integration-service` | consumer | holds / captures |
| `notification-service` | service | top-up / refund notifications |
| `support-service` / `admin-service` | service | admin tools |
| `configuration-service` | service | tuning |

## 12. Business Workflows

- Top-up (charge payment method → credit wallet).
- Hold (reserve funds for a pending charge).
- Capture (commit the held funds to the integration service).
- Release (return the hold to available).
- Refund (credit back to wallet).
- Manual adjustment (admin).
- Daily reconciliation.

## 13. Exception Workflows

- **Top-up fails (payment declined)**: no credit; the user is
  told the top-up failed.
- **Capture fails (insufficient balance at capture time)**:
  rare (the hold should have prevented it); if it happens, the
  integration service is notified; the wallet is reconciled
  with a manual adjustment.
- **Hold TTL expired**: the hold is auto-released; the
  integration service is notified.

## 14. Success Criteria

- 99.99% of transactions are applied within 1 second.
- 0 untracked balance drift (reconciliation reports zero drift
  for 30 consecutive days).
- 0 holds over-committing the balance (verified by invariant
  checks).
- 100% of refunds are applied within 1 minute of the trigger.

## 15. KPIs

| KPI | Target | Measurement |
|-----|--------|-------------|
| Balance read p99 | ≤ 50ms | from API call to response |
| Hold / capture p99 | ≤ 100ms | from API call to commit |
| Top-up success rate | ≥ 99% | completed / attempted |
| Reconciliation diff | 0 | per day |
| Statement freshness | ≤ 1 min | from event to API response |

## 16. Acceptance Criteria

- A hold + capture succeeds end-to-end in staging.
- A hold release returns the amount to available.
- A top-up credits the wallet on `payment.captured.v1`.
- A refund debits the wallet on `payment.refund.completed.v1`.
- The reconciliation job reports zero drift over 7 days.
- A suspended customer cannot top up.
- The admin manual adjustment is audit-logged.

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

