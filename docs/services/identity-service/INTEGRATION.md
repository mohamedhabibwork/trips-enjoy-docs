# identity-service — Integration Contract

## 1. Inbound APIs

All endpoints require a JWT bearer token. Admin endpoints
require a `platform-internal` token with `identity.admin` or
`super_admin` realm role. Service-to-service endpoints
require a `client_credentials` token from
`platform-services` with the `identity.read` or
`identity.write` client role on the
`identity-service` client.

### 1.1 `GET /v1/identities/{identity_id}`

- **Purpose**: lookup an identity by its stable internal id.
- **Auth**: bearer (service-to-service `identity.read`).
- **Response (200)**:

  ```json
  {
    "id": "01HZX…",
    "kc_sub": "f4a8c0…",
    "realm": "platform-customer",
    "user_type": "customer",
    "region": "eu-west",
    "tenant_id": null,
    "name": "Jane Doe",
    "email": "jane@example.com",
    "email_verified": true,
    "phone": "+31612345678",
    "phone_verified": true,
    "locale": "en-US",
    "mfa_enabled": true,
    "status": "active",
    "suspended_reason": null,
    "suspended_at": null,
    "customer_id": "01HZX…",
    "created_at": "2026-01-15T10:42:11.183Z",
    "updated_at": "2026-07-29T10:42:11.183Z"
  }
  ```

- **Errors**:
  - 401 `UNAUTHENTICATED`
  - 403 `FORBIDDEN`
  - 404 `NOT_FOUND`
  - 410 `GONE` (erased identity; returns the tombstone
    with all PII redacted)

### 1.2 `GET /v1/identities?kc_sub={sub}&realm={realm}`

- **Purpose**: lookup by Keycloak subject and realm.
- **Auth**: bearer (service-to-service `identity.read`).
- **Response**: same as 1.1.
- **Errors**: as 1.1.

### 1.3 `POST /v1/identities/introspect`

- **Purpose**: normalize a token's claims. The service
  verifies the token against Keycloak's JWKS, looks up the
  `identity_id` by `kc_sub`, and returns the normalized
  view.
- **Auth**: bearer (service-to-service `identity.read`).
- **Request**:

  ```json
  {
    "token": "eyJ…"
  }
  ```

- **Response (200)**:

  ```json
  {
    "identity_id": "01HZX…",
    "kc_sub": "f4a8c0…",
    "realm": "platform-customer",
    "user_type": "customer",
    "roles": ["customer"],
    "scopes": ["openid", "profile", "email"],
    "tenant_id": null,
    "status": "active",
    "claims": {
      "name": "Jane Doe",
      "email": "jane@example.com",
      "phone": "+31612345678",
      "locale": "en-US",
      "mfa_enabled": true
    }
  }
  ```

- **Errors**:
  - 400 `VALIDATION_FAILED`
  - 401 `UNAUTHENTICATED`
  - 403 `FORBIDDEN`
  - 404 `NOT_FOUND` (no `identities` row for this sub;
    rare — back-channel `*.created.v1` should have
    created it)

### 1.4 `POST /v1/identities`

- **Purpose**: create a new identity mapping. Used by
  profile services on first reference if the back-channel
  event was lost.
- **Auth**: bearer (service-to-service `identity.write`).
- **Idempotency**: `Idempotency-Key` header required.
- **Request**:

  ```json
  {
    "kc_sub": "f4a8c0…",
    "realm": "platform-customer",
    "user_type": "customer",
    "name": "Jane Doe",
    "email": "jane@example.com",
    "phone": "+31612345678",
    "locale": "en-US"
  }
  ```

- **Response (201)**:

  ```json
  {
    "id": "01HZX…",
    "kc_sub": "f4a8c0…",
    "realm": "platform-customer",
    "user_type": "customer",
    "status": "active",
    "created_at": "2026-07-29T10:42:11.183Z"
  }
  ```

