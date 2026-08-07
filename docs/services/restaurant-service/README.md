# restaurant-service

## 1. Purpose

`restaurant-service` is the canonical owner of the **restaurant
aggregate** — the operational brand that a merchant operates on the
platform. It owns the restaurant's profile (name, type, cuisines,
logo, brand), its lifecycle state (draft, pending_review, approved,
online, offline, suspended, closed), and its operational ratings
(average rating aggregated by ``trip-service` / `food-order-service` / `search-service` (review projections)`). It does
NOT own the merchant (legal entity) — that is ``restaurant-service` (merchant)` —
and it does NOT own branches (physical locations) or menus.

## 2. Bounded Context

- **In scope**: restaurant brand, type, cuisines, logo, ratings
  (denormalized from ``trip-service` / `food-order-service` / `search-service` (review projections)`), online/offline
  status, lifecycle state, opening hours (delegated to branches
  but exposed at restaurant level for searching), tags, search
  attributes.
- **Out of scope**: merchant legal entity; branches; menus; staff;
  orders; payments. The restaurant is the brand under which a
  merchant operates; one merchant may own many restaurants.

## 3. Responsibilities

- Onboard a new restaurant under an approved merchant.
- Persist and serve the restaurant profile (name, type, cuisines,
  logo ref, description).
- Maintain the restaurant's online/offline state.
- Suspend and reinstate a restaurant (admin action).
- Permanently close a restaurant (admin action).
- Aggregate review ratings (read-side projection from
  ``trip-service` / `food-order-service` / `search-service` (review projections)`).
- Expose a search-friendly view (consumed by `search-service`).
- Emit `restaurant.*.v1` events for every state change.
- Block new order acceptance when the restaurant is offline,
  suspended, or closed (in cooperation with ``food-order-service` (cart)` and
  ``food-order-service` (checkout)`).

## 4. Explicitly NOT Owned

- **Merchant legal entity** — owned by ``restaurant-service` (merchant)`. A
  restaurant holds a `merchant_id` UUID column with no FK.
- **Branches** (physical locations, hours, prep capacity) — owned
  by ``restaurant-service` (branch)`. A restaurant has 1..n branches.
- **Menus** — owned by ``restaurant-service` (menu)`. The menu is keyed by
  `restaurant_id`.
- **Restaurant staff** — owned by ``restaurant-service` (staff)`.
- **Reviews and ratings** — owned by ``trip-service` / `food-order-service` / `search-service` (review projections)`; this
  service holds a denormalized aggregate (`avg_rating`,
  `review_count`).
- **Orders, prep state** — owned by `food-order-service` and
  ``food-order-service` (queue)`.
- **Geocoding** — owned by `geolocation-service`; the restaurant
  inherits its service area from its branches (and indirectly
  from ``geolocation-service` (zones)`).

## 5. Actors

| Actor | Type | Access |
|-------|------|--------|
| Merchant Owner | human | read/write own restaurants |
| Merchant Ops | human | read/write own restaurants |
| Platform Admin | human | read/write any restaurant; lifecycle |
| Restaurant Operator (staff) | human | read; toggle online/offline |
| ``restaurant-service` (merchant)` | system | read (merchant exists check) |
| ``restaurant-service` (branch)` | system | read (parent) |
| ``restaurant-service` (menu)` | system | read (parent) |
| ``food-order-service` (cart)` | system | read (online check at checkout) |
| ``food-order-service` (checkout)` | system | read (online check at checkout) |
| `food-order-service` | system | read (restaurant ref) |
| ``food-order-service` (queue)` | system | read (restaurant ref) |
| `search-service` | system | read (index source) |
| ``trip-service` / `food-order-service` / `search-service` (review projections)` | system | write (rating update) |
| `audit-service` | system | read (audit trail) |

## 6. Dependencies

### Synchronous (REST)

- ``restaurant-service` (merchant)` — validate merchant exists and is approved
  before allowing restaurant creation — SLO 99.95%, circuit
  breaker: **yes**.
- `configuration-service` — read restaurant configuration (cuisine
  list, type enums) — SLO 99.95%, circuit breaker: **yes**.
