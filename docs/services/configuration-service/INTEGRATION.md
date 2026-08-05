# Configuration Service — Integration Contract

## 1. Inbound APIs

All endpoints are versioned under `/v1`. Auth: bearer JWT (RS256,
Keycloak JWKS). Errors use the standard envelope
(`code`, `message`, `correlationId`, `details[]`).

### 1.1 `GET /v1/configurations/{key}`

- **Purpose**: Read the latest value of a configuration key, resolved
  against the provided evaluation context.
- **Auth**: Bearer JWT. Required scope: `config.read`.
- **Query params**:
  - `tenant_id` (optional, default from token).
  - `city`, `ride_type`, `customer_segment`, `restaurant_id`,
    `branch_id`, `merchant_id`, `user_id` (optional; the resolution
    uses the precedence rules in
    `docs/architecture/CONFIGURATION_ARCHITECTURE.md`).
  - `at=<rfc3339>` (optional; read at a point in time).
  - `nocache=1` (optional; bypass Redis).
- **Response (200)**:
  ```json
  {
    "key": "pricing.base_fare",
    "value": { "amount_minor": 250, "currency": "EUR" },
    "matched_scope_type": "city",
    "matched_scope_id": "amsterdam",
    "version": 4123,
    "schema_version": 1,
    "resolved_at": "2026-07-29T10:42:11.183Z",
    "correlation_id": "01HZX9C7T0XK2P9F0V6E4B1MZA"
  }
  ```
- **Errors**:
  - 400 `VALIDATION_FAILED` (bad query).
  - 401 `UNAUTHENTICATED`.
  - 403 `FORBIDDEN`.
  - 404 `CONFIG_KEY_NOT_FOUND`.
  - 503 `CIRCUIT_OPEN` (long-poll limit reached).

### 1.2 `PUT /v1/configurations/{key}/versions`

- **Purpose**: Create a new version of a configuration key.
- **Auth**: Bearer JWT. Required role: `config.admin`. Required
  header: `X-Audit-Reason`. High-value mutations also require
  `X-Signature` (HMAC-SHA256, per-tenant secret).
- **Idempotency**: `Idempotency-Key` required.
- **Request**:
  ```json
  {
    "value": { "amount_minor": 275, "currency": "EUR" },
    "scope_type": "city",
    "scope_id": "amsterdam",
    "cohort": null,
    "effective_from": null,
    "effective_to": null,
    "expected_current_version": 4123
  }
  ```
- **Response (201)**:
  ```json
  {
    "document_id": "01HZX9C5S3B1L7K0P2F8V4T6YDA",
    "key": "pricing.base_fare",
    "version": 4124,
    "value": { "amount_minor": 275, "currency": "EUR" },
    "matched_scope_type": "city",
    "matched_scope_id": "amsterdam",
    "impact": {
      "consumers_reloading": [
        "pricing-service", "ride-request-service", "checkout-service"
      ]
    },
    "correlation_id": "01HZX9C7T0XK2P9F0V6E4B1MZA"
  }
  ```
- **Errors**:
  - 400 `VALIDATION_FAILED` / `AUDIT_REASON_REQUIRED`.
  - 401 `UNAUTHENTICATED`.
  - 403 `FORBIDDEN` / `SIGNATURE_INVALID`.
  - 404 `CONFIG_KEY_NOT_FOUND` (when key is new, use `POST /v1/configurations`).
  - 409 `VERSION_CONFLICT` (current version mismatch).
  - 422 `VALIDATION_FAILED` (schema mismatch) with field `details[]`.
  - 422 `IDEMPOTENCY_KEY_REUSED`.

### 1.3 `POST /v1/configurations`

- **Purpose**: Create a new configuration key (and its first version).
- **Auth**: Bearer JWT. Required role: `config.admin`.
- **Idempotency**: `Idempotency-Key` required.
- **Request**:
  ```json
  {
    "key": "restaurant.max_active_orders",
    "schema": {
      "type": "integer",
      "minimum": 1,
      "maximum": 1000
    },
    "value": 50,
    "scope_type": "global",
    "scope_id": null,
    "reason": "Initial rollout"
  }
  ```
