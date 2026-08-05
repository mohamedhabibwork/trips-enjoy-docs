# delivery-service

## 1. Purpose

`delivery-service` owns the **delivery aggregate**: the lifecycle of
a single food delivery from "courier assigned" to "delivered" (or
"failed"). It records the courier's state transitions, the proof of
delivery, the customer's contact attempts, and the events that drive
downstream payment, settlement, and earning.

## 2. Bounded Context

Bounded context: **Delivery Aggregate**.

- **In scope**: delivery state machine, proof of delivery, batched
  delivery (one courier, multiple orders from the same restaurant),
  customer-unreachable handling, redelivery / reassignment request.
- **Out of scope**: which courier is matched (owned by
  `courier-dispatch-service`), courier location stream
  (`courier-tracking-service`), food order state
  (`food-order-service`), payment (`payment-service` /
  `food-payment-integration-service`).

## 3. Responsibilities

- Maintain the `delivery` aggregate state machine:
  `assigned → en_route_pickup → arrived_pickup → picked_up →
  en_route_dropoff → delivered` (or `failed`).
- Receive courier state updates from the mobile app
  (`POST /v1/deliveries/{id}/...`) and persist them.
- Record proof of delivery (photo, signature, or PIN).
- Detect customer-unreachable (courier reports + 5-minute wait).
- Trigger redelivery / reassignment through `courier-dispatch-service`.
- Emit lifecycle events for downstream consumers
  (`delivery.pickup.v1`, `delivery.in_transit.v1`,
  `delivery.completed.v1`, `delivery.failed.v1`).
- Support batched deliveries (a single courier may hold up to
  `batch_max_size` deliveries; the state machine handles each
  independently).

## 4. Explicitly NOT Owned

- Courier selection / matching — owned by `courier-dispatch-service`.
- High-frequency courier location stream — owned by
  `courier-tracking-service`.
- Food order state — owned by `food-order-service`.
- Payment, refund, settlement — owned by the financial services.
- Customer notifications — owned by `notification-service`
  (this service only emits trigger events).

## 5. Actors

| Actor | Type | Access |
|-------|------|--------|
| Courier | human (mobile) | state transitions, proof of delivery (write) |
| `courier-dispatch-service` | system | creates delivery (write) |
| `food-payment-integration-service` | system | reads delivery state for the financial saga (read) |
| `customer-service` | system | reads delivery for order history (read) |
| `support-service` | system | force-fail, redeliver, read full state (admin) |
| `admin-service` | system | same as support |

## 6. Dependencies

### Synchronous (REST)

- `courier-service` — `GET /v1/couriers/{id}` to enrich delivery
  records — circuit breaker: yes, SLO 50ms p99.
- `food-order-service` — `GET /v1/orders/{id}` to enrich
  (restaurant, branch) — circuit breaker: yes, SLO 50ms p99.
- `customer-service` — `GET /v1/customers/{id}` (phone, language)
  for notifications — circuit breaker: yes.
- `courier-dispatch-service` — `POST /v1/dispatches/{id}/cancel`
  for reassignment — circuit breaker: yes.

### Asynchronous (events consumed)

- `delivery.courier.assigned.v1` from `courier-dispatch-service` —
  creates a `delivery` row — dedup: inbox.
- `courier.location.updated.v1` from `courier-tracking-service` —
  updates the delivery's `last_known_*` (for ETA computation) —
  dedup: inbox (throttled).
- `food.order.cancelled.v1` from `food-order-service` — sets
  delivery to `cancelled` if not yet `picked_up` — dedup: inbox.
- `customer.suspended.v1` from `customer-service` — marks all
  active deliveries for the customer as `at_risk` — dedup: inbox.

## 7. Technology Assumptions

- Runtime: Node 20 (TypeScript).
- Database: PostgreSQL 18 (per-service schema `delivery`).
- Cache: Redis (per-service) for hot lookups (current courier's
  active deliveries).
- Event broker: Kafka.
- Map / geo: PostGIS via `courier-tracking-service` (read) and
  `eta-routing-service` (read).

## 8. Database Ownership