- `identity-service` — Keycloak subject verification — SLO 99.95%,
  circuit breaker: **yes**.
- `geolocation-service` — derive service zone from a sample
  address (read-only) — SLO 99.9%, circuit breaker: **yes**.
- `notification-service` — trigger lifecycle messages — SLO 99.9%,
  circuit breaker: **yes**.

### Asynchronous (events consumed)

- `merchant.approved.v1` from ``restaurant-service` (merchant)` — a merchant is
  now eligible to host restaurants — duplicate handling: **inbox
  dedup**.
- `merchant.suspended.v1` from ``restaurant-service` (merchant)` — cascade
  suspension to all restaurants of the merchant — duplicate
  handling: **inbox dedup**.
- `merchant.reinstated.v1` from ``restaurant-service` (merchant)` — cascade
  re-instatement — duplicate handling: **inbox dedup**.
- `merchant.closed.v1` from ``restaurant-service` (merchant)` — close all
  restaurants — duplicate handling: **inbox dedup**.
- `branch.created.v1` from ``restaurant-service` (branch)` — a branch is added
  under this restaurant; recompute service availability — **inbox
  dedup**.
- `branch.hours.changed.v1` from ``restaurant-service` (branch)` — recompute
  online state — **inbox dedup**.
- `review.submitted.v1` and `review.aggregated.v1` from
  ``trip-service` / `food-order-service` / `search-service` (review projections)` — update denormalized rating fields —
  **inbox dedup**.

## 7. Technology Assumptions

- Runtime: **Node 20** (TypeScript), NestJS/Fastify.
- Database: **PostgreSQL 18** (per-service schema `restaurant`).
- Cache: **Redis** (per-service, used for the search-friendly
  restaurant summary and for ``food-order-service` (cart)`/``food-order-service` (checkout)`
  online lookups).
- Event broker: **Kafka**.
- ORM: **Prisma** (TypeScript).
- Migration tool: **prisma migrate**, versioned, forward-only.

## 8. Database Ownership

- Schema: `restaurant` (owned exclusively by this service).
- Tables: `restaurants`, `restaurant_cuisines`, `restaurant_tags`,
  `restaurant_ratings`, `outbox`, `inbox`.
- Migrations: `services/restaurant-service/prisma/migrations/`.
- Soft delete: **yes** (`deleted_at` on `restaurants`).
- Partitioning: **no**.

## 9. API Overview

| Method | URI | Auth | Purpose |
|--------|-----|------|---------|
| POST | /v1/restaurants | bearer (merchant_owner) | create (under approved merchant) |
| GET | /v1/restaurants/{id} | bearer (owner / staff / admin) | read |
| PATCH | /v1/restaurants/{id} | bearer (owner / admin) | update profile |
| POST | /v1/restaurants/{id}/submit | bearer (owner) | submit for review |
| POST | /v1/restaurants/{id}/approve | bearer (admin) | approve |
| POST | /v1/restaurants/{id}/reject | bearer (admin) | reject (reason required) |
| POST | /v1/restaurants/{id}/online | bearer (owner / staff) | go online |
| POST | /v1/restaurants/{id}/offline | bearer (owner / staff) | go offline |
| POST | /v1/restaurants/{id}/suspend | bearer (admin) | suspend |
| POST | /v1/restaurants/{id}/reinstate | bearer (admin) | reinstate |
| POST | /v1/restaurants/{id}/close | bearer (admin) | close (terminal) |
| GET | /v1/restaurants | bearer (admin / search) | list (filters) |
| GET | /v1/restaurants/by-merchant/{merchant_id} | bearer (system) | list for a merchant |
| GET | /v1/restaurants/{id}/online | bearer (system) | is it online? (cached) |
| GET | /v1/restaurants/{id}/summary | bearer (system) | search summary |
| GET | /health, /ready, /started | none | K8s probes |

(Full contracts in `INTEGRATION.md`.)

## 10. Events Produced

