# merchant-service — Integration Contract

## 1. Inbound APIs

### 1.1 `POST /v1/merchants`

- **Purpose**: Submit a new merchant KYC application.
- **Auth**: Bearer JWT (role: `merchant_owner`; owner must match
  the `owner_kc_sub` in the body).
- **Idempotency**: `Idempotency-Key` header **required**.
- **Request**:
  ```json
  {
    "legal_name": "Acme Eats LLC",
    "legal_form": "llc",
    "country": "US",
    "tax_id": "12-3456789",
    "tax_id_jurisdiction": "US",
    "primary_currency": "USD",
    "owner": {
      "full_name": "Jane Doe",
      "email": "jane@acme.com",
      "phone": "+15551234567"
    },
    "primary_contact": {
      "role": "primary",
      "full_name": "Jane Doe",
      "email": "jane@acme.com",
      "phone": "+15551234567"
    },
    "bank_account": {
      "account_holder_name": "Acme Eats LLC",
      "iban": "US12ACME00001234567890",
      "bank_name": "Acme Bank",
      "bank_country": "US",
      "currency": "USD"
    },
    "documents": [
      { "file_id": "01HZX...", "document_type": "trade_license" },
      { "file_id": "01HZX...", "document_type": "tax_cert" }
    ]
  }
  ```
- **Response (201)**:
  ```json
  {
    "id": "01HZX...",
    "state": "pending_review",
    "owner_kc_sub": "...",
    "created_at": "2026-07-29T10:42:11.183Z"
  }
  ```
- **Errors**:
  - 400 `VALIDATION_FAILED` — body schema violation
  - 401 `UNAUTHENTICATED`
  - 403 `FORBIDDEN` — not the owner
  - 409 `MERCHANT_ALREADY_EXISTS` — owner already has a merchant
  - 422 `SANCTIONS_MATCH` — sanctions screening returned a match
  - 422 `KYC_INCOMPLETE` — missing required document types
  - 422 `BANK_INVALID` — bank account validation failed
  - 422 `IDEMPOTENCY_KEY_REUSED` — same key, different body
  - 429 `RATE_LIMITED`
  - 503 `CIRCUIT_OPEN` / `DEPENDENCY_TIMEOUT`
- **Validation**: tax-id pattern from
  `merchant.onboarding.tax_id_patterns.{country}`; required
  documents from `merchant.onboarding.required_documents.{country}`;
  IBAN MOD-97.

### 1.2 `GET /v1/merchants/{id}`

- **Purpose**: Read a merchant.
- **Auth**: Bearer JWT (role: `merchant_owner` of this merchant,
  `merchant_finance` of this merchant, `platform_admin`, or
  `platform_compliance`).
- **Response (200)**:
  ```json
  {
    "id": "01HZX...",
    "owner_kc_sub": "...",
    "legal_name": "Acme Eats LLC",
    "legal_form": "llc",
    "country": "US",
    "primary_currency": "USD",
    "state": "approved",
    "payout_hold": false,
    "contacts": [...],
    "bank_accounts": [...],
    "documents": [...],
    "created_at": "...",
    "updated_at": "..."
  }
  ```
- **Errors**: 401, 403, 404, 410 (`MERCHANT_CLOSED`).

### 1.3 `PATCH /v1/merchants/{id}`

- **Purpose**: Update legal/tax fields.
- **Auth**: Bearer JWT (role: `merchant_owner` of this merchant or
  `platform_admin`).
- **Idempotency**: `Idempotency-Key` required.
- **Request**: any subset of the `POST` body fields (legal name,
  tax_id, contacts, bank account).
- **Errors**: 400, 401, 403, 404, 409, 410, 422, 429.
- **Side effects**: re-runs sanctions screening if legal name or
  owner name changes; emits `merchant.updated.v1`.

### 1.4 `POST /v1/merchants/{id}/submit`

- **Purpose**: Submit the draft for admin review.
- **Auth**: `merchant_owner` of this merchant.
- **Idempotency**: required.
- **State transition**: `draft → pending_review`.
- **Errors**: 409 `STATE_INVALID`, 422 `KYC_INCOMPLETE`,
  422 `SANCTIONS_MATCH`.

### 1.5 `POST /v1/merchants/{id}/approve`

- **Purpose**: Admin approves a pending merchant.
- **Auth**: `platform_admin` only.
- **Idempotency**: required.
- **Request**: `{"review_notes": "Looks good"}` (optional).
- **State transition**: `pending_review → approved`.
- **Errors**: 401, 403, 404, 409, 422 (`SANCTIONS_MATCH`,
  `KYC_INCOMPLETE`, `BANK_INVALID`).
- **Side effects**: emits `merchant.approved.v1`.

### 1.6 `POST /v1/merchants/{id}/reject`

