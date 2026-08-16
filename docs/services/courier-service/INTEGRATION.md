# courier-service — Integration Contract

## 1. Inbound APIs

All endpoints require a JWT bearer token. Self-service
endpoints accept the gateway-injected `X-User-Id`.
Service-to-service endpoints require a
`client_credentials` token from `platform-services` with
the `courier.read` / `courier.write` / `courier.read.any`
client role.

### 1.1 `GET /v1/couriers/{courier_id}`

- **Purpose**: get a courier.
- **Auth**: bearer (self or service with
  `courier.read.any`).
- **Response (200)**:

  ```json
  {
    "id": "01HZX…",
    "identity_id": "01HZX…",
    "name": "Jane Courier",
    "email": "jane@example.com",
    "phone": "+31612345678",
    "vehicle_type": "bicycle",
    "primary_vehicle_id": null,
    "rating": 4.8,
    "rating_count": 187,
    "status": "approved",
    "documents_warn": false,
    "created_at": "2026-02-15T10:42:11.183Z",
    "updated_at": "2026-07-29T10:42:11.183Z"
  }
  ```

- **Errors**: 401, 403, 404.

### 1.2 `POST /v1/couriers`

- **Purpose**: create a courier (idempotent on
  `identity_id`).
- **Auth**: bearer (service).
- **Idempotency**: required.
- **Request**: `{ "identity_id": "01HZX…", "name": "Jane Courier", "phone": "+31612345678", "vehicle_type": "bicycle" }`.
- **Response (201)**: as 1.1.
- **Errors**: 400, 401, 403, 409, 422.

### 1.3 `PATCH /v1/couriers/{courier_id}`

- **Auth**: bearer (self or admin).
- **Request**: any subset of `name`, `email`, `phone`,
  `primary_vehicle_id`.
- **Response (200)**: as 1.1.

### 1.4 `GET /v1/couriers/{courier_id}/documents`

- **Auth**: bearer (self or service).
- **Response (200)**: list of documents.

### 1.5 `POST /v1/couriers/{courier_id}/documents`

- **Auth**: bearer (self).
- **Idempotency**: required.
- **Request**: `{ "type": "id", "file_id": "01HZX…", "expiry_date": "2028-01-15", "critical": true }`.
- **Response (201)**: the document row.

### 1.6 `DELETE /v1/couriers/{courier_id}/documents/{document_id}`

- **Auth**: bearer (self or admin).
- **Response (204)**: no body.

### 1.7 `GET /v1/couriers/{courier_id}/vehicle-type`

- **Auth**: bearer (self or service).
- **Response (200)**: `{ "vehicle_type": "bicycle" }`.

### 1.8 `PUT /v1/couriers/{courier_id}/vehicle-type`

- **Auth**: bearer (self).
- **Request**: `{ "vehicle_type": "motorcycle" }`.
- **Response (200)**: the courier.
- **Errors**: 400 (invalid vehicle_type), 401, 403, 404.

### 1.9 `GET /v1/couriers/{courier_id}/eligibility`

- **Auth**: bearer (self or service).
- **Response (200)**: list of city eligibility rows.

### 1.10 `POST /v1/couriers/{courier_id}/eligibility/cities/{city_id}`

- **Auth**: bearer (self or admin).
- **Response (200)**: the eligibility row.

### 1.11 `GET /v1/couriers/{courier_id}/rating`

- **Auth**: bearer (self or service).
- **Response (200)**: `{ "rating": 4.8, "rating_count": 187, "rating_updated_at": "..." }`.

### 1.12 `GET /v1/couriers/{courier_id}/shifts`

- **Auth**: bearer (self or service).
- **Response (200)**: list of shifts (past + future).

### 1.13 `POST /v1/couriers/{courier_id}/shifts`

- **Purpose**: schedule a shift.
- **Auth**: bearer (self).
- **Idempotency**: required.
- **Request**: `{ "start_at": "2026-07-30T08:00:00Z", "end_at": "2026-07-30T16:00:00Z" }`.
- **Response (201)**: the shift row.
- **Errors**: 400, 401, 403, 404, 422 `SHIFT_OVERLAP`, 422 `SHIFT_DURATION_OUT_OF_RANGE`.

### 1.14 `DELETE /v1/couriers/{courier_id}/shifts/{shift_id}`

- **Purpose**: cancel a shift.
- **Auth**: bearer (self).
- **Response (204)**: no body.

