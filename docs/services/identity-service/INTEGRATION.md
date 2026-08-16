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
  see `admin-service/INTEGRATION.md` 1.x `GET /v1/admin/presets`).
- **Errors**: 400, 401, 403, 404.

### 1.12 `POST /admin/v1/identities/{identity_id}/roles/{role}`

- **Purpose**: grant a single realm role to a Keycloak user. The
  recommended path for granting the `SUPER_ADMIN` preset bundle is
  via `admin-service POST /v1/admin/identity/grant-super-admin` (1
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
  (see `identity-service/ERD.md` 3.7). It does not affect
  enforcement; the realm role list is the source of truth.
- **Response (200)**: full updated role list (same shape as 1.11).
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
  break-glass rules as 1.12. The `SUPER_ADMIN` preset fan-out lives
  at `admin-service DELETE /v1/admin/identity/revoke-super-admin`.
- **Response (200)**: full updated role list (same shape as 1.11).
- **Errors**: same as 1.12 plus 404 `ROLE_NOT_ASSIGNED`.

## 2. Outbound APIs

| Target | Method | URI | Purpose | Timeout | Retry | Circuit |
|--------|--------|-----|---------|---------|-------|---------|
| Keycloak admin | GET | `/admin/realms/{realm}/users/{id}` | read user | 2s | 2, exp backoff | yes |
| Keycloak admin | PUT | `/admin/realms/{realm}/users/{id}` | update user | 2s | 2, exp backoff | yes |
| Keycloak admin | PUT | `/admin/realms/{realm}/users/{id}/disable-credential-types` | disable credentials | 2s | 2, exp backoff | yes |
| Keycloak admin | POST | `/admin/realms/{realm}/users/{id}/logout` | force-logout | 2s | 2, exp backoff | yes |
| Keycloak admin | DELETE | `/admin/realms/{realm}/users/{id}` | delete user | 2s | 2, exp backoff | yes |
| Keycloak admin | GET | `/admin/realms/{realm}/users/{id}/role-mappings/realm` | list user realm roles (consumed by 1.11) | 2s | 2, exp backoff | yes |
| Keycloak admin | POST | `/admin/realms/{realm}/users/{id}/role-mappings/realm` | grant realm role (consumed by 1.12) | 2s | 2, exp backoff | yes |
| Keycloak admin | DELETE | `/admin/realms/{realm}/users/{id}/role-mappings/realm` | revoke realm role (consumed by 1.13) | 2s | 2, exp backoff | yes |
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
- **Trigger**: 1.12 `POST /admin/v1/identities/{id}/roles/{role}`
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
- **Trigger**: 1.13 succeeds. Same fan-out semantics as 3.8.
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

---

## 8. Keycloak integration

> **Appended 2026-08-07.** The operational mechanics of how this
> service talks to Keycloak. The end-to-end role-catalog overview
> lives in [`../admin-service/INTEGRATION.md` 1.20–1.21](../admin-service/INTEGRATION.md#120-role-catalog-canonical);
> this section is the **wire-up detail** (per-realm client setup,
> claim resolution, JWKS rotation handling, group → role mapping,
> MFA, IP allowlist, co-signature, refresh-token, error envelope).

### 8.1 Realm structure and per-realm client setup

| Realm | Client ID | Client auth | Purpose |
|---|---|---|---|
| `platform-customer` | `identity-service-customer` | confidential + `client_credentials` | mirror user lifecycle (`identity.user.created.v1` etc.) |
| `platform-driver` | `identity-service-driver` | confidential + `client_credentials` | mirror driver users |
| `platform-courier` | `identity-service-courier` | confidential + `client_credentials` | mirror courier users |
| `platform-merchant` | `identity-service-merchant` | confidential + `client_credentials` | mirror merchant users |
| `platform-internal` | `identity-service-internal` | confidential + `client_credentials` | mirror admin users (the role-assignment realm) |
| `platform-services` | `identity-service-services` | confidential + `client_credentials` | service-to-service; **no users**, only clients |

Each realm's client has:

- **Service-account roles**: `realm-management` query + `view-users` +
  `manage-users` + `manage-realm` (for the `platform-internal` client,
  used to grant / revoke `<service>.admin` and `platform.super_admin`).
- **Audience**: the realm's `account` client (so JWTs issued to
  `identity-service` are accepted by services in that realm).
- **Token TTL**: access 600s, refresh 1800s.

`client_secret` is stored in **Vault** at
`secret/identity-service/{realm}/client_secret` and rotated quarterly
by the platform Vault job.

### 8.2 Claim → `identity_id` resolution

Keycloak issues a JWT with the following claims used by this service:

| Claim | Source | Used for |
|---|---|---|
| `sub` | realm user id | join key to `identity.identities.kc_sub` per realm |
| `email`, `email_verified` | user profile | cached PII fields |
| `phone`, `phone_verified` | user profile | cached PII fields |
| `name` | user profile | cached PII field |
| `realm_access.roles` | realm role mapping | cached role list (used by `GET /v1/identities/{id}` 1.1) |
| `mfa_enabled` | user attribute | cached boolean |
| `mfa_step_up` | session-level | required header for `platform.super_admin` grants (1.12) |
| `cosigner_id` | **NOT** a JWT claim — header `X-Break-Glass-Cosigner` | break-glass validation (1.12) |

The resolution flow on every authenticated inbound request:

```
JWT → Keycloak JWKS verify → decode claims → identity_id = mirror.lookup(sub)
                                                              │
                                                              ├── found  → attach identity_id to request context
                                                              └── missing → 401 UNAUTHENTICATED
```

`identity_id` is **never** derived from the JWT subject directly; it
is always read from the mirror table. This is the source of truth
for stable cross-realm references (the platform guarantees
`identity_id` is never recycled even on erasure).

### 8.3 JWKS rotation handling

JWKS keys rotate on the Keycloak side without warning. The
`api-gateway` caches the JWKS per realm in Redis (`jwks:{realm}`,
TTL 3600s). The per-service JWT verifiers (Spring Security 7 in
Kotlin, `coreos/go-oidc v3` in Go, `authlib` in Python) follow the
cache.

On a `401 UNAUTHENTICATED` with `error=invalid_token` and a key id
(`kid`) not in the local cache:

1. Service refreshes the JWKS from the api-gateway Redis cache.
2. If still missing, the api-gateway re-fetches from Keycloak.
3. If still missing after re-fetch, the api-gateway emits
   `identity.jwks.rotation.failed.v1` and the platform on-call is paged.

The per-service JWKS cache is **not** invalidated on every
`identity.session.revoked.v1` (that event is per-session, not
per-key). Keycloak's `kid` rotation triggers a refresh; per-session
revocation triggers a token re-check.

### 8.4 Group → role mapping

The 9 `platform.*` realm roles are **group-derived** (auto-assigned
on login via Keycloak's group → role mapper). The 20 `<service>.admin`
realm roles are **explicit** (granted only via the
`identity-service 1.12` endpoint or the `admin-service 1.14`
fan-out).

| Keycloak group | Realm role assigned | Membership managed by |
|---|---|---|
| `super-admin-pool` | `platform.super_admin`, `platform.break_glass` | `admin-service` operator (RBAC: existing `platform.super_admin`) |
| `admin-pool` | `platform.admin` | on-hire automation |
| `ops-pool` | `platform.ops` | on-hire automation |
| `support-pool` | `platform.support` | on-hire automation |
| `finance-pool` | `platform.finance` | on-hire automation |
| `engineering-pool` | `platform.engineering` | on-hire automation |
| `data-eng-pool` | `platform.data_eng` | on-hire automation |

Group membership is durable in Keycloak; role assignment is the
auto-derived effect on next JWT issuance.

### 8.5 MFA step-up

The standard `platform-internal` flow has MFA optional. For a
`platform.super_admin` grant (1.12) MFA is **mandatory**. The
mechanism:

1. Client attempts the grant with the standard access token →
   `identity-service` checks `mfa_step_up` claim → if absent, returns
   `403 MFA_REQUIRED`.
2. Client requests step-up from Keycloak
   (`/realms/platform-internal/protocol/openid-connect/auth?prompt=login`).
3. Keycloak re-authenticates with MFA; the new token carries
   `mfa_step_up: true` AND a fresh `auth_time`.
4. Client retries with `X-Mfa-Claim: <step-up token>`.
5. `identity-service` validates the step-up token's `auth_time` is
   within 60s of the grant attempt; rejects with `403 MFA_REQUIRED`
   otherwise.

Off-hours (`00:00–06:00 UTC`) the co-signer gate (8.6) is mandatory
in addition to step-up MFA.

### 8.6 Super-admin IP allowlist

`identity-service` reads the `identity.super_admin_ip_allowlist`
configuration key on every `platform.super_admin` grant attempt. The
list is a JSON array of CIDR ranges. Updates go through `admin-service`
(1.1–1.4) and emit `configuration.updated.v1`. The same list is
mirrored as a Keycloak Authorization Service policy
(`policy/super-admin-ip`) so Keycloak itself enforces it as a
second gate (defense in depth). Off-allowlist calls return
`403 IP_NOT_ALLOWED`.

### 8.7 Break-glass co-signature

The `X-Break-Glass-Cosigner` header carries a `cosigner_id` (a
`user_id` of a different admin). `identity-service` validates:

1. `cosigner_id != requester_id` — the cosigner MUST be a different
   person.
2. `cosigner_id` has `platform.super_admin` AND
   `platform.break_glass` in the realm-role list at the time of the
   grant attempt.
3. `cosigner_id` is in the `identity.break_glass.cosigner_pool`
   configuration key (a quarterly-rotated N+1 list).

Failure modes: `403 CO_SIGNER_REQUIRED` (header missing),
`403 COSIGNER_NOT_ELIGIBLE` (cosigner lacks the role or pool
membership), `403 COSIGNER_IS_REQUESTER` (self-co-sign attempt).

The cosigner's identity is recorded on the `admin.super_admin_grant`
row and in the `admin.super_admin.granted.v1` event payload
(`cosigner_id`). Audit-service exposes it via
`GET /admin/v1/audit?actor=...&endpoint=...grant-super-admin`.

### 8.8 Refresh-token rotation

Keycloak default: refresh tokens rotate on every use; reuse triggers
forced logout and emits `identity.session.revoked.v1` with
`reason=token_reuse_detected`.

`identity-service` mirrors each issued token's `jti` in Redis
(`identity.session.{jti}`, TTL = access_token_ttl + 30s slack) to
support `POST /v1/identities/{id}/logout-everywhere` (1.10) which
deletes all `jti`s for the user. Revoked `jti`s are added to a
denylist (`identity.session.revoked.{jti}`, TTL = original
refresh_token_ttl) consulted on every `introspect` (1.3) and
`GET /v1/identities/{id}` (1.1).

### 8.9 Per-realm `client_credentials` rotation

The `client_secret` for each of the six realm clients is rotated
quarterly by the platform Vault job:

1. Vault generates a new secret; stores it at
   `secret/identity-service/{realm}/client_secret_next`.
2. `identity-service` Vault-watcher picks it up; performs a
   client_credentials grant with the NEW secret to verify;
   on success, the secret becomes the active one (the OLD secret
   remains valid for 24h for grace).
3. After 24h, the OLD secret is deleted from Vault.
4. The rotation event is recorded in `audit-service` as
   `identity.client_secret.rotated.v1`.

A failed rotation (step 2) triggers `identity.client_secret.rotation_failed.v1`
and pages the platform on-call.

### 8.11 Auto-seed and Swagger defaults (appended 2026-08-14; extended 2026-08-14)

> **Appended 2026-08-14, extended 2026-08-14.** Operational behaviour of the
> `KeycloakSeeder` (`apps/identity-service/src/main/kotlin/.../integration/keycloak/KeycloakSeeder.kt`)
> plus the seeder-aware `OpenApiConfiguration` (`apps/identity-service/src/main/kotlin/.../config/OpenApiConfiguration.kt`).
> Both read the same `SeedSpec` bean (`SeedCatalog.kt`) so the realm
> graph stays the single source of truth.

> **Appended 2026-08-14.** Operational behaviour of the
> `KeycloakSeeder` (`apps/identity-service/src/main/kotlin/.../integration/keycloak/KeycloakSeeder.kt`)
> plus the seeder-aware `OpenApiConfiguration` (`apps/identity-service/src/main/kotlin/.../config/OpenApiConfiguration.kt`).
> Both read the same `SeedSpec` bean (`SeedCatalog.kt`) so the realm
> graph stays the single source of truth.

When `identity.keycloak.seed.enabled=true` (the `dev` profile default
as of 2026-08-14; `stg` and `prod` default `false`), the seeder
runs on startup and **idempotently** provisions:

- **7 realms** (`master` is created by Keycloak; we add
  `platform-customer`, `platform-driver`, `platform-courier`,
  `platform-staff`, `platform-internal`, `platform-services`).
- **6 channel clients** (`web-customer`, `mobile-customer`,
  `web-driver`, `mobile-driver`, `web-courier`, `mobile-courier`)
  on the customer/driver/courier realms, plus 4 staff/internal
  confidential clients (`web-restaurant`, `web-merchant`,
  `web-support`, `web-admin`). All use PKCE S256 and
  `isStandardFlowEnabled=true`; only the public web/mobile clients
  disable service accounts.
- **21 service clients** (one per bounded-context service) on
  `platform-services`, each with `<prefix>.read`, `<prefix>.write`,
  `<prefix>.admin` client roles.
- **Realm roles**: the canonical `<prefix>.admin` set in
  `platform-internal` (20 services) plus the 11 platform support
  roles (`admin`, `super_admin`, `platform.admin`,
  `platform.super_admin`, `identity.admin`, plus
  `support_agent_l1`/`l2`/`l3`, `operations`, `finance`,
  `fraud_reviewer`), and `<prefix>.svc` per service in
  `platform-services`.
- **A `platform-claims` client scope on every realm** with protocol
  mappers wired to project the canonical claims from
  [`../../architecture/KEYCLOAK_ARCHITECTURE.md`](../../architecture/KEYCLOAK_ARCHITECTURE.md)
  §"Scopes and Claims" (`kc_sub`, `user_type`, `tenant_id`,
  `region`, `device_id`, `session_id`, `amr`, `email_verified`,
  `phone_verified`). The scope is attached to each realm's
  `defaultDefaultClientScopes`, so every issued token carries them.
- **A `service-claims` client scope on `platform-services`** with
  three protocol mappers per service (the 20 active bounded-context
  services plus `identity-service`, totaling 21): `<service>.scopes`,
  `<service>.level`, `<service>.tenant`. The scope is attached to
  `platform-services` `defaultDefaultClientScopes` and the underlying
  realm roles (`<prefix>.read`, `<prefix>.write`, `<prefix>.admin`,
  `<prefix>.support`) are **promoted to realm roles in `platform-services`**
  (previously they existed only as client roles on each `<service>-service`
  client). Per-service claims give every service-to-service token a
  precomputed authorization payload so the request never needs a Keycloak
  round-trip to evaluate `<service>.level >= 2`.
- **A super-admin user** in `platform-internal` with the locked
  21-entry preset (1 × `platform.super_admin` + 20 × `<service>.admin`).
  Username and password are read from
  `IDENTITY_KEYCLOAK_SUPER_ADMIN_USERNAME` /
  `IDENTITY_KEYCLOAK_SUPER_ADMIN_PASSWORD`.
- **Per-realm dev users** for integration testing:
  `customer@trips-enjoy.com`, `driver@trips-enjoy.com`,
  `courier@trips-enjoy.com`, `restaurant-staff@trips-enjoy.com`,
  `merchant-staff@trips-enjoy.com`, `support@trips-enjoy.com`,
  `finance@trips-enjoy.com`. All share the password from
  `IDENTITY_KEYCLOAK_SEED_DEFAULT_PASSWORD` (default
  `H@bib1998`; the seeder logs a one-time `WARN` if the
  default literal is in use).
- **Service-account grants**: each `<service>-service` client on
  `platform-services` receives the `identity-service` `identity.read`
  client role, so services can call `GET /v1/identities/{id}`.
- **Dev-user per-service grants**: each of the 7 per-realm dev users
  has a mirror in `platform-services` with the per-service role bundle
  declared in `SeedCatalog.devUsers.serviceRoles`. For example
  `driver@trips-enjoy.com` carries `driver-service.read/.write`,
  `trip.read`, `geolocation-service.read/.write`, and
  `notification.read`, so integration tests can validate the
  `<service>.scopes` / `.level` claims end-to-end. The super-admin
  user gets the full `<prefix>.read/.write/.admin/.support` set in
  `platform-services` for all 21 services.

When the seeder is enabled, the OpenAPI contract at
`/openapi.json` (and Swagger UI at `/docs`) is augmented with:

- a `Server` URL pointing at `{baseUrl}/realms/platform-services`
  (the runtime issuer realm, configurable via
  `identity.keycloak.default-realm`),
- one `oauth2` `authorizationCode` `SecurityScheme` per channel
  client (`kc-platform-customer-web-customer`,
  `kc-platform-driver-mobile-driver`, …), so Swagger UI's
  "Authorize" dropdown lets you log in as each persona,
- a `tags` entry per seeded realm (7 tags total), with the
  default-realm tag flagged via `x-seed-default=true`.

When the seeder is disabled, the OpenAPI bean falls back to the
minimal `bearerAuth` shape so the contract still renders.

#### Testing

`KeycloakSeederIT` and `KeycloakSeederIdempotencyIT` (under
`apps/identity-service/src/test/kotlin/com/trips_enjoy/identity/integration/keycloak/`)
exercise the seeder against a real Keycloak Testcontainer; both
are gated on `RUN_KEYCLOAK_IT=true` because Testcontainers Keycloak
takes ~30 s for first-boot. `OpenApiConfigurationTest` (under
`apps/identity-service/src/test/kotlin/com/trips_enjoy/identity/config/`)
covers the Swagger surface as a pure unit test and runs on every
`./gradlew test`.

### 8.12 Per-service claim contract (extended 2026-08-14)

> **Extended 2026-08-14.** Tokens issued in the `platform-services` realm
> carry three claims per bounded-context service. These are emitted by
> `oidc-script-based-property-mapper` protocol mappers on the
> `service-claims` client scope (see §8.11). Authorization across every
> service-to-service endpoint can therefore check the JWT directly
> without calling Keycloak.

For each `<service>-service` (21 in total):

| Claim | Type | Source | Example |
|---|---|---|---|
| `<service>.scopes` | string[] | user's realm-role membership for `<prefix>.{read,write,admin,support}` | `["trip.read","trip.write"]` |
| `<service>.level` | int (0..4) | highest held role (read=1, write=2, admin=3, support=4) | `2` |
| `<service>.tenant` | string | first realm role matching `tenant:<service>:<id>` (absent ⇒ empty string) | `01HABC…` |

#### Mapper implementation

```javascript
// <service>.scopes — oidc-script-based-property-mapper
var roles = (context.accessToken.getOtherClaims().get('realm_access') || {}).roles || [];
var allowed = ['<prefix>.read','<prefix>.write','<prefix>.admin','<prefix>.support'];
exports = roles.filter(r => allowed.indexOf(r) >= 0);

// <service>.level — oidc-script-based-property-mapper
var roles = (context.accessToken.getOtherClaims().get('realm_access') || {}).roles || [];
var level = 0;
if (roles.indexOf('<prefix>.read') >= 0) level = Math.max(level, 1);
if (roles.indexOf('<prefix>.write') >= 0) level = Math.max(level, 2);
if (roles.indexOf('<prefix>.admin') >= 0) level = Math.max(level, 3);
if (roles.indexOf('<prefix>.support') >= 0) level = Math.max(level, 4);
exports = String(level);
```

#### Authorization pattern

Instead of calling `GET /admin/realms/platform-services/roles` and
matching locally, services can evaluate:

```python
required_level = 2  # write
if jwt_claims.get(f"{service}.level", 0) >= required_level:
    ...
```

or, when finer-grained scope membership is needed:

```kotlin
val scopes = jwt.getClaimAsStringList("${service}.scopes") ?: emptyList()
if ("${prefix}.write" in scopes) authorize()
```

#### Tenant scoping

`<service>.tenant` is the first realm role whose name starts with
`tenant:<service>:`. The seeder doesn't grant tenant roles today;
runtime `identity-service` 1.12 grants them on per-merchant onboarding
flow (lives in admin-service, not in this service). A user holding
no tenant role for a given service has `<service>.tenant = ""`.

#### Catalog

The canonical triple lives in `SeedCatalog.serviceClaims` (21 entries,
one per service) and is consumed by both the seeder (idempotent mapper
installation) and the OpenAPI generator (info description references
`<service>.scopes`, `<service>.level`, `<service>.tenant`). When a
new bounded-context service is added, append its name to
`SeedCatalog.services` and a fresh `SeedServiceClaim.canonicalFor(...)`
entry is generated automatically.

### 8.13 Topology modes (single-realm default, multi-realm opt-in) (appended 2026-08-14)

> **Appended 2026-08-14.** The `KeycloakSeeder` supports two realm
> topologies, selected by `IDENTITY_KEYCLOAK_TOPOLOGY`. Dev / CI defaults
> to `single-realm` so a fresh developer runs `./gradlew bootRun` and
> gets a fully provisioned platform in one realm (`platform-dev` by
> default). Stg / prod default to `multi-realm` — the documented 6-realm
> split. Both modes share the same `SeedSpec` bean; the seeder reads
> `SeedSpec.servicesRealm` + `SeedSpec.adminRealm` instead of hardcoded
> realm names.

| Topology | `IDENTITY_KEYCLOAK_TOPOLOGY` | Realm count | `servicesRealm` | `adminRealm` | `devRealmName` | Use case |
|---|---|---|---|---|---|---|
| `single-realm` (default) | unset or `single-realm` | 1 (`platform-dev`) | `platform-dev` | `platform-dev` | `platform-dev` | local dev, CI |
| `multi-realm` | `multi-realm` | 6 | `platform-services` | `platform-internal` | `platform-dev` (unused) | stg, prod |

Optional escape-hatch env vars (`IDENTITY_KEYCLOAK_SERVICES_REALM_NAME`,
`IDENTITY_KEYCLOAK_ADMIN_REALM_NAME`) let operators split the services
realm away from the admin realm even in single-realm mode (e.g. for
testing a tenant-isolation failure or staging a migration).

#### Single-realm mode specifics

When `topology=single-realm`:

- One realm (`platform-dev` by default) holds every realm role
  (union of the 5 multi-realm per-realm role sets + the 21 × 5
  promoted per-service realm roles) and every client (10 channel
  clients + 21 service clients).
- All 7 dev users (including `admin@inovoria.com`) live in
  the single realm with their per-service role bundle intact.
- The `service-claims` client scope is attached to the single realm
  and emits the same 63 protocol mappers (3 × 21) as multi-realm
  mode, so `<service>.scopes` / `<service>.level` / `<service>.tenant`
  claims resolve identically.

#### Multi-realm mode specifics (the legacy shape)

When `topology=multi-realm`:

- 6 realms: `platform-customer`, `platform-driver`, `platform-courier`,
  `platform-staff`, `platform-internal`, `platform-services`.
- Each user-realm realm (`platform-customer` etc.) holds its own set
  of channel clients + per-realm realm roles.
- The `servicesRealm` (`platform-services`) holds the 21 service
  clients + the per-service promoted realm roles + the `service-claims`
  scope.
- The `adminRealm` (`platform-internal`) holds the super-admin user
  with the canonical 21-entry preset + the per-service admin realm
  roles.

#### Switching topologies

Per `uber-docs-append-not-renumber`, both modes are documented in this
single section; switching between them is an operator action:

1. `IDENTITY_KEYCLOAK_TOPOLOGY=multi-realm ./gradlew bootRun` — flips
   the seeder to the 6-realm shape; idempotent on existing realms.
2. `IDENTITY_KEYCLOAK_TOPOLOGY=single-realm ./gradlew bootRun` —
   collapses to one realm; **destructive** if the operator also sets
   `IDENTITY_KEYCLOAK_DEV_REALM_NAME` to a name that doesn't yet
   exist (creates a fresh `platform-dev`); leave the existing 6
   realms untouched.

The seeder logs its active topology at boot:

```
INFO  Keycloak seeder topology: single-realm (servicesRealm=platform-dev, adminRealm=platform-dev)
```

Mismatch detection (e.g. `topology=single-realm` but
`IDENTITY_KEYCLOAK_DEFAULT_REALM=platform-services` — a common
copy-paste error) is operator-visible via the boot log; the seeder
doesn't validate the two settings against each other.

### 8.14 Seeder token-expiry retry (appended 2026-08-14)

> **Appended 2026-08-14.** Keycloak 24 dev-mode issues 60-second access
> tokens with **no refresh-token** for the `admin-cli` password grant.
> The seeder runs for 3-5 minutes (installing 130 realm roles + 37
> clients + 63 protocol mappers + 8 users via REST round-trips), so a
> token always expires mid-run. `KeycloakSeeder.withFreshClient { }`
> detects the `401 invalid_token` response Keycloak emits, closes the
> stale admin client, opens a fresh one via `KeycloakBuilder.build()`,
> and retries the operation once. Any other status (404, 500, 403,
> `401 invalid_client`) propagates unchanged — we don't retry on
> missing resources or credentials problems.

#### Operator guidance

- The seeder **always completes** on a fresh Keycloak, even with the
 60-second default access-token TTL. Operators see one INFO line per
 expiry: `Keycloak admin token expired mid-seeder; reopening client and
 retrying the operation (attempt 2/2).`
- For a quieter run, start the local Keycloak with a 30-minute TTL
 (see `apps/identity-service/.env.example`):
 ```
 docker run --rm -d --name kc -p 8181:8080 \
   -e KEYCLOAK_ADMIN=admin -e KEYCLOAK_ADMIN_PASSWORD=admin \
   -e KC_SPI_ADMIN_AUTH_ACCESS_TOKEN_LIFESPAN=1800 \
   quay.io/keycloak/keycloak:24.0 start-dev
 ```
- The retry helper is **additive** — no behavior change in the success
 path; the seeder still uses `password` grant (no `client_credentials`
 fallback because `admin-cli` is public in dev-mode Keycloak and
 rejects `client_credentials` with `invalid_client`).
- `KeycloakSeederReauthTest` (under
 `apps/identity-service/src/test/.../integration/keycloak/`) covers the
 `isTokenExpired` predicate with 7 unit assertions: 401+invalid_token
 returns true; 404, 403, 500, 401+invalid_client, and bare WAE all
 return false. The full retry-loop end-to-end coverage lives in
 `KeycloakSeederSingleRealmIT` (gated `RUN_KEYCLOAK_IT=true`).

### 8.10 Cross-link summary

| Concern | Owner doc |
|---|---|
| Role catalog (9 `platform.*` + 20 `<service>.admin`) | [`../admin-service/INTEGRATION.md` 1.20](../admin-service/INTEGRATION.md#120-role-catalog-canonical) |
| Role-assignment endpoints (1.11–1.13) | this file |
| End-to-end Keycloak summary | [`../admin-service/INTEGRATION.md` 1.21](../admin-service/INTEGRATION.md#121-keycloak-integration-summary) |
| Break-glass contract | [`../../architecture/SECURITY_ARCHITECTURE.md` 14](../../architecture/SECURITY_ARCHITECTURE.md) |
| Platform-wide role hierarchy | [`../RECOMMENDATIONS.md` 6.2](../RECOMMENDATIONS.md#62-keycloak-admin-role-hierarchy) |
| Canonical Keycloak architecture | [`../../architecture/KEYCLOAK_ARCHITECTURE.md`](../../architecture/KEYCLOAK_ARCHITECTURE.md) |
| Configuration keys this service reads | [9 below](#9-configuration-keys-consumed) |

---

## 9. Configuration keys consumed

> **Appended 2026-08-07.** The `identity.*` key family this service
> reads from `configuration-service` at runtime. The canonical
> per-service key index lives in
> [`../configuration-service/INTEGRATION.md` 10.12](../configuration-service/INTEGRATION.md#1012-identity-service).

| Key | Type | Default | Purpose |
|---|---|---|---|
| `identity.session.refresh_token_ttl_seconds` | int | 1800 | mirrors Keycloak's refresh-token TTL |
| `identity.session.access_token_ttl_seconds` | int | 600 | mirrors Keycloak's access-token TTL |
| `identity.mfa.required_for_roles` | string[] | `["platform.super_admin"]` | which realm roles require step-up MFA |
| `identity.erasure.preserve_financial_years` | int | 7 | how long financial records retain the `identity_id` reference after erasure |
| `identity.super_admin_ip_allowlist` | string[] | (empty) | CIDR allowlist for `platform.super_admin` grants (8.6) |
| `identity.break_glass.cosigner_pool` | string[] (UUIDs) | (empty) | quarterly-rotated pool of eligible co-signers (8.7) |
| `identity.introspect.cache_ttl_seconds` | int | 60 | in-memory cache TTL for token-introspect results (1.3) |
| `identity.role_grant.idempotency_ttl_seconds` | int | 86400 | how long a `platform.super_admin` grant fan-out is idempotent on `Idempotency-Key` |
| `identity.session.denylist_ttl_seconds` | int | 86400 | how long revoked `jti`s remain on the denylist (8.8) |

Update mechanism: `configuration.updated.v1` triggers a hot-reload
of the in-memory cache (TTL 30s, push-invalidate via
`configuration-service/INTEGRATION.md` 3.1).

---

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
| [`courier-service`](../courier-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [`customer-service`](../customer-service/README.md) | CRITICAL | 503 `DEPENDENCY_UNAVAILABLE` |
| [`driver-service`](../driver-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [`fraud-risk-service`](../fraud-risk-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [`ledger-service`](../ledger-service/README.md) | CRITICAL | 503 `DEPENDENCY_UNAVAILABLE` |
| [``restaurant-service` (merchant)`](../restaurant-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [`notification-service`](../notification-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [`payment-service`](../payment-service/README.md) | CRITICAL | 503 `DEPENDENCY_UNAVAILABLE` |
| [`restaurant-service`](../restaurant-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [``admin-service` (support module)`](../admin-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [``customer-service` (cross-persona profile)`](../customer-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |

### Downstream services that depend on this service

| Downstream | Class (from its perspective) |
|---|---|
| [``customer-service` (addresses)`](../customer-service/README.md) | _see [`SERVICE_ISOLATION.md` 2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`admin-service`](../admin-service/README.md) | _see [`SERVICE_ISOLATION.md` 2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`api-gateway`](../api-gateway/README.md) | _see [`SERVICE_ISOLATION.md` 2](../../architecture/SERVICE_ISOLATION.md)_ |
| [``restaurant-service` (branch)`](../restaurant-service/README.md) | _see [`SERVICE_ISOLATION.md` 2](../../architecture/SERVICE_ISOLATION.md)_ |
| [``notification-service` (provider ACL)`](../notification-service/README.md) | _see [`SERVICE_ISOLATION.md` 2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`configuration-service`](../configuration-service/README.md) | _see [`SERVICE_ISOLATION.md` 2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`courier-service`](../courier-service/README.md) | _see [`SERVICE_ISOLATION.md` 2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`customer-service`](../customer-service/README.md) | _see [`SERVICE_ISOLATION.md` 2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`driver-service`](../driver-service/README.md) | _see [`SERVICE_ISOLATION.md` 2](../../architecture/SERVICE_ISOLATION.md)_ |
| [``configuration-service` (flags)`](../configuration-service/README.md) | _see [`SERVICE_ISOLATION.md` 2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`file-service`](../file-service/README.md) | _see [`SERVICE_ISOLATION.md` 2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`fraud-risk-service`](../fraud-risk-service/README.md) | _see [`SERVICE_ISOLATION.md` 2](../../architecture/SERVICE_ISOLATION.md)_ |
| [``restaurant-service` (merchant)`](../restaurant-service/README.md) | _see [`SERVICE_ISOLATION.md` 2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`notification-service`](../notification-service/README.md) | _see [`SERVICE_ISOLATION.md` 2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`restaurant-service`](../restaurant-service/README.md) | _see [`SERVICE_ISOLATION.md` 2](../../architecture/SERVICE_ISOLATION.md)_ |
| [``restaurant-service` (staff)`](../restaurant-service/README.md) | _see [`SERVICE_ISOLATION.md` 2](../../architecture/SERVICE_ISOLATION.md)_ |
| [``admin-service` (support module)`](../admin-service/README.md) | _see [`SERVICE_ISOLATION.md` 2](../../architecture/SERVICE_ISOLATION.md)_ |
| [``customer-service` (cross-persona profile)`](../customer-service/README.md) | _see [`SERVICE_ISOLATION.md` 2](../../architecture/SERVICE_ISOLATION.md)_ |
| [``driver-service` (vehicles)`](../driver-service/README.md) | _see [`SERVICE_ISOLATION.md` 2](../../architecture/SERVICE_ISOLATION.md)_ |
| [``geolocation-service` (zones)`](../geolocation-service/README.md) | _see [`SERVICE_ISOLATION.md` 2](../../architecture/SERVICE_ISOLATION.md)_ |

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
Workers are colocated in this service's binary; SDK: **conductor-node v1.x**.

| Workflow ID | Tasks owned | Idempotency-Key namespace |
|---|---|---|
| Workflow ID | Tasks owned | Idempotency-Key namespace |
|---|---|---|
| `wf.onboarding.driver.v1` | identity_service_kyc_start + identity_service_document_verify | `driver:{id}:kyc:*` |
| `wf.onboarding.courier.v1` | identity_service_kyc_start + identity_service_document_verify | `courier:{id}:kyc:*` |


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
