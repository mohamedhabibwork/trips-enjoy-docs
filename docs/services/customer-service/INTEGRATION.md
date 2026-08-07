# customer-service — Integration Contract

## 1. Inbound APIs

All endpoints require a JWT bearer token. Self-service
endpoints accept the gateway-injected `X-User-Id`.
Service-to-service endpoints require a
`client_credentials` token from `platform-services` with
the `customer.read` / `customer.write` /
`customer.read.any` client role.

### 1.1 `GET /v1/customers/{customer_id}`

- **Purpose**: get a customer.
- **Auth**: bearer (self — `X-User-Id == customer_id`; or
  service with `customer.read.any`).
- **Response (200)**:

  ```json
  {
    "id": "01HZX…",
    "identity_id": "01HZX…",
    "name": "Jane Doe",
    "email": "jane@example.com",
    "phone": "+31612345678",
    "kyc_tier": "tier_2",
    "kyc_verification_id": "01HZX…",
    "kyc_verified_at": "2026-06-15T10:42:11.183Z",
    "default_payment_method_id": "01HZX…",
    "default_address_id": "01HZX…",
    "primary_city_id": "01HZX…",
    "ltv_minor": 245000,
    "ltv_currency": "USD",
    "ltv_updated_at": "2026-07-29T10:42:11.183Z",
    "segment": "frequent",
    "rides_this_month": 23,
    "last_active_at": "2026-07-29T10:00:00.000Z",
    "status": "active",
    "created_at": "2026-01-15T10:42:11.183Z",
    "updated_at": "2026-07-29T10:42:11.183Z"
  }
  ```

- **Errors**: 401, 403, 404.

### 1.2 `POST /v1/customers`

- **Purpose**: create a customer (idempotent on
  `identity_id`).
- **Auth**: bearer (service).
- **Idempotency**: `Idempotency-Key` required.
- **Request**:

  ```json
  {
    "identity_id": "01HZX…",
    "name": "Jane Doe",
    "email": "jane@example.com",
    "phone": "+31612345678",
    "primary_city_id": "01HZX…"
  }
  ```

- **Response (201)**: as 1.1.
- **Errors**: 400, 401, 403, 409 (exists), 422.

### 1.3 `PATCH /v1/customers/{customer_id}`

- **Purpose**: update profile fields.
- **Auth**: bearer (self or admin).
- **Request**: any subset of `name`, `email`, `phone`,
  `primary_city_id`.
- **Response (200)**: as 1.1.
- **Errors**: 400, 401, 403, 404, 409 (row_version
  mismatch).

### 1.4 `GET /v1/customers/{customer_id}/kyc`

- **Purpose**: get KYC tier.
- **Auth**: bearer (self or service).
- **Response (200)**:

  ```json
  {
    "tier": "tier_2",
    "verification_id": "01HZX…",
    "verified_at": "2026-06-15T10:42:11.183Z",
    "limits": {
      "tier_0": 0,
      "tier_1": 50000,
      "tier_2": 500000,
      "tier_3": null
    }
  }
  ```

### 1.5 `POST /v1/customers/{customer_id}/kyc/upgrade`

- **Purpose**: request a KYC tier upgrade. The service
  sends the documents to the KYC provider; the provider
  returns the verified tier.
- **Auth**: bearer (self or admin).
- **Idempotency**: `Idempotency-Key` required.
- **Request**:

  ```json
  {
    "document_file_ids": ["01HZX…", "01HZX…"],
    "target_tier": "tier_3"
  }
  ```

- **Response (200)**:

  ```json
  {
    "tier": "tier_3",
    "verification_id": "01HZX…",
    "verified_at": "2026-07-29T10:42:11.183Z"
  }
  ```

- **Errors**: 400, 401, 403, 404, 422
  `KYC_DOCUMENTS_REQUIRED`, 502
  `DEPENDENCY_UPSTREAM_FAILURE`.

### 1.6 `POST /v1/customers/{customer_id}/suspend`

- **Purpose**: suspend a customer. Required: `reason`.
- **Auth**: bearer (admin `customer.admin`).
- **Idempotency**: required.
- **Request**:

  ```json
  {
    "reason": "fraud",
    "note": "Confirmed chargeback pattern"
  }
  ```