- **Response (201)**:
  ```json
  {
    "document_id": "01HZX9C5S3B1L7K0P2F8V4T6YDA",
    "key": "restaurant.max_active_orders",
    "version": 1,
    "schema_version": 1,
    "correlation_id": "01HZX9C7T0XK2P9F0V6E4B1MZA"
  }
  ```
- **Errors**: as above + 409 `CONFIG_KEY_EXISTS`.

### 1.4 `POST /v1/configurations/{key}/rollback`

- **Purpose**: Revert to a prior version.
- **Auth**: Bearer JWT. Required role: `config.admin`. High-value:
  signature + step-up MFA.
- **Idempotency**: `Idempotency-Key` required.
- **Request**:
  ```json
  {
    "to_version": 4120,
    "reason": "Bad surge cap change"
  }
  ```
- **Response (201)**: same shape as 1.2 with the rolled-back value.
- **Errors**: as above + 404 `VERSION_NOT_FOUND`.

### 1.5 `GET /v1/configurations/{key}/versions`

- **Purpose**: Read the history of a key.
- **Auth**: Bearer JWT. Required role: `config.audit`.
- **Query params**: `cursor`, `limit` (default 20, max 100), `from`,
  `to`.
- **Response (200)**:
  ```json
  {
    "items": [
      {
        "version": 4123,
        "scope_type": "city",
        "scope_id": "amsterdam",
        "value": { "amount_minor": 250, "currency": "EUR" },
        "actor_id": "01HZX…",
        "reason": "Quarterly review",
        "created_at": "2026-07-15T09:00:00.000Z",
        "superseded_at": "2026-07-29T10:42:11.183Z"
      }
    ],
    "next_cursor": "eyJ…",
    "has_more": true
  }
  ```
- **Errors**: 401 / 403 / 404.

### 1.6 `GET /v1/configurations/stream`

- **Purpose**: Long-poll update stream for one or more keys.
- **Auth**: Bearer JWT. Required scope: `config.subscribe`.
- **Query params**:
  - `keys=pricing.base_fare,pricing.surge.max_multiplier`
  - `since_version=<int>` (returns immediately if a newer version is
    available).
  - `wait_seconds` (default `LONGPOLL_MAX_WAIT_SECONDS`).
- **Response (200)**:
  ```json
  {
    "updates": [
      {
        "key": "pricing.base_fare",
        "version": 4124,
        "value": { "amount_minor": 275, "currency": "EUR" },
        "changed_at": "2026-07-29T10:42:11.183Z"
      }
    ],
    "next_since_version": 4124
  }
  ```
- **Behavior**: holds the connection open until any subscribed key
  changes, or until `wait_seconds` elapses (then 200 with empty
  updates).
- **Errors**: 401 / 403 / 503 `CIRCUIT_OPEN`.

### 1.7 `GET /v1/configurations/snapshot`

- **Purpose**: Bulk read for a service's known keys in one round-trip.
- **Auth**: Bearer JWT. Required scope: `config.read`.
- **Query params**: `service=pricing-service&keys=pricing.base_fare,…`
  (or `tenant_id=…&all=1` for the full tenant view).
- **Response (200)**:
  ```json
  {
    "tenant_id": "global",
    "as_of": "2026-07-29T10:42:11.183Z",
    "values": {
      "pricing.base_fare": {
        "value": { "amount_minor": 275, "currency": "EUR" },
        "version": 4124,
        "matched_scope_type": "city",
        "matched_scope_id": "amsterdam"
      }
    }
  }
  ```
- **Errors**: 401 / 403.

### 1.8 `GET /v1/channels/{channel}/configurations`

- **Purpose**: Filtered subset for a mobile / web client.
- **Auth**: Bearer JWT.
- **Path params**: `channel` (e.g. `customer_app_en`,
  `driver_app_ar`).
- **Response (200)**:
  ```json
  {
    "channel": "customer_app_en",
    "as_of": "2026-07-29T10:42:11.183Z",
    "values": {
      "ui.theme.primary": "#0F62FE",
      "ui.copy.welcome": "Welcome to Uber"
    }
  }
  ```
- **Errors**: 401 / 403 / 404 `CHANNEL_NOT_FOUND`.

### 1.9 `POST /v1/configurations/{key}/deprecate`

