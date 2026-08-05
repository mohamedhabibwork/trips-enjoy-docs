# courier-tracking-service

## 1. Purpose

`courier-tracking-service` is the **high-frequency location stream**
for couriers. It receives a position update from every online
courier roughly every second, persists the current point, emits a
curated stream for downstream consumers (dispatch, delivery, safety),
and provides a low-latency "where is this courier right now" query
for the matching algorithm.

## 2. Bounded Context

Bounded context: **High-Frequency Courier Location**.

- **In scope**: ingestion of location pings, persistence of the
  current location and a recent trail, the curated outbound stream
  (`courier.location.updated.v1`), per-courier "last known" reads.
- **Out of scope**: courier profile / KYC (owned by
  `courier-service`), the actual delivery state (owned by
  `delivery-service`), dispatch (owned by
  `courier-dispatch-service`).

## 3. Responsibilities

- Ingest location pings at up to 5 Hz per courier (target 1 Hz).
- Persist the **current location** in a hot table (UPSERT by
  `courier_id`).
- Persist a **trail** of recent pings (range-partitioned by day) for
  replay and analytics.
- Emit `courier.location.updated.v1` at a curated rate (default
  1 Hz per courier) for downstream consumers.
- Serve `GET /v1/couriers/{id}/location` for synchronous reads
  (dispatch, delivery ETA, safety).
- Detect a "stale" courier (no ping in 60s) and mark them
  accordingly; the curated stream is suppressed for stale couriers
  unless the read endpoint is called.

## 4. Explicitly NOT Owned

- Courier profile / KYC / vehicle — owned by `courier-service`.
- Courier availability / online state — owned by `courier-service`
  (with `courier-dispatch-service` reflecting busy).
- Delivery state — owned by `delivery-service`.
- Dispatch / matching — owned by `courier-dispatch-service`.

## 5. Actors

| Actor | Type | Access |
|-------|------|--------|
| Courier | human (mobile) | pings location (write) |
| `courier-service` | system | produces availability events (read) |
| `courier-dispatch-service` | system | reads current location (read) |
| `delivery-service` | system | reads last-known for ETA (read) |
| `eta-routing-service` | system | reads location (read) |
| `ride-safety-service` | system | reads location (read) |
| `admin-service` | system | read-only operational view |

## 6. Dependencies

### Synchronous (REST)

- `courier-service` — `GET /v1/couriers/{id}` to enrich (vehicle
  type, KYC) when emitting the curated stream — circuit breaker:
  yes, SLO 50ms p99.

### Asynchronous (events consumed)

- `courier.availability.online.v1` from `courier-service` — start
  accepting pings — dedup: inbox.
- `courier.availability.offline.v1` from `courier-service` — stop
  persisting / stop emitting — dedup: inbox.
- `configuration.updated.v1` from `configuration-service` — reload
  curated-stream rate and stale threshold — dedup: inbox.

## 7. Technology Assumptions

- Runtime: Go 1.22 (high-throughput ingestion benefits from a
  compiled runtime).
- Database: PostgreSQL 18 (per-service schema `courier_tracking`).
  PostGIS extension enabled for the location columns.
- Cache: Redis (per-service) for the last-known location with a
  short TTL (5 min) for synchronous reads.
- Event broker: Kafka.

## 8. Database Ownership

- Schema: `courier_tracking`
- Migrations: `services/courier-tracking-service/migrations/` —
  versioned, forward-only.
- Soft delete: no (trail rows are immutable; current location is
  UPSERT).
- Partitioning: yes — `courier_tracking.locations` is
  range-partitioned by day. `courier_tracking.current_locations`
  is NOT partitioned (one row per courier).

## 9. API Overview

| Method | URI | Auth | Purpose |
|--------|-----|------|---------|
| POST | `/v1/couriers/{id}/location` | bearer (courier) | ingest a ping |
| GET | `/v1/couriers/{id}/location` | bearer (service) | read last-known |
| GET | `/v1/couriers/{id}/trail?from=…&to=…` | bearer (service / admin) | recent trail |
| GET | `/v1/locations/recent?city_id=…&bbox=…` | bearer (service) | nearby couriers |
| GET | `/v1/health/metrics` | bearer (admin) | operational counters |

