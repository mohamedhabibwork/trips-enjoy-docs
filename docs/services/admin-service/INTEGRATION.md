# Admin Service — Integration Contract

## 1. Inbound APIs

All endpoints are versioned under `/v1`. Auth: bearer JWT. Errors
use the standard envelope.

### 1.1 `POST /v1/admin/{service}/{action}`

- **Purpose**: Dispatch an action to a target service.
- **Auth**: Bearer JWT. Required role: per-action RBAC
  (e.g. `payment.refund` for a manual refund). Required headers:
  `X-Audit-Reason`. High-value actions additionally require
  `X-Signature`. Super-admin off-hours additionally require
  `X-Break-Glass-Cosigner`.
- **Idempotency**: `Idempotency-Key` required.
- **Request**: depends on the action; example for
  `payment-service.refund`:
  ```json
  {
    "payment_intent_id": "01HZX…",
    "amount_minor": 1000,
    "currency": "EUR",
    "reason": "Customer reported missing item"
  }
  ```
- **Response (200)**:
  ```json
  {
    "action_id": "01HZX…",
    "target_service": "payment-service",
    "action": "refund",
    "result": "success",
    "result_code": "201",
    "response": { ... },
    "correlation_id": "01HZX9C7T0XK2P9F0V6E4B1MZA",
    "completed_at": "2026-07-29T10:42:11.183Z"
  }
  ```
- **Errors**:
  - 400 `AUDIT_REASON_REQUIRED`.
  - 401 / 403 `FORBIDDEN` / `SIGNATURE_INVALID` / `BREAK_GLASS_REQUIRED` /
    `OFF_HOURS_RESTRICTED` / `IP_NOT_ALLOWED` / `MFA_REQUIRED`.
  - 422 `IDEMPOTENCY_KEY_REUSED`.
  - 502 / 504 from the target service (propagated).

### 1.2 `GET /v1/admin/actions`

- **Purpose**: Search the action log.
- **Auth**: Bearer JWT. Required role: `admin.read`.
- **Query params**:
  - `actor_id`, `target_service`, `action`, `target_user_id`,
    `target_resource_id`, `from`, `to`, `result`.
  - `cursor`, `limit` (default 20, max 100).
- **Response (200)**:
  ```json
  {
    "items": [
      {
        "id": "01HZX…",
        "actor_id": "01HZX…",
        "target_service": "payment-service",
        "action": "refund",
        "result": "success",
        "reason": "Customer reported missing item",
        "created_at": "2026-07-29T10:42:11.183Z"
      }
    ],
    "next_cursor": "eyJ…",
    "has_more": true
  }
  ```
- **Errors**: 401 / 403.

### 1.3 `GET /v1/admin/actions/{id}`

- **Purpose**: Read action detail.
- **Auth**: Bearer JWT. Required role: `admin.read`.
- **Response (200)**: full action including `request`, `response`,
  `signature`, `break_glass` fields.
- **Errors**: 401 / 403 / 404.

### 1.4 `POST /v1/admin/actions/{id}/break-glass`

- **Purpose**: Co-sign a pending break-glass request.
- **Auth**: Bearer JWT. Required role: `admin.break_glass`. The
  co-signer MUST differ from the requester.
- **Request**:
  ```json
  { "decision": "approve", "reason": "Verified with security" }
  ```
- **Response (200)**: confirmation.
- **Errors**: 401 / 403 / 404 / 409 `BREAK_GLASS_ALREADY_RESOLVED`.

### 1.5 `GET /v1/admin/permissions`

- **Purpose**: List current user's scopes.
- **Auth**: Bearer JWT.
- **Response (200)**:
  ```json
  {
    "user_id": "01HZX…",
    "roles": [
          "platform.super_admin",
          "admin.admin",
          "api_gateway.admin",
          "audit.admin",
          "configuration.admin",
          "courier.admin",
          "customer.admin",
          "driver.admin",
          "file.admin",
          "food_order.admin",
          "fraud_risk.admin",
          "geolocation.admin",
          "identity.admin",
          "ledger.admin",
          "notification.admin",
          "payment.admin",
          "pricing.admin",
          "reporting.admin",
          "restaurant.admin",
          "search.admin",
          "trip.admin",
          ],
        "role_count": 21,
        "documentation": "docs/services/admin-service/INTEGRATION.md#114-post-v1adminidentitygrant-super-admin"
      }
    ]
  }
  ```

- **Errors**: 401, 403.

### 1.14 `POST /v1/admin/identity/grant-super-admin`

