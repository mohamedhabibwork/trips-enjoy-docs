# driver-location-service — Integration Contract

## 1. Inbound APIs

### 1.1 `POST /v1/location`

- **Purpose**: Driver pushes a single GPS point.
- **Auth**: Bearer JWT (driver).
- **Idempotency**: optional (UPSERT is idempotent by `driver_id`).
- **Request**:
  ```json
  {
    "lat": 25.2048,
    "lon": 55.2708,
    "bearing": 87.0,
    "speed_mps": 12.4,
    "accuracy_m": 5.0,
    "recorded_at": "2026-07-29T10:48:30.000Z"
  }
  ```
- **Response (202)**:
  ```json
  { "accepted": true, "recorded_at": "..." }
  ```
- **Errors**:
  - 400 `VALIDATION_FAILED` — bad coordinates.
  - 400 `GPS_TOO_NOISY` — `accuracy_m > 100`.
  - 400 `TIMESTAMP_OUT_OF_RANGE` — `recorded_at` is more than 60s
    in the past or 5s in the future.
  - 401 `UNAUTHENTICATED`
  - 403 `FORBIDDEN` — driver_id mismatch.
  - 429 `RATE_LIMITED` — too many points.

### 1.2 `POST /v1/location/batch`

- **Purpose**: Driver pushes a batch of points (offline recovery).
- **Auth**: Bearer JWT (driver).
- **Idempotency**: `Idempotency-Key` required.
- **Request**:
  ```json
  {
    "points": [
      { "lat": ..., "lon": ..., "bearing": ..., "speed_mps": ..., "accuracy_m": ..., "recorded_at": "..." },
      ...
    ]
  }
  ```
- **Response (202)**:
  ```json
  { "accepted": 142, "rejected": 0 }
  ```
- **Errors**: 400, 401, 403, 422 (batch too large > 1000), 429.

### 1.3 `GET /v1/location/{driver_id}/current`

- **Purpose**: Read the last known position.
- **Auth**: Bearer JWT (system, safety, admin).
- **Response (200)**:
  ```json
  {
    "driver_id": "...",
    "lat": 25.2048,
    "lon": 55.2708,
    "bearing": 87.0,
    "speed_mps": 12.4,
    "accuracy_m": 5.0,
    "recorded_at": "2026-07-29T10:48:30.000Z",
    "received_at": "2026-07-29T10:48:30.123Z",
    "stale": false
  }
  ```
- **Errors**: 401, 403, 404.

### 1.4 `GET /v1/location/{driver_id}/trail?from=…&to=…`

- **Purpose**: Read the recent trail.
- **Auth**: Bearer JWT (system, safety, admin).
- **Query params**: `from` (RFC3339), `to` (RFC3339), `limit`
  (default 100, max 1000).
- **Response (200)**:
  ```json
  {
    "items": [
      { "lat": ..., "lon": ..., "recorded_at": "..." }
    ],
    "has_more": false
  }
  ```

### 1.5 `GET /v1/location/zone/{zone_id}/current?radius_m=…`

- **Purpose**: List drivers in a zone (within `radius_m` of the zone
  centroid).
- **Auth**: Service-to-service JWT.
- **Response (200)**:
  ```json
  {
    "items": [
      { "driver_id": "...", "lat": ..., "lon": ..., "recorded_at": "...", "stale": false }
    ],
    "has_more": false
  }
  ```

## 2. Outbound APIs

None. The service is a sink on the write path; it does not call
downstream services. (Internal: PostGIS queries, Kafka publish.)

## 3. Produced Events

### 3.1 `driver.location.updated.v1`

- **Topic**: `driver.location.updated`.
- **Partition key**: `driver_id`.
- **Consumers**: `dispatch-service`, `trip-service`,
  `ride-safety-service`, `eta-routing-service`.
- **Schema**:
  ```json
  {
    "event_id": "...",
    "event_name": "driver.location.updated.v1",
    "aggregate_id": "<driver_id>",
    "data": {
      "lat": 25.2048,
      "lon": 55.2708,
      "bearing": 87.0,
      "speed_mps": 12.4,
      "accuracy_m": 5.0,
      "recorded_at": "..."
    }
  }
  ```