- **Errors**:
  - 400 `VALIDATION_FAILED`
  - 401 `UNAUTHENTICATED`
  - 403 `FORBIDDEN`
  - 409 `CONFLICT` (a row with the same `(kc_sub, realm)`
    exists)
  - 422 `IDEMPOTENCY_KEY_REUSED`

### 1.5 `PATCH /v1/identities/{identity_id}`

- **Purpose**: update claims (name, locale,
  `email_verified`, `phone_verified`).
- **Auth**: bearer (admin `identity.admin`).
- **Request**:

  ```json
  {
    "name": "Jane D.",
    "locale": "nl-NL"
  }
  ```

- **Response (200)**: same as 1.1.
- **Errors**: 400, 401, 403, 404, 409 (row_version
  mismatch).

### 1.6 `POST /v1/identities/{identity_id}/suspend`

- **Purpose**: suspend a user. Required: `reason` (one of
  `fraud`, `payment_failure`, `manual_review`, `security`,
  `legal`).
- **Auth**: bearer (admin `identity.admin`).
- **Idempotency**: `Idempotency-Key` header required.
- **Request**:

  ```json
  {
    "reason": "fraud",
    "note": "Confirmed chargeback pattern",
    "expected_duration_days": 30
  }
  ```

- **Response (200)**:

  ```json
  {
    "id": "01HZX…",
    "status": "suspended",
    "suspended_reason": "fraud",
    "suspended_at": "2026-07-29T10:42:11.183Z"
  }
  ```

- **Errors**: 400, 401, 403, 404, 409 (already suspended
  with a different reason), 422.

### 1.7 `POST /v1/identities/{identity_id}/disable`

- **Purpose**: permanently disable a user (compliance /
  legal hold).
- **Auth**: bearer (admin `identity.admin` or
  `super_admin`).
- **Idempotency**: `Idempotency-Key` required.
- **Request**:

  ```json
  {
    "reason": "legal",
    "note": "Court order"
  }
  ```

- **Response (200)**: as 1.6, with `status: "disabled"`.
- **Errors**: as 1.6.

### 1.8 `POST /v1/identities/{identity_id}/reinstate`

- **Purpose**: re-instate a suspended user.
- **Auth**: bearer (admin `identity.admin`).
- **Idempotency**: `Idempotency-Key` required.
- **Request**:

  ```json
  {
    "note": "Investigation closed, no fault"
  }
  ```

- **Response (200)**: as 1.6, with `status: "active"`.
- **Errors**: 400, 401, 403, 404, 409 (not currently
  suspended).

### 1.9 `POST /v1/identities/{identity_id}/erase`

- **Purpose**: GDPR right-to-erasure. Anonymizes PII,
  preserves `identity_id` and `kc_sub` for referential
  integrity, emits `identity.user.erased.v1`.
- **Auth**: bearer (admin `identity.admin` or
  `super_admin`).
- **Idempotency**: `Idempotency-Key` required.
- **Request**:

  ```json
  {
    "legal_basis": "user_request",
    "note": "GDPR Article 17 request"
  }
  ```

- **Response (200)**:

  ```json
  {
    "id": "01HZX…",
    "status": "erased",
    "erased_at": "2026-07-29T10:42:11.183Z",
    "warnings": []
  }
  ```

  `warnings[]` is populated if there are active financial
  records (the erasure is performed but the financial
  records retain the `identity_id` reference; their
  PII fields are redacted by the owning service).

- **Errors**: 400, 401, 403, 404, 409 (already erased),
  422.

### 1.10 `POST /v1/identities/{identity_id}/logout-everywhere`

- **Purpose**: force-logout all sessions for a user.
- **Auth**: bearer (admin `identity.admin`).
- **Idempotency**: `Idempotency-Key` required.
- **Request**:

  ```json
  {
    "reason": "security",
    "note": "Suspected session theft"
  }
  ```

- **Response (200)**:

  ```json
  {
    "id": "01HZX…",
    "sessions_revoked": 3,
    "revoked_jtis": ["jti-1", "jti-2", "jti-3"]
  }
  ```

