# branch-service

## 1. Purpose

`branch-service` is the canonical owner of the **branch aggregate**
— a physical location of a restaurant where customers can order
from. It owns the branch's address, geographic coordinates, weekly
opening hours, special hours / holidays, prep capacity (max
concurrent orders), busy state, and online availability derived
from hours. It does NOT own the restaurant (brand) or the merchant
(legal entity).

## 2. Bounded Context

- **In scope**: physical location, address, geocoded point, weekly
  hours, special hours, prep capacity, busy state, temporary
  closures, on-shift status.
- **Out of scope**: restaurant brand, menus, staff, orders. A
  branch belongs to exactly one restaurant; a restaurant has
  1..n branches.

## 3. Responsibilities

- CRUD for branches under an approved restaurant.
- Persist and serve branch address, hours, capacity, busy state.
- Geocode the address (via `geolocation-service`) and store the
  normalized point.
- Compute current open/closed state from hours (and special
  hours) and emit `branch.hours.changed.v1` on changes.
- Support temporary closures (e.g. equipment failure) and
  busy-state changes (operator signals "kitchen overwhelmed").
- Emit `branch.created.v1`, `branch.updated.v1`,
  `branch.hours.changed.v1`, `branch.busy.v1`.

## 4. Explicitly NOT Owned

- **Restaurant brand** — owned by `restaurant-service`. A branch
  holds a `restaurant_id` UUID column with no FK.
- **Merchant legal entity** — owned by `merchant-service`.
- **Menus** — owned by `menu-service`. A branch can serve from
  one or more menus (the menu is keyed at the restaurant level,
  not the branch; the branch's hours determine when a menu is
  available).
- **Orders and prep state** — owned by `food-order-service` and
  `restaurant-order-mgmt-service`.
- **Geocoding** — owned by `geolocation-service`; this service
  calls it to normalize addresses.
- **Couriers** — owned by `courier-dispatch-service`; this
  service does not assign couriers.

## 5. Actors

| Actor | Type | Access |
|-------|------|--------|
| Merchant Owner | human | read/write own branches |
| Merchant Ops | human | read/write own branches |
| Restaurant Operator (staff) | human | read; toggle busy, temporary closure |
| Platform Admin | human | read/write any branch; lifecycle |
| `restaurant-service` | system | read (parent) |
| `geolocation-service` | system | geocoding (call) |
| `zone-service` | system | read (zone validation) |
| `menu-service` | system | read (parent) |
| `cart-service` | system | read (branch open / busy) |
| `checkout-service` | system | read (branch open) |
| `courier-dispatch-service` | system | read (branch open / busy) |
| `food-order-service` | system | read (branch ref) |
| `restaurant-order-mgmt-service` | system | read (branch ref) |
| `audit-service` | system | read (audit trail) |

## 6. Dependencies

### Synchronous (REST)

- `restaurant-service` — verify parent restaurant is approved
  before allowing branch creation — SLO 99.95%, circuit breaker:
  **yes**.
- `geolocation-service` — geocode the address (synchronous on
  create; cache result) — SLO 99.9%, circuit breaker: **yes**.
- `zone-service` — verify the branch is within a serving zone —
  SLO 99.95%, circuit breaker: **yes**.
- `configuration-service` — read hours / capacity defaults — SLO
  99.95%, circuit breaker: **yes**.
- `identity-service` — Keycloak subject verification — SLO 99.95%,
  circuit breaker: **yes**.
- `notification-service` — trigger lifecycle messages — SLO 99.9%,
  circuit breaker: **yes**.

### Asynchronous (events consumed)

- `restaurant.created.v1` from `restaurant-service` — parent
  restaurant is now eligible to host branches — duplicate
  handling: **inbox dedup**.
- `restaurant.suspended.v1` from `restaurant-service` — cascade
  temporary closure to all branches — **inbox dedup**.
- `restaurant.closed.v1` from `restaurant-service` — close all
  branches — **inbox dedup**.
- `zone.updated.v1` from `zone-service` — recompute whether the
  branch is still in a serving zone; close if no longer — **inbox
  dedup**.

## 7. Technology Assumptions

