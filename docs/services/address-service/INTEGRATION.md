# address-service — Integration Contract

## 1. Inbound APIs

All endpoints require a JWT bearer token. Self-service
endpoints accept the gateway-injected `X-User-Id`.
Service-to-service endpoints require a
`client_credentials` token from `platform-services` with
the `address.read` / `address.write` /
`address.read.any` client role.

### 1.1 `GET /v1/addresses/{address_id}`

- **Purpose**: get an address.
- **Auth**: bearer (self or service with
  `address.read.any`).
- **Response (200)**:

  ```json
  {
    "id": "01HZX…",
    "identity_id": "01HZX…",
    "label": "Home",
    "tag": "home",
    "street_line1": "123 Main St",
    "street_line2": "Apt 4B",
    "city": "Anytown",
    "region": "CA",
    "country": "US",
    "postal_code": "94016",
    "location": { "type": "Point", "coordinates": [-122.4194, 37.7749] },
    "geocode_status": "success",
    "geocoded_at": "2026-07-29T10:42:11.183Z",
    "default_for_context": "ride_pickup",
    "created_at": "2026-07-29T10:42:11.183Z",
    "updated_at": "2026-07-29T10:42:11.183Z"
  }
  ```

- **Errors**: 401, 403, 404.

### 1.2 `POST /v1/addresses`

- **Purpose**: create an address.
- **Auth**: bearer (self).
- **Idempotency**: required.
- **Request**:

  ```json
  {
    "label": "Home",
    "tag": "home",
    "street_line1": "123 Main St",
    "street_line2": "Apt 4B",
    "city": "Anytown",
    "region": "CA",
    "country": "US",
    "postal_code": "94016"
  }
  ```

- **Response (201)**: as 1.1, with `geocode_status:
  "pending"`. The geocode runs asynchronously.
- **Errors**:
  - 400 `VALIDATION_FAILED`
  - 401, 403
  - 409 `ADDRESS_LIMIT_REACHED`
  - 422 `IDEMPOTENCY_KEY_REUSED`

### 1.3 `PATCH /v1/addresses/{address_id}`

- **Auth**: bearer (self).
- **Idempotency**: `If-Match` with `row_version`
  recommended.
- **Request**: any subset of `label`, `tag`,
  `street_line1`, `street_line2`, `city`, `region`,
  `country`, `postal_code`.
- **Response (200)**: as 1.1; a re-geocode is
  triggered.
- **Errors**: 400, 401, 403, 404, 409 (row_version
  mismatch).

### 1.4 `DELETE /v1/addresses/{address_id}`

- **Purpose**: soft-delete an address.
- **Auth**: bearer (self or admin).
- **Response (204)**: no body.

### 1.5 `GET /v1/addresses?identity_id={id}`

- **Purpose**: list my addresses.
- **Auth**: bearer (self or service with
  `address.read.any`).
- **Response (200)**:

  ```json
  {
    "items": [ /* address objects */ ],
    "next_cursor": null,
    "has_more": false
  }
  ```

### 1.6 `PUT /v1/addresses/{address_id}/default`

- **Purpose**: set as default for a context.
- **Auth**: bearer (self).
- **Idempotency**: required.
- **Request**: `{ "context": "ride_pickup" }`.
- **Response (200)**: the address with
  `default_for_context: "ride_pickup"`.
- **Errors**: 400, 401, 403, 404, 409 (already has
  a default for this context), 422.

### 1.7 `DELETE /v1/addresses/{address_id}/default`

- **Purpose**: unset as default.
- **Auth**: bearer (self).
- **Response (200)**: the address with
  `default_for_context: null`.

### 1.8 `GET /v1/addresses/{address_id}/geocode`

- **Purpose**: trigger re-geocode.
- **Auth**: bearer (self or service).
- **Response (200)**: the address with the new
  `geocode_status`.
- **Errors**: 422 `GEOCODE_FAILED` (final).

### 1.9 `POST /v1/addresses/{address_id}/erase`

- **Purpose**: GDPR erasure.
- **Auth**: bearer (admin `address.admin` or
  `super_admin`).
- **Idempotency**: required.
- **Request**: `{ "legal_basis": "user_request" }`.
- **Response (200)**: the address with `deleted_at`
  set, PII redacted.

## 2. Outbound APIs

| Target | Method | URI | Purpose | Timeout | Retry | Circuit |
|--------|--------|-----|---------|---------|-------|---------|
| `geolocation-service` | POST | `/v1/geocode` | geocode an address | 2s | 3, exp backoff | yes |
| `geolocation-service` | POST | `/v1/reverse-geocode` | reverse geocode | 2s | 3, exp backoff | yes |
| `identity-service` | GET | `/v1/identities/{identity_id}` | read claims | 500ms | 2 | yes |
| `configuration-service` | GET | `/v1/configurations/address.*` | read config | 500ms | 2 | yes |

## 3. Produced Events

