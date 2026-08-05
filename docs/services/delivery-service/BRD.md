# delivery-service — Business Requirements Document

## 1. Document Purpose

This BRD captures the business intent for `delivery-service`. It
informs product decisions, the SRS, and operational acceptance
criteria. It is read by product, support, operations, and
engineering.

## 2. Business Context

The delivery is the moment of truth in food delivery. Everything
upstream (order placed, kitchen prep) and everything downstream
(payment capture, settlement, courier earning) hinge on the
delivery being completed accurately and quickly. A wrong state
transition cascades into wrong payments, wrong ratings, and
wrong support decisions.

The `delivery-service` exists to be the authoritative record of
"this courier has this order and is doing X with it." It must be
fast, accurate, and resilient. It must handle the messy reality
of real-world delivery: couriers who don't move, restaurants who
are slow, customers who are unreachable, proof that turns out to
be invalid.

## 3. Objectives

| ID | Objective | Metric |
|----|-----------|--------|
| BR--001 | Drive the delivery state machine accurately | 0.01% state-machine violations / month |
| BR--002 | Make every state transition auditable | 100% transitions have an audit row within 1s |
| BR--003 | Deliver proof of delivery for 100% of completed deliveries | proof row present + valid |
| BR--004 | Minimise time from "picked up" to "delivered" | p50 ≤ 15 min, p95 ≤ 35 min (per city baseline) |
| BR--005 | Handle customer-unreachable gracefully | unreachable timeout honoured within 30s |
| BR--006 | Support batched delivery (one courier, multiple orders) | 100% batched cases handled independently |
| BR--007 | Emit lifecycle events that financial services rely on | 100% of completed deliveries emit `delivery.completed.v1` |
| BR--008 | Support redelivery and refund triggers | redelivery path covered; refund path triggered when redelivery fails |

## 4. Stakeholders

| Stakeholder | Role | Interest |
|-------------|------|----------|
| Product (Food) | owns the food marketplace | accurate, fast, traceable delivery state |
| Operations | city ops | redelivery, force-fail tools |
| Couriers (Trust & Safety) | end users | clear state, valid proof |
| Customers | downstream consumer | accurate ETA, proof, complaint resolution |
| Finance | downstream consumer of events | correct event for settlement |
| Engineering (Courier Domain) | implements | reliability; observability |

## 5. Actors / Personas

- **Courier** — performs the physical delivery; reports state
  transitions and proof via the mobile app.
- **Customer** — receives the delivery; supplies PIN if required.
- **Restaurant operator** — hands the order to the courier;
  not a direct user of this service.
- **City operator / Support agent** — uses admin / support tools
  to force-fail, redeliver, or investigate a delivery.
- **Food-payment-integration service** — system actor that reads
  the delivery state to drive the financial saga.

## 6. Business Capabilities

- Create a delivery when a courier is assigned
  (consumes `delivery.courier.assigned.v1`).
- Track the courier's progress through the state machine via
  courier mobile app pings.
- Record proof of delivery (photo, signature, or PIN).
- Detect customer-unreachable (courier reports + 5-minute wait).
- Request redelivery / reassignment when a delivery fails or a
  courier cancels pre-pickup.
- Emit lifecycle events for downstream payment, settlement, and
  notification.
- Support batched deliveries: a single courier may hold up to
  `batch_max_size` active deliveries; each has its own state
  machine.

## 7. Business Requirements

| ID | Requirement | Priority | Source |
|----|-------------|----------|--------|
| BR--010 | The service MUST be the source of truth for the delivery state machine. | MUST | Architecture |
| BR--011 | The service MUST accept state transitions only from the assigned courier or an admin. | MUST | Security |
| BR--012 | The service MUST persist every state transition as an audit row within 1 second. | MUST | Audit |
| BR--013 | The service MUST emit `delivery.completed.v1` for every successful delivery and `delivery.failed.v1` for every failure. | MUST | Financial saga |
| BR--014 | The service MUST require proof of delivery for completion (photo, signature, or PIN). | MUST | Operations |
| BR--015 | The service MUST trigger a 5-minute wait when the courier reports "customer_unreachable", then auto-fail if no resolution. | MUST | Food workflows |
| BR--016 | The service MUST trigger a redelivery request when a delivery fails and the customer's food is still viable. | SHOULD | Food workflows |
| BR--017 | The service MUST support batched deliveries with independent state machines. | SHOULD | Operations |
| BR--018 | The service MUST allow an admin to force a state transition (with audit note). | MUST | Operations |
| BR--019 | The service MUST record cash-on-delivery collection as a separate event when the merchant allows COD. | MUST | Food workflows |
| BR--020 | The service MUST honour per-merchant `proof.required` configuration. | SHOULD | Operations |

