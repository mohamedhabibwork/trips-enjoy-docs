# Loyalty Service — Integration Contract

## 1. Inbound APIs

All endpoints are versioned under `/v1`. Auth: bearer JWT (RS256,
Keycloak JWKS). Errors use the standard envelope.

### 1.1 `GET /v1/accounts/{customer_id}`

- **Purpose**: Read balance + tier.
- **Auth**: Bearer JWT. Required scope: `loyalty.read`.
- **Response (200)**:
  ```json
  {
    "customer_id": "01HZX…",
    "balance": 4321,
    "lifetime_earned": 12000,
    "lifetime_burned": 7679,
    "tier": "gold",
    "qualifying_spend_minor": 45000,
    "currency": "EUR",
    "tier_window_start": "2026-04-30T00:00:00Z",
    "last_tier_change_at": "2026-06-15T10:00:00Z",
    "points_value_minor": 21
  }
  ```
  `points_value_minor` is the conversion rate: 100 points = 1 EUR
  = 100 minor units, so 1 point ≈ 1 minor unit. The pricing engine
  multiplies `points_to_burn * points_value_minor / 100`.
- **Errors**: 401 / 403 / 404.

### 1.2 `GET /v1/accounts/{customer_id}/transactions`

- **Purpose**: Statement.
- **Auth**: Bearer JWT. Required scope: `loyalty.read`.
- **Query params**: `cursor`, `limit`, `from`, `to`.
- **Response (200)**:
  ```json
  {
    "items": [
      {
        "id": "01HZX…",
        "type": "earn",
        "points_delta": 120,
        "balance_after": 4321,
        "source_type": "trip",
        "ride_id": "01HZX…",
        "applied_at": "2026-07-29T10:42:11.183Z"
      }
    ],
    "next_cursor": "eyJ…",
    "has_more": true
  }
  ```
- **Errors**: 401 / 403 / 404.

### 1.3 `POST /v1/accounts/{customer_id}/earn`

- **Purpose**: Earn points.
- **Auth**: Bearer JWT. Required scope: `loyalty.earn`.
- **Idempotency**: `Idempotency-Key` required.
- **Request**:
  ```json
  {
    "points": 120,
    "source_event_id": "01HZX…",
    "source_type": "trip",
    "ride_id": "01HZX…",
    "description": "Ride earn"
  }
  ```
- **Response (200)**:
  ```json
  {
    "transaction_id": "01HZX…",
    "balance_after": 4441,
    "tier": "gold"
  }
  ```
- **Errors**: 401 / 403 / 404 / 409 / 422.

### 1.4 `POST /v1/accounts/{customer_id}/burn`

- **Purpose**: Burn points.
- **Auth**: Bearer JWT. Required scope: `loyalty.burn`.
- **Idempotency**: `Idempotency-Key` required.
- **Request**:
  ```json
  {
    "points": 500,
    "source_event_id": "01HZX…",
    "source_type": "cart",
    "cart_id": "01HZX…",
    "description": "Burn at checkout"
  }
  ```
- **Response (200)**:
  ```json
  {
    "transaction_id": "01HZX…",
    "balance_after": 3941,
    "tier": "gold"
  }
  ```
- **Errors**:
  - 401 / 403 / 404.
  - 409 `INSUFFICIENT_POINTS`.
  - 422 `IDEMPOTENCY_KEY_REUSED`.

### 1.5 `POST /v1/accounts/{customer_id}/adjust`

- **Purpose**: Manual adjust (admin).
- **Auth**: Bearer JWT. Required role: `loyalty.admin`. Required
  header: `X-Audit-Reason`, `X-Signature`.
- **Idempotency**: `Idempotency-Key` required.
- **Request**:
  ```json
  {
    "points_delta": 1000,
    "reason": "Compensation for cancelled ride"
  }
  ```
- **Response (200)**: confirmation.
- **Errors**: 400 / 401 / 403 / 404.

### 1.6 `GET /v1/tiers`

- **Purpose**: List tier definitions.
- **Auth**: Bearer JWT. Required scope: `loyalty.read`.
- **Response (200)**: array of tier objects (name, threshold,
  benefits).
- **Errors**: 401 / 403.

### 1.7 `GET /v1/accounts/{customer_id}/frequent-zones?window_days=30`

- **Purpose**: Return the customer's frequent zones for the
  rolling window. Powers the `pricing-service` loyalty-discount
  hot path.
