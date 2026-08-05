# user-profile-service — Integration Contract

## 1. Inbound APIs

All endpoints require a JWT bearer token (gateway-injected
`X-User-Id`). Service-to-service endpoints require a
`client_credentials` token from `platform-services` with
the `user-profile.read` / `user-profile.write` /
`user-profile.read.any` client role.

### 1.1 `GET /v1/profiles/{identity_id}`

- **Purpose**: get a profile.
- **Auth**: bearer (self — `X-User-Id == identity_id`; or
  service with `user-profile.read.any`).
- **Response (200)**:

  ```json
  {
    "id": "01HZX…",
    "identity_id": "01HZX…",
    "preferred_locale": "en-US",
    "secondary_locale": "ar-SA",
    "avatar_file_id": "01HZX…",
    "notification_preferences": {
      "marketing": { "push": true, "email": false, "sms": false },
      "transactional": { "push": true, "email": true, "sms": false }
    },
    "do_not_disturb": { "start": "22:00", "end": "07:00", "tz": "Asia/Riyadh" },
    "status": "active",
    "created_at": "2026-01-15T10:42:11.183Z",
    "updated_at": "2026-07-29T10:42:11.183Z"
  }
  ```

- **Errors**:
  - 401 `UNAUTHENTICATED`
  - 403 `FORBIDDEN`
  - 404 `NOT_FOUND`

### 1.2 `POST /v1/profiles/{identity_id}`

- **Purpose**: create a profile (idempotent on
  `identity_id`).
- **Auth**: bearer (self or service).
- **Idempotency**: `Idempotency-Key` header required.
- **Request**:

  ```json
  {
    "preferred_locale": "en-US",
    "secondary_locale": "ar-SA",
    "notification_preferences": {
      "marketing": { "push": true, "email": false, "sms": false }
    }
  }
  ```

- **Response (201)**: as 1.1.
- **Errors**:
  - 400 `VALIDATION_FAILED`
  - 401, 403
  - 409 `CONFLICT` (profile exists)
  - 422 `IDEMPOTENCY_KEY_REUSED`

### 1.3 `PATCH /v1/profiles/{identity_id}`

- **Purpose**: update preferences.
- **Auth**: bearer (self or admin).
- **Idempotency**: `If-Match` header with `row_version`
  recommended.
- **Request**: any subset of
  `preferred_locale`, `secondary_locale`,
  `notification_preferences`, `do_not_disturb`.
- **Response (200)**: as 1.1.
- **Errors**: 400, 401, 403 `PROFILE_READ_ONLY`, 404,
  409 (row_version mismatch).

### 1.4 `GET /v1/profiles/{identity_id}/devices`

- **Purpose**: list active devices.
- **Auth**: bearer (self or service).
- **Response (200)**:

  ```json
  {
    "items": [
      {
        "id": "01HZX…",
        "platform": "ios",
        "model": "iPhone15,2",
        "os_version": "iOS 17.4",
        "app_version": "5.42.0",
        "push_provider": "apns",
        "locale": "en-US",
        "timezone": "America/Los_Angeles",
        "last_seen_at": "2026-07-29T10:42:11.183Z",
        "status": "active"
      }
    ],
    "next_cursor": null,
    "has_more": false
  }
  ```

### 1.5 `POST /v1/profiles/{identity_id}/devices`

- **Purpose**: register a device.
- **Auth**: bearer (self).
- **Idempotency**: `Idempotency-Key` required.
- **Request**:

  ```json
  {
    "platform": "ios",
    "model": "iPhone15,2",
    "os_version": "iOS 17.4",
    "app_version": "5.42.0",
    "push_token": "abcd1234…",
    "push_provider": "apns",
    "locale": "en-US",
    "timezone": "America/Los_Angeles"
  }
  ```

- **Response (201)**: the device row (without
  `push_token`).
- **Errors**:
  - 400 `VALIDATION_FAILED`
  - 401, 403
  - 409 `DEVICE_LIMIT_REACHED` (over
    `user_profile.devices.max_per_user`)
  - 422 `IDEMPOTENCY_KEY_REUSED`

### 1.6 `PATCH /v1/profiles/{identity_id}/devices/{device_id}`

- **Purpose**: update push token, last-seen, app
  version.