- **Purpose**: Mark a key as deprecated (consumers still receive it
  but a warning is emitted).
- **Auth**: Bearer JWT. Required role: `config.admin`.
- **Idempotency**: `Idempotency-Key` required.
- **Request**:
  ```json
  { "reason": "Replaced by pricing.surge.max_v2" }
  ```
- **Response (200)**: confirmation envelope.
- **Errors**: 401 / 403 / 404.

## 2. Outbound APIs

This service does not call other services synchronously. The only
outbound call is the broker publish (see §3).

| Target | Method | URI | Purpose | Timeout | Retry | Circuit |
|--------|--------|-----|---------|---------|-------|---------|
| n/a    | n/a    | n/a | n/a     | n/a     | n/a   | n/a     |

## 3. Produced Events

### 3.1 `configuration.updated.v1`

- **Producer**: `configuration-service`.
- **Topic**: `configuration.updated`.
- **Trigger**: any successful write of a new version.
- **Schema version**: 1.
- **Partition key**: `document_id`.
- **Consumers**: every service.
- **Schema**:
  ```json
  {
    "event_id": "01HZX9C8W6K0G3V2Y5N1Q4R7PB",
    "event_name": "configuration.updated.v1",
    "occurred_at": "2026-07-29T10:42:11.183Z",
    "schema_version": 1,
    "producer": "configuration-service",
    "tenant_id": "global",
    "correlation_id": "01HZX9C7T0XK2P9F0V6E4B1MZA",
    "causation_id": null,
    "aggregate_type": "ConfigurationDocument",
    "aggregate_id": "01HZX9C5S3B1L7K0P2F8V4T6YDA",
    "data": {
      "key": "pricing.base_fare",
      "version": 4124,
      "old_version": 4123,
      "value": { "amount_minor": 275, "currency": "EUR" },
      "scope_type": "city",
      "scope_id": "amsterdam",
      "actor_id": "01HZX…",
      "reason": "Quarterly review"
    }
  }
  ```
- **Retry**: outbox poller, 3 attempts, exponential backoff.
- **DLQ**: `configuration.updated.dlq`.

The payload of `configuration.updated.v1` is **generic over the
key prefix** — this service does not interpret the values. Three
key families introduced by the recent rating-density /
loyalty-discount / geo-config work are routed to their owning
consumers as follows:

- `trip.reward.*` — consumed by `trip-service` (the per-trip
  guaranteed-reward engine). The `trip.reward.user.kind`
  discriminator (`wallet_credit` / `loyalty_credit` / `none`)
  decides which downstream service consumes
  `trip.reward.granted.v1` for the user-side credit.
- `pricing.rating_density.*` — consumed by `pricing-service`
  (rating-density multipliers for fare calculation).
  `pricing-service` separately consumes the
  `review.zone_aggregated.v1` event from `review-rating-service`
  to warm its rating-density cache.
- `pricing.loyalty.frequent_rider.*` — consumed by
  `pricing-service` (frequent-rider loyalty thresholds and
  bonuses). `pricing-service` separately consumes the
  `loyalty.frequent_zone.aggregated.v1` event from
  `loyalty-service` to warm its loyalty-frequent cache.
- `pricing.geo_overrides.*` — operator-friendly pointer that
  mirrors the *head* `geo_config` value which is actually owned
  by `admin-service` and published via
  `pricing.geo_config.updated.v1` (see
  `admin-service/INTEGRATION.md` §3.6). The pointer is the
  read-side convenience; the authoritative CRUD is in
  `admin-service`.

### 3.2 `configuration.rolled_back.v1`

- **Producer**: `configuration-service`.
- **Topic**: `configuration.rolled_back`.
- **Trigger**: a rollback.
- **Schema version**: 1.
- **Partition key**: `document_id`.
- **Schema**: same as 3.1 with `data.action = "rollback"`,
  `data.from_version`, `data.to_version`.
- **Retry**: outbox.
- **DLQ**: `configuration.rolled_back.dlq`.

### 3.3 `configuration.key.deprecated.v1`

