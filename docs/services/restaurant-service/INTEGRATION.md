# restaurant-service — Integration Contract

## 1. Inbound APIs

### 1.1 `POST /v1/restaurants`

- **Purpose**: Create a new restaurant under an approved merchant.
- **Auth**: Bearer JWT (role: `merchant_owner` of the parent
  merchant).
- **Idempotency**: `Idempotency-Key` header **required**.
- **Request**:
  ```json
  {
    "merchant_id": "01HZX...",
    "name": "Pizza Palace",
    "slug": "pizza-palace",
    "type": "restaurant",
    "cuisines": ["pizza", "italian"],
    "description": "Wood-fired pizza since 1998.",
    "logo_file_id": "01HZX...",
    "tags": ["family_friendly"]
  }
  ```
- **Response (201)**:
  ```json
  {
    "id": "01HZX...",
    "merchant_id": "01HZX...",
    "name": "Pizza Palace",
    "state": "draft",
    "created_at": "2026-07-29T10:42:11.183Z"
  }
  ```
- **Errors**:
  - 400 `VALIDATION_FAILED` (cuisine not in allowed list, slug
    format)
  - 401 `UNAUTHENTICATED`
  - 403 `FORBIDDEN` (not the merchant owner)
  - 409 `MERCHANT_NOT_APPROVED` (parent merchant not approved)
  - 409 `MERCHANT_SUSPENDED` (parent merchant suspended)
  - 409 `SLUG_TAKEN`
  - 422 `IDEMPOTENCY_KEY_REUSED`
  - 429 `RATE_LIMITED`
  - 503 `DEPENDENCY_TIMEOUT` (merchant-service down)

### 1.2 `GET /v1/restaurants/{id}`

- **Purpose**: Read a restaurant.
- **Auth**: Bearer JWT (owner / staff / admin).
- **Response (200)**: full restaurant including cuisines, tags,
  denormalized rating.
- **Errors**: 401, 403, 404, 410 (`RESTAURANT_CLOSED`).

### 1.3 `PATCH /v1/restaurants/{id}`

- **Purpose**: Update profile fields.
- **Auth**: `merchant_owner` of the parent merchant or
  `platform_admin`.
- **Idempotency**: required.
- **Request**: any subset of the create body.
- **Side effects**: emits `restaurant.updated.v1`.

### 1.4 `POST /v1/restaurants/{id}/submit`

- **Purpose**: Submit the draft for admin review.
- **Auth**: `merchant_owner`.
- **Idempotency**: required.
- **State transition**: `draft → pending_review`.
- **Side effects**: emits `restaurant.updated.v1` with the new
  state.

### 1.5 `POST /v1/restaurants/{id}/approve`

- **Purpose**: Admin approval.
- **Auth**: `platform_admin`.
- **Idempotency**: required.
- **State transition**: `pending_review → approved`.
- **Side effects**: emits `restaurant.approved.v1`; the restaurant
  becomes available for branches and menus to be created under
  it.

### 1.6 `POST /v1/restaurants/{id}/reject`

- **Purpose**: Admin rejection.
- **Auth**: `platform_admin`.
- **Idempotency**: required.
- **Request**: `{"reason_code": "incomplete_info",
  "reason_text": "..."}`.
- **State transition**: `pending_review → rejected`.
- **Side effects**: emits `restaurant.rejected.v1`.

### 1.7 `POST /v1/restaurants/{id}/online` and `POST /v1/restaurants/{id}/offline`

- **Purpose**: Toggle online / offline.
- **Auth**: `merchant_owner`, `merchant_ops`, `restaurant_staff`,
  or `platform_admin`.
- **Idempotency**: required.
- **Side effects**: emits `restaurant.online.v1` or
  `restaurant.offline.v1`. The downstream `cart-service` and
  `checkout-service` consume the event and update their caches.

### 1.8 `POST /v1/restaurants/{id}/suspend`

- **Purpose**: Admin suspension.
- **Auth**: `platform_admin`; break-glass requires co-sign.
- **Idempotency**: required.
- **Request**: `{"reason_code": "quality", "reason_text": "..."}`.
- **State transition**: any of `approved|online|offline →
  suspended`.
- **Side effects**: emits `restaurant.suspended.v1`; downstream
  services block orders.

### 1.9 `POST /v1/restaurants/{id}/reinstate`

- **Purpose**: Admin re-instatement.
- **Auth**: `platform_admin`.
- **Idempotency**: required.
- **Request**: `{"reason_code": "issue_resolved",
  "reason_text": "..."}`.
- **State transition**: `suspended → approved` (operator must
  re-enable online).

### 1.10 `POST /v1/restaurants/{id}/close`

- **Purpose**: Admin permanent closure.
- **Auth**: `platform_admin`; break-glass requires co-sign.
- **Idempotency**: required.
- **State transition**: any non-terminal → `closed`.
- **Side effects**: emits `restaurant.closed.v1`; soft-deletes
  the restaurant.

### 1.11 `POST /v1/restaurants/{id}/resubmit`

