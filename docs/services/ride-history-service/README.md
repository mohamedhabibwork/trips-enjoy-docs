# ride-history-service

## 1. Purpose

`ride-history-service` owns the **read model** that customers,
drivers, and admins see as "ride history." It is a denormalised
view of trips, payments, and reviews that is optimised for fast
reads. It is derived from upstream events and refreshed
asynchronously.

## 2. Bounded Context

Bounded context: **Ride History Read Model**.

In scope:

- The denormalised "ride history entry" (per trip).
- The customer's "my trips" list.
- The driver's "my trips" list.
- The admin's "all trips" list.
- Pagination and filtering.
- Read-side caching.
- Retention.

Out of scope (explicitly):

- The trip aggregate — `trip-service`.
- The ride request aggregate — `ride-request-service`.
- Payment capture — `payment-service`.
- Reviews — `review-rating-service` (we only denormalise the
  rating).

## 3. Responsibilities

- Consume `trip.completed.v1`, `ride.payment.completed.v1`, and
  `review.submitted.v1` and project them into the read model.
- Serve `GET /v1/history/trips` (customer), `GET
  /v1/drivers/{id}/trips` (driver), and admin endpoints.
- Filter by date, status, and ride type.
- Paginate cursor-based.
- Cache hot reads in Redis.
- Apply retention (7 years).

## 4. Explicitly NOT Owned

- The trip aggregate.
- The ride request aggregate.
- Payment capture.
- Reviews.

## 5. Actors

| Actor | Type | Access |
|-------|------|--------|
| Customer app | system | read own trips |
| Driver app | system | read own trips |
| `admin-service` | system | read all trips |
| `trip-service` | system | emits `trip.completed.v1` |
| `ride-payment-integration-service` | system | emits `ride.payment.completed.v1` |
| `review-rating-service` | system | emits `review.submitted.v1` |

## 6. Dependencies

### Synchronous (REST)

- `customer-service` — read customer name — SLO 100ms — circuit
  breaker: yes (cached).
- `driver-service` — read driver name — SLO 100ms — circuit
  breaker: yes (cached).
- `trip-service` — read trip details (rare; events are the main
  path) — SLO 100ms — circuit breaker: yes.

### Asynchronous (events consumed)

- `trip.completed.v1` from `trip-service` — project the trip —
  duplicate handling: inbox dedup.
- `ride.payment.completed.v1` from `ride-payment-integration-service`
  — add the fare — duplicate handling: inbox dedup.
- `review.submitted.v1` from `review-rating-service` — add the
  rating — duplicate handling: inbox dedup.
- `configuration.updated.v1` from `configuration-service` —
  reload config.

## 7. Technology Assumptions

- Runtime: Node 20 (TypeScript).
- Database: PostgreSQL 18, per-service schema `ride_history`.
- Cache: Redis (per-service) for the hot per-customer / per-driver
  reads.
- Event broker: Kafka.
- Read replicas: 2 in the same region for the high-traffic reads.

## 8. Database Ownership

- Schema: `ride_history` (owned exclusively by this service).
- Migrations: `services/ride-history-service/migrations/`.
- Soft delete: no (the entry is the read model; if the trip is
  deleted upstream, we delete here too).
- Partitioning: yes — `ride_history.entries` is
  range-partitioned by `trip_completed_at` (year).

## 9. API Overview

| Method | URI | Auth | Purpose |
|--------|-----|------|---------|
| GET | /v1/history/trips | bearer (customer) | the caller's trips |
| GET | /v1/history/trips/{id} | bearer (owner / driver / admin) | one trip |
| GET | /v1/drivers/{driver_id}/trips | bearer (driver / admin) | the driver's trips |
| GET | /v1/admin/trips | bearer (admin) | all trips (admin) |

Full contracts in `INTEGRATION.md`.

## 10. Events Produced

None (read-only service).

## 11. Events Consumed

| Event | Producer | Reason | Handler |
|-------|----------|--------|---------|
| `trip.completed.v1` | `trip-service` | project the trip | upsert entry |
| `ride.payment.completed.v1` | `ride-payment-integration-service` | add the fare | update entry |
| `review.submitted.v1` | `review-rating-service` | add the rating | update entry |
| `configuration.updated.v1` | `configuration-service` | reload | cache invalidation |

## 12. External Integrations

