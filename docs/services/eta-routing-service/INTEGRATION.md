# eta-routing-service — Integration Contract

## 1. Inbound APIs

### 1.1 `POST /v1/routing/eta`

- **Purpose**: Compute an ETA.
- **Auth**: Bearer JWT (role `internal_service`).
- **Request**:
  ```json
  {
    "origin": { "lat": 25.2048, "lon": 55.2708 },
    "destination": { "lat": 25.1419, "lon": 55.2282 },
    "mode": "car",
    "departure_time": "2026-07-29T10:42:00.000Z"
  }
  ```
- **Response (200)**:
  ```json
  {
    "eta_seconds": 1080,
    "distance_meters": 12500,
    "traffic_multiplier": 1.2,
    "polyline": "...",
    "source": "cache"
  }
  ```
- **Errors**:
  - 400 `VALIDATION_FAILED`
  - 401 `UNAUTHENTICATED`
  - 403 `FORBIDDEN`
  - 503 `DEPENDENCY_TIMEOUT` (both providers down)
  - 429 `RATE_LIMITED`

### 1.2 `POST /v1/routing/route`

- **Purpose**: Compute a route.
- **Auth**: Bearer JWT (role `internal_service`).
- **Request**:
  ```json
  {
    "origin": { "lat": 25.2048, "lon": 55.2708 },
    "destination": { "lat": 25.1419, "lon": 55.2282 },
    "mode": "car",
    "alternatives": true
  }
  ```
- **Response (200)**:
  ```json
  {
    "polyline": "...",
    "eta_seconds": 1080,
    "distance_meters": 12500,
    "alternatives": [
      { "polyline": "...", "eta_seconds": 1200, "distance_meters": 13000 }
    ],
    "source": "provider"
  }
  ```

### 1.3 `GET /v1/routing/health`

- **Purpose**: Provider health.
- **Auth**: System-only.
- **Response (200)**:
  ```json
  {
    "primary": { "status": "up", "latency_ms": 120 },
    "failover": { "status": "up", "latency_ms": 150 }
  }
  ```
- **Errors**: 503 if both are down.

## 2. Outbound APIs

| Target | Method | URI | Purpose | Timeout | Retry | Circuit |
|--------|--------|-----|---------|---------|-------|---------|
| Map provider (primary) | varies | varies | ETA / route | 800ms | 1 | yes |
| Map provider (failover) | varies | varies | ETA / route | 800ms | 1 | yes |

## 3. Produced Events

### 3.1 `eta.computed.v1`

- **Topic**: `eta.computed`.
- **Partition key**: cache_key hash.
- **Consumers**: `analytics-service`.
- **Schema**:
  ```json
  {
    "event_id": "...",
    "event_name": "eta.computed.v1",
    "aggregate_id": "<cache_key_hash>",
    "data": {
      "origin": { "lat": 25.2048, "lon": 55.2708 },
      "destination": { "lat": 25.1419, "lon": 55.2282 },
      "mode": "car",
      "eta_seconds": 1080,
      "distance_meters": 12500,
      "source": "cache",
      "provider": "here",
      "latency_ms": 12
    }
  }
  ```
- **Retry**: outbox, 3; DLQ.

### 3.2 `route.computed.v1`

- **Topic**: `route.computed`.
- **Partition key**: cache_key hash.
- **Consumers**: `analytics-service`.
- **Schema**: same shape as `eta.computed.v1` with `polyline` and
  `alternatives`.

### 3.3 `eta.computed.v1`

- **Producer**: this service.
- **Topic**: `eta.computed`.
- **Trigger**: An ETA is computed (cache miss → provider call).
- **Schema version**: 1.
- **Partition key**: `request_id`.
- **Consumers**: `trip-service`, `dispatch-service`, `pricing-service`, `analytics-service`.
- **Schema**:

  ```json
  {
    "event_id": "01HZX9C8W6K0G3V2Y5N1Q4R7PB",
    "event_name": "eta.computed.v1",
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
- **DLQ**: `eta.computed.dlq`.


### 3.4 `route.computed.v1`

- **Producer**: this service.
- **Topic**: `route.computed`.
- **Trigger**: A route is computed.
- **Schema version**: 1.
- **Partition key**: `request_id`.
- **Consumers**: `trip-service`, `delivery-service`, `analytics-service`.
- **Schema**:

  ```json
  {
    "event_id": "01HZX9C8W6K0G3V2Y5N1Q4R7PB",
    "event_name": "route.computed.v1",
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
- **DLQ**: `route.computed.dlq`.



## 4. Consumed Events

### 4.1 `configuration.updated.v1`

- **Producer**: `configuration-service`.
- **Reason**: reload provider config, cache TTL.
- **Handler**: cache invalidation; flush the Redis cache on
  provider change.
- **Deduplication**: inbox on `event_id`.
- **Retry**: 3; failure → DLQ.

### 4.2 `configuration.updated.v1`

- **Producer**: `configuration-service`.
- **Reason**: Provider credentials / cache TTL changed.
- **Handler**: Reload config.
- **Deduplication / Retry / Failure**: inbox keyed by
  `event_id` / 3 with exponential backoff / DLQ.


### 4.3 `geolocation.geocoded.v1`

- **Producer**: `geolocation-service`.
- **Reason**: A geocode completed; route may use it.
- **Handler**: Update cache.
- **Deduplication / Retry / Failure**: inbox keyed by
  `event_id` / 3 with exponential backoff / DLQ.



## 5. Reliability

- **Timeouts**: provider 800ms; DB 30s.
- **Retries**: 1 on the primary; on failure, call the failover.
- **Circuit breakers**: per provider.
- **Bulkheads**: per provider connection pool.
- **Outbox**: `eta_routing.outbox` table.
- **Inbox**: `eta_routing.inbox` table.
- **DLQ**: per topic.
- **Reconciliation**: a daily job in `reporting-service` checks for
  `eta.computed.v1` failure rate spikes.

## 6. Correlation IDs

Every request carries `X-Correlation-Id`. The service:
- Logs the id on every line within the request scope (without
  lat/lon).
- Propagates it to the provider call.
- Embeds it in every emitted event and Kafka header.

## 7. Distributed Tracing

OpenTelemetry. One root span per request. The provider call is a
child span. `traceparent` is propagated. Sample rate: 100% for
errors, 10% for successes in production.


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
| [`analytics-service`](../analytics-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [`configuration-service`](../configuration-service/README.md) | DEGRADABLE | degrade (cache / default / flag) |
| [`delivery-service`](../delivery-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [`dispatch-service`](../dispatch-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [`driver-location-service`](../driver-location-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [`geolocation-service`](../geolocation-service/README.md) | DEGRADABLE | degrade (cache / default / flag) |
| [`pricing-service`](../pricing-service/README.md) | CRITICAL | 503 `DEPENDENCY_UNAVAILABLE` |
| [`ride-request-service`](../ride-request-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [`trip-service`](../trip-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |

### Downstream services that depend on this service

| Downstream | Class (from its perspective) |
|---|---|
| [`courier-dispatch-service`](../courier-dispatch-service/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`courier-tracking-service`](../courier-tracking-service/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`delivery-service`](../delivery-service/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`dispatch-service`](../dispatch-service/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`driver-location-service`](../driver-location-service/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`geolocation-service`](../geolocation-service/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`ride-request-service`](../ride-request-service/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`trip-service`](../trip-service/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |

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