- **Errors**: 400, 401, 403, 404.

### 1.11 `GET /admin/v1/identities/{identity_id}/roles`

- **Purpose**: list the realm roles currently assigned to a Keycloak
  user. Used by `admin-service` to render the operator-UI permission
  view and to verify `SUPER_ADMIN` preset membership.
- **Auth**: bearer (admin `platform.admin`).
- **Response (200)**:

  ```json
  {
    "identity_id": "01HZX…",
    "kc_sub": "f4a8c0…",
    "realm": "platform-internal",
    "roles": ["platform.admin", "payment.admin", "trip.admin"],
    "presets": ["SUPER_ADMIN"],
    "evaluated_at": "2026-08-05T12:00:00Z"
  }
  ```

  `presets[]` is computed by intersecting the user's actual role set
  with the platform's preset definitions (currently only `SUPER_ADMIN`;
  see `admin-service/INTEGRATION.md` §1.x `GET /v1/admin/presets`).
- **Errors**: 400, 401, 403, 404.

### 1.12 `POST /admin/v1/identities/{identity_id}/roles/{role}`

- **Purpose**: grant a single realm role to a Keycloak user. The
  recommended path for granting the `SUPER_ADMIN` preset bundle is
  via `admin-service POST /v1/admin/identity/grant-super-admin` (§1
  in `admin-service/INTEGRATION.md`), which fans out to this endpoint
  for each role in the bundle.
- **Auth**:
  - For `role = "platform.super_admin"`: `platform.super_admin` is
    **already required** on the caller; additionally the request MUST
    carry a valid `X-Break-Glass-Cosigner` header (the service returns
    403 `CO_SIGNER_REQUIRED` otherwise).
  - For all other roles: `platform.admin` or the matching
    `<service>.admin` for service-scoped roles.
- **Idempotency**: `Idempotency-Key` required (Keycloak has no
  native grant idempotency).
- **Headers (super-admin grant)**:
  - `X-Audit-Reason: string ≥ 8 chars` (required; recorded in audit).
  - `X-Signature: t=<unix>,v1=<hex>` (HMAC-SHA256 over body +
    timestamp; required for `platform.super_admin` grants).
  - `X-Break-Glass-Cosigner: <uuid>` (required for
    `platform.super_admin` grants; the co-signer MUST be a different
    admin with `platform.super_admin`).
- **Request body**:

  ```json
  {
    "reason_code": "ops-onboarding-#1234",
    "preset": "SUPER_ADMIN"
  }
  ```

  `preset` is optional metadata recorded in `identity.role_assignment_history`
  (see `identity-service/ERD.md` §3.7). It does not affect
  enforcement; the realm role list is the source of truth.
- **Response (200)**: full updated role list (same shape as §1.11).
- **Errors**:
  - 400 `VALIDATION_FAILED` (missing reason / signature / co-signer).
  - 401 `UNAUTHENTICATED`.
  - 403 `FORBIDDEN` / `CO_SIGNER_REQUIRED` / `BREAK_GLASS_REQUIRED` /
    `SIGNATURE_INVALID` / `MFA_REQUIRED` / `IP_NOT_ALLOWED`.
  - 404 `NOT_FOUND` (identity not in realm).
  - 409 `ROLE_ALREADY_ASSIGNED`.
  - 503 `DEPENDENCY_UNAVAILABLE` (Keycloak unreachable).

### 1.13 `DELETE /admin/v1/identities/{identity_id}/roles/{role}`

- **Purpose**: revoke a realm role. Same auth, headers, and
  break-glass rules as §1.12. The `SUPER_ADMIN` preset fan-out lives
  at `admin-service DELETE /v1/admin/identity/revoke-super-admin`.
- **Response (200)**: full updated role list (same shape as §1.11).
- **Errors**: same as §1.12 plus 404 `ROLE_NOT_ASSIGNED`.

## 2. Outbound APIs