- Runtime: **Node 20** (TypeScript), NestJS/Fastify.
- Database: **PostgreSQL 18** with **PostGIS** extension
  (per-service schema `branch`).
- Cache: **Redis** (per-service, used for fast open / busy
  lookups by `cart-service`, `checkout-service`,
  `courier-dispatch-service`).
- Event broker: **Kafka**.
- ORM: **Prisma** with PostGIS support.
- Migration tool: **prisma migrate**, versioned, forward-only.

## 8. Database Ownership

- Schema: `branch` (owned exclusively by this service).
- Tables: `branches`, `branch_hours`, `branch_special_hours`,
  `branch_temporary_closures`, `outbox`, `inbox`.
- Migrations: `services/branch-service/prisma/migrations/`.
- Soft delete: **yes** (`deleted_at` on `branches`).
- Partitioning: **no**.

## 9. API Overview

| Method | URI | Auth | Purpose |
|--------|-----|------|---------|
| POST | /v1/branches | bearer (merchant_owner) | create branch |
| GET | /v1/branches/{id} | bearer (owner / staff / admin) | read |
| PATCH | /v1/branches/{id} | bearer (owner / admin) | update profile |
| POST | /v1/branches/{id}/close | bearer (admin) | close (terminal) |
| POST | /v1/branches/{id}/open | bearer (owner / admin) | re-open (post temporary closure) |
| POST | /v1/branches/{id}/busy | bearer (staff / owner) | mark busy |
| DELETE | /v1/branches/{id}/busy | bearer (staff / owner) | clear busy |
| POST | /v1/branches/{id}/temporary-closure | bearer (staff / owner) | temporary closure |
| DELETE | /v1/branches/{id}/temporary-closure | bearer (staff / owner) | clear temporary closure |
| PUT | /v1/branches/{id}/hours | bearer (owner / admin) | set weekly hours |
| POST | /v1/branches/{id}/special-hours | bearer (owner / admin) | add a holiday / special date |
| DELETE | /v1/branches/{id}/special-hours/{sid} | bearer (owner / admin) | remove a special date |
| GET | /v1/branches | bearer (admin / search) | list |
| GET | /v1/branches/by-restaurant/{restaurant_id} | bearer (system) | list for a restaurant |
| GET | /v1/branches/{id}/open | bearer (system) | is it open? (cached) |
| GET | /v1/branches/{id}/busy | bearer (system) | is it busy? (cached) |
| GET | /v1/branches/{id}/prep-capacity | bearer (system) | max concurrent orders |
| GET | /health, /ready, /started | none | K8s probes |

(Full contracts in `INTEGRATION.md`.)

## 10. Events Produced

| Event | Trigger | Consumers |
|-------|---------|-----------|
| `branch.created.v1` | `POST /v1/branches` | `menu-service`, `cart-service`, `courier-dispatch-service`, `search-service`, `audit-service` |
| `branch.updated.v1` | profile change | `cart-service`, `courier-dispatch-service`, `search-service`, `audit-service` |
| `branch.hours.changed.v1` | hours / special hours change | `cart-service`, `courier-dispatch-service`, `search-service`, `audit-service` |
| `branch.busy.v1` | busy toggle | `courier-dispatch-service`, `cart-service`, `audit-service` |
| `branch.closed.v1` | permanent close | `menu-service`, `cart-service`, `courier-dispatch-service`, `search-service`, `notification-service`, `audit-service` |
| `branch.temporary_closure.v1` | temporary closure toggle | `cart-service`, `courier-dispatch-service`, `audit-service` |

(Full contracts in `INTEGRATION.md`.)

## 11. Events Consumed

| Event | Producer | Reason | Handler |
|-------|----------|--------|---------|
| `restaurant.created.v1` | `restaurant-service` | parent eligible | log; no action |
| `restaurant.suspended.v1` | `restaurant-service` | parent suspended | cascade: temporary-closure all branches (with reason `parent_suspended`) |
| `restaurant.closed.v1` | `restaurant-service` | parent closed | cascade: close all non-terminal branches |
| `zone.updated.v1` | `zone-service` | zone changed | check if branch is still in a serving zone; if not, temporary-closure with reason `out_of_zone` |

## 12. External Integrations

