# ride-request-service

## 1. Purpose

`ride-request-service` owns the **ride booking aggregate**: the moment a
customer confirms a pickup and dropoff until either a driver is matched
or the request is cancelled or expires. It is the single point at which
a ride-hailing request becomes a priced, actionable, time-bound order
that downstream services (dispatch, trip, payment) can react to.

## 2. Bounded Context

Bounded context: **Ride Booking Aggregate**.

In scope:

- Accepting a customer's ride request (pickup, dropoff, ride type, payment
  method reference, optional schedule).
- Pricing the request through `pricing-service` and holding a quote for
  the request.
- Persisting the request aggregate in its state machine
  (`requested → matched → cancelled | expired`).
- Triggering `dispatch-service` to find a driver.
- Handling cancellations (customer-initiated) and the cancellation-fee
  policy.
- Coordinating with `scheduled-ride-service` for future-dated requests.
- Idempotency for retries from the customer app and edge retries.

Out of scope (explicitly):

- The actual `trip` lifecycle — owned by `trip-service`.
- Driver-side online state — owned by `driver-availability-service`.
- Driver locations — owned by `driver-location-service`.
- Matching algorithm details — owned by `dispatch-service` (we consume
  the result).
- Payment capture and driver earnings — owned by
  `ride-payment-integration-service`, `payment-service`, and
  `driver-earnings-service`.

## 3. Responsibilities

- Create, read, and update ride requests.
- Validate the request: customer active, pickup/dropoff in a served
  zone, ride type available, payment method usable.
- Hold a `PriceQuote` (with TTL) against the request so the dispatch
  decision and the trip settlement agree on the price.
- Emit `ride.request.created.v1`, `ride.request.matched.v1`,
  `ride.request.cancelled.v1`, and `ride.request.expired.v1` events.
- Apply the cancellation policy (free cancellation window, post-match
  fee, post-pickup no-cancel-by-customer) and orchestrate any fee
  capture with `payment-service`.
- Honor scheduled ride handoffs: when `scheduled_ride.due.v1` arrives,
  create the live `requested` request with the same parameters.

## 4. Explicitly NOT Owned

- The `trip` aggregate (states `assigned`, `en_route_pickup`, etc.) —
  `trip-service`.
- The driver's `busy`/`available` state — `driver-availability-service`.
- Geocoding, ETA, or routing — `geolocation-service`, `eta-routing-service`.
- Pricing rule evaluation — `pricing-service` (we consume a quote).
- The actual driver offer/accept flow — `dispatch-service`.

## 5. Actors

| Actor | Type | Access |
|-------|------|--------|
| Customer (mobile) | human | read/write own requests |
| Driver app | system | none (driver view is via `trip-service` after match) |
| `dispatch-service` | system | read (consumes `ride.request.created.v1`) |
| `pricing-service` | system | read quote |
| `scheduled-ride-service` | system | writes via `scheduled_ride.due.v1` |
| `admin-service` | system | read/cancel any request with reason and audit |

## 6. Dependencies

### Synchronous (REST)

- `customer-service` — validate the customer is active, not suspended,
  and fetch default payment-method ref — SLO 200ms — circuit breaker: yes.
- `pricing-service` — produce a `PriceQuote` — SLO 300ms — circuit breaker:
  yes.
- `dispatch-service` — request a driver match (synchronous, with a short
  timeout; the result lands back as a `dispatch.matched.v1` event).
- `driver-availability-service` — pre-check zone coverage for the
  pickup (cheap read) — SLO 100ms — circuit breaker: yes.
- `zone-service` — verify the pickup/dropoff is in a served zone — SLO
  100ms — circuit breaker: yes.
- `payment-service` — pre-authorize the customer's payment method
  (optional, low-amount hold) — SLO 500ms — circuit breaker: yes.

### Asynchronous (events consumed)

- `customer.created.v1` from `customer-service` — to warm a small
  in-memory cache of customer segments — duplicate handling: inbox
  dedup.
- `customer.suspended.v1` from `customer-service` — auto-cancel any
  open requests for that customer and emit cancellation events —
  duplicate handling: inbox dedup.
- `dispatch.matched.v1` from `dispatch-service` — advance state from
  `requested` to `matched`, record `driver_id` and `trip_id` —
  duplicate handling: inbox dedup, idempotent by `ride_request_id`.