- **Response (200)**: the customer with `status:
  "suspended"`.
- **Errors**: 400, 401, 403, 404, 409 (already
  suspended).

### 1.7 `POST /v1/customers/{customer_id}/reinstate`

- **Purpose**: re-instate a suspended customer.
- **Auth**: bearer (admin).
- **Idempotency**: required.
- **Request**: `{ "note": "Investigation closed" }`.
- **Response (200)**: the customer with `status:
  "active"`.

### 1.8 `POST /v1/customers/{customer_id}/disable`

- **Purpose**: permanently disable a customer.
- **Auth**: bearer (admin `customer.admin` or
  `super_admin`).
- **Idempotency**: required.
- **Request**: `{ "reason": "legal", "note": "..." }`.
- **Response (200)**: the customer with `status:
  "disabled"`.

### 1.9 `POST /v1/customers/{customer_id}/erase`

- **Purpose**: GDPR right-to-erasure.
- **Auth**: bearer (admin `customer.admin` or
  `super_admin`).
- **Idempotency**: required.
- **Request**:

  ```json
  {
    "legal_basis": "user_request",
    "note": "GDPR Article 17"
  }
  ```

- **Response (200)**: the customer with `status:
  "erased"`, with `warnings[]` if there are active
  financial records.

### 1.10 `PUT /v1/customers/{customer_id}/default-payment-method/{payment_method_id}`

- **Purpose**: set the default payment method.
- **Auth**: bearer (self).
- **Response (200)**: the customer.
- **Errors**: 400, 401, 403 `FORBIDDEN` (method not
  owned), 404.

### 1.11 `PUT /v1/customers/{customer_id}/default-address/{address_id}`

- **Purpose**: set the default address.
- **Auth**: bearer (self).
- **Response (200)**: the customer.

## 2. Outbound APIs

| Target | Method | URI | Purpose | Timeout | Retry | Circuit |
|--------|--------|-----|---------|---------|-------|---------|
| `identity-service` | GET | `/v1/identities/{identity_id}` | read claims | 500ms | 2, exp backoff | yes |
| `payment-service` | GET | `/v1/payment-methods/{id}` | validate default method ownership | 500ms | 2 | yes |
| ``customer-service` (addresses)` | GET | `/v1/addresses/{id}` | validate default address ownership | 500ms | 2 | yes |
| `geolocation-service` | GET | `/v1/cities/{id}` | validate city | 500ms | 2 | yes |
| KYC provider | POST | `/v1/verifications` | submit documents | 10s | 1 | yes |
| KYC provider | GET | `/v1/verifications/{id}` | poll result | 2s | 3 | yes |
| `configuration-service` | GET | `/v1/configurations/customer.*` | read config | 500ms | 2 | yes |

## 3. Produced Events

All events use the standard envelope. The producer is
`customer-service`. The partition key is `aggregate_id`
(=`customer_id`).

### 3.1 `customer.created.v1`

- **Topic**: `customer.created`.
- **Trigger**: a new `customers` row is created.
- **Consumers**: `audit-service`, ``reporting-service` (data lake)`,
  `identity-service` (back-channel).
- **Data**:

  ```json
  {
    "customer_id": "01HZX…",
    "identity_id": "01HZX…",
    "kyc_tier": "tier_0",
    "primary_city_id": "01HZX…",
    "occurred_at": "2026-07-29T10:42:11.183Z"
  }
  ```

### 3.2 `customer.updated.v1`

- **Topic**: `customer.updated`.
- **Trigger**: any change to the customer profile.
- **Data**: the changed fields.

### 3.3 `customer.suspended.v1`

- **Topic**: `customer.suspended`.
- **Trigger**: a customer is suspended.
- **Consumers**: ``trip-service` (ride-request)`,
  `food-order-service`, ``food-order-service` (cart)`, `payment-service`,
  `notification-service`, `fraud-risk-service`,
  `audit-service`.
- **Data**:

  ```json
  {
    "customer_id": "01HZX…",
    "reason": "fraud",
    "suspended_by": "01HZX…",
    "occurred_at": "2026-07-29T10:42:11.183Z"
  }
  ```