- **Producer**: `configuration-service`.
- **Topic**: `configuration.key.deprecated`.
- **Trigger**: `POST /v1/configurations/{key}/deprecate`.
- **Schema version**: 1.
- **Partition key**: `document_id`.
- **Schema**:
  ```json
  {
    "event_id": "01HZX9C8W6K0G3V2Y5N1Q4R7PB",
    "event_name": "configuration.key.deprecated.v1",
    "occurred_at": "2026-07-29T10:42:11.183Z",
    "schema_version": 1,
    "producer": "configuration-service",
    "tenant_id": "global",
    "correlation_id": "01HZX9C7T0XK2P9F0V6E4B1MZA",
    "aggregate_type": "ConfigurationDocument",
    "aggregate_id": "01HZX9C5S3B1L7K0P2F8V4T6YDA",
    "data": {
      "key": "pricing.surge.max_multiplier",
      "reason": "Replaced by v2",
      "replacement_key": "pricing.surge.max_v2"
    }
  }
  ```
- **Retry**: outbox.
- **DLQ**: `configuration.key.deprecated.dlq`.

### 3.4 `configuration.snapshot.exported.v1`

- **Producer**: `configuration-service`.
- **Topic**: `configuration.snapshot.exported`.
- **Trigger**: nightly snapshot job success.
- **Schema version**: 1.
- **Partition key**: `tenant_id`.
- **Schema**:
  ```json
  {
    "event_id": "01HZX9C8W6K0G3V2Y5N1Q4R7PB",
    "event_name": "configuration.snapshot.exported.v1",
    "occurred_at": "2026-07-29T03:00:00.000Z",
    "schema_version": 1,
    "producer": "configuration-service",
    "tenant_id": "global",
    "correlation_id": "01HZX9C7T0XK2P9F0V6E4B1MZA",
    "aggregate_type": "ConfigurationSnapshot",
    "aggregate_id": "2026-07-29",
    "data": {
      "s3_path": "s3://trips-enjoy-platform-audit/configuration/snapshots/2026/07/29/global.json",
      "key_count": 4127
    }
  }
  ```
- **Retry**: outbox.
- **DLQ**: `configuration.snapshot.exported.dlq`.

## 4. Consumed Events

### 4.1 `customer.segment.changed.v1`

- **Producer**: `customer-service`.
- **Reason**: a per-user override cache may now be stale.
- **Handler**: invalidate Redis entries under
  `cache:user:<user_id>:*`; the next read computes the override
  freshly.
- **Deduplication**: inbox on `event_id`.
- **Retry**: 3 with backoff.
- **Failure**: DLQ.

### 4.2 `admin.configuration.changed.v1`

- **Producer**: `admin-service`.
- **Reason**: An admin changed a config via the console.
- **Handler**: Audit; reload internal state.
- **Deduplication / Retry / Failure**: inbox keyed by
  `event_id` / 3 with exponential backoff / DLQ.


### 4.3 `admin.configuration.rollback_requested.v1`

- **Producer**: `admin-service`.
- **Reason**: An admin rolled back a config.
- **Handler**: Restore previous version.
- **Deduplication / Retry / Failure**: inbox keyed by
  `event_id` / 3 with exponential backoff / DLQ.


### 4.4 `feature_flag.updated.v1`

- **Producer**: `feature-flag-service`.
- **Reason**: A flag was changed (link to config).
- **Handler**: Audit; emit `configuration.updated.v1`.
- **Deduplication / Retry / Failure**: inbox keyed by
  `event_id` / 3 with exponential backoff / DLQ.



## 4.5 Owned config-key families pushed via `configuration.updated.v1`

In addition to the events this service consumes from upstream
producers (§4.1–4.4 above), this service **publishes** values for
the following config-key families on `configuration.updated.v1`
(topic `configuration.updated`). Consumers reload their in-memory
tables and downstream caches on receipt; this service does not
interpret the values.