| Event | Trigger | Consumers |
|-------|---------|-----------|
| `restaurant.created.v1` | `POST /v1/restaurants` | ``restaurant-service` (branch)`, ``restaurant-service` (menu)`, `search-service`, `audit-service` |
| `restaurant.approved.v1` | admin approval | ``restaurant-service` (branch)`, ``restaurant-service` (menu)`, `search-service`, `audit-service` |
| `restaurant.rejected.v1` | admin rejection | `notification-service`, `audit-service` |
| `restaurant.online.v1` | owner/staff toggle | ``food-order-service` (cart)`, `search-service`, ``courier-service` (dispatch)`, `audit-service` |
| `restaurant.offline.v1` | owner/staff toggle or auto | ``food-order-service` (cart)`, `search-service`, ``courier-service` (dispatch)`, `audit-service` |
| `restaurant.suspended.v1` | admin suspend or cascade | ``restaurant-service` (branch)`, ``restaurant-service` (menu)`, ``food-order-service` (cart)`, ``courier-service` (dispatch)`, `search-service`, `notification-service`, `audit-service` |
| `restaurant.reinstated.v1` | admin reinstate | ``restaurant-service` (branch)`, ``restaurant-service` (menu)`, ``food-order-service` (cart)`, ``courier-service` (dispatch)`, `search-service`, `notification-service`, `audit-service` |
| `restaurant.closed.v1` | admin close | ``restaurant-service` (branch)`, ``restaurant-service` (menu)`, ``food-order-service` (cart)`, ``courier-service` (dispatch)`, `search-service`, `notification-service`, `audit-service` |
| `restaurant.updated.v1` | profile changes | `search-service`, `audit-service` |

(Full contracts in `INTEGRATION.md`.)

## 11. Events Consumed

| Event | Producer | Reason | Handler |
|-------|----------|--------|---------|
| `merchant.approved.v1` | ``restaurant-service` (merchant)` | parent merchant is approved | note: enables creation of restaurants under it |
| `merchant.suspended.v1` | ``restaurant-service` (merchant)` | parent merchant suspended | cascade: suspend all `approved|online` restaurants of the merchant |
| `merchant.reinstated.v1` | ``restaurant-service` (merchant)` | parent merchant reinstated | cascade: reinstate restaurants (but leave `offline` if owner set them offline) |
| `merchant.closed.v1` | ``restaurant-service` (merchant)` | parent merchant closed | cascade: close all restaurants |
| `branch.created.v1` | ``restaurant-service` (branch)` | new branch under a restaurant | recompute `online` based on branch hours |
| `branch.hours.changed.v1` | ``restaurant-service` (branch)` | branch hours changed | recompute `online` |
| `review.submitted.v1` | ``trip-service` / `food-order-service` / `search-service` (review projections)` | a new review was submitted | update denormalized rating |
| `review.aggregated.v1` | ``trip-service` / `food-order-service` / `search-service` (review projections)` | rating re-aggregated | update denormalized rating |

## 12. External Integrations

- **Image / logo storage** via `file-service` — credentials in
  Vault at `secret/file-service/{env}` (not held by this service).
- **Map / geocoding** via `geolocation-service` (read-only).
- No direct payment provider integration.

## 13. Configuration

| Key | Type | Source | Notes |
|-----|------|--------|-------|
| `restaurant.cuisine.list` | array<string> | configuration-service | allowed cuisine tags |
| `restaurant.type.list` | array<string> | configuration-service | e.g. `restaurant`, `cafe`, `bakery` |
| `restaurant.suspension.reason_codes` | array<string> | configuration-service | admin enum |
| `restaurant.suspension.grace_period_hours` | int | configuration-service | warning window |
| `restaurant.online.required_branches` | int | configuration-service | min open branches to be online |
| `restaurant.rate_limit.create_per_hour` | int | configuration-service | throttle creations |
| `restaurant.feature.auto_offline_on_no_open_branch` | bool | configuration-service | auto-set offline when no branch is open |
| `feature_flag.restaurant.auto_approve_enabled` | bool | `configuration-service` (flags) | auto-approval rollout |

## 14. Security

- AuthN: **Bearer JWT** validated at gateway; service-to-service
  via `client_credentials`.