- **Purpose**: Re-submission after rejection.
- **Auth**: `merchant_owner`.
- **Idempotency**: required.
- **State transition**: `rejected → pending_review`.

### 1.12 `GET /v1/restaurants`

- **Purpose**: List restaurants.
- **Auth**: `platform_admin`, `platform_compliance`, or
  `client_credentials` (search).
- **Query params**: `state`, `merchant_id`, `cuisine`, `type`,
  `q`, `cursor`, `limit`.
- **Response (200)**: `{"items": [...], "next_cursor": "...",
  "has_more": true}`.

### 1.13 `GET /v1/restaurants/by-merchant/{merchant_id}`

- **Purpose**: System lookup by merchant.
- **Auth**: `client_credentials`.
- **Response (200)**: list of restaurants for the merchant.

### 1.14 `GET /v1/restaurants/{id}/online`

- **Purpose**: Fast online flag lookup.
- **Auth**: `client_credentials` (cart-service, checkout-service).
- **Cached**: 30 s TTL in Redis, key `restaurant:online:{id}`.
- **Response (200)**: `{"id": "...", "online": true,
  "state": "online", "expires_at": "..."}`.

### 1.15 `GET /v1/restaurants/{id}/summary`

- **Purpose**: Search summary view.
- **Auth**: `client_credentials` (search-service).
- **Response (200)**: minimal projection for indexing.

## 2. Outbound APIs

| Target | Method | URI | Purpose | Timeout | Retry | Circuit |
|--------|--------|-----|---------|---------|-------|---------|
| `merchant-service` | GET | /v1/merchants/{id} | verify parent merchant exists and is approved | 1 s | 3 | yes |
| `merchant-service` | GET | /v1/merchants/by-user/{kc_sub} | resolve merchant by owner | 1 s | 3 | yes |
| `configuration-service` | GET | /v1/configurations/{key} | read cuisine/type list | 1 s | 3 | yes |
| `identity-service` | GET | /v1/users/{kc_sub} | verify subject | 1 s | 3 | yes |
| `file-service` | GET | /v1/files/{id} | verify logo file exists | 1 s | 3 | yes |
| `notification-service` | POST | /v1/notifications | trigger lifecycle messages | 1 s | 3 | yes |

## 3. Produced Events

### 3.1 `restaurant.created.v1`

- **Producer**: `restaurant-service`.
- **Topic**: `restaurant.restaurant.created`.
- **Trigger**: `POST /v1/restaurants`.
- **Schema version**: 1.
- **Partition key**: `restaurant.id`.
- **Consumers**: `branch-service`, `menu-service`,
  `search-service`, `audit-service`.
- **Schema**:
  ```json
  {
    "event_id": "01HZX...",
    "event_name": "restaurant.created.v1",
    "occurred_at": "2026-07-29T10:42:11.183Z",
    "schema_version": 1,
    "producer": "restaurant-service",
    "tenant_id": "global",
    "correlation_id": "01HZX...",
    "aggregate_type": "Restaurant",
    "aggregate_id": "01HZX...",
    "data": {
      "restaurant_id": "01HZX...",
      "merchant_id": "01HZX...",
      "name": "Pizza Palace",
      "type": "restaurant",
      "cuisines": ["pizza", "italian"],
      "state": "draft"
    }
  }
  ```
- **DLQ**: `restaurant.restaurant.created.dlq`.

### 3.2 `restaurant.approved.v1`

Same envelope, `data.state = "approved"`, `data.approved_at`,
`data.approved_by_kc_sub`.

### 3.3 `restaurant.online.v1` and `restaurant.offline.v1`

Same envelope, `data.online = true|false`,
`data.from_online = true|false`. Consumed by `cart-service`,
`search-service`, `courier-dispatch-service`.

### 3.4 `restaurant.suspended.v1`

Same envelope, `data.reason_code`, `data.reason_text`,
`data.suspended_by_kc_sub`, `data.cause` (`admin` or
`merchant_cascade`).

### 3.5 `restaurant.reinstated.v1`

Same envelope, `data.reason_code`,
`data.reinstated_by_kc_sub`, `data.cause`.

### 3.6 `restaurant.closed.v1`

Same envelope, `data.cause` (`admin` or `merchant_cascade`).

### 3.7 `restaurant.rejected.v1`

Same envelope, `data.reason_code`, `data.reason_text`.

### 3.8 `restaurant.updated.v1`

Same envelope, `data.changed_fields: [...]`.

## 4. Consumed Events

### 4.1 `merchant.approved.v1`

- **Producer**: `merchant-service`.
- **Reason**: enables creation of restaurants under the merchant.
- **Handler**: log only; no state change.
- **Deduplication**: inbox on `event_id`.

### 4.2 `merchant.suspended.v1`

- **Producer**: `merchant-service`.
- **Reason**: cascade suspension to all `approved|online|offline`
  restaurants of the merchant.
- **Handler**: query `restaurants` by `merchant_id`; for each
  non-terminal restaurant, transition to `suspended` with
  `reason_code = "merchant_suspended"`; emit
  `restaurant.suspended.v1`.
