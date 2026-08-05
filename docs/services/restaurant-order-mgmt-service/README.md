# restaurant-order-mgmt-service

## 1. Purpose

`restaurant-order-mgmt-service` is the canonical owner of the
**restaurant-side order queue aggregate** — the operator's view
of incoming orders, the accept / reject timer, the prep state
(`preparing`, `ready`), and the ready signal that triggers
courier dispatch. It does NOT own the food order aggregate
(owned by `food-order-service`), the menu (owned by
`menu-service`), the delivery (owned by `delivery-service`), or
the kitchen UI (which is a separate operator console that
calls this service).

## 2. Bounded Context

- **In scope**: restaurant-side order queue, accept / reject
  timer, prep state, ready signal, rejection reason.
- **Out of scope**: food order (read-only here, owned by
  `food-order-service`), menu (read-only), delivery
  (read-only), kitchen UI.

## 3. Responsibilities

- Receive `food.order.placed.v1` and add the order to the
  restaurant's queue.
- Drive the accept / reject timer (default 5 minutes).
- Allow the operator to accept or reject the order.
- Allow the operator to mark the order `preparing` (kitchen
  started) and `ready` (kitchen finished).
- Emit `food.order.accepted.v1`, `food.order.rejected.v1`,
  `food.order.preparing.v1`, `food.order.ready.v1`.
- Auto-reject on timer expiry.

## 4. Explicitly NOT Owned

- **Food order** — owned by `food-order-service`. This service
  holds a denormalized view (the queue) and emits events that
  `food-order-service` consumes to transition the order.
- **Menu** — owned by `menu-service`. Read-only.
- **Delivery** — owned by `delivery-service`. The ready signal
  is consumed by `courier-dispatch-service` for assignment.
- **Kitchen UI** — the operator console is a separate web app
  that calls this service.

## 5. Actors

| Actor | Type | Access |
|-------|------|--------|
| Restaurant Manager (staff) | human | accept, reject, mark preparing, mark ready |
| Kitchen Staff | human | mark preparing, mark ready |
| `food-order-service` | system | write (via events) |
| `courier-dispatch-service` | system | read (ready signal) |
| `notification-service` | system | read (operator alerts) |
| `audit-service` | system | read (audit trail) |

## 6. Dependencies

### Synchronous (REST)

- `menu-service` — read product / modifier / add-on details
  for the kitchen view — SLO 99.95%, circuit breaker: **yes**.
- `restaurant-service` — verify restaurant is approved — SLO
  99.95%, circuit breaker: **yes**.
- `branch-service` — verify branch is open — SLO 99.95%,
  circuit breaker: **yes**.
- `customer-service` — read customer (for the operator view) —
  SLO 99.95%, circuit breaker: **yes**.
- `notification-service` — alert operator (sound, push) — SLO
  99.9%, circuit breaker: **yes**.

### Asynchronous (events consumed)

- `food.order.placed.v1` from `food-order-service` — add to
  queue, start accept timer — duplicate handling: **inbox
  dedup**.
- `food.order.cancelled.v1` from `food-order-service` —
  customer cancelled; remove from queue — **inbox dedup**.

## 7. Technology Assumptions

- Runtime: **Node 20** (TypeScript), NestJS/Fastify.
- Database: **PostgreSQL 18** (per-service schema
  `restaurant_order_mgmt`).
- Cache: **Redis** (per-service, used for the operator console
  queue view).
- Event broker: **Kafka**.
- ORM: **Prisma**.
- Migration tool: **prisma migrate**, versioned, forward-only.

## 8. Database Ownership

- Schema: `restaurant_order_mgmt` (owned exclusively by this
  service).
- Tables: `queue`, `queue_state_history`, `accept_timers`,
  `outbox`, `inbox`.
- Migrations: `services/restaurant-order-mgmt-service/prisma/migrations/`.
- Soft delete: **no**; queue items are short-lived (they are
  terminal once the order is accepted / rejected / prepared /
  ready) and pruned after retention.
- Partitioning: **no**.

## 9. API Overview

| Method | URI | Auth | Purpose |
|--------|-----|------|---------|
| GET | /v1/queue | bearer (restaurant_staff) | list the queue |
| GET | /v1/queue/{order_id} | bearer (restaurant_staff) | read a queue item |
| POST | /v1/queue/{order_id}/accept | bearer (manager / dispatcher) | accept |
| POST | /v1/queue/{order_id}/reject | bearer (manager / dispatcher) | reject (reason required) |
| POST | /v1/queue/{order_id}/preparing | bearer (kitchen) | mark preparing |
| POST | /v1/queue/{order_id}/ready | bearer (kitchen) | mark ready |
| GET | /v1/queue/by-restaurant/{restaurant_id} | bearer (system) | list for a restaurant |
| GET | /v1/queue/by-branch/{branch_id} | bearer (system) | list for a branch |
| GET | /health, /ready, /started | none | K8s probes |

(Full contracts in `INTEGRATION.md`.)

## 10. Events Produced

