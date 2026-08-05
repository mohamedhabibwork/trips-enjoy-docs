# zone-service — Integration Contract

## 1. Inbound APIs

All endpoints follow `architecture/API_STANDARDS.md` (JSON, JWT,
cursor pagination on lists, error envelope, `X-Correlation-Id`,
`Idempotency-Key` on POSTs, OpenAPI 3.1 spec at
`/openapi.json`).

### 1.1 `POST /v1/cities`

- **Purpose**: Create a new city.
- **Auth**: Bearer JWT + role `admin` or `city_ops`; body
  HMAC-SHA256 signed.
- **Idempotency**: required.
- **Request**:
  ```json
  {
    "tenant_id": "01HZX9C5S3B1L7K0P2F8V4T6YDA",
    "name": "Riyadh",
    "name_i18n": { "en": "Riyadh", "ar": "الرياض" },
    "country_code": "SA",
    "timezone": "Asia/Riyadh",
    "currency": "SAR",
    "polygon": {
      "type": "Polygon",
      "coordinates": [[[46.5,24.5],[46.9,24.5],[46.9,25.0],[46.5,25.0],[46.5,24.5]]]
    },
    "supported_verticals": ["ride", "food"]
  }
  ```
- **Response (201)**:
  ```json
  {
    "id": "01HZX9C8W6K0G3V2Y5N1Q4R7PB",
    "name": "Riyadh",
    "country_code": "SA",
    "timezone": "Asia/Riyadh",
    "currency": "SAR",
    "supported_verticals": ["ride", "food"],
    "status": "active",
    "version": 1,
    "created_at": "2026-07-29T10:42:11.183Z"
  }
  ```
- **Errors**:
  - 400 `VALIDATION_FAILED`
  - 401 `UNAUTHENTICATED` / 403 `FORBIDDEN` (tenant mismatch)
  - 409 `SIGNATURE_INVALID`
  - 422 `POLYGON_SELF_INTERSECTS` / `POLYGON_INVALID_SRID` /
    `POLYGON_TOO_LARGE`
  - 422 `IDEMPOTENCY_KEY_REUSED`

### 1.2 `GET /v1/cities`

- **Purpose**: List cities (cursor pagination, filter by
  `country_code`, `status`).
- **Auth**: Bearer JWT.
- **Request (query)**: `?country_code=US&status=active&limit=50`
- **Response (200)**:
  ```json
  {
    "items": [
      { "id": "01HZX9C8W6K0G3V2Y5N1Q4R7PB", "name": "Riyadh", ... }
    ],
    "next_cursor": "eyJ...",
    "has_more": false
  }
  ```

### 1.3 `GET /v1/cities/{id}`

- **Purpose**: Get a city by id.
- **Auth**: Bearer JWT.
- **Response (200)**: same shape as in 1.1.

### 1.4 `PATCH /v1/cities/{id}`

- **Purpose**: Update a city (partial).
- **Auth**: Bearer JWT + role `admin` or `city_ops`; body
  HMAC signed; `If-Match: <version>` required.
- **Idempotency**: required.
- **Request**: same fields as 1.1, any subset.
- **Response (200)**: same shape, with new `version`.
- **Errors**:
  - 400 `VALIDATION_FAILED`
  - 401 / 403
  - 409 `VERSION_MISMATCH` / `SIGNATURE_INVALID`
  - 422 `POLYGON_*`

### 1.5 `GET /v1/cities/lookup`

- **Purpose**: Resolve a coordinate to the enclosing city.
- **Auth**: Bearer JWT.
- **Request (query)**: `?lat=24.7136&lon=46.6753`
- **Response (200)**:
  ```json
  {
    "city_id": "01HZX9C8W6K0G3V2Y5N1Q4R7PB",
    "name": "Riyadh",
    "country_code": "SA",
    "timezone": "Asia/Riyadh"
  }
  ```
- **Errors**: 404 `CITY_NOT_FOUND` if no city contains the
  point.

