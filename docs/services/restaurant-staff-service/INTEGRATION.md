# restaurant-staff-service — Integration Contract

## 1. Inbound APIs

### 1.1 `POST /v1/staff/invitations`

- **Purpose**: Invite a staff member.
- **Auth**: Bearer JWT (role: `merchant_owner` of the parent
  restaurant).
- **Idempotency**: `Idempotency-Key` header **required**.
- **Request**:
  ```json
  {
    "email": "manager@restaurant.example",
    "scope": "restaurant",
    "restaurant_id": "01HZX...",
    "branch_id": null,
    "roles": ["manager", "cashier"]
  }
  ```
- **Response (201)**:
  ```json
  {
    "id": "01HZX...",
    "token": "raw-token-once-only",
    "email": "manager@restaurant.example",
    "expires_at": "2026-08-01T10:42:11.183Z",
    "accept_url": "https://merchant.example/invite/..."
  }
  ```
- **Errors**:
  - 400 `VALIDATION_FAILED`
  - 401 `UNAUTHENTICATED`
  - 403 `FORBIDDEN` (not the owner / manager)
  - 404 `RESTAURANT_NOT_FOUND`
  - 409 `RESTAURANT_NOT_APPROVED`
  - 422 `ROLE_NOT_ALLOWED`
  - 422 `IDEMPOTENCY_KEY_REUSED`
  - 429 `RATE_LIMITED`
  - 503 `DEPENDENCY_TIMEOUT` / `CIRCUIT_OPEN`

### 1.2 `GET /v1/staff/invitations/{token}`

- **Purpose**: Fetch invitation details (for the acceptance
  page).
- **Auth**: Bearer JWT (any authenticated user; the page is
  shown to the invitee).
- **Response (200)**: minimal invitation view (email masked,
  restaurant name, roles, expires_at).
- **Errors**: 404, 410 (`INVITATION_EXPIRED`).

### 1.3 `POST /v1/staff/invitations/{token}/accept`

- **Purpose**: Accept an invitation.
- **Auth**: Bearer JWT (any authenticated user; the `kc_sub` of
  the token presenter is captured).
- **Idempotency**: required.
- **Request**: empty body.
- **Side effects**: creates a `staff` record; emits
  `staff.activated.v1`.
- **Errors**:
  - 404 `INVITATION_NOT_FOUND`
  - 410 `INVITATION_EXPIRED`
  - 409 `INVITATION_ALREADY_ACCEPTED`

### 1.4 `GET /v1/staff`

- **Purpose**: List staff.
- **Auth**: `merchant_owner`, `merchant_ops`, `restaurant_manager`,
  `platform_admin`.
- **Query params**: `restaurant_id`, `branch_id`, `role`, `state`,
  `q` (email), `cursor`, `limit`.

### 1.5 `GET /v1/staff/{id}`

- **Purpose**: Read a staff record.
- **Auth**: `merchant_owner`, `merchant_manager`, `platform_admin`.
- **Response (200)**: full record including roles and devices.

### 1.6 `PATCH /v1/staff/{id}/roles`

- **Purpose**: Add or remove roles.
- **Auth**: `merchant_owner` or `merchant_manager`.
- **Idempotency**: required.
- **Request**:
  ```json
  { "add": ["dispatcher"], "remove": ["cashier"] }
  ```
- **Side effects**: emits `staff.role_changed.v1`.

### 1.7 `POST /v1/staff/{id}/devices` and `DELETE /v1/staff/{id}/devices/{device_id}`

- **Purpose**: Register / remove a device.
- **Auth**: `staff` self, `merchant_owner`, or `platform_admin`.
- **Idempotency**: required.
- **Side effects**: emits `staff.device_registered.v1` or a
  `device_removed` audit event.

### 1.8 `POST /v1/staff/{id}/deactivate` and `POST /v1/staff/{id}/reactivate`

- **Purpose**: Deactivate / reactivate.
- **Auth**: `merchant_owner`, `merchant_manager`, or
  `platform_admin`.
- **Idempotency**: required.
- **Request (deactivate)**:
  `{"reason_code": "left_company", "reason_text": "..."}`.
- **Side effects**: emits `staff.deactivated.v1` or
  `staff.reactivated.v1`.

### 1.9 `GET /v1/staff/by-user/{kc_sub}`

- **Purpose**: System lookup by Keycloak subject.
- **Auth**: `client_credentials`.

### 1.10 `GET /v1/staff/rbac/check?kc_sub=&restaurant_id=&branch_id=&role=`

- **Purpose**: Fast RBAC check.
- **Auth**: `client_credentials`.
- **Cached**: 60 s TTL in Redis, key
  `staff:rbac:{kc_sub}:{restaurant_id}:{branch_id}:{role}`.
- **Response (200)**:
  ```json
  { "allowed": true, "expires_at": "..." }
  ```
- **Errors**: 400 if any required param is missing.

## 2. Outbound APIs

| Target | Method | URI | Purpose | Timeout | Retry | Circuit |
|--------|--------|-----|---------|---------|-------|---------|
| `identity-service` | GET | /v1/users/{kc_sub} | verify subject | 1 s | 3 | yes |
| `restaurant-service` | GET | /v1/restaurants/{id} | verify parent | 1 s | 3 | yes |
| `branch-service` | GET | /v1/branches/{id} | verify branch | 1 s | 3 | yes |
| `configuration-service` | GET | /v1/configurations/{key} | read role list | 1 s | 3 | yes |
| `notification-service` | POST | /v1/notifications | send invitation / deactivation | 1 s | 3 | yes |

## 3. Produced Events

### 3.1 `staff.invited.v1`