| Target | Method | URI | Purpose | Timeout | Retry | Circuit |
|--------|--------|-----|---------|---------|-------|---------|
| Keycloak admin | GET | `/admin/realms/{realm}/users/{id}` | read user | 2s | 2, exp backoff | yes |
| Keycloak admin | PUT | `/admin/realms/{realm}/users/{id}` | update user | 2s | 2, exp backoff | yes |
| Keycloak admin | PUT | `/admin/realms/{realm}/users/{id}/disable-credential-types` | disable credentials | 2s | 2, exp backoff | yes |
| Keycloak admin | POST | `/admin/realms/{realm}/users/{id}/logout` | force-logout | 2s | 2, exp backoff | yes |
| Keycloak admin | DELETE | `/admin/realms/{realm}/users/{id}` | delete user | 2s | 2, exp backoff | yes |
| Keycloak admin | GET | `/admin/realms/{realm}/users/{id}/role-mappings/realm` | list user realm roles (consumed by §1.11) | 2s | 2, exp backoff | yes |
| Keycloak admin | POST | `/admin/realms/{realm}/users/{id}/role-mappings/realm` | grant realm role (consumed by §1.12) | 2s | 2, exp backoff | yes |
| Keycloak admin | DELETE | `/admin/realms/{realm}/users/{id}/role-mappings/realm` | revoke realm role (consumed by §1.13) | 2s | 2, exp backoff | yes |
| Keycloak | GET | `/realms/{realm}/protocol/openid-connect/certs` | JWKS | 2s | 3 | yes |
| `configuration-service` | GET | `/v1/configurations/identity.*` | read config | 500ms | 2 | yes |

## 3. Produced Events

All events use the standard envelope from
`architecture/EVENT_ARCHITECTURE.md`. The producer is
`identity-service`. The partition key is `aggregate_id`
(=`identity_id`).

### 3.1 `identity.user.created.v1`

- **Topic**: `identity.user.created`.
- **Trigger**: a new `identities` row is created.
- **Schema version**: 1.
- **Consumers**: ``customer-service` (cross-persona profile)`, `customer-service`,
  `driver-service`, `courier-service`, ``restaurant-service` (merchant)`,
  `audit-service`, ``reporting-service` (data lake)`.
- **Data**:

  ```json
  {
    "identity_id": "01HZX…",
    "kc_sub": "f4a8c0…",
    "realm": "platform-customer",
    "user_type": "customer",
    "region": "eu-west",
    "tenant_id": null,
    "email_verified": true,
    "phone_verified": true,
    "mfa_enabled": false,
    "occurred_at": "2026-07-29T10:42:11.183Z"
  }
  ```

- **Retry**: outbox, 3 attempts.
- **DLQ**: `identity.user.created.dlq`.

### 3.2 `identity.user.updated.v1`

- **Topic**: `identity.user.updated`.
- **Trigger**: a cached claim changed.
- **Consumers**: ``customer-service` (cross-persona profile)`, `customer-service`,
  `driver-service`, `courier-service`, ``restaurant-service` (merchant)`,
  `notification-service`.
- **Data**: the changed fields (full row not emitted; the
  consumer fetches the current row via REST if needed).
- **Retry / DLQ**: as 3.1.

### 3.3 `identity.user.suspended.v1`

- **Topic**: `identity.user.suspended`.
- **Trigger**: a user is suspended.
- **Consumers**: every service that owns a profile,
  `notification-service`, `fraud-risk-service`,
  `api-gateway`.
- **Data**:

  ```json
  {
    "identity_id": "01HZX…",
    "reason": "fraud",
    "expected_duration_days": 30,
    "suspended_by": "01HZX…",
    "occurred_at": "2026-07-29T10:42:11.183Z"
  }
  ```

- **Retry / DLQ**: as 3.1.

### 3.4 `identity.user.disabled.v1`

- **Topic**: `identity.user.disabled`.
- **Trigger**: a user is disabled.
- **Data**:

  ```json
  {
    "identity_id": "01HZX…",
    "reason": "legal",
    "disabled_by": "01HZX…",
    "occurred_at": "2026-07-29T10:42:11.183Z"
  }
  ```

