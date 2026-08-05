# courier-earnings-service — Business Requirements Document

## 1. Document Purpose

This BRD is the source of truth for the courier earnings
business — what a courier is owed, when, and how. It informs
product (payouts, statements), operations (reconciliation,
disputes), and engineering.

## 2. Business Context

Couriers are paid per delivery. The amount is a function of the
delivery fee, the distance, the courier's incentive plan, and any
customer tip. The platform's cut is the commission; the courier's
net is the rest.

A courier's trust in the platform is built on the accuracy of
their earnings statement and the reliability of withdrawals. A
missing accrual or a stuck withdrawal erodes trust quickly. The
`courier-earnings-service` exists to make earnings **accurate,
immutable, auditable, and withdrawable on demand**.

## 3. Objectives

| ID | Objective | Metric |
|----|-----------|--------|
| BR--001 | Accrue a courier's earning within 5 minutes of `delivery.completed.v1` | p99 accrual latency |
| BR--002 | Make every accrual immutable and audit-traceable | 100% ledger rows audited |
| BR--003 | Make withdrawals available on demand (or per the configured window) | 100% of valid requests initiated within 30s |
| BR--004 | Surface failed payouts to ops within 5 minutes | time-to-ticket |
| BR--005 | Reconcile earnings against the ledger daily with zero drift | reconciliation diff = 0 |
| BR--006 | Provide a courier-facing statement that is always up to date | statement freshness ≤ 1 minute |

## 4. Stakeholders

| Stakeholder | Role | Interest |
|-------------|------|----------|
| Product (Food) | owns the food marketplace | timely, accurate earnings |
| Couriers (Trust & Safety) | end users | trustworthy statement, fast withdrawals |
| Finance | downstream consumer | correct commission; reconciliation |
| Operations | city ops | failed payout handling |
| Engineering (Courier Domain) | implements | reliability; observability |

## 5. Actors / Personas

- **Courier** — sees their earnings and requests withdrawals on
  the mobile app.
- **Customer** — adds a tip; the courier sees the tip arrive.
- **Operations / Support** — handles failed payouts and
  reconciliation drift.
- **Finance** — reads the commission totals for reporting.

## 6. Business Capabilities

- Accrue a base earning on `delivery.completed.v1`.
- Accrue a tip on `customer.tip.added.v1`.
- Maintain the courier earnings ledger (append-only).
- Accept a withdrawal request from the courier.
- Initiate a payout via `payment-service`.
- Retry failed payouts with backoff; surface to ops when retries
  are exhausted.
- Provide a daily / weekly / monthly statement.
- Reconcile earnings against the ledger daily.

## 7. Business Requirements

| ID | Requirement | Priority | Source |
|----|-------------|----------|--------|
| BR--010 | The service MUST accrue a courier's earning on `delivery.completed.v1`. | MUST | Product |
| BR--011 | The service MUST be idempotent on the accrual trigger. | MUST | Architecture |
| BR--012 | The service MUST NOT allow an earning to be modified once posted. | MUST | Audit |
| BR--013 | The service MUST accept a withdrawal request from the courier if the available balance is ≥ `min_withdrawal_minor`. | MUST | Product |
| BR--014 | The service MUST initiate a payout via `payment-service` within 30s of a valid request. | MUST | Product |
| BR--015 | The service MUST retry a failed payout up to `payout_max_retries` with backoff. | MUST | Operations |
| BR--016 | The service MUST surface a failed payout to `support-service` after retries are exhausted. | MUST | Operations |
| BR--017 | The service MUST provide a statement view to the courier. | MUST | Product |
| BR--018 | The service MUST emit `courier.earning.accrued.v1` on every accrual. | MUST | Reporting |
| BR--019 | The service MUST reconcile against `ledger-service` daily. | MUST | Finance |
| BR--020 | The service MUST respect per-city commission overrides. | SHOULD | Finance |
| BR--021 | The service MUST allow an admin to force a manual payout (with audit note). | MUST | Operations |
| BR--022 | The service MUST allow a courier to cancel a pending withdrawal before it is initiated. | SHOULD | Product |

## 8. Business Rules