- **Auth**: bearer (self).
- **Request**: any subset of `push_token`,
  `push_provider`, `app_version`, `last_seen_at`.
- **Response (200)**: the device row.
- **Errors**: 400, 401, 403, 404.

### 1.7 `DELETE /v1/profiles/{identity_id}/devices/{device_id}`

- **Purpose**: unregister a device.
- **Auth**: bearer (self).
- **Response (204)**: no body.
- **Errors**: 401, 403, 404.

### 1.8 `GET /v1/profiles/{identity_id}/notification-preferences`

- **Purpose**: list notification preferences.
- **Auth**: bearer (self or service).
- **Response (200)**: the `notification_preferences`
  JSONB.

### 1.9 `PUT /v1/profiles/{identity_id}/notification-preferences`

- **Purpose**: set notification preferences (full
  replacement).
- **Auth**: bearer (self).
- **Request**: full preferences object.
- **Response (200)**: the new preferences.
- **Errors**: 400 `VALIDATION_FAILED`, 401, 403
  `PROFILE_READ_ONLY`, 404.

### 1.10 `POST /v1/profiles/{identity_id}/avatar`

- **Purpose**: upload an avatar (delegated to
  `file-service`).
- **Auth**: bearer (self).
- **Request**: multipart/form-data with the avatar
  file.
- **Response (200)**:

  ```json
  {
    "avatar_file_id": "01HZX…"
  }
  ```

- **Errors**:
  - 400, 401, 403, 404
  - 413 `PAYLOAD_TOO_LARGE`
  - 415 `UNSUPPORTED_MEDIA_TYPE`
  - 502 `DEPENDENCY_UPSTREAM_FAILURE` (file-service)

## 2. Outbound APIs

| Target | Method | URI | Purpose | Timeout | Retry | Circuit |
|--------|--------|-----|---------|---------|-------|---------|
| `identity-service` | GET | `/v1/identities/{identity_id}` | read claims | 500ms | 2, exp backoff | yes |
| `file-service` | POST | `/v1/files` | upload avatar | 5s | 1 | yes |
| `file-service` | GET | `/v1/files/{file_id}` | fetch metadata | 500ms | 2 | yes |
| `configuration-service` | GET | `/v1/configurations/user_profile.*` | read config | 500ms | 2 | yes |

## 3. Produced Events

All events use the standard envelope from
`architecture/EVENT_ARCHITECTURE.md`. The producer is
`user-profile-service`. The partition key is
`aggregate_id`.

### 3.1 `user.profile.updated.v1`

- **Topic**: `user.profile.updated`.
- **Trigger**: any change to a profile (preferences,
  avatar, do-not-disturb).
- **Schema version**: 1.
- **Consumers**: `notification-service`,
  `customer-service`, `driver-service`,
  `courier-service`, `merchant-service`,
  `admin-service`, `analytics-service`.
- **Data**:

  ```json
  {
    "profile_id": "01HZX…",
    "identity_id": "01HZX…",
    "changed_fields": ["preferred_locale", "notification_preferences"],
    "preferred_locale": "ar-SA",
    "secondary_locale": "en-US",
    "avatar_file_id": "01HZX…",
    "occurred_at": "2026-07-29T10:42:11.183Z"
  }
  ```

- **Retry**: outbox, 3 attempts.
- **DLQ**: `user.profile.updated.dlq`.

### 3.2 `user.profile.erased.v1`

- **Topic**: `user.profile.erased`.
- **Trigger**: GDPR erasure applied to the profile.
- **Consumers**: `audit-service`, `analytics-service`.
- **Data**:

  ```json
  {
    "profile_id": "01HZX…",
    "identity_id": "01HZX…",
    "erased_at": "2026-07-29T10:42:11.183Z"
  }
  ```

### 3.3 `user.device.added.v1`

- **Topic**: `user.device.added`.
- **Trigger**: a device is registered.
- **Consumers**: `notification-service`, `audit-service`.
- **Data**:

  ```json
  {
    "profile_id": "01HZX…",
    "identity_id": "01HZX…",
    "device_id": "01HZX…",
    "platform": "ios",
    "push_provider": "apns",
    "occurred_at": "2026-07-29T10:42:11.183Z"
  }
  ```

### 3.4 `user.device.removed.v1`

