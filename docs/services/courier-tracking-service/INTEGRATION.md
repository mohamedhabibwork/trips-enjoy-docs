# courier-tracking-service — Integration Contract

## 1. Inbound APIs

### 1.1 `POST /v1/couriers/{courier_id}/location`

- **Purpose**: Ingest a single location ping.
- **Auth**: Bearer JWT (Keycloak `platform-courier`).
- **Idempotency**: optional `Idempotency-Key`; if supplied, replays
  within 60s return the original response.
- **Request**:
  ```json
  {
    "lat": 52.370216,
    "lng": 4.895168,
    "accuracy_m": 8.0,
    "speed_mps": 4.2,
    "heading_deg": 87.0,
    "battery_pct": 78,
    "timestamp": "2026-07-29T10:42:11.183Z"
  }
  ```
- **Response (202)**:
  ```json
  {
    "courier_id": "01HZX8D2Y1X1M5K7P9F3V2T8YDG",
    "received_at": "2026-07-29T10:42:11.250Z",
    "curated_emitted": true,
    "next_curated_at": "2026-07-29T10:42:12.000Z"
  }
  ```
- **Errors**:
  - 400 `VALIDATION_FAILED`
  - 401 `UNAUTHENTICATED`
  - 403 `NOT_ASSIGNED_COURIER`
  - 409 `COURIER_OFFLINE`
  - 422 `TIMESTAMP_OUT_OF_BOUNDS`
  - 429 `RATE_LIMITED`
  - 503 `DEPENDENCY_UNAVAILABLE`

### 1.2 `GET /v1/couriers/{courier_id}/location`

- **Purpose**: Read the last known position. Served from Redis with
  PostgreSQL fallback.
- **Auth**: Bearer JWT — service-to-service
  (`courier-tracking.read`).
- **Response (200)**:
  ```json
  {
    "courier_id": "01HZX8D2Y1X1M5K7P9F3V2T8YDG",
    "city_id": "01HZX7Y0X9W8M3K5P7F1V0T6YDD",
    "lat": 52.370216,
    "lng": 4.895168,
    "accuracy_m": 8.0,
    "speed_mps": 4.2,
    "heading_deg": 87.0,
    "battery_pct": 78,
    "recorded_at": "2026-07-29T10:42:11.183Z",
    "received_at": "2026-07-29T10:42:11.250Z",
    "is_stale": false
  }
  ```
- **Errors**: 401, 403, 404.

### 1.3 `GET /v1/couriers/{courier_id}/trail?from=…&to=…`

- **Purpose**: Read the recent trail.
- **Auth**: Bearer JWT — service-to-service
  (`courier-tracking.read`) OR admin (`courier-tracking.admin`).
- **Query params**: `from` (RFC3339), `to` (RFC3339), `limit`
  (default 1000, max 5000).
- **Response (200)**:
  ```json
  {
    "courier_id": "...",
    "items": [
      { "lat": 52.37, "lng": 4.89, "speed_mps": 4.2,
        "recorded_at": "...", "received_at": "..." }
    ],
    "next_cursor": null
  }
  ```
- **Errors**: 401, 403, 404, 422 (invalid range).

### 1.4 `GET /v1/locations/recent?city_id=…&bbox=…`

- **Purpose**: List couriers currently within a bounding box.
- **Auth**: Bearer JWT — service-to-service
  (`courier-tracking.read`).
- **Query params**: `city_id` (UUID), `bbox=min_lng,min_lat,
  max_lng,max_lat`, `limit` (default 500, max 5000).
- **Response (200)**:
  ```json
  {
    "items": [
      { "courier_id": "01HZX…", "lat": 52.37, "lng": 4.89,
        "recorded_at": "...", "is_stale": false }
    ]
  }
  ```
- **Errors**: 401, 403, 422 (invalid bbox).

### 1.5 `GET /v1/health/metrics`

- **Purpose**: Operational counters (admin only).
- **Auth**: Bearer JWT — admin (`courier-tracking.admin`).
- **Query params**: `?city_id=…`.
- **Response (200)**:
  ```json
  {
    "pool_size": 1234,
    "stale_count": 12,
    "ingested_last_minute": 74123,
    "curated_emitted_last_minute": 1234,
    "p99_ingest_ms": 42
  }
  ```

## 2. Outbound APIs

| Target | Method | URI | Purpose | Timeout | Retry | Circuit |
|--------|--------|-----|---------|---------|-------|---------|
| `courier-service` | GET | `/v1/couriers/{id}` | enrich (city, vehicle) | 200ms | 2 | yes |

## 3. Produced Events

### 3.1 `courier.location.updated.v1`

