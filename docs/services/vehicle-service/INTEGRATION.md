# vehicle-service — Integration Contract

## 1. Inbound APIs

All endpoints require a JWT bearer token. Owner-only
mutations require the JWT's `X-User-Id` to match
`owner_driver_id` or `owner_courier_id` of the vehicle
(validated via `driver-service` /
`courier-service`). Service-to-service endpoints
require a `client_credentials` token from
`platform-services` with the `vehicle.read` /
`vehicle.write` / `vehicle.read.any` client role.

### 1.1 `GET /v1/vehicles/{vehicle_id}`

- **Purpose**: get a vehicle.
- **Auth**: bearer (owner or service with
  `vehicle.read.any`).
- **Response (200)**:

  ```json
  {
    "id": "01HZX…",
    "plate_number": "ABC-1234",
    "plate_country": "US",
    "make": "Toyota",
    "model": "Camry",
    "year": 2022,
    "color": "white",
    "vin": "1HGBH41JXMN109186",
    "owner_driver_id": "01HZX…",
    "owner_courier_id": null,
    "status": "approved",
    "created_at": "2026-01-15T10:42:11.183Z",
    "updated_at": "2026-07-29T10:42:11.183Z"
  }
  ```

- **Errors**: 401, 403, 404.

### 1.2 `POST /v1/vehicles`

- **Purpose**: register a vehicle.
- **Auth**: bearer (driver or courier).
- **Idempotency**: required.
- **Request**:

  ```json
  {
    "plate_number": "ABC-1234",
    "plate_country": "US",
    "make": "Toyota",
    "model": "Camry",
    "year": 2022,
    "color": "white",
    "vin": "1HGBH41JXMN109186",
    "owner_driver_id": "01HZX…",
    "registration_certificate_file_id": "01HZX…"
  }
  ```

- **Response (201)**: as 1.1.
- **Errors**: 400 `VALIDATION_FAILED` (plate format),
  401, 403, 409 (duplicate plate), 422.

### 1.3 `PATCH /v1/vehicles/{vehicle_id}`

- **Auth**: bearer (owner or admin).
- **Request**: any subset of `color`, `vin`,
  `owner_driver_id`, `owner_courier_id`,
  `registration_certificate_file_id`.
- **Response (200)**: as 1.1.
- **Errors**: 400, 401, 403, 404, 409.

### 1.4 `GET /v1/vehicles/{vehicle_id}/insurances`

- **Auth**: bearer (owner or service).
- **Response (200)**: list of insurance policies.

### 1.5 `POST /v1/vehicles/{vehicle_id}/insurances`

- **Auth**: bearer (owner).
- **Idempotency**: required.
- **Request**:

  ```json
  {
    "policy_file_id": "01HZX…",
    "provider": "Allianz",
    "policy_number": "POL-12345",
    "coverage_minor": 1000000,
    "coverage_currency": "USD",
    "start_date": "2026-07-29T00:00:00Z",
    "expiry_date": "2027-07-29T00:00:00Z"
  }
  ```

- **Response (201)**: the insurance row.
- **Errors**: 400, 401, 403, 404, 422.

### 1.6 `DELETE /v1/vehicles/{vehicle_id}/insurances/{insurance_id}`

- **Auth**: bearer (owner or admin).
- **Response (204)**: no body.

### 1.7 `GET /v1/vehicles/{vehicle_id}/inspections`

- **Auth**: bearer (owner or service).
- **Response (200)**: list of inspections.

### 1.8 `POST /v1/vehicles/{vehicle_id}/inspections`

- **Auth**: bearer (owner).
- **Idempotency**: required.
- **Request**:

  ```json
  {
    "certificate_file_id": "01HZX…",
    "inspector": "DEKRA",
    "inspection_date": "2026-07-29T00:00:00Z",
    "expiry_date": "2027-07-29T00:00:00Z",
    "result": "pass"
  }
  ```

- **Response (201)**: the inspection row.
- **Errors**: 400, 401, 403, 404, 422.

### 1.9 `DELETE /v1/vehicles/{vehicle_id}/inspections/{inspection_id}`

- **Auth**: bearer (owner or admin).
- **Response (204)**: no body.

### 1.10 `POST /v1/vehicles/{vehicle_id}/owners`

- **Purpose**: add a co-owner (driver or courier).
- **Auth**: bearer (owner or admin).
- **Idempotency**: required.
- **Request**:

  ```json
  {
    "owner_driver_id": "01HZX…",
    "owner_courier_id": "01HZX…"
  }
  ```

- **Response (200)**: the vehicle.
- **Errors**: 400, 401, 403, 404, 409 (already
  associated).

### 1.11 `DELETE /v1/vehicles/{vehicle_id}/owners/{owner_id}`

- **Purpose**: remove a co-owner.
- **Auth**: bearer (owner or admin).
- **Response (204)**: no body.

### 1.12 `POST /v1/vehicles/{vehicle_id}/approve`

- **Auth**: bearer (admin `vehicle.admin`).
- **Idempotency**: required.
- **Response (200)**: the vehicle with `status: "approved"`.
- **Errors**: 409 (already approved).

### 1.13 `POST /v1/vehicles/{vehicle_id}/erase`

- **Auth**: bearer (admin).
- **Idempotency**: required.
- **Response (200)**: the vehicle with `status: "erased"`,
  with `warnings[]` if there are active trip /
  delivery records.

## 2. Outbound APIs