- **Idempotency**: inbox on `event_id`; check that the
  restaurant is not already `suspended` before transitioning.

### 4.3 `merchant.reinstated.v1`

- **Producer**: `merchant-service`.
- **Reason**: cascade re-instatement.
- **Handler**: query suspended restaurants of the merchant;
  transition to `approved` (NOT `online`); emit
  `restaurant.reinstated.v1`. The operator must re-enable online.

### 4.4 `merchant.closed.v1`

- **Producer**: `merchant-service`.
- **Reason**: cascade closure.
- **Handler**: close all non-terminal restaurants of the merchant;
  emit `restaurant.closed.v1`.

### 4.5 `branch.created.v1`

- **Producer**: `branch-service`.
- **Reason**: a new branch exists; recompute `online` flag.
- **Handler**: call `branch-service` to get the branch's current
  open state; if open and `auto_offline_enabled` is true and
  `required_branches` is met, set `online = true` and emit
  `restaurant.online.v1`.

### 4.6 `branch.hours.changed.v1`

- **Producer**: `branch-service`.
- **Reason**: branch hours changed; recompute `online` flag.
- **Handler**: same as `branch.created.v1`.

### 4.7 `review.submitted.v1` and `review.aggregated.v1`

- **Producer**: `review-rating-service`.
- **Reason**: refresh denormalized rating.
- **Handler**: upsert `avg_rating`, `review_count`,
  `last_rating_update_at` on the restaurant.
- **Deduplication**: inbox on `event_id`.

## 5. Reliability

- **Timeouts**: HTTP 1 s; DB 30 s; Kafka 5 s.
- **Retries**: 3 attempts with exponential backoff and jitter;
  Idempotency-Key on all non-idempotent calls.
- **Circuit breakers**: 5 consecutive failures or 50% over 30 s;
  standard half-open.
- **Bulkheads**: per-downstream connection pools.
- **Outbox**: yes, `restaurant.outbox`.
- **Inbox**: yes, `restaurant.inbox`.
- **DLQ**: every topic has a paired `.dlq`; 30-day retention.
- **Reconciliation**: daily job in `reporting-service` detects
  restaurants with `state = approved` and no branches; opens
  support tickets.

## 6. Correlation IDs

All requests carry `X-Correlation-Id`; the service propagates it
to outbound calls and embeds it in the event envelope. Logs and
traces are correlated.

## 7. Distributed Tracing

OpenTelemetry SDK; one root span per request; named
`POST /v1/restaurants`, etc. Propagated through Kafka. Sample
100% on errors, 10% on success in production.

## 8. Threat Surface (per `SECURITY_ARCHITECTURE.md` §18)

| Threat | Mitigation |
|--------|------------|
| Spoofing | mTLS + JWT |
| Tampering (admin action) | HMAC-SHA256 signature; break-glass co-sign |
| Repudiation | audit log with actor, signature, correlation |
| Information disclosure | minimal PII; logo via file-service signed URLs |
| Denial of service | rate limits; circuit breakers |
| Elevation of privilege | resource-level ownership checks; role check at gateway |


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
| [`branch-service`](../branch-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [`cart-service`](../cart-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [`checkout-service`](../checkout-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [`configuration-service`](../configuration-service/README.md) | DEGRADABLE | degrade (cache / default / flag) |
| [`courier-dispatch-service`](../courier-dispatch-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [`dispatch-service`](../dispatch-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [`feature-flag-service`](../feature-flag-service/README.md) | DEGRADABLE | degrade (cache / default / flag) |
| [`file-service`](../file-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [`food-order-service`](../food-order-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [`geolocation-service`](../geolocation-service/README.md) | DEGRADABLE | degrade (cache / default / flag) |
| [`identity-service`](../identity-service/README.md) | CRITICAL | 503 `DEPENDENCY_UNAVAILABLE` |
| [`menu-service`](../menu-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [`merchant-service`](../merchant-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [`notification-service`](../notification-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [`restaurant-order-mgmt-service`](../restaurant-order-mgmt-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [`restaurant-staff-service`](../restaurant-staff-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [`review-rating-service`](../review-rating-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [`search-service`](../search-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [`zone-service`](../zone-service/README.md) | DEGRADABLE | degrade (cache / default / flag) |

### Downstream services that depend on this service

| Downstream | Class (from its perspective) |
|---|---|
| [`api-gateway`](../api-gateway/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`branch-service`](../branch-service/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`cart-service`](../cart-service/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`checkout-service`](../checkout-service/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`file-service`](../file-service/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`food-order-service`](../food-order-service/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`identity-service`](../identity-service/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`inventory-service`](../inventory-service/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`menu-service`](../menu-service/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`merchant-service`](../merchant-service/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`restaurant-order-mgmt-service`](../restaurant-order-mgmt-service/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`restaurant-staff-service`](../restaurant-staff-service/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`review-rating-service`](../review-rating-service/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`search-service`](../search-service/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |

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