### 1.6 `POST /v1/zones`

- **Purpose**: Create a service zone.
- **Auth**: Bearer JWT + role `admin` or `city_ops`; body
  HMAC signed.
- **Idempotency**: required.
- **Request**:
  ```json
  {
    "city_id": "01HZX9C8W6K0G3V2Y5N1Q4R7PB",
    "name": "Riyadh Downtown",
    "vertical": "ride",
    "polygon": { "type": "Polygon", "coordinates": [...] },
    "allowed_ride_types": ["standard", "premium"],
    "max_concurrent_rides": 500,
    "metadata": { "tag": "downtown" },
    "status": "active"
  }
  ```
- **Response (201)**: zone shape, with `version=1`.
- **Errors**:
  - 400 `VALIDATION_FAILED`
  - 403 `FORBIDDEN` (city tenant mismatch)
  - 409 `SIGNATURE_INVALID` / `OVERLAP_DETECTED` (warning; not
    always an error — see 1.18)
  - 422 `POLYGON_OUTSIDE_CITY` / `POLYGON_SELF_INTERSECTS` /
    `POLYGON_TOO_LARGE` / `POLYGON_INVALID_SRID` /
    `IDEMPOTENCY_KEY_REUSED`

### 1.7 `GET /v1/zones`

- **Purpose**: List service zones (filter by `city_id`,
  `vertical`, `status`).
- **Auth**: Bearer JWT.
- **Response (200)**: paginated list.

### 1.8 `GET /v1/zones/{id}`

- **Purpose**: Get a service zone.
- **Auth**: Bearer JWT.
- **Response (200)**: zone shape.

### 1.9 `PATCH /v1/zones/{id}`

- **Purpose**: Update a service zone (partial).
- **Auth**: Bearer JWT + role `admin` or `city_ops`; body
  HMAC signed; `If-Match: <version>` required.
- **Idempotency**: required.
- **Errors**: 409 `VERSION_MISMATCH` / 422 `POLYGON_*` etc.

### 1.10 `POST /v1/zones/{id}/retire`

- **Purpose**: Retire a service zone.
- **Auth**: Bearer JWT + role `admin` or `city_ops`; HMAC.
- **Idempotency**: required.
- **Response (200)**: zone shape, `status=retired`.

### 1.11 `POST /v1/zones/contains` (HOT PATH)

- **Purpose**: Resolve a coordinate to the matching service /
  surge / restricted zones, time-aware.
- **Auth**: Bearer JWT; rate-limited.
- **Request**:
  ```json
  {
    "coordinate": { "lat": 24.7136, "lon": 46.6753 },
    "at_time": "2026-07-29T10:42:11.183Z",
    "vertical": "ride"
  }
  ```
- **Response (200)**:
  ```json
  {
    "coordinate": { "lat": 24.7136, "lon": 46.6753 },
    "city": { "id": "01HZX9C8W6K0G3V2Y5N1Q4R7PB", "name": "Riyadh", "timezone": "Asia/Riyadh" },
    "service_zones": [
      { "id": "01HZX9C8W6K0G3V2Y5N1Q4R7PC", "name": "Riyadh Downtown", "vertical": "ride", "active": true }
    ],
    "surge_zones": [
      { "id": "01HZX9C8W6K0G3V2Y5N1Q4R7PD", "multiplier": 1.5, "active_now": true, "priority": 100 }
    ],
    "restricted_zones": [
      { "id": "01HZX9C8W6K0G3V2Y5N1Q4R7PE", "type": "no_pickup", "active_now": true }
    ],
    "cache_hit": true
  }
  ```
- **Errors**:
  - 400 `VALIDATION_FAILED` (lat/lon out of range)
  - 404 `CITY_NOT_FOUND`

### 1.12 `POST /v1/zones/intersects`

- **Purpose**: Return all zones that overlap a given polygon
  (used by admins to detect overlap before save).
- **Auth**: Bearer JWT + role `admin`.
- **Request**: `{"polygon": {...}, "types": ["service", "surge", "restricted"]}`
- **Response (200)**: list of zones (id, name, type, status).