## 8. Business Rules

| ID | Rule | Notes |
|----|------|-------|
| BR--030 | A delivery can only move forward in the state machine; backward transitions are forbidden except by admin override. | |
| BR--031 | A `picked_up` delivery cannot be `cancelled` by the customer; only by admin or via compensation flow. | |
| BR--032 | The "customer_unreachable" timer is started when the courier reports it; the timer is per-delivery, not per-courier. | |
| BR--033 | Batched deliveries share the courier but have independent states; one batch member's failure does not affect the others. | |
| BR--034 | Proof of delivery photos are stored encrypted; the `file_id` reference is the only thing kept in this service. | |
| BR--035 | The "redelivery" flow is a new delivery; the original delivery is closed as `failed` with `reason=redelivered`. | |

## 9. Assumptions

- The courier mobile app pings state transitions in near real-time
  (median latency from event to API call < 1s).
- The customer is reachable at the dropoff address during the
  delivery window in 95% of cases.
- The proof type is configurable per merchant (default: any of
  photo/signature/PIN).
- A delivery is "viable for redelivery" if the food has not been
  out of the restaurant for more than `redelivery_window_minutes`
  (default 30).

## 10. Constraints

- The service MUST be Tier-1 SLO (99.95%).
- The service MUST complete a state-transition API call in p99 ≤
  300ms.
- The service MUST NOT store customer PII (name, phone, address);
  only the `customer_id` (UUID).
- The service MUST emit `delivery.completed.v1` synchronously with
  the database commit (via outbox).

## 11. Dependencies

| Dependency | Type | Notes |
|------------|------|-------|
| `courier-dispatch-service` | service | creates the delivery; receives reassignment requests |
| `courier-service` | service | enriches (vehicle type, KYC) |
| `food-order-service` | service | source of `food_order_id`; cancellation events |
| `customer-service` | service | customer contact (read) |
| `courier-tracking-service` | service | live location (read) |
| `eta-routing-service` | service | ETA computation (read) |
| `geolocation-service` | service | distance / geofence (read) |
| `file-service` | service | proof-of-delivery photos |
| `notification-service` | service | customer-facing messages |
| `food-payment-integration-service` | consumer | subscribes to lifecycle events |
| `courier-earnings-service` | consumer | subscribes to `delivery.completed.v1` |
| `audit-service` | consumer | subscribes to `delivery.audit.*` |
| `support-service` / `admin-service` | service | admin tools |

## 12. Business Workflows

- Happy path: assigned → en_route_pickup → arrived_pickup →
  picked_up → en_route_dropoff → delivered.
- Batched delivery (one courier, multiple orders).
- Customer unreachable: 5-minute wait → redeliver or fail.
- Courier cancels pre-pickup → reassignment.
- COD collection (where allowed).
- Redelivery flow (new delivery).

## 13. Exception Workflows

- Courier app crash mid-delivery: state is recovered from this
  service; the courier sees the current state on next open.
- Customer unreachable: 5-minute wait, then auto-fail + refund.
- Restaurant runs out of an item: order is cancelled; delivery
  is auto-cancelled (pre-pickup) or auto-failed (post-pickup).
- Proof of delivery fails validation: courier is prompted to
  re-take; if they can't, the delivery is held in `pending_proof`.

## 14. Success Criteria

- 99.99% of state transitions are accepted (i.e. < 0.01% rejected
  for state-machine violations).
- 100% of completed deliveries have a valid proof row.
- 100% of completed deliveries emit `delivery.completed.v1` within
  1s of the database commit.
- p95 pickup-to-delivered ≤ 35 minutes (per city).
- Redelivery rate ≤ 2% (target).

## 15. KPIs

| KPI | Target | Measurement |
|-----|--------|-------------|
| Pickup-to-delivered (p50) | ≤ 15 min | from `picked_up` to `delivered` |
| Pickup-to-delivered (p95) | ≤ 35 min | per city |
| Customer-unreachable rate | ≤ 3% | `failed=unreachable` / total |
| Redelivery success rate | ≥ 80% | `redelivered=delivered` / `redelivered` |
| Proof-of-delivery success rate | ≥ 99% | completed with valid proof / completed total |
| Batched share | ≥ 30% in dense cities | batched / total deliveries |

## 16. Acceptance Criteria

- The state machine is fully unit-tested with > 99% branch coverage.
- A chaos test (kill `courier-tracking-service`) shows the delivery
  state machine is unaffected.
- A load test sustains 200 rps of state transitions with p99 ≤ 300ms.
- All completed deliveries emit `delivery.completed.v1` (verified
  by integration test with `food-payment-integration-service`).
- Admin force-fail and redeliver are audit-logged.
- Batched deliveries are handled end-to-end in staging.

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