| Key prefix | Owning consumer | Shape (illustrative) |
|------------|-----------------|----------------------|
| `trip.reward.*` | `trip-service` | per-trip guaranteed-reward tuning (driver / customer top-up tables; `min_window_minutes`, `currency`, etc.) — example: `{"min_window_minutes": 60, "currency": "EUR"}` |
| `pricing.rating_density.*` | `pricing-service` | rating-density multipliers applied during fare calculation — example: `{"min_rating": 4.7, "multiplier": 1.10}` |
| `pricing.loyalty.frequent_rider.*` | `pricing-service` | frequent-rider loyalty thresholds and bonus parameters — example: `{"trips_threshold": 30, "bonus_amount_minor": 500, "currency": "EUR"}` |
| `pricing.geo_overrides.*` | `pricing-service` | geo-specific fare / surge / fee overrides keyed by `geo_id` — example: `{"geo_id": "amsterdam-center", "surce_cap": 1.8}` |
| `payment.gateway.*` | `payment-service` | gateway registry for the 46 supported payment gateways (see [`payment-service/GATEWAYS.md`](../payment-service/GATEWAYS.md)) — head: `payment.gateway.default` (string gateway_id); per-gateway: `payment.gateway.<id>.{enabled,priority,regions,supported_currencies,supported_methods,signature_scheme,verify_style,health_url,webhook_ttl_seconds}`; per-scope overrides: `payment.gateway.override.{tenant,region,currency,payment_method}.<id>` (each → gateway_id). Example: `{"enabled": true, "priority": 10, "regions": ["mena"], "supported_currencies": ["EGP"], "supported_methods": ["card","wallet"], "signature_scheme": "paymob_hmac", "verify_style": "signed_webhook", "webhook_ttl_seconds": 5}` |
| `deal.*` | `ride-request-service`, `food-order-service`, `dispatch-service`, `courier-dispatch-service`, `configuration-service` (entry-point) | Make-a-Deal negotiation kernel — see [`../../shared/DEAL_FEATURE.md`](../../shared/DEAL_FEATURE.md) §8 for the full key list. Head keys: `deal.enabled.{city_id}.{ride_type}` (boolean, default `false`, also surfaced via `feature-flag-service`); `deal.window.ttl_seconds` (int, default `90`); `deal.bid.ttl_seconds` (int, default `15`); `deal.max_counter_rounds` (int, default `3`); `deal.broadcast.radius_m` (int, default `5000`); `deal.broadcast.max_concurrent_drivers` (int, default `10`); per-scope: `deal.band.{tenant}.{city}.{ride_type}.{min_fare_minor,max_fare_minor,currency}` (object, schema-validated). Example: `{"min_fare_minor": 3000, "max_fare_minor": 5000, "currency": "EUR"}` |

All four families are stored under the standard `(scope_type,
scope_id, key)` model (§1.1 / §1.2); their scope resolution,
versioning, audit, and `configuration.updated.v1` lifecycle are
identical to every other config key. There are no events emitted
specifically for these families — every write produces the
generic `configuration.updated.v1` (§3.1), and the owning
consumer filters by `data.key` prefix.

### 4.5.1 `deal.band.{tenant}.{city}.{ride_type}` schema (Make a Deal — Phase 7.5)

The per-tenant / per-city / per-ride-type fare band is the
authoritative referent for the Make-a-Deal negotiation kernel.
The shape is the same as the `restaurant.max_active_orders`
example in §1.3 — `{type: "object", properties: {…}, required: […]}`:

```json
{
  "key": "deal.band.global.amsterdam.economy",
  "value": {
    "min_fare_minor": 3000,
    "max_fare_minor": 5000,
    "currency":       "EUR"
  },
  "schema": {
    "type": "object",
    "properties": {
      "min_fare_minor": { "type": "integer", "minimum": 0 },
      "max_fare_minor": { "type": "integer", "minimum": 0 },
      "currency":       { "type": "string",  "minLength": 3, "maxLength": 3 }
    },
    "required": ["min_fare_minor", "max_fare_minor", "currency"]
  }
}
```

Cross-constraint validated at write-time: `min_fare_minor <= max_fare_minor`.
If violated, the write returns `422 INVALID_BAND`.

The `deal.enabled.{city_id}.{ride_type}` key is also exposed through
`feature-flag-service` (the configuration-service
publication is the source of truth; feature-flag-service re-publishes
under its own namespace for the existing flag-evaluation endpoints).
See [`../../shared/DEAL_FEATURE.md`](../../shared/DEAL_FEATURE.md) §8
for the full `deal.*` key catalogue, and §9 for the rollout procedure.

## 5. Reliability