### 1.13 `POST /v1/surge-zones`

- **Purpose**: Create a surge zone.
- **Auth**: Bearer JWT + role `admin` or `pricing_ops`; HMAC.
- **Idempotency**: required.
- **Request**:
  ```json
  {
    "city_id": "01HZX9C8W6K0G3V2Y5N1Q4R7PB",
    "name": "Riyadh Friday Surge",
    "polygon": { "type": "Polygon", "coordinates": [...] },
    "multiplier": 1.5,
    "time_windows": [
      { "weekday": 5, "opens_at": "16:00", "closes_at": "22:00" }
    ],
    "priority": 50
  }
  ```
- **Response (201)**: surge zone shape.
- **Errors**:
  - 422 `MULTIPLIER_OUT_OF_RANGE` (above
    `zone.surge.max_multiplier`)
  - 422 `POLYGON_OUTSIDE_CITY` etc.

### 1.14 `GET /v1/surge-zones`

- **Purpose**: List surge zones (filter by `city_id`, `status`).
- **Auth**: Bearer JWT.
- **Response (200)**: paginated list, including `multiplier`
  and `time_windows`.

### 1.15 `PATCH /v1/surge-zones/{id}`

- **Purpose**: Update a surge zone (typically the multiplier).
- **Auth**: Bearer JWT + role `admin` or `pricing_ops`; HMAC.
- **Idempotency**: required.
- **Errors**: 422 `MULTIPLIER_OUT_OF_RANGE`.

### 1.16 `GET /v1/restricted-zones`

- **Purpose**: List restricted zones (filter by `city_id`,
  `type`, `status`).
- **Auth**: Bearer JWT.
- **Response (200)**: paginated list.

### 1.17 `POST /v1/restricted-zones`

- **Purpose**: Create a restricted zone.
- **Auth**: Bearer JWT + role `admin` or `compliance_officer`;
  HMAC. `legal_hold=true` requires co-signature.
- **Idempotency**: required.
- **Request**:
  ```json
  {
    "city_id": "01HZX9C8W6K0G3V2Y5N1Q4R7PB",
    "name": "King Fahd Stadium (match day)",
    "polygon": { "type": "Polygon", "coordinates": [...] },
    "type": "no_pickup",
    "reason": "Stadium event, security perimeter",
    "time_windows": [
      { "weekday": 5, "opens_at": "18:00", "closes_at": "23:00" }
    ],
    "legal_hold": false
  }
  ```
- **Response (201)**: restricted zone shape.
- **Errors**: 422 `REASON_REQUIRED` etc.

### 1.18 `PATCH /v1/restricted-zones/{id}`

- **Purpose**: Update a restricted zone.
- **Auth**: Bearer JWT + role `admin` or `compliance_officer`;
  HMAC.
- **Idempotency**: required.
- **Errors**:
  - 409 `LEGAL_HOLD_ACTIVE` (cannot delete while on hold)
  - 422 `REASON_REQUIRED` etc.

## 2. Outbound APIs

| Target | Method | URI | Purpose | Timeout | Retry | Circuit |
|--------|--------|-----|---------|---------|-------|---------|
| `geolocation-service` | GET | `/v1/geocodes/reverse` | reverse-geocode new zone centroids on creation | 1.0s | 2 | yes |
| `configuration-service` | GET | `/v1/config/zone` | read defaults, supported verticals, holiday calendar | 500ms | 3 | yes |
| `identity-service` | GET | `/v1/identities/{sub}` | resolve admin actor (rare; usually the gateway has the claims) | 300ms | 1 | no |

All outbound calls carry `X-Correlation-Id` and `traceparent`.

## 3. Produced Events

### 3.1 `zone.city.updated.v1`