All events use the standard envelope. The producer is
`address-service`. The partition key is `aggregate_id`
(=`address_id`).

### 3.1 `address.created.v1`

- **Topic**: `address.created`.
- **Trigger**: a new `addresses` row is created.
- **Consumers**: `customer-service` (cache
  invalidation), `notification-service`,
  `audit-service`, `analytics-service`.
- **Data**: `{ "address_id": "...", "identity_id": "...", "tag": "...", "default_for_context": "...", "occurred_at": "..." }`.

### 3.2 `address.updated.v1`

- **Topic**: `address.updated`.
- **Trigger**: an address is updated (re-geocoded,
  tag changed, default set, etc.).
- **Data**: `{ "address_id": "...", "identity_id": "...", "changed_fields": ["tag", "default_for_context"], "occurred_at": "..." }`.

### 3.3 `address.deleted.v1`

- **Topic**: `address.deleted`.
- **Trigger**: a soft-delete (manual or GDPR).
- **Data**: `{ "address_id": "...", "identity_id": "...", "reason": "user_request" | "gdpr", "occurred_at": "..." }`.

### 3.4 `address.geocoded.v1`

- **Topic**: `address.geocoded`.
- **Trigger**: an address was successfully geocoded.
- **Consumers**: `customer-service` (cache update).
- **Data**: `{ "address_id": "...", "identity_id": "...", "location": { "lat": 37.7749, "lng": -122.4194 }, "occurred_at": "..." }`.

## 4. Consumed Events

### 4.1 `configuration.updated.v1`

- **Producer**: `configuration-service`.
- **Reason**: hot-reload address config.
- **Handler**: reload in-process config.

### 4.2 `address.geocode.retry_requested.v1`

- **Producer**: `internal`.
- **Reason**: Backoff signal to retry geocoding.
- **Handler**: Trigger backfill retry.
- **Deduplication / Retry / Failure**: inbox keyed by
  `event_id` / 3 with exponential backoff / DLQ.


### 4.3 `address.audit.geocode_provider_health.v1`

- **Producer**: `internal`.
- **Reason**: Provider health signal.
- **Handler**: Update provider-health cache.
- **Deduplication / Retry / Failure**: inbox keyed by
  `event_id` / 3 with exponential backoff / DLQ.


### 4.4 `customer.address.default_changed.v1`

- **Producer**: `customer-service`.
- **Reason**: Customer's default address changed elsewhere.
- **Handler**: Invalidate hot cache.
- **Deduplication / Retry / Failure**: inbox keyed by
  `event_id` / 3 with exponential backoff / DLQ.



## 5. Reliability

- **Timeouts**: 500 ms for `identity-service` and
  configuration reads; 2 s for `geolocation-service`.
- **Retries**: 3 with exponential backoff for
  `geolocation-service`; 2 for `identity-service` and
  configuration.
- **Circuit breakers**: per upstream; default open
  after 5 failures in 10 s, reset after 30 s with a
  half-open trial.
- **Bulkheads**: per-upstream concurrency cap;
  default 50.
- **Outbox**: yes; poller single-writer per replica.
- **Inbox**: yes; keyed by `event_id`; TTL 24 h.
- **DLQ**: one per topic; retention 30 days.
- **Backfill**: a job retries failed geocodes
  (`geocode_status='pending'`) every 10 minutes;
  bounded to `address.geocode.retry_attempts`.
- **Reconciliation**: a daily job in
  `reporting-service` compares the `addresses` row
  count to the `identity-service` user count;
  drift opens a ticket.

## 6. Correlation IDs

All requests carry `X-Correlation-Id`; emitted events
carry the same in the envelope. The
`address_audit_log.correlation_id` column links the
action to the originating request.

## 7. Distributed Tracing

OpenTelemetry. One root span per request. Spans for
`geolocation-service` calls, DB queries, Redis
lookups, Kafka publishes. `traceparent` propagated to
all calls. `correlation_id` enriched on every span.


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
| [`cart-service`](../cart-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [`checkout-service`](../checkout-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [`configuration-service`](../configuration-service/README.md) | DEGRADABLE | degrade (cache / default / flag) |
| [`courier-service`](../courier-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [`customer-service`](../customer-service/README.md) | CRITICAL | 503 `DEPENDENCY_UNAVAILABLE` |
| [`delivery-service`](../delivery-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [`driver-service`](../driver-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [`geolocation-service`](../geolocation-service/README.md) | DEGRADABLE | degrade (cache / default / flag) |
| [`identity-service`](../identity-service/README.md) | CRITICAL | 503 `DEPENDENCY_UNAVAILABLE` |
| [`notification-service`](../notification-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [`trip-service`](../trip-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |

### Downstream services that depend on this service

| Downstream | Class (from its perspective) |
|---|---|
| [`api-gateway`](../api-gateway/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`checkout-service`](../checkout-service/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`customer-service`](../customer-service/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`geolocation-service`](../geolocation-service/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
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