- **Retry**: outbox, 3; DLQ.
- **Throttle**: at most 1 event per second per driver.

### 3.2 `driver_location.audit.location_ingested.v1`

- **Producer**: this service.
- **Topic**: `platform.driver_location.audit`.
- **Trigger**: Sampled audit of every 100th location update (1% sampling).
- **Schema version**: 1.
- **Partition key**: `driver_id`.
- **Consumers**: `audit-service`, `analytics-service`.
- **Schema**:

  ```json
  {
    "event_id": "01HZX9C8W6K0G3V2Y5N1Q4R7PB",
    "event_name": "driver_location.audit.location_ingested.v1",
    "occurred_at": "2026-07-29T10:42:11.183Z",
    "schema_version": 1,
    "producer": "this-service",
    "tenant_id": "global",
    "correlation_id": "01HZX9C7T0XK2P9F0V6E4B1MZA",
    "aggregate_type": "Aggregate",
    "aggregate_id": "01HZX…",
    "data": { }
  }
  ```

- **Retry**: outbox, 3 attempts.
- **DLQ**: `platform.driver_location.audit.dlq`.


### 3.3 `driver_location.health.stale_detected.v1`

- **Producer**: this service.
- **Topic**: `platform.driver_location.health`.
- **Trigger**: A driver has not sent a location update in T minutes (online but idle).
- **Schema version**: 1.
- **Partition key**: `driver_id`.
- **Consumers**: `driver-availability-service` (flags suspicious), `audit-service`.
- **Schema**:

  ```json
  {
    "event_id": "01HZX9C8W6K0G3V2Y5N1Q4R7PB",
    "event_name": "driver_location.health.stale_detected.v1",
    "occurred_at": "2026-07-29T10:42:11.183Z",
    "schema_version": 1,
    "producer": "this-service",
    "tenant_id": "global",
    "correlation_id": "01HZX9C7T0XK2P9F0V6E4B1MZA",
    "aggregate_type": "Aggregate",
    "aggregate_id": "01HZX…",
    "data": { }
  }
  ```

- **Retry**: outbox, 3 attempts.
- **DLQ**: `platform.driver_location.health.dlq`.



## 4. Consumed Events

### 4.1 `driver.availability.online.v1`

- **Producer**: `driver-availability-service`.
- **Reason**: start accepting points.
- **Handler**: set `is_online=true` in `driver_state_cache`.
- **Deduplication**: inbox on `event_id`.
- **Retry**: 3; failure → DLQ.

### 4.2 `driver.availability.offline.v1`

- **Producer**: `driver-availability-service`.
- **Reason**: stop accepting.
- **Handler**: set `is_online=false` in `driver_state_cache`. We
  still accept late points (driver in tunnel); we log a warning.
- **Deduplication**: inbox on `event_id`.
- **Retry**: 3; failure → DLQ.

### 4.3 `driver.availability.online.v1`

- **Producer**: `driver-availability-service`.
- **Reason**: Driver is now online; start tracking.
- **Handler**: Open stream buffer.
- **Deduplication / Retry / Failure**: inbox keyed by
  `event_id` / 3 with exponential backoff / DLQ.


### 4.4 `driver.availability.offline.v1`

- **Producer**: `driver-availability-service`.
- **Reason**: Driver is offline; close stream.
- **Handler**: Close buffer.
- **Deduplication / Retry / Failure**: inbox keyed by
  `event_id` / 3 with exponential backoff / DLQ.


### 4.5 `configuration.updated.v1`

- **Producer**: `configuration-service`.
- **Reason**: Stream retention / sampling changed.
- **Handler**: Reload config.
- **Deduplication / Retry / Failure**: inbox keyed by
  `event_id` / 3 with exponential backoff / DLQ.



## 5. Reliability

- **Timeouts**: outbound N/A; DB 30s.
- **Retries**: bounded 3 on event handlers; the write path itself
  does not retry (the driver app does).
- **Circuit breakers**: N/A (no outbound calls).
- **Bulkheads**: N/A.
- **Outbox**: `driver_location.outbox` table for curated events.
- **Inbox**: `driver_location.inbox` table for consumed events.
- **DLQ**: per topic (e.g. `driver.location.updated.dlq`).
- **Reconciliation**: a daily job in `reporting-service` checks for
  drivers in `current_location` that have been offline for > 30
  days and removes them.