### 1.15 `POST /v1/couriers/{courier_id}/approve`

- **Auth**: bearer (admin `courier.admin`).
- **Idempotency**: required.
- **Response (200)**: the courier with `status: "approved"`.
- **Errors**: 422 `KYC_DOCUMENTS_REQUIRED`, 422 `PRIMARY_VEHICLE_REQUIRED`, 409.

### 1.16 `POST /v1/couriers/{courier_id}/reject`

- **Auth**: bearer (admin).
- **Idempotency**: required.
- **Response (200)**: the courier with `status: "rejected"`.

### 1.17 `POST /v1/couriers/{courier_id}/suspend`

- **Auth**: bearer (admin).
- **Idempotency**: required.
- **Response (200)**: the courier with `status: "suspended"`.

### 1.18 `POST /v1/couriers/{courier_id}/reinstate`

- **Auth**: bearer (admin).
- **Idempotency**: required.
- **Response (200)**: the courier with `status: "approved"`.

### 1.19 `POST /v1/couriers/{courier_id}/disable`

- **Auth**: bearer (admin `super_admin`).
- **Idempotency**: required.
- **Response (200)**: the courier with `status: "disabled"`.

### 1.20 `POST /v1/couriers/{courier_id}/erase`

- **Auth**: bearer (admin).
- **Idempotency**: required.
- **Response (200)**: the courier with `status: "erased"`,
  with `warnings[]` if there are active financial
  records.

## 2. Outbound APIs

| Target | Method | URI | Purpose | Timeout | Retry | Circuit |
|--------|--------|-----|---------|---------|-------|---------|
| `identity-service` | GET | `/v1/identities/{identity_id}` | read claims | 500ms | 2 | yes |
| ``driver-service` (vehicles)` | GET | `/v1/vehicles/{id}` | read vehicle | 500ms | 2 | yes |
| `geolocation-service` | GET | `/v1/cities/{id}` | validate city | 500ms | 2 | yes |
| ``geolocation-service` (zones)` | GET | `/v1/zones?city_id=...` | validate zone | 500ms | 2 | yes |
| KYC provider | POST | `/v1/verifications` | submit document | 5s | 1 | yes |
| KYC provider | GET | `/v1/verifications/{id}` | poll result | 2s | 3 | yes |
| Background-check provider | POST | `/v1/background-checks` | submit | 10s | 1 | yes |
| `configuration-service` | GET | `/v1/configurations/courier.*` | read config | 500ms | 2 | yes |
| **`chat-service`** *(Phase 7.7)* | GET | `/v1/chat/threads?kind=delivery_chat&context_id={delivery_id}` | read the chat thread id for the delivery detail view (the customer / courier app opens the chat via the thread id) | 500ms | 2 | yes |

## 3. Produced Events

All events use the standard envelope. The producer is
`courier-service`. The partition key is `aggregate_id`
(=`courier_id`).

### 3.1 `courier.created.v1`

- **Topic**: `courier.created`.
- **Consumers**: `audit-service`, ``reporting-service` (data lake)`,
  `identity-service` (back-channel).
- **Data**: `{ "courier_id": "...", "identity_id": "...", "vehicle_type": "...", "occurred_at": "..." }`.

### 3.2 `courier.approved.v1`

- **Topic**: `courier.approved`.
- **Consumers**: ``courier-service` (dispatch)`,
  ``courier-service` (tracking)`, `notification-service`,
  `audit-service`.
- **Data**: `{ "courier_id": "...", "approved_by": "...", "occurred_at": "..." }`.

### 3.3 `courier.rejected.v1`

- **Topic**: `courier.rejected`.
- **Data**: `{ "courier_id": "...", "reason": "...", "rejected_by": "...", "occurred_at": "..." }`.

### 3.4 `courier.suspended.v1`

- **Topic**: `courier.suspended`.
- **Consumers**: ``courier-service` (dispatch)`,
  ``courier-service` (delivery)`, `notification-service`,
  `fraud-risk-service`, `audit-service`.
- **Data**: `{ "courier_id": "...", "reason": "...", "suspended_by": "...", "occurred_at": "..." }`.

### 3.5 `courier.reinstated.v1`

Same consumers as 3.4, with `status: "approved"`.

### 3.6 `courier.disabled.v1`

Same as 3.4, plus ``admin-service` (support module)`.

