# courier-dispatch-service

## 1. Purpose

`courier-dispatch-service` is the brain of food-delivery matching. It
receives every food order that the kitchen has marked `ready` and decides
which courier (if any) should pick it up. It owns the courier assignment
ledger — the only system of record for "this courier is committed to
this delivery."

## 2. Bounded Context

Bounded context: **Courier Matching**.

- **In scope**: search for available couriers, batched offer flow,
  assignment decisions, the assignment ledger, no-courier handling,
  reassignment, courier release.
- **Out of scope**: courier profile / KYC (owned by `courier-service`),
  high-frequency location stream (owned by `courier-tracking-service`),
  delivery state machine (owned by `delivery-service`), payment
  (owned by `payment-service` / `food-payment-integration-service`).

## 3. Responsibilities

- Maintain the live pool of *available* couriers in the current city /
  zone, joined with their last-known location.
- Evaluate match attempts for a `food.order.ready.v1` event and
  produce a `delivery.courier.assigned.v1` (or a `no_courier`).
- Run the offer flow: push to couriers, wait for acceptance, time-out
  and re-offer.
- Persist the assignment ledger (`courier_dispatch.assignments`) so
  the decision is auditable and replayable.
- Support batched offers (multiple orders from the same restaurant).
- Handle reassignment: when a courier cancels or fails, re-dispatch
  the same delivery to a new courier.
- Surface `delivery.dispatch.no_courier.v1` when no courier accepts
  within the offer window, and re-dispatch on a configurable interval.

## 4. Explicitly NOT Owned

- The delivery state machine — owned by `delivery-service`.
- Courier profile / KYC / vehicle / shift — owned by `courier-service`.
- High-frequency courier location stream — owned by
  `courier-tracking-service`.
- Food order state — owned by `food-order-service`.
- Any money movement — owned by `payment-service` /
  `food-payment-integration-service` / `wallet-service` /
  `ledger-service`.

## 5. Actors

| Actor | Type | Access |
|-------|------|--------|
| Courier | human (mobile) | accept/reject offer (write) |
| `food-order-service` | system | triggers dispatch (read) |
| `courier-service` | system | online/offline state (read) |
| `courier-tracking-service` | system | live locations (read) |
| `zone-service` | system | surge / restricted zones (read) |
| `delivery-service` | system | assignment result (write — emits event the service subscribes to) |
| `support-service` / `admin-service` | system | force reassign (admin) |

## 6. Dependencies

### Synchronous (REST)

- `courier-service` — `GET /v1/couriers/{id}` to enrich assignment
  records (vehicle, KYC) — circuit breaker: yes, SLO 50ms p99.
- `courier-tracking-service` — `GET /v1/couriers/{id}/location` to read
  the last known point — circuit breaker: yes, SLO 30ms p99.

### Asynchronous (events consumed)

- `food.order.ready.v1` from `food-order-service` — primary trigger
  for dispatch — dedup: inbox on `event_id`.
- `courier.availability.online.v1` / `offline.v1` from
  `courier-service` — updates the available pool — dedup: inbox.
- `courier.location.updated.v1` from `courier-tracking-service` —
  refreshes the live pool ordering by distance — dedup: inbox.
- `courier.shift.ended.v1` from `courier-service` — removes a courier
  from the pool — dedup: inbox.
- `delivery.courier.cancelled.v1` from `delivery-service` — triggers
  reassignment — dedup: inbox.

## 7. Technology Assumptions

- Runtime: Node 20 (TypeScript).
- Database: PostgreSQL 18 (per-service schema `courier_dispatch`).
- Cache: Redis (per-service) for the live available-courier pool.
- Event broker: Kafka.
- Map / geo: PostGIS via `courier-tracking-service` (read) and
  `geolocation-service` (read) — no PostGIS in this schema.

## 8. Database Ownership

