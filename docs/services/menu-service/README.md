# menu-service

## 1. Purpose

`menu-service` is the canonical owner of the **menu aggregate** —
the hierarchical catalog of categories, products (menu items),
modifiers (e.g. "size", "spice level"), and add-ons (e.g. "extra
cheese") that a restaurant offers. It owns the menu's draft /
published lifecycle, photos, price changes, and per-item
unavailability (the "86" operation). It does NOT own the
restaurant, branch, inventory stock counts (the source of truth
is `inventory-service`), or orders.

## 2. Bounded Context

- **In scope**: menus (one or more per restaurant), categories,
  products, modifiers, modifier options, add-ons, photos (refs
  to `file-service`), draft / published state, prices, taxes
  (denormalized from `tax-service`), per-item unavailability.
- **Out of scope**: restaurant brand, branch data, inventory
  stock counts, orders, payments. The menu is keyed at the
  restaurant level; branches can scope availability via hours
  but the menu itself is shared.

## 3. Responsibilities

- CRUD for menus, categories, products, modifiers, add-ons.
- Persist and serve the menu hierarchy.
- Support draft / published lifecycle; only published items are
  visible to customers and orderable.
- Track price history (current and previous prices).
- Track per-item unavailability (`86`).
- Compute tax-inclusive and tax-exclusive prices (delegated to
  `tax-service` for tax codes; cached).
- Emit `menu.created.v1`, `menu.updated.v1`,
  `menu.item.price.changed.v1`, `menu.item.unavailable.v1`.

## 4. Explicitly NOT Owned

- **Restaurant brand** — owned by `restaurant-service`. A menu
  holds a `restaurant_id` UUID column with no FK.
- **Branch data** — owned by `branch-service`. A menu is keyed
  at the restaurant level; per-branch availability is computed
  by combining menu state with branch hours.
- **Inventory stock counts** — owned by `inventory-service`. A
  product may reference an `inventory_item_id` (cross-service
  ref) for stock-based availability; the source of truth for
  stock is `inventory-service`.
- **Orders and prep state** — owned by `food-order-service` and
  `restaurant-order-mgmt-service`.
- **Photos / images** — bytes live in object storage via
  `file-service`; this service holds `file_id` refs.

## 5. Actors

| Actor | Type | Access |
|-------|------|--------|
| Merchant Owner | human | read/write own menus |
| Restaurant Manager (staff) | human | read/write menus; 86 items |
| Platform Admin | human | read/write any menu |
| `restaurant-service` | system | read (parent) |
| `inventory-service` | system | read (stock); write (`inventory.item.out_of_stock`) |
| `tax-service` | system | read (tax codes) |
| `cart-service` | system | read (menu items in cart) |
| `checkout-service` | system | read (final quote) |
| `food-order-service` | system | read (line items) |
| `restaurant-order-mgmt-service` | system | read (kitchen view) |
| `search-service` | system | read (index) |
| `audit-service` | system | read (audit trail) |

## 6. Dependencies

### Synchronous (REST)

- `restaurant-service` — verify parent restaurant is approved —
  SLO 99.95%, circuit breaker: **yes**.
- `tax-service` — read tax codes (cached) — SLO 99.9%, circuit
  breaker: **yes**.
- `inventory-service` — check stock state (read) — SLO 99.9%,
  circuit breaker: **yes**.
- `file-service` — request signed URL for photo upload — SLO
  99.9%, circuit breaker: **yes**.
- `configuration-service` — read menu config (max categories,
  max products per category, etc.) — SLO 99.95%, circuit
  breaker: **yes**.
- `notification-service` — trigger lifecycle messages — SLO
  99.9%, circuit breaker: **yes**.

### Asynchronous (events consumed)

- `restaurant.created.v1` from `restaurant-service` — parent
  eligible for menus — duplicate handling: **inbox dedup**.
- `restaurant.suspended.v1` from `restaurant-service` — cascade
  to unpublish menus (cannot order from a suspended
  restaurant) — **inbox dedup**.
- `restaurant.closed.v1` from `restaurant-service` — cascade
  to unpublish — **inbox dedup**.
- `inventory.item.out_of_stock.v1` from `inventory-service` —
  item is out of stock; set `unavailable = true` and emit
  `menu.item.unavailable.v1` — **inbox dedup**.
- `inventory.item.restocked.v1` from `inventory-service` —
  item is back in stock; clear `unavailable` if it was
  stock-driven — **inbox dedup**.
- `tax.updated.v1` from `tax-service` (or
  `configuration.updated.v1`) — invalidate tax code cache —
  **inbox dedup**.

## 7. Technology Assumptions

- Runtime: **Node 20** (TypeScript), NestJS/Fastify.
- Database: **PostgreSQL 18** (per-service schema `menu`).
- Cache: **Redis** (per-service, used for fast menu lookups
  by `cart-service`, `checkout-service`,
  `restaurant-order-mgmt-service`).
- Event broker: **Kafka**.
- ORM: **Prisma**.
- Migration tool: **prisma migrate**, versioned, forward-only.

## 8. Database Ownership

- Schema: `menu` (owned exclusively by this service).
- Tables: `menus`, `categories`, `products`, `modifiers`,
  `modifier_options`, `addons`, `product_addons`, `outbox`,
  `inbox`.
- Migrations: `services/menu-service/prisma/migrations/`.
- Soft delete: **yes** (`deleted_at` on `menus`, `categories`,
  `products`).
- Partitioning: **no**.

## 9. API Overview

| Method | URI | Auth | Purpose |
|--------|-----|------|---------|
| POST | /v1/restaurants/{restaurant_id}/menus | bearer (owner / manager) | create menu |
| GET | /v1/menus/{id} | bearer (any) | read menu |
| PATCH | /v1/menus/{id} | bearer (owner / manager) | update menu metadata |
| POST | /v1/menus/{id}/publish | bearer (owner / manager) | publish (draft → published) |
| POST | /v1/menus/{id}/unpublish | bearer (owner / manager) | unpublish (published → draft) |
| POST | /v1/menus/{id}/categories | bearer (owner / manager) | add category |
| PATCH | /v1/menus/{id}/categories/{cid} | bearer (owner / manager) | update category |
| DELETE | /v1/menus/{id}/categories/{cid} | bearer (owner / manager) | delete category |
| POST | /v1/menus/{id}/categories/{cid}/products | bearer (owner / manager) | add product |
| PATCH | /v1/menus/{id}/categories/{cid}/products/{pid} | bearer (owner / manager) | update product |
| DELETE | /v1/menus/{id}/categories/{cid}/products/{pid} | bearer (owner / manager) | delete product |
| POST | /v1/menus/{id}/products/{pid}/modifiers | bearer (owner / manager) | add modifier |
| POST | /v1/menus/{id}/products/{pid}/addons | bearer (owner / manager) | add add-on |
| POST | /v1/menus/{id}/products/{pid}/price | bearer (owner / manager) | change price (with effective date) |
| POST | /v1/menus/{id}/products/{pid}/86 | bearer (owner / manager / staff) | 86 the item |
| DELETE | /v1/menus/{id}/products/{pid}/86 | bearer (owner / manager / staff) | un-86 |
| GET | /v1/restaurants/{restaurant_id}/menu | bearer (any) | read the published menu for a restaurant |
| GET | /v1/menus/{id}/items/{pid} | bearer (any) | read a single product |
| GET | /v1/menus/by-restaurant/{restaurant_id} | bearer (system) | list menus |
| GET | /v1/menus/products/{pid}/availability | bearer (system) | is the product orderable? |
| GET | /health, /ready, /started | none | K8s probes |

(Full contracts in `INTEGRATION.md`.)

## 10. Events Produced

| Event | Trigger | Consumers |
|-------|---------|-----------|
| `menu.created.v1` | menu created | `cart-service`, `search-service`, `inventory-service`, `audit-service` |
| `menu.updated.v1` | menu or category changes | `cart-service`, `search-service`, `inventory-service`, `audit-service` |
| `menu.published.v1` | menu published | `cart-service`, `search-service`, `audit-service` |
| `menu.unpublished.v1` | menu unpublished | `cart-service`, `search-service`, `audit-service` |
| `menu.item.price.changed.v1` | product price change | `cart-service` (re-quote), `audit-service` |
| `menu.item.unavailable.v1` | product 86'd | `cart-service` (remove from cart), `search-service`, `audit-service` |
| `menu.item.available.v1` | product un-86'd | `cart-service`, `search-service`, `audit-service` |

(Full contracts in `INTEGRATION.md`.)

## 11. Events Consumed

| Event | Producer | Reason | Handler |
|-------|----------|--------|---------|
| `restaurant.created.v1` | `restaurant-service` | parent eligible | log only |
| `restaurant.suspended.v1` | `restaurant-service` | cascade unpublish | unpublish all `published` menus of the restaurant |
| `restaurant.closed.v1` | `restaurant-service` | cascade unpublish | same |
| `inventory.item.out_of_stock.v1` | `inventory-service` | stock-driven 86 | if the product has an `inventory_item_id` and the policy is `auto_86_on_oos`, set `unavailable = true` and emit `menu.item.unavailable.v1` |
| `inventory.item.restocked.v1` | `inventory-service` | stock restored | clear `unavailable` if it was stock-driven |
| `tax.updated.v1` / `configuration.updated.v1` | `tax-service` / `configuration-service` | tax code cache invalidation | invalidate Redis cache for `tax:code:*` |

## 12. External Integrations

- **Image storage** via `file-service`.
- **Tax calculation** via `tax-service` (read-mostly; cached).
- No direct payment provider integration.

## 13. Configuration

| Key | Type | Source | Notes |
|-----|------|--------|-------|
| `menu.max_categories` | int | configuration-service | per menu |
| `menu.max_products_per_category` | int | configuration-service | per category |
| `menu.max_modifiers_per_product` | int | configuration-service | per product |
| `menu.max_addons_per_product` | int | configuration-service | per product |
| `menu.price.history.max_versions` | int | configuration-service | price history retention |
| `menu.publish.requires_photo` | bool | configuration-service | policy |
| `menu.86.auto_on_oos` | bool | configuration-service | auto-86 on out-of-stock |
| `menu.rate_limit.publish_per_hour` | int | configuration-service | throttle |
| `feature_flag.menu.bulk_publish_enabled` | bool | feature-flag-service | bulk publish rollout |

## 14. Security

- AuthN: **Bearer JWT** validated at gateway; service-to-service
  via `client_credentials`.
- AuthZ: **RBAC** (`merchant_owner`, `merchant_ops`,
  `restaurant_manager`, `restaurant_staff` (kitchen, dispatcher),
  `platform_admin`); fine-grained resource ownership.
- Secrets: Vault paths `secret/menu-service/{env}`.
- PII: minimal; only the operator's Keycloak subject.
- Audit: every state change emits an event.

## 15. Observability

- Logs: JSON to stdout, fields: `service=menu-service`,
  `correlation_id`, `trace_id`, `user_id`, `route`, `latency_ms`,
  `status`, `menu_id`, `product_id`, `restaurant_id`, `state`.
- Metrics:
  - RED: standard.
  - Business: `menus_published_total{restaurant_id}`,
    `menus_unpublished_total{reason}`,
    `menu_items_total{state}`,
    `menu_items_86d_total{reason}`,
    `menu_price_changes_total`,
    `menu_lookups_total{cache_hit}`,
    `menu_publish_seconds` (histogram).
- Traces: OpenTelemetry auto-instrumented.
- Health: `/health`, `/ready`, `/started`.

## 16. Scalability

- Replicas: default **3**, HPA on CPU > 60% and
  `http_requests_in_flight > 500/replica`; max 12.
- Hot path: `GET /v1/restaurants/{restaurant_id}/menu` (called
  by `cart-service` on every cart open) — Redis-cached with
  60 s TTL; key `menu:by_restaurant:{restaurant_id}`.
- DB: 1 read replica in each region.
- Cache: Redis cluster.

## 17. Local Development

- `docker compose up` boots PostgreSQL, Kafka, Redis, and the
  service in dev mode.
- Seed: 1 published menu with 3 categories, 8 products, 2
  modifiers each, 3 add-ons.
- `bun run test`, `bun run e2e`.

## 18. Deployment

- Image: `registry.platform.io/menu-service:{git-sha}`.
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

- **Depends on**: [`audit-service`](../audit-service/README.md), [`branch-service`](../branch-service/README.md), [`cart-service`](../cart-service/README.md), [`checkout-service`](../checkout-service/README.md), [`configuration-service`](../configuration-service/README.md), [`feature-flag-service`](../feature-flag-service/README.md), [`file-service`](../file-service/README.md), [`food-order-service`](../food-order-service/README.md), [`inventory-service`](../inventory-service/README.md), [`notification-service`](../notification-service/README.md), [`restaurant-order-mgmt-service`](../restaurant-order-mgmt-service/README.md), [`restaurant-service`](../restaurant-service/README.md), [`search-service`](../search-service/README.md), [`tax-service`](../tax-service/README.md)
- **Depended on by**: [`api-gateway`](../api-gateway/README.md), [`branch-service`](../branch-service/README.md), [`cart-service`](../cart-service/README.md), [`food-order-service`](../food-order-service/README.md), [`inventory-service`](../inventory-service/README.md), [`merchant-service`](../merchant-service/README.md), [`pricing-service`](../pricing-service/README.md), [`restaurant-order-mgmt-service`](../restaurant-order-mgmt-service/README.md), [`restaurant-service`](../restaurant-service/README.md), [`search-service`](../search-service/README.md), [`tax-service`](../tax-service/README.md)

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

- [`../../workflows/MERCHANT_WORKFLOWS.md`](../../workflows/MERCHANT_WORKFLOWS.md) — merchant onboarding, menu ops
