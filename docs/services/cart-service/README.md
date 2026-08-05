# cart-service

## 1. Purpose

`cart-service` is the canonical owner of the **shopping cart
aggregate** — the customer's in-progress order before checkout.
It owns the cart lifecycle (active, abandoned, checked out),
items (product, quantity, modifiers, add-ons), applied
promotions, computed totals (sub-quote), and re-quote on price
or availability change. It does NOT own the order (owned by
`food-order-service`), the checkout session (owned by
`checkout-service`), or the menu (owned by `menu-service`).

## 2. Bounded Context

- **In scope**: carts, items, modifiers, add-ons, applied
  promotions, computed subtotals, cart lifecycle, re-quote.
- **Out of scope**: checkout session, food order, payment
  intent, menu catalog (read-only), promotions (read-only).

## 3. Responsibilities

- CRUD on carts (create, read, update, abandon).
- Add / remove / update items (with modifiers and add-ons).
- Apply and remove promotions.
- Compute and cache the subtotal (sub-quote).
- Re-quote on `menu.item.price.changed.v1`,
  `menu.item.unavailable.v1`, `restaurant.offline.v1`.
- Detect abandonment (no activity for 30 min) and emit
  `cart.abandoned.v1`.
- Emit `cart.created.v1`, `cart.updated.v1`,
  `cart.checked_out.v1`, `cart.abandoned.v1`.

## 4. Explicitly NOT Owned

- **Checkout session** — owned by `checkout-service`. A cart
  is referenced by `cart_id` (no FK) from the checkout session.
- **Food order** — owned by `food-order-service`. A cart is
  referenced by `cart_id` (no FK) from the order.
- **Menu** — owned by `menu-service`. The cart reads menu
  products (REST) and consumes menu events.
- **Promotions** — owned by `promotion-service`. The cart
  applies promotions via the promotion service.
- **Pricing** — owned by `pricing-service`. The cart requests
  a sub-quote.

## 5. Actors

| Actor | Type | Access |
|-------|------|--------|
| Customer | human | read/write own carts |
| `customer-service` | system | read (recent cart) |
| `menu-service` | system | read (products, prices, availability) |
| `restaurant-service` | system | read (online check) |
| `promotion-service` | system | read (validate / apply promo) |
| `pricing-service` | system | read (sub-quote) |
| `checkout-service` | system | read (cart contents at checkout) |
| `food-order-service` | system | read (after checkout) |
| `audit-service` | system | read (audit trail) |

## 6. Dependencies

### Synchronous (REST)

- `customer-service` — verify customer — SLO 99.95%, circuit
  breaker: **yes**.
- `menu-service` — read product, price, modifiers, add-ons,
  availability — SLO 99.95%, circuit breaker: **yes**.
- `restaurant-service` — read online status — SLO 99.95%,
  circuit breaker: **yes**.
- `promotion-service` — validate and apply promo — SLO
  99.9%, circuit breaker: **yes**.
- `pricing-service` — request sub-quote — SLO 99.95%, circuit
  breaker: **yes**.

### Asynchronous (events consumed)

- `menu.item.price.changed.v1` from `menu-service` — re-quote
  the cart — duplicate handling: **inbox dedup**.
- `menu.item.unavailable.v1` from `menu-service` — remove the
  item from the cart and notify the customer — **inbox dedup**.
- `restaurant.offline.v1` from `restaurant-service` — block
  checkout and notify the customer — **inbox dedup**.
- `cart.item.unavailable.v1` from `inventory-service` — remove
  the item — **inbox dedup**.

## 7. Technology Assumptions

- Runtime: **Node 20** (TypeScript), NestJS/Fastify.
- Database: **PostgreSQL 18** (per-service schema `cart`).
- Cache: **Redis** (per-service, used for fast cart reads by
  the customer app and `checkout-service`).
- Event broker: **Kafka**.
- ORM: **Prisma**.
- Migration tool: **prisma migrate**, versioned, forward-only.

## 8. Database Ownership

- Schema: `cart` (owned exclusively by this service).
- Tables: `carts`, `cart_items`, `cart_item_modifiers`,
  `cart_item_addons`, `cart_promotions`, `outbox`, `inbox`.
- Migrations: `services/cart-service/prisma/migrations/`.
- Soft delete: **no**; carts are abandoned (state) and
  hard-deleted after retention.
- Partitioning: **no** (cart volume is per active session;
  millions of carts per day, but rows are small and pruned
  aggressively).

## 9. API Overview

| Method | URI | Auth | Purpose |
|--------|-----|------|---------|
| POST | /v1/carts | bearer (customer) | create cart (Idempotency-Key required) |
| GET | /v1/carts/{id} | bearer (customer / system) | read |
| PATCH | /v1/carts/{id} | bearer (customer) | update (e.g. tip, address ref) |
| DELETE | /v1/carts/{id} | bearer (customer) | abandon |
| POST | /v1/carts/{id}/items | bearer (customer) | add item |
| PATCH | /v1/carts/{id}/items/{iid} | bearer (customer) | update item (quantity) |
| DELETE | /v1/carts/{id}/items/{iid} | bearer (customer) | remove item |
| POST | /v1/carts/{id}/promotions | bearer (customer) | apply promo |
| DELETE | /v1/carts/{id}/promotions | bearer (customer) | remove promo |
| POST | /v1/carts/{id}/re-quote | bearer (system) | re-quote (internal) |
| POST | /v1/carts/{id}/checkout | bearer (customer) | create checkout session |
| GET | /v1/carts/by-customer/{customer_id} | bearer (customer / system) | list active carts |
| GET | /health, /ready, /started | none | K8s probes |

