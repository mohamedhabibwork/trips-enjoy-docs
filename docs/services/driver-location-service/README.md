# driver-location-service

## 1. Purpose

`driver-location-service` owns the **high-frequency driver location
stream**: where each online driver is right now, and a short recent
trail of past points. It is the spatial truth that dispatch, ETA,
and safety all rely on. It is optimised for write throughput and
freshness; the platform's default schema (one row per driver, one
table) is wrong for this volume, so this service uses a partitioned
hot-path + a `current_location` UPSERT pattern.

## 2. Bounded Context

Bounded context: **Driver Location Stream**.

In scope:

- Accepting GPS points from the driver app at up to 5 Hz per driver.
- Maintaining the **last known location** per driver
  (`current_location` table, keyed by `driver_id`).
- Maintaining a **recent trail** of points per driver
  (`locations` table, partitioned by day).
- Publishing a curated `driver.location.updated.v1` event stream at
  1 Hz per driver (so consumers don't drown).
- Exposing a REST read for "where is driver X right now?" and
  "where were they in the last N minutes?".

Out of scope (explicitly):

- Whether the driver is online — `driver-availability-service`.
- Driver profile — `driver-service`.
- Trip tracking as a first-class concern — `trip-service` (it
  consumes the curated stream).

## 3. Responsibilities

- Accept `POST /v1/location` from the driver app with `{lat, lon,
  bearing, speed_mps, accuracy_m, recorded_at}`.
- UPSERT into `current_location`.
- INSERT into `locations` (the partitioned table).
- Throttle at the producer side to 1 Hz when emitting
  `driver.location.updated.v1` to the curated topic.
- Provide a read API for the latest position and the recent trail.
- Reject updates for offline drivers (the driver app should not be
  sending points; we tolerate them but log a warning).

## 4. Explicitly NOT Owned

- Driver online state — `driver-availability-service`.
- Trip state — `trip-service`.
- Geocoding or ETA — `geolocation-service`, `eta-routing-service`.
- Map provider integration — `eta-routing-service`.

## 5. Actors

| Actor | Type | Access |
|-------|------|--------|
| Driver app | system | write only (own location) |
| `dispatch-service` | system | read (last known + trail) |
| `ride-safety-service` | system | read (live location) |
| `eta-routing-service` | system | read (curated stream) |
| `trip-service` | system | read (curated stream, trip tracking) |
| `reporting-service` | system | read (aggregated hourly cells) |
| `admin-service` | system | read (with reason) |

## 6. Dependencies

### Synchronous (REST)

None. The service is intentionally a sink; it does not call
downstream services on the write path.

### Asynchronous (events consumed)

- `driver.availability.online.v1` from `driver-availability-service` —
  start accepting points for the driver — duplicate handling: inbox
  dedup.
- `driver.availability.offline.v1` from `driver-availability-service`
  — stop accepting points (mark `offline` in a small cache; the
  driver app should stop sending) — duplicate handling: inbox
  dedup.

## 7. Technology Assumptions

- Runtime: Node 20 (TypeScript).
- Database: PostgreSQL 18, per-service schema `driver_location`.
- Cache: Redis (per-service) for the hot `current_location` UPSERT
  path (write-through; durable answer is in PG).
- Event broker: Kafka. The curated stream is on
  `driver.location.updated` topic, partitioned by `driver_id`.
- HTTP server: Fastify with a custom binary protocol alternative
  (HTTP/2 + Protobuf) for the highest-frequency write path.

## 8. Database Ownership

- Schema: `driver_location` (owned exclusively by this service).
- Migrations: `services/driver-location-service/migrations/`.
- Soft delete: no.
- Partitioning: yes — `driver_location.locations` is range-partitioned
  by day. `driver_location.current_location` is not partitioned (one
  row per driver).

## 9. API Overview

| Method | URI | Auth | Purpose |
|--------|-----|------|---------|
| POST | /v1/location | bearer (driver) | push a GPS point |
| GET | /v1/location/{driver_id}/current | bearer (system / safety / admin) | last known |
| GET | /v1/location/{driver_id}/trail | bearer (system / safety / admin) | recent points |
| POST | /v1/location/batch | bearer (driver) | batch upload on reconnect |
| GET | /v1/location/zone/{zone_id}/current | system | drivers in a zone, last known |

Full contracts in `INTEGRATION.md`.

## 10. Events Produced

| Event | Trigger | Consumers |
|-------|---------|-----------|
| `driver.location.updated.v1` | every accepted point (1Hz curated) | `dispatch-service`, `ride-safety-service`, `eta-routing-service`, `trip-service` (curated) |

## 11. Events Consumed

| Event | Producer | Reason | Handler |
|-------|----------|--------|---------|
| `driver.availability.online.v1` | `driver-availability-service` | start accepting | mark driver as `online` in our cache |
| `driver.availability.offline.v1` | `driver-availability-service` | stop accepting | mark driver as `offline`; tolerate late points |

## 12. External Integrations

- PostgreSQL 18 with PostGIS for the `current_location.geog` column
  and the per-zone query (`ST_DWithin`).
- Kafka for the curated event stream.

## 13. Configuration

| Key | Type | Source | Notes |
|-----|------|--------|-------|
| `driver_location.curated_stream_hz` | float | configuration-service | default 1.0 |
| `driver_location.ingest.max_hz_per_driver` | int | configuration-service | default 5 |
| `driver_location.trail.retention_seconds` | int | configuration-service | default 7200 (2h) |
| `driver_location.partition.retention_hours` | int | configuration-service | default 48 |
| `driver_location.zone.radius_meters` | int | configuration-service | default 5000 |

## 14. Security

- AuthN: Bearer JWT for both the driver write path and the system
  read path.
- AuthZ: drivers can write only their own location
  (`driver_id == sub`); system / safety / admin can read.
- Secrets: Vault at `secret/driver_location/{env}/*`.
- PII: precise GPS is sensitive. Stored encrypted at rest (disk-level
  KMS). Beyond the trail retention window, the trail is dropped; the
  aggregated hourly cell in `reporting-service` is the long-term
  record.

## 15. Observability

- Logs: JSON to stdout with `correlation_id`, `driver_id`, `latency_ms`,
  `status`. **Do not log lat/lon.**
- Metrics: `driver_location_points_total`,
  `driver_location_points_dropped_total{reason}`,
  `driver_location_current_set_total`,
  `driver_location_curated_emitted_total`,
  `driver_location_ingest_seconds` (histogram),
  `driver_location_current_set_seconds` (histogram).
- Traces: OpenTelemetry, root span per request; sampled at 1% for
  writes to keep the trace volume manageable.
- Health: `/health`, `/ready` (DB + Kafka + Redis), `/started`.

## 16. Scalability

- Replicas: 12 (default); HPA on
  `driver_location_points_total` and on CPU.
- Hot path: `POST /v1/location` (write). The service is designed to
  ingest 1M points/s sustained per region.
- The `current_location` table is hot but small (one row per
  driver); UPSERT is on PK (`driver_id`).
- The `locations` table is range-partitioned by day; partitions are
  pre-created and dropped after the retention window.

## 17. Local Development

```bash
docker compose up driver-location-service postgres kafka redis
bun run --filter driver-location-service dev
```

Seed data: a fake `driver.availability.online.v1` for a test
driver.

## 18. Deployment

- Image: `registry.uber.io/driver-location-service:<sha>`.
- Replicas: 12 (HPA to 60).
- Resource limits: see `architecture/DEPLOYMENT_ARCHITECTURE.md`.
- Migrations: K8s Job before rolling deploy.
- Partition maintenance: nightly K8s CronJob.

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

- **Depends on**: [`admin-service`](../admin-service/README.md), [`configuration-service`](../configuration-service/README.md), [`dispatch-service`](../dispatch-service/README.md), [`driver-availability-service`](../driver-availability-service/README.md), [`driver-service`](../driver-service/README.md), [`eta-routing-service`](../eta-routing-service/README.md), [`geolocation-service`](../geolocation-service/README.md), [`reporting-service`](../reporting-service/README.md), [`ride-safety-service`](../ride-safety-service/README.md), [`trip-service`](../trip-service/README.md)
- **Depended on by**: [`dispatch-service`](../dispatch-service/README.md), [`driver-availability-service`](../driver-availability-service/README.md), [`driver-service`](../driver-service/README.md), [`eta-routing-service`](../eta-routing-service/README.md), [`geolocation-service`](../geolocation-service/README.md), [`ride-request-service`](../ride-request-service/README.md), [`ride-safety-service`](../ride-safety-service/README.md), [`trip-service`](../trip-service/README.md), [`vehicle-service`](../vehicle-service/README.md), [`zone-service`](../zone-service/README.md)

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

- [`../../workflows/DRIVER_WORKFLOWS.md`](../../workflows/DRIVER_WORKFLOWS.md) — onboarding, shifts, earnings
