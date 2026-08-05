# ride-safety-service — Integration Contract

## 1. Inbound APIs

### 1.1 `POST /v1/safety/sos`

- **Purpose**: Trigger SOS.
- **Auth**: Bearer JWT (customer or driver).
- **Idempotency**: `Idempotency-Key` header required.
- **Request**:
  ```json
  {
    "trip_id": "01HZX9C5S3B1L7K0P2F8V4T6YDA",
    "location": { "lat": 25.2048, "lon": 55.2708 },
    "type": "sos"
  }
  ```
- **Response (200)**:
  ```json
  {
    "incident_id": "01HZX9C8W6K0G3V2Y5N1Q4R7PB",
    "support_ticket_id": "01HZX9C8K4D2H1A8N5J7V3R0Q9",
    "trusted_contacts_notified": 3,
    "state": "in_incident"
  }
  ```
- **Errors**:
  - 400 `VALIDATION_FAILED`
  - 401 `UNAUTHENTICATED`
  - 403 `FORBIDDEN` (not a participant of the trip)
  - 404 `TRIP_NOT_FOUND`
  - 409 `STATE_INVALID` (trip not in `in_progress`)
  - 422 `IDEMPOTENCY_KEY_REUSED`

### 1.2 `POST /v1/safety/share`

- **Purpose**: Share trip with a trusted contact.
- **Auth**: Bearer JWT (customer or driver).
- **Idempotency**: `Idempotency-Key` header required.
- **Request**:
  ```json
  {
    "trip_id": "...",
    "contact_phone": "+971 50 123 4567",
    "contact_name": "Spouse"
  }
  ```
- **Response (201)**:
  ```json
  {
    "share_link_id": "...",
    "contact_phone": "+971 50 123 4567",
    "started_at": "..."
  }
  ```
- **Errors**: 400, 401, 403, 404, 409.

### 1.3 `POST /v1/safety/record`

- **Purpose**: Start audio recording.
- **Auth**: Bearer JWT (customer or driver).
- **Idempotency**: `Idempotency-Key` header required.
- **Request**:
  ```json
  { "trip_id": "..." }
  ```
- **Response (202)**:
  ```json
  {
    "recording_id": "...",
    "upload_url": "...",
    "started_at": "..."
  }
  ```
- **Errors**: 400, 401, 403, 404, 409.

### 1.4 `GET /v1/safety/trips/{trip_id}`

- **Purpose**: Read trip safety state.
- **Auth**: Bearer JWT (safety, admin, support).
- **Response (200)**:
  ```json
  {
    "trip_id": "...",
    "state": "in_incident",
    "current_incident_id": "...",
    "current_recording_id": null,
    "live_location": { "lat": ..., "lon": ..., "stale": false } | null
  }
  ```

### 1.5 `GET /v1/safety/incidents/{id}`

- **Purpose**: Read an incident.
- **Auth**: Bearer JWT (safety, admin, support).
- **Response (200)**:
  ```json
  {
    "id": "...",
    "trip_id": "...",
    "actor_id": "...",
    "actor_type": "customer",
    "type": "sos",
    "severity": "high",
    "state": "open",
    "opened_at": "...",
    "support_ticket_id": "...",
    "trusted_contacts_notified": 3
  }
  ```

### 1.6 `POST /v1/safety/incidents/{id}/close`

- **Purpose**: Close an incident.
- **Auth**: Bearer JWT (admin) with `X-Audit-Reason`.
- **Request**:
  ```json
  { "reason": "false_alarm" }
  ```
- **Response (200)**: the closed incident.

### 1.7 `GET /v1/safety/incidents`

- **Purpose**: List incidents.
- **Auth**: Bearer JWT (safety, admin, support).
- **Query params**: `cursor`, `limit`, `state` (optional filter).
- **Response (200)**: paginated list.

## 2. Outbound APIs

| Target | Method | URI | Purpose | Timeout | Retry | Circuit |
|--------|--------|-----|---------|---------|-------|---------|
| `trip-service` | GET | /v1/trips/{id} | trip context | 200ms | 1 | yes |
| `customer-service` | GET | /v1/customers/{id}/trusted-contacts | trusted contacts | 200ms | 1 | yes |
| `driver-location-service` | GET | /v1/location/{driver_id}/current | live location | 100ms | 1 | yes |
| `file-service` | POST | /v1/files/reserve | reserve storage | 500ms | 1 | yes |
| `file-service` | PUT | (upload URL) | stream audio | 30s | 1 | yes |

## 3. Produced Events

### 3.1 `ride.safety.sos.v1`

- **Topic**: `ride.safety.sos`.
- **Partition key**: `trip_id`.
- **Consumers**: `notification-service`, `support-service`,
  `audit-service`.