| ID | Rule | Notes |
|----|------|-------|
| BR--030 | An earning row is keyed on `(delivery_id, courier_id, type)` where `type` is `base` or `tip`. | Unique constraint. |
| BR--031 | Tips are added to the next earning accrual; they do not modify the captured base amount. | |
| BR--032 | A withdrawal's amount MUST NOT exceed the courier's available balance. | |
| BR--033 | A courier may have at most one pending withdrawal at a time. | |
| BR--034 | A withdrawal that is `initiated` may be cancelled by the courier up to 30s after creation. | |
| BR--035 | The earnings ledger is append-only; corrections are new rows. | |
| BR--036 | The commission rate is the platform's cut; the rest is the courier's net. | Per city. |

## 9. Assumptions

- The delivery fee, distance, and tip are provided by
  `food-payment-integration-service` in `food.payment.completed.v1`.
- The courier's bank details are stored in `payment-service` as
  a tokenised reference; this service does NOT see them.
- Payouts are processed by `payment-service` (bank transfer or
  wallet credit, depending on the courier's preference).
- The courier's "available balance" is the sum of all `accrued`
  rows minus the sum of all `withdrawn` rows.

## 10. Constraints

- The service MUST be Tier-1 SLO (99.95%).
- The service MUST accrue in p99 ≤ 5 minutes of
  `delivery.completed.v1`.
- The service MUST NOT store courier PII (name, email, phone).
- The service MUST NOT store bank account details.

## 11. Dependencies

| Dependency | Type | Notes |
|------------|------|-------|
| `courier-service` | service | courier profile (read) |
| `food-order-service` | service | order context (read) |
| `delivery-service` | consumer | subscribes to `delivery.completed.v1` |
| `food-payment-integration-service` | consumer | subscribes to `food.payment.completed.v1` and `customer.tip.added.v1` |
| `payment-service` | service | executes payouts |
| `wallet-service` | service | optional wallet-credit payouts |
| `ledger-service` | consumer / producer | records postings; reconciliation |
| `notification-service` | service | courier statements |
| `support-service` / `admin-service` | service | admin tools |
| `configuration-service` | service | tuning |
| `reporting-service` | consumer | subscribes to events |

## 12. Business Workflows

- Earning accrual on delivery completion.
- Tip accrual on `customer.tip.added.v1`.
- Withdrawal request → payout → completion.
- Withdrawal retry on failure.
- Daily reconciliation against `ledger-service`.

## 13. Exception Workflows

- **Payout fails persistently**: opens a P1 ticket; an admin can
  force a manual payout via `payment-service`.
- **Courier's bank details are invalid**: the withdrawal is
  rejected with a clear reason; the courier is prompted to update.
- **Reconciliation drift detected**: a P1 ticket is opened; the
  ops team investigates.
- **Tip added after the courier's withdrawal is initiated**: the
  tip is added to the available balance for the next withdrawal.

## 14. Success Criteria

- 99.99% of `delivery.completed.v1` events result in an accrual
  within 5 minutes.
- 100% of withdrawals are processed within 1 business day.
- Reconciliation diff = 0 every day for 30 consecutive days.
- 0 ledger rows modified after insert (verified by audit).

## 15. KPIs

| KPI | Target | Measurement |
|-----|--------|-------------|
| Accrual p99 | ≤ 5 min | from `delivery.completed.v1` to `courier.earning.accrued.v1` |
| Withdrawal p99 initiation | ≤ 30s | from request to `payment-service` call |
| Withdrawal success rate | ≥ 99% | completed / requested |
| Payout retries (avg) | ≤ 1.5 | per withdrawal |
| Reconciliation diff | 0 | per day |
| Statement freshness | ≤ 1 min | from event to API response |

## 16. Acceptance Criteria

- An earning is accrued within 5 minutes of every
  `delivery.completed.v1` (verified by integration test).
- A withdrawal succeeds end-to-end (request → payout → completion)
  in staging.
- A failed payout is retried up to `payout_max_retries` and
  surfaced to support after exhaustion.
- The reconciliation job reports zero drift over 7 days.
- The statement view is up to date within 1 minute.
- The admin force-payout is audit-logged.

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