(Full contracts in `INTEGRATION.md`.)

## 10. Events Produced

| Event | Trigger | Consumers |
|-------|---------|-----------|
| `courier.location.updated.v1` | curated stream (default 1 Hz per courier) | `courier-dispatch-service`, `delivery-service`, `eta-routing-service` (read), `ride-safety-service` (read) |
| `courier_tracking.audit.location_ingested.v1` | every ping (sample 1 in 1000) | `audit-service` |

(Full contracts in `INTEGRATION.md`.)

## 11. Events Consumed

| Event | Producer | Reason | Handler |
|-------|----------|--------|---------|
| `courier.availability.online.v1` | `courier-service` | start accepting pings | mark courier as live; init current row |
| `courier.availability.offline.v1` | `courier-service` | stop | mark courier as offline; stop emitting curated events |
| `configuration.updated.v1` | `configuration-service` | reload thresholds | refresh in-memory config |

## 12. External Integrations

None.

## 13. Configuration

| Key | Type | Source | Notes |
|-----|------|--------|-------|
| `courier_tracking.curated_rate_hz` | int | `configuration-service` | default 1; max 5 |
| `courier_tracking.stale_threshold_seconds` | int | `configuration-service` | default 60 |
| `courier_tracking.max_ping_hz` | int | `configuration-service` | default 5; throttle beyond |
| `courier_tracking.trail_retention_days` | int | `configuration-service` | default 30; partitions older dropped |

## 14. Security

- AuthN: JWT bearer (Keycloak `platform-courier` for couriers,
  `platform-services` for service-to-service).
- AuthZ: couriers may only ping their own location
  (`courier_id == sub`).
- Secrets: none direct.
- PII: the location itself is potentially PII (precise GPS trail).
  Retention is short (30 days), and the column is treated as
  "sensitive" per
  [`SECURITY_ARCHITECTURE.md`](../../architecture/SECURITY_ARCHITECTURE.md).

## 15. Observability

- Logs: JSON to stdout with `correlation_id`, `courier_id`, `city_id`,
  `tenant_id`.
- Metrics: `courier_location_ingested_total{city_id,source}`,
  `courier_location_curated_emitted_total{city_id}`,
  `courier_location_p99_latency_ms`,
  `courier_location_stale_count{city_id}`,
  `courier_location_pool_size{city_id}`.
- Traces: OpenTelemetry; one span per ingest; sampled 1/1000 in
  production.
- Health: `/health`, `/ready`, `/started`.

## 16. Scalability

- Replicas: 12 (default) — HPA on `kafka_consumer_lag` and
  `courier_location_ingested_total` rate.
- Hot path: ingest API. Mitigations: stateless ingest workers
  write to a partition by `courier_id`; the trail insert is
  asynchronous (batched); the current-location UPSERT is the only
  synchronous DB write.

## 17. Local Development

- `docker compose up courier-tracking-service` brings up the
  service, PostgreSQL with PostGIS, Kafka, and a synthetic ping
  generator.
- Tests: `go test ./...`, `go test -tags=e2e`.

## 18. Deployment

- Image: `registry.platform.io/courier-tracking-service:{version}`.
- Replicas: 12 (per region).
- Resource limits: 2 vCPU / 2 GiB per replica.
- Migrations: separate job.
- Rollout: rolling update.

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

- **Depends on**: [`admin-service`](../admin-service/README.md), [`audit-service`](../audit-service/README.md), [`configuration-service`](../configuration-service/README.md), [`courier-dispatch-service`](../courier-dispatch-service/README.md), [`courier-service`](../courier-service/README.md), [`delivery-service`](../delivery-service/README.md), [`dispatch-service`](../dispatch-service/README.md), [`eta-routing-service`](../eta-routing-service/README.md), [`ride-safety-service`](../ride-safety-service/README.md)
- **Depended on by**: [`courier-dispatch-service`](../courier-dispatch-service/README.md), [`courier-service`](../courier-service/README.md), [`delivery-service`](../delivery-service/README.md), [`geolocation-service`](../geolocation-service/README.md), [`vehicle-service`](../vehicle-service/README.md), [`zone-service`](../zone-service/README.md)

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