- **Purpose**: Admin rejects a pending merchant.
- **Auth**: `platform_admin` only.
- **Idempotency**: required.
- **Request**: `{"reason_code": "insufficient_docs", "reason_text":
  "Trade license expired"}` — `reason_code` required.
- **State transition**: `pending_review → rejected`.
- **Errors**: 401, 403, 409.

### 1.7 `POST /v1/merchants/{id}/suspend`

- **Purpose**: Admin suspends an approved merchant.
- **Auth**: `platform_admin` only; break-glass requires a second
  admin's co-signature.
- **Idempotency**: required.
- **Request**: `{"reason_code": "quality", "reason_text": "...",
  "cascade_to_restaurants": true}`.
- **State transition**: `approved → suspended`.
- **Errors**: 401, 403, 409, 410.
- **Side effects**: emits `merchant.suspended.v1`; if
  `cascade_to_restaurants`, downstream services act via the event
  (not via direct API).

### 1.8 `POST /v1/merchants/{id}/reinstate`

- **Purpose**: Admin reinstates a suspended merchant.
- **Auth**: `platform_admin` only.
- **Idempotency**: required.
- **Request**: `{"reason_code": "issue_resolved",
  "reason_text": "..."}`.
- **State transition**: `suspended → approved`.
- **Side effects**: emits `merchant.reinstated.v1`; payout hold
  is cleared (separate `merchant.payout.hold.v1` event).

### 1.9 `POST /v1/merchants/{id}/close`

- **Purpose**: Admin permanently closes a merchant.
- **Auth**: `platform_admin` only; break-glass.
- **Idempotency**: required.
- **Request**: `{"reason_code": "merchant_request",
  "reason_text": "..."}`.
- **State transition**: any non-terminal → `closed`.
- **Side effects**: emits `merchant.closed.v1`; soft-deletes the
  merchant; notifies `restaurant-settlement-service` to settle the
  final payable.

### 1.10 `POST /v1/merchants/{id}/payout-hold` and `DELETE /v1/merchants/{id}/payout-hold`

- **Purpose**: Admin sets or clears a payout hold.
- **Auth**: `platform_admin` only.
- **Idempotency**: required.
- **Request (POST)**: `{"reason_code": "compliance",
  "reason_text": "..."}`.
- **Side effects**: emits `merchant.payout.hold.v1` (with
  `held: true|false`); consumed by
  `restaurant-settlement-service`.

### 1.11 `GET /v1/merchants`

- **Purpose**: List merchants (admin only).
- **Auth**: `platform_admin` or `platform_compliance`.
- **Query params**: `state`, `country`, `q` (matches legal name),
  `cursor`, `limit` (default 20, max 100).
- **Response (200)**: `{"items": [...], "next_cursor": "...",
  "has_more": true}`.

### 1.12 `GET /v1/merchants/by-user/{kc_sub}`

- **Purpose**: System lookup by Keycloak subject.
- **Auth**: `client_credentials` (service-to-service).
- **Response (200)**: merchant or 404.
- **Cached**: 60 s TTL in Redis, key
  `merchant:by_user:{kc_sub}`.

### 1.13 `POST /v1/merchants/{id}/contacts`, `PATCH /v1/merchants/{id}/contacts/{cid}`, `DELETE /v1/merchants/{id}/contacts/{cid}`

- Standard CRUD; auth: `merchant_owner` of the merchant or admin.

### 1.14 `PUT /v1/merchants/{id}/bank-account`

- **Purpose**: Set or replace the primary bank account.
- **Auth**: `merchant_owner`, `merchant_finance`, or admin.
- **Idempotency**: required.
- **Validation**: bank validator (synchronous).
- **Side effects**: emits `merchant.updated.v1` with the masked
  IBAN; if the primary changes, settlement is signaled via
  `merchant.updated.v1`.

## 2. Outbound APIs

| Target | Method | URI | Purpose | Timeout | Retry | Circuit |
|--------|--------|-----|---------|---------|-------|---------|
| `identity-service` | GET | /v1/users/{kc_sub} | verify subject | 1 s | 3 | yes |
| `file-service` | GET | /v1/files/{id} | fetch scan status | 1 s | 3 | yes |
| `file-service` | POST | /v1/uploads | request signed URL | 1 s | 3 | yes |
| `configuration-service` | GET | /v1/configurations/{key} | read onboarding config | 1 s | 3 | yes |
| `notification-service` | POST | /v1/notifications | trigger lifecycle messages | 1 s | 3 | yes |
| KYC / sanctions provider | — | (provider) | sanctions screening | 5 s | 2 | yes |
| Bank validator provider | — | (provider) | IBAN validation | 3 s | 2 | yes |

## 3. Produced Events

### 3.1 `merchant.created.v1`