- **Consumers**: every service that owns a profile,
  ``admin-service` (support module)`, `api-gateway`.
- **Retry / DLQ**: as 3.1.

### 3.5 `identity.user.reinstated.v1`

- **Topic**: `identity.user.reinstated`.
- **Data**:

  ```json
  {
    "identity_id": "01HZX…",
    "reinstated_by": "01HZX…",
    "occurred_at": "2026-07-29T10:42:11.183Z"
  }
  ```

- **Consumers**: every service that owns a profile.
- **Retry / DLQ**: as 3.1.

### 3.6 `identity.user.erased.v1`

- **Topic**: `identity.user.erased`.
- **Data**:

  ```json
  {
    "identity_id": "01HZX…",
    "legal_basis": "user_request",
    "erased_by": "01HZX…",
    "occurred_at": "2026-07-29T10:42:11.183Z"
  }
  ```

- **Consumers**: every service that owns a profile,
  `audit-service`.
- **Retry / DLQ**: as 3.1.

### 3.7 `identity.session.revoked.v1`

- **Topic**: `identity.session.revoked`.
- **Trigger**: a Keycloak session was revoked (logout,
  theft, force).
- **Data**:

  ```json
  {
    "identity_id": "01HZX…",
    "jti": "f4a8c0…",
    "exp": 1753801200,
    "reason": "user_logout",
    "revoked_by": "01HZX…",
    "occurred_at": "2026-07-29T10:42:11.183Z"
  }
  ```

- **Consumers**: `notification-service`,
  `audit-service`, `api-gateway`.
- **Retry / DLQ**: as 3.1.

### 3.8 `identity.role.granted.v1`

- **Topic**: `identity.role.granted`.
- **Trigger**: §1.12 `POST /admin/v1/identities/{id}/roles/{role}`
  succeeds (single role). For a `SUPER_ADMIN` preset fan-out the
  event is emitted 59 times — once per granted role — keyed on the
  granted `role` name.
- **Schema version**: 1.
- **Consumers**: `audit-service`, `admin-service` (to update its
  `SuperAdminGrant` table view and invalidate the operator UI
  permission cache), ``reporting-service` (data lake)`.
- **Data**:

  ```json
  {
    "identity_id": "01HZX…",
    "kc_sub": "f4a8c0…",
    "realm": "platform-internal",
    "role": "platform.super_admin",
    "preset": "SUPER_ADMIN",
    "actor_id": "01HZY…",
    "actor_username": "alice@example.com",
    "cosigner_id": "01HZZ…",
    "break_glass": true,
    "reason_code": "ops-onboarding-#1234",
    "correlation_id": "01HAA…",
    "occurred_at": "2026-08-05T12:00:00Z"
  }
  ```

### 3.9 `identity.role.revoked.v1`

- **Topic**: `identity.role.revoked`.
- **Trigger**: §1.13 succeeds. Same fan-out semantics as 3.8.
- **Schema version**: 1.
- **Consumers**: `audit-service`, `admin-service`, ``reporting-service` (data lake)`.
- **Data**: same as 3.8 with `role` and `preset` fields.

## 4. Consumed Events

### 4.1 `customer.created.v1`

- **Producer**: `customer-service`.
- **Reason**: back-channel — ensure an `identities` row
  exists for the customer (the `identity.user.created.v1`
  may have been emitted before the customer's
  `customer.created.v1`).
- **Handler**: upsert identity mapping if `customer_id`
  not yet set on the row.
- **Deduplication**: idempotent on `identity_id`.
- **Retry**: 3 with backoff.
- **Failure**: DLQ.

### 4.2 `driver.created.v1`

Same as 4.1, for `driver-service`.

### 4.3 `courier.created.v1`

Same as 4.1, for `courier-service`.

### 4.4 `merchant.created.v1`

Same as 4.1, for ``restaurant-service` (merchant)`.

### 4.5 `restaurant.created.v1`