- **Purpose**: Grant the `SUPER_ADMIN` preset to a Keycloak user
  — i.e. `platform.super_admin` + all 20 `<service>.admin` scopes.
  Fan-out is performed by calling
  [`identity-service POST /admin/v1/identities/{id}/roles/{role}`](../identity-service/INTEGRATION.md#112-post-adminv1identitiesidrolesrole)
  21 times (one per role). The fan-out is wrapped in a single
  idempotent `Idempotency-Key`; on partial failure the
  `admin-service` performs compensating revokes for the roles
  that did succeed (see 3.7 `admin.super_admin.granted.v1`).
- **Auth**: Bearer JWT. Role `platform.super_admin`.
- **Required headers** (all five — `SECURITY_ARCHITECTURE.md` 14):
  - `X-Audit-Reason: string ≥ 8 chars` — recorded in audit.
  - `X-Signature: t=<unix>,v1=<hex>` — HMAC-SHA256 over body + timestamp.
  - `X-Break-Glass-Cosigner: <uuid>` — a different admin with `platform.super_admin`.
  - `X-Mfa-Claim: <signed MFA token>` — step-up MFA proof.
  - `Idempotency-Key: <uuid>` — required.
- **Network gates**: caller's IP MUST be on the super-admin
  IP allowlist (separate from the regular admin allowlist); off-hours
  the co-signer is mandatory.
- **Request body**:

  ```json
  {
    "user_id": "01HZX…",
    "preset": "SUPER_ADMIN",
    "reason": "ops-onboarding-#1234",
    "tenant_id": "global"
  }
  ```

- **Response (200)**:

  ```json
  {
    "user_id": "01HZX…",
    "preset": "SUPER_ADMIN",
    "roles_granted": [
      "platform.super_admin",
      "admin.admin",
      "api_gateway.admin",
      "audit.admin",
      "configuration.admin",
      "courier.admin",
      "customer.admin",
      "driver.admin",
      "file.admin",
      "food_order.admin",
      "fraud_risk.admin",
      "geolocation.admin",
      "identity.admin",
      "ledger.admin",
      "notification.admin",
      "payment.admin",
      "pricing.admin",
      "reporting.admin",
      "restaurant.admin",
      "search.admin",
      "trip.admin"
    ],
    "source_request_id": "01HAA…",
    "actor_id": "01HZY…",
    "cosigner_id": "01HZZ…",
    "break_glass": true,
    "occurred_at": "2026-08-05T12:00:00Z"
  }
  ```

- **Errors**:
  - 400 `VALIDATION_FAILED` (missing reason / signature / co-signer / MFA / idempotency key).
  - 401 `UNAUTHENTICATED`.
  - 403 `FORBIDDEN` / `CO_SIGNER_REQUIRED` / `BREAK_GLASS_REQUIRED` / `SIGNATURE_INVALID` / `MFA_REQUIRED` / `IP_NOT_ALLOWED` / `OFF_HOURS_RESTRICTED`.
  - 404 `NOT_FOUND` (user not found in identity-service).
  - 409 `SUPER_ADMIN_ALREADY_GRANTED` (idempotency key collision on the same `user_id`).
  - 422 `BUNDLE_MISMATCH` (preset not in the catalog).
  - 503 `DEPENDENCY_UNAVAILABLE` (identity-service unreachable; compensating rollback begins).

### 1.15 `DELETE /v1/admin/identity/revoke-super-admin`

- **Purpose**: Revoke the `SUPER_ADMIN` preset from a Keycloak
  user. Same auth + headers + break-glass gates as 1.14.
- **Request body**:

  ```json
  {
    "user_id": "01HZX…",
    "preset": "SUPER_ADMIN",
    "reason": "ops-offboarding-#5678",
    "tenant_id": "global"
  }
  ```

- **Response (200)**: same shape as 1.14 with `roles_revoked` instead of `roles_granted`.
- **Errors**: same as 1.14 plus 404 `SUPER_ADMIN_NOT_GRANTED`.

### 1.16 `GET /v1/admin/identity/permissions/{user_id}`

- **Purpose**: List a user's current realm roles + computed
  preset membership + last grant/revoke record per preset.
  Forwards to
  [`identity-service GET /admin/v1/identities/{id}/roles`](../identity-service/INTEGRATION.md#111-get-adminv1identitiesidroles).
- **Auth**: Bearer JWT. Role `platform.admin` (read; no break-glass).
- **Response (200)**: same shape as `identity-service` 1.11
  with two extra fields `last_super_admin_grant_id` and
  `last_super_admin_revoke_id` (the `admin.super_admin_grant.id`
  of the most recent action).
- **Errors**: 401, 403, 404.

### 1.17 Service-request endpoints

The service-request endpoints cover the four Conductor workflows in
[`shared/CONDUCTOR_WORKFLOWS.md`](../../shared/CONDUCTOR_WORKFLOWS.md) 3.5
(access request, change request, service onboarding, time-bounded
alias). All endpoints are synchronous REST triggers; the worker tasks
are async.

#### 1.17.1 `POST /v1/admin/access-requests`

- **Purpose**: Open an access request (for a role/scope). Triggers
  `wf.service_request.access.v1` (per
  [`shared/CONDUCTOR_WORKFLOWS.md`](../../shared/CONDUCTOR_WORKFLOWS.md) 3.5.1).
- **Auth**: Bearer JWT. Authenticated user (any role).
- **Idempotency-Key**: required.
- **Request body**:

  ```json
  {
    "target_role": "<role_name>",
    "scope": "<service-name or '*'>",
    "justification": "<string ≥ 32 chars>",
    "duration_seconds": <integer, optional; defaults to 86400>
  }
  ```

- **Response**: 201 with `{ "request_id": "<UUIDv7>", "status": "open" }`.

#### 1.17.2 `GET /v1/admin/access-requests`

- **Purpose**: List the caller's access requests. Filter by status.
- **Auth**: Bearer JWT. Authenticated user.
- **Response**: 200 with `{ "requests": [{ "request_id", "target_role", "scope", "status", "justification", "opened_at", "approved_at", "expires_at" }] }`.

#### 1.17.3 `GET /v1/admin/access-requests/{id}`

- **Purpose**: Get a specific access request detail + state.
- **Auth**: Bearer JWT. Caller must be the requester OR `platform.admin`.
- **Response**: 200 with the full request row + Conductor workflow run id.

#### 1.17.4 `POST /v1/admin/access-requests/{id}/approve`

- **Purpose**: Approve an access request (advances the Conductor HUMAN TASK).
- **Auth**: Bearer JWT. Role `platform.admin`. The approver must NOT be the requester.
- **Required headers**: `X-Audit-Reason`, `Idempotency-Key`.
- **Response**: 200 with `{ "request_id", "status": "approved", "expires_at" }`.

#### 1.17.5 `POST /v1/admin/access-requests/{id}/deny`

- **Purpose**: Deny an access request.
- **Auth**: Bearer JWT. Role `platform.admin`.
- **Required headers**: `X-Audit-Reason`, `Idempotency-Key`.
- **Request body**: `{ "reason": "<string ≥ 16 chars>" }`.
- **Response**: 200 with `{ "request_id", "status": "denied" }`.

#### 1.17.6 `POST /v1/admin/access-requests/{id}/cancel`

- **Purpose**: Requester cancels their own request.
- **Auth**: Bearer JWT. Caller must be the requester.
- **Required headers**: `Idempotency-Key`.
- **Response**: 200 with `{ "request_id", "status": "cancelled" }`.

#### 1.17.7 `POST /v1/admin/change-requests`

- **Purpose**: Open a change request. Triggers
  `wf.service_request.change.v1`.
- **Auth**: Bearer JWT. Role `platform.admin`.
- **Request body**:

  ```json
  {
    "change_kind": "rollback" | "config_edit" | "feature_flag_toggle" | "service_deploy" | "data_migration",
    "target_service": "<service-name>",
    "target_resource_id": "<resource-id>",
    "justification": "<string ≥ 64 chars>",
    "rollback_plan": "<string ≥ 32 chars>",
    "blast_radius": "low" | "medium" | "high"
  }
  ```

- **Idempotency-Key**: required.
- **Response**: 201 with `{ "request_id", "status": "open" }`.

#### 1.17.8 `POST /v1/admin/service-onboarding-requests`

- **Purpose**: Open a service onboarding request. Triggers
  `wf.service_request.service_onboarding.v1`.
- **Auth**: Bearer JWT. Role `platform.admin`.
- **Request body**:

  ```json
  {
    "service_name": "<string>",
    "version": "<semver>",
    "is_new_service": <boolean>,
    "deployment_runbook_url": "<string>",
    "rollback_runbook_url": "<string>"
  }
  ```

- **Idempotency-Key**: required.
- **Response**: 201 with `{ "request_id", "status": "open" }`.

#### 1.17.9 `POST /v1/admin/access-requests/{id}/alias`

- **Purpose**: Request a time-bounded SUPER_ADMIN alias per
  [`shared/TIME_BOUNDED_ALIASES.md`](../../shared/TIME_BOUNDED_ALIASES.md).
  Triggers `wf.service_request.time_bounded_alias.v1`.
- **Auth**: Bearer JWT. Role `platform.super_admin`. Requires
  `X-Break-Glass-Cosigner` header.
- **Required headers**: `X-Audit-Reason`, `X-Signature`, `X-Break-Glass-Cosigner`, `X-Mfa-Claim`, `Idempotency-Key`.
- **Request body**:

  ```json
  {
    "alias_ttl_seconds": <integer, ≥ 3600 ≤ 1209600>,
    "justification": "<string ≥ 32 chars>",
    "incident_id": "<UUIDv7, optional>"
  }
  ```

- **Response**: 201 with `{ "request_id", "status": "pending_cosign", "expires_at": null }`.
- **Errors**: 409 `ALIAS_ALREADY_ACTIVE` (caller already has an active alias).

### 1.18 Conductor live-state endpoints

Per the Workflow Live State section in
[`MASTER_TASK.md`](../../MASTER_TASK.md) 12, the admin-service exposes a
read-only live-state API for Conductor workflows.

#### 1.18.1 `GET /v1/admin/conductor/workflows/{id}/state`

- **Purpose**: Get live state for a single Conductor workflow run, or
  list live runs across the workflow ID when no run is specified.
- **Auth**: Bearer JWT. Role `platform.admin`.
- **Path params**: `{id}` = the workflow ID (e.g. `wf.phase7.reward_grant.v1`).
- **Query params**:
  - `?run_id=<uuid>` (optional; defaults to listing active runs)
  - `?page=<n>` (default 1)
  - `?page_size=<n>` (default 100, max 500)
  - `?owner_service=<service>` (filter; optional)
  - `?sla_breached=<bool>` (filter; optional)
  - `?date_from=<RFC3339>` (filter; optional)
  - `?date_to=<RFC3339>` (filter; optional)
- **Response**: 200 with `{ "workflow_id", "runs": [{ "run_id", "owner_service", "current_step", "available_actions[]", "sla_timer_status", "actor_role_required", "started_at", "last_updated_at", "run_history_summary" }] }`.
- **Errors**: 404 `WORKFLOW_NOT_FOUND` for unknown workflow ID.

### 1.19 Time-bounded SUPER_ADMIN alias endpoints

See [`shared/TIME_BOUNDED_ALIASES.md`](../../shared/TIME_BOUNDED_ALIASES.md)
for the canonical contract. The endpoints are listed here for
discoverability.

#### 1.19.1 `POST /v1/admin/identity/grant-time-bounded-super-admin`

- **Purpose**: Grant a time-bounded `SUPER_ADMIN` alias. Equivalent to
  `POST /v1/admin/identity/grant-super-admin` with `expires_at` set.
- **Auth**: Bearer JWT. Role `platform.super_admin`. Requires
  `X-Break-Glass-Cosigner` header.
- **Required headers**: `X-Audit-Reason`, `X-Signature`, `X-Break-Glass-Cosigner`, `X-Mfa-Claim`, `Idempotency-Key`.
- **Request body**:

  ```json
  {
    "user_id": "<UUIDv7>",
    "ttl_seconds": <integer, ≥ 3600 ≤ 1209600>,
    "incident_id": "<UUIDv7, optional>"
  }
  ```

- **Response**: 201 with `{ "grant_id", "expires_at", "status": "active" }`.
- **Errors**: 409 `ALIAS_ALREADY_ACTIVE` if the user already has an active alias.

#### 1.19.2 `DELETE /v1/admin/identity/revoke-time-bounded-super-admin`

- **Purpose**: Revoke an active alias before its `expires_at`.
- **Auth**: Bearer JWT. Role `platform.super_admin`.
- **Required headers**: `X-Audit-Reason`, `Idempotency-Key`.
- **Response**: 204 No Content.

#### 1.19.3 `GET /v1/admin/identity/aliases/{user_id}`

- **Purpose**: List the user's active and historical aliases.
- **Auth**: Bearer JWT. Caller must be the user OR `platform.admin`.
- **Response**: 200 with `{ "aliases": [{ "grant_id", "expires_at", "issued_at", "revoked_at", "status" }] }`.

## 2. Outbound APIs

| Target | Method | URI | Purpose | Timeout | Retry | Circuit |
|--------|--------|-----|---------|---------|-------|---------|
| Every target service | per action | per action | dispatch the action | 5s | 1 | yes |
| ``geolocation-service` (zones)` | POST | `/v1/zones/exists` | validate that an origin and destination zone exist before persisting an OD-pair record (FR--024) | 800ms | 2 | yes |
| `pricing-service` | GET | `/v1/admin/pricing/geo-config/{id}` | admin debug fetch (optional; the live path is the async event) | 800ms | 3 | yes |
| `identity-service` | GET | `/admin/v1/identities/{id}/roles` | read a user's roles + computed presets (used by 1.16) | 1s | 2, exp backoff | yes |
| `identity-service` | POST | `/admin/v1/identities/{id}/roles/{role}` | grant a single realm role (consumed by 1.14, fanned out 21 ×) | 1s | 2, exp backoff | yes |
| `identity-service` | DELETE | `/admin/v1/identities/{id}/roles/{role}` | revoke a single realm role (consumed by 1.15, fanned out 21 ×) | 1s | 2, exp backoff | yes |

The target service's API is documented in its own `INTEGRATION.md`.
The admin service is the **caller**; the target service enforces
its own RBAC and signature checks. Defense in depth.

## 3. Produced Events

### 3.1 `admin.action.performed.v1`

- **Producer**: `admin-service`.
- **Topic**: `admin.action.performed`.
- **Trigger**: every action (success or failed).
- **Schema version**: 1.
- **Partition key**: `target_user_id` (when present) or
  `target_resource_id` (when present) or `actor_id`.
- **Schema**:
  ```json
  {
    "event_id": "01HZX9C8W6K0G3V2Y5N1Q4R7PB",
    "event_name": "admin.action.performed.v1",
    "occurred_at": "2026-07-29T10:42:11.183Z",
    "schema_version": 1,
    "producer": "admin-service",
    "tenant_id": "global",
    "correlation_id": "01HZX9C7T0XK2P9F0V6E4B1MZA",
    "aggregate_type": "AdminAction",
    "aggregate_id": "01HZX9C5S3B1L7K0P2F8V4T6YDA",
    "data": {
      "actor_id": "01HZX…",
      "actor_ip": "10.0.0.1",
      "target_service": "payment-service",
      "target_resource_type": "payment_intent",
      "target_resource_id": "01HZX…",
      "target_user_id": "01HZX…",
      "action": "refund",
      "result": "success",
      "reason": "Customer reported missing item",
      "break_glass": false,
      "signature": "t=…,v1=…",
      "started_at": "2026-07-29T10:42:11.000Z",
      "completed_at": "2026-07-29T10:42:11.183Z"
    }
  }
  ```
- **Retry / DLQ**: outbox / `admin.action.performed.dlq`.

### 3.2 `admin.user.suspended.v1`

- **Producer**: this service.
- **Topic**: `platform.admin`.
- **Trigger**: An admin suspends a user.
- **Schema version**: 1.
- **Partition key**: `identity_id`.
- **Consumers**: `audit-service`, `notification-service`.
- **Schema**:

  ```json
  {
    "event_id": "01HZX9C8W6K0G3V2Y5N1Q4R7PB",
    "event_name": "admin.user.suspended.v1",
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
- **DLQ**: `platform.admin.dlq`.


### 3.3 `admin.user.disabled.v1`

- **Producer**: this service.
- **Topic**: `platform.admin`.
- **Trigger**: An admin disables a user.
- **Schema version**: 1.
- **Partition key**: `identity_id`.
- **Consumers**: `audit-service`, ``admin-service` (support module)`.
- **Schema**:

  ```json
  {
    "event_id": "01HZX9C8W6K0G3V2Y5N1Q4R7PB",
    "event_name": "admin.user.disabled.v1",
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
- **DLQ**: `platform.admin.dlq`.


### 3.4 `admin.user.reinstated.v1`

- **Producer**: this service.
- **Topic**: `platform.admin`.
- **Trigger**: An admin reinstates a suspended user.
- **Schema version**: 1.
- **Partition key**: `identity_id`.
- **Consumers**: `audit-service`, `notification-service`.
- **Schema**:

  ```json
  {
    "event_id": "01HZX9C8W6K0G3V2Y5N1Q4R7PB",
    "event_name": "admin.user.reinstated.v1",
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
- **DLQ**: `platform.admin.dlq`.


### 3.5 `admin.configuration.changed.v1`

- **Producer**: this service.
- **Topic**: `platform.admin`.
- **Trigger**: An admin changes a configuration via the admin console.
- **Schema version**: 1.
- **Partition key**: `config_key`.
- **Consumers**: `audit-service`, ``reporting-service` (data lake)`.
- **Schema**:

  ```json
  {
    "event_id": "01HZX9C8W6K0G3V2Y5N1Q4R7PB",
    "event_name": "admin.configuration.changed.v1",
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
- **DLQ**: `platform.admin.dlq`.



### 3.7 `admin.super_admin.granted.v1`

- **Topic**: `admin.super_admin.granted`.
- **Trigger**: 1.14 `POST /v1/admin/identity/grant-super-admin` succeeds.
  One event per grant call (not per role — the 21 per-role
  events live in `identity.role.granted.v1` per
  [`identity-service/INTEGRATION.md`](../identity-service/INTEGRATION.md#38-identityrolegrantedv1)).
- **Schema version**: 1.
- **Consumers**: `audit-service`, `notification-service`
  (pages security on-call — `SECURITY_ARCHITECTURE.md` 14),
  ``reporting-service` (data lake)`.
- **Data**:

  ```json
  {
    "grant_id": "01HBB…",
    "user_id": "01HZX…",
    "preset": "SUPER_ADMIN",
    "roles_granted": ["platform.super_admin", "address.admin", "…"],
    "source_request_id": "01HAA…",
    "actor_id": "01HZY…",
    "actor_username": "alice@example.com",
    "cosigner_id": "01HZZ…",
    "break_glass": true,
    "reason": "ops-onboarding-#1234",
    "signature": "v1=<hex>",
    "correlation_id": "01HAA…",
    "tenant_id": "global",
    "occurred_at": "2026-08-05T12:00:00Z"
  }
  ```

### 3.8 `admin.super_admin.revoked.v1`

- **Topic**: `admin.super_admin.revoked`.
- **Trigger**: 1.15 succeeds.
- **Schema version**: 1.
- **Consumers**: `audit-service`, `notification-service`
  (pages security on-call), ``reporting-service` (data lake)`.
- **Data**: same shape as 3.7 with `roles_revoked` field.

### 3.6 `pricing.geo_config.updated.v1`

- **Producer**: `admin-service`.
- **Topic**: `pricing.geo_config.updated`.
- **Trigger**: every successful CRUD on
  `/v1/admin/pricing/geo-config[...]` (create, PATCH, disable,
  rollback).
- **Partition key**: `geo_config_id` (the new head id).
- **Consumers**: `pricing-service` (refreshes its in-memory hash for
  `pricing.rule_bindings`), ``reporting-service` (data lake)`, `audit-service`.
- **Schema version**: 1.
- **Schema**:
  ```json
  {
    "event_id": "01HZX9C8W6K0G3V2Y5N1Q4R7PB",
    "event_name": "pricing.geo_config.updated.v1",
    "occurred_at": "2026-08-04T10:42:11.183Z",
    "schema_version": 1,
    "producer": "admin-service",
    "tenant_id": "global",
    "correlation_id": "01HZX9C7T0X9F0V6E4B1MZA",
    "aggregate_type": "PricingGeoConfig",
    "aggregate_id": "01HZX9C5S3B1L7K0P2F8V4T6YDA",
    "data": {
      "geo_config_id": "01HZX…",
      "kind": "OD_CORRIDOR",
      "rule_kind": "od_corridor",
      "version": 7,
      "previous_version": 6,
      "action": "update",
      "status": "ACTIVE",
      "priority": 100,
      "value_hash": "sha256:…",
      "effective_from": "2026-08-01T00:00:00Z",
      "effective_to": null,
      "actor_id": "01HZX…",
      "reason": "Schiphol-to-Rotterdam rush-hour surcharge"
    }
  }
  ```
  The payload does NOT include the full `value` JSONB (hashed
  instead) so analytics consumers can correlate without seeing
  operator-side adjustments; `pricing-service` re-fetches the
  current head via its own API if it needs the value.
- **Retry**: outbox poller, 3 attempts.
- **DLQ**: `pricing.geo_config.updated.dlq`.



## 4. Consumed Events

### 4.1 `identity.session.revoked.v1`

- **Producer**: `identity-service`.
- **Reason**: an admin's session is revoked; their in-memory
  permission cache must be invalidated.
- **Handler**: invalidate cache entry for the affected admin.
- **Deduplication / Retry / Failure**: inbox / 3 / DLQ.

### 4.1a `identity.role.granted.v1`

- **Producer**: `identity-service`.
- **Reason**: a Keycloak user's role changed (consumed when
  triggered by `admin-service` 1.14 so this service can update its
  `super_admin_grant` table view and reconcile its own cache).
- **Handler**: upsert `super_admin_grant` view row keyed by
  `source_request_id`; invalidate the operator-UI permission cache
  for any operator whose visible role set changed.
- **Deduplication / Retry / Failure**: inbox keyed by
  `(identity_id, role, occurred_at)` / 3 with exponential backoff / DLQ.

### 4.1b `identity.role.revoked.v1`

- **Producer**: `identity-service`.
- **Reason**: same as 4.1a for revoke (1.15 fan-out).
- **Handler / Deduplication / Retry / Failure**: same as 4.1a.

### 4.2 `customer.suspended.v1`

- **Producer**: `customer-service`.
- **Reason**: An admin views the suspension in the support console.
- **Handler**: Render in timeline.
- **Deduplication / Retry / Failure**: inbox keyed by
  `event_id` / 3 with exponential backoff / DLQ.


### 4.3 `driver.suspended.v1`

- **Producer**: `driver-service`.
- **Reason**: An admin views the suspension.
- **Handler**: Render in timeline.
- **Deduplication / Retry / Failure**: inbox keyed by
  `event_id` / 3 with exponential backoff / DLQ.


### 4.4 `courier.suspended.v1`

- **Producer**: `courier-service`.
- **Reason**: An admin views the suspension.
- **Handler**: Render in timeline.
- **Deduplication / Retry / Failure**: inbox keyed by
  `event_id` / 3 with exponential backoff / DLQ.


### 4.5 `configuration.updated.v1`

- **Producer**: `configuration-service`.
- **Reason**: An admin's permission cache must reload.
- **Handler**: Invalidate cache.
- **Deduplication / Retry / Failure**: inbox keyed by
  `event_id` / 3 with exponential backoff / DLQ.

### 4.6 `chat.message.reported.v1` *(Phase 7.7 — In-App Chat)*

- **Producer**: `chat-service`.
- **Reason**: a participant reported a message in a chat thread
  (trip / food order / delivery). When the reason is `safety`,
  `abuse`, or `illegal`, the support module must open a support
  ticket and surface the chat payload as evidence.
- **Handler**: when `data.reason IN ('safety', 'abuse', 'illegal')`,
  create a `admin.support_tickets` row with category = `data.reason`,
  attach the chat message metadata + a `X-Audit-Reason` link to the
  thread context (the chat-service's `chat.thread.{id}` GET API
  returns the message body for admin tokens; the moderator reviews
  via `/admin/v1/chat/threads/{id}` per
  [`../chat-service/INTEGRATION.md` 5](../chat-service/INTEGRATION.md)).
  For `reason = 'spam'` / `'other'`, log only.
- **Deduplication**: inbox on `event_id`; UNIQUE on `(report_id, ticket_id)`
  if the report is escalated twice.
- **Retry**: 3; failure → DLQ.



## 5. Reliability

- **Timeouts**: HTTP 5s for the target service; DB 30s; Kafka
  publish 5s.
- **Retries**: bounded 3 with exponential backoff + jitter; the
  target service is idempotent on its own `Idempotency-Key`.
- **Circuit breakers**: every outbound; on `CIRCUIT_OPEN`, the
  action fails with 503.
- **Bulkheads**: separate outbound pool per target service.
- **Outbox**: yes.
- **Inbox**: yes.
- **DLQ**: every topic above has a paired DLQ.
- **Reconciliation**: daily job verifies the action log against the
  target services' state.

## 6. Correlation IDs

Every request carries `X-Correlation-Id`; the service returns it in
the response header and embeds it in the event envelope.

## 7. Distributed Tracing

OpenTelemetry: one root span per action; child spans for the target
service call, DB, Redis, Kafka. `traceparent` propagated through
Kafka headers and to the target service. Sample rate 100% for
errors, 10% for successes.

### 3.2 `admin.action.dispatched.v1`

- **Producer**: `admin-service`.
- **Topic**: `admin.action.dispatched`.
- **Trigger**: a high-value action is dispatched (before the
  target service responds). Used for in-flight visibility.
- **Schema version**: 1.
- **Partition key**: `target_resource_id` (when present) or
  `actor_id`.
- **Schema**:
  ```json
  {
    "event_id": "01HZX9C8W6K0G3V2Y5N1Q4R7PB",
    "event_name": "admin.action.dispatched.v1",
    "occurred_at": "2026-07-29T10:42:11.000Z",
    "schema_version": 1,
    "producer": "admin-service",
    "tenant_id": "global",
    "correlation_id": "01HZX9C7T0XK2P9F0V6E4B1MZA",
    "aggregate_type": "AdminAction",
    "aggregate_id": "01HZX9C5S3B1L7K0P2F8V4T6YDA",
    "data": {
      "actor_id": "01HZX…",
      "target_service": "payment-service",
      "action": "refund",
      "reason": "Customer reported missing item"
    }
  }
  ```
- **Retry / DLQ**: outbox / `admin.action.dispatched.dlq`.

### 3.3 `admin.action.failed.v1`

- **Producer**: `admin-service`.
- **Topic**: `admin.action.failed`.
- **Trigger**: a target service returned 4xx / 5xx; the action is
  logged as `failed` and the event is emitted for alerting.
- **Schema version**: 1.
- **Partition key**: `target_service`.
- **Schema**: same envelope as 3.1 with `data.result = "failed"`,
  `data.result_code`, `data.error`.
- **Retry / DLQ**: outbox.

### 4.2 `customer.suspended.v1`

- **Producer**: `customer-service`.
- **Reason**: when a customer is suspended, the admin console must
  block new actions targeting that customer.
- **Handler**: add `customer_id` to a Redis `blocked_targets` set
  with TTL = 24h or until `customer.reinstated.v1`.
- **Deduplication**: inbox on `event_id`.
- **Retry**: 3 with backoff.
- **Failure**: DLQ.

### 4.3 `trip.completed.v1`

- **Producer**: `trip-service`.
- **Reason**: the admin service keeps a denormalized view of recent
  trip completions for context on support actions.
- **Handler**: upsert into `admin.trip_cache` (a small lookup
  table).
- **Deduplication / Retry / Failure**: inbox / 3 / DLQ.

### 4.4 `payment.failed.v1`

- **Producer**: `payment-service`.
- **Reason**: the admin service surfaces failed payments in the
  console for support agents to act on.
- **Handler**: upsert into `admin.payment_failure_cache`.
- **Deduplication / Retry / Failure**: inbox / 3 / DLQ.

---

### 1.20 Role catalog (canonical)

> **Appended 2026-08-07.** The canonical platform role matrix lives
> here, mirroring and extending [`../RECOMMENDATIONS.md` 6.2](../RECOMMENDATIONS.md#62-keycloak-admin-role-hierarchy)
> (the technology-rec table) and [`../../architecture/SECURITY_ARCHITECTURE.md` 14](../../architecture/SECURITY_ARCHITECTURE.md)
> (the break-glass contract). The role-assignment wire-up (who calls
> Keycloak to grant / revoke) is in
> [`../identity-service/INTEGRATION.md` 1.11–1.13](../identity-service/INTEGRATION.md#111-get-adminv1identitiesidroles);
> this section is the **role catalog** itself.

#### 1.20.1 `platform.*` realm roles (9)

These are the platform-wide realm roles defined in Keycloak. A
higher role inherits everything below it unless noted.

| Role | Scope | Default grant path | Min break-glass gate |
|---|---|---|---|
| `platform.super_admin` | All services, all data + secrets (break-glass only) | `POST /v1/admin/identity/grant-super-admin` (1.14) — fan-out to 21 realms via `identity-service` 1.12 | **always**: MFA + cosigner + IP allowlist + signature + idempotency (per 1.14) |
| `platform.admin` | All services, all data, PII included; secrets never | `POST /v1/admin/{service}/{action}` with `X-Audit-Reason` (the operator's home service issues the role via `identity-service`) | none |
| `platform.ops` | All services, operational data; PII via scrubbed view | `admin-service` operator console | none |
| `platform.support` | All services, read with `reason_code`; PII redacted by default | `admin-service` support console; per-ticket reason code | none |
| `platform.finance` | Financial services (`payment`, `wallet`, `ledger`, `restaurant-settlement`) | `admin-service` finance console | none |
| `platform.engineering` | All services, meta only (health, metrics, logs, config) | on-hire automation via `identity-service` | none |
| `platform.data_eng` | All services, read on operational data (no PII) | on-hire automation | none |
| `platform.break_glass` | Cosigner eligibility (the pool of admins who can co-sign `platform.super_admin` grants) | quarterly rotation via `admin.break_glass.cosigner_pool` | n/a (this role is itself the gate) |
| `platform.mfa` | Marker role carried by users who have completed step-up MFA at least once in the session window | `identity-service` mfa callback | n/a |

#### 1.20.2 `<service>.admin` realm roles (20 — the locked SUPER_ADMIN preset)

Per [ADR-0017](../../architecture/adrs/0017-20-service-architecture.md)
the preset is exactly **21 realm roles** total: 1 × `platform.super_admin`
+ 20 × `<service>.admin`. The 38 absorbed predecessor roles
(`address.admin`, `cart.admin`, `branch.admin`, `loyalty.admin`, etc.)
are **not** part of the preset.

The full table lives in
[`../RECOMMENDATIONS.md` 6.2a](../RECOMMENDATIONS.md#62a-super_admin-preset-membership);
the canonical role list lives at `admin-service GET /v1/admin/services`.

| # | Service | `<service>.admin` | In SUPER_ADMIN preset |
|---:|---|---|---|
| 1 | `api-gateway` | `api_gateway.admin` | ✅ |
| 2 | `identity-service` | `identity.admin` | ✅ |
| 3 | `customer-service` | `customer.admin` | ✅ |
| 4 | `driver-service` | `driver.admin` | ✅ |
| 5 | `trip-service` | `trip.admin` | ✅ |
| 6 | `pricing-service` | `pricing.admin` | ✅ |
| 7 | `restaurant-service` | `restaurant.admin` | ✅ |
| 8 | `food-order-service` | `food_order.admin` | ✅ |
| 9 | `courier-service` | `courier.admin` | ✅ |
| 10 | `geolocation-service` | `geolocation.admin` | ✅ |
| 11 | `payment-service` | `payment.admin` | ✅ |
| 12 | `ledger-service` | `ledger.admin` | ✅ |
| 13 | `configuration-service` | `configuration.admin` | ✅ |
| 14 | `notification-service` | `notification.admin` | ✅ |
| 15 | `file-service` | `file.admin` | ✅ |
| 16 | `audit-service` | `audit.admin` | ✅ |
| 17 | `admin-service` | `admin.admin` | ✅ |
| 18 | `reporting-service` | `reporting.admin` | ✅ |
| 19 | `fraud-risk-service` | `fraud_risk.admin` | ✅ |
| 20 | `search-service` | `search.admin` | ✅ |

#### 1.20.3 Per-service `<service>.support` and `<service>.finance` (optional)

Each service MAY additionally declare a `<service>.support` role (read
with `reason_code`) and a `<service>.finance` role (read/write on the
service's financial aspects). Services without financial concerns
(e.g. `geolocation-service`) do not declare `<service>.finance`. The
declarations live in each service's `TECH.md` 10.

#### 1.20.4 Audit event for every grant / revoke

Every `POST /admin/v1/identities/{id}/roles/{role}` (and its fan-out for
`SUPER_ADMIN`) emits one of:

| Event | Trigger |
|---|---|
| `identity.role.granted.v1` | single-role grant |
| `identity.role.revoked.v1` | single-role revoke |
| `admin.super_admin.granted.v1` | SUPER_ADMIN preset grant (after fan-out completes) |
| `admin.super_admin.revoked.v1` | SUPER_ADMIN preset revoke |
| `admin.super_admin_grant.{action}` row | durable audit row in `admin.super_admin_grant` table |

`audit-service` is the system of record; the local `audit_log` in
`admin-service` is a fast local mirror for the operator console
("what changed" view).

### 1.21 Keycloak integration summary

> **Appended 2026-08-07.** Canonical reference for **how** the platform
> wires to Keycloak. The detailed operational mechanics (JWKS rotation,
> claim resolution, refresh-token) live in
> [`../identity-service/INTEGRATION.md` 5](../identity-service/INTEGRATION.md#5-keycloak-integration);
> this section is the **end-to-end map**.

#### 1.21.1 Realm structure

| Realm | Audience | Client ID |
|---|---|---|
| `platform-customer` | end users (rider, diner) | `customer-app` (public + PKCE) |
| `platform-driver` | driver users | `driver-app` (public + PKCE) |
| `platform-courier` | courier users | `courier-app` (public + PKCE) |
| `platform-merchant` | restaurant / branch / staff users | `merchant-portal` (public + PKCE) |
| `platform-internal` | ops / support / finance / engineering staff | `admin-portal` (confidential + service-account) |
| `platform-services` | service-to-service | `identity-service`, `payment-service`, … (one client per service, all confidential) |

Per-realm role assignment is **scoped**: a `<service>.admin` role
granted in `platform-internal` does NOT grant it in `platform-services`.
Cross-realm grants use the `identity-service` mirror table
(`identity_id` ↔ `kc_sub` per realm).

#### 1.21.2 JWKS endpoint + Redis cache

```
client → api-gateway → keycloak /realms/{realm}/protocol/openid-connect/certs
                                          ↓
                                  Redis cache (TTL 1h)
                                          ↓
                                  per-service JWT verifier
```

- JWKS endpoint per realm: `/realms/{realm}/protocol/openid-connect/certs`.
- Redis cache key: `jwks:{realm}` (TTL 1h, refresh-on-401).
- Per-service verifier: Spring Security 7 (`Kotlin`), `coreos/go-oidc v3`
  (`Go`), `authlib` (`Python`).
- Refresh trigger: `identity.session.revoked.v1` clears the
  corresponding key in the api-gateway Redis cache; per-service caches
  refresh on the next 401.

#### 1.21.3 Claim → `identity_id` mirror

Keycloak's `sub` claim is the canonical user id in the issuing realm.
The platform mirrors `sub → identity_id` per realm:

- `platform-customer` → `customer.identity_id`
- `platform-driver` → `driver.identity_id`
- `platform-courier` → `courier.identity_id`
- `platform-merchant` → `merchant.identity_id`
- `platform-internal` → `admin.identity_id`
- `platform-services` → no identity (service-account, no user)

The mirror is owned by `identity-service` (the single source of truth
for `identity_id`); see [`../identity-service/INTEGRATION.md` 1.2](../identity-service/INTEGRATION.md#12-get-v1identitieskc_subsubsrealmrealm)
for the read API.

#### 1.21.4 Group → role mapping

Keycloak groups drive the 9 `platform.*` realm roles. Per-service
`<service>.admin` are **explicit** realm roles (not group-derived) so
that grant / revoke can be precise.

| Keycloak group | Realm roles assigned |
|---|---|
| `super-admin-pool` | `platform.super_admin` + `platform.break_glass` |
| `admin-pool` | `platform.admin` |
| `ops-pool` | `platform.ops` |
| `support-pool` | `platform.support` |
| `finance-pool` | `platform.finance` |
| `engineering-pool` | `platform.engineering` |
| `data-eng-pool` | `platform.data_eng` |

Group membership is managed by `admin-service` operator actions
(1.1–1.4); role assignment is then automatic on next JWT issuance.

#### 1.21.5 MFA step-up for `platform.super_admin`

The Keycloak authentication flow for `platform-internal` requires
step-up MFA for `platform.super_admin` grants. The mechanism:

1. User authenticates via the standard flow → receives a short-lived
   access token (`mfa_step_up: false`).
2. For a `POST /v1/admin/identity/grant-super-admin` (1.14), the
   client requests step-up MFA from Keycloak
   (`/realms/platform-internal/protocol/openid-connect/auth?...&prompt=login`).
3. Keycloak re-authenticates with MFA; the new token carries
   `mfa_step_up: true` AND a fresh `auth_time`.
4. The client sends the step-up token in the `X-Mfa-Claim` header;
   `identity-service 1.12` validates it (and rejects with `403 MFA_REQUIRED`
   if absent or stale).
5. Step-up tokens have a TTL of 60 seconds; off-hours (00:00–06:00
   UTC) the co-signer gate (1.21.6) is mandatory in addition.

#### 1.21.6 Super-admin IP allowlist

The `identity-service` consults the
`identity.super_admin_ip_allowlist` configuration key
(`configuration-service/INTEGRATION.md` 10.12) on every
`platform.super_admin` grant attempt. The Keycloak
Authorization Service policy `policy/super-admin-ip` mirrors the same
list and is enforced by Keycloak itself as a second gate. Updates
to the list go through `admin-service` (RBAC: `platform.super_admin`)
and emit `configuration.updated.v1`.

#### 1.21.7 Break-glass co-signature

Custom OIDC claim `cosigner_id` is set on the request header
`X-Break-Glass-Cosigner` (NOT a JWT claim; it's a service-to-service
header that `identity-service 1.12` validates against the
`identity.break_glass.cosigner_pool` configuration key). The
co-signer MUST be:

- a different user with `platform.super_admin` AND `platform.break_glass`;
- not the requester;
- in the `admin.break_glass.cosigner_pool` (a quarterly-rotated list
  of N+1 eligible co-signers).

The co-signer's `identity_id` is recorded on the
`admin.super_admin_grant` row AND in the
`admin.super_admin.granted.v1` event payload — never in plain log
lines (PII-sensitive).

#### 1.21.8 Refresh-token rotation

Keycloak default (refresh-token rotation on every use; reuse detection
triggers `identity.session.revoked.v1`). `identity-service` mirrors
the `jti` per session to support `POST /v1/identities/{id}/logout-everywhere`
([1.10](../identity-service/INTEGRATION.md#110-post-v1identitiesidentity_idlogout-everywhere)).
Sessions are tracked in Redis (`identity.session.{jti}`, TTL = access
token TTL + 30s slack).

#### 1.21.9 Cross-link summary

| Concern | Owner doc |
|---|---|
| Role catalog (this section) | this 1.20 |
| Role-assignment wire-up | [`../identity-service/INTEGRATION.md` 1.11–1.13](../identity-service/INTEGRATION.md#111-get-adminv1identitiesidroles) |
| JWKS + claim + Keycloak admin API mechanics | [`../identity-service/INTEGRATION.md` 5](../identity-service/INTEGRATION.md#5-keycloak-integration) (newly appended 2026-08-07) |
| Break-glass contract | [`../../architecture/SECURITY_ARCHITECTURE.md` 14](../../architecture/SECURITY_ARCHITECTURE.md) |
| Platform-wide role hierarchy | [`../RECOMMENDATIONS.md` 6.2](../RECOMMENDATIONS.md#62-keycloak-admin-role-hierarchy) |
| SUPER_ADMIN preset membership (locked 21-role catalog) | [`../RECOMMENDATIONS.md` 6.2a](../RECOMMENDATIONS.md#62a-super_admin-preset-membership) |
| Canonical Keycloak architecture | [`../../architecture/KEYCLOAK_ARCHITECTURE.md`](../../architecture/KEYCLOAK_ARCHITECTURE.md) |

### 1.22 BFF admin endpoints for graduated services (end-to-end management)

> **Appended 2026-08-14.** Per
> [`../../DEPLOYMENT_ORDER.md` §8.1](../../DEPLOYMENT_ORDER.md#81-graduate-summary-2026-08-14),
> 9 of 21 active services are **graduated** from docs-only to a
> buildable implementation as of 2026-08-14:
> `configuration-service`, `identity-service`, `audit-service`,
> `api-gateway`, `file-service`, `geolocation-service`,
> `notification-service`, `ledger-service`, `reporting-service`.
>
> This section is the **end-to-end admin management surface** for
> those 9 services, exposed through this BFF. The endpoints wrap
> the per-service `/admin/v1/...` (or `/v1/admin/...`) admin APIs
> so that operators in the admin console have a single URL space,
> uniform audit emission (`admin.action.performed.v1`), uniform
> security gates (RBAC + `X-Audit-Reason` + `Idempotency-Key` +
> `X-Signature` + `X-Break-Glass-Cosigner` + super-admin IP
> allowlist), uniform error envelope, and uniform RBAC inheritance
> from the `<service>.admin` realm role.
>
> **Conventions.**
> - All endpoints below are versioned under `/v1/admin/{service}/...`.
> - The minimum RBAC role on each endpoint is `<service>.admin`
>   unless noted; `platform.admin` and `platform.super_admin`
>   inherit. The same inheritance is documented per-role in
>   [1.20.2](#1202-serviceadmin-realm-roles-20--the-locked-super_admin-preset).
> - Every endpoint emits `admin.action.performed.v1` with
>   `target_service = "<service>"`, `action = "<verb>"`, and the
>   result of the downstream call. The same audit emission is
>   guaranteed for **read** endpoints too, with `data.read = true`
>   and `data.result_filter = "<field_redaction>"` where PII may
>   surface.
> - The platform-wide admin pattern (gateway, headers, idempotency,
>   envelopes, RBAC, IP allowlist, audit emission, isolation
>   policy) is in [`../RECOMMENDATIONS.md` 6](../RECOMMENDATIONS.md#6-admin-endpoints--rbac)
>   and [`../../architecture/SECURITY_ARCHITECTURE.md` 14](../../architecture/SECURITY_ARCHITECTURE.md#14-break-glass-co-signature);
>   this section enumerates **which target endpoint each BFF
>   endpoint wraps**.
> - The endpoints are *added* to the BFF; the target service's own
>   `/admin/v1/...` endpoint remains the canonical record. Both
>   keep working. The BFF exists to centralise the audit / signature
>   / break-glass / RBAC inheritance + give the operator UI one URL
>   space.
> - **Not in scope here:** the 12 stub services
>   (`customer-service`, `driver-service`, `courier-service`,
>   `restaurant-service`, `trip-service`, `food-order-service`,
>   `search-service`, `pricing-service`, `payment-service`,
>   `admin-service`, `fraud-risk-service`, `chat-service`) — when
>   they graduate, a `### 1.23` block will be appended in the
>   same shape (see "Append-not-renumber" in
>   [`../../shared/CONVENTIONS.md`](../../shared/CONVENTIONS.md)).

#### 1.22.1 `configuration-service`

| Method | BFF path | Wraps | Min role | Audit reason | Break-glass |
|---|---|---|---|---|---|
| `GET` | `/v1/admin/configuration/documents/{key}` | [`configuration-service` 1.1](../configuration-service/INTEGRATION.md#11-get-v1documentskey) | `configuration.admin` | optional | — |
| `PUT` | `/v1/admin/configuration/documents/{key}` | [`configuration-service` 1.2](../configuration-service/INTEGRATION.md#12-put-v1documentskey) | `configuration.admin` | **required** | — |
| `GET` | `/v1/admin/configuration/documents/{key}/history` | [`configuration-service` 1.4](../configuration-service/INTEGRATION.md#14-get-v1documentskeyhistory) | `configuration.admin` | optional | — |
| `POST` | `/v1/admin/configuration/documents/{key}/rollback` | [`configuration-service` 1.5](../configuration-service/INTEGRATION.md#15-post-v1documentskeyrollback) | `configuration.admin` | **required** | **required** |
| `GET` | `/v1/admin/configuration/schemas` | [`configuration-service` 1.6](../configuration-service/INTEGRATION.md#16-get-v1schemas) | `configuration.admin` | optional | — |
| `POST` | `/v1/admin/configuration/schemas/{id}/validate` | [`configuration-service` 1.7](../configuration-service/INTEGRATION.md#17-post-v1schemasidvalidate) | `configuration.admin` | optional | — |
| `GET` | `/v1/admin/configuration/key-status/{key}` | [`configuration-service` 1.8](../configuration-service/INTEGRATION.md#18-get-v1keystatuskey) | `configuration.admin` | optional | — |
| `GET` | `/v1/admin/configuration/snapshot/export` | [`configuration-service` 1.9](../configuration-service/INTEGRATION.md#19-get-v1snapshotexport) | `configuration.admin` | optional | — |
| `POST` | `/v1/admin/configuration/snapshot/import` | [`configuration-service` 1.10](../configuration-service/INTEGRATION.md#110-post-v1snapshotimport) | `configuration.admin` | **required** | **required** |
| `GET` | `/v1/admin/configuration/audit-log` | [`configuration-service` 1.11](../configuration-service/INTEGRATION.md#111-get-v1audit-log) | `configuration.admin` | optional | — |

Notes:

- **Read endpoints** (`GET /v1/admin/configuration/documents/...`,
  `/schemas`, `/key-status/{key}`, `/snapshot/export`,
  `/audit-log`) emit `admin.action.performed.v1` with
  `data.read = true`; PII fields in the response are scrubbed per
  [`RECOMMENDATIONS.md` 6.5](../RECOMMENDATIONS.md#65-data-access-by-role-platform-wide)
  unless the caller carries `platform.admin` and passes a
  `reason_code` query parameter (recorded in the audit event).
- **Write endpoints** (`PUT /documents/{key}`,
  `/documents/{key}/rollback`, `/snapshot/import`) require the
  platform-standard headers `X-Audit-Reason`,
  `Idempotency-Key`, and `X-Signature` (HMAC-SHA256 over
  body + timestamp) per
  [`../../architecture/SECURITY_ARCHITECTURE.md` 14](../../architecture/SECURITY_ARCHITECTURE.md#14-break-glass-co-signature).
- **Break-glass** is required for `/rollback` and `/snapshot/import`
  because those operations create new head versions that
  downstream consumers reload live. The break-glass gate is the
  same one that protects `platform.super_admin` (co-signer pool,
  off-hours mandatory).
- **Audit event** payload carries `data.config_key`,
  `data.config_version`, `data.previous_version` so
  `audit-service` and `reporting-service` (data lake) can
  reconstruct the configuration delta without re-fetching the
  document body.
- **The BFF forwards `X-Request-Id`** to `configuration-service`
  unchanged (ADR-0019) and emits the standard 502 / 504 errors
  per [`DOWNSTREAM_ERROR_CATALOG.md` 5](../../architecture/DOWNSTREAM_ERROR_CATALOG.md#5-propagation-rules).

#### 1.22.2 `identity-service`

| Method | BFF path | Wraps | Min role | Audit reason | Break-glass |
|---|---|---|---|---|---|
| `GET` | `/v1/admin/identity/users/{identity_id}` | [`identity-service` 1.1](../identity-service/INTEGRATION.md#11-get-v1identitiesidentity_id) | `identity.admin` | optional | — |
| `GET` | `/v1/admin/identity/users/by-kc-sub/{realm}/{kc_sub}` | [`identity-service` 1.2](../identity-service/INTEGRATION.md#12-get-v1identitieskc_subsubsrealmrealm) | `identity.admin` | optional | — |
| `POST` | `/v1/admin/identity/users` | [`identity-service` 1.3](../identity-service/INTEGRATION.md#13-post-v1identities) | `identity.admin` | **required** | — |
| `PATCH` | `/v1/admin/identity/users/{identity_id}` | [`identity-service` 1.4](../identity-service/INTEGRATION.md#14-patch-v1identitiesidentity_id) | `identity.admin` | **required** | — |
| `POST` | `/v1/admin/identity/users/{identity_id}/logout-everywhere` | [`identity-service` 1.10](../identity-service/INTEGRATION.md#110-post-v1identitiesidentity_idlogout-everywhere) | `identity.admin` | **required** | — |
| `GET` | `/v1/admin/identity/users/{identity_id}/roles` | [`identity-service` 1.11](../identity-service/INTEGRATION.md#111-get-adminv1identitiesidroles) | `identity.admin` | optional | — |
| `POST` | `/v1/admin/identity/users/{identity_id}/roles/{role}` | [`identity-service` 1.12](../identity-service/INTEGRATION.md#112-post-adminv1identitiesidrolesrole) | `identity.admin` | **required** | — |
| `DELETE` | `/v1/admin/identity/users/{identity_id}/roles/{role}` | [`identity-service` 1.13](../identity-service/INTEGRATION.md#113-delete-adminv1identitiesidrolesrole) | `identity.admin` | **required** | — |
| `POST` | `/v1/admin/identity/users/{identity_id}/suspend` | `identity-service` 4.2 event consumer trigger | `identity.admin` | **required** | — |
| `POST` | `/v1/admin/identity/users/{identity_id}/reinstate` | `identity-service` 4.4 event consumer trigger | `identity.admin` | **required** | — |
| `GET` | `/v1/admin/identity/super-admin-ip-allowlist` | [`identity-service` 8.6](../identity-service/INTEGRATION.md#86-super-admin-ip-allowlist) | `platform.admin` | optional | — |
| `PUT` | `/v1/admin/identity/super-admin-ip-allowlist` | configures `identity.super_admin_ip_allowlist` | `platform.super_admin` | **required** | **required** |

Notes:

- The **`/super-admin-ip-allowlist` PUT** is itself a high-value
  mutation (it widens or narrows the super-admin allowlist). It
  goes through the same break-glass / signature / MFA gates as
  `grant-super-admin` (1.14). The BFF writes the new list to
  `configuration-service` (key
  `identity.super_admin_ip_allowlist`) and emits
  `configuration.updated.v1` (which `identity-service` consumes
  to reload its in-memory cache); the durable audit row lives in
  `audit-service` (`audit.admin.admin.v1`).
- The **suspend / reinstate** endpoints publish
  `admin.user.suspended.v1` / `admin.user.reinstated.v1` (see
  [3.2 / 3.4](#32-adminusersuspendedv1) below for the schema) and
  cause `identity-service` to emit its own `identity.user.suspended.v1`
  for downstream consumers (notification, support). The two events
  are correlated by `aggregate_id` (= `identity_id`).
- The **single-role grant / revoke** (`/users/{id}/roles/{role}`)
  endpoints emit `admin.role.granted.v1` /
  `admin.role.revoked.v1` (NEW) on the same `platform.admin`
  topic, with `data.preset = "SINGLE_ROLE"`. The 21-role fan-out
  for `SUPER_ADMIN` is handled by [1.14](#114-post-v1adminidentitygrant-super-admin)
  above; the single-role BFF endpoints exist so that operators
  can grant / revoke one role without going through the
  SUPER_ADMIN preset.
- **The BFF forwards the caller's `X-User-Id`** to
  `identity-service` unchanged (ADR-0019 + the gateway's
  claim-to-X-User-* translator per
  [`../api-gateway/INTEGRATION.md` 4](../api-gateway/INTEGRATION.md#4-claim-translation)),
  so `identity-service` can enforce its own per-user rate limit
  + audit attribution.

#### 1.22.3 `audit-service`

| Method | BFF path | Wraps | Min role | Audit reason | Break-glass |
|---|---|---|---|---|---|
| `POST` | `/v1/admin/audit/search` | [`audit-service` 1.1](../audit-service/INTEGRATION.md#11-post-v1auditsearch) | `audit.admin` | optional | — |
| `POST` | `/v1/admin/audit/verify-chain` | [`audit-service` 1.2](../audit-service/INTEGRATION.md#12-post-v1auditverify-chain) | `audit.admin` | **required** | — |
| `POST` | `/v1/admin/audit/litigation-hold` | [`audit-service` 1.3](../audit-service/INTEGRATION.md#13-post-v1auditlitigation-hold) | `audit.admin` | **required** | — |
| `DELETE` | `/v1/admin/audit/litigation-hold/{id}` | [`audit-service` 1.4](../audit-service/INTEGRATION.md#14-delete-v1auditlitigation-holdid) | `audit.admin` | **required** | **required** |
| `POST` | `/v1/admin/audit/export/run` | [`audit-service` 1.5](../audit-service/INTEGRATION.md#15-post-v1auditexportrun) | `audit.admin` | **required** | — |
| `GET` | `/v1/admin/audit/export/{job_id}` | [`audit-service` 1.6](../audit-service/INTEGRATION.md#16-get-v1auditexportjob_id) | `audit.admin` | optional | — |
| `POST` | `/v1/admin/audit/retention/run` | [`audit-service` 1.7](../audit-service/INTEGRATION.md#17-post-v1auditretentionrun) | `audit.admin` | **required** | **required** |
| `GET` | `/v1/admin/audit/retention/policy` | [`audit-service` 1.8](../audit-service/INTEGRATION.md#18-get-v1auditretentionpolicy) | `audit.admin` | optional | — |
| `GET` | `/v1/admin/audit/integrity` | [`audit-service` 1.9](../audit-service/INTEGRATION.md#19-get-v1auditintegrity) | `audit.admin` | optional | — |
| `POST` | `/v1/admin/audit/dlq/replay` | [`audit-service` 1.10](../audit-service/INTEGRATION.md#110-post-v1auditdlqreplay) | `audit.admin` | **required** | — |
| `GET` | `/v1/admin/audit/topics` | [`audit-service` 1.11](../audit-service/INTEGRATION.md#111-get-v1audittopics) | `audit.admin` | optional | — |

Notes:

- **Litigation-hold DELETE** is break-glass because the row in
  `audit.litigation_hold` is append-only at the DB level (the
  trigger blocks `UPDATE`/`DELETE` per
  [`audit-service/INTEGRATION.md` 1.4](../audit-service/INTEGRATION.md#14-delete-v1auditlitigation-holdid)
  — the BFF uses the **`UPDATE retention_until = NULL` workaround**
  that the service exposes for break-glass operators only).
- **Retention run** is break-glass because it can delete data
  covered by an in-force litigation hold; the BFF rejects the
  call if any active hold covers the time range, with
  `409 HOLD_CONFLICTS`.
- **DLQ replay** writes to `audit.dlq_replay` (the durable
  record of every replay invocation) so the action is itself
  audited; the replayed events flow back through the regular
  consumer path with their original `event_id` (inbox dedup
  ensures idempotency).
- **Audit emission is recursive** — every BFF call on the
  audit-service emits `audit.admin.admin.v1` (because this
  BFF wraps the service), and the audit-service itself emits
  `audit.admin.audit.v1` (because the audit-service processes
  the audit event it just consumed). The two events share
  `data.target_action_id` for traceability.

#### 1.22.4 `api-gateway`

| Method | BFF path | Wraps | Min role | Audit reason | Break-glass |
|---|---|---|---|---|---|
| `GET` | `/v1/admin/api-gateway/health/deep` | [`api-gateway` 1.1](../api-gateway/INTEGRATION.md#11-get-healthdeep) | `platform.engineering` | optional | — |
| `GET` | `/v1/admin/api-gateway/circuit-breakers` | [`api-gateway` 1.2](../api-gateway/INTEGRATION.md#12-get-circuit-breakers) | `platform.engineering` | optional | — |
| `POST` | `/v1/admin/api-gateway/circuit-breakers/{downstream}/reset` | [`api-gateway` 1.3](../api-gateway/INTEGRATION.md#13-post-circuit-breakersdownstreamreset) | `platform.engineering` | **required** | — |
| `POST` | `/v1/admin/api-gateway/routing/reload` | [`api-gateway` 1.4](../api-gateway/INTEGRATION.md#14-post-routingreload) | `platform.admin` | **required** | — |
| `POST` | `/v1/admin/api-gateway/reload` | [`api-gateway` 1.5](../api-gateway/INTEGRATION.md#15-post-adminreload-internal-0000-only) | `platform.admin` | **required** | **required** |
| `POST` | `/v1/admin/api-gateway/redis/flush-revocations` | [`api-gateway` 1.6](../api-gateway/INTEGRATION.md#16-post-redisflush-revocations) | `platform.super_admin` | **required** | **required** |
| `POST` | `/v1/admin/api-gateway/waf/rules/publish` | [`api-gateway` 1.7](../api-gateway/INTEGRATION.md#17-post-wafrulespublish) | `platform.admin` | **required** | **required** |

Notes:

- The BFF forwards the **caller's source IP** to
  `api-gateway POST /v1/admin/api-gateway/reload` because the
  api-gateway's own admin mux binds to `0.0.0.0` only on the
  internal port; the BFF adds an `X-Internal-Auth: <one-shot
  token>` header that the gateway validates against
  `APIGATEWAY_INTERNAL_TOKEN` env (rotated hourly).
- **`/redis/flush-revocations`** wipes the global
  `auth:revoked` set in Redis; this is a security-critical
  reset (it forces every JWT to be re-validated against
  Keycloak JWKS on the next request) and is break-glass +
  super-admin gated. The BFF emits
  `admin.security.revocation_reset.v1` (NEW, schema version 1)
  on the `platform.security` topic so the audit trail captures
  who reset the revocation set.
- **`/waf/rules/publish`** takes a Sigstore-signed ruleset;
  the BFF verifies the signature locally before forwarding to
  the gateway (defense in depth). A failed verification rejects
  with `403 WAF_SIGNATURE_INVALID` and emits
  `admin.security.waf_publish_failed.v1` for security on-call.

#### 1.22.5 `file-service`

| Method | BFF path | Wraps | Min role | Audit reason | Break-glass |
|---|---|---|---|---|---|
| `GET` | `/v1/admin/file/storage-drivers` | [`file-service` 1.10](../file-service/INTEGRATION.md#110-get-v1admindrivers) | `file.admin` | optional | — |
| `POST` | `/v1/admin/file/storage-drivers/{id}/pin` | [`file-service` 1.11](../file-service/INTEGRATION.md#111-post-v1admindriversidpin) | `file.admin` | **required** | — |
| `GET` | `/v1/admin/file/migrations` | [`file-service` 1.12](../file-service/INTEGRATION.md#112-post-v1adminmigrations) | `file.admin` | optional | — |
| `GET` | `/v1/admin/file/migrations/{id}` | [`file-service` 1.13](../file-service/INTEGRATION.md#113-get-v1adminmigrationsid) | `file.admin` | optional | — |
| `POST` | `/v1/admin/file/migrations/{id}/run` | `file-service` migration runner | `file.admin` | **required** | **required** |
| `POST` | `/v1/admin/file/retention/run` | [`file-service` 1.14](../file-service/INTEGRATION.md#114-post-v1adminretentionrun) | `file.admin` | **required** | **required** |

Notes:

- The **driver-pin** operation records a `driver_history` row
  with `change_type = 'pin'`; the BFF surfaces the resulting
  `assignment_id` in the response so operators can correlate
  with `audit.admin.file.v1`.
- **Migration run** is break-glass because a failing migration
  can lock the `file.files` table; the BFF requires a
  co-signer with `platform.admin` and rejects with
  `409 MIGRATION_LOCKED` if a previous run is in flight.
- **Retention run** is break-glass for the same reason as
  audit-service retention (1.22.3): it can delete data covered
  by an in-force legal hold. The BFF cross-checks
  `audit-service GET /v1/admin/audit/litigation-holds?owner=file`
  and rejects with `409 HOLD_CONFLICTS` if any hold covers the
  scope.

#### 1.22.6 `geolocation-service`

| Method | BFF path | Wraps | Min role | Audit reason | Break-glass |
|---|---|---|---|---|---|
| `POST` | `/v1/admin/geolocation/cache/purge` | [`geolocation-service` 1.6](../geolocation-service/INTEGRATION.md#16-post-v1admincachepurge) | `geolocation.admin` | **required** | — |
| `GET` | `/v1/admin/geolocation/providers` | [`geolocation-service` 5.1](../geolocation-service/INTEGRATION.md#51-get-v1adminproviders) | `platform.engineering` | optional | — |
| `GET` | `/v1/admin/geolocation/providers/{vendor_id}` | [`geolocation-service` 5.2](../geolocation-service/INTEGRATION.md#52-get-v1adminprovidersvendor_id) | `platform.engineering` | optional | — |
| `POST` | `/v1/admin/geolocation/providers/{vendor_id}/test` | [`geolocation-service` 5.3](../geolocation-service/INTEGRATION.md#53-post-v1adminprovidersvendor_idtest) | `platform.engineering` | **required** | — |
| `PUT` | `/v1/admin/geolocation/region-chains/{region}/{capability}` | [`geolocation-service` 5.4](../geolocation-service/INTEGRATION.md#54-put-v1adminregion-chainsregioncapability) | `platform.engineering` | **required** | **required** (co-signature per source) |
| `PATCH` | `/v1/admin/geolocation/providers/{vendor_id}` | [`geolocation-service` 5.5](../geolocation-service/INTEGRATION.md#55-patch-v1adminprovidersvendor_id) | `platform.engineering` | **required** | **required** |

Notes:

- **Region-chains PUT** carries the `X-Co-Signature` header
  that `geolocation-service` requires from a *second*
  `platform.engineering` user; the BFF surfaces the co-sign
  requirement in its own header validation (rejects with
  `409 CO_SIGNATURE_MISSING` if absent) so the operator UI
  can prompt for it before the call.
- **Provider PATCH** is break-glass because it can disable a
  provider that other services rely on (file-service for
  static-map URLs, reporting-service for geo-aggregations);
  the BFF cross-checks `health: provider_disabled` via
  `audit-service GET /v1/admin/audit/dependencies?service=geolocation`
  and rejects with `409 DEPENDENCY_IMPACT` if the disable
  would break a downstream service. The cross-check is
  advisory (warns, does not block) for `platform.engineering`
  + break-glass, and mandatory (`409 DEPENDENCY_IMPACT`) for
  `platform.admin` alone.
- The **`geolocation.provider_chain.changed.v1`** event the
  service emits is consumed by the BFF to invalidate its own
  cache of `/providers` + `/region-chains`; the consumer is
  added to the existing 4.5 consumer list (configuration-style
  pub-sub invalidation per the lift-forward map in
  [`DEPLOYMENT_ORDER.md` §8.3](../../DEPLOYMENT_ORDER.md#83-pattern-lift-forward-map)).

#### 1.22.7 `notification-service`

| Method | BFF path | Wraps | Min role | Audit reason | Break-glass |
|---|---|---|---|---|---|
| `POST` | `/v1/admin/notification/templates` | [`notification-service` 1.5](../notification-service/INTEGRATION.md#15-post-v1admintemplates) | `notification.admin` | **required** | — |
| `GET` | `/v1/admin/notification/templates` | [`notification-service` 1.6](../notification-service/INTEGRATION.md#16-get-v1admintemplates) | `notification.admin` | optional | — |
| `PATCH` | `/v1/admin/notification/templates/{id}` | [`notification-service` 1.7](../notification-service/INTEGRATION.md#17-patch-v1admintemplatesid) | `notification.admin` | **required** | — |
| `POST` | `/v1/admin/notification/templates/{id}/submit-for-approval` | [`notification-service` 1.7.a](../notification-service/INTEGRATION.md#17a-post-v1admintemplatesidsubmit-for-approval) | `notification.admin` | **required** | — |
| `POST` | `/v1/admin/notification/templates/{id}/approve` | [`notification-service` 1.7.b](../notification-service/INTEGRATION.md#17b-post-v1admintemplatesidapprove) | `notification.admin` | **required** | — |
| `POST` | `/v1/admin/notification/templates/{id}/publish` | [`notification-service` 1.7.c](../notification-service/INTEGRATION.md#17c-post-v1admintemplatesidpublish-atomic-across-locales) | `notification.admin` | **required** | **required** (atomic-across-locales publish) |
| `GET` | `/v1/admin/notification/templates/{id}/history` | [`notification-service` 1.7.d](../notification-service/INTEGRATION.md#17d-get-v1admintemplatesidhistory) | `notification.admin` | optional | — |
| `POST` | `/v1/admin/notification/suppressions` | [`notification-service` 1.8](../notification-service/INTEGRATION.md#18-post-v1adminsuppressions) | `notification.admin` | **required** | — |
| `GET` | `/v1/admin/notification/suppressions` | [`notification-service` 1.9](../notification-service/INTEGRATION.md#19-get-v1adminsuppressions) | `notification.admin` | optional | — |
| `DELETE` | `/v1/admin/notification/suppressions/{id}` | [`notification-service` 1.10](../notification-service/INTEGRATION.md#110-delete-v1adminsuppressionsid) | `notification.admin` | **required** | — |

Notes:

- **Publish** is break-glass because it atomically publishes
  across all configured locales and is irreversible (every
  template_version_snapshot row is bound to its template
  version per the immutable audit-chain contract in the
  `notification-service` 1.7.c spec).
- The BFF **does NOT** expose the per-provider (`push`,
  `sms`, `email`, `in_app`, `whatsapp`) ACL endpoints
  individually; they are surfaced through
  `/v1/admin/notification/templates/{id}/preview` (a NEW BFF
  endpoint that calls each `ProviderDriver.preview()` for
  dry-run rendering) and audited separately.
- The BFF consumes `notification.template_version.invalidated.v1`
  on the `notification.template_version.invalidated` topic to
  invalidate its own template cache; this is added to the
  existing 4.5 consumer list.

#### 1.22.8 `ledger-service`

| Method | BFF path | Wraps | Min role | Audit reason | Break-glass |
|---|---|---|---|---|---|
| `POST` | `/v1/admin/ledger/journal-entries` | [`ledger-service` 1.1](../ledger-service/INTEGRATION.md#11-post-v1journal-entries) | `ledger.admin` | **required** | — |
| `GET` | `/v1/admin/ledger/journal-entries/{id}` | [`ledger-service` 1.2](../ledger-service/INTEGRATION.md#12-get-v1journal-entriesid) | `ledger.admin` | optional | — |
| `POST` | `/v1/admin/ledger/journal-entries/{id}/reverse` | [`ledger-service` 1.3](../ledger-service/INTEGRATION.md#13-post-v1journal-entriesidreverse) | `ledger.admin` | **required** | **required** |
| `POST` | `/v1/admin/ledger/accounts` | [`ledger-service` 1.4](../ledger-service/INTEGRATION.md#14-post-v1accounts) | `ledger.admin` | **required** | — |
| `POST` | `/v1/admin/ledger/accounts/{id}/lock` | [`ledger-service` 1.5](../ledger-service/INTEGRATION.md#15-post-v1accountsidlock) | `ledger.admin` | **required** | **required** |
| `GET` | `/v1/admin/ledger/trial-balance` | [`ledger-service` 1.6](../ledger-service/INTEGRATION.md#16-get-v1trial-balance) | `ledger.admin` | optional | — |
| `POST` | `/v1/admin/ledger/reconciliation/run` | [`ledger-service` 1.7](../ledger-service/INTEGRATION.md#17-post-v1reconciliationrun) | `ledger.admin` | **required** | — |
| `GET` | `/v1/admin/ledger/reconciliation/{run_id}` | [`ledger-service` 1.8](../ledger-service/INTEGRATION.md#18-get-v1reconciliationrun_id) | `ledger.admin` | optional | — |

Notes:

- **Manual journal entries** (`POST /journal-entries`) require
  `audit_note ≥ 10 chars` per
  [`../../workflows/ACCOUNTING_WORKFLOWS.md`](../../workflows/ACCOUNTING_WORKFLOWS.md);
  the BFF enforces this in addition to the service-level check
  (defense in depth) and rejects with
  `400 AUDIT_NOTE_TOO_SHORT`.
- **Reversal** is break-glass because the original entry is
  immutable (the DB-level append-only trigger per
  ledger-service implementation); the reversal creates a new
  *compensating* entry with `reverses_entry_id` set, never
  an `UPDATE`/`DELETE` on the original.
- **Account lock** is break-glass because the lock affects
  every dependent service that posts to that account; the
  BFF cross-checks `account.open_balance != 0` and rejects
  with `409 ACCOUNT_HAS_OPEN_BALANCE` unless the call is
  `platform.super_admin` + break-glass.
- **Trial balance** is the highest-volume read endpoint; the
  BFF caches the result in Redis for 60s per `tenant_id +
  as_of` to avoid hammering the ledger for a busy admin UI.

#### 1.22.9 `reporting-service`

| Method | BFF path | Wraps | Min role | Audit reason | Break-glass |
|---|---|---|---|---|---|
| `GET` | `/v1/admin/reporting/dashboards/{name}` | [`reporting-service` 1.1](../reporting-service/INTEGRATION.md#11-get-v1dashboardsname) | `reporting.admin` | optional | — |
| `GET` | `/v1/admin/reporting/views/{view_name}` | [`reporting-service` 1.2](../reporting-service/INTEGRATION.md#12-get-v1viewsview_name) | `reporting.admin` | optional | — |
| `POST` | `/v1/admin/reporting/exports/{name}/run` | [`reporting-service` 1.3](../reporting-service/INTEGRATION.md#13-post-v1exportsnamerun) | `reporting.admin` | **required** | — |
| `GET` | `/v1/admin/reporting/exports/{name}/status` | [`reporting-service` 1.4](../reporting-service/INTEGRATION.md#14-get-v1exportsnamestatusjob_id) | `reporting.admin` | optional | — |
| `GET` | `/v1/admin/reporting/reconciliation/drift` | [`reporting-service` 1.5](../reporting-service/INTEGRATION.md#15-get-v1reconciliationdrift) | `reporting.admin` | optional | — |
| `GET` | `/v1/admin/reporting/read-models` | [`reporting-service` 1.6](../reporting-service/INTEGRATION.md#16-get-v1read-models) | `reporting.admin` | optional | — |

Notes:

- **Export run** writes to S3 via `reporting-service`; the BFF
  surfaces the resulting `s3_url` + `s3_etag` in the response
  so operators can copy the artifact path directly.
- The BFF **does NOT** wrap the export schema endpoints
  (`/v1/admin/reporting/exports/{name}/schema` etc.); those
  are surfaceable in a future `### 1.23.10` once the
  `reporting-service` schema registry stabilises (per
  [`reporting-service/PLAN.md` §6.3](../reporting-service/PLAN.md#63-export-schema-registry)).

#### 1.22.10 Cross-service BFF aggregations

In addition to the per-service wrappers above, the admin-service
BFF exposes a small set of **cross-service aggregations** that
fan out across multiple graduated services in one call. These
are the URL space operators actually use for daily triage.

| Method | BFF path | Aggregates | Min role |
|---|---|---|---|
| `GET` | `/v1/admin/services/{service}/overview` | aggregates `/health`, `/config`, `/audit?from=-24h`, `/metrics` (RED), `/read-models` for the named service | `platform.engineering` |
| `GET` | `/v1/admin/services/{service}/config-keys` | aggregates `configuration-service GET /v1/documents/{key}` for the keys owned by the named service (per [`configuration-service/INTEGRATION.md` §10](../configuration-service/INTEGRATION.md#10-key-index)) | `platform.engineering` |
| `GET` | `/v1/admin/audit/cross-service?actor_id=...&from=...&to=...` | fan-out to `audit-service POST /v1/audit/search` for the actor across all `audit.admin.*.v1` topics, then merge | `platform.admin` |
| `POST` | `/v1/admin/security/revoke-everywhere/{identity_id}` | chains `identity-service POST /v1/identities/{id}/logout-everywhere` + `api-gateway POST /redis/flush-revocations` + `notification-service` user-suspended push | `platform.super_admin` + break-glass |

Notes:

- **`/overview`** is the canonical "is this service healthy"
  view surfaced in the operator UI; the BFF caches the merged
  result in Redis for 30s per service.
- **`/config-keys`** reads the `services.<name>.*` key family
  from `configuration-service` (per the per-service index
  documented in [`configuration-service/INTEGRATION.md` §10](../configuration-service/INTEGRATION.md#10-key-index))
  and surfaces them grouped by category.
- **`/audit/cross-service`** is what an investigator uses after
  a security incident: "show me every admin action by this
  actor in the last 24h across every service". The BFF fans
  out to `audit-service` (which is the system of record) and
  de-duplicates on `audit_id` before returning.
- **`/security/revoke-everywhere`** is the panic button: revoke
  the identity, flush the api-gateway revocation set, push a
  push notification to the user's devices. It is the single
  most security-critical endpoint in the BFF and is gated
  behind `platform.super_admin` + break-glass + signature +
  MFA + super-admin IP allowlist (the same gate as
  [1.14](#114-post-v1adminidentitygrant-super-admin)).

#### 1.22.11 Audit + RBAC summary

Every endpoint in this section emits
`admin.action.performed.v1` per [3.1](#31-adminactionperformedv1)
below. The RBAC inheritance chain is:

```
platform.super_admin  ─┐
platform.admin        ─┼─ inherits all <service>.admin below
platform.engineering  ─┤  (for read endpoints only — writes still
platform.finance      ─┤  require the matching <service>.admin)
                       │
                       └─ <service>.admin   (writes on the matching service)
                                       └─ <service>.support (read with reason_code)
                                       └─ <service>.finance (read/write on financial aspects)
```

This matches the table in
[`../RECOMMENDATIONS.md` 6.2](../RECOMMENDATIONS.md#62-keycloak-admin-role-hierarchy)
and the per-service specifics in
[`TECH.md` §10.1](#101-keycloak-admin-roles-accepted).

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
| [`audit-service`](../audit-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [`identity-service`](../identity-service/README.md) | CRITICAL | 503 `DEPENDENCY_UNAVAILABLE` |
| [``admin-service` (support module)`](../admin-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |

### Downstream services that depend on this service

| Downstream | Class (from its perspective) |
|---|---|
| [``customer-service` (addresses)`](../customer-service/README.md) | _see [`SERVICE_ISOLATION.md` 2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`api-gateway`](../api-gateway/README.md) | _see [`SERVICE_ISOLATION.md` 2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`audit-service`](../audit-service/README.md) | _see [`SERVICE_ISOLATION.md` 2](../../architecture/SERVICE_ISOLATION.md)_ |
| [``notification-service` (provider ACL)`](../notification-service/README.md) | _see [`SERVICE_ISOLATION.md` 2](../../architecture/SERVICE_ISOLATION.md)_ |
| [``courier-service` (dispatch)`](../courier-service/README.md) | _see [`SERVICE_ISOLATION.md` 2](../../architecture/SERVICE_ISOLATION.md)_ |
| [``payment-service` (courier earnings)`](../payment-service/README.md) | _see [`SERVICE_ISOLATION.md` 2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`courier-service`](../courier-service/README.md) | _see [`SERVICE_ISOLATION.md` 2](../../architecture/SERVICE_ISOLATION.md)_ |
| [``courier-service` (tracking)`](../courier-service/README.md) | _see [`SERVICE_ISOLATION.md` 2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`customer-service`](../customer-service/README.md) | _see [`SERVICE_ISOLATION.md` 2](../../architecture/SERVICE_ISOLATION.md)_ |
| [``courier-service` (delivery)`](../courier-service/README.md) | _see [`SERVICE_ISOLATION.md` 2](../../architecture/SERVICE_ISOLATION.md)_ |
| [``driver-service` (dispatch)`](../driver-service/README.md) | _see [`SERVICE_ISOLATION.md` 2](../../architecture/SERVICE_ISOLATION.md)_ |
| [``driver-service` (availability)`](../driver-service/README.md) | _see [`SERVICE_ISOLATION.md` 2](../../architecture/SERVICE_ISOLATION.md)_ |
| [``payment-service` (driver earnings)`](../payment-service/README.md) | _see [`SERVICE_ISOLATION.md` 2](../../architecture/SERVICE_ISOLATION.md)_ |
| [``driver-service` (incentives)`](../driver-service/README.md) | _see [`SERVICE_ISOLATION.md` 2](../../architecture/SERVICE_ISOLATION.md)_ |
| [``driver-service` (location)`](../driver-service/README.md) | _see [`SERVICE_ISOLATION.md` 2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`driver-service`](../driver-service/README.md) | _see [`SERVICE_ISOLATION.md` 2](../../architecture/SERVICE_ISOLATION.md)_ |
| [``geolocation-service` (ETA/routing)`](../geolocation-service/README.md) | _see [`SERVICE_ISOLATION.md` 2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`file-service`](../file-service/README.md) | _see [`SERVICE_ISOLATION.md` 2](../../architecture/SERVICE_ISOLATION.md)_ |
| [``payment-service` (food saga)`](../payment-service/README.md) | _see [`SERVICE_ISOLATION.md` 2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`fraud-risk-service`](../fraud-risk-service/README.md) | _see [`SERVICE_ISOLATION.md` 2](../../architecture/SERVICE_ISOLATION.md)_ |
| _…and 19 more_ | |

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
| `wf.onboarding.driver.v1` | admin_service_manual_approval (HUMAN TASK, 24h SLA) | `driver:{id}:admin:approval` |
| `wf.onboarding.courier.v1` | admin_service_manual_approval (HUMAN TASK, 24h SLA) | `courier:{id}:admin:approval` |


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