- **Producer**: `merchant-service`.
- **Topic**: `merchant.merchant.created`.
- **Trigger**: `POST /v1/merchants` succeeds.
- **Schema version**: 1.
- **Partition key**: `merchant.id` (UUID).
- **Consumers**: `restaurant-service`, `restaurant-staff-service`,
  `restaurant-settlement-service`, `audit-service`,
  `analytics-service`.
- **Schema**:
  ```json
  {
    "event_id": "01HZX...",
    "event_name": "merchant.created.v1",
    "occurred_at": "2026-07-29T10:42:11.183Z",
    "schema_version": 1,
    "producer": "merchant-service",
    "tenant_id": "global",
    "correlation_id": "01HZX...",
    "aggregate_type": "Merchant",
    "aggregate_id": "01HZX...",
    "data": {
      "merchant_id": "01HZX...",
      "owner_kc_sub": "...",
      "legal_name": "Acme Eats LLC",
      "country": "US",
      "primary_currency": "USD",
      "state": "pending_review"
    }
  }
  ```
- **Retry**: outbox, 3 attempts.
- **DLQ**: `merchant.merchant.created.dlq`.

### 3.2 `merchant.approved.v1`

- **Producer**: `merchant-service`.
- **Topic**: `merchant.merchant.approved`.
- **Trigger**: `POST /approve` succeeds.
- **Schema version**: 1.
- **Partition key**: `merchant.id`.
- **Consumers**: `restaurant-service`, `restaurant-settlement-service`,
  `notification-service`, `audit-service`.
- **Schema**:
  ```json
  {
    "event_id": "...",
    "event_name": "merchant.approved.v1",
    "occurred_at": "...",
    "schema_version": 1,
    "producer": "merchant-service",
    "tenant_id": "global",
    "correlation_id": "...",
    "aggregate_type": "Merchant",
    "aggregate_id": "...",
    "data": {
      "merchant_id": "...",
      "approved_at": "...",
      "approved_by_kc_sub": "..."
    }
  }
  ```
- **DLQ**: `merchant.merchant.approved.dlq`.

### 3.3 `merchant.suspended.v1`

- **Producer**: `merchant-service`.
- **Topic**: `merchant.merchant.suspended`.
- **Trigger**: `POST /suspend` or cascade from user suspension.
- **Schema version**: 1.
- **Partition key**: `merchant.id`.
- **Consumers**: `restaurant-service`, `restaurant-settlement-service`,
  `payment-service`, `notification-service`, `audit-service`.
- **Schema**:
  ```json
  {
    "event_id": "...",
    "event_name": "merchant.suspended.v1",
    "occurred_at": "...",
    "schema_version": 1,
    "producer": "merchant-service",
    "tenant_id": "global",
    "correlation_id": "...",
    "aggregate_type": "Merchant",
    "aggregate_id": "...",
    "data": {
      "merchant_id": "...",
      "reason_code": "quality",
      "reason_text": "...",
      "suspended_by_kc_sub": "...",
      "cascade_to_restaurants": true
    }
  }
  ```
- **DLQ**: `merchant.merchant.suspended.dlq`.

### 3.4 `merchant.reinstated.v1`

Same envelope, with `data.reinstated_at`,
`data.reinstated_by_kc_sub`, `data.reason_code`.

### 3.5 `merchant.closed.v1`

Same envelope, terminal state. Consumed by
`restaurant-settlement-service` to trigger final payout.

### 3.6 `merchant.rejected.v1`

Same envelope, with `data.reason_code`, `data.reason_text`.

### 3.7 `merchant.updated.v1`

Same envelope, with `data.changed_fields: [...]`. Used for tax-id
changes, bank account changes, contact changes.

### 3.8 `merchant.payout.hold.v1`

Same envelope, with `data.held: true|false`, `data.reason_code`,
`data.merchant_id`. Consumed by `restaurant-settlement-service`.

## 4. Consumed Events

### 4.1 `identity.user.created.v1`

- **Producer**: `identity-service`.
- **Reason**: when a new user with role `merchant_owner` is
  created, we may need to create a draft merchant.
- **Handler**: lookup existing merchant by `kc_sub`; if absent and
  the user has role `merchant_owner`, do nothing here (the owner
  creates the merchant via `POST /v1/merchants` from the operator
  console).
- **Deduplication**: inbox on `event_id`.
- **Retry**: 3 with backoff.
- **Failure**: DLQ.

### 4.2 `identity.user.suspended.v1`

- **Producer**: `identity-service`.
- **Reason**: if the user is the owner of one or more merchants,
  and `merchant.payout.hold_on_owner_suspend` is true, cascade
  suspension to those merchants.
