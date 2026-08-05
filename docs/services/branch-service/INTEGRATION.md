# branch-service — Integration Contract

## 1. Inbound APIs

### 1.1 `POST /v1/branches`

- **Purpose**: Create a new branch under an approved restaurant.
- **Auth**: Bearer JWT (role: `merchant_owner` of the parent
  restaurant).
- **Idempotency**: `Idempotency-Key` header **required**.
- **Request**:
  ```json
  {
    "restaurant_id": "01HZX...",
    "name": "Pizza Palace - Downtown",
    "slug": "pizza-palace-downtown",
    "address": {
      "line1": "123 Main St",
      "city": "Amsterdam",
      "region": "NH",
      "postal_code": "1012AB",
      "country": "NL"
    },
    "timezone": "Europe/Amsterdam",
    "phone": "+31201234567",
    "email": "downtown@pizzapalace.example",
    "hours": [
      { "day_of_week": 1, "open_time": "11:00", "close_time": "23:00" },
      { "day_of_week": 2, "open_time": "11:00", "close_time": "23:00" }
    ],
    "prep_capacity": 20
  }
  ```
- **Response (201)**:
  ```json
  {
    "id": "01HZX...",
    "restaurant_id": "01HZX...",
    "name": "Pizza Palace - Downtown",
    "slug": "pizza-palace-downtown",
    "state": "open",
    "location": { "lat": 52.37, "lng": 4.89 },
    "timezone": "Europe/Amsterdam",
    "prep_capacity": 20,
    "created_at": "2026-07-29T10:42:11.183Z"
  }
  ```
- **Errors**:
  - 400 `VALIDATION_FAILED`
  - 401 `UNAUTHENTICATED`
  - 403 `FORBIDDEN` (not the owner)
  - 409 `RESTAURANT_NOT_APPROVED`
  - 409 `RESTAURANT_SUSPENDED`
  - 409 `SLUG_TAKEN`
  - 422 `GEOCODE_FAILED`
  - 422 `OUT_OF_ZONE`
  - 422 `IDEMPOTENCY_KEY_REUSED`
  - 429 `RATE_LIMITED`
  - 503 `DEPENDENCY_TIMEOUT` / `CIRCUIT_OPEN`

### 1.2 `GET /v1/branches/{id}`

- **Purpose**: Read a branch.
- **Auth**: Bearer JWT (owner / staff / admin / search).
- **Response (200)**: full branch including hours, special hours,
  temporary closure status, busy state.
- **Errors**: 401, 403, 404, 410 (`BRANCH_CLOSED`).

### 1.3 `PATCH /v1/branches/{id}`

- **Purpose**: Update profile fields.
- **Auth**: `merchant_owner` of the parent restaurant or
  `platform_admin`.
- **Idempotency**: required.
- **Side effects**: emits `branch.updated.v1`.

### 1.4 `PUT /v1/branches/{id}/hours`

- **Purpose**: Replace weekly hours (full set).
- **Auth**: `merchant_owner`, `merchant_ops`, or admin.
- **Idempotency**: required.
- **Side effects**: emits `branch.hours.changed.v1`.

### 1.5 `POST /v1/branches/{id}/special-hours` and `DELETE /v1/branches/{id}/special-hours/{sid}`

- **Purpose**: Add / remove a special date.
- **Auth**: `merchant_owner` or admin.
- **Idempotency**: required.
- **Side effects**: emits `branch.hours.changed.v1`.

### 1.6 `POST /v1/branches/{id}/busy` and `DELETE /v1/branches/{id}/busy`

- **Purpose**: Mark / clear busy.
- **Auth**: `restaurant_staff`, `merchant_owner`, or admin.
- **Idempotency**: required.
- **Side effects**: emits `branch.busy.v1` (with
  `data.busy = true|false`).

### 1.7 `POST /v1/branches/{id}/temporary-closure` and `DELETE /v1/branches/{id}/temporary-closure`

- **Purpose**: Set / clear a temporary closure.
- **Auth**: `restaurant_staff`, `merchant_owner`, or admin.
- **Idempotency**: required.
- **Request (POST)**: `{"start_at": "2026-07-29T15:00:00Z",
  "end_at": "2026-07-29T18:00:00Z", "reason_code": "equipment",
  "reason_text": "..."}`.
- **Side effects**: state → `temporarily_closed`; emits
  `branch.temporary_closure.v1`.

### 1.8 `POST /v1/branches/{id}/close` and `POST /v1/branches/{id}/open`

- **Purpose**: Admin permanent close / re-open.
- **Auth**: `platform_admin`; `close` requires break-glass
  co-sign.