- AuthZ: **RBAC** (`merchant_owner`, `merchant_ops`,
  `platform_admin`, `restaurant_staff`); fine-grained resource
  ownership (`restaurant.merchant.owner_kc_sub == sub` for owner
  actions).
- Secrets: Vault paths `secret/restaurant-service/{env}`.
- PII: minimal; mostly public info (name, cuisines) plus the
  owner's Keycloak subject (held as `created_by` for audit).
- Audit: every admin action and every state change emits an
  event.

## 15. Observability

- Logs: JSON to stdout, fields: `service=restaurant-service`,
  `correlation_id`, `trace_id`, `user_id`, `route`, `latency_ms`,
  `status`, `restaurant_id`, `merchant_id`, `state`.
- Metrics:
  - RED: standard.
  - Business: `restaurants_created_total{country,cuisine}`,
    `restaurants_online_total`,
    `restaurants_offline_total{reason}`,
    `restaurant_suspension_propagation_seconds`,
    `restaurant_search_lookups_total{cache_hit}`.
  - USE: standard.
- Traces: OpenTelemetry auto-instrumented.
- Health: `/health`, `/ready`, `/started`.

## 16. Scalability

- Replicas: default **3**, HPA on CPU > 60% and
  `http_requests_in_flight > 500/replica`; max 12.
- Hot path: `GET /v1/restaurants/{id}/online` (called on every
  cart add and every checkout) — Redis-cached with 30 s TTL; key
  `restaurant:online:{id}`.
- DB: 1 read replica in each region.
- Cache: Redis cluster.

## 17. Local Development

- `docker compose up` boots PostgreSQL, Kafka, Redis, and the
  service in dev mode.
- Seed: 3 restaurants under an approved merchant in different
  states (`approved|online`, `approved|offline`, `suspended`).
- `bun run test`, `bun run e2e`.

## 18. Deployment

- Image: `registry.platform.io/restaurant-service:{git-sha}`.
- Replicas: 3 baseline, HPA up to 12.
- Resource limits: 500m–2000m CPU, 512Mi–2Gi memory.
- Migrations: init container.
- Rollout: rolling update with `maxUnavailable: 0`,
  `maxSurge: 1`.
- Region: `eu-west` and `ap-southeast`.


---

## Appendix A — Removed predecessor capability

The capability that used to live in ``restaurant-service` (merchant)` (legal
entity), ``restaurant-service` (branch)` (physical locations), ``restaurant-service` (menu)`
(categories, products, modifiers, add-ons, pricing),
``restaurant-service` (inventory)` (stock counts, 86-list), and
``restaurant-service` (staff)` (staff invitations, role assignments,
devices) is now absorbed into this service. The canonical source
is [`../../MIGRATION_HUB.md`](../../MIGRATION_HUB.md) 3.16
(merchant), 3.17 (branch), 3.18 (menu), 3.19 (inventory),
3.20 (restaurant-staff). Section numbering is preserved so deep
links into the predecessor READMEs continue to resolve.

### A.1 Bounded context (post-merger)

Merchant legal entity + restaurant brand + branches + menus +
inventory + staff / role assignments / devices / invitations. The
service is the **only** writer of the `restaurant` schema. Out of
scope: Keycloak identity (`identity-service`).

### A.2 Absorbed responsibilities (from ``restaurant-service` (merchant)`)

- Maintain `restaurant.merchants` (legal entity, tax info, payout
  refs).
- Approve / suspend / close a merchant.
- Emit `merchant.created.v1`, `merchant.approved.v1`,
  `merchant.suspended.v1`, `merchant.updated.v1`.

### A.3 Absorbed responsibilities (from ``restaurant-service` (branch)`)

- Maintain `restaurant.branches` + `restaurant.branch_hours`.
- Per-branch prep capacity.
- Emit `branch.created.v1`, `branch.updated.v1`,
  `branch.hours.changed.v1`, `branch.busy.v1`.

### A.4 Absorbed responsibilities (from ``restaurant-service` (menu)`)

- Categories, products, modifiers, add-ons, pricing.
- 86-list (integrated with the absorbed inventory capability).
- Emit `menu.created.v1`, `menu.updated.v1`,
  `menu.item.price.changed.v1`, `menu.item.unavailable.v1`.

