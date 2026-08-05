# driver-availability-service — Integration Contract

## 1. Inbound APIs

### 1.1 `POST /v1/availability/online`

- **Purpose**: Driver goes online.
- **Auth**: Bearer JWT (driver).
- **Idempotency**: `Idempotency-Key` required.
- **Request**:
  ```json
  {
    "vehicle_id": "01HZX9C8K4D2H1A8N5J7V3R0Q9",
    "ride_types": ["economy", "premium"],
    "zone_id": "01HZX9C8W6K0G3V2Y5N1Q4R7PB"
  }
  ```
- **Response (200)**:
  ```json
  {
    "driver_id": "01HZX9C8K4D2H1A8N5J7V3R0Q9",
    "state": "online_available",
    "zone_id": "01HZX9C8W6K0G3V2Y5N1Q4R7PB",
    "ride_types": ["economy", "premium"],
    "shift_id": "01HZX9C8W6K0G3V2Y5N1Q4R7PB",
    "shift_started_at": "2026-07-29T10:00:00.000Z"
  }
  ```
- **Errors**:
  - 400 `VALIDATION_FAILED`
  - 401 `UNAUTHENTICATED`
  - 403 `DRIVER_NOT_APPROVED` / `DRIVER_SUSPENDED`
  - 422 `ZONE_UNSERVED` / `RIDE_TYPE_NOT_ALLOWED`
  - 409 `STATE_INVALID` (already online)
  - 422 `IDEMPOTENCY_KEY_REUSED`

### 1.2 `POST /v1/availability/offline`

- **Purpose**: Driver goes offline.
- **Auth**: Bearer JWT (driver).
- **Idempotency**: `Idempotency-Key` required.
- **Request**: empty body.
- **Response (200)**:
  ```json
  { "driver_id": "...", "state": "offline" }
  ```
- **Errors**: 401, 403, 409 `CANNOT_OFFLINE_BUSY`, 422
  `IDEMPOTENCY_KEY_REUSED`.

### 1.3 `GET /v1/availability/{driver_id}`

- **Purpose**: Read state.
- **Auth**: Bearer JWT (driver, support, admin).
- **Response (200)**: same shape as `POST /online`.

### 1.4 `PATCH /v1/availability/{driver_id}/zone`

- **Purpose**: Change zone.
- **Auth**: Bearer JWT (driver).
- **Idempotency**: `Idempotency-Key` required.
- **Request**:
  ```json
  { "zone_id": "01HZX9C8W6K0G3V2Y5N1Q4R7PB" }
  ```
- **Response (200)**: updated row.
- **Errors**: 401, 403, 409 `CANNOT_CHANGE_ZONE_BUSY`,
  422 `ZONE_UNSERVED`.

### 1.5 `PATCH /v1/availability/{driver_id}/ride-types`

- **Purpose**: Change accepted ride types.
- **Auth**: Bearer JWT (driver).
- **Idempotency**: `Idempotency-Key` required.
- **Request**:
  ```json
  { "ride_types": ["economy"] }
  ```
- **Response (200)**: updated row.
- **Errors**: 401, 403, 409 `CANNOT_CHANGE_RIDE_TYPES_BUSY`.

### 1.6 `POST /v1/availability/{driver_id}/break`

- **Purpose**: Start a break.
- **Auth**: Bearer JWT (driver).
- **Idempotency**: `Idempotency-Key` required.
- **Request**: empty body.
- **Response (200)**:
  ```json
  { "driver_id": "...", "state": "on_break", "break_started_at": "..." }
  ```
- **Errors**: 401, 403, 409 `NOT_ONLINE_AVAILABLE`.

### 1.7 `POST /v1/availability/{driver_id}/resume`

- **Purpose**: End a break.
- **Auth**: Bearer JWT (driver).
- **Idempotency**: `Idempotency-Key` required.
- **Response (200)**: state `online_available`.

### 1.8 `POST /v1/availability/zone/{zone_id}/online-drivers`

- **Purpose**: List online drivers in a zone (used by dispatch).
- **Auth**: Service-to-service JWT (dispatch-service).
- **Query params**: `limit` (default 100, max 1000), `cursor`.
- **Response (200)**:
  ```json
  {
    "items": [
      { "driver_id": "...", "ride_types": ["economy"], "shift_started_at": "..." }
    ],
    "next_cursor": "eyJ…",
    "has_more": false
  }
  ```

## 2. Outbound APIs

| Target | Method | URI | Purpose | Timeout | Retry | Circuit |
|--------|--------|-----|---------|---------|-------|---------|
| `driver-service` | GET | /v1/drivers/{id} | validate approved / not suspended | 500ms | 1 | yes |
| `zone-service` | GET | /v1/zones/{id} | validate served | 500ms | 1 | yes |

## 3. Produced Events

### 3.1 `driver.availability.online.v1`

- **Topic**: `driver.availability.online`.
- **Partition key**: `driver_id`.
- **Consumers**: `dispatch-service`, `driver-location-service`.
- **Schema**:
  ```json
  {
    "event_id": "...",
    "event_name": "driver.availability.online.v1",
    "aggregate_id": "<driver_id>",
    "data": {
      "zone_id": "...",
      "ride_types": ["economy", "premium"],
      "shift_id": "...",
      "shift_started_at": "..."
    }
  }
  ```
- **Retry**: outbox, 3; DLQ.

### 3.2 `driver.availability.offline.v1`

