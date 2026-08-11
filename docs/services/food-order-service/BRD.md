# food-order-service — Business Requirements Document

## 1. Document Purpose

This BRD defines the business capabilities, requirements, and
rules for the `food-order-service` — the canonical owner of the
**food order aggregate** (the customer-facing order). It is
read by:

- Product managers scoping the order UX.
- Engineering leads planning the service's roadmap.
- Restaurant operators when designing the kitchen view.
- Trust & Safety teams when designing cancellation and
  rejection.

It informs decisions on order state machine, configuration
snapshot, cancellation policy, and the relationship to
checkout, kitchen, and delivery.

## 2. Business Context

A **food order** is the canonical record of a customer's
purchase from a restaurant. It is created when checkout
completes successfully and persists through the entire
fulfillment lifecycle: placed, accepted, preparing, ready,
picked up, delivered, cancelled, or rejected.

The order holds:

- A configuration snapshot (menu, prices, tax at order time).
- Line items with modifiers and add-ons.
- The pricing snapshot.
- The customer id, branch id, address id, slot, payment
  intent id.
- The state.

The order is **immutable** except for state transitions.
Modifying the menu, prices, or tax after the order is placed
does not affect the order. This is critical for financial
reconciliation.

Without this service, the platform could not enforce a single
canonical order record, support state transitions, or
implement the cancellation policy.

## 3. Objectives

| ID | Objective | Metric |
|----|-----------|--------|
| BR--001 | Create an order on checkout within 1 second | `order_creation_seconds` (P99) < 1 s |
| BR--002 | Reflect state changes in real time | `state_propagation_seconds` (P95) < 1 s |
| BR--003 | Allow customer cancellation per the policy | `cancellation_check_seconds` (P99) < 200 ms |
| BR--004 | Ensure 100% of state changes are captured in the audit log | `audit_completeness` = 1.00 |
| BR--005 | Persist orders for 7 years (financial retention) | `order_retention_completeness` = 1.00 |

## 4. Stakeholders

| Stakeholder | Role | Interest |
|-------------|------|----------|
| Customer | End user | accurate order; cancellation per policy |
| Restaurant Operator (kitchen) | Operator | clear order queue; reject if can't fulfill |
| Customer Service | Support | read; manual actions (with audit) |
| Platform Admin | Reviewer | read; manual actions (with audit) |
| Courier (indirect) | Picker | clear order details (via ``courier-service` (delivery)`) |
| Finance (indirect) | Reconciliation | immutable record |

## 5. Actors / Personas

- **Customer**: reads own orders; cancels (per policy).
- **Restaurant Operator (kitchen)**: views orders (via
  ``food-order-service` (queue)`); accepts, rejects, marks
  preparing / ready.
- **Customer Service**: reads orders; may perform manual
  actions (with audit and reason).
- **Platform Admin**: reads orders; may perform manual
  actions.

## 6. Business Capabilities

- **Order creation**: on `checkout.completed.v1`, the order is
  created in `state = placed`.
- **State transitions**: placed → accepted → preparing → ready
  → courier_assigned → picked_up → delivered; or
  cancelled / rejected at various points.
- **Configuration snapshot**: menu, prices, tax, items
  snapshotted at creation.
- **Cancellation policy**: full / partial refund depending on
  state.
- **State history**: every state transition is recorded in
  `order_state_history` for audit.
- **Cancellation fee preview**: the customer can preview the
  fee before cancelling.

## 7. Business Requirements

| ID | Requirement | Priority | Source |
|----|-------------|----------|--------|
| BR--010 | The service MUST create an order on `checkout.completed.v1` | MUST | Product |
| BR--011 | The service MUST snapshot the menu, prices, tax, and items at order creation | MUST | Financial |
| BR--012 | The service MUST enforce the order state machine | MUST | Product |
| BR--013 | The service MUST support customer cancellation per the policy | MUST | Product |
| BR--014 | The service MUST support restaurant rejection | MUST | Trust & Safety |
| BR--015 | The service MUST support admin / customer service manual state transitions (with reason) | MUST | Operations |
| BR--016 | The service MUST emit `food.order.*.v1` events for every state change | MUST | Event architecture |
| BR--017 | The service MUST record every state transition in `order_state_history` | MUST | Audit |
| BR--018 | The service MUST persist orders for 7 years | MUST | Financial |
| BR--019 | The service MUST support a cancellation fee preview | SHOULD | Product |
| BR--020 | The service MUST support scheduled / future orders (configurable) | SHOULD | Future |

## 8. Business Rules