### 3.7 `courier.erased.v1`

- **Topic**: `courier.erased`.
- **Consumers**: `audit-service`, ``reporting-service` (data lake)`,
  every service that owns a profile.

### 3.8 `courier.shift.scheduled.v1`

- **Topic**: `courier.shift.scheduled`.
- **Trigger**: a shift is scheduled.
- **Consumers**: `notification-service`, `audit-service`.
- **Data**: `{ "courier_id": "...", "shift_id": "...", "start_at": "...", "end_at": "...", "occurred_at": "..." }`.

### 3.9 `courier.shift.started.v1`

- **Topic**: `courier.shift.started`.
- **Trigger**: a courier goes online.
- **Consumers**: ``courier-service` (dispatch)`,
  `notification-service`, `audit-service`.
- **Data**: `{ "courier_id": "...", "shift_id": "...", "actual_start_at": "...", "occurred_at": "..." }`.

### 3.10 `courier.shift.ended.v1`

- **Topic**: `courier.shift.ended`.
- **Trigger**: a courier goes offline.
- **Consumers**: ``courier-service` (dispatch)`,
  `notification-service`, `audit-service`.
- **Data**: `{ "courier_id": "...", "shift_id": "...", "actual_end_at": "...", "occurred_at": "..." }`.

### 3.11 `courier.document.expiring.v1`

- **Topic**: `courier.document.expiring`.
- **Trigger**: nightly job; 30, 7, 1 day before expiry.
- **Consumers**: `notification-service`, `audit-service`.
- **Data**: `{ "courier_id": "...", "document_id": "...", "document_type": "id", "expiry_date": "...", "days_remaining": 7, "occurred_at": "..." }`.

### 3.12 `courier.document.expired.v1`

- **Topic**: `courier.document.expired`.
- **Trigger**: nightly job; document past expiry +
  grace period.
- **Consumers**: ``courier-service` (dispatch)`,
  `notification-service`, `audit-service`.
- **Data**: `{ "courier_id": "...", "document_id": "...", "document_type": "id", "expiry_date": "...", "grace_period_ended_at": "...", "occurred_at": "..." }`.

## 4. Consumed Events

### 4.1 `identity.user.created.v1`

- **Producer**: `identity-service`.
- **Reason**: back-channel — ensure a `couriers`
  row exists.
- **Handler**: upsert if missing; pull claims for
  defaults.
- **Deduplication**: idempotent on `identity_id`.

### 4.2 `identity.user.updated.v1`

- **Reason**: refresh cached claims.
- **Handler**: update cached fields.

### 4.3 `identity.user.suspended.v1`

- **Handler**: set `status='suspended'`; emit
  `courier.suspended.v1`.

### 4.4 `identity.user.disabled.v1`

- **Handler**: set `status='disabled'`; emit
  `courier.disabled.v1`.

### 4.5 `identity.user.reinstated.v1`

- **Handler**: set `status='approved'`; emit
  `courier.reinstated.v1`.

### 4.6 `identity.user.erased.v1`

- **Handler**: anonymize the row; emit
  `courier.erased.v1`.

### 4.7 `vehicle.registered.v1`

- **Producer**: ``driver-service` (vehicles)`.
- **Reason**: link to primary vehicle.
- **Handler**: if the `courier_id` matches and the
  vehicle is marked `primary`, set
  `primary_vehicle_id`.

### 4.8 `vehicle.insurance.expired.v1`

- **Reason**: auto-suspend the courier if the
  expired vehicle is the primary and no
  replacement is uploaded within the grace period.
- **Handler**: if `primary_vehicle_id` matches and
  the grace period has elapsed, set
  `status='suspended'`,
  `suspended_reason='insurance_expired'`, emit
  `courier.suspended.v1` and
  `courier.document.expired.v1`.

### 4.9 `review.aggregated.v1`

- **Producer**: ``trip-service` / `food-order-service` / `search-service` (review projections)`.
- **Reason**: update the rating read-model.
- **Handler**: update `rating`, `rating_count`,
  `rating_updated_at`; append to
  `courier_rating_history`.

### 4.10 `configuration.updated.v1`

- **Reason**: hot-reload courier config.
- **Handler**: reload in-process config.

### 4.11 `chat.message.reported.v1` *(Phase 7.7 — In-App Chat)*