- Schema: `delivery`
- Migrations: `services/delivery-service/migrations/` — versioned,
  forward-only.
- Soft delete: no (deliveries are immutable once terminal).
- Partitioning: no (volume is bounded by orders).

## 9. API Overview

| Method | URI | Auth | Purpose |
|--------|-----|------|---------|
| POST | `/v1/deliveries` | bearer (service) | create a delivery (from dispatch) |
| GET | `/v1/deliveries/{id}` | bearer | read |
| POST | `/v1/deliveries/{id}/en_route_pickup` | bearer (courier) | courier moving to restaurant |
| POST | `/v1/deliveries/{id}/arrived_pickup` | bearer (courier) | courier at restaurant |
| POST | `/v1/deliveries/{id}/pickup` | bearer (courier) | courier has the order |
| POST | `/v1/deliveries/{id}/en_route_dropoff` | bearer (courier) | courier moving to customer |
| POST | `/v1/deliveries/{id}/complete` | bearer (courier) | delivered (with proof) |
| POST | `/v1/deliveries/{id}/failed` | bearer (courier) | cannot deliver (with reason) |
| POST | `/v1/deliveries/{id}/cancel` | bearer (service / admin) | compensate |
| POST | `/v1/deliveries/{id}/cash-collected` | bearer (courier) | COD collected |
| GET | `/v1/deliveries?courier_id=…&state=…` | bearer | list (with cursor pagination) |
| GET | `/v1/deliveries/{id}/audit` | bearer (admin) | full state history |

(Full contracts in `INTEGRATION.md`.)

## 10. Events Produced

| Event | Trigger | Consumers |
|-------|---------|-----------|
| `delivery.pickup.v1` | courier marks `picked_up` | `notification-service`, `customer-service` (history) |
| `delivery.in_transit.v1` | courier marks `en_route_dropoff` | `notification-service`, `customer-service` (history) |
| `delivery.arrived.v1` | courier arrives at dropoff | `notification-service` |
| `delivery.completed.v1` | courier marks `delivered` (with proof) | `food-payment-integration-service`, `courier-earnings-service`, `customer-service` (history), `notification-service`, `review-rating-service` |
| `delivery.failed.v1` | courier reports `failed` OR customer-unreachable timeout | `food-order-service`, `food-payment-integration-service` (refund), `notification-service` |
| `delivery.courier.cancelled.v1` | courier cancels pre-pickup (reassignment trigger) | `courier-dispatch-service` |
| `delivery.audit.state_changed.v1` | every state transition | `audit-service` |
| `cash.collected.v1` | COD collected | `food-payment-integration-service`, `ledger-service` (read) |

(Full contracts in `INTEGRATION.md`.)

## 11. Events Consumed

| Event | Producer | Reason | Handler |
|-------|----------|--------|---------|
| `delivery.courier.assigned.v1` | `courier-dispatch-service` | create the delivery aggregate | insert `delivery` row |
| `courier.location.updated.v1` | `courier-tracking-service` | update last-known position | update `last_known_*` columns |
| `food.order.cancelled.v1` | `food-order-service` | customer cancelled before pickup | set `state=cancelled` |
| `customer.suspended.v1` | `customer-service` | customer under review | set `at_risk=true` |
| `configuration.updated.v1` | `configuration-service` | reload thresholds (e.g. unreachable wait) | refresh in-memory |

(Full contracts in `INTEGRATION.md`.)

## 12. External Integrations

- Map provider via `eta-routing-service` and `geolocation-service`.
- File storage via `file-service` (proof-of-delivery photos).
- No direct third-party integrations.

## 13. Configuration

| Key | Type | Source | Notes |
|-----|------|--------|-------|
| `delivery.unreachable_wait_seconds` | int | `configuration-service` | default 300 (5 min) |
| `delivery.proof.required` | enum (`photo`/`signature`/`pin`/`any`) | `configuration-service` | default `any` |
| `delivery.courier_eta_ping_seconds` | int | `configuration-service` | how often to update ETA, default 30 |
| `delivery.feature.batched` | bool | `feature-flag-service` | default true |
| `delivery.cash_on_delivery_enabled` | bool | `configuration-service` | per-merchant override |