| Target | Method | URI | Purpose | Timeout | Retry | Circuit |
|--------|--------|-----|---------|---------|-------|---------|
| `identity-service` | GET | `/v1/identities/{identity_id}` | read claims | 500ms | 2 | yes |
| `driver-service` | GET | `/v1/drivers/{id}` | validate owner_driver_id | 500ms | 2 | yes |
| `courier-service` | GET | `/v1/couriers/{id}` | validate owner_courier_id | 500ms | 2 | yes |
| `configuration-service` | GET | `/v1/configurations/vehicle.*` | read config | 500ms | 2 | yes |

## 3. Produced Events

All events use the standard envelope. The producer is
`vehicle-service`. The partition key is `aggregate_id`
(=`vehicle_id`).

### 3.1 `vehicle.registered.v1`

- **Topic**: `vehicle.registered`.
- **Trigger**: a new `vehicles` row is created.
- **Consumers**: `driver-service`, `courier-service`,
  `audit-service`, `analytics-service`.
- **Data**: `{ "vehicle_id": "...", "owner_driver_id": "...", "owner_courier_id": "...", "make": "...", "model": "...", "year": ..., "plate_number": "...", "plate_country": "...", "occurred_at": "..." }`.

### 3.2 `vehicle.approved.v1`

- **Topic**: `vehicle.approved`.
- **Consumers**: `driver-service`, `courier-service`,
  `notification-service`, `audit-service`.
- **Data**: `{ "vehicle_id": "...", "approved_by": "...", "occurred_at": "..." }`.

### 3.3 `vehicle.insurance.expiring.v1`

- **Topic**: `vehicle.insurance.expiring`.
- **Trigger**: nightly job; 30, 7, 1 day before expiry.
- **Consumers**: `notification-service`, `audit-service`.
- **Data**: `{ "vehicle_id": "...", "insurance_id": "...", "provider": "...", "expiry_date": "...", "days_remaining": 7, "occurred_at": "..." }`.

### 3.4 `vehicle.insurance.expired.v1`

- **Topic**: `vehicle.insurance.expired`.
- **Trigger**: nightly job; past expiry + grace period.
- **Consumers**: `driver-service`, `courier-service`,
  `driver-availability-service`, `audit-service`.
- **Data**: `{ "vehicle_id": "...", "insurance_id": "...", "expiry_date": "...", "occurred_at": "..." }`.

### 3.5 `vehicle.inspection.expiring.v1`

Same as 3.3, for inspection certificates.

### 3.6 `vehicle.inspection.expired.v1`

Same as 3.4, for inspection certificates.

### 3.7 `vehicle.erased.v1`

- **Topic**: `vehicle.erased`.
- **Consumers**: `audit-service`, `analytics-service`.

## 4. Consumed Events

### 4.1 `configuration.updated.v1`

- **Producer**: `configuration-service`.
- **Reason**: hot-reload vehicle config.
- **Handler**: reload in-process config.

### 4.2 `vehicle.insurance_replaced.v1`

- **Producer**: `internal`.
- **Reason**: A new insurance policy replaced the old one.
- **Handler**: Update primary insurance reference.
- **Deduplication / Retry / Failure**: inbox keyed by
  `event_id` / 3 with exponential backoff / DLQ.


### 4.3 `vehicle.driver_linked.v1`

- **Producer**: `internal`.
- **Reason**: A driver linked to this vehicle.
- **Handler**: Update owner_driver_id.
- **Deduplication / Retry / Failure**: inbox keyed by
  `event_id` / 3 with exponential backoff / DLQ.


### 4.4 `vehicle.courier_linked.v1`

- **Producer**: `internal`.
- **Reason**: A courier linked to this vehicle.
- **Handler**: Update owner_courier_id.
- **Deduplication / Retry / Failure**: inbox keyed by
  `event_id` / 3 with exponential backoff / DLQ.



## 5. Reliability

- **Timeouts**: 500 ms for upstream REST.
- **Retries**: 3 with exponential backoff.
- **Circuit breakers**: per upstream; default open
  after 5 failures in 10 s, reset after 30 s.
- **Bulkheads**: per-upstream concurrency cap;
  default 50.
- **Outbox**: yes; poller single-writer per replica.
- **Inbox**: yes; keyed by `event_id`; TTL 24 h.
- **DLQ**: one per topic; retention 30 days.

## 6. Correlation IDs

All requests carry `X-Correlation-Id`; emitted events
carry the same in the envelope. The
`vehicle_audit_log.correlation_id` column links the
action to the originating request.

## 7. Distributed Tracing

OpenTelemetry. One root span per request. Spans for
upstream calls, DB queries, Redis lookups, Kafka
publishes. `traceparent` propagated to all calls.
`correlation_id` enriched on every span.


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
| [`api-gateway`](../api-gateway/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [`audit-service`](../audit-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [`configuration-service`](../configuration-service/README.md) | DEGRADABLE | degrade (cache / default / flag) |
| [`courier-dispatch-service`](../courier-dispatch-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [`courier-service`](../courier-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [`courier-tracking-service`](../courier-tracking-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [`dispatch-service`](../dispatch-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [`driver-availability-service`](../driver-availability-service/README.md) | CRITICAL | 503 `DEPENDENCY_UNAVAILABLE` |
| [`driver-location-service`](../driver-location-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [`driver-service`](../driver-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [`identity-service`](../identity-service/README.md) | CRITICAL | 503 `DEPENDENCY_UNAVAILABLE` |
| [`notification-service`](../notification-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |

### Downstream services that depend on this service

| Downstream | Class (from its perspective) |
|---|---|
| [`api-gateway`](../api-gateway/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`courier-service`](../courier-service/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`driver-service`](../driver-service/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |

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