- **Producer**: `chat-service`.
- **Reason**: a customer or courier reported a message in the
  delivery chat. When the reason is `safety` or `abuse`, this
  service flags the delivery for ops review and includes the
  chat payload as evidence.
- **Handler**: when `data.reason IN ('safety', 'abuse')`, attach
  the chat-message evidence to the delivery's existing incident
  record (via ``admin-service` (support)`); for `reason = 'other'`
  / `spam`, log only.
- **Deduplication**: inbox on `event_id`.
- **Retry**: 3; failure → DLQ.

### 4.12 `chat.thread.closed.v1` *(Phase 7.7 — In-App Chat)*

- **Producer**: `chat-service`.
- **Reason**: informational. The chat thread for this delivery
  closed (on `delivery.completed.v1` / `delivery.cancelled.v1`).
- **Handler**: log only.
- **Deduplication**: inbox on `event_id`.
- **Retry**: 3; failure → DLQ.

## 5. Reliability

- **Timeouts**: 500 ms for upstream REST; 10 s for
  KYC submit; 2 s for KYC poll.
- **Retries**: 3 with exponential backoff for
  upstream REST; 1 for KYC submit.
- **Circuit breakers**: per upstream; default open
  after 5 failures in 10 s, reset after 30 s.
- **Bulkheads**: per-upstream concurrency cap;
  default 50.
- **Outbox**: yes; poller single-writer per replica.
- **Inbox**: yes; keyed by `event_id`; TTL 24 h.
- **DLQ**: one per topic; retention 30 days.
- **Reconciliation**: a daily job reconciles
  `couriers` row count against `identities` row
  count; drift opens a ticket.

## 6. Correlation IDs

All requests carry `X-Correlation-Id`; emitted events
carry the same in the envelope. The
`courier_audit_log.correlation_id` column links the
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
[`DOWNSTREAM_ERROR_CATALOG.md` 5](../../architecture/DOWNSTREAM_ERROR_CATALOG.md)
(forward verbatim, translate, degrade, or reject) and includes
a `downstream` block identifying the original source.

### Upstream services this service depends on