(Full contracts in `INTEGRATION.md`.)

## 10. Events Produced

| Event | Trigger | Consumers |
|-------|---------|-----------|
| `cart.created.v1` | `POST /v1/carts` | `analytics-service`, `customer-service` (history), `audit-service` |
| `cart.updated.v1` | any PATCH (item / promo / address / tip) | `analytics-service`, `customer-service` (history) |
| `cart.checked_out.v1` | `POST /v1/carts/{id}/checkout` succeeds | `analytics-service`, `customer-service` (history), `audit-service` |
| `cart.abandoned.v1` | 30 min idle | `analytics-service`, `customer-service` (history) |

(Full contracts in `INTEGRATION.md`.)

## 11. Events Consumed

| Event | Producer | Reason | Handler |
|-------|----------|--------|---------|
| `menu.item.price.changed.v1` | `menu-service` | re-quote | recompute subtotal; if the new subtotal differs, emit `cart.updated.v1` and notify customer |
| `menu.item.unavailable.v1` | `menu-service` | item removed | remove the item from the cart; emit `cart.updated.v1`; notify customer |
| `cart.item.unavailable.v1` | `inventory-service` | out-of-stock mirror | same as above |
| `restaurant.offline.v1` | `restaurant-service` | block checkout | set `checkout_blocked = true`; emit `cart.updated.v1`; notify customer |

## 12. External Integrations

- None directly. All integrations are via REST or events.

## 13. Configuration

| Key | Type | Source | Notes |
|-----|------|--------|-------|
| `cart.abandonment.idle_minutes` | int | configuration-service | default 30 |
| `cart.max_items` | int | configuration-service | default 50 |
| `cart.max_quantity_per_item` | int | configuration-service | default 20 |
| `cart.quote.cache_ttl_seconds` | int | configuration-service | default 60 |
| `cart.rate_limit.create_per_hour` | int | configuration-service | throttle |
| `feature_flag.cart.scheduled_orders_enabled` | bool | feature-flag-service | future |

## 14. Security

- AuthN: **Bearer JWT** validated at gateway; service-to-service
  via `client_credentials`.
- AuthZ: **RBAC**; resource-level ownership
  (`cart.customer_id == sub`).
- Secrets: Vault paths `secret/cart-service/{env}`.
- PII: minimal (the customer's id is held for ownership).
- Audit: every state change emits an event.

## 15. Observability

- Logs: JSON to stdout, fields: `service=cart-service`,
  `correlation_id`, `trace_id`, `user_id`, `route`,
  `latency_ms`, `status`, `cart_id`, `customer_id`, `state`.
- Metrics:
  - RED: standard.
  - Business: `carts_created_total`,
    `carts_abandoned_total{reason}`,
    `carts_checked_out_total`,
    `cart_items_total{restaurant_id}`,
    `cart_re_quote_total{reason}`,
    `cart_quote_seconds` (histogram).
- Traces: OpenTelemetry auto-instrumented.
- Health: `/health`, `/ready`, `/started`.

## 16. Scalability

- Replicas: default **3**, HPA on CPU > 60% and
  `http_requests_in_flight > 500/replica`; max 12.
- Hot path: `GET /v1/carts/{id}` (called on every customer
  app open) — Redis-cached with 30 s TTL; key `cart:{id}`.
- DB: 1 read replica in each region.
- Cache: Redis cluster.

## 17. Local Development

- `docker compose up` boots PostgreSQL, Kafka, Redis, and the
  service in dev mode.
- Seed: 3 active carts under different customers.
- `bun run test`, `bun run e2e`.

## 18. Deployment

- Image: `registry.platform.io/cart-service:{git-sha}`.
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

- **Depends on**: [`analytics-service`](../analytics-service/README.md), [`audit-service`](../audit-service/README.md), [`checkout-service`](../checkout-service/README.md), [`configuration-service`](../configuration-service/README.md), [`customer-service`](../customer-service/README.md), [`feature-flag-service`](../feature-flag-service/README.md), [`food-order-service`](../food-order-service/README.md), [`inventory-service`](../inventory-service/README.md), [`menu-service`](../menu-service/README.md), [`pricing-service`](../pricing-service/README.md), [`promotion-service`](../promotion-service/README.md), [`restaurant-service`](../restaurant-service/README.md)
- **Depended on by**: [`address-service`](../address-service/README.md), [`branch-service`](../branch-service/README.md), [`checkout-service`](../checkout-service/README.md), [`customer-service`](../customer-service/README.md), [`food-order-service`](../food-order-service/README.md), [`inventory-service`](../inventory-service/README.md), [`loyalty-service`](../loyalty-service/README.md), [`menu-service`](../menu-service/README.md), [`pricing-service`](../pricing-service/README.md), [`promotion-service`](../promotion-service/README.md), [`restaurant-service`](../restaurant-service/README.md), [`tax-service`](../tax-service/README.md)

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