- **Map / geocoding** via `geolocation-service` (read-mostly).
- No direct payment provider integration.

## 13. Configuration

| Key | Type | Source | Notes |
|-----|------|--------|-------|
| `branch.default_hours` | object<day,open,close> | configuration-service | default weekly hours |
| `branch.prep_capacity.default` | int | configuration-service | default max concurrent orders |
| `branch.prep_capacity.max` | int | configuration-service | upper bound per branch |
| `branch.busy.threshold_orders` | int | configuration-service | auto-busy threshold |
| `branch.hours.timezone.default` | string | configuration-service | default branch timezone (IANA) |
| `branch.cascade.suspend_to_temp_closure` | bool | configuration-service | policy |
| `feature_flag.branch.auto_busy_enabled` | bool | feature-flag-service | auto-busy rollout |

## 14. Security

- AuthN: **Bearer JWT** validated at gateway; service-to-service
  via `client_credentials`.
- AuthZ: **RBAC** (`merchant_owner`, `merchant_ops`,
  `restaurant_staff`, `platform_admin`); fine-grained resource
  ownership at the service layer.
- Secrets: Vault paths `secret/branch-service/{env}`.
- PII: minimal; only the operator's Keycloak subject.
- Audit: every state change emits an event.

## 15. Observability

- Logs: JSON to stdout, fields: `service=branch-service`,
  `correlation_id`, `trace_id`, `user_id`, `route`, `latency_ms`,
  `status`, `branch_id`, `restaurant_id`, `state`.
- Metrics:
  - RED: standard.
  - Business: `branches_created_total{country}`,
    `branches_open_total`,
    `branches_busy_total`,
    `branches_temporary_closure_total{reason}`,
    `branch_hours_change_total`,
    `branch_geocode_seconds`,
    `branch_open_lookups_total{cache_hit}`.
- Traces: OpenTelemetry auto-instrumented.
- Health: `/health`, `/ready`, `/started`.

## 16. Scalability

- Replicas: default **3**, HPA on CPU > 60% and
  `http_requests_in_flight > 500/replica`; max 12.
- Hot path: `GET /v1/branches/{id}/open` (called on every cart
  add and every checkout) — Redis-cached with 30 s TTL; key
  `branch:open:{id}`.
- DB: 1 read replica in each region.
- Cache: Redis cluster.

## 17. Local Development

- `docker compose up` boots PostgreSQL + PostGIS, Kafka, Redis,
  and the service in dev mode.
- Seed: 3 branches under an approved restaurant in different
  states (open, busy, temporary closure).
- `bun run test`, `bun run e2e`.

## 18. Deployment

- Image: `registry.platform.io/branch-service:{git-sha}`.
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

- **Depends on**: [`audit-service`](../audit-service/README.md), [`cart-service`](../cart-service/README.md), [`checkout-service`](../checkout-service/README.md), [`configuration-service`](../configuration-service/README.md), [`courier-dispatch-service`](../courier-dispatch-service/README.md), [`dispatch-service`](../dispatch-service/README.md), [`feature-flag-service`](../feature-flag-service/README.md), [`food-order-service`](../food-order-service/README.md), [`geolocation-service`](../geolocation-service/README.md), [`identity-service`](../identity-service/README.md), [`menu-service`](../menu-service/README.md), [`merchant-service`](../merchant-service/README.md), [`notification-service`](../notification-service/README.md), [`restaurant-order-mgmt-service`](../restaurant-order-mgmt-service/README.md), [`restaurant-service`](../restaurant-service/README.md), [`search-service`](../search-service/README.md), [`zone-service`](../zone-service/README.md)
- **Depended on by**: [`api-gateway`](../api-gateway/README.md), [`checkout-service`](../checkout-service/README.md), [`food-order-service`](../food-order-service/README.md), [`inventory-service`](../inventory-service/README.md), [`menu-service`](../menu-service/README.md), [`merchant-service`](../merchant-service/README.md), [`restaurant-order-mgmt-service`](../restaurant-order-mgmt-service/README.md), [`restaurant-service`](../restaurant-service/README.md), [`restaurant-staff-service`](../restaurant-staff-service/README.md)

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