None (in-cluster only).

## 13. Configuration

| Key | Type | Source | Notes |
|-----|------|--------|-------|
| `ride_history.cache.customer_ttl_seconds` | int | configuration-service | default 60 |
| `ride_history.cache.driver_ttl_seconds` | int | configuration-service | default 60 |
| `ride_history.retention.years` | int | configuration-service | default 7 |
| `ride_history.projection.batch_size` | int | configuration-service | default 500 |

## 14. Security

- AuthN: Bearer JWT.
- AuthZ: customer can read own; driver can read own; admin can
  read all.
- Secrets: Vault at `secret/ride_history/{env}/*`.
- PII: pickup / dropoff (read model); encrypted at rest.

## 15. Observability

- Logs: JSON to stdout with `correlation_id`, `customer_id`,
  `route`, `latency_ms`, `status`.
- Metrics: `ride_history_projection_lag_seconds` (histogram),
  `ride_history_entries_total`,
  `ride_history_read_seconds` (histogram),
  `ride_history_cache_hit_ratio`.
- Traces: OpenTelemetry, root span per request.
- Health: `/health`, `/ready` (DB + Kafka + Redis), `/started`.

## 16. Scalability

- Replicas: 4 (default); HPA on CPU and on
  `ride_history_read_seconds_p99`.
- Hot path: the customer's "my trips" list. Cached in Redis for
  60s per customer.
- Read replicas: 2 for the high-traffic reads.

## 17. Local Development

```bash
docker compose up ride-history-service postgres kafka redis
bun run --filter ride-history-service dev
```

Seed data: a default customer, a default driver, a default
completed trip.

## 18. Deployment

- Image: `registry.uber.io/ride-history-service:<sha>`.
- Replicas: 4 (HPA to 20).
- Resource limits: see `architecture/DEPLOYMENT_ARCHITECTURE.md`.
- Migrations: K8s Job before rolling deploy.
- Partition maintenance: yearly.

## 19. Cross-Service Coordination Notes

This service participates in the platform's
cross-service choreography. The following notes summarize
how it fits with the broader event-driven architecture
(see `architecture/EVENT_ARCHITECTURE.md`):

- **Idempotency**: every non-idempotent write is
  protected by an `Idempotency-Key` header and the
  platform-standard idempotency store. A retried
  request with the same key and body returns the
  stored response.
- **Outbox**: every state change that needs to be
  published to Kafka is written to the local outbox
  table in the same database transaction as the
  state change. A separate poller publishes to Kafka
  with `acks=all` and retries on failure. Outbox rows
  are purged 24 h after a successful publish.
- **Inbox**: every consumed event is recorded in the
  local inbox table keyed by `event_id` with a 24 h
  TTL, so re-deliveries are de-duplicated.
- **Cross-service references**: every cross-service
  reference (e.g. `identity_id`, `customer_id`,
  `driver_id`, `courier_id`, `vehicle_id`,
  `address_id`, `payment_method_id`) is stored as a
  UUID column WITHOUT database FK. The owning
  service is the source of truth; this service
  validates the reference exists and is current
  before persisting.
- **Distributed tracing**: OpenTelemetry
  `traceparent` is propagated to every downstream
  call. The platform's `correlation_id` is enriched on
  every span and emitted in every event's envelope.
- **Graceful degradation**: when a non-critical
  dependency is unavailable, the service degrades
  to a safe fallback (e.g. cached read, degraded
  write). The fallback is documented in the
  relevant workflow's `WORKFLOWS.md`.


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

- **Depends on**: [`admin-service`](../admin-service/README.md), [`configuration-service`](../configuration-service/README.md), [`customer-service`](../customer-service/README.md), [`driver-service`](../driver-service/README.md), [`payment-service`](../payment-service/README.md), [`review-rating-service`](../review-rating-service/README.md), [`ride-payment-integration-service`](../ride-payment-integration-service/README.md), [`ride-request-service`](../ride-request-service/README.md), [`trip-service`](../trip-service/README.md)
- **Depended on by**: [`customer-service`](../customer-service/README.md), [`driver-earnings-service`](../driver-earnings-service/README.md), [`reporting-service`](../reporting-service/README.md), [`ride-payment-integration-service`](../ride-payment-integration-service/README.md), [`trip-service`](../trip-service/README.md)

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
