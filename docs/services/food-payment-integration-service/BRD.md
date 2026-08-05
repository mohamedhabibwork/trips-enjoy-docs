# food-payment-integration-service — Business Requirements Document

## 1. Document Purpose

This BRD is the source of truth for the food payment saga — the
end-to-end orchestration of money movement triggered by a food
order. It is read by finance, product, operations, support, and
engineering.

## 2. Business Context

When a customer places a food order, the platform does not move
money immediately. The customer's card is *authorized* at checkout
(the hold), and the actual *capture* happens at delivery completion
(when the service is rendered). At capture, three things must
happen in coordination:

1. The customer is charged.
2. The merchant is credited (less commission).
3. The courier is credited (base + tip).

This is a classic distributed-transaction problem. We solve it
with an **orchestrated saga**: this service is the orchestrator.
It owns the saga state, derives idempotency keys for each step,
and ensures the whole chain is safe to retry, safe to fail, and
safe to compensate.

A missed step, a double-charge, or a stuck saga directly erodes
trust with the customer, the merchant, and the courier. The
`food-payment-integration-service` exists to make this chain
**correct, idempotent, auditable, and reconcilable**.

## 3. Objectives

| ID | Objective | Metric |
|----|-----------|--------|
| BR--001 | Capture a payment within 5 minutes of `delivery.completed.v1` | p99 capture latency |
| BR--002 | Make every saga step idempotent (replay-safe) | 100% idempotency coverage |
| BR--003 | Make every compensation explicit and audit-logged | 100% compensations logged |
| BR--004 | Emit a `food.payment.completed.v1` exactly once per successful order | 0 duplicates |
| BR--005 | Handle partial refunds correctly (per policy) | 100% correct math |
| BR--006 | Reconcile against `ledger-service` daily with zero drift | reconciliation diff = 0 |
| BR--007 | Make saga state visible to support and admin | saga API |
| BR--008 | Make tip accrual synchronous with the capture step (or within the tip window) | tip on time |

## 4. Stakeholders

| Stakeholder | Role | Interest |
|-------------|------|----------|
| Finance | downstream consumer | exact money math; ledger matches |
| Product (Food) | owns the food marketplace | smooth customer experience |
| Operations | city ops | stuck-saga handling |
| Merchants | downstream consumer | correct, timely settlement |
| Couriers | downstream consumer | correct, timely earnings |
| Customers | downstream consumer | correct, timely charge |
| Support | tier-2 | saga visibility |
| Engineering (Courier / Financial Domain) | implements | reliability; observability |

## 5. Actors / Personas

- **Customer** — experiences the charge and (if applicable) the
  refund.
- **Merchant** — receives the settlement (handled downstream).
- **Courier** — receives the earning (handled downstream).
- **Support agent** — investigates stuck sagas, force-compensates.
- **Finance** — reviews reconciliation reports.

## 6. Business Capabilities

- Create a saga at checkout (the authorize step happens at
  checkout; the saga tracks the order_id and the authorization).
- Receive `delivery.completed.v1` and start the capture step.
- On successful capture: post the ledger entry, trigger merchant
  settlement accrual, trigger courier earning accrual.
- On capture failure: start compensation (void the authorization;
  if already captured, refund).
- Handle `food.order.cancelled.v1` (pre-delivery): refund the
  authorization.
- Handle `food.order.rejected.v1`: refund the authorization.
- Handle partial refunds (support-initiated): compute the
  merchant / courier split and post the entries.
- Persist saga state; allow re-entry on consumer crash.
- Provide a saga-status API for support / admin.
- Reconcile against the ledger daily.

## 7. Business Requirements

| ID | Requirement | Priority | Source |
|----|-------------|----------|--------|
| BR--010 | The service MUST be the only service that orchestrates the food payment chain. | MUST | Architecture |
| BR--011 | The service MUST derive idempotency keys from the `food_order_id` and the step. | MUST | Architecture |
| BR--012 | The service MUST call `payment-service` for authorize at checkout (separate saga entry) and for capture at delivery. | MUST | Finance |
| BR--013 | The service MUST post a double-entry to `ledger-service` for every successful capture and every refund. | MUST | Finance |
| BR--014 | The service MUST trigger `courier-earnings-service.accrue` on capture. | MUST | Couriers |
| BR--015 | The service MUST trigger `restaurant-settlement-service.accrue` on capture. | MUST | Merchants |
| BR--016 | The service MUST support full refunds (cancellation, reject). | MUST | Food workflows |
| BR--017 | The service MUST support partial refunds (support-initiated, quality issues). | MUST | Food workflows |
| BR--018 | The service MUST NOT double-post to the ledger (idempotent on `saga_id + step`). | MUST | Finance |
| BR--019 | The service MUST allow an admin to force-compensate a stuck saga. | MUST | Operations |
| BR--020 | The service MUST persist saga state for at least 7 years. | MUST | Finance |
| BR--021 | The service MUST support tip handling (added before or after delivery, within the tip window). | MUST | Couriers |
| BR--022 | The service MUST be Tier-1 SLO (99.95%). | MUST | Architecture |
| BR--023 | The service MUST support wallet-credited payment methods (closed-loop refunds). | SHOULD | Product |