- Schema: `courier_dispatch`
- Migrations: `services/courier-dispatch-service/migrations/` (versioned,
  forward-only, `golang-migrate`).
- Soft delete: no (assignments are immutable; cancelled rows are
  flagged via `status`, not deleted).
- Partitioning: no (assignment volume is bounded by orders, which is
  millions/year, not millions/second).

## 9. API Overview

| Method | URI | Auth | Purpose |
|--------|-----|------|---------|
| POST | `/v1/dispatches` | bearer (service) | start a dispatch for a `food_order_id` |
| GET | `/v1/dispatches/{id}` | bearer | read a dispatch attempt |
| GET | `/v1/dispatches?order_id=…` | bearer | list attempts for an order |
| POST | `/v1/dispatches/{id}/offers` | bearer (internal) | record an offer attempt |
| POST | `/v1/dispatches/{id}/accept` | bearer (courier) | courier accepts an offer |
| POST | `/v1/dispatches/{id}/reject` | bearer (courier) | courier rejects an offer |
| POST | `/v1/dispatches/{id}/cancel` | bearer (service / admin) | cancel a dispatch (compensates) |
| POST | `/v1/dispatches/{id}/reassign` | bearer (service / admin) | force reassignment |
| GET | `/v1/dispatches/metrics` | bearer (admin) | operational counters |

(Full contracts in `INTEGRATION.md`.)

## 10. Events Produced

| Event | Trigger | Consumers |
|-------|---------|-----------|
| `delivery.courier.assigned.v1` | a courier accepts an offer and the assignment is committed | `delivery-service`, `food-order-service`, `notification-service`, `audit-service` |
| `delivery.dispatch.no_courier.v1` | offer window expires with no acceptance | `food-order-service`, `notification-service`, `support-service` |
| `delivery.dispatch.offer.expired.v1` | a courier's offer window expires without a response | `audit-service` |
| `delivery.dispatch.reassigned.v1` | a courier cancels / fails; the delivery is re-offered | `notification-service`, `audit-service` |
| `courier_dispatch.audit.assignment_committed.v1` | internal audit of an assignment | `audit-service` |

(Full contracts in `INTEGRATION.md`.)

## 11. Events Consumed

| Event | Producer | Reason | Handler |
|-------|----------|--------|---------|
| `food.order.ready.v1` | `food-order-service` | start a dispatch | enqueue dispatch job |
| `courier.availability.online.v1` | `courier-service` | add courier to pool | upsert pool entry |
| `courier.availability.offline.v1` | `courier-service` | remove courier | mark pool entry offline |
| `courier.location.updated.v1` | `courier-tracking-service` | re-rank pool | update pool ordering (curated stream, throttled) |
| `delivery.courier.cancelled.v1` | `delivery-service` | reassign | enqueue reassignment |
| `configuration.updated.v1` | `configuration-service` | reload offer window / max attempts | refresh in-memory config |

(Full contracts in `INTEGRATION.md`.)

## 12. External Integrations

- None directly. Map provider calls are routed via
  `eta-routing-service` and `geolocation-service`. No third-party
  push notifications — push is delivered through
  `notification-service`.

## 13. Configuration

| Key | Type | Source | Notes |
|-----|------|--------|-------|
| `courier_dispatch.offer_window_seconds` | int | `configuration-service` | default 30; per-city override allowed |
| `courier_dispatch.max_offer_attempts` | int | `configuration-service` | default 6; per-zone override allowed |
| `courier_dispatch.batch_max_size` | int | `configuration-service` | default 3; max orders in one batch |
| `courier_dispatch.no_courier_backoff_seconds` | int | `configuration-service` | default 60; re-offer interval after no_courier |
| `courier_dispatch.pool_max_radius_meters` | int | `configuration-service` | default 3000; max search radius |
| `courier_dispatch.feature.batched_dispatch` | bool | `feature-flag-service` | rollout flag |
| `courier_dispatch.feature.zone_surge_aware` | bool | `feature-flag-service` | rollout flag |