- **Handler**: query merchants by `owner_kc_sub`; for each
  `approved` merchant, set state to `suspended` with
  `reason_code = "owner_suspended"` and emit
  `merchant.suspended.v1`.
- **Deduplication**: inbox on `event_id`.

### 4.3 `customer.suspended.v1`

- **Producer**: `customer-service`.
- **Reason**: informational; we don't own customers. No action
  beyond logging.

### 4.4 `configuration.updated.v1`

- **Producer**: `configuration-service`.
- **Reason**: invalidate local cache of onboarding config.
- **Handler**: delete keys matching `merchant.onboarding.*` from
  Redis.
- **Deduplication**: inbox on `event_id`.

## 5. Reliability

- **Timeouts**:
  - HTTP outbound: 1 s default; 5 s for KYC provider; 3 s for bank
    validator.
  - DB statement: 30 s; default.
  - Kafka producer ack: 5 s.
- **Retries**: exponential backoff with jitter; 3 attempts by
  default. Idempotency-Key on all non-idempotent calls.
- **Circuit breakers**:
  - KYC provider: open after 5 consecutive failures or 50% over
    30 s; half-open after 30 s. When open, submission is rejected
    with 503 `CIRCUIT_OPEN`.
  - Bank validator: same.
  - `identity-service`, `file-service`, `notification-service`,
    `configuration-service`: standard 5/30s.
- **Bulkheads**: separate connection pools per downstream.
- **Outbox**: yes, schema `merchant.outbox` (see `ERD.md`).
  Poller publishes to Kafka; on broker ack, sets
  `published_at`. Failed rows are retried with backoff. Rows are
  purged 24 h after `published_at`.
- **Inbox**: yes, schema `merchant.inbox` (see `ERD.md`).
- **DLQ**: every topic has a paired `.dlq`; retention 30 days.
  Replay tooling: `replay-cli` and admin console "DLQ Inspector".
- **Reconciliation**: a daily job in `reporting-service` checks for
  merchants that are `approved` but have no bank account, and for
  `pending_review` merchants > 90 days old. Findings open support
  tickets.

## 6. Correlation IDs

All requests carry `X-Correlation-Id`. The service propagates it to
every outbound call (HTTP header `X-Correlation-Id`) and embeds it
in the event envelope's `correlation_id` field. All log lines in
the request scope include it. Outbox rows carry the originating
`correlation_id`.

## 7. Distributed Tracing

OpenTelemetry SDK; one root span per request; named
`POST /v1/merchants` (or `GET /v1/merchants/{id}`, etc.). The span
is propagated to downstream HTTP and to Kafka producer/consumer
spans via `traceparent`. Sample 100% on errors, 10% on success in
production; 100% in staging.

## 8. Threat Surface (per `SECURITY_ARCHITECTURE.md` §18)

| Threat | Mitigation |
|--------|------------|
| Spoofing (impersonating owner) | mTLS + JWT with `sub` claim; resource-level ownership checks |
| Tampering (admin action) | HMAC-SHA256 request signature; break-glass co-sign |
| Repudiation (denying admin action) | audit log with actor, signature, correlation id |
| Information disclosure (PII leak) | column-level encryption; access logging; no raw IBAN in responses |
| Denial of service (KYC flooding) | rate limit per user; circuit breaker on provider |
| Elevation of privilege (admin to other merchants) | role check at gateway + resource check at service |


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
| [`audit-service`](../audit-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [`branch-service`](../branch-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [`configuration-service`](../configuration-service/README.md) | DEGRADABLE | degrade (cache / default / flag) |
| [`customer-service`](../customer-service/README.md) | CRITICAL | 503 `DEPENDENCY_UNAVAILABLE` |
| [`feature-flag-service`](../feature-flag-service/README.md) | DEGRADABLE | degrade (cache / default / flag) |
| [`file-service`](../file-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [`identity-service`](../identity-service/README.md) | CRITICAL | 503 `DEPENDENCY_UNAVAILABLE` |
| [`menu-service`](../menu-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [`notification-service`](../notification-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [`payment-service`](../payment-service/README.md) | CRITICAL | 503 `DEPENDENCY_UNAVAILABLE` |
| [`restaurant-service`](../restaurant-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [`restaurant-settlement-service`](../restaurant-settlement-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [`restaurant-staff-service`](../restaurant-staff-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [`tax-service`](../tax-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |

### Downstream services that depend on this service

| Downstream | Class (from its perspective) |
|---|---|
| [`branch-service`](../branch-service/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`file-service`](../file-service/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`identity-service`](../identity-service/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`notification-service`](../notification-service/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`payment-service`](../payment-service/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`restaurant-service`](../restaurant-service/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`restaurant-settlement-service`](../restaurant-settlement-service/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`search-service`](../search-service/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`support-service`](../support-service/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`user-profile-service`](../user-profile-service/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
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