| ID | Rule | Notes |
|----|------|-------|
| BR--030 | The order is immutable except for state transitions. | enforced |
| BR--031 | The configuration snapshot includes menu items, prices, tax, branch hours at order time. | enforced |
| BR--032 | Cancellation within `food_order.cancellation.full_refund_window_minutes` (default 5) results in a full refund. | policy |
| BR--033 | Cancellation within `food_order.cancellation.partial_refund_window_minutes` (default 15) results in a partial refund (default 50%). | policy |
| BR--034 | Cancellation after the partial window but before ready: no refund. | policy |
| BR--035 | Cancellation after ready: 409 `STATE_INVALID` (cannot cancel, courier en route). | enforced |
| BR--036 | Restaurant rejection within the accept window results in a full refund. | policy |
| BR--037 | Manual state transitions require a `reason_code` and (for admin) an HMAC-SHA256 signature. | enforced |
| BR--038 | The `placed → accepted` transition is driven by the restaurant operator (via ``food-order-service` (queue)`). | driven by event |
| BR--039 | The state machine is enforced server-side; illegal transitions return 409 `STATE_INVALID`. | enforced |

## 9. Assumptions

- The checkout session has completed successfully
  (`checkout.completed.v1` received).
- The cart, menu, branch, customer, and payment intent exist
  (verified via API or events).
- The restaurant operator uses the kitchen view
  (``food-order-service` (queue)`) to accept / reject.
- The customer has a verified Keycloak identity.

## 10. Constraints

- The service is the source of truth for the order. It MUST
  NOT own kitchen, delivery, or payment intent state.
- The service MUST be deployable independently of
  ``food-order-service` (checkout)` and ``food-order-service` (queue)`.
- The service MUST remain within the platform's PCI scope
  (SAQ-A).
- The service MUST respect GDPR — only the customer's id is
  stored; the order is the financial record.

## 11. Dependencies

| Dependency | Type | Notes |
|------------|------|-------|
| ``food-order-service` (checkout)` | service | order creation (event-driven) |
| ``food-order-service` (cart)` | service | cart contents (read) |
| `customer-service` | service | customer reference |
| `restaurant-service` | service | restaurant reference |
| ``restaurant-service` (branch)` | service | branch reference |
| `pricing-service` | service | final quote |
| ``food-order-service` (queue)` | service | state transitions |
| ``courier-service` (dispatch)` | service | dispatch (downstream) |
| ``courier-service` (delivery)` | service | delivery state |
| ``payment-service` (food saga)` | service | refund on cancel / reject |
| `notification-service` | service | customer notifications |
| `configuration-service` | service | cancellation policy |
| `audit-service` | service | audit events |
| Vault | infra | secrets |

## 12. Business Workflows

- **Order Creation**: `checkout.completed.v1` → order in
  `state = placed`.
- **Restaurant Acceptance**: `placed → accepted` via the
  restaurant operator.
- **Preparation**: `accepted → preparing` when the kitchen
  starts.
- **Ready**: `preparing → ready` when the kitchen marks the
  order ready.
- **Courier Assignment**: `ready → courier_assigned` when
  dispatch matches a courier.
- **Picked Up**: `courier_assigned → picked_up` when the
  courier picks up.
- **Delivered**: `picked_up → delivered` when the courier
  delivers.
- **Customer Cancellation**: per policy.
- **Restaurant Rejection**: per policy.

(Detailed sequences in `WORKFLOWS.md`.)

## 13. Exception Workflows

- **Restaurant doesn't accept in time**: auto-reject (driven by
  ``food-order-service` (queue)` timer).
- **Restaurant is offline at order creation**: 409
  `RESTAURANT_OFFLINE`; checkout fails.
- **Item out of stock after order placement**: rare; the
  ``restaurant-service` (inventory)` event may trigger a manual cancel by
  the restaurant.

## 14. Success Criteria

- 100% of state changes are emitted as events.
- 100% of orders are persisted for 7 years.
- 100% of cancellation requests honor the policy.
- P99 order creation < 1 s.

## 15. KPIs

| KPI | Target | Measurement |
|-----|--------|-------------|
| Order creation (P99) | ≤ 1 s | `order_creation_seconds` |
| State propagation (P95) | ≤ 1 s | `state_propagation_seconds` |
| Restaurant acceptance rate | ≥ 95% | `orders_accepted_total / orders_placed_total` |
| Cancellation rate | (varies) | `orders_cancelled_total{reason}` |
| Order-to-delivery time | (varies) | `order_delivery_seconds` |

## 16. Acceptance Criteria

- AC-1: An order is created on `checkout.completed.v1` within
  1 s.
- AC-2: The order snapshot includes all relevant fields.
- AC-3: Customer cancellation is per the policy.
- AC-4: Restaurant rejection triggers a full refund.
- AC-5: All state changes are emitted as events.
- AC-6: All state transitions are recorded in
  `order_state_history`.
- AC-7: The service meets its 99.95% SLO.
- AC-8: The order is immutable except for state.
- AC-9: The service stores no card data.
- AC-10: Orders are persisted for 7 years.

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