| Upstream | Class | Behavior on failure |
|---|---|---|
| [`admin-service`](../admin-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [``reporting-service` (data lake)`](../reporting-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [`api-gateway`](../api-gateway/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [`audit-service`](../audit-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [`configuration-service`](../configuration-service/README.md) | DEGRADABLE | degrade (cache / default / flag) |
| [``courier-service` (dispatch)`](../courier-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [``payment-service` (courier earnings)`](../payment-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [``courier-service` (tracking)`](../courier-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [``courier-service` (delivery)`](../courier-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [``driver-service` (dispatch)`](../driver-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [`fraud-risk-service`](../fraud-risk-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [`geolocation-service`](../geolocation-service/README.md) | DEGRADABLE | degrade (cache / default / flag) |
| [`identity-service`](../identity-service/README.md) | CRITICAL | 503 `DEPENDENCY_UNAVAILABLE` |
| [`notification-service`](../notification-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [`trip-service`](../trip-service/README.md) · [`food-order-service`](../food-order-service/README.md) · [`search-service`](../search-service/README.md) (review projections) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [``admin-service` (support module)`](../admin-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [``customer-service` (cross-persona profile)`](../customer-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [``driver-service` (vehicles)`](../driver-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [``geolocation-service` (zones)`](../geolocation-service/README.md) | DEGRADABLE | degrade (cache / default / flag) |

### Downstream services that depend on this service

| Downstream | Class (from its perspective) |
|---|---|
| [``customer-service` (addresses)`](../customer-service/README.md) | _see [`SERVICE_ISOLATION.md` 2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`api-gateway`](../api-gateway/README.md) | _see [`SERVICE_ISOLATION.md` 2](../../architecture/SERVICE_ISOLATION.md)_ |
| [``courier-service` (dispatch)`](../courier-service/README.md) | _see [`SERVICE_ISOLATION.md` 2](../../architecture/SERVICE_ISOLATION.md)_ |
| [``payment-service` (courier earnings)`](../payment-service/README.md) | _see [`SERVICE_ISOLATION.md` 2](../../architecture/SERVICE_ISOLATION.md)_ |
| [``courier-service` (tracking)`](../courier-service/README.md) | _see [`SERVICE_ISOLATION.md` 2](../../architecture/SERVICE_ISOLATION.md)_ |
| [``courier-service` (delivery)`](../courier-service/README.md) | _see [`SERVICE_ISOLATION.md` 2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`file-service`](../file-service/README.md) | _see [`SERVICE_ISOLATION.md` 2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`fraud-risk-service`](../fraud-risk-service/README.md) | _see [`SERVICE_ISOLATION.md` 2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`identity-service`](../identity-service/README.md) | _see [`SERVICE_ISOLATION.md` 2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`notification-service`](../notification-service/README.md) | _see [`SERVICE_ISOLATION.md` 2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`trip-service`](../trip-service/README.md) · [`food-order-service`](../food-order-service/README.md) · [`search-service`](../search-service/README.md) (review projections) | _see [`SERVICE_ISOLATION.md` 2](../../architecture/SERVICE_ISOLATION.md)_ |
| [``admin-service` (support module)`](../admin-service/README.md) | _see [`SERVICE_ISOLATION.md` 2](../../architecture/SERVICE_ISOLATION.md)_ |
| [``customer-service` (cross-persona profile)`](../customer-service/README.md) | _see [`SERVICE_ISOLATION.md` 2](../../architecture/SERVICE_ISOLATION.md)_ |
| [``driver-service` (vehicles)`](../driver-service/README.md) | _see [`SERVICE_ISOLATION.md` 2](../../architecture/SERVICE_ISOLATION.md)_ |

### Per-downstream configuration

Per-downstream timeout / bulkhead / circuit / retry / fallback
configuration lives in the service's application config
(Kotlin: `application.yml` under `platform.outbounds.*`;
Go: `internal/outbounds/manifest.yaml`). The shared library
(`platform-spring-boot-bulkhead` for Kotlin, `internal/bulkhead`
for Go) reads the manifest and wires up the isolation pattern.

### Error envelope

Every error response uses the platform envelope defined in
[`../../shared/CONVENTIONS.md` 1](../../shared/CONVENTIONS.md)
(RFC 7807 + `downstream` block). The codes this service emits
are in 1 of this document; the canonical catalog is in
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
- [`../../README.md`](../../README.md) — services overview (the catalog of all 20 services)
- [`../../../main.md`](../../../main.md) — top-level platform specification (architecture, Keycloak, PostgreSQL 19, messaging, observability baseline)

## Conductor Workers

This service runs Conductor workers for the following workflows per
[ADR-0018](../../architecture/adrs/0018-workflow-engine-conductor.md) and
[`shared/CONDUCTOR_WORKFLOWS.md`](../../shared/CONDUCTOR_WORKFLOWS.md).
Workers are colocated in this service's binary; SDK: **conductor-kotlin v3.x**.

| Workflow ID | Tasks owned | Idempotency-Key namespace |
|---|---|---|
| Workflow ID | Tasks owned | Idempotency-Key namespace |
|---|---|---|
| `wf.onboarding.courier.v1` | courier_service_training_module_complete + vehicle_inspection + activation | `courier:{id}:onboarding:*` |


### Kafka signal mapping

| Topic | Signal | Triggers |
|---|---|---|
| (no inbound Kafka signals — REST trigger only or worker is reactive to conductor-kafka-bridge events) | – | – |


### Compensation responsibilities

This service implements the following compensation tasks; see
[`shared/CONDUCTOR_WORKFLOWS.md`](../../shared/CONDUCTOR_WORKFLOWS.md) 4 for
ordering rules.

| Forward task | Compensation task | Reversibility |
|---|---|---|
| (no compensation — terminal states only, or compensation is no-op) | – | – |


### Configuration keys

- `conductor.server.url` — set by Helm per env (e.g. `https://conductor.prod.trips-enjoy.com`)
- `conductor.task.<task_name>.timeout_seconds` — default 30s
- `conductor.task.<task_name>.retry_count` — default 3
- `conductor.worker.heartbeat_interval_seconds` — default 5s
- `conductor.kafka.bridge.url` — for `conductor-kafka-bridge` integration

### Operational references

- Runbook: [`shared/CONDUCTOR_WORKFLOWS.md`](../../shared/CONDUCTOR_WORKFLOWS.md) 8
- Observability: [`shared/CONDUCTOR_WORKFLOWS.md`](../../shared/CONDUCTOR_WORKFLOWS.md) 7
- Master task registry: [`MASTER_TASK.md`](../../MASTER_TASK.md) 7-9
