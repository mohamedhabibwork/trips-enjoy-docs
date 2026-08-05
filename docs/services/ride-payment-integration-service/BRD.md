# ride-payment-integration-service — Business Requirements Document

## 1. Document Purpose

Read by finance, engineering, and operations to align on what
`ride-payment-integration-service` does. The ride payment saga is
the financial heart of the platform; getting it wrong means lost
revenue, double-charges, or unpaid drivers.

## 2. Business Context

A completed trip is not a paid trip until the customer's payment is
captured, the driver's earning is accrued, and the general ledger
records both. Each of these is owned by a different service; the
saga orchestrator owns the order, the retries, the idempotency, and
the compensation when something goes wrong. Without it, the
platform would have inconsistent money.

## 3. Objectives

| ID | Objective | Metric |
|----|-----------|--------|
| BR--001 | Be the single source of truth for the ride payment saga state | 100% of trip.completed.v1 lead to exactly one saga |
| BR--002 | Capture the customer's payment within 5 minutes of trip completion | p99 ≤ 5min |
| BR--003 | Accrue the driver's earning within 5 minutes of capture | p99 ≤ 5min |
| BR--004 | Post the ledger entry on the same saga | always |
| BR--005 | Be idempotent: replaying the same trip.completed.v1 produces the same result | 100% |
| BR--006 | Compensate on failure: void auth, refund capture, release earning | 100% of failures |
| BR--007 | Open a support ticket on failure | 100% of failures |

## 4. Stakeholders

| Stakeholder | Role | Interest |
|-------------|------|----------|
| Finance | owner | money conservation, ledger accuracy |
| Engineering (Rides) | builder | saga correctness, retry logic |
| Driver Operations | reviewer | driver is paid correctly and on time |
| Customer Support | operator | ability to read a saga, retry on customer's behalf |
| Compliance | reviewer | audit trail, idempotency |

## 5. Actors / Personas

- **`trip-service`** — emits the trigger event.
- **`payment-service`** — capture / void / refund.
- **`driver-earnings-service`** — accrue the driver earning.
- **`ledger-service`** — record the double-entry posting.
- **`customer-service`** — sees the payment in the customer's
  history.
- **Customer Support** — reads sagas, retries on the customer's
  behalf.
- **Admin** — same, with broader rights.

## 6. Business Capabilities

- Consume `trip.completed.v1` and start a saga.
- Capture the payment (with idempotency key `trip:{trip_id}:cap`).
- Accrue the driver earning (with idempotency key
  `trip:{trip_id}:earn`).
- Post the ledger (idempotent by saga id).
- Compensate on failure (void, refund, release).
- Emit `ride.payment.completed.v1` or `ride.payment.failed.v1`.
- Allow admin retry.

## 7. Business Requirements

| ID | Requirement | Priority | Source |
|----|-------------|----------|--------|
| BR--010 | The service MUST consume `trip.completed.v1` and start a saga within 5 seconds. | MUST | Finance |
| BR--011 | The service MUST call `payment-service.capture` with `Idempotency-Key=trip:{trip_id}:cap`. | MUST | Finance |
| BR--012 | The service MUST call `driver-earnings-service.accrue` with `Idempotency-Key=trip:{trip_id}:earn`. | MUST | Finance |
| BR--013 | The service MUST call `ledger-service.post` for the saga. | MUST | Finance |
| BR--014 | The service MUST emit `ride.payment.completed.v1` on success. | MUST | Platform Event Standards |
| BR--015 | The service MUST emit `ride.payment.failed.v1` on failure. | MUST | Platform Event Standards |
| BR--016 | The service MUST compensate on failure: void the authorization, refund the capture, release the earning. | MUST | Finance |
| BR--017 | The service MUST open a support ticket on failure. | MUST | Customer Support |
| BR--018 | The service MUST notify the customer on failure. | MUST | Customer Support |
| BR--019 | The service MUST be idempotent: replaying the same `trip.completed.v1` re-enters the same saga state and produces the same result. | MUST | Finance |
| BR--020 | The service MUST allow admin to force-retry with a reason. | MUST | Customer Support |
| BR--021 | The service MUST record an audit event for every saga transition. | MUST | Compliance |

## 8. Business Rules

| ID | Rule | Notes |
|----|------|-------|
| BR--030 | The capture amount is the trip's `final_fare.amount_minor`. | Tied to the trip. |
| BR--031 | The driver earning is the trip's `final_fare` minus platform commission (configured). | |
| BR--032 | The ledger posting is a double-entry: credit `customer_receivable`, credit `driver_payable`, credit `platform_revenue`. | |
| BR--033 | A failed capture aborts the saga; the trip is not paid. | Customer must update payment method. |
| BR--034 | A failed earning accrual after a successful capture triggers a refund. | We never have a capture without an earning (eventually). |
| BR--035 | A failed ledger post after capture + earning is a P1 incident; the saga is in a recoverable state. | Reconciliation catches. |

## 9. Assumptions

- The trip's `final_fare` is set before the event is emitted.
- The customer's payment method is on file and not expired (the
  pre-auth in `ride-request-service` validated this).
- The driver's earning account is open.

## 10. Constraints

- One saga per `trip_id`.
- The saga is durable in the DB; replays are safe.
- All emitted events go through the outbox.

## 11. Dependencies

| Dependency | Type | Notes |
|------------|------|-------|
| `trip-service` | service | trigger event |
| `payment-service` | service | capture / void / refund |
| `driver-earnings-service` | service | accrue |
| `ledger-service` | service | post |
| `notification-service` | service | notify customer |
| `support-service` | service | open ticket on failure |
| `configuration-service` | service | commission, retries |

## 12. Business Workflows

- **Saga (happy path)** — see `WORKFLOWS.md`.
- **Saga (capture fails)** — see `WORKFLOWS.md`.
- **Saga (earning accrual fails after capture)** — see `WORKFLOWS.md`.
- **Saga (ledger post fails after capture + earning)** — see
  `WORKFLOWS.md`.
- **Admin retry** — see `WORKFLOWS.md`.

## 13. Exception Workflows

- `payment-service` timeout: retry up to N times; on persistent
  failure, fail the saga.
- `driver-earnings-service` timeout: retry; on persistent failure,
  fail the saga and refund.
- `ledger-service` timeout: retry; on persistent failure, page
  on-call (P1).
- Both `trip-service` and the saga are down: the `trip.completed.v1`
  is retried by the broker; the saga catches up.

## 14. Success Criteria

- The ledger is always in balance (the reconciliation job in
  `reporting-service` confirms).
- Drivers are paid on time.
- Customers are never double-charged.

## 15. KPIs

| KPI | Target | Measurement |
|-----|--------|-------------|
| Capture success rate | ≥ 99.5% | `ride_payment_saga_failures_total{step=capture}` / total |
| Saga completion P99 | ≤ 5 min | `ride_payment_saga_duration_seconds` |
| Earning accrual success rate | ≥ 99.9% | `ride_payment_saga_failures_total{step=accrue}` / total |
| Support tickets opened | 1 per failure | `support.ticket.opened.v1` correlation |

## 16. Acceptance Criteria

- Replaying `trip.completed.v1` for the same trip id does not
  produce a second capture.
- A capture failure triggers `ride.payment.failed.v1` and a
  support ticket.
- An earning accrual failure after a successful capture triggers a
  refund and `ride.payment.failed.v1`.
- The ledger is posted exactly once per saga.

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

