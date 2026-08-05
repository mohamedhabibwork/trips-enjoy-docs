# food-order-service

## 1. Purpose

`food-order-service` is the canonical owner of the **food order
aggregate** — the customer-facing order that ties together the
cart, the configuration snapshot, the line items, the pricing
snapshot, and the order state machine. It owns the order
lifecycle (placed, accepted, preparing, ready, picked_up,
delivered, cancelled, rejected) and the configuration snapshot
taken at order placement. It does NOT own the cart
(`cart-service`), the checkout session (`checkout-service`),
the kitchen view (`restaurant-order-mgmt-service`), the
delivery (`delivery-service`), or the payment intent
(`payment-service`).

## 2. Bounded Context

- **In scope**: food order state machine, configuration
  snapshot (menu, prices, tax at order time), line items with
  modifiers and add-ons, pricing snapshot, cancellation policy,
  state transitions, audit trail.
- **Out of scope**: cart (read-only), checkout session
  (read-only), kitchen view (owned by
  `restaurant-order-mgmt-service`), delivery (owned by
  `delivery-service`), payment intent (owned by
  `payment-service`).

## 3. Responsibilities

- Create an order on `checkout.completed.v1` (or directly
  from `checkout-service` in the create-order saga).
- Maintain the order state machine.
- Snapshot the menu configuration, prices, tax, and items at
  order creation; the order is immutable except for state.
- Drive the cancellation policy (full / partial refund
  depending on state).
- Emit `food.order.*.v1` events for every state change.

## 4. Explicitly NOT Owned

- **Cart** — owned by `cart-service`. A order references the
  cart by `cart_id` (no FK).
- **Checkout session** — owned by `checkout-service`. A order
  references the session by `checkout_session_id` (no FK).
- **Kitchen view** — owned by `restaurant-order-mgmt-service`.
- **Delivery** — owned by `delivery-service`.
- **Payment intent** — owned by `payment-service`. A order
  references the intent by `payment_intent_id` (no FK).
- **Menu** — owned by `menu-service`. The order holds a
  snapshot of the menu at order time.
- **Branch hours** — owned by `branch-service`. The order
  records the slot at order time.

## 5. Actors

| Actor | Type | Access |
|-------|------|--------|
| Customer | human | read own orders; cancel (per policy) |
| Customer Service | human | read; manual actions (with audit) |
| Platform Admin | human | read; manual actions (with audit) |
| `checkout-service` | system | write (create order) |
| `restaurant-order-mgmt-service` | system | read; write (state transitions) |
| `courier-dispatch-service` | system | read |
| `delivery-service` | system | read; write (state transitions) |
| `food-payment-integration-service` | system | read |
| `customer-service` | system | read (history) |
| `review-rating-service` | system | read (post-delivery) |
| `audit-service` | system | read (audit trail) |

## 6. Dependencies

### Synchronous (REST)

- `cart-service` — read cart contents (rare; the order is
  created from the checkout session) — SLO 99.95%, circuit
  breaker: **yes**.
- `customer-service` — verify customer — SLO 99.95%, circuit
  breaker: **yes**.
- `restaurant-service` — verify restaurant — SLO 99.95%,
  circuit breaker: **yes**.
- `branch-service` — verify branch — SLO 99.95%, circuit
  breaker: **yes**.
- `pricing-service` — read final quote for the order
  snapshot — SLO 99.95%, circuit breaker: **yes**.

### Asynchronous (events consumed)

- `checkout.completed.v1` from `checkout-service` — create
  the order (saga step) — duplicate handling: **inbox dedup**.
- `food.order.placed.v1` from `restaurant-order-mgmt-service`
  (echo) — note: the order was placed — **inbox dedup**.
- `food.order.accepted.v1` from `restaurant-order-mgmt-service`
  (echo) — state → `accepted` — **inbox dedup**.
- `food.order.rejected.v1` from `restaurant-order-mgmt-service`
  — state → `rejected` — **inbox dedup**.
- `food.order.preparing.v1` from `restaurant-order-mgmt-service`
  (echo) — state → `preparing` — **inbox dedup**.
- `food.order.ready.v1` from `restaurant-order-mgmt-service`
  (echo) — state → `ready` — **inbox dedup**.
- `branch.busy.v1` from `branch-service` — informational —
  **inbox dedup**.
- `payment.captured.v1` from `payment-service` — note: the
  payment was captured — **inbox dedup**.
- `payment.refund.completed.v1` from `payment-service` —
  note: a refund was issued — **inbox dedup**.