### 3.4 `customer.disabled.v1`

Same as 3.3 with `status: "disabled"`. Consumers also
include ``admin-service` (support module)`.

### 3.5 `customer.reinstated.v1`

- **Topic**: `customer.reinstated`.
- **Consumers**: ``trip-service` (ride-request)`,
  `food-order-service`, ``food-order-service` (cart)`, `payment-service`,
  `notification-service`.

### 3.6 `customer.erased.v1`

- **Topic**: `customer.erased`.
- **Consumers**: `audit-service`, ``reporting-service` (data lake)`,
  every service that owns a profile.

### 3.7 `customer.segment.changed.v1`

- **Topic**: `customer.segment.changed`.
- **Trigger**: the segment changes.
- **Consumers**: ``pricing-service` (promotion)`, ``pricing-service` (loyalty rules) / `customer-service` (account)`,
  `pricing-service`, `notification-service`.
- **Data**:

  ```json
  {
    "customer_id": "01HZX…",
    "from_segment": "standard",
    "to_segment": "frequent",
    "trigger": "rides_count_change",
    "occurred_at": "2026-07-29T10:42:11.183Z"
  }
  ```

### 3.8 `customer.kyc.tier_changed.v1`

- **Topic**: `customer.kyc.tier_changed`.
- **Trigger**: the KYC tier changes.
- **Consumers**: `payment-service`, ``trip-service` (ride-request)`,
  `food-order-service`, `notification-service`.
- **Data**:

  ```json
  {
    "customer_id": "01HZX…",
    "from_tier": "tier_1",
    "to_tier": "tier_2",
    "verification_id": "01HZX…",
    "actor": "01HZX…",
    "occurred_at": "2026-07-29T10:42:11.183Z"
  }
  ```

## 4. Consumed Events

### 4.1 `identity.user.created.v1`

- **Producer**: `identity-service`.
- **Reason**: back-channel — ensure a `customers` row
  exists.
- **Handler**: upsert if missing; pull claims for
  defaults.
- **Deduplication**: idempotent on `identity_id`.

### 4.2 `identity.user.updated.v1`

- **Reason**: refresh cached claims.
- **Handler**: update cached fields.

### 4.3 `identity.user.suspended.v1`

- **Handler**: set `status='suspended'` and
  `suspended_reason` mirror; emit
  `customer.suspended.v1` only if the change is new
  (idempotency on `identity_id`).

### 4.4 `identity.user.disabled.v1`

- **Handler**: set `status='disabled'`; emit
  `customer.disabled.v1`.

### 4.5 `identity.user.reinstated.v1`

- **Handler**: set `status='active'`; emit
  `customer.reinstated.v1`.

### 4.6 `identity.user.erased.v1`

- **Handler**: anonymize the row; emit
  `customer.erased.v1`.

### 4.7 `payment.method.saved.v1`

- **Producer**: `payment-service`.
- **Reason**: track default payment method.
- **Handler**: if the `payment_method_id` is the
  customer's most-recent and the customer has no
  default, set the default; emit
  `customer.updated.v1`.

### 4.8 `payment.method.removed.v1`

- **Handler**: if the `payment_method_id` is the
  current default, clear it; emit
  `customer.updated.v1`.

### 4.9 `ride.payment.completed.v1`

- **Producer**: ``payment-service` (ride saga)`.
- **Handler**: increment LTV by the payment amount;
  emit `customer.updated.v1` if LTV change crosses a
  segment threshold (triggering a segment change
  via the recomputation logic).

### 4.10 `food.payment.completed.v1`

Same as 4.9 for food orders.

### 4.11 `configuration.updated.v1`

- **Reason**: hot-reload customer config.
- **Handler**: reload in-process config.

## 5. Reliability

- **Timeouts**: 500 ms for `identity-service`,
  `payment-service`, ``customer-service` (addresses)`,
  `geolocation-service`, configuration reads; 10 s
  for KYC provider submit; 2 s for KYC poll.
- **Retries**: 3 with exponential backoff for upstream
  REST; 1 for KYC submit.
- **Circuit breakers**: per upstream; default open
  after 5 failures in 10 s, reset after 30 s.