- **Auth**: Bearer JWT. Required scope: `loyalty.read` (service).
  Pricing hot path uses client-credentials with `pricing.read`.
- **Query params**: `window_days` (default 30, min 7, max 90).
- **Response (200)**:
  ```json
  {
    "customer_id": "01HZX…",
    "window_days": 30,
    "tenant_id": "global",
    "items": [
      {
        "zone_id": "01HZX…",
        "city_id": "amsterdam",
        "trip_count": 12,
        "tier": "gold",
        "last_trip_at": "2026-08-03T10:42:11.183Z"
      }
    ],
    "last_computed_at": "2026-08-04T03:00:00.000Z",
    "cache_hit": true,
    "correlation_id": "01HZX9C7T0XK2P9F0V6E4B1MZA"
  }
  ```
- **Errors**: 401 / 403 / 404.

## 2. Outbound APIs

| Target | Method | URI | Purpose | Timeout | Retry | Circuit |
|--------|--------|-----|---------|---------|-------|---------|
| `configuration-service` | GET | `/v1/configurations/{key}` | read rules | 1s | 3 | yes |
| `customer-service` | GET | `/v1/customers/{id}` | read customer (tier eligibility) | 1s | 3 | yes |

## 3. Produced Events

### 3.1 `loyalty.points.earned.v1`

- **Producer**: `loyalty-service`.
- **Topic**: `loyalty.points.earned`.
- **Trigger**: every successful earn.
- **Schema version**: 1.
- **Partition key**: `customer_id`.
- **Schema**:
  ```json
  {
    "event_id": "01HZX9C8W6K0G3V2Y5N1Q4R7PB",
    "event_name": "loyalty.points.earned.v1",
    "occurred_at": "2026-07-29T10:42:11.183Z",
    "schema_version": 1,
    "producer": "loyalty-service",
    "tenant_id": "global",
    "correlation_id": "01HZX9C7T0XK2P9F0V6E4B1MZA",
    "aggregate_type": "LoyaltyAccount",
    "aggregate_id": "01HZX9C5S3B1L7K0P2F8V4T6YDA",
    "data": {
      "customer_id": "01HZX…",
      "points_delta": 120,
      "balance_after": 4441,
      "source_type": "trip",
      "ride_id": "01HZX…",
      "tier": "gold"
    }
  }
  ```
- **Retry / DLQ**: outbox / `loyalty.points.earned.dlq`.

### 3.2 `loyalty.points.burned.v1`

Same shape as 3.1 with negative `points_delta`, `cart_id`, etc.

### 3.3 `loyalty.tier.changed.v1`

- **Producer**: `loyalty-service`.
- **Topic**: `loyalty.tier.changed`.
- **Trigger**: every tier change.
- **Schema version**: 1.
- **Partition key**: `customer_id`.
- **Schema**:
  ```json
  {
    "event_id": "01HZX9C8W6K0G3V2Y5N1Q4R7PB",
    "event_name": "loyalty.tier.changed.v1",
    "occurred_at": "2026-07-29T10:42:11.183Z",
    "schema_version": 1,
    "producer": "loyalty-service",
    "tenant_id": "global",
    "correlation_id": "01HZX9C7T0XK2P9F0V6E4B1MZA",
    "aggregate_type": "LoyaltyAccount",
    "aggregate_id": "01HZX9C5S3B1L7K0P2F8V4T6YDA",
    "data": {
      "customer_id": "01HZX…",
      "from_tier": "silver",
      "to_tier": "gold",
      "qualifying_spend_minor": 45000,
      "changed_at": "2026-07-29T10:42:11.183Z"
    }
  }
  ```
- **Retry / DLQ**: outbox.

### 3.4 `loyalty.frequent_zone.aggregated.v1`

- **Producer**: `loyalty-service`.
- **Topic**: `loyalty.frequent_zone.aggregated`.
- **Trigger**: a customer's frequent-zone aggregation changed
  (debounced daily at 03:00 UTC).
- **Schema version**: 1.
- **Partition key**: `customer_id`.
- **Consumers**: `pricing-service` (warms the loyalty
  frequent-rider cache), `analytics-service`, `reporting-service`.