- **Schema**:
  ```json
  {
    "event_id": "...",
    "event_name": "ride.safety.sos.v1",
    "aggregate_id": "<trip_id>",
    "data": {
      "incident_id": "...",
      "trip_id": "...",
      "actor_id": "...",
      "actor_type": "customer",
      "type": "sos",
      "severity": "high",
      "location": { "lat": ..., "lon": ... },
      "opened_at": "..."
    }
  }
  ```

### 3.2 `ride.safety.share.v1`

- **Topic**: `ride.safety.share`.
- **Partition key**: `trip_id`.
- **Consumers**: `notification-service`, `audit-service`.
- **Schema**:
  ```json
  {
    "event_id": "...",
    "event_name": "ride.safety.share.v1",
    "aggregate_id": "<trip_id>",
    "data": {
      "share_link_id": "...",
      "trip_id": "...",
      "actor_id": "...",
      "contact_phone": "...",
      "started_at": "..."
    }
  }
  ```

### 3.3 `ride.safety.incident.v1`

- **Topic**: `ride.safety.incident`.
- **Partition key**: `trip_id`.
- **Consumers**: `notification-service`, `support-service`,
  `audit-service`.
- **Schema**: same as `ride.safety.sos.v1` plus `state` and
  `close_reason` (for opens and closes).

### 3.4 `ride.safety.recording.finalized.v1`

- **Topic**: `ride.safety.recording.finalized`.
- **Partition key**: `trip_id`.
- **Consumers**: `audit-service`.
- **Schema**:
  ```json
  {
    "event_id": "...",
    "event_name": "ride.safety.recording.finalized.v1",
    "aggregate_id": "<trip_id>",
    "data": {
      "recording_id": "...",
      "trip_id": "...",
      "file_id": "...",
      "duration_seconds": 600,
      "finalized_at": "..."
    }
  }
  ```

## 4. Consumed Events

### 4.1 `trip.started.v1`

- **Producer**: `trip-service`.
- **Reason**: initialise the trip safety row.
- **Handler**: create the row with `state=active`.
- **Deduplication**: inbox on `event_id`; UNIQUE on `trip_id`.
- **Retry**: 3; failure → DLQ.

### 4.2 `trip.completed.v1`

- **Producer**: `trip-service`.
- **Reason**: close the trip safety row.
- **Handler**: mark `state=closed`; expire any active share links.
- **Deduplication**: inbox on `event_id`.
- **Retry**: 3; failure → DLQ.

### 4.3 `ride.safety.sos.v1` (self)

- **Producer**: this service.
- **Reason**: audit / replay.
- **Handler**: log only.
- **Deduplication**: inbox on `event_id`.
- **Retry**: 3; failure → DLQ.

## 5. Reliability

- **Timeouts**: outbound 100–500ms; DB 30s.
- **Retries**: bounded 3, exponential backoff with jitter.
- **Circuit breakers**: per downstream.
- **Bulkheads**: per downstream connection pool.
- **Outbox**: `ride_safety.outbox` table.
- **Inbox**: `ride_safety.inbox` table.
- **DLQ**: per topic.
- **Reconciliation**: a daily job in `reporting-service` checks
  for `trip_safety` rows in `in_incident` with no incident row
  (anomalous) and for incidents in `open` with no live location
  update in 5 minutes.

## 6. Correlation IDs

Every request carries `X-Correlation-Id`. The service:
- Logs the id on every line within the request scope.
- Propagates it to outbound calls.
- Embeds it in every emitted event and Kafka header.
- Reads it from the inbound event envelope and uses the same id
  for the resulting state changes.

## 7. Distributed Tracing

OpenTelemetry. One root span per request. Each notification call
is a child span. `traceparent` is propagated. Sample rate: 100%
for errors, 10% for successes in production; 100% for SOS events.


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
| [`customer-service`](../customer-service/README.md) | CRITICAL | 503 `DEPENDENCY_UNAVAILABLE` |
| [`driver-location-service`](../driver-location-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [`driver-service`](../driver-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [`file-service`](../file-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [`notification-service`](../notification-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [`support-service`](../support-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [`trip-service`](../trip-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |

### Downstream services that depend on this service

| Downstream | Class (from its perspective) |
|---|---|
| [`communication-gateway-service`](../communication-gateway-service/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`courier-tracking-service`](../courier-tracking-service/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`driver-location-service`](../driver-location-service/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`file-service`](../file-service/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`fraud-risk-service`](../fraud-risk-service/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`notification-service`](../notification-service/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`support-service`](../support-service/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`trip-service`](../trip-service/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
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