- `dispatch.no_driver.v1` from `dispatch-service` — if the match
  window expired, transition to `expired` and notify the customer.
- `dispatch.offer.expired.v1` from `dispatch-service` — informational;
  re-arms the request for the next dispatch attempt.
- `scheduled_ride.due.v1` from `scheduled-ride-service` — convert the
  scheduled job into a live `requested` ride.

## 7. Technology Assumptions

- Runtime: Node 20 (TypeScript).
- Database: PostgreSQL 18, per-service schema `ride_request`.
- Cache: Redis (per-service, for hot reads of the customer's recent
  requests and for the in-memory fleet status cache).
- Event broker: Kafka.
- HTTP server: Fastify.
- ORM/query: Drizzle ORM with raw SQL fallback for hot paths.
- Migrations: `dbmate` (versioned, forward-only).
- Idempotency store: `ride_request.idempotency` table (see ERD).

## 8. Database Ownership

- Schema: `ride_request` (one schema, owned exclusively by this
  service).
- Migrations: `services/ride-request-service/migrations/`.
- Soft delete: **no** for active ride requests (cancel is a real
  state); yes for archived snapshot rows beyond retention.
- Partitioning: no (volume is moderate; primary indexes suffice).

## 9. API Overview

| Method | URI | Auth | Purpose |
|--------|-----|------|---------|
| POST | /v1/rides | bearer (customer) | create a ride request |
| GET | /v1/rides/{id} | bearer (owner or admin) | read a request |
| GET | /v1/rides | bearer (customer) | list the caller's recent requests |
| POST | /v1/rides/{id}/cancellation | bearer (owner) | customer cancels a request |
| POST | /v1/rides/{id}/rebook | bearer (owner) | rebook after no-driver |
| GET | /v1/rides/{id}/quote | bearer (owner) | refresh quote before match |
| GET | /v1/rides/{id}/state | bearer (owner or system) | state machine view |

Full contracts in `INTEGRATION.md`.

## 10. Events Produced

| Event | Trigger | Consumers |
|-------|---------|-----------|
| `ride.request.created.v1` | on successful create | `dispatch-service`, `pricing-service` (telemetry), `audit-service` |
| `ride.request.matched.v1` | on receipt of `dispatch.matched.v1` | `trip-service` (reads via REST + event), `notification-service`, `audit-service` |
| `ride.request.cancelled.v1` | customer or system cancels | `notification-service`, `audit-service`, `pricing-service` (fee calc) |
| `ride.request.expired.v1` | no driver found in T | `dispatch-service` (next attempt), `notification-service`, `audit-service` |

## 11. Events Consumed

| Event | Producer | Reason | Handler |
|-------|----------|--------|---------|
| `dispatch.matched.v1` | `dispatch-service` | advance state | mark `matched`, set `driver_id`/`trip_id` |
| `dispatch.no_driver.v1` | `dispatch-service` | abandon | mark `expired`, notify customer |
| `dispatch.offer.expired.v1` | `dispatch-service` | re-attempt | re-arm dispatch with same quote |
| `scheduled_ride.due.v1` | `scheduled-ride-service` | materialise request | create live request from the job |
| `customer.suspended.v1` | `customer-service` | safety | cancel any open requests for that customer |
| `customer.created.v1` | `customer-service` | warm cache | record customer segment in cache |

## 12. External Integrations

- `pricing-service` (in-cluster) — for quotes.
- `dispatch-service` (in-cluster) — for matching.
- Map provider (HERE / Google Maps) — only via `geolocation-service` and
  `eta-routing-service`; we do not call the provider directly.

## 13. Configuration

| Key | Type | Source | Notes |
|-----|------|--------|-------|
| `ride_request.match_timeout_seconds` | int | configuration-service | default 90s |
| `ride_request.cancellation.free_window_seconds` | int | configuration-service | default 60s |
| `ride_request.cancellation.fee_minor` | money | configuration-service | default per-currency value |
| `ride_request.cancellation.fee_pickup_minor` | money | configuration-service | higher fee if at pickup |
| `ride_request.quote_ttl_seconds` | int | configuration-service | default 120s |
| `ride_request.dispatch.max_attempts` | int | configuration-service | default 5 |
| `ride_request.dispatch.between_attempts_ms` | int | configuration-service | default 4000 |
| `ride_request.allowed_ride_types` | string[] | configuration-service | per city |