## 7. Technology Assumptions

- Runtime: **Node 20** (TypeScript), NestJS/Fastify.
- Database: **PostgreSQL 18** (per-service schema
  `food_order`).
- Cache: **Redis** (per-service, used for fast order reads by
  the customer app and `customer-service`).
- Event broker: **Kafka**.
- ORM: **Prisma**.
- Migration tool: **prisma migrate**, versioned, forward-only.

## 8. Database Ownership

- Schema: `food_order` (owned exclusively by this service).
- Tables: `orders`, `order_items`, `order_item_modifiers`,
  `order_item_addons`, `order_state_history`, `outbox`, `inbox`.
- Migrations: `services/food-order-service/prisma/migrations/`.
- Soft delete: **no**; orders are financial records and are
  never hard-deleted within retention.
- Partitioning: **yes** on `orders` (partitioned by month) for
  high-volume storage.

## 9. API Overview

| Method | URI | Auth | Purpose |
|--------|-----|------|---------|
| GET | /v1/orders/{id} | bearer (customer / staff / admin) | read |
| GET | /v1/orders/by-customer/{customer_id} | bearer (customer / system) | list for a customer |
| GET | /v1/orders/by-restaurant/{restaurant_id} | bearer (system) | list for a restaurant |
| GET | /v1/orders/by-branch/{branch_id} | bearer (system) | list for a branch |
| POST | /v1/orders/{id}/cancellation | bearer (customer) | cancel (per policy) |
| POST | /v1/orders/{id}/state-transition | bearer (admin / system) | manual state transition (with reason) |
| GET | /v1/orders/{id}/state-history | bearer (customer / admin) | read state history |
| GET | /v1/orders/{id}/cancellation-fee | bearer (customer) | preview cancellation fee |
| GET | /health, /ready, /started | none | K8s probes |

(Full contracts in `INTEGRATION.md`.)

## 10. Events Produced

| Event | Trigger | Consumers |
|-------|---------|-----------|
| `food.order.placed.v1` | order created | `restaurant-order-mgmt-service`, `notification-service`, `analytics-service`, `audit-service` |
| `food.order.accepted.v1` | restaurant accepted | `notification-service`, `customer-service` (history), `audit-service` |
| `food.order.rejected.v1` | restaurant rejected | `food-payment-integration-service` (refund), `notification-service`, `audit-service` |
| `food.order.preparing.v1` | kitchen started | `notification-service`, `audit-service` |
| `food.order.ready.v1` | kitchen ready | `courier-dispatch-service`, `notification-service`, `audit-service` |
| `food.order.cancelled.v1` | customer cancelled (per policy) | `food-payment-integration-service` (refund), `notification-service`, `audit-service` |

(Full contracts in `INTEGRATION.md`.)

## 11. Events Consumed

| Event | Producer | Reason | Handler |
|-------|----------|--------|---------|
| `checkout.completed.v1` | `checkout-service` | create the order | create the order in `state = placed`; emit `food.order.placed.v1` |
| `food.order.placed.v1` (self-echo) | this service | note | idempotent (inbox dedup) |
| `food.order.accepted.v1` (self-echo) | this service (or `restaurant-order-mgmt-service`) | state transition | set `state = accepted` |
| `food.order.rejected.v1` | `restaurant-order-mgmt-service` | state transition | set `state = rejected`; the `food-payment-integration-service` consumes the event for refund |
| `food.order.preparing.v1` (self-echo) | this service | state transition | set `state = preparing` |
| `food.order.ready.v1` (self-echo) | this service | state transition | set `state = ready`; `courier-dispatch-service` consumes for dispatch |
| `payment.captured.v1` | `payment-service` | note | informational; the `food-payment-integration-service` orchestrates the capture |
| `payment.refund.completed.v1` | `payment-service` | note | informational |

## 12. External Integrations

- None directly. All integrations are via REST or events.

## 13. Configuration

| Key | Type | Source | Notes |
|-----|------|--------|-------|
| `food_order.cancellation.full_refund_window_minutes` | int | configuration-service | default 5 (before restaurant accept) |
| `food_order.cancellation.partial_refund_window_minutes` | int | configuration-service | default 15 (after accept, before ready) |
| `food_order.cancellation.partial_refund_pct` | int | configuration-service | default 50 |
| `food_order.cancellation.no_refund_after_ready` | bool | configuration-service | true |
| `food_order.partition.retention_months` | int | configuration-service | default 84 (7 years) |
| `feature_flag.food_order.scheduled_orders_enabled` | bool | feature-flag-service | future |

