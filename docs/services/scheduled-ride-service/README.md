# scheduled-ride-service

## 1. Purpose

`scheduled-ride-service` owns the **scheduled ride jobs**: future-
dated ride requests that the customer has booked in advance. At
the right time, the service materialises the scheduled job into a
live ride request so the customer doesn't need the app open.

## 2. Bounded Context

Bounded context: **Scheduled Ride Jobs**.

In scope:

- Creating, reading, updating (limited), and cancelling scheduled
  ride jobs.
- The scheduler that fires `scheduled_ride.due.v1` at the right
  time.
- Retry on materialisation failure.
- Customer notifications.

Out of scope (explicitly):

- The ride request aggregate — `ride-request-service`.
- Pricing — `pricing-service`.
- Dispatch — `dispatch-service`.

## 3. Responsibilities

- Accept a scheduled ride booking (pickup, dropoff, ride type,
  scheduled time).
- Validate the time window (e.g. 15 min to 30 days in the future).
- Schedule a job to fire `scheduled_ride.due.v1` at
  `scheduled_for - lead_time_minutes` (default 15 min).
- At fire time, hand off to `ride-request-service` via the event.
- Retry on materialisation failure (up to N times).
- Allow the customer to cancel a scheduled ride.
- Notify the customer on booking, on the day, and on failure.

## 4. Explicitly NOT Owned

- The ride request aggregate.
- Pricing.
- Dispatch.

## 5. Actors

| Actor | Type | Access |
|-------|------|--------|
| Customer app | system | create, read, cancel own scheduled rides |
| Scheduler (system) | system | fires `scheduled_ride.due.v1` |
| `ride-request-service` | consumer | materialises the live request |
| `pricing-service` | system | pre-quote at booking |
| `notification-service` | system | notify customer |
| `admin-service` | system | read, force-cancel with reason |

## 6. Dependencies

### Synchronous (REST)

- `customer-service` — validate customer — SLO 100ms — circuit
  breaker: yes.
- `pricing-service` — quote at booking — SLO 300ms — circuit
  breaker: yes.
- `zone-service` — validate pickup/dropoff — SLO 100ms — circuit
  breaker: yes.

### Asynchronous (events produced)

- `scheduled_ride.due.v1` — fires at the scheduled time.

### Asynchronous (events consumed)

- `customer.suspended.v1` from `customer-service` — auto-cancel —
  duplicate handling: inbox dedup.
- `configuration.updated.v1` from `configuration-service` — reload
  config.

## 7. Technology Assumptions

- Runtime: Node 20 (TypeScript).
- Database: PostgreSQL 18, per-service schema `scheduled_ride`.
- Cache: Redis (per-service) for the per-customer upcoming list.
- Event broker: Kafka.
- Scheduler: a row-per-job design with a periodic sweeper
  (every 30s) that picks due jobs. The sweeper uses
  `SELECT … FOR UPDATE SKIP LOCKED` for safe parallelism.

## 8. Database Ownership

- Schema: `scheduled_ride` (owned exclusively by this service).
- Migrations: `services/scheduled-ride-service/migrations/`.
- Soft delete: yes (cancelled is a state; soft delete is for
  admin-forced removals).
- Partitioning: no (volume is moderate).

## 9. API Overview

| Method | URI | Auth | Purpose |
|--------|-----|------|---------|
| POST | /v1/scheduled-rides | bearer (customer) | create a scheduled ride |
| GET | /v1/scheduled-rides/{id} | bearer (owner / admin) | read |
| GET | /v1/scheduled-rides | bearer (customer) | list the caller's upcoming |
| POST | /v1/scheduled-rides/{id}/cancellation | bearer (owner) | cancel |
| PATCH | /v1/scheduled-rides/{id} | bearer (owner) | limited update (notes, contact phone) |

Full contracts in `INTEGRATION.md`.

## 10. Events Produced

| Event | Trigger | Consumers |
|-------|---------|-----------|
| `scheduled_ride.due.v1` | when the scheduler fires | `ride-request-service` |
| `scheduled_ride.failed.v1` | on materialisation failure (after N retries) | `notification-service`, `support-service` |
| `scheduled_ride.cancelled.v1` | on customer / system cancel | `notification-service`, `audit-service` |

## 11. Events Consumed

| Event | Producer | Reason | Handler |
|-------|----------|--------|---------|
| `customer.suspended.v1` | `customer-service` | auto-cancel | mark `cancelled`; emit event |
| `configuration.updated.v1` | `configuration-service` | reload | cache invalidation |

## 12. External Integrations

- `pricing-service` (in-cluster) for quotes.
- No external map provider.

## 13. Configuration

| Key | Type | Source | Notes |
|-----|------|--------|-------|
| `scheduled_ride.min_lead_minutes` | int | configuration-service | default 15 |
| `scheduled_ride.max_lead_days` | int | configuration-service | default 30 |
| `scheduled_ride.scheduler.sweep_interval_seconds` | int | configuration-service | default 30 |
| `scheduled_ride.materialise.max_attempts` | int | configuration-service | default 3 |
| `scheduled_ride.materialise.retry_backoff_seconds` | int | configuration-service | default 60 |
| `scheduled_ride.cancellation.free_window_minutes` | int | configuration-service | default 60 |

## 14. Security

- AuthN: Bearer JWT.
- AuthZ: customer can read/cancel own scheduled rides; admin can
  read/force-cancel with reason.
- Secrets: Vault at `secret/scheduled_ride/{env}/*`.
- PII: pickup/dropoff, contact phone.

## 15. Observability

- Logs: JSON to stdout with `correlation_id`,
  `scheduled_ride_id`, `customer_id`, `route`, `latency_ms`,
  `status`.
- Metrics: `scheduled_rides_created_total{city, ride_type}`,
  `scheduled_rides_due_total{city}`,
  `scheduled_rides_failed_total{city, reason}`,
  `scheduled_rides_cancelled_total{city, actor}`,
  `scheduled_ride_lead_seconds` (histogram).
- Traces: OpenTelemetry, root span per request.
- Health: `/health`, `/ready` (DB + Kafka + Redis), `/started`.

## 16. Scalability

- Replicas: 4 (default); HPA on CPU.
- Hot path: the scheduler sweep; the index on `scheduled_for`
  with a partial index on `state='pending'` keeps the sweep
  fast.
- Read replicas: 1 read replica for the customer's "upcoming
  rides" list.

## 17. Local Development

```bash
docker compose up scheduled-ride-service postgres kafka redis
bun run --filter scheduled-ride-service dev
```

Seed data: a default customer and a default scheduled ride for
testing.

## 18. Deployment

- Image: `registry.uber.io/scheduled-ride-service:<sha>`.
- Replicas: 4 (HPA to 20).
- Resource limits: see `architecture/DEPLOYMENT_ARCHITECTURE.md`.
- Migrations: K8s Job before rolling deploy.
- The scheduler runs on all replicas; the
  `SELECT … FOR UPDATE SKIP LOCKED` ensures only one replica
  fires each job.

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

- **Depends on**: [`admin-service`](../admin-service/README.md), [`audit-service`](../audit-service/README.md), [`configuration-service`](../configuration-service/README.md), [`customer-service`](../customer-service/README.md), [`dispatch-service`](../dispatch-service/README.md), [`notification-service`](../notification-service/README.md), [`pricing-service`](../pricing-service/README.md), [`ride-request-service`](../ride-request-service/README.md), [`support-service`](../support-service/README.md), [`zone-service`](../zone-service/README.md)
- **Depended on by**: [`ride-request-service`](../ride-request-service/README.md)

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
