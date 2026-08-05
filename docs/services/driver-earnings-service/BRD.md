# driver-earnings-service — Business Requirements Document

## 1. Document Purpose

Read by finance, driver operations, engineering, and customer
support to align on what `driver-earnings-service` does. The
earnings ledger is the driver's view of the platform; getting it
wrong means a driver is underpaid, overpaid, or unable to
withdraw.

## 2. Business Context

A driver earns money on every completed trip. The platform keeps
a running balance; the driver can withdraw to a bank account
whenever the balance exceeds a minimum. This service is the system
of record for the ledger, the balance, and the withdrawal flow.

## 3. Objectives

| ID | Objective | Metric |
|----|-----------|--------|
| BR--001 | Be the single source of truth for driver earnings | 100% of trip completions produce an earning |
| BR--002 | Accrue the earning within 5 minutes of `ride.payment.completed.v1` | p99 ≤ 5min |
| BR--003 | Honour the minimum withdrawal amount | 100% of withdrawals below minimum are rejected |
| BR--004 | Honour the cooldown | 100% of withdrawals within cooldown are rejected |
| BR--005 | Pay the driver within 1 business day of a withdrawal request | p99 ≤ 1 BD |
| BR--006 | Be idempotent: replaying the same event does not double-accrue | 100% |
| BR--007 | Notify the driver on every accrual and withdrawal | 100% |

## 4. Stakeholders

| Stakeholder | Role | Interest |
|-------------|------|----------|
| Finance | owner | ledger accuracy, commission correctness |
| Driver Operations | reviewer | withdrawal policy, payout SLAs |
| Driver | consumer | accurate balance, fast withdrawal |
| Customer Support | operator | read earnings, manage bank details on behalf |
| Compliance | reviewer | audit trail, GDPR |

## 5. Actors / Personas

- **Driver** — sees the balance; requests withdrawals; manages bank
  details.
- **`ride-payment-integration-service`** — triggers accrual on
  payment completed.
- **`payment-service`** — pays the driver to the bank.
- **`wallet-service`** — holds the balance during withdrawal.
- **`ledger-service`** — records the double-entry posting.
- **Customer Support** — reads earnings; updates bank details.
- **Admin** — force-adjusts with a reason.

## 6. Business Capabilities

- Accrue an earning on `ride.payment.completed.v1`.
- Accrue a tip / bonus on `trip.completed.v1` (if attached).
- Maintain a running balance.
- Allow withdrawal requests.
- Cooperate with `payment-service` for the payout.
- Cooperate with `wallet-service` for the hold / release.
- Cooperate with `ledger-service` for the posting.
- Emit accrual and withdrawal events.
- Allow the driver to manage bank details.

## 7. Business Requirements

| ID | Requirement | Priority | Source |
|----|-------------|----------|--------|
| BR--010 | The service MUST accrue the driver earning on `ride.payment.completed.v1` with `Idempotency-Key=trip:{trip_id}:earn`. | MUST | Finance |
| BR--011 | The service MUST compute the earning as `final_fare - commission`. | MUST | Finance |
| BR--012 | The service MUST allow the driver to request a withdrawal when the balance is ≥ the minimum. | MUST | Driver Operations |
| BR--013 | The service MUST reject a withdrawal within the cooldown window. | MUST | Driver Operations |
| BR--014 | The service MUST call `wallet-service.hold(amount)` before payout. | MUST | Finance |
| BR--015 | The service MUST call `payment-service.payout(bank_details, amount)` to pay the driver. | MUST | Finance |
| BR--016 | The service MUST release the hold on payout failure. | MUST | Finance |
| BR--017 | The service MUST post the withdrawal to the ledger. | MUST | Finance |
| BR--018 | The service MUST emit `driver.earning.accrued.v1` on accrual. | MUST | Platform Event Standards |
| BR--019 | The service MUST emit `driver.withdrawal.requested.v1` on withdrawal request. | MUST | Platform Event Standards |
| BR--020 | The service MUST emit `driver.withdrawal.completed.v1` on payout success. | MUST | Platform Event Standards |
| BR--021 | The service MUST emit `driver.withdrawal.failed.v1` on payout failure. | MUST | Platform Event Standards |
| BR--022 | The service MUST allow the driver to manage bank details (up to N). | MUST | Driver Operations |
| BR--023 | The service MUST encrypt bank details at rest (per-column encryption). | MUST | Security |
| BR--024 | The service MUST allow admin to force-adjust with a reason. | MUST | Customer Support |
| BR--025 | The service MUST record an audit event for every state transition. | MUST | Compliance |