- **Bulkheads**: per-upstream concurrency cap; default
  50.
- **Outbox**: yes; poller single-writer per replica.
- **Inbox**: yes; keyed by `event_id`; TTL 24 h.
- **DLQ**: one per topic; retention 30 days.
- **Reconciliation**: a daily job in
  `reporting-service` reconciles `customers` row
  count against `identities` row count; drift opens
  a ticket.

## 6. Correlation IDs

All requests carry `X-Correlation-Id`; emitted events
carry the same in the envelope. The
`customer_audit_log.correlation_id` column links the
action to the originating request.

## 7. Distributed Tracing

OpenTelemetry. One root span per request. Spans for
upstream calls, DB queries, Redis lookups, Kafka
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
[`DOWNSTREAM_ERROR_CATALOG.md` 5](../../architecture/DOWNSTREAM_ERROR_CATALOG.md)
(forward verbatim, translate, degrade, or reject) and includes
a `downstream` block identifying the original source.

### Upstream services this service depends on

| Upstream | Class | Behavior on failure |
|---|---|---|
| [``customer-service` (addresses)`](../customer-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [`admin-service`](../admin-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [``reporting-service` (data lake)`](../reporting-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [`api-gateway`](../api-gateway/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [`audit-service`](../audit-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [``food-order-service` (cart)`](../food-order-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [`configuration-service`](../configuration-service/README.md) | DEGRADABLE | degrade (cache / default / flag) |
| [`file-service`](../file-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [`food-order-service`](../food-order-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [``payment-service` (food saga)`](../payment-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [`fraud-risk-service`](../fraud-risk-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [`geolocation-service`](../geolocation-service/README.md) | DEGRADABLE | degrade (cache / default / flag) |
| [`identity-service`](../identity-service/README.md) | CRITICAL | 503 `DEPENDENCY_UNAVAILABLE` |
| [`ledger-service`](../ledger-service/README.md) | CRITICAL | 503 `DEPENDENCY_UNAVAILABLE` |
| [`pricing-service`](../pricing-service/README.md) · [`customer-service`](../customer-service/README.md) (loyalty rules / account) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [`notification-service`](../notification-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [`payment-service`](../payment-service/README.md) | CRITICAL | 503 `DEPENDENCY_UNAVAILABLE` |
| [`pricing-service`](../pricing-service/README.md) | CRITICAL | 503 `DEPENDENCY_UNAVAILABLE` |
| [``pricing-service` (promotion)`](../pricing-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [`trip-service`](../trip-service/README.md) · [`food-order-service`](../food-order-service/README.md) · [`search-service`](../search-service/README.md) (review projections) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| _…and 5 more (see [`SERVICE_ISOLATION.md` 2](../../architecture/SERVICE_ISOLATION.md))_ | | |

### Downstream services that depend on this service

| Downstream | Class (from its perspective) |
|---|---|
| [``customer-service` (addresses)`](../customer-service/README.md) | _see [`SERVICE_ISOLATION.md` 2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`api-gateway`](../api-gateway/README.md) | _see [`SERVICE_ISOLATION.md` 2](../../architecture/SERVICE_ISOLATION.md)_ |
| [``food-order-service` (cart)`](../food-order-service/README.md) | _see [`SERVICE_ISOLATION.md` 2](../../architecture/SERVICE_ISOLATION.md)_ |
| [``food-order-service` (checkout)`](../food-order-service/README.md) | _see [`SERVICE_ISOLATION.md` 2](../../architecture/SERVICE_ISOLATION.md)_ |
| [``notification-service` (provider ACL)`](../notification-service/README.md) | _see [`SERVICE_ISOLATION.md` 2](../../architecture/SERVICE_ISOLATION.md)_ |
| [``courier-service` (delivery)`](../courier-service/README.md) | _see [`SERVICE_ISOLATION.md` 2](../../architecture/SERVICE_ISOLATION.md)_ |
| [``configuration-service` (flags)`](../configuration-service/README.md) | _see [`SERVICE_ISOLATION.md` 2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`file-service`](../file-service/README.md) | _see [`SERVICE_ISOLATION.md` 2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`food-order-service`](../food-order-service/README.md) | _see [`SERVICE_ISOLATION.md` 2](../../architecture/SERVICE_ISOLATION.md)_ |
| [``payment-service` (food saga)`](../payment-service/README.md) | _see [`SERVICE_ISOLATION.md` 2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`fraud-risk-service`](../fraud-risk-service/README.md) | _see [`SERVICE_ISOLATION.md` 2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`identity-service`](../identity-service/README.md) | _see [`SERVICE_ISOLATION.md` 2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`pricing-service`](../pricing-service/README.md) · [`customer-service`](../customer-service/README.md) (loyalty rules / account) | _see [`SERVICE_ISOLATION.md` 2](../../architecture/SERVICE_ISOLATION.md)_ |
| [``restaurant-service` (merchant)`](../restaurant-service/README.md) | _see [`SERVICE_ISOLATION.md` 2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`notification-service`](../notification-service/README.md) | _see [`SERVICE_ISOLATION.md` 2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`payment-service`](../payment-service/README.md) | _see [`SERVICE_ISOLATION.md` 2](../../architecture/SERVICE_ISOLATION.md)_ |
| [``pricing-service` (promotion)`](../pricing-service/README.md) | _see [`SERVICE_ISOLATION.md` 2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`reporting-service`](../reporting-service/README.md) | _see [`SERVICE_ISOLATION.md` 2](../../architecture/SERVICE_ISOLATION.md)_ |
| [``food-order-service` (queue)`](../food-order-service/README.md) | _see [`SERVICE_ISOLATION.md` 2](../../architecture/SERVICE_ISOLATION.md)_ |
| [``trip-service` (history)`](../trip-service/README.md) | _see [`SERVICE_ISOLATION.md` 2](../../architecture/SERVICE_ISOLATION.md)_ |
| _…and 10 more_ | |

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
- [`../../../main.md`](../../../main.md) — top-level platform specification (architecture, Keycloak, PostgreSQL 18, messaging, observability baseline)

## Conductor Workers

This service runs Conductor workers for the following workflows per
[ADR-0018](../architecture/adrs/0018-workflow-engine-conductor.md) and
[`shared/CONDUCTOR_WORKFLOWS.md`](../shared/CONDUCTOR_WORKFLOWS.md).
Workers are colocated in this service's binary; SDK: **conductor-kotlin v3.x**.

| Workflow ID | Tasks owned | Idempotency-Key namespace |
|---|---|---|
| Workflow ID | Tasks owned | Idempotency-Key namespace |
|---|---|---|
| `wf.refund.standard.v1` | customer_service_refund_notification | `refund:{refund_id}:customer:notif` |
| `wf.refund.partial.v1` | customer_service_refund_notification | `refund:{refund_id}:customer:notif` |


### Kafka signal mapping

| Topic | Signal | Triggers |
|---|---|---|
| (no inbound Kafka signals — REST trigger only or worker is reactive to conductor-kafka-bridge events) | – | – |


### Compensation responsibilities

This service implements the following compensation tasks; see
[`shared/CONDUCTOR_WORKFLOWS.md`](../shared/CONDUCTOR_WORKFLOWS.md) 4 for
ordering rules.

| Forward task | Compensation task | Reversibility |
|---|---|---|
| (no compensation — terminal states only, or compensation is no-op) | – | – |


### Configuration keys

- `conductor.server.url` — set by Helm per env (e.g. `https://conductor.prod.uber.io`)
- `conductor.task.<task_name>.timeout_seconds` — default 30s
- `conductor.task.<task_name>.retry_count` — default 3
- `conductor.worker.heartbeat_interval_seconds` — default 5s
- `conductor.kafka.bridge.url` — for `conductor-kafka-bridge` integration

### Operational references

- Runbook: [`shared/CONDUCTOR_WORKFLOWS.md`](../shared/CONDUCTOR_WORKFLOWS.md) 8
- Observability: [`shared/CONDUCTOR_WORKFLOWS.md`](../shared/CONDUCTOR_WORKFLOWS.md) 7
- Master task registry: [`MASTER_TASK.md`](../MASTER_TASK.md) 7-9