- **Topic**: `driver.availability.offline`.
- **Partition key**: `driver_id`.
- **Consumers**: `dispatch-service`, `driver-location-service`.
- **Schema**:
  ```json
  {
    "event_id": "...",
    "event_name": "driver.availability.offline.v1",
    "aggregate_id": "<driver_id>",
    "data": { "reason": "driver_choice" | "suspended" | "document_expired" | "break_timeout" | "admin" }
  }
  ```

### 3.3 `driver.availability.busy.v1`

- **Topic**: `driver.availability.busy`.
- **Partition key**: `driver_id`.
- **Consumers**: `dispatch-service`.
- **Schema**:
  ```json
  {
    "event_id": "...",
    "event_name": "driver.availability.busy.v1",
    "aggregate_id": "<driver_id>",
    "data": { "reason": "trip_started" | "break_started", "trip_id": "..." | null }
  }
  ```

### 3.4 `driver.availability.available.v1`

- **Topic**: `driver.availability.available`.
- **Partition key**: `driver_id`.
- **Consumers**: `dispatch-service`.
- **Schema**:
  ```json
  {
    "event_id": "...",
    "event_name": "driver.availability.available.v1",
    "aggregate_id": "<driver_id>",
    "data": { "reason": "trip_completed" | "trip_cancelled" | "break_ended" | "resumed", "zone_id": "..." }
  }
  ```

### 3.5 `driver.availability.zone.changed.v1`

- **Topic**: `driver.availability.zone.changed`.
- **Partition key**: `driver_id`.
- **Consumers**: `dispatch-service`.
- **Schema**:
  ```json
  {
    "event_id": "...",
    "event_name": "driver.availability.zone.changed.v1",
    "aggregate_id": "<driver_id>",
    "data": { "from_zone_id": "...", "to_zone_id": "..." }
  }
  ```

## 4. Consumed Events

### 4.1 `driver.approved.v1`

- **Producer**: `driver-service`.
- **Reason**: allow online.
- **Handler**: cache `approved=true` for the driver; do not
  auto-go-online (the driver must opt in).
- **Deduplication**: inbox on `event_id`.
- **Retry**: 3; failure → DLQ.

### 4.2 `driver.suspended.v1`

- **Producer**: `driver-service`.
- **Reason**: force offline.
- **Handler**: if the driver is in `online_available` /
  `online_busy` / `on_break`, transition to `offline` with
  `reason=suspended`; emit `driver.availability.offline.v1`. The
  active trip (if any) is unaffected — `trip-service` handles
  safety.
- **Deduplication**: inbox on `event_id`.
- **Retry**: 3; failure → DLQ.

### 4.3 `driver.document.expired.v1`

- **Producer**: `driver-service`.
- **Reason**: force offline.
- **Handler**: same as 4.2 with `reason=document_expired`.
- **Deduplication**: inbox on `event_id`.
- **Retry**: 3; failure → DLQ.

### 4.4 `trip.started.v1`

- **Producer**: `trip-service`.
- **Reason**: mark busy.
- **Handler**: transition to `online_busy`; emit
  `driver.availability.busy.v1`. Idempotent.
- **Deduplication**: inbox on `event_id`.
- **Retry**: 3; failure → DLQ.

### 4.5 `trip.completed.v1`

- **Producer**: `trip-service`.
- **Reason**: mark available.
- **Handler**: transition to `online_available` (unless on break);
  emit `driver.availability.available.v1`. Idempotent.
- **Deduplication**: inbox on `event_id`.
- **Retry**: 3; failure → DLQ.

### 4.6 `trip.cancelled.v1`

- **Producer**: `trip-service`.
- **Reason**: mark available (pre-pickup or post-mid-trip).
- **Handler**: transition to `online_available`; emit
  `driver.availability.available.v1`. Idempotent.
- **Deduplication**: inbox on `event_id`.
- **Retry**: 3; failure → DLQ.

## 5. Reliability

- **Timeouts**: outbound 500ms; DB 30s.
- **Retries**: bounded 3, exponential backoff with jitter.
- **Circuit breakers**: per downstream.
- **Bulkheads**: per downstream connection pool.
- **Outbox**: `driver_availability.outbox` table.
- **Inbox**: `driver_availability.inbox` table.
- **DLQ**: per topic.
- **Reconciliation**: a daily job in `reporting-service` checks for
  drivers in `online_busy` with no `trip.started.v1` in the last
  5 minutes (anomalous) and drivers in `online_available` with no
  `driver.location.updated.v1` for more than N minutes (idle).

## 6. Correlation IDs

Every request carries `X-Correlation-Id`. The service:
- Logs the id on every line within the request scope.
- Propagates it to outbound calls.
- Embeds it in every emitted event and Kafka header.
- Reads it from the inbound event envelope and uses the same id for
  the resulting state changes.

## 7. Distributed Tracing

OpenTelemetry. One root span per inbound request. `traceparent` is
propagated. Sample rate: 100% for errors, 10% for successes in
production.


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
| [`driver-location-service`](../driver-location-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [`driver-service`](../driver-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [`ride-request-service`](../ride-request-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [`trip-service`](../trip-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [`zone-service`](../zone-service/README.md) | DEGRADABLE | degrade (cache / default / flag) |

### Downstream services that depend on this service

| Downstream | Class (from its perspective) |
|---|---|
| [`dispatch-service`](../dispatch-service/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`driver-location-service`](../driver-location-service/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`driver-service`](../driver-service/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`ride-request-service`](../ride-request-service/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`trip-service`](../trip-service/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`vehicle-service`](../vehicle-service/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |

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