## 8. Business Rules

| ID | Rule | Notes |
|----|------|-------|
| BR--030 | Earning is `final_fare × (1 - commission_pct)`. | Configurable per city. |
| BR--031 | The minimum withdrawal is `driver_earnings.withdrawal.min_minor.{currency}`. | |
| BR--032 | The cooldown is `driver_earnings.withdrawal.cooldown_hours`. | |
| BR--033 | A driver can have at most N bank details. | Configurable. |
| BR--034 | A payout failure triggers `driver.withdrawal.failed.v1` and a support ticket. | |
| BR--035 | The earnings ledger is append-only; corrections are negative entries with a reason. | |

## 9. Assumptions

- The driver's earning account is open when they are `approved`.
- The trip's `final_fare` is the source of truth for the earning
  amount.
- The `payment-service` handles the actual bank transfer; we call
  it via a stable API.

## 10. Constraints

- The earnings ledger is append-only.
- Bank details are encrypted at rest.
- All money-movement calls use idempotency keys.

## 11. Dependencies

| Dependency | Type | Notes |
|------------|------|-------|
| `ride-payment-integration-service` | service | accrual trigger |
| `trip-service` | service | tip / bonus trigger |
| `payment-service` | service | payout |
| `wallet-service` | service | hold / release |
| `ledger-service` | service | post |
| `driver-service` | service | validate driver |
| `configuration-service` | service | commission, minimum, cooldown |
| `support-service` | service | open ticket on failure |
| `notification-service` | service | notify driver |

## 12. Business Workflows

- **Accrual on payment completed** — see `WORKFLOWS.md`.
- **Withdrawal request (happy path)** — see `WORKFLOWS.md`.
- **Withdrawal failure** — see `WORKFLOWS.md`.
- **Bank details update** — see `WORKFLOWS.md`.

## 13. Exception Workflows

- `payment-service` payout fails: release the hold, emit
  `driver.withdrawal.failed.v1`, open a support ticket, notify the
  driver.
- `wallet-service` hold fails: reject the withdrawal, notify the
  driver.
- `ledger-service` post fails: retry; on persistent failure, page
  on-call (P1).

## 14. Success Criteria

- Drivers are paid accurately and on time.
- The reconciliation job in `reporting-service` finds no drift
  between the earnings ledger and the wallet postings.
- Withdrawal failure rate is within the published envelope.

## 15. KPIs

| KPI | Target | Measurement |
|-----|--------|-------------|
| Accrual latency P99 | ≤ 5min | `driver_earnings_accrual_seconds` |
| Withdrawal success rate | ≥ 98% | `driver_withdrawals_total{status=success}` / total |
| Payout latency P99 | ≤ 1 BD | `driver_withdrawal_seconds` |
| Bank detail update success rate | ≥ 99% | `driver_bank_update_total{status=success}` / total |

## 16. Acceptance Criteria

- Replaying `ride.payment.completed.v1` for the same trip id does
  not double-accrue.
- A withdrawal below the minimum is rejected with 422.
- A withdrawal within the cooldown is rejected with 409.
- A payout failure releases the hold and emits
  `driver.withdrawal.failed.v1`.
- Bank details are encrypted at rest.

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