- **Idempotency**: required.
- **State transitions**: any non-terminal → `closed`; `closed` is
  terminal; `open` is allowed only if not `closed`.
- **Side effects**: emits `branch.closed.v1` or
  `branch.updated.v1`.

### 1.9 `GET /v1/branches`

- **Purpose**: List branches.
- **Auth**: `platform_admin` or `client_credentials` (search).
- **Query params**: `state`, `restaurant_id`, `q`, `cursor`,
  `limit`.

### 1.10 `GET /v1/branches/by-restaurant/{restaurant_id}`

- **Purpose**: List branches for a restaurant.
- **Auth**: `client_credentials`.

### 1.11 `GET /v1/branches/{id}/open`

- **Purpose**: Fast open flag lookup.
- **Auth**: `client_credentials`.
- **Cached**: 30 s TTL in Redis, key `branch:open:{id}`.
- **Response (200)**: `{"id": "...", "open": true,
  "next_change_at": "...", "expires_at": "..."}`.

### 1.12 `GET /v1/branches/{id}/busy`

- **Purpose**: Fast busy flag lookup.
- **Auth**: `client_credentials`.
- **Cached**: 15 s TTL in Redis, key `branch:busy:{id}`.

### 1.13 `GET /v1/branches/{id}/prep-capacity`

- **Purpose**: Read prep capacity.
- **Auth**: `client_credentials`.
- **Response (200)**: `{"branch_id": "...", "prep_capacity": 20,
  "in_flight_orders": 5}`.

## 2. Outbound APIs

| Target | Method | URI | Purpose | Timeout | Retry | Circuit |
|--------|--------|-----|---------|---------|-------|---------|
| `restaurant-service` | GET | /v1/restaurants/{id} | verify parent | 1 s | 3 | yes |
| `geolocation-service` | GET | /v1/geocode | geocode address | 3 s | 2 | yes |
| `zone-service` | POST | /v1/zones/contains | check zone | 1 s | 3 | yes |
| `configuration-service` | GET | /v1/configurations/{key} | read defaults | 1 s | 3 | yes |
| `identity-service` | GET | /v1/users/{kc_sub} | verify subject | 1 s | 3 | yes |
| `notification-service` | POST | /v1/notifications | trigger lifecycle | 1 s | 3 | yes |
| `food-order-service` | GET | /v1/orders/in-flight?branch_id= | count in-flight for capacity | 1 s | 2 | yes |

## 3. Produced Events

### 3.1 `branch.created.v1`

- **Producer**: `branch-service`.
- **Topic**: `branch.branch.created`.
- **Trigger**: `POST /v1/branches`.
- **Schema version**: 1.
- **Partition key**: `branch.id`.
- **Consumers**: `menu-service`, `cart-service`,
  `courier-dispatch-service`, `search-service`,
  `audit-service`.
- **Schema**:
  ```json
  {
    "event_id": "01HZX...",
    "event_name": "branch.created.v1",
    "occurred_at": "2026-07-29T10:42:11.183Z",
    "schema_version": 1,
    "producer": "branch-service",
    "tenant_id": "global",
    "correlation_id": "01HZX...",
    "aggregate_type": "Branch",
    "aggregate_id": "01HZX...",
    "data": {
      "branch_id": "01HZX...",
      "restaurant_id": "01HZX...",
      "name": "Pizza Palace - Downtown",
      "country": "NL",
      "timezone": "Europe/Amsterdam",
      "prep_capacity": 20,
      "location": { "lat": 52.37, "lng": 4.89 }
    }
  }
  ```
- **DLQ**: `branch.branch.created.dlq`.

### 3.2 `branch.updated.v1`

Same envelope, with `data.changed_fields: [...]`.

### 3.3 `branch.hours.changed.v1`

Same envelope, with `data.hours: [...]`,
`data.special_hours: [...]`. Consumed by
`restaurant-service` to recompute parent online state and by
`cart-service` / `courier-dispatch-service` to update caches.

### 3.4 `branch.busy.v1`

Same envelope, with `data.busy: true|false`,
`data.busy_actor_kc_sub`. Consumed by `courier-dispatch-service`.

### 3.5 `branch.closed.v1`

Same envelope, with `data.cause` (`admin` or
`parent_cascade`).

### 3.6 `branch.temporary_closure.v1`

Same envelope, with `data.closed: true|false`,
`data.start_at`, `data.end_at`, `data.reason_code`.

## 4. Consumed Events

### 4.1 `restaurant.created.v1`

- **Producer**: `restaurant-service`.
- **Reason**: parent is eligible for branches.
- **Handler**: log only.
- **Deduplication**: inbox on `event_id`.