Same as 4.1, for `restaurant-service` (restaurant staff).

### 4.6 `configuration.updated.v1`

- **Producer**: `configuration-service`.
- **Reason**: hot-reload identity config.
- **Handler**: reload in-process config atomically.
- **Deduplication**: configuration version stamp.
- **Retry / Failure**: as 4.1.

## 5. Reliability

- **Timeouts**: 2 s for Keycloak admin calls; 500 ms for
  configuration reads; 30 s statement timeout for DB.
- **Retries**: 3 with exponential backoff (1 s, 2 s, 4 s)
  for Keycloak calls; 2 retries for configuration reads.
- **Circuit breakers**: per Keycloak endpoint; default open
  after 5 failures in 10 s, reset after 30 s with a
  half-open trial.
- **Bulkheads**: per-upstream concurrency cap; default
  100.
- **Outbox**: yes, table `identity.outbox`; poller is
  single-writer per replica (Postgres advisory lock).
- **Inbox**: yes, table `identity.inbox` keyed by
  `event_id`; TTL 24 h.
- **DLQ**: one per topic; retention 30 days.
- **Reconciliation**: a daily job in `reporting-service`
  compares the count of `identity.user.created.v1` events
  to the count of `identities` rows created in the same
  window; drift opens a ticket.

## 6. Correlation IDs

All requests carry `X-Correlation-Id`; emitted events
carry the same in the envelope. The
`identity_audit_log.correlation_id` column links the
action to the originating request and to the downstream
event.

## 7. Distributed Tracing

OpenTelemetry. One root span per request. Spans for
Keycloak admin calls, DB queries, Redis lookups, Kafka
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
| [``reporting-service` (data lake)`](../`reporting-service` (data lake)/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [`api-gateway`](../api-gateway/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [`audit-service`](../audit-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [`configuration-service`](../configuration-service/README.md) | DEGRADABLE | degrade (cache / default / flag) |
| [`courier-service`](../courier-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [`customer-service`](../customer-service/README.md) | CRITICAL | 503 `DEPENDENCY_UNAVAILABLE` |
| [`driver-service`](../driver-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [`fraud-risk-service`](../fraud-risk-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [`ledger-service`](../ledger-service/README.md) | CRITICAL | 503 `DEPENDENCY_UNAVAILABLE` |
| [``restaurant-service` (merchant)`](../`restaurant-service` (merchant)/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [`notification-service`](../notification-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [`payment-service`](../payment-service/README.md) | CRITICAL | 503 `DEPENDENCY_UNAVAILABLE` |
| [`restaurant-service`](../restaurant-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [``admin-service` (support module)`](../`admin-service` (support module)/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [``customer-service` (cross-persona profile)`](../`customer-service` (cross-persona profile)/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |

### Downstream services that depend on this service

| Downstream | Class (from its perspective) |
|---|---|
| [``customer-service` (addresses)`](../`customer-service` (addresses)/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`admin-service`](../admin-service/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`api-gateway`](../api-gateway/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [``restaurant-service` (branch)`](../`restaurant-service` (branch)/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [``notification-service` (provider ACL)`](../`notification-service` (provider ACL)/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`configuration-service`](../configuration-service/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`courier-service`](../courier-service/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`customer-service`](../customer-service/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`driver-service`](../driver-service/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [``configuration-service` (flags)`](../`configuration-service` (flags)/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`file-service`](../file-service/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`fraud-risk-service`](../fraud-risk-service/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [``restaurant-service` (merchant)`](../`restaurant-service` (merchant)/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`notification-service`](../notification-service/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`restaurant-service`](../restaurant-service/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [``restaurant-service` (staff)`](../`restaurant-service` (staff)/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [``admin-service` (support module)`](../`admin-service` (support module)/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [``customer-service` (cross-persona profile)`](../`customer-service` (cross-persona profile)/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [``driver-service` (vehicles)`](../`driver-service` (vehicles)/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [``geolocation-service` (zones)`](../`geolocation-service` (zones)/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |

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