## 14. Security

- AuthN: **Bearer JWT** validated at gateway; service-to-service
  via `client_credentials`.
- AuthZ: **RBAC**; resource-level ownership
  (`order.customer_id == sub` for customer actions).
- Secrets: Vault paths `secret/food-order-service/{env}`.
- PII: minimal (the customer's id and the address id are
  held).
- Audit: every state transition emits an event.

## 15. Observability

- Logs: JSON to stdout, fields: `service=food-order-service`,
  `correlation_id`, `trace_id`, `user_id`, `route`,
  `latency_ms`, `status`, `order_id`, `customer_id`,
  `restaurant_id`, `state`.
- Metrics:
  - RED: standard.
  - Business: `orders_placed_total{restaurant_id}`,
    `orders_accepted_total{restaurant_id}`,
    `orders_rejected_total{reason}`,
    `orders_cancelled_total{reason}`,
    `orders_delivered_total{restaurant_id}`,
    `order_acceptance_seconds` (histogram),
    `order_prep_seconds` (histogram),
    `order_cancellation_rate{reason}`.
- Traces: OpenTelemetry auto-instrumented.
- Health: `/health`, `/ready`, `/started`.

## 16. Scalability

- Replicas: default **3**, HPA on CPU > 60% and
  `http_requests_in_flight > 500/replica`; max 12.
- Hot path: `GET /v1/orders/{id}` (called on every customer
  app order detail view) — Redis-cached with 30 s TTL; key
  `order:{id}`.
- DB: 1 read replica in each region.
- Cache: Redis cluster.
- Partitioning: `orders` is partitioned by month; the
  partition key is `created_at`.

## 17. Local Development

- `docker compose up` boots PostgreSQL, Kafka, Redis, and the
  service in dev mode.
- Seed: 5 orders in different states.
- `bun run test`, `bun run e2e`.

## 18. Deployment

- Image: `registry.platform.io/food-order-service:{git-sha}`.
- Replicas: 3 baseline, HPA up to 12.
- Resource limits: 500m–2000m CPU, 512Mi–2Gi memory.
- Migrations: init container.
- Rollout: rolling update with `maxUnavailable: 0`,
  `maxSurge: 1`.
- Region: `eu-west` and `ap-southeast`.


---

## Appendix A — Removed predecessor capability

The capability that used to live in `restaurant-order-mgmt-service`
(restaurant-side order queue, accept/reject timer, prep state,
ready signal) is now absorbed into this service. The canonical
source for these sections is
[`../../MIGRATION_HUB.md`](../../MIGRATION_HUB.md) §3.9.
Section numbering is preserved so deep links into the predecessor
README continue to resolve.

### A.1 Bounded context (post-merger)

Food order aggregate (placed → accepted → preparing → ready →
picked_up → delivered → cancelled) **plus** the restaurant-side
queue (pending_accept → accepted → preparing → ready → rejected)
with the accept/reject timer. The service is the **only** writer
of the `food_order` schema.

### A.2 Absorbed responsibilities (from `restaurant-order-mgmt-service`)

- Receive `food.order.placed.v1` and add the order to the
  restaurant's queue (own producer; no cross-service hop).
- Drive the accept / reject timer (default 5 minutes).
- Allow the operator to accept or reject the order.
- Allow the operator to mark the order `preparing` (kitchen
  started) and `ready` (kitchen finished).
- Emit `food.order.accepted.v1`, `food.order.rejected.v1`,
  `food.order.preparing.v1`, `food.order.ready.v1`.
- Auto-reject on timer expiry.

### A.3 Absorbed REST endpoints

| Method | URI | Auth | Purpose |
|--------|-----|------|---------|
| POST | `/v1/orders/{id}/accept` | bearer (operator) | accept |
| POST | `/v1/orders/{id}/reject` | bearer (operator) | reject |
| POST | `/v1/orders/{id}/preparing` | bearer (operator) | mark preparing |
| POST | `/v1/orders/{id}/ready` | bearer (operator) | mark ready |
| GET  | `/v1/queue?branch_id=…` | bearer (operator) | read queue |

### A.4 Absorbed events

**Produced** (same topic + schema version, by this service):

- `food.order.accepted.v1`, `food.order.rejected.v1`,
  `food.order.preparing.v1`, `food.order.ready.v1`.

**Consumed**: `food.order.placed.v1` (own producer; no hop).

### A.5 Absorbed configuration keys