## 6. Correlation IDs

Every request carries `X-Correlation-Id`. The service:
- Logs the id on every line within the request scope (without
  lat/lon).
- Embeds it in the curated event's `correlation_id` and Kafka
  header.

## 7. Distributed Tracing

OpenTelemetry. One root span per request. Writes are sampled at
1% to keep the trace volume manageable; reads are sampled at 10%.
Errors are sampled at 100%. `traceparent` is propagated.


## Downstream isolation

This section describes how this service handles failures in
its upstream and downstream services. The platform-wide
isolation playbook — including the per-class (CRITICAL /
DEGRADABLE / BEST-EFFORT) behavior, the dependency matrix,
and the configuration knobs — is in
[`../../architecture/SERVICE_ISOLATION.md`](../../architecture/SERVICE_ISOLATION.md).
The canonical error-code catalog and propagation rules are in
[`../../architecture/DOWNSTREAM_ERROR_CATALOG.md`](../../architecture/DOWNSTREAM_ERROR_CATALOG.md).

When this service's own code fails unexpectedly, it returns
`500 INTERNAL_ERROR`. When an error originates from another
service, this service follows the propagation rules in
[`DOWNSTREAM_ERROR_CATALOG.md` §5](../../architecture/DOWNSTREAM_ERROR_CATALOG.md)
(forward verbatim, translate, degrade, or reject) and includes
a `downstream` block identifying the original source.

### Upstream services this service depends on

| Upstream | Class | Behavior on failure |
|---|---|---|
| [`admin-service`](../admin-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [`configuration-service`](../configuration-service/README.md) | DEGRADABLE | degrade (cache / default / flag) |
| [`dispatch-service`](../dispatch-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [`driver-availability-service`](../driver-availability-service/README.md) | CRITICAL | 503 `DEPENDENCY_UNAVAILABLE` |
| [`driver-service`](../driver-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [`eta-routing-service`](../eta-routing-service/README.md) | DEGRADABLE | degrade (cache / default / flag) |
| [`geolocation-service`](../geolocation-service/README.md) | DEGRADABLE | degrade (cache / default / flag) |
| [`reporting-service`](../reporting-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [`ride-safety-service`](../ride-safety-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [`trip-service`](../trip-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |

### Downstream services that depend on this service

| Downstream | Class (from its perspective) |
|---|---|
| [`dispatch-service`](../dispatch-service/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`driver-availability-service`](../driver-availability-service/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`driver-service`](../driver-service/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`eta-routing-service`](../eta-routing-service/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`geolocation-service`](../geolocation-service/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`ride-request-service`](../ride-request-service/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`ride-safety-service`](../ride-safety-service/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`trip-service`](../trip-service/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`vehicle-service`](../vehicle-service/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`zone-service`](../zone-service/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |

### Per-downstream configuration

Per-downstream timeout / bulkhead / circuit / retry / fallback
configuration lives in the service's application config
(Kotlin: `application.yml` under `platform.outbounds.*`;
Go: `internal/outbounds/manifest.yaml`). The shared library
(`platform-spring-boot-bulkhead` for Kotlin, `internal/bulkhead`
for Go) reads the manifest and wires up the isolation pattern.

### Error envelope

Every error response uses the platform envelope defined in
[`../../shared/CONVENTIONS.md` §1](../../shared/CONVENTIONS.md)
(RFC 7807 + `downstream` block). The codes this service emits
are in §1 of this document; the canonical catalog is in
[`DOWNSTREAM_ERROR_CATALOG.md`](../../architecture/DOWNSTREAM_ERROR_CATALOG.md).


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

### Platform-wide

- [`../../shared/README.md`](../../shared/README.md) — `platform-spring-boot-starter` shared library (the single source of cross-cutting code for all Spring Boot services in the platform)
- [`../RECOMMENDATIONS.md`](../RECOMMENDATIONS.md) — platform-wide technology map (language, framework, version baseline, admin/RBAC pattern)
- [`../../README.md`](../../README.md) — services overview (the catalog of all 58 services)
- [`../../../main.md`](../../../main.md) — top-level platform specification (architecture, Keycloak, PostgreSQL 18, messaging, observability baseline)