- **Topic**: `user.device.removed`.
- **Trigger**: a device is unregistered (manual or
  inactivity job).
- **Data**:

  ```json
  {
    "profile_id": "01HZX…",
    "identity_id": "01HZX…",
    "device_id": "01HZX…",
    "reason": "user_request" | "idle" | "erasure" | "session_revocation",
    "occurred_at": "2026-07-29T10:42:11.183Z"
  }
  ```

## 4. Consumed Events

### 4.1 `identity.user.created.v1`

- **Producer**: `identity-service`.
- **Reason**: ensure a `profiles` row exists for every
  user.
- **Handler**: upsert if missing; pull claims from
  `identity-service` for defaults.
- **Deduplication**: idempotent on `identity_id`.
- **Retry**: 3 with backoff.
- **Failure**: DLQ.

### 4.2 `identity.user.updated.v1`

- **Producer**: `identity-service`.
- **Reason**: refresh cached claims (name, locale).
- **Handler**: update cached fields; if
  `preferred_locale` was empty, set it to the new
  locale.
- **Deduplication**: idempotent.
- **Retry / Failure**: as 4.1.

### 4.3 `identity.user.suspended.v1`

- **Producer**: `identity-service`.
- **Reason**: set `read_only`.
- **Handler**: `UPDATE profiles SET status='read_only',
  read_only_at=now(), read_only_reason='suspended'`.
- **Deduplication**: idempotent.
- **Retry / Failure**: as 4.1.

### 4.4 `identity.user.disabled.v1`

Same as 4.3, with `read_only_reason='disabled'` and
`status='read_only_permanent'`.

### 4.5 `identity.user.reinstated.v1`

- **Handler**: `UPDATE profiles SET status='active',
  read_only_at=NULL, read_only_reason=NULL`.

### 4.6 `identity.user.erased.v1`

- **Handler**: anonymize the row (PII, devices cleared);
  emit `user.profile.erased.v1` and `user.device.removed.v1`
  for each device.

### 4.7 `configuration.updated.v1`

- **Producer**: `configuration-service`.
- **Reason**: hot-reload user-profile config.
- **Handler**: reload in-process config atomically.
- **Deduplication**: configuration version stamp.
- **Retry / Failure**: as 4.1.

## 5. Reliability

- **Timeouts**: 500 ms for `identity-service` and
  configuration reads; 5 s for `file-service` upload;
  30 s statement timeout for DB.
- **Retries**: 3 with exponential backoff for upstream
  REST; 2 for configuration.
- **Circuit breakers**: per upstream; default open
  after 5 failures in 10 s, reset after 30 s with a
  half-open trial.
- **Bulkheads**: per-upstream concurrency cap; default
  50.
- **Outbox**: yes, table `user_profile.outbox`; poller
  single-writer per replica.
- **Inbox**: yes, table `user_profile.inbox` keyed by
  `event_id`; TTL 24 h.
- **DLQ**: one per topic; retention 30 days.
- **Reconciliation**: daily job in `reporting-service`
  compares `user_profile.profiles` row count to
  `identity.identities` row count; drift opens a
  ticket.

## 6. Correlation IDs

All requests carry `X-Correlation-Id`; emitted events
carry the same in the envelope. The
`profile_audit_log.correlation_id` column links the
action to the originating request and to the downstream
event.

## 7. Distributed Tracing

OpenTelemetry. One root span per request. Spans for
identity-service calls, file-service calls, DB
queries, Redis lookups, Kafka publishes.
`traceparent` propagated to all calls.
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
| [`courier-service`](../courier-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [`customer-service`](../customer-service/README.md) | CRITICAL | 503 `DEPENDENCY_UNAVAILABLE` |
| [`driver-service`](../driver-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [`file-service`](../file-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [`identity-service`](../identity-service/README.md) | CRITICAL | 503 `DEPENDENCY_UNAVAILABLE` |
| [`merchant-service`](../merchant-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [`notification-service`](../notification-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |

### Downstream services that depend on this service

| Downstream | Class (from its perspective) |
|---|---|
| [`configuration-service`](../configuration-service/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`courier-service`](../courier-service/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`customer-service`](../customer-service/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`driver-service`](../driver-service/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`feature-flag-service`](../feature-flag-service/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`identity-service`](../identity-service/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`notification-service`](../notification-service/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |

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