### A.5 Absorbed responsibilities (from ``restaurant-service` (inventory)`)

- Stock counts and time-bound availability per menu product.
- Emit `inventory.item.out_of_stock.v1`,
  `inventory.item.restocked.v1`, `inventory.item.86d.v1`.

### A.6 Absorbed responsibilities (from ``restaurant-service` (staff)`)

- Invite a staff member by email; issue an invitation token.
- Activate a staff member (after they complete Keycloak sign-up
  and the invitation token is presented).
- Assign roles per restaurant or per branch (`manager`,
  `cashier`, `kitchen`, `dispatcher`).
- Manage per-device login state (allow-list of device IDs per
  staff member).
- Deactivate a staff member (admin or owner action).
- Emit `staff.invited.v1`, `staff.activated.v1`,
  `staff.deactivated.v1`.

### A.7 Absorbed REST endpoints (highlights)

| Method | URI | Auth | Purpose |
|--------|-----|------|---------|
| POST | `/v1/merchants` | bearer (admin) | create merchant |
| GET  | `/v1/merchants/{id}` | bearer | read |
| PATCH | `/v1/merchants/{id}` | bearer (admin) | update |
| POST | `/v1/merchants/{id}/approve` | bearer (admin) | approve |
| POST | `/v1/merchants/{id}/suspend` | bearer (admin) | suspend |
| POST | `/v1/restaurants/{id}/branches` | bearer (manager) | create branch |
| GET  | `/v1/restaurants/{id}/branches` | bearer | list |
| PATCH | `/v1/branches/{id}` | bearer (manager) | update |
| POST | `/v1/branches/{id}/hours` | bearer (manager) | set hours |
| POST | `/v1/restaurants/{id}/menu/categories` | bearer (manager) | create category |
| POST | `/v1/restaurants/{id}/menu/products` | bearer (manager) | create product |
| GET  | `/v1/restaurants/{id}/menu` | bearer | read menu |
| PATCH | `/v1/menu/products/{product_id}/price` | bearer (manager) | change price |
| POST | `/v1/menu/products/{product_id}/86` | bearer (manager) | mark unavailable |
| POST | `/v1/menu/products/{product_id}/stock` | bearer (manager) | upsert stock |
| POST | `/v1/menu/products/{product_id}/restock` | bearer (manager) | restock |
| POST | `/v1/restaurants/{id}/staff/invite` | bearer (manager) | invite staff |
| POST | `/v1/staff/activate` | bearer (invitee) | activate with invitation token |
| POST | `/v1/restaurants/{id}/staff/{staff_id}/roles` | bearer (manager) | assign roles |
| POST | `/v1/restaurants/{id}/staff/{staff_id}/devices` | bearer (staff) | register device |
| POST | `/v1/restaurants/{id}/staff/{staff_id}/deactivate` | bearer (manager) | deactivate |

### A.8 Absorbed events

**Produced** (same topic + schema version, by this service):

- `merchant.created.v1`, `merchant.approved.v1`,
  `merchant.suspended.v1`, `merchant.updated.v1`.
- `branch.created.v1`, `branch.updated.v1`,
  `branch.hours.changed.v1`, `branch.busy.v1`.
- `menu.created.v1`, `menu.updated.v1`,
  `menu.item.price.changed.v1`, `menu.item.unavailable.v1`.
- `inventory.item.out_of_stock.v1`, `inventory.item.restocked.v1`,
  `inventory.item.86d.v1`.
- `staff.invited.v1`, `staff.activated.v1`, `staff.deactivated.v1`.

### A.9 Absorbed configuration keys

- `restaurant.staff.invite_ttl_hours` (int, default 168 = 7 days).
- `restaurant.staff.max_devices_per_staff` (int, default 5).
- `restaurant.menu.price_change_min_minor` (int, default 0).

### A.10 Compatibility window

For at least six calendar months from 2026-08-05:

- `merchant.*.v1`, `branch.*.v1`, `menu.*.v1`, `inventory.*.v1`,
  `staff.*.v1` are published under the same topic names and schema
  versions.