| Event | Trigger | Consumers |
|-------|---------|-----------|
| `food.order.accepted.v1` | operator accepts | `food-order-service`, `notification-service`, `audit-service` |
| `food.order.rejected.v1` | operator rejects or timer expires | `food-order-service`, `food-payment-integration-service`, `notification-service`, `audit-service` |
| `food.order.preparing.v1` | operator marks preparing | `food-order-service`, `notification-service`, `audit-service` |
| `food.order.ready.v1` | operator marks ready | `food-order-service`, `courier-dispatch-service`, `notification-service`, `audit-service` |

(Full contracts in `INTEGRATION.md`.)

## 11. Events Consumed

| Event | Producer | Reason | Handler |
|-------|----------|--------|---------|
| `food.order.placed.v1` | `food-order-service` | add to queue, start accept timer | insert queue item; insert `accept_timers` with `expires_at = now() + 5 minutes`; emit (via downstream services) a sound / push to the operator console |
| `food.order.cancelled.v1` | `food-order-service` | customer cancelled; remove from queue | mark the queue item `cancelled`; emit no further events |

## 12. External Integrations

- **Push notifications for the operator** via
  `notification-service`.
- **Sound alerts** are emitted by the operator console (not
  the service).

## 13. Configuration

| Key | Type | Source | Notes |
|-----|------|--------|-------|
| `restaurant_order_mgmt.accept_timer.minutes` | int | configuration-service | default 5 |
| `restaurant_order_mgmt.queue.max_visible` | int | configuration-service | default 50 |
| `restaurant_order_mgmt.rate_limit.actions_per_minute` | int | configuration-service | throttle |
| `feature_flag.restaurant_order_mgmt.bulk_actions_enabled` | bool | feature-flag-service | future |

## 14. Security

- AuthN: **Bearer JWT** validated at gateway; service-to-service
  via `client_credentials`.
- AuthZ: **RBAC** (`manager`, `dispatcher`, `kitchen`,
  `platform_admin`); fine-grained resource ownership.
- Secrets: Vault paths `secret/restaurant-order-mgmt-service/{env}`.
- PII: minimal (the customer's id is held for the operator
  view).
- Audit: every action emits an event.

## 15. Observability

- Logs: JSON to stdout, fields: `service=restaurant-order-mgmt-service`,
  `correlation_id`, `trace_id`, `user_id`, `route`,
  `latency_ms`, `status`, `order_id`, `restaurant_id`,
  `branch_id`, `state`.
- Metrics:
  - RED: standard.
  - Business: `queue_items_added_total{restaurant_id}`,
    `queue_items_accepted_total{restaurant_id}`,
    `queue_items_rejected_total{reason}`,
    `queue_items_preparing_total`,
    `queue_items_ready_total`,
    `accept_timer_expired_total{restaurant_id}`,
    `order_acceptance_seconds` (histogram),
    `order_prep_seconds` (histogram).
- Traces: OpenTelemetry auto-instrumented.
- Health: `/health`, `/ready`, `/started`.

## 16. Scalability

- Replicas: default **3**, HPA on CPU > 60% and
  `http_requests_in_flight > 500/replica`; max 12.
- Hot path: `GET /v1/queue` (called on every operator console
  poll) — Redis-cached with 5 s TTL; key
  `queue:by_branch:{branch_id}`.
- DB: 1 read replica in each region.
- Cache: Redis cluster.

## 17. Local Development

- `docker compose up` boots PostgreSQL, Kafka, Redis, and the
  service in dev mode.
- Seed: 3 queue items in different states (new, preparing,
  ready).
- `bun run test`, `bun run e2e`.

## 18. Deployment

- Image: `registry.platform.io/restaurant-order-mgmt-service:{git-sha}`.
- Replicas: 3 baseline, HPA up to 12.
- Resource limits: 500m–2000m CPU, 512Mi–2Gi memory.
- Migrations: init container.
- Rollout: rolling update with `maxUnavailable: 0`,
  `maxSurge: 1`.
- Region: `eu-west` and `ap-southeast`.


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

- **Depends on**: [`audit-service`](../audit-service/README.md), [`branch-service`](../branch-service/README.md), [`configuration-service`](../configuration-service/README.md), [`courier-dispatch-service`](../courier-dispatch-service/README.md), [`customer-service`](../customer-service/README.md), [`delivery-service`](../delivery-service/README.md), [`dispatch-service`](../dispatch-service/README.md), [`feature-flag-service`](../feature-flag-service/README.md), [`food-order-service`](../food-order-service/README.md), [`food-payment-integration-service`](../food-payment-integration-service/README.md), [`menu-service`](../menu-service/README.md), [`notification-service`](../notification-service/README.md), [`restaurant-service`](../restaurant-service/README.md)
- **Depended on by**: [`branch-service`](../branch-service/README.md), [`food-order-service`](../food-order-service/README.md), [`inventory-service`](../inventory-service/README.md), [`menu-service`](../menu-service/README.md), [`restaurant-service`](../restaurant-service/README.md), [`restaurant-staff-service`](../restaurant-staff-service/README.md)

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