- **Producer**: `zone-service`.
- **Topic**: `zone.city.updated`.
- **Trigger**: city created, updated, suspended, retired.
- **Partition key**: `city_id`.
- **Schema (data)**:
  ```json
  {
    "city_id": "01HZX9C8W6K0G3V2Y5N1Q4R7PB",
    "name": "Riyadh",
    "country_code": "SA",
    "timezone": "Asia/Riyadh",
    "currency": "SAR",
    "supported_verticals": ["ride", "food"],
    "status": "active",
    "change": "created",
    "correlation_id": "01HZX9C7T0XK2P9F0V6E4B1MZA"
  }
  ```
- **Retry**: outbox, 3 attempts; DLQ `zone.city.updated.dlq`.

### 3.2 `zone.updated.v1`

- **Producer**: `zone-service`.
- **Topic**: `zone.zone.updated`.
- **Trigger**: service zone created, updated, retired.
- **Partition key**: `zone_id`.
- **Schema (data)**:
  ```json
  {
    "zone_id": "01HZX9C8W6K0G3V2Y5N1Q4R7PC",
    "city_id": "01HZX9C8W6K0G3V2Y5N1Q4R7PB",
    "name": "Riyadh Downtown",
    "vertical": "ride",
    "polygon_b64": "...",
    "status": "active",
    "change": "updated",
    "fields_changed": ["polygon", "max_concurrent_rides"],
    "correlation_id": "01HZX9C7T0XK2P9F0V6E4B1MZA"
  }
  ```
  (`polygon_b64` is the GeoJSON encoded as base64 — large
  polygons are common; consumers should rely on the
  REST API to fetch the full polygon rather than parse it
  out of the event.)
- **Retry / DLQ**: same as 3.1.

### 3.3 `zone.surge.updated.v1`

- **Producer**: `zone-service`.
- **Topic**: `zone.surge.updated`.
- **Trigger**: surge zone created, updated, multiplier changed.
- **Partition key**: `zone_id`.
- **Schema (data)**:
  ```json
  {
    "zone_id": "01HZX9C8W6K0G3V2Y5N1Q4R7PD",
    "city_id": "01HZX9C8W6K0G3V2Y5N1Q4R7PB",
    "multiplier": 1.5,
    "status": "active",
    "change": "multiplier_changed",
    "correlation_id": "01HZX9C7T0XK2P9F0V6E4B1MZA"
  }
  ```
- **Retry / DLQ**: same as 3.1.

### 3.4 `zone.restricted.updated.v1`

- **Producer**: `zone-service`.
- **Topic**: `zone.restricted.updated`.
- **Trigger**: restricted zone created, updated, retired.
- **Partition key**: `zone_id`.
- **Schema (data)**:
  ```json
  {
    "zone_id": "01HZX9C8W6K0G3V2Y5N1Q4R7PE",
    "city_id": "01HZX9C8W6K0G3V2Y5N1Q4R7PB",
    "type": "no_pickup",
    "reason": "Stadium event",
    "legal_hold": false,
    "status": "active",
    "change": "created",
    "correlation_id": "01HZX9C7T0XK2P9F0V6E4B1MZA"
  }
  ```
- **Retry / DLQ**: same as 3.1.

## 4. Consumed Events

### 4.1 `configuration.updated.v1`

- **Producer**: `configuration-service`.
- **Topic**: `configuration.configuration.updated`.
- **Reason**: default country, supported verticals, holiday
  calendar, polygon max area, surge max multiplier changed.
- **Handler**: reload config (idempotent; config hash
  compared before swap).
- **Deduplication**: inbox on `event_id`.
- **Retry**: 3 with backoff.
- **Failure**: DLQ.

### 4.2 `customer.created.v1`

- **Producer**: `customer-service`.
- **Topic**: `customer.customer.created`.
- **Reason**: analytics — track customers by city.
- **Handler**: no-op on zone state; we don't update zones
  on customer creation. (Documented for completeness; this
  service does not currently consume this event in
  production.)
- **Deduplication**: inbox on `event_id`.
- **Failure**: DLQ.

### 4.3 `merchant.approved.v1`