- `/v1/merchants*`, `/v1/restaurants/{id}/branches*`,
  `/v1/branches/{id}*`, `/v1/restaurants/{id}/menu*`,
  `/v1/menu/*`, `/v1/restaurants/{id}/staff/*`,
  `/v1/staff/activate` continue to be served from this service.
- Old schema names `merchant.*`, `branch.*`, `menu.*`,
  `inventory.*`, `restaurant_staff.*` remain readable as views in
  the `restaurant` schema.

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

- **Depends on**: [`admin-service`](../admin-service/README.md), [`audit-service`](../audit-service/README.md), [`configuration-service`](../configuration-service/README.md), [`courier-service`](../courier-service/README.md), [`customer-service`](../customer-service/README.md), [`driver-service`](../driver-service/README.md), [`file-service`](../file-service/README.md), [`food-order-service`](../food-order-service/README.md), [`geolocation-service`](../geolocation-service/README.md), [`identity-service`](../identity-service/README.md), [`notification-service`](../notification-service/README.md), [`payment-service`](../payment-service/README.md), [`pricing-service`](../pricing-service/README.md), [`search-service`](../search-service/README.md)
- **Depended on by**: [`api-gateway`](../api-gateway/README.md), [`courier-service`](../courier-service/README.md), [`file-service`](../file-service/README.md), [`food-order-service`](../food-order-service/README.md), [`identity-service`](../identity-service/README.md), [`notification-service`](../notification-service/README.md), [`payment-service`](../payment-service/README.md), [`search-service`](../search-service/README.md)

> Full dependency map in [`../README.md`](../README.md) and [`../../architecture/MICROSERVICES_MAP.md`](../../architecture/MICROSERVICES_MAP.md).

### Platform-wide

- [`../../shared/README.md`](../../shared/README.md) — `platform-spring-boot-starter` shared library (the single source of cross-cutting code for all Spring Boot services in the platform)
- [`../../shared/PLATFORM_BASELINE.md`](../../shared/PLATFORM_BASELINE.md) — single source for PostgreSQL 18, Kafka, Keycloak, Redis, OpenTelemetry, Vault, deployment, DR (do not restate these in this README)
- [`../../architecture/SERVICE_ISOLATION.md`](../../architecture/SERVICE_ISOLATION.md) — **how this service behaves when a downstream is down** (timeout / bulkhead / circuit / retry / fallback, by class: CRITICAL / DEGRADABLE / BEST-EFFORT)
- [`../../architecture/DOWNSTREAM_ERROR_CATALOG.md`](../../architecture/DOWNSTREAM_ERROR_CATALOG.md) — **canonical error-code catalog + propagation rules** (the `downstream` block, forward/translate/degrade/reject)
- [`../RECOMMENDATIONS.md`](../RECOMMENDATIONS.md) — platform-wide technology map (language, framework, version baseline, admin/RBAC pattern)
- [`../../README.md`](../../README.md) — services overview (the catalog of all 20 services)
- [`../../../main.md`](../../../main.md) — top-level platform specification (architecture, Keycloak, PostgreSQL 18, messaging, observability baseline)
- [`../../shared/OSS_DEPENDENCIES.md`](../../shared/OSS_DEPENDENCIES.md) — **open-source dependencies & license attribution** (platform-wide OSS projects + per-language OSS libraries with SPDX IDs; per-service bundle index; license compatibility matrix)
- [`../../shared/TYPE_CATALOG.md`](../../shared/TYPE_CATALOG.md) — **platform-wide type vocabulary** — merchant tiers / restaurant types (restaurant / cafe / bakery / cloud_kitchen / food_truck / other) catalogued in [7](../../shared/TYPE_CATALOG.md#7-merchant-tiers--restaurant-types); CHECK at `restaurant.restaurants.type`. Cuisines, dietary tags, and spice levels live in the shared [`LOOKUPS.md`](../../shared/LOOKUPS.md) catalog.

### Workflows this service participates in

- [`../../workflows/MERCHANT_WORKFLOWS.md`](../../workflows/MERCHANT_WORKFLOWS.md) — merchant onboarding, menu ops
