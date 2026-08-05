# checkout-service

## 1. Purpose

`checkout-service` is the canonical owner of the **checkout
session aggregate** — the customer's pre-payment state. It
owns the session lifecycle, delivery address, delivery slot,
payment method selection, and the final quote (frozen for the
session). It is the bridge between the cart and the payment
authorization. It does NOT own the cart (owned by
`cart-service`), the payment intent (owned by `payment-service`),
or the food order (owned by `food-order-service`).

## 2. Bounded Context

- **In scope**: checkout session, address selection, delivery
  slot selection, payment method selection, final quote
  (frozen), idempotency, lifecycle (pending, completed,
  failed, expired).
- **Out of scope**: cart contents (owned by `cart-service`;
  read-only), payment authorization (owned by `payment-service`),
  food order (owned by `food-order-service`).

## 3. Responsibilities

- Create a checkout session from a cart.
- Validate the cart contents (snapshot at session creation).
- Select / change delivery address and delivery slot.
- Select / change payment method.
- Compute and freeze the final quote for the session.
- Authorize payment via `payment-service`.
- Create the food order in `food-order-service` on success.
- Emit `checkout.completed.v1`, `checkout.failed.v1`.

## 4. Explicitly NOT Owned

- **Cart contents** — owned by `cart-service`. A session
  references the cart by `cart_id` (no FK) and snapshots the
  contents at creation.
- **Payment intent** — owned by `payment-service`. The session
  references the payment intent by `payment_intent_id` (no FK).
- **Food order** — owned by `food-order-service`. The session
  references the order by `food_order_id` (no FK).
- **Pricing engine** — owned by `pricing-service`. The session
  requests a final quote.

## 5. Actors

| Actor | Type | Access |
|-------|------|--------|
| Customer | human | read/write own sessions |
| `cart-service` | system | read (cart contents) |
| `pricing-service` | system | read (final quote) |
| `payment-service` | system | read/write (authorization) |
| `address-service` | system | read (saved addresses) |
| `customer-service` | system | read (default payment method) |
| `food-order-service` | system | read/write (create order) |
| `restaurant-service` | system | read (online check) |
| `branch-service` | system | read (open check) |
| `audit-service` | system | read (audit trail) |

## 6. Dependencies

### Synchronous (REST)

- `cart-service` — read cart contents — SLO 99.95%, circuit
  breaker: **yes**.
- `pricing-service` — request final quote — SLO 99.95%, circuit
  breaker: **yes**.
- `address-service` — read saved addresses — SLO 99.9%, circuit
  breaker: **yes**.
- `payment-service` — authorize payment — SLO 99.95%, circuit
  breaker: **yes**.
- `customer-service` — read default payment method — SLO
  99.95%, circuit breaker: **yes**.
- `food-order-service` — create order on success — SLO 99.95%,
  circuit breaker: **yes**.
- `restaurant-service` / `branch-service` — verify online /
  open — SLO 99.95%, circuit breaker: **yes**.

### Asynchronous (events consumed)

- `cart.updated.v1` from `cart-service` — the cart changed
  (e.g. tip, address); the session may be invalidated —
  duplicate handling: **inbox dedup**.
- `restaurant.offline.v1` from `restaurant-service` — block
  the session — **inbox dedup**.
- `payment.authorized.v1` from `payment-service` — proceed to
  order creation — **inbox dedup**.
- `payment.failed.v1` from `payment-service` — mark session
  failed — **inbox dedup**.

## 7. Technology Assumptions

- Runtime: **Node 20** (TypeScript), NestJS/Fastify.
- Database: **PostgreSQL 18** (per-service schema `checkout`).
- Cache: **Redis** (per-service, used for fast session reads
  by the customer app).
- Event broker: **Kafka**.
- ORM: **Prisma**.
- Migration tool: **prisma migrate**, versioned, forward-only.

## 8. Database Ownership

- Schema: `checkout` (owned exclusively by this service).
- Tables: `checkout_sessions`, `checkout_session_items`,
  `checkout_session_modifiers`, `checkout_session_addons`,
  `outbox`, `inbox`.
- Migrations: `services/checkout-service/prisma/migrations/`.
- Soft delete: **no**; sessions are short-lived and hard-
  deleted after retention.
- Partitioning: **no**.

## 9. API Overview

| Method | URI | Auth | Purpose |
|--------|-----|------|---------|
| POST | /v1/checkouts | bearer (customer) | create session (Idempotency-Key required) |
| GET | /v1/checkouts/{id} | bearer (customer / system) | read |
| PATCH | /v1/checkouts/{id} | bearer (customer) | update (address, slot, tip, payment method) |
| POST | /v1/checkouts/{id}/pay | bearer (customer) | authorize payment and create order |
| DELETE | /v1/checkouts/{id} | bearer (customer) | cancel session |
| GET | /health, /ready, /started | none | K8s probes |