## 14. Security

- AuthN: JWT bearer (Keycloak `platform-courier` for couriers,
  `platform-services` for service-to-service).
- AuthZ: couriers may only act on their own offers
  (`offer.courier_id == sub`); admin endpoints require `courier.admin`
  role.
- Secrets: provider API keys (none directly); database creds via Vault.
- PII: courier name and phone are NOT stored here; only
  `courier_id` (UUID) is referenced.

## 15. Observability

- Logs: JSON to stdout with `correlation_id`, `dispatch_id`,
  `order_id`, `courier_id`, `tenant_id`, `region`.
- Metrics: `dispatches_started_total{result}`,
  `dispatch_offer_seconds{outcome}`,
  `dispatch_pool_size{city,zone}`,
  `dispatch_no_courier_total{city,zone}`,
  `dispatch_assignment_ledger_size`.
- Traces: OpenTelemetry; one root span per dispatch; child spans for
  pool search, offer, accept.
- Health: `/health`, `/ready`, `/started`.

## 16. Scalability

- Replicas: 6 (default) — HPA on `kafka_consumer_lag` and
  `dispatch_pool_size` events.
- Hot path: search the available-courier pool (PostGIS-style nearest-N
  read in Redis with a geo-index). Backed by a sorted set keyed on
  `courier_id` with a score = `epoch_ms_of_last_ping`.

## 17. Local Development

- `docker compose up courier-dispatch-service` brings up the service,
  PostgreSQL, Kafka, and a synthetic courier pool.
- Seed script: `pnpm seed:couriers` — loads 500 couriers in
  `eu-west-amsterdam` for development.
- Tests: `pnpm test` (unit), `pnpm test:e2e` (Kafka + Postgres test
  containers).

## 18. Deployment

- Image: `registry.platform.io/courier-dispatch-service:{version}`.
- Replicas: 6 (per region, can scale).
- Resource limits: 1 vCPU / 1 GiB memory per replica (stateless,
  CPU-bound).
- Migrations: `pnpm migrate:up` runs at job start; migrations are
  owned by this service.
- Rollout: rolling update; HPA on `kafka_consumer_lag`.


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

- **Depends on**: [`admin-service`](../admin-service/README.md), [`audit-service`](../audit-service/README.md), [`configuration-service`](../configuration-service/README.md), [`courier-service`](../courier-service/README.md), [`courier-tracking-service`](../courier-tracking-service/README.md), [`delivery-service`](../delivery-service/README.md), [`dispatch-service`](../dispatch-service/README.md), [`eta-routing-service`](../eta-routing-service/README.md), [`feature-flag-service`](../feature-flag-service/README.md), [`food-order-service`](../food-order-service/README.md), [`food-payment-integration-service`](../food-payment-integration-service/README.md), [`geolocation-service`](../geolocation-service/README.md), [`ledger-service`](../ledger-service/README.md), [`notification-service`](../notification-service/README.md), [`payment-service`](../payment-service/README.md), [`support-service`](../support-service/README.md), [`wallet-service`](../wallet-service/README.md), [`zone-service`](../zone-service/README.md)
- **Depended on by**: [`api-gateway`](../api-gateway/README.md), [`branch-service`](../branch-service/README.md), [`courier-service`](../courier-service/README.md), [`courier-tracking-service`](../courier-tracking-service/README.md), [`delivery-service`](../delivery-service/README.md), [`food-order-service`](../food-order-service/README.md), [`restaurant-order-mgmt-service`](../restaurant-order-mgmt-service/README.md), [`restaurant-service`](../restaurant-service/README.md), [`vehicle-service`](../vehicle-service/README.md), [`zone-service`](../zone-service/README.md)

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

- [`../../workflows/COURIER_WORKFLOWS.md`](../../workflows/COURIER_WORKFLOWS.md) — courier shifts, dispatch, delivery