## 14. Security

- AuthN: Bearer JWT, validated at the gateway and re-validated when
  the service is called out-of-band.
- AuthZ: Customer owns their own requests (`ride_request.customer_id ==
  sub`); admin override requires `admin` role + `X-Audit-Reason`.
- Secrets: Vault at `secret/ride_request/{env}/*`. No secrets in
  source.
- PII: pickup/dropoff coordinates and addresses. Stored as JSONB with
  the request; encrypted at rest by the disk-level KMS. Coordinates
  beyond 24h are masked in `ride_history-service`'s read model.

## 15. Observability

- Logs: structured JSON to stdout with `correlation_id`, `ride_request_id`,
  `customer_id`, `city_id`, `route`, `latency_ms`, `status`.
- Metrics: `ride_request_created_total{city,ride_type}`,
  `ride_request_match_seconds` (histogram, label `city`),
  `ride_request_state_transitions_total{from,to}`,
  `ride_request_cancellations_total{actor,reason}`,
  `ride_request_expirations_total{city}`, `ride_request_dispatch_attempts_total`.
- Traces: OpenTelemetry, root span per request, propagated through the
  outbound calls to `pricing-service`/`dispatch-service` and the
  emitted events.
- Health: `/health`, `/ready` (DB + Kafka + Redis checks),
  `/started` (after warm caches).

## 16. Scalability

- Replicas: 6 (default), HPA on CPU > 60% and on
  `ride_request_match_seconds_p99` > 400ms.
- Hot path: the create-request POST (price quote + persist + emit).
  Cache the customer's segment and the zone lookup. Pre-fetch the
  customer's recent ride-type preference.
- Read replicas: optional for the customer's "my recent rides" query.

## 17. Local Development

```bash
# from repo root
docker compose up ride-request-service postgres kafka
bun run --filter ride-request-service dev
# seed an active customer
curl -X POST localhost:8080/v1/admin/seed -H 'X-Admin-Role: admin'
```

Seed data: a default `pricing-service` quote, a test customer, and one
fake `dispatch-service` returning `matched` for `ride_type=economy` in
the test zone.

## 18. Deployment

- Image: `registry.uber.io/ride-request-service:<sha>`.
- Replicas: 6 (HPA to 30).
- Resource limits: see `architecture/DEPLOYMENT_ARCHITECTURE.md`.
- Migrations: run as a Kubernetes Job before the rolling deploy.
- Rollout: standard rolling; no special order required.


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

- **Depends on**: [`admin-service`](../admin-service/README.md), [`audit-service`](../audit-service/README.md), [`configuration-service`](../configuration-service/README.md), [`customer-service`](../customer-service/README.md), [`dispatch-service`](../dispatch-service/README.md), [`driver-availability-service`](../driver-availability-service/README.md), [`driver-earnings-service`](../driver-earnings-service/README.md), [`driver-location-service`](../driver-location-service/README.md), [`eta-routing-service`](../eta-routing-service/README.md), [`geolocation-service`](../geolocation-service/README.md), [`notification-service`](../notification-service/README.md), [`payment-service`](../payment-service/README.md), [`pricing-service`](../pricing-service/README.md), [`ride-payment-integration-service`](../ride-payment-integration-service/README.md), [`scheduled-ride-service`](../scheduled-ride-service/README.md), [`trip-service`](../trip-service/README.md), [`zone-service`](../zone-service/README.md)
- **Depended on by**: [`api-gateway`](../api-gateway/README.md), [`customer-service`](../customer-service/README.md), [`dispatch-service`](../dispatch-service/README.md), [`driver-availability-service`](../driver-availability-service/README.md), [`driver-incentive-service`](../driver-incentive-service/README.md), [`driver-service`](../driver-service/README.md), [`eta-routing-service`](../eta-routing-service/README.md), [`fraud-risk-service`](../fraud-risk-service/README.md), [`geolocation-service`](../geolocation-service/README.md), [`pricing-service`](../pricing-service/README.md), [`ride-history-service`](../ride-history-service/README.md), [`scheduled-ride-service`](../scheduled-ride-service/README.md), [`trip-service`](../trip-service/README.md), [`zone-service`](../zone-service/README.md)

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

- [`../../workflows/RIDE_WORKFLOWS.md`](../../workflows/RIDE_WORKFLOWS.md) — end-to-end ride flows