### 4.2 `restaurant.suspended.v1`

- **Producer**: `restaurant-service`.
- **Reason**: cascade temporary closure to all non-terminal
  branches.
- **Handler**: query non-terminal branches of the restaurant; for
  each, insert a `branch_temporary_closures` row with
  `reason_code = "parent_suspended"` and an end time far in the
  future (until the parent is reinstated); set `state =
  temporarily_closed`; emit `branch.temporary_closure.v1`.

### 4.3 `restaurant.reinstated.v1`

- **Producer**: `restaurant-service`.
- **Reason**: cascade re-instatement.
- **Handler**: clear the `parent_suspended` temporary closure;
  set `state = open`; emit `branch.temporary_closure.v1` with
  `data.closed = false`.

### 4.4 `restaurant.closed.v1`

- **Producer**: `restaurant-service`.
- **Reason**: cascade permanent closure.
- **Handler**: close all non-terminal branches of the
  restaurant; emit `branch.closed.v1`.

### 4.5 `zone.updated.v1`

- **Producer**: `zone-service`.
- **Reason**: serving zones may have changed; branches that fell
  out of a zone are auto-temporarily-closed.
- **Handler**: for each non-terminal branch, check if its
  `location` is still in any serving zone; if not, set
  `temporarily_closed` with `reason_code = "out_of_zone"` and
  end time far in the future (until the zone includes the branch
  again).

## 5. Reliability

- **Timeouts**: HTTP 1 s default; geocoding 3 s; DB 30 s; Kafka
  5 s.
- **Retries**: 3 attempts with exponential backoff and jitter;
  Idempotency-Key on all non-idempotent calls.
- **Circuit breakers**: 5 consecutive failures or 50% over 30 s;
  standard half-open.
- **Bulkheads**: per-downstream connection pools.
- **Outbox**: yes, `branch.outbox`.
- **Inbox**: yes, `branch.inbox`.
- **DLQ**: every topic has a paired `.dlq`; 30-day retention.
- **Reconciliation**: daily job in `reporting-service` checks
  that `branch.state = open` branches have at least one weekly
  hour row; opens tickets if not.

## 6. Correlation IDs

All requests carry `X-Correlation-Id`; the service propagates it
to outbound calls and embeds it in the event envelope. Logs and
traces are correlated.

## 7. Distributed Tracing

OpenTelemetry SDK; one root span per request; named
`POST /v1/branches`, etc. Propagated through Kafka. Sample 100%
on errors, 10% on success in production.

## 8. Threat Surface (per `SECURITY_ARCHITECTURE.md` §18)

| Threat | Mitigation |
|--------|------------|
| Spoofing | mTLS + JWT |
| Tampering (admin close) | HMAC-SHA256 signature; break-glass co-sign |
| Repudiation | audit log with actor, signature, correlation |
| Information disclosure | address is public; no PII |
| Denial of service | rate limits; circuit breakers |
| Elevation of privilege | resource-level ownership checks |


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
| [`cart-service`](../cart-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [`checkout-service`](../checkout-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [`configuration-service`](../configuration-service/README.md) | DEGRADABLE | degrade (cache / default / flag) |
| [`courier-dispatch-service`](../courier-dispatch-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [`dispatch-service`](../dispatch-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [`feature-flag-service`](../feature-flag-service/README.md) | DEGRADABLE | degrade (cache / default / flag) |
| [`food-order-service`](../food-order-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [`geolocation-service`](../geolocation-service/README.md) | DEGRADABLE | degrade (cache / default / flag) |
| [`identity-service`](../identity-service/README.md) | CRITICAL | 503 `DEPENDENCY_UNAVAILABLE` |
| [`menu-service`](../menu-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [`merchant-service`](../merchant-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [`notification-service`](../notification-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [`restaurant-order-mgmt-service`](../restaurant-order-mgmt-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [`restaurant-service`](../restaurant-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [`search-service`](../search-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [`zone-service`](../zone-service/README.md) | DEGRADABLE | degrade (cache / default / flag) |

### Downstream services that depend on this service

| Downstream | Class (from its perspective) |
|---|---|
| [`api-gateway`](../api-gateway/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`checkout-service`](../checkout-service/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`food-order-service`](../food-order-service/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`inventory-service`](../inventory-service/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`menu-service`](../menu-service/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`merchant-service`](../merchant-service/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`restaurant-order-mgmt-service`](../restaurant-order-mgmt-service/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`restaurant-service`](../restaurant-service/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`restaurant-staff-service`](../restaurant-staff-service/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |

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