## 14. Security

- AuthN: JWT bearer (Keycloak `platform-courier` for couriers,
  `platform-services` for service-to-service, `platform-internal`
  for support).
- AuthZ: couriers may only act on their own deliveries
  (`delivery.courier_id == sub`); admins require `delivery.admin`.
- Secrets: file-service credentials via Vault.
- PII: customer name and phone are NOT stored here; only
  `customer_id` (UUID). Proof-of-delivery photos are stored
  encrypted by `file-service` and referenced by `file_id`.

## 15. Observability

- Logs: JSON to stdout with `correlation_id`, `delivery_id`,
  `order_id`, `courier_id`, `state`, `tenant_id`, `region`.
- Metrics: `delivery_state_transitions_total{from,to,result}`,
  `delivery_pickup_seconds`, `delivery_dropoff_seconds`,
  `delivery_failed_total{reason}`,
  `delivery_proof_type_total{type}`.
- Traces: OpenTelemetry; one root span per delivery; child spans
  per state transition.
- Health: `/health`, `/ready`, `/started`.

## 16. Scalability

- Replicas: 8 (default) — HPA on `kafka_consumer_lag` and
  `delivery_in_flight` gauge.
- Hot path: the courier mobile app pings every state transition;
  the service processes them in a small write. The hot path is
  HTTP latency from the courier's device, not DB load.

## 17. Local Development

- `docker compose up delivery-service` brings up the service,
  PostgreSQL, Kafka, and a synthetic courier device.
- Tests: `pnpm test` (unit), `pnpm test:e2e` (Kafka + Postgres).

## 18. Deployment

- Image: `registry.platform.io/delivery-service:{version}`.
- Replicas: 8 (per region).
- Resource limits: 1 vCPU / 1 GiB per replica.
- Migrations: `pnpm migrate:up` runs as a separate job.
- Rollout: rolling update.


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

- **Depends on**: [`admin-service`](../admin-service/README.md), [`audit-service`](../audit-service/README.md), [`configuration-service`](../configuration-service/README.md), [`courier-dispatch-service`](../courier-dispatch-service/README.md), [`courier-earnings-service`](../courier-earnings-service/README.md), [`courier-service`](../courier-service/README.md), [`courier-tracking-service`](../courier-tracking-service/README.md), [`customer-service`](../customer-service/README.md), [`dispatch-service`](../dispatch-service/README.md), [`eta-routing-service`](../eta-routing-service/README.md), [`feature-flag-service`](../feature-flag-service/README.md), [`file-service`](../file-service/README.md), [`food-order-service`](../food-order-service/README.md), [`food-payment-integration-service`](../food-payment-integration-service/README.md), [`geolocation-service`](../geolocation-service/README.md), [`ledger-service`](../ledger-service/README.md), [`notification-service`](../notification-service/README.md), [`payment-service`](../payment-service/README.md), [`review-rating-service`](../review-rating-service/README.md), [`support-service`](../support-service/README.md)
- **Depended on by**: [`address-service`](../address-service/README.md), [`api-gateway`](../api-gateway/README.md), [`courier-dispatch-service`](../courier-dispatch-service/README.md), [`courier-earnings-service`](../courier-earnings-service/README.md), [`courier-service`](../courier-service/README.md), [`courier-tracking-service`](../courier-tracking-service/README.md), [`eta-routing-service`](../eta-routing-service/README.md), [`food-order-service`](../food-order-service/README.md), [`food-payment-integration-service`](../food-payment-integration-service/README.md), [`geolocation-service`](../geolocation-service/README.md), [`loyalty-service`](../loyalty-service/README.md), [`notification-service`](../notification-service/README.md), [`restaurant-order-mgmt-service`](../restaurant-order-mgmt-service/README.md), [`review-rating-service`](../review-rating-service/README.md)

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
- [`../../workflows/FOOD_ORDER_WORKFLOWS.md`](../../workflows/FOOD_ORDER_WORKFLOWS.md) — end-to-end order/delivery flows
