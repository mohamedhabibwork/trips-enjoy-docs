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
    "roles": ["admin"],
    "scopes": ["payment.refund", "configuration.write", ...],
    "is_super_admin": false,
    "ip_allowlisted": true,
    "mfa_satisfied": true
  }
  ```
- **Errors**: 401.

### 1.6 `POST /v1/admin/pricing/geo-config`

- **Purpose**: Create a per-location or OD-pair pricing override record.
- **Auth**: Bearer JWT. Required scope: `pricing.admin`.
- **Idempotency**: `Idempotency-Key` required.
- **Request body (OD-pair)**:
  ```json
  {
    "kind": "OD_CORRIDOR",
    "city_id": "amsterdam",
    "origin_zone_id": "01HZX…AMS",
    "destination_zone_id": "01HZX…RTM",
    "ride_type": "economy",
    "rule_kind": "od_corridor",
    "value": { "multiplier_adjustment": "1.15" },
    "priority": 100,
    "effective_from": "2026-08-01T00:00:00Z",
    "effective_to": null,
    "reason": "Schiphol-to-Rotterdam rush-hour surcharge (Q3 review)"
  }
  ```
- **Behavior**: this service calls
  ``geolocation-service` (zones) POST /v1/zones/exists` for each side; a missing
  zone is 422 `ZONE_UNKNOWN`. Ambiguous priority/scope is
  422 `GEO_OVERRIDE_AMBIGUOUS`. On success, emits
  `pricing.geo_config.updated.v1` (partition key `geo_config_id`)
  in the same transaction as the row write.
- **Errors**: 400, 401, 403, 422 (zone unknown / ambiguous /
  reason too short), 503.

### 1.7 `GET /v1/admin/pricing/geo-config/{id}`

- **Purpose**: Read a single geo-config record (current head).
- **Auth**: Bearer JWT. Scope `pricing.admin`.
- **Response (200)**: the record JSON. The response does NOT
  include the history rows (separate endpoint).
- **Errors**: 401, 403, 404 `GEO_CONFIG_NOT_FOUND`.

### 1.8 `PATCH /v1/admin/pricing/geo-config/{id}`

- **Purpose**: Update a record. Creates a new version.
- **Auth**: Bearer JWT. Scope `pricing.admin`.
- **Idempotency**: `Idempotency-Key` required.
- **Behavior**: appends a new history row, updates the head.
  Emits `pricing.geo_config.updated.v1` with the new `version`.

### 1.9 `POST /v1/admin/pricing/geo-config/{id}/disable`

- **Purpose**: Soft-disable (`effective_to = now()`, `status =
  RETIRED`). Reversible by creating a new record.
- **Behavior**: emits `pricing.geo_config.updated.v1` with the new
  status; downstream `pricing-service` removes the binding from its
  in-memory hash.

### 1.10 `POST /v1/admin/pricing/geo-config/{id}/rollback`

- **Purpose**: Roll back to a prior version (creates a new head
  pointing at the prior payload; never UPDATE/DELETE).
- **Auth**: Bearer JWT. Scope `pricing.admin` + break-glass co-sign.
- **Request body**: `{ "to_version": 7, "reason": "..." }`.
- **Behavior**: appends a `rollback` history row; the head is
  replaced with a copy of the target version; emits
  `pricing.geo_config.updated.v1` with the new `version`.

### 1.11 `GET /v1/admin/pricing/geo-config?kind=...&status=...`

- **Purpose**: List geo-config records (paginated by
  `created_at DESC`, cursor-based, page_size ≤ 100).
- **Auth**: Bearer JWT. Scope `pricing.admin`.

### 1.12 `GET /v1/admin/services`

- **Purpose**: Service catalog. Returns the **58-service**
  inventory with the admin scopes each service accepts (per each
  service's `TECH.md` §10.1) and each service's `SUPER_ADMIN`
  preset membership (per §10.7 of each `TECH.md`). The catalog is
  the source of truth for what `POST /v1/admin/identity/grant-super-admin`
  will grant.
- **Auth**: Bearer JWT. Role `platform.admin` (read; no break-glass).
- **Response (200)**:

  ```json
  {
    "services": [
      {
        "name": "payment-service",
        "directory": "payment-service",
        "db_schema": "payment",
        "admin_scopes": [
          "platform.super_admin",
          "platform.admin",
          "platform.finance",
          "payment.admin",
          "payment.finance",
          "payment.support"
        ],
        "super_admin_preset_role": "payment.admin",
        "tech_doc": "docs/services/payment-service/TECH.md"
      },
      ...
    ],
    "preset_count": 1,
    "updated_at": "2026-08-05T12:00:00Z"
  }
  ```

- **Errors**: 401, 403.

### 1.13 `GET /v1/admin/presets`

- **Purpose**: List the available permission presets. Currently
  exactly one: `SUPER_ADMIN` = `platform.super_admin` + all 58
  `<service>.admin` scopes (per `docs/services/RECOMMENDATIONS.md` §6.2).
- **Auth**: Bearer JWT. Role `platform.admin`.
- **Response (200)**:

  ```json
  {
    "presets": [
      {
        "name": "SUPER_ADMIN",
        "description": "Platform super admin: full operational and PII access to all 58 services.",
        "roles": [
          "platform.super_admin",
          "address.admin",
          "admin.admin",
          "analytics.admin",
          "api_gateway.admin",
          "audit.admin",
          "branch.admin",
          "cart.admin",
          "checkout.admin",
          "communication_gateway.admin",
          "configuration.admin",
          "courier.admin",
          "courier_dispatch.admin",
          "courier_earnings.admin",
          "courier_tracking.admin",
          "customer.admin",
          "delivery.admin",
          "dispatch.admin",
          "driver.admin",
          "driver_availability.admin",
          "driver_earnings.admin",
          "driver_incentive.admin",
          "driver_location.admin",
          "eta_routing.admin",
          "feature_flag.admin",
          "file.admin",
          "food_order.admin",
          "food_payment_integration.admin",
          "fraud_risk.admin",
          "geolocation.admin",
          "identity.admin",
          "inventory.admin",
          "ledger.admin",
          "loyalty.admin",
          "menu.admin",
          "merchant.admin",
          "notification.admin",
          "payment.admin",
          "pricing.admin",
          "promotion.admin",
          "reporting.admin",
          "restaurant.admin",
          "restaurant_order_mgmt.admin",
          "restaurant_settlement.admin",
          "restaurant_staff.admin",
          "review_rating.admin",
          "ride_history.admin",
          "ride_payment_integration.admin",
          "ride_request.admin",
          "ride_safety.admin",
          "scheduled_ride.admin",
          "search.admin",
          "support.admin",
          "tax.admin",
          "trip.admin",
          "user_profile.admin",
          "vehicle.admin",
          "wallet.admin",
          "zone.admin"
        ],
        "role_count": 59,
        "documentation": "docs/services/admin-service/INTEGRATION.md#114-post-v1adminidentitygrant-super-admin"
      }
    ]
  }
  ```

- **Errors**: 401, 403.

### 1.14 `POST /v1/admin/identity/grant-super-admin`

- **Purpose**: Grant the `SUPER_ADMIN` preset to a Keycloak user
  — i.e. `platform.super_admin` + all 58 `<service>.admin` scopes.
  Fan-out is performed by calling
  [`identity-service POST /admin/v1/identities/{id}/roles/{role}`](../identity-service/INTEGRATION.md#112-post-adminv1identitiesidrolesrole)
  59 times (one per role). The fan-out is wrapped in a single
  idempotent `Idempotency-Key`; on partial failure the
  `admin-service` performs compensating revokes for the roles
  that did succeed (see §3.7 `admin.super_admin.granted.v1`).
- **Auth**: Bearer JWT. Role `platform.super_admin`.
- **Required headers** (all five — `SECURITY_ARCHITECTURE.md` §14):
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
      "address.admin",
      "… (59 total)"
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
  user. Same auth + headers + break-glass gates as §1.14.
- **Request body**:

  ```json
  {
    "user_id": "01HZX…",
    "preset": "SUPER_ADMIN",
    "reason": "ops-offboarding-#5678",
    "tenant_id": "global"
  }
  ```

- **Response (200)**: same shape as §1.14 with `roles_revoked` instead of `roles_granted`.
- **Errors**: same as §1.14 plus 404 `SUPER_ADMIN_NOT_GRANTED`.

### 1.16 `GET /v1/admin/identity/permissions/{user_id}`

- **Purpose**: List a user's current realm roles + computed
  preset membership + last grant/revoke record per preset.
  Forwards to
  [`identity-service GET /admin/v1/identities/{id}/roles`](../identity-service/INTEGRATION.md#111-get-adminv1identitiesidroles).
- **Auth**: Bearer JWT. Role `platform.admin` (read; no break-glass).
- **Response (200)**: same shape as `identity-service` §1.11
  with two extra fields `last_super_admin_grant_id` and
  `last_super_admin_revoke_id` (the `admin.super_admin_grant.id`
  of the most recent action).
- **Errors**: 401, 403, 404.

## 2. Outbound APIs

| Target | Method | URI | Purpose | Timeout | Retry | Circuit |
|--------|--------|-----|---------|---------|-------|---------|
| Every target service | per action | per action | dispatch the action | 5s | 1 | yes |
| ``geolocation-service` (zones)` | POST | `/v1/zones/exists` | validate that an origin and destination zone exist before persisting an OD-pair record (FR--024) | 800ms | 2 | yes |
| `pricing-service` | GET | `/v1/admin/pricing/geo-config/{id}` | admin debug fetch (optional; the live path is the async event) | 800ms | 3 | yes |
| `identity-service` | GET | `/admin/v1/identities/{id}/roles` | read a user's roles + computed presets (used by §1.16) | 1s | 2, exp backoff | yes |
| `identity-service` | POST | `/admin/v1/identities/{id}/roles/{role}` | grant a single realm role (consumed by §1.14, fanned out 59×) | 1s | 2, exp backoff | yes |
| `identity-service` | DELETE | `/admin/v1/identities/{id}/roles/{role}` | revoke a single realm role (consumed by §1.15, fanned out 59×) | 1s | 2, exp backoff | yes |

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
- **Trigger**: §1.14 `POST /v1/admin/identity/grant-super-admin` succeeds.
  One event per grant call (not per role — the 59 per-role
  events live in `identity.role.granted.v1` per
  [`identity-service/INTEGRATION.md`](../identity-service/INTEGRATION.md#38-identityrolegrantedv1)).
- **Schema version**: 1.
- **Consumers**: `audit-service`, `notification-service`
  (pages security on-call — `SECURITY_ARCHITECTURE.md` §14),
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
- **Trigger**: §1.15 succeeds.
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
  triggered by `admin-service` §1.14 so this service can update its
  `super_admin_grant` table view and reconcile its own cache).
- **Handler**: upsert `super_admin_grant` view row keyed by
  `source_request_id`; invalidate the operator-UI permission cache
  for any operator whose visible role set changed.
- **Deduplication / Retry / Failure**: inbox keyed by
  `(identity_id, role, occurred_at)` / 3 with exponential backoff / DLQ.

### 4.1b `identity.role.revoked.v1`

- **Producer**: `identity-service`.
- **Reason**: same as 4.1a for revoke (§1.15 fan-out).
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
| [`audit-service`](../audit-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [`identity-service`](../identity-service/README.md) | CRITICAL | 503 `DEPENDENCY_UNAVAILABLE` |
| [``admin-service` (support module)`](../`admin-service` (support module)/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |

### Downstream services that depend on this service

| Downstream | Class (from its perspective) |
|---|---|
| [``customer-service` (addresses)`](../`customer-service` (addresses)/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`api-gateway`](../api-gateway/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`audit-service`](../audit-service/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [``notification-service` (provider ACL)`](../`notification-service` (provider ACL)/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [``courier-service` (dispatch)`](../`courier-service` (dispatch)/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [``payment-service` (courier earnings)`](../`payment-service` (courier earnings)/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`courier-service`](../courier-service/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [``courier-service` (tracking)`](../`courier-service` (tracking)/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`customer-service`](../customer-service/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [``courier-service` (delivery)`](../`courier-service` (delivery)/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [``driver-service` (dispatch)`](../`driver-service` (dispatch)/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [``driver-service` (availability)`](../`driver-service` (availability)/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [``payment-service` (driver earnings)`](../`payment-service` (driver earnings)/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [``driver-service` (incentives)`](../`driver-service` (incentives)/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [``driver-service` (location)`](../`driver-service` (location)/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`driver-service`](../driver-service/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [``geolocation-service` (ETA/routing)`](../`geolocation-service` (ETA/routing)/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`file-service`](../file-service/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [``payment-service` (food saga)`](../`payment-service` (food saga)/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`fraud-risk-service`](../fraud-risk-service/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
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