- **Timeouts**:
  - HTTP read: 1s.
  - HTTP write: 2s.
  - DB: 30s statement timeout.
  - Kafka publish: 5s.
- **Retries**: bounded (3) with exponential backoff + jitter; respects
  `Retry-After`.
- **Circuit breakers**: not required (no synchronous outbound calls).
- **Bulkheads**: long-poll pool is separate from the request pool.
- **Outbox**: yes, table `configuration.outbox`, polled every 200ms
  by a sidecar.
- **Inbox**: yes, table `configuration.inbox` for the single
  consumed event.
- **DLQ**: every topic above has a paired DLQ with 30-day retention.
- **Reconciliation**: daily job compares the S3 snapshot to the
  current DB state; drift opens a `support.ticket` and emits
  `reconciliation.drift.found.v1`.

## 6. Correlation IDs

Every request carries `X-Correlation-Id`. The service:

- Returns the same value in the response header.
- Embeds it in the event envelope (`correlation_id`).
- Embeds it in the `audit_log` row.
- Logs it on every line of the request scope.

## 7. Distributed Tracing

OpenTelemetry: one root span per HTTP request (`GET /v1/configurations/{key}`,
`PUT /v1/configurations/{key}/versions`, etc.). Child spans for DB,
Redis, Kafka publish. `traceparent` propagated through Kafka
headers. Sample rate 100% for errors, 10% for successes.

### 4.2 `customer.segment.changed.v1`

(Already covered above as the primary consumed event. Two more
concrete consumed events follow.)

### 4.3 `customer.created.v1`

- **Producer**: `customer-service`.
- **Reason**: when a new customer is created, the configuration
  service pre-warms any per-user override caches.
- **Handler**: insert a sentinel into the per-user cache (e.g.
  a `customer_id` key with a 24h TTL).
- **Deduplication**: inbox on `event_id`.
- **Retry**: 3 with backoff.
- **Failure**: DLQ.

### 4.4 `zone.surge.updated.v1`

- **Producer**: `zone-service`.
- **Reason**: when a surge zone is updated, the configuration
  service's per-zone override caches must be invalidated.
- **Handler**: invalidate `cache:zone:<zone_id>:*` in Redis.
- **Deduplication / Retry / Failure**: inbox / 3 / DLQ.

### 4.5 `feature_flag.updated.v1`

- **Producer**: `feature-flag-service`.
- **Reason**: when a flag is updated, the configuration service
  may have a `feature_flag.<key>` override (rare, but supported);
  the cache must be invalidated.
- **Handler**: invalidate the affected key.
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
| [`feature-flag-service`](../feature-flag-service/README.md) | DEGRADABLE | degrade (cache / default / flag) |
| [`identity-service`](../identity-service/README.md) | CRITICAL | 503 `DEPENDENCY_UNAVAILABLE` |
| [`pricing-service`](../pricing-service/README.md) | CRITICAL | 503 `DEPENDENCY_UNAVAILABLE` |
| [`promotion-service`](../promotion-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [`reporting-service`](../reporting-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [`tax-service`](../tax-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [`user-profile-service`](../user-profile-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [`zone-service`](../zone-service/README.md) | DEGRADABLE | degrade (cache / default / flag) |

### Downstream services that depend on this service

| Downstream | Class (from its perspective) |
|---|---|
| [`address-service`](../address-service/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`api-gateway`](../api-gateway/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`branch-service`](../branch-service/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`cart-service`](../cart-service/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`checkout-service`](../checkout-service/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`communication-gateway-service`](../communication-gateway-service/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`courier-dispatch-service`](../courier-dispatch-service/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`courier-earnings-service`](../courier-earnings-service/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`courier-service`](../courier-service/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`courier-tracking-service`](../courier-tracking-service/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`customer-service`](../customer-service/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`delivery-service`](../delivery-service/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`dispatch-service`](../dispatch-service/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`driver-availability-service`](../driver-availability-service/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`driver-earnings-service`](../driver-earnings-service/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`driver-incentive-service`](../driver-incentive-service/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`driver-location-service`](../driver-location-service/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`driver-service`](../driver-service/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`eta-routing-service`](../eta-routing-service/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`feature-flag-service`](../feature-flag-service/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| _…and 32 more_ | |

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

