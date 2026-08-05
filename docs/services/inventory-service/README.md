# inventory-service

## 1. Purpose

`inventory-service` is the canonical owner of the **inventory
aggregate** — the per-product stock counts, time-bound
availability, and 86-list that drive whether a product can be
ordered. It owns the stock state of items linked to menu
products, the 86 (unavailable) list, time-bound availability
(e.g. "only available 11:00–14:00"), and auto-restock schedules.
It does NOT own the menu (the source of truth for the catalog
is `menu-service`) or orders.

## 2. Bounded Context

- **In scope**: stock counts, decrement on order (event-driven),
  86 list (per-product, per-branch), time-bound availability
  windows, auto-restock schedules.
- **Out of scope**: menu catalog (owned by `menu-service`),
  orders, prep state, payments.

## 3. Responsibilities

- Track stock counts per `inventory_item_id` (linked to a
  `product_id` in `menu-service`).
- Decrement stock on `food.order.placed.v1` (saga step).
- Auto-restock at scheduled times.
- Maintain a per-item 86 list with reason.
- Support time-bound availability (e.g. "only available during
  lunch hours").
- Emit `inventory.item.out_of_stock.v1` and
  `inventory.item.restocked.v1`.

## 4. Explicitly NOT Owned

- **Menu catalog** — owned by `menu-service`. A product may
  reference an `inventory_item_id`; the source of truth for the
  product is `menu-service`.
- **Orders** — owned by `food-order-service`; this service
  decrements stock based on order events.
- **Payments** — owned by `payment-service`.
- **Branch hours** — owned by `branch-service`; time-bound
  availability here is for inventory-specific windows, not
  branch hours.

## 5. Actors

| Actor | Type | Access |
|-------|------|--------|
| Merchant Owner | human | read/write own inventory |
| Restaurant Manager (staff) | human | read/write inventory; 86 items |
| Kitchen Staff | human | 86 items (out of ingredient) |
| Platform Admin | human | read/write any |
| `menu-service` | system | read; link `inventory_item_id` |
| `cart-service` | system | read (availability) |
| `checkout-service` | system | read (availability at final quote) |
| `food-order-service` | system | read; decrement on order |
| `restaurant-order-mgmt-service` | system | read (kitchen view) |
| `audit-service` | system | read (audit trail) |

## 6. Dependencies

### Synchronous (REST)

- `menu-service` — read product to link inventory item — SLO
  99.95%, circuit breaker: **yes**.
- `configuration-service` — read inventory config (default
  thresholds, restock defaults) — SLO 99.95%, circuit breaker:
  **yes**.
- `notification-service` — alert on low stock — SLO 99.9%,
  circuit breaker: **yes**.

### Asynchronous (events consumed)

- `menu.item.unavailable.v1` from `menu-service` — operator
  86'd a product; mirror in the inventory 86 list if the
  product has an `inventory_item_id` — duplicate handling:
  **inbox dedup**.
- `food.order.placed.v1` from `food-order-service` — decrement
  stock for each line item — duplicate handling: **inbox
  dedup**.
- `food.order.cancelled.v1` from `food-order-service` —
  re-credit stock for cancelled line items — **inbox dedup**.
- `restaurant.suspended.v1` from `restaurant-service` — cascade
  86 all items of the restaurant — **inbox dedup**.

## 7. Technology Assumptions

- Runtime: **Node 20** (TypeScript), NestJS/Fastify.
- Database: **PostgreSQL 18** (per-service schema `inventory`).
- Cache: **Redis** (per-service, used for fast availability
  lookups by `cart-service`, `checkout-service`).
- Event broker: **Kafka**.
- ORM: **Prisma**.
- Migration tool: **prisma migrate**, versioned, forward-only.

## 8. Database Ownership

- Schema: `inventory` (owned exclusively by this service).
- Tables: `inventory_items`, `stock_counts`, `stock_movements`,
  `availability_windows`, `restock_schedules`, `outbox`, `inbox`.
- Migrations: `services/inventory-service/prisma/migrations/`.
- Soft delete: **yes** (`deleted_at` on `inventory_items`).
- Partitioning: **no**.

## 9. API Overview

| Method | URI | Auth | Purpose |
|--------|-----|------|---------|
| POST | /v1/inventory/items | bearer (owner / manager) | create inventory item |
| GET | /v1/inventory/items/{id} | bearer (any) | read |
| PATCH | /v1/inventory/items/{id} | bearer (owner / manager) | update |
| POST | /v1/inventory/items/{id}/restock | bearer (owner / manager) | restock (with quantity) |
| POST | /v1/inventory/items/{id}/adjust | bearer (admin) | adjust (e.g. waste) |
| POST | /v1/inventory/items/{id}/86 | bearer (owner / manager / kitchen) | 86 the item |
| DELETE | /v1/inventory/items/{id}/86 | bearer (owner / manager / kitchen) | un-86 |
| POST | /v1/inventory/items/{id}/availability-windows | bearer (owner / manager) | add a time-bound window |
| POST | /v1/inventory/items/{id}/restock-schedules | bearer (owner / manager) | add an auto-restock schedule |
| GET | /v1/inventory/items | bearer (any) | list (filterable) |
| GET | /v1/inventory/items/by-product/{product_id} | bearer (system) | lookup by product |
| GET | /v1/inventory/items/{id}/availability | bearer (system) | is the item available? (cached) |
| GET | /v1/inventory/items/{id}/stock | bearer (system) | current stock count |
| GET | /health, /ready, /started | none | K8s probes |

(Full contracts in `INTEGRATION.md`.)

## 10. Events Produced

| Event | Trigger | Consumers |
|-------|---------|-----------|
| `inventory.item.created.v1` | item created | `menu-service`, `cart-service`, `audit-service` |
| `inventory.item.out_of_stock.v1` | stock reached 0 or below threshold | `menu-service` (auto-86), `cart-service` (remove from cart), `search-service`, `audit-service` |
| `inventory.item.restocked.v1` | stock increased above threshold | `menu-service` (auto-un-86), `cart-service`, `search-service`, `audit-service` |
| `inventory.item.unavailable.v1` | item 86'd by operator | `menu-service` (mirror), `cart-service`, `audit-service` |
| `inventory.item.available.v1` | item un-86'd | `menu-service`, `cart-service`, `audit-service` |
| `inventory.item.low_stock.v1` | stock below low-stock threshold | `notification-service`, `audit-service` |

(Full contracts in `INTEGRATION.md`.)

## 11. Events Consumed

| Event | Producer | Reason | Handler |
|-------|----------|--------|---------|
| `menu.item.unavailable.v1` | `menu-service` | mirror in inventory 86 list | set `unavailable = true` if the product has an `inventory_item_id` |
| `food.order.placed.v1` | `food-order-service` | decrement stock | for each line item with an `inventory_item_id`, decrement stock; if any reaches 0 or below threshold, emit `inventory.item.out_of_stock.v1` |
| `food.order.cancelled.v1` | `food-order-service` | re-credit stock | for each cancelled line item, re-credit stock; if it crosses the threshold upward, emit `inventory.item.restocked.v1` |
| `restaurant.suspended.v1` | `restaurant-service` | cascade 86 all items | set `unavailable = true` for all items of the restaurant; emit `inventory.item.unavailable.v1` |
| `restaurant.closed.v1` | `restaurant-service` | cascade 86 | same |

## 12. External Integrations

- **Notification provider** via `notification-service` for
  low-stock alerts.
- No direct payment provider integration.

## 13. Configuration

| Key | Type | Source | Notes |
|-----|------|--------|-------|
| `inventory.low_stock.threshold` | int | configuration-service | emit `low_stock` at this level |
| `inventory.out_of_stock.threshold` | int | configuration-service | emit `out_of_stock` at this level (default 0) |
| `inventory.restock.default_quantity` | int | configuration-service | default restock |
| `inventory.rate_limit.restock_per_hour` | int | configuration-service | throttle |
| `inventory.cascade.suspend_to_86` | bool | configuration-service | policy |
| `feature_flag.inventory.auto_restock_enabled` | bool | feature-flag-service | rollout of auto-restock |

## 14. Security

- AuthN: **Bearer JWT** validated at gateway; service-to-service
  via `client_credentials`.
- AuthZ: **RBAC** (`merchant_owner`, `merchant_ops`,
  `restaurant_manager`, `kitchen`, `platform_admin`); fine-
  grained resource ownership.
- Secrets: Vault paths `secret/inventory-service/{env}`.
- PII: minimal.
- Audit: every state change emits an event.

## 15. Observability

- Logs: JSON to stdout, fields: `service=inventory-service`,
  `correlation_id`, `trace_id`, `user_id`, `route`,
  `latency_ms`, `status`, `inventory_item_id`, `product_id`.
- Metrics:
  - RED: standard.
  - Business: `inventory_items_created_total`,
    `inventory_items_out_of_stock_total`,
    `inventory_items_restocked_total`,
    `inventory_items_86d_total{reason}`,
    `stock_movements_total{type}`,
    `inventory_low_stock_total`,
    `inventory_lookups_total{cache_hit}`.
- Traces: OpenTelemetry auto-instrumented.
- Health: `/health`, `/ready`, `/started`.

## 16. Scalability

- Replicas: default **3**, HPA on CPU > 60% and
  `http_requests_in_flight > 500/replica`; max 12.
- Hot path: `GET /v1/inventory/items/{id}/availability` (called
  on every cart add and every checkout) — Redis-cached with
  30 s TTL; key `inventory:availability:{id}`.
- DB: 1 read replica in each region.
- Cache: Redis cluster.

## 17. Local Development

- `docker compose up` boots PostgreSQL, Kafka, Redis, and the
  service in dev mode.
- Seed: 5 inventory items with various stock levels, 2 86'd.
- `bun run test`, `bun run e2e`.

## 18. Deployment

- Image: `registry.platform.io/inventory-service:{git-sha}`.
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

- **Depends on**: [`audit-service`](../audit-service/README.md), [`branch-service`](../branch-service/README.md), [`cart-service`](../cart-service/README.md), [`checkout-service`](../checkout-service/README.md), [`configuration-service`](../configuration-service/README.md), [`feature-flag-service`](../feature-flag-service/README.md), [`food-order-service`](../food-order-service/README.md), [`menu-service`](../menu-service/README.md), [`notification-service`](../notification-service/README.md), [`payment-service`](../payment-service/README.md), [`restaurant-order-mgmt-service`](../restaurant-order-mgmt-service/README.md), [`restaurant-service`](../restaurant-service/README.md), [`search-service`](../search-service/README.md)
- **Depended on by**: [`cart-service`](../cart-service/README.md), [`menu-service`](../menu-service/README.md)

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