## 8. Business Rules

| ID | Rule | Notes |
|----|------|-------|
| BR--030 | The saga id is the `food_order_id`. | Re-entry on the same `food_order_id` resumes the same saga. |
| BR--031 | Idempotency keys are `food:<order_id>:<step>:<attempt>`. | |
| BR--032 | The capture step is retried up to `saga_max_retries` before failing. | |
| BR--033 | On capture failure after retries, the saga is `compensating`. | A support agent or auto-policy refunds. |
| BR--034 | Partial refunds are computed as: customer refund amount = the requested amount; merchant debit = the proportional commission; courier earning = the proportional base + tip. | |
| BR--035 | Tips are commission-free by default. | Per-city override allowed. |
| BR--036 | The ledger posting is keyed on `saga_id + step` to be idempotent. | |
| BR--037 | A force-compensation requires an admin's `audit_note` ≥ 10 characters. | |

## 9. Assumptions

- The customer has been authorized at checkout (separate flow in
  `checkout-service`); this service is the orchestrator of
  capture, not authorize.
- The provider is integrated via `payment-service`; this service
  has no direct provider API.
- The merchant settlement is a separate flow in
  `restaurant-settlement-service`; this service triggers it.
- The courier earning accrual is a separate flow in
  `courier-earnings-service`; this service triggers it.

## 10. Constraints

- The service MUST be Tier-1 SLO (99.95%).
- The service MUST complete a capture step in p99 ≤ 5 minutes of
  the trigger.
- The service MUST NOT store customer PII.
- The service MUST NOT store bank account details.
- The service MUST NOT directly call a payment provider; all
  provider calls go through `payment-service`.

## 11. Dependencies

| Dependency | Type | Notes |
|------------|------|-------|
| `delivery-service` | consumer | trigger for capture |
| `payment-service` | service | authorize / capture / refund |
| `wallet-service` | service | closed-loop wallet refunds |
| `ledger-service` | service | double-entry postings |
| `courier-earnings-service` | service | courier accrual |
| `restaurant-settlement-service` | service | merchant accrual |
| `food-order-service` | service | source of `food_order_id` |
| `customer-service` | service | customer contact |
| `notification-service` | service | customer-facing |
| `support-service` / `admin-service` | service | admin tools |
| `configuration-service` | service | tuning |

## 12. Business Workflows

- Authorize at checkout (the saga is created here; not driven by
  this service directly, but the saga id is the order id).
- Capture on `delivery.completed.v1`.
- Tip accrual (added before or after delivery).
- Refund on cancellation.
- Refund on reject.
- Partial refund (support / quality).
- Saga stuck / force-compensate.
- Daily reconciliation against `ledger-service`.

## 13. Exception Workflows

- **Capture fails after retries**: the saga enters
  `compensating`; a support ticket is opened; the
  `payment-service` authorization is voided; the customer is
  notified.
- **Merchant / courier downstream fails**: the saga is
  `partially_completed`; the service retries the downstream;
  reconciliation will detect any persistent drift.
- **Provider-initiated chargeback**: the saga is re-entered with
  `event=chargeback`; the original steps are reversed.
- **Tip window expired**: the tip is recorded as a customer
  credit instead of an earning.

## 14. Success Criteria

- 100% of `delivery.completed.v1` events result in a capture
  within 5 minutes.
- 100% of sagas reach a terminal state within 24 hours.
- 100% of refunds are applied within 1 hour of the trigger.
- 0 ledger duplicates.
- Reconciliation diff = 0 every day for 30 consecutive days.

## 15. KPIs

| KPI | Target | Measurement |
|-----|--------|-------------|
| Capture p99 | ≤ 5 min | from `delivery.completed.v1` to `payment.captured.v1` |
| Saga completion p99 | ≤ 30s after capture | from `payment.captured.v1` to `food.payment.completed.v1` |
| Compensation rate | ≤ 0.5% | compensating sagas / total sagas |
| Refund p99 | ≤ 1 hour | from trigger to `food.payment.full_refund.v1` |
| Tip accrual p99 | ≤ 30s | from tip add to `courier.earning.tip_accrued.v1` |
| Reconciliation diff | 0 | per day |

## 16. Acceptance Criteria

- Capture succeeds end-to-end in staging (delivery → capture →
  merchant → courier → ledger).
- Refund (full) succeeds end-to-end in staging.
- Refund (partial) succeeds end-to-end in staging with the
  correct merchant / courier split.
- The saga is idempotent: replaying the same `delivery.completed.v1`
  does not double-post.
- A stuck saga can be force-compensated by an admin.
- Reconciliation reports zero drift over 7 days.

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

