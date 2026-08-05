# restaurant-order-mgmt-service — Business Requirements Document

## 1. Document Purpose

This BRD defines the business capabilities, requirements, and
rules for the `restaurant-order-mgmt-service` — the canonical
owner of the **restaurant-side order queue aggregate** (the
operator's view of incoming orders). It is read by:

- Product managers scoping the operator console.
- Engineering leads planning the service's roadmap.
- Restaurant operators when designing the kitchen workflow.
- Trust & Safety teams when designing the accept timer and
  rejection.

It informs decisions on the queue lifecycle, accept / reject
timer, prep state, and the relationship to food orders,
delivery, and the operator console.

## 2. Business Context

The **restaurant-side order queue** is the operator's view of
incoming orders. It is a denormalized projection of the
food-order aggregate, augmented with:

- An accept / reject timer.
- Prep state.
- A ready signal that triggers courier dispatch.

When `food.order.placed.v1` is received, the order is added
to the queue and the accept timer starts (default 5 minutes).
The operator can accept or reject; if neither happens in
time, the order is auto-rejected and a full refund is
initiated. Once accepted, the kitchen starts (`preparing`)
and marks ready; the ready signal triggers courier dispatch.

Without this service, the platform could not enforce the
accept timer, support the kitchen prep workflow, or
coordinate the ready signal with courier dispatch.

## 3. Objectives

| ID | Objective | Metric |
|----|-----------|--------|
| BR--001 | Add a placed order to the queue within 1 s | `queue_add_seconds` (P99) < 1 s |
| BR--002 | Auto-reject on timer expiry within 1 s of expiry | `auto_reject_seconds` (P95) < 1 s |
| BR--003 | Reflect operator actions in the queue within 1 s | `queue_action_seconds` (P95) < 1 s |
| BR--004 | Propagate ready signal to dispatch within 1 s | `ready_signal_seconds` (P95) < 1 s |
| BR--005 | Ensure 100% of state changes are captured in the audit log | `audit_completeness` = 1.00 |
| BR--006 | Achieve ≥ 95% acceptance rate within 5 minutes | `acceptance_rate_within_5min` ≥ 0.95 |

## 4. Stakeholders

| Stakeholder | Role | Interest |
|-------------|------|----------|
| Restaurant Manager | Operator | accept / reject orders |
| Kitchen Staff | Operator | mark preparing / ready |
| Platform Admin | Reviewer | full access |
| Courier (indirect) | Picker | ready signal for assignment |
| Customer (indirect) | End user | fast acceptance |

## 5. Actors / Personas

- **Restaurant Manager**: views the queue, accepts or rejects
  orders.
- **Kitchen Staff**: marks orders as preparing / ready.
- **Platform Admin**: full access (rare).

## 6. Business Capabilities

- **Queue Management**: receive `food.order.placed.v1`, add
  to the queue.
- **Accept Timer**: 5-minute default; auto-reject on expiry.
- **Accept / Reject**: operator actions with reason codes.
- **Prep Workflow**: `preparing` (kitchen started) and `ready`
  (kitchen finished).
- **Ready Signal**: emit `food.order.ready.v1` for
  `courier-dispatch-service`.
- **Auto-Reject**: timer-driven auto-reject with reason
  `auto_reject`.

## 7. Business Requirements

| ID | Requirement | Priority | Source |
|----|-------------|----------|--------|
| BR--010 | The service MUST add a placed order to the queue within 1 s | MUST | Product |
| BR--011 | The service MUST start a 5-minute accept timer on add | MUST | Product |
| BR--012 | The service MUST auto-reject on timer expiry | MUST | Trust & Safety |
| BR--013 | The service MUST support operator accept with a reason (optional) | MUST | Product |
| BR--014 | The service MUST support operator reject with a required reason | MUST | Trust & Safety |
| BR--015 | The service MUST support operator mark `preparing` and `ready` | MUST | Operations |
| BR--016 | The service MUST emit `food.order.*.v1` events for every state change | MUST | Event architecture |
| BR--017 | The service MUST support a `GET /v1/queue` for the operator console with filters | MUST | Product |
| BR--018 | The service MUST support a max of 50 visible items in the queue per branch | SHOULD | Product |
| BR--019 | The service MUST remove cancelled orders from the queue | MUST | Product |
| BR--020 | The service MUST hard-delete queue items 7 days after the order is terminal | MUST | Retention |

## 8. Business Rules

| ID | Rule | Notes |
|----|------|-------|
| BR--030 | The queue is per branch, not per restaurant. | enforced |
| BR--031 | The accept timer is `restaurant_order_mgmt.accept_timer.minutes` (default 5). | configurable |
| BR--032 | Auto-reject on timer expiry emits `food.order.rejected.v1` with `reason_code = "auto_reject"`. | enforced |
| BR--033 | Reject by the operator requires a `reason_code` from the platform enum. | enforced |
| BR--034 | The operator can only accept / reject an order in `placed` state. | enforced |
| BR--035 | The operator can only mark `preparing` an order in `accepted` state. | enforced |
| BR--036 | The operator can only mark `ready` an order in `preparing` state. | enforced |
| BR--037 | The ready signal triggers `food.order.ready.v1`; the operator cannot undo. | enforced |
| BR--038 | The queue is read-only for non-staff roles. | enforced |

## 9. Assumptions

- The operator has a verified Keycloak identity and a staff
  assignment (managed by `restaurant-staff-service`).
- `food-order-service` emits `food.order.placed.v1` reliably.
- `courier-dispatch-service` is operational and consumes
  `food.order.ready.v1`.

## 10. Constraints

- The service is the source of truth for the restaurant-side
  queue only. It MUST NOT own the food order aggregate, the
  delivery, or the kitchen UI.
- The service MUST be deployable independently of
  `food-order-service` and `courier-dispatch-service`.

## 11. Dependencies

| Dependency | Type | Notes |
|------------|------|-------|
| `food-order-service` | service | emits `food.order.placed.v1`; consumes accept/reject/preparing/ready |
| `menu-service` | service | menu (read for operator view) |
| `restaurant-service` | service | parent |
| `branch-service` | service | parent |
| `customer-service` | service | customer (read for operator view) |
| `courier-dispatch-service` | service | consumes `food.order.ready.v1` |
| `notification-service` | service | operator alerts |
| `configuration-service` | service | timer default, queue limit |
| `audit-service` | service | audit events |
| Vault | infra | secrets |

## 12. Business Workflows

- **Order Arrival**: `food.order.placed.v1` → add to queue,
  start accept timer, alert operator.
- **Accept**: operator accepts → state → `accepted`.
- **Reject**: operator rejects → state → `rejected` (full
  refund).
- **Auto-Reject**: timer expires → state → `rejected` with
  `reason_code = "auto_reject"`.
- **Preparing**: operator marks preparing → state →
  `preparing`.
- **Ready**: operator marks ready → state → `ready`,
  `food.order.ready.v1` emitted.
- **Cancellation**: customer cancels → remove from queue.

(Detailed sequences in `WORKFLOWS.md`.)

## 13. Exception Workflows

- **Operator accidentally rejects**: the customer is refunded;
  the order must be re-placed (the order is the financial
  record).
- **Operator marks ready but the courier doesn't pick up**:
  the order remains `ready`; the operator can mark
  `preparing` again (rare; admin can override).

## 14. Success Criteria

- 100% of state changes are emitted as events.
- 100% of `placed` orders are auto-rejected on timer expiry.
- P95 acceptance latency < 2 min.
- Acceptance rate within 5 min ≥ 95%.

## 15. KPIs

| KPI | Target | Measurement |
|-----|--------|-------------|
| Queue add (P99) | ≤ 1 s | `queue_add_seconds` |
| Auto-reject (P95) | ≤ 1 s | `auto_reject_seconds` |
| Operator action (P95) | ≤ 1 s | `queue_action_seconds` |
| Ready signal (P95) | ≤ 1 s | `ready_signal_seconds` |
| Acceptance rate (within 5 min) | ≥ 95% | `acceptance_rate_within_5min` |
| Reject rate | (varies) | `queue_items_rejected_total{reason}` |
| Prep time (P50) | (varies by cuisine) | `order_prep_seconds` |

## 16. Acceptance Criteria

- AC-1: A placed order appears in the queue within 1 s.
- AC-2: The accept timer starts on add.
- AC-3: Auto-reject on timer expiry within 1 s.
- AC-4: The operator can accept an order.
- AC-5: The operator can reject an order with a reason.
- AC-6: The operator can mark preparing / ready.
- AC-7: The ready signal triggers courier dispatch.
- AC-8: All state changes are emitted as events.
- AC-9: The service meets its 99.95% SLO.
- AC-10: Cancelled orders are removed from the queue.

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