- **Topic**: `courier.location.updated`
- **Trigger**: curated stream cadence per courier (default 1 Hz).
- **Partition key**: `courier_id`
- **Schema**:
  ```json
  {
    "event_id": "01HZX9C8W6K0G3V2Y5N1Q4R7PB",
    "event_name": "courier.location.updated.v1",
    "occurred_at": "2026-07-29T10:42:11.250Z",
    "schema_version": 1,
    "producer": "courier-tracking-service",
    "tenant_id": "global",
    "correlation_id": "01HZX9C7T0XK2P9F0V6E4B1MZA",
    "causation_id": null,
    "aggregate_type": "Courier",
    "aggregate_id": "01HZX8D2Y1X1M5K7P9F3V2T8YDG",
    "data": {
      "courier_id": "01HZX8D2Y1X1M5K7P9F3V2T8YDG",
      "city_id": "01HZX7Y0X9W8M3K5P7F1V0T6YDD",
      "lat": 52.370216,
      "lng": 4.895168,
      "accuracy_m": 8.0,
      "speed_mps": 4.2,
      "heading_deg": 87.0,
      "battery_pct": 78,
      "recorded_at": "2026-07-29T10:42:11.183Z",
      "received_at": "2026-07-29T10:42:11.250Z"
    }
  }
  ```
- **Consumers**: `courier-dispatch-service`, `delivery-service`,
  `eta-routing-service` (read), `ride-safety-service` (read).
- **DLQ**: `courier.location.updated.dlq`.

### 3.2 `courier_tracking.audit.location_ingested.v1`

- **Topic**: `courier_tracking.audit.location_ingested`
- **Trigger**: 1/1000 sampled pings.
- **Partition key**: `courier_id`
- **Schema**: same as the curated event, with `sample: true`.
- **Consumers**: `audit-service`.

### 3.3 `courier_tracking.audit.location_ingested.v1`

- **Producer**: this service.
- **Topic**: `platform.courier_tracking.audit`.
- **Trigger**: Sampled audit of every 100th location update (1% sampling).
- **Schema version**: 1.
- **Partition key**: `courier_id`.
- **Consumers**: `audit-service`, `analytics-service`.
- **Schema**:

  ```json
  {
    "event_id": "01HZX9C8W6K0G3V2Y5N1Q4R7PB",
    "event_name": "courier_tracking.audit.location_ingested.v1",
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
- **DLQ**: `platform.courier_tracking.audit.dlq`.



## 4. Consumed Events

### 4.1 `courier.availability.online.v1`

- **Producer**: `courier-service`.
- **Reason**: start accepting pings.
- **Handler**: upsert `courier_states` to `state=online`; clear
  stale flag.
- **Deduplication**: inbox on `event_id`.

### 4.2 `courier.availability.offline.v1`

- **Producer**: `courier-service`.
- **Reason**: stop.
- **Handler**: set `state=offline`; stop emitting curated events.
  The current-location row is left for 24h then reaped.

### 4.3 `configuration.updated.v1`

- **Producer**: `configuration-service`.
- **Reason**: reload thresholds.
- **Handler**: refresh in-memory config.

## 5. Reliability

- **Timeouts**: outbound 200ms; ingest 100ms.
- **Retries**: pings are at-least-once; the trail insert is retried
  with backoff (3 attempts). Curated emits are retried via the
  outbox.
- **Circuit breakers**: outbound to `courier-service` wrapped.
- **Bulkheads**: per-downstream connection pool.
- **Outbox**: yes.
- **Inbox**: yes.
- **DLQ**: paired with each topic; retention 30 days.
- **Reconciliation**: a daily job in `reporting-service`
  reconciles the number of pings ingested with the number of
  curated emits; gaps open tickets.

## 6. Correlation IDs

All requests carry `X-Correlation-Id`; emitted events carry the
same in the envelope. Logs and traces are correlated.

## 7. Distributed Tracing

OpenTelemetry; one span per ingest (sampled 1/1000). Spans
include `courier_id`, `city_id`, `latency_ms`. `traceparent`
propagated through Kafka headers for the curated stream.


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
| [`audit-service`](../audit-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [`configuration-service`](../configuration-service/README.md) | DEGRADABLE | degrade (cache / default / flag) |
| [`courier-dispatch-service`](../courier-dispatch-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [`courier-service`](../courier-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [`delivery-service`](../delivery-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [`dispatch-service`](../dispatch-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [`eta-routing-service`](../eta-routing-service/README.md) | DEGRADABLE | degrade (cache / default / flag) |
| [`ride-safety-service`](../ride-safety-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |

### Downstream services that depend on this service

| Downstream | Class (from its perspective) |
|---|---|
| [`courier-dispatch-service`](../courier-dispatch-service/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`courier-service`](../courier-service/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`delivery-service`](../delivery-service/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`geolocation-service`](../geolocation-service/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
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