- **Producer**: `restaurant-staff-service`.
- **Topic**: `restaurant_staff.staff.invited`.
- **Trigger**: `POST /v1/staff/invitations`.
- **Schema version**: 1.
- **Partition key**: `invitation.id`.
- **Consumers**: `notification-service`, `audit-service`.
- **Schema**:
  ```json
  {
    "event_id": "01HZX...",
    "event_name": "staff.invited.v1",
    "occurred_at": "2026-07-29T10:42:11.183Z",
    "schema_version": 1,
    "producer": "restaurant-staff-service",
    "tenant_id": "global",
    "correlation_id": "01HZX...",
    "aggregate_type": "StaffInvitation",
    "aggregate_id": "01HZX...",
    "data": {
      "invitation_id": "01HZX...",
      "email_hash": "...",
      "restaurant_id": "01HZX...",
      "branch_id": null,
      "scope": "restaurant",
      "roles": ["manager","cashier"],
      "invited_by_kc_sub": "...",
      "expires_at": "2026-08-01T10:42:11.183Z"
    }
  }
  ```
- **DLQ**: `restaurant_staff.staff.invited.dlq`.

### 3.2 `staff.activated.v1`

Same envelope, `aggregate_type = Staff`, `data.staff_id`,
`data.kc_sub`, `data.email_hash`, `data.restaurant_id`,
`data.branch_id`, `data.scope`, `data.roles`.

### 3.3 `staff.role_changed.v1`

Same envelope, `data.staff_id`, `data.added`, `data.removed`.

### 3.4 `staff.device_registered.v1`

Same envelope, `data.staff_id`, `data.device_id`,
`data.device_label`.

### 3.5 `staff.deactivated.v1`

Same envelope, `data.staff_id`, `data.reason_code`,
`data.reason_text`, `data.cause` (`admin`, `owner`,
`cascade`).

### 3.6 `staff.reactivated.v1`

Same envelope, `data.staff_id`, `data.cause`.

## 4. Consumed Events

### 4.1 `restaurant.created.v1`

- **Producer**: `restaurant-service`.
- **Reason**: parent eligible for staff.
- **Handler**: log only.

### 4.2 `restaurant.suspended.v1`

- **Producer**: `restaurant-service`.
- **Reason**: cascade deactivation of staff scoped only to this
  restaurant.
- **Handler**: query `staff` where `restaurant_id = ? AND state =
  'active' AND kc_sub NOT IN (staff scoped to other restaurants)`;
  for each, set `state = 'deactivated'`, `deactivation_cause =
  'cascade'`, `deactivation_reason_code = 'restaurant_suspended'`;
  emit `staff.deactivated.v1`.

### 4.3 `restaurant.closed.v1`

- **Producer**: `restaurant-service`.
- **Reason**: cascade permanent deactivation.
- **Handler**: same as 4.2 with `restaurant_closed`.

### 4.4 `identity.user.suspended.v1`

- **Producer**: `identity-service`.
- **Reason**: deactivate all staff records of the user.
- **Handler**: query `staff` by `kc_sub`; deactivate each active
  record with `deactivation_cause = 'cascade'`,
  `deactivation_reason_code = 'user_suspended'`.

### 4.5 `identity.user.disabled.v1`

- **Producer**: `identity-service`.
- **Reason**: deactivate all staff records.
- **Handler**: same as 4.4 with `user_disabled`.

## 5. Reliability

- **Timeouts**: HTTP 1 s; DB 30 s; Kafka 5 s.
- **Retries**: 3 attempts with exponential backoff and jitter;
  Idempotency-Key on all non-idempotent calls.
- **Circuit breakers**: standard 5/30 s.
- **Bulkheads**: per-downstream connection pools.
- **Outbox**: yes, `restaurant_staff.outbox`.
- **Inbox**: yes, `restaurant_staff.inbox`.
- **DLQ**: every topic has a paired `.dlq`; 30-day retention.
- **Reconciliation**: daily job in `reporting-service` checks
  for `staff` records that should have been deactivated by
  cascade (cross-references `restaurant.suspended.v1` log);
  opens tickets if drift is found.

## 6. Correlation IDs

All requests carry `X-Correlation-Id`; the service propagates it
to outbound calls and embeds it in the event envelope.

## 7. Distributed Tracing

OpenTelemetry SDK; one root span per request; named
`POST /v1/staff/invitations`, etc. Propagated through Kafka.
Sample 100% on errors, 10% on success in production.

## 8. Threat Surface (per `SECURITY_ARCHITECTURE.md` §18)

| Threat | Mitigation |
|--------|------------|
| Spoofing | mTLS + JWT |
| Tampering (admin deactivate) | HMAC-SHA256 signature; break-glass co-sign |
| Repudiation | audit log with actor, signature, correlation |
| Information disclosure | email is encrypted; no PII beyond |
| Denial of service | rate limits; circuit breakers |
| Elevation of privilege | resource-level ownership; owner cannot be deactivated |


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
| [`branch-service`](../branch-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [`configuration-service`](../configuration-service/README.md) | DEGRADABLE | degrade (cache / default / flag) |
| [`feature-flag-service`](../feature-flag-service/README.md) | DEGRADABLE | degrade (cache / default / flag) |
| [`identity-service`](../identity-service/README.md) | CRITICAL | 503 `DEPENDENCY_UNAVAILABLE` |
| [`notification-service`](../notification-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [`restaurant-order-mgmt-service`](../restaurant-order-mgmt-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [`restaurant-service`](../restaurant-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |

### Downstream services that depend on this service

| Downstream | Class (from its perspective) |
|---|---|
| [`merchant-service`](../merchant-service/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`restaurant-service`](../restaurant-service/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |

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