- `food_order.queue.accept_window_seconds` (int, default 300 = 5 min).
- `food_order.queue.auto_reject_reason` (text, default `TIMER_EXPIRED`).

### A.6 Compatibility window

For at least six calendar months from 2026-08-05:

- `food.order.accepted.v1`, `food.order.rejected.v1`,
  `food.order.preparing.v1`, `food.order.ready.v1` are published
  under the same topic names and schema versions.
- `/v1/orders/{id}/accept`, `…/reject`, `…/preparing`, `…/ready`,
  `/v1/queue` continue to be served from this service.
- Old schema name `restaurant_order_mgmt.*` remains readable as a
  view in the `food_order` schema.

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

### Related services

- **Depends on**: [`analytics-service`](../analytics-service/README.md), [`audit-service`](../audit-service/README.md), [`branch-service`](../branch-service/README.md), [`cart-service`](../cart-service/README.md), [`checkout-service`](../checkout-service/README.md), [`configuration-service`](../configuration-service/README.md), [`courier-service`](../courier-service/README.md), [`customer-service`](../customer-service/README.md), [`delivery-service`](../delivery-service/README.md), [`feature-flag-service`](../feature-flag-service/README.md), [`menu-service`](../menu-service/README.md), [`notification-service`](../notification-service/README.md), [`payment-service`](../payment-service/README.md), [`pricing-service`](../pricing-service/README.md), [`restaurant-service`](../restaurant-service/README.md), [`review-rating-service`](../review-rating-service/README.md)
- **Depended on by**: [`api-gateway`](../api-gateway/README.md), [`branch-service`](../branch-service/README.md), [`cart-service`](../cart-service/README.md), [`checkout-service`](../checkout-service/README.md), [`courier-service`](../courier-service/README.md), [`customer-service`](../customer-service/README.md), [`delivery-service`](../delivery-service/README.md), [`fraud-risk-service`](../fraud-risk-service/README.md), [`inventory-service`](../inventory-service/README.md), [`loyalty-service`](../loyalty-service/README.md), [`menu-service`](../menu-service/README.md), [`notification-service`](../notification-service/README.md), [`payment-service`](../payment-service/README.md), [`pricing-service`](../pricing-service/README.md), [`promotion-service`](../promotion-service/README.md), [`restaurant-service`](../restaurant-service/README.md), [`review-rating-service`](../review-rating-service/README.md), [`tax-service`](../tax-service/README.md)

> Full dependency map in [`../README.md`](../README.md) and [`../../architecture/MICROSERVICES_MAP.md`](../../architecture/MICROSERVICES_MAP.md).

### Platform-wide

- [`../../shared/README.md`](../../shared/README.md) — `platform-spring-boot-starter` shared library (the single source of cross-cutting code for all Spring Boot services in the platform)
- [`../../shared/PLATFORM_BASELINE.md`](../../shared/PLATFORM_BASELINE.md) — single source for PostgreSQL 18, Kafka, Keycloak, Redis, OpenTelemetry, Vault, deployment, DR (do not restate these in this README)
- [`../../architecture/SERVICE_ISOLATION.md`](../../architecture/SERVICE_ISOLATION.md) — **how this service behaves when a downstream is down** (timeout / bulkhead / circuit / retry / fallback, by class: CRITICAL / DEGRADABLE / BEST-EFFORT)
- [`../../architecture/DOWNSTREAM_ERROR_CATALOG.md`](../../architecture/DOWNSTREAM_ERROR_CATALOG.md) — **canonical error-code catalog + propagation rules** (the `downstream` block, forward/translate/degrade/reject)
- [`../RECOMMENDATIONS.md`](../RECOMMENDATIONS.md) — platform-wide technology map (language, framework, version baseline, admin/RBAC pattern)
- [`../../README.md`](../../README.md) — services overview (the catalog of all 58 services)
- [`../../../main.md`](../../../main.md) — top-level platform specification (architecture, Keycloak, PostgreSQL 18, messaging, observability baseline)
- [`../../shared/OSS_DEPENDENCIES.md`](../../shared/OSS_DEPENDENCIES.md) — **open-source dependencies & license attribution** (platform-wide OSS projects + per-language OSS libraries with SPDX IDs; per-service bundle index; license compatibility matrix)

### Workflows this service participates in

- [`../../workflows/FOOD_ORDER_WORKFLOWS.md`](../../workflows/FOOD_ORDER_WORKFLOWS.md) — end-to-end order/delivery flows