- **Producer**: `merchant-service`.
- **Topic**: `merchant.merchant.approved`.
- **Reason**: a new merchant may need a new auto-generated
  delivery zone (rare; usually ops adds the zone manually).
- **Handler**: re-validate any auto-generated zones for the
  merchant's branch; emit `zone.updated.v1` if anything
  changed.
- **Deduplication**: inbox on `event_id`.
- **Failure**: DLQ.

## 5. Reliability

- **Timeouts** (defaults):
  - `geolocation-service`: 1.0s.
  - `configuration-service`: 500ms.
  - `identity-service`: 300ms.
- **Retries**: 2 attempts with exponential backoff and jitter
  on 5xx/timeout. Never on 4xx (except 429).
- **Circuit breakers** per downstream: open on ≥ 3 consecutive
  5xx/timeout in 30s; half-open after 30s.
- **Bulkheads**: separate connection pools per downstream.
- **Outbox**: `zone.outbox` table; poller publishes to Kafka
  at-least-once; rows purged 24h after `published_at`.
- **Inbox**: `zone.inbox` table; consumers dedupe on
  `event_id`.
- **DLQ**: every topic has a paired `<topic>.dlq`; 30-day
  retention.
- **Reconciliation**: a daily job verifies that every active
  zone in the DB has a corresponding `zone.*.updated.v1` in
  the outbox in the last 24h (catches missed emissions).

## 6. Correlation IDs

- The inbound `X-Correlation-Id` is propagated to:
  - All outbound HTTP calls.
  - All log lines in the request scope.
  - The `correlation_id` field of every emitted event envelope.
  - The `headers.correlation_id` of every outbox row.
- The `causation_id` of an emitted event is the `event_id` of
  the consumed event that caused it (if any).
- The `traceparent` header is propagated end-to-end.

## 7. Distributed Tracing

- OpenTelemetry SDK, auto-instruments:
  - HTTP server (inbound).
  - HTTP client (outbound).
  - Kafka producer / consumer.
  - PostgreSQL queries.
  - Redis calls.
- One root span per request; the inbound `traceparent` is
  honored if present.
- Sample 100% of errors, 10% of successes in production; 100%
  in staging.


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
| [`address-service`](../address-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [`analytics-service`](../analytics-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [`configuration-service`](../configuration-service/README.md) | DEGRADABLE | degrade (cache / default / flag) |
| [`courier-dispatch-service`](../courier-dispatch-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [`courier-tracking-service`](../courier-tracking-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [`customer-service`](../customer-service/README.md) | CRITICAL | 503 `DEPENDENCY_UNAVAILABLE` |
| [`dispatch-service`](../dispatch-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [`driver-location-service`](../driver-location-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [`food-order-service`](../food-order-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [`fraud-risk-service`](../fraud-risk-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [`geolocation-service`](../geolocation-service/README.md) | DEGRADABLE | degrade (cache / default / flag) |
| [`identity-service`](../identity-service/README.md) | CRITICAL | 503 `DEPENDENCY_UNAVAILABLE` |
| [`merchant-service`](../merchant-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [`pricing-service`](../pricing-service/README.md) | CRITICAL | 503 `DEPENDENCY_UNAVAILABLE` |
| [`ride-request-service`](../ride-request-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [`ride-safety-service`](../ride-safety-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [`search-service`](../search-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [`trip-service`](../trip-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |

### Downstream services that depend on this service

| Downstream | Class (from its perspective) |
|---|---|
| [`branch-service`](../branch-service/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`configuration-service`](../configuration-service/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`courier-dispatch-service`](../courier-dispatch-service/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`courier-service`](../courier-service/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`driver-availability-service`](../driver-availability-service/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`driver-service`](../driver-service/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`geolocation-service`](../geolocation-service/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`pricing-service`](../pricing-service/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`restaurant-service`](../restaurant-service/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`ride-request-service`](../ride-request-service/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`scheduled-ride-service`](../scheduled-ride-service/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`search-service`](../search-service/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |

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