- **Schema**:
  ```json
  {
    "event_id": "01HZX9C8W6K0G3V2Y5N1Q4R7PB",
    "event_name": "loyalty.frequent_zone.aggregated.v1",
    "occurred_at": "2026-08-04T03:00:00.000Z",
    "schema_version": 1,
    "producer": "loyalty-service",
    "tenant_id": "global",
    "correlation_id": "01HZX9C7T0XK2P9F0V6E4B1MZA",
    "aggregate_type": "LoyaltyFrequentZone",
    "aggregate_id": "01HZX9C5S3B1L7K0P2F8V4T6YDA",
    "data": {
      "customer_id": "01HZX…",
      "window_days": 30,
      "window_end": "2026-08-04T00:00:00Z",
      "items": [
        {
          "zone_id": "01HZX…",
          "city_id": "amsterdam",
          "trip_count": 12,
          "tier": "gold",
          "last_trip_at": "2026-08-03T10:42:11.183Z"
        }
      ]
    }
  }
  ```
- **Retry**: outbox, 3 attempts.
- **DLQ**: `loyalty.frequent_zone.aggregated.dlq`.

## 4. Consumed Events

### 4.1 `trip.completed.v1`

- **Producer**: `trip-service`.
- **Reason**: earn points on trip completion.
- **Handler**: lookup earn rule by ride type / city / region; compute
  `points = base * tier_multiplier`; insert into `transactions` with
  `source_event_id = event_id`; emit `loyalty.points.earned.v1`.
- **Deduplication**: inbox on `event_id`; UNIQUE on
  `(customer_id, source_event_id)`.
- **Retry**: 3.
- **Failure**: DLQ.

### 4.2 `food.order.delivered.v1`

- **Producer**: `delivery-service`.
- **Reason**: earn points on order delivery.
- **Handler**: same shape as 4.1, with `order_id`.
- **Deduplication / Retry / Failure**: same.

### 4.3 `customer.suspended.v1`

- **Producer**: `customer-service`.
- **Reason**: block earn / burn.
- **Handler**: `UPDATE accounts SET blocked = true WHERE customer_id = ?`.
- **Deduplication / Retry / Failure**: same.

### 4.4 `configuration.updated.v1`

- **Producer**: `configuration-service`.
- **Reason**: reload rule cache.
- **Handler**: invalidate cache for the affected key; reload on next
  read.
- **Deduplication / Retry / Failure**: same.

## 5. Reliability

- **Timeouts**: HTTP 1s; DB 30s; Kafka publish 5s.
- **Retries**: bounded 3 with exponential backoff + jitter.
- **Circuit breakers**: every outbound; on `CIRCUIT_OPEN`, fall back
  to cached values; on cache miss, the earn / burn endpoint returns
  503 unless the call is idempotent.
- **Bulkheads**: separate outbound pool per dependency.
- **Outbox**: yes.
- **Inbox**: yes.
- **DLQ**: every topic above has a paired DLQ.
- **Reconciliation**: daily job verifies that the sum of
  `transactions.points_delta` equals the account's `balance`; drift
  opens a `support.ticket`.

## 6. Correlation IDs

Every request carries `X-Correlation-Id`; the service returns it in
the response header and embeds it in the event envelope and the
`audit_log` row.

## 7. Distributed Tracing

OpenTelemetry: one root span per request; child spans for DB, Redis,
Kafka. `traceparent` propagated through Kafka headers. Sample rate
100% for errors, 10% for successes.


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
| [`analytics-service`](../analytics-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [`cart-service`](../cart-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [`configuration-service`](../configuration-service/README.md) | DEGRADABLE | degrade (cache / default / flag) |
| [`customer-service`](../customer-service/README.md) | CRITICAL | 503 `DEPENDENCY_UNAVAILABLE` |
| [`delivery-service`](../delivery-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [`feature-flag-service`](../feature-flag-service/README.md) | DEGRADABLE | degrade (cache / default / flag) |
| [`food-order-service`](../food-order-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [`payment-service`](../payment-service/README.md) | CRITICAL | 503 `DEPENDENCY_UNAVAILABLE` |
| [`pricing-service`](../pricing-service/README.md) | CRITICAL | 503 `DEPENDENCY_UNAVAILABLE` |
| [`promotion-service`](../promotion-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [`trip-service`](../trip-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [`wallet-service`](../wallet-service/README.md) | CRITICAL | 503 `DEPENDENCY_UNAVAILABLE` |

### Downstream services that depend on this service

| Downstream | Class (from its perspective) |
|---|---|
| [`customer-service`](../customer-service/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`trip-service`](../trip-service/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |

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