(Full contracts in `INTEGRATION.md`.)

## 10. Events Produced

| Event | Trigger | Consumers |
|-------|---------|-----------|
| `checkout.completed.v1` | payment authorized and order created | `cart-service` (clear), `audit-service` |
| `checkout.failed.v1` | payment failed or session expired | `cart-service` (re-enable), `notification-service`, `audit-service` |

(Full contracts in `INTEGRATION.md`.)

## 11. Events Consumed

| Event | Producer | Reason | Handler |
|-------|----------|--------|---------|
| `cart.updated.v1` | `cart-service` | the cart changed; the session may be invalidated | if the session is `pending` and the cart changed (e.g. tip, items), re-quote and update; if the cart was abandoned or checked out, mark the session `expired` |
| `restaurant.offline.v1` | `restaurant-service` | block the session | set `pay_blocked = true`; emit `checkout.failed.v1` if `pay` was called |
| `payment.authorized.v1` | `payment-service` | proceed to order creation | create the food order; on success, emit `checkout.completed.v1` |
| `payment.failed.v1` | `payment-service` | mark session failed | set `state = 'failed'`; emit `checkout.failed.v1` |

## 12. External Integrations

- None directly. All integrations are via REST or events.

## 13. Configuration

| Key | Type | Source | Notes |
|-----|------|--------|-------|
| `checkout.session.ttl_minutes` | int | configuration-service | default 15 |
| `checkout.delivery_slot.min_lead_minutes` | int | configuration-service | default 30 |
| `checkout.quote.cache_ttl_seconds` | int | configuration-service | default 60 |
| `checkout.rate_limit.create_per_hour` | int | configuration-service | throttle |
| `feature_flag.checkout.scheduled_orders_enabled` | bool | feature-flag-service | future |

## 14. Security

- AuthN: **Bearer JWT** validated at gateway; service-to-service
  via `client_credentials`.
- AuthZ: **RBAC**; resource-level ownership
  (`checkout_session.customer_id == sub`).
- Secrets: Vault paths `secret/checkout-service/{env}`.
- PII: minimal (the customer's id and the address id are
  held for ownership).
- Audit: every state change emits an event.

## 15. Observability

- Logs: JSON to stdout, fields: `service=checkout-service`,
  `correlation_id`, `trace_id`, `user_id`, `route`,
  `latency_ms`, `status`, `checkout_session_id`, `customer_id`,
  `state`.
- Metrics:
  - RED: standard.
  - Business: `checkouts_created_total`,
    `checkouts_completed_total`,
    `checkouts_failed_total{reason}`,
    `checkouts_expired_total`,
    `checkout_quote_seconds` (histogram).
- Traces: OpenTelemetry auto-instrumented.
- Health: `/health`, `/ready`, `/started`.

## 16. Scalability

- Replicas: default **3**, HPA on CPU > 60% and
  `http_requests_in_flight > 500/replica`; max 12.
- Hot path: `GET /v1/checkouts/{id}` (called on every customer
  app checkout screen open) — Redis-cached with 30 s TTL; key
  `checkout:{id}`.
- DB: 1 read replica in each region.
- Cache: Redis cluster.

## 17. Local Development

- `docker compose up` boots PostgreSQL, Kafka, Redis, and the
  service in dev mode.
- Seed: 3 sessions in different states.
- `bun run test`, `bun run e2e`.

## 18. Deployment

- Image: `registry.platform.io/checkout-service:{git-sha}`.
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

- **Depends on**: [`address-service`](../address-service/README.md), [`audit-service`](../audit-service/README.md), [`branch-service`](../branch-service/README.md), [`cart-service`](../cart-service/README.md), [`configuration-service`](../configuration-service/README.md), [`customer-service`](../customer-service/README.md), [`feature-flag-service`](../feature-flag-service/README.md), [`food-order-service`](../food-order-service/README.md), [`notification-service`](../notification-service/README.md), [`payment-service`](../payment-service/README.md), [`pricing-service`](../pricing-service/README.md), [`restaurant-service`](../restaurant-service/README.md)
- **Depended on by**: [`address-service`](../address-service/README.md), [`branch-service`](../branch-service/README.md), [`cart-service`](../cart-service/README.md), [`food-order-service`](../food-order-service/README.md), [`inventory-service`](../inventory-service/README.md), [`menu-service`](../menu-service/README.md), [`pricing-service`](../pricing-service/README.md), [`promotion-service`](../promotion-service/README.md), [`restaurant-service`](../restaurant-service/README.md), [`tax-service`](../tax-service/README.md)

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
