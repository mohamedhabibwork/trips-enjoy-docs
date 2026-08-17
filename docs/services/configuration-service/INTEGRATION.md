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
        "pricing-service", "`trip-service` (ride-request)", "`food-order-service` (checkout)"
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

### 1.5.1 `GET /v1/configurations/{key}/versions/{version}`

- **Purpose**: Read a specific version of a configuration key.
- **Auth**: Bearer JWT. Required role: `configuration.audit`.
- **Path params**: `key` (the configuration key), `version` (the
  numeric version).
- **Response (200)**:
  ```json
  {
    "key": "pricing.base_fare",
    "version": 4123,
    "value": { "amount_minor": 250, "currency": "EUR" },
    "scope_type": "city",
    "scope_id": "amsterdam",
    "actor_id": "01HZX…",
    "reason": "Quarterly review",
    "created_at": "2026-07-15T09:00:00.000Z",
    "correlation_id": "01HZX9C7T0XK2P9F0V6E4B1MZA"
  }
  ```
- **Errors**: 401 / 403 / 404 `VERSION_NOT_FOUND`.

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
outbound call is the broker publish (see 3).

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
  `review.zone_aggregated.v1` event from ``trip-service` / `food-order-service` / `search-service` (review projections)`
  to warm its rating-density cache.
- `pricing.loyalty.frequent_rider.*` — consumed by
  `pricing-service` (frequent-rider loyalty thresholds and
  bonuses). `pricing-service` separately consumes the
  `loyalty.frequent_zone.aggregated.v1` event from
  ``pricing-service` (loyalty rules) / `customer-service` (account)` to warm its loyalty-frequent cache.
- `pricing.geo_overrides.*` — operator-friendly pointer that
  mirrors the *head* `geo_config` value which is actually owned
  by `admin-service` and published via
  `pricing.geo_config.updated.v1` (see
  `admin-service/INTEGRATION.md` 3.6). The pointer is the
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

The 5 upstream events this service subscribes to. Every event is
deduplicated on `event_id` via the `configuration.inbox` table; the
handler is invoked at-least-once but executes at-most-once per
`event_id`.

### 4.1 `customer.segment.changed.v1`

- **Producer**: `customer-service`.
- **Reason**: a per-user override cache may now be stale.
- **Handler**: invalidate Redis entries under
  `cache:user:<user_id>:*`; the next read computes the override
  freshly.
- **Deduplication**: inbox on `event_id`.
- **Retry**: 3 with backoff.
- **Failure**: DLQ.

### 4.2 `customer.created.v1`

- **Producer**: `customer-service`.
- **Reason**: when a new customer is created, the configuration
  service pre-warms any per-user override caches.
- **Handler**: insert a sentinel into the per-user cache (e.g.
  a `customer_id` key with a 24h TTL).
- **Deduplication**: inbox on `event_id`.
- **Retry**: 3 with backoff.
- **Failure**: DLQ.

### 4.3 `zone.surge.updated.v1`

- **Producer**: ``geolocation-service` (zones)`.
- **Reason**: when a surge zone is updated, the configuration
  service's per-zone override caches must be invalidated.
- **Handler**: invalidate `cache:zone:<zone_id>:*` in Redis.
- **Deduplication / Retry / Failure**: inbox / 3 / DLQ.

### 4.4 `admin.configuration.changed.v1`

- **Producer**: `admin-service`.
- **Reason**: An admin changed a config via the console.
- **Handler**: Audit; reload internal state.
- **Deduplication / Retry / Failure**: inbox keyed by
  `event_id` / 3 with exponential backoff / DLQ.

### 4.5 `admin.configuration.rollback_requested.v1`

- **Producer**: `admin-service`.
- **Reason**: An admin rolled back a config.
- **Handler**: Restore previous version.
- **Deduplication / Retry / Failure**: inbox keyed by
  `event_id` / 3 with exponential backoff / DLQ.

### 4.6 `feature_flag.updated.v1`

- **Producer**: ``configuration-service` (flags)`.
- **Reason**: A flag was changed (link to config).
- **Handler**: Audit; emit `configuration.updated.v1`.
- **Deduplication / Retry / Failure**: inbox keyed by
  `event_id` / 3 with exponential backoff / DLQ.

## 4.7 Owned config-key families pushed via `configuration.updated.v1`

In addition to the events this service consumes from upstream
producers (4.1–4.4 above), this service **publishes** values for
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
| `deal.*` | ``trip-service` (ride-request)`, `food-order-service`, ``driver-service` (dispatch)`, ``courier-service` (dispatch)`, `configuration-service` (entry-point) | Make-a-Deal negotiation kernel — see [`../../shared/DEAL_FEATURE.md`](../../shared/DEAL_FEATURE.md) 8 for the full key list. Head keys: `deal.enabled.{city_id}.{ride_type}` (boolean, default `false`, also surfaced via ``configuration-service` (flags)`); `deal.window.ttl_seconds` (int, default `90`); `deal.bid.ttl_seconds` (int, default `15`); `deal.max_counter_rounds` (int, default `3`); `deal.broadcast.radius_m` (int, default `5000`); `deal.broadcast.max_concurrent_drivers` (int, default `10`); per-scope: `deal.band.{tenant}.{city}.{ride_type}.{min_fare_minor,max_fare_minor,currency}` (object, schema-validated). Example: `{"min_fare_minor": 3000, "max_fare_minor": 5000, "currency": "EUR"}` |

All four families are stored under the standard `(scope_type,
scope_id, key)` model (1.1 / 1.2); their scope resolution,
versioning, audit, and `configuration.updated.v1` lifecycle are
identical to every other config key. There are no events emitted
specifically for these families — every write produces the
generic `configuration.updated.v1` (3.1), and the owning
consumer filters by `data.key` prefix.

### 4.7.1 `deal.band.{tenant}.{city}.{ride_type}` schema (Make a Deal — Phase 7.5)

The per-tenant / per-city / per-ride-type fare band is the
authoritative referent for the Make-a-Deal negotiation kernel.
The shape is the same as the `restaurant.max_active_orders`
example in 1.3 — `{type: "object", properties: {…}, required: […]}`:

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
``configuration-service` (flags)` (the configuration-service
publication is the source of truth; `configuration-service` (flags) re-publishes
under its own namespace for the existing flag-evaluation endpoints).
See [`../../shared/DEAL_FEATURE.md`](../../shared/DEAL_FEATURE.md) 8
for the full `deal.*` key catalogue, and 9 for the rollout procedure.

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
| [``configuration-service` (flags)`](../configuration-service/README.md) | DEGRADABLE | degrade (cache / default / flag) |
| [`identity-service`](../identity-service/README.md) | CRITICAL | 503 `DEPENDENCY_UNAVAILABLE` |
| [`pricing-service`](../pricing-service/README.md) | CRITICAL | 503 `DEPENDENCY_UNAVAILABLE` |
| [``pricing-service` (promotion)`](../pricing-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [`reporting-service`](../reporting-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [``pricing-service` (tax)`](../pricing-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [``customer-service` (cross-persona profile)`](../customer-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [``geolocation-service` (zones)`](../geolocation-service/README.md) | DEGRADABLE | degrade (cache / default / flag) |

### Downstream services that depend on this service

| Downstream | Class (from its perspective) |
|---|---|
| [``customer-service` (addresses)`](../customer-service/README.md) | _see [`SERVICE_ISOLATION.md` 2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`api-gateway`](../api-gateway/README.md) | _see [`SERVICE_ISOLATION.md` 2](../../architecture/SERVICE_ISOLATION.md)_ |
| [``restaurant-service` (branch)`](../restaurant-service/README.md) | _see [`SERVICE_ISOLATION.md` 2](../../architecture/SERVICE_ISOLATION.md)_ |
| [``food-order-service` (cart)`](../food-order-service/README.md) | _see [`SERVICE_ISOLATION.md` 2](../../architecture/SERVICE_ISOLATION.md)_ |
| [``food-order-service` (checkout)`](../food-order-service/README.md) | _see [`SERVICE_ISOLATION.md` 2](../../architecture/SERVICE_ISOLATION.md)_ |
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
| [``configuration-service` (flags)`](../configuration-service/README.md) | _see [`SERVICE_ISOLATION.md` 2](../../architecture/SERVICE_ISOLATION.md)_ |
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
[`../../shared/CONVENTIONS.md` 1](../../shared/CONVENTIONS.md)
(RFC 7807 + `downstream` block). The codes this service emits
are in 1 of this document; the canonical catalog is in
[`DOWNSTREAM_ERROR_CATALOG.md`](../../architecture/DOWNSTREAM_ERROR_CATALOG.md).


---

## 10. Per-service key index (canonical catalog)

> **Appended 2026-08-07.** The single source of truth for **which
> service reads / writes which configuration key prefix**. Every row
> below maps a `key_prefix` to one or more owner services and a
> read/write flag. For the **full per-key schema** (type, default,
> validation), follow the link in each row to that service's
> `README.md` 13 "Configuration" table — that table is the
> human-readable form, while this index is the search-friendly form.
>
> **Conventions:**
> - `R` = reader (the service consumes the key at runtime via
>   `GET /v1/configurations/{key}` or via the in-memory cache invalidated
>   by `configuration.updated.v1`).
> - `W` = writer (the service writes / versions the key via
>   `PUT /v1/configurations/{key}/versions` or via the admin-service
>   BFF at `POST /v1/admin/{service}/{action}`).
> - `RW` = both reader and writer (typically operator-driven; the
>   service writes its own config snapshot then reads it back).
> - A writer that is not the key's owner must go through
>   `admin-service` (the writer is a delegate; the owner remains the
>   service in whose namespace the key lives).
>
> **Cross-link:** the platform-margin-doctrine locked keys
> (`pricing.commission.*`, `pricing.discount_bearer`) are **immutable
> until an ADR ratifies a flip** — see
> [`../../shared/TYPE_CATALOG.md` 8.7](../../shared/TYPE_CATALOG.md#87-platform-margin-doctrine--20--1currency--dynamic-multiplier).

### 10.1 `admin-service`

| Key prefix | R/W | Notes |
|---|---|---|
| `admin.super_admin_allowlist.ips` | RW | super-admin IP allowlist; updated by `admin-service` on operator action and consumed by `api-gateway` and `identity-service` |
| `admin.break_glass.cosigner_pool` | RW | eligible break-glass co-signers (UUIDs); refreshed quarterly |
| `admin.audit.retention_days` | R | retention window for the local `audit_log` mirror |
| `admin.action.permissions_cache_ttl_seconds` | R | default 30 |
| `admin.support.categories` | R | support case categories (mirrors `lookup_types`) |
| `admin.support.priorities` | R | support case priorities (mirrors `lookup_types`) |
| Full per-key schema | — | [13 in admin-service README](../admin-service/README.md#13-configuration) |

### 10.2 `api-gateway`

| Key prefix | R/W | Notes |
|---|---|---|
| `api_gateway.rate_limit.{route}.{actor}.rps` | RW | per-route per-actor rate limit |
| `api_gateway.rate_limit.{route}.burst` | RW | token-bucket burst |
| `api_gateway.cors.{origin}.allowed` | RW | CORS allowlist |
| `api_gateway.jwt.jwks_cache_ttl_seconds` | R | default 3600 |
| `api_gateway.request_id.header` | R | default `X-Request-Id` |
| `api_gateway.super_admin_ip_allowlist` | R | consumed by the auth middleware on every request |
| Full per-key schema | — | [13 in api-gateway README](../api-gateway/README.md#13-configuration) |

### 10.3 `audit-service`

| Key prefix | R/W | Notes |
|---|---|---|
| `audit.retention.financial_years` | R | default 7 |
| `audit.retention.default_years` | R | default 1 |
| `audit.export.s3.path_template` | R | default `s3://trips-enjoy-platform-audit/audit/exports/<yyyy>/<mm>/<dd>/` |
| `audit.export.cron` | R | default `0 4 * * *` (04:00 UTC) |
| `audit.hash.algo` | R | default `sha256` |
| Full per-key schema | — | [13 in audit-service README](../audit-service/README.md#13-configuration) |

### 10.4 `configuration-service`

| Key prefix | R/W | Notes |
|---|---|---|
| `*` (all keys) | W | **Configuration-service is the sole writer** for every key namespace. Other services that "write" config (e.g. `admin-service` via the BFF) are delegating writers — the actual storage row lives here. |
| `configuration-service` itself | — | does **not** read from its own store (no chicken-and-egg); configured by env-vars only. See [13 in configuration-service README](../configuration-service/README.md#13-configuration). |

### 10.5 `courier-service`

| Key prefix | R/W | Notes |
|---|---|---|
| `courier.kyc.required_documents` | RW | operator-managed; courier-service writes per-locale overrides |
| `courier.documents.expiry_warning_days` | R | default `[30, 7, 1]` |
| `courier.documents.expiry_grace_days` | R | default 7 |
| `courier.vehicle_types` | R | default `["bicycle","motorcycle","car","scooter","walking"]` |
| `courier.eligibility.min_rating` | R | default 4.0 |
| `courier.inactive_after_days` | R | default 30 |
| `courier.shifts.min_duration_minutes` | R | default 60 |
| `courier.shifts.max_duration_hours` | R | default 12 |
| `courier.erasure.keep_financial_years` | R | default 7 |
| Full per-key schema | — | [13 in courier-service README](../courier-service/README.md#13-configuration) |

### 10.6 `customer-service`

| Key prefix | R/W | Notes |
|---|---|---|
| `customer.kyc.tier_{0..3}_limit_minor` | R | KYC tier limits |
| `customer.segment.frequent_rides` | R | default 20 |
| `customer.segment.vip_ltv_minor` | R | default 1 000 000 |
| `customer.segment.churned_idle_days` | R | default 90 |
| `customer.ltv.refresh_window_days` | R | default 365 |
| `customer.erasure.keep_financial_years` | R | default 7 |
| Full per-key schema | — | [13 in customer-service README](../customer-service/README.md#13-configuration) |

### 10.7 `driver-service`

| Key prefix | R/W | Notes |
|---|---|---|
| `driver.kyc.required_documents` | RW | operator-managed; per-locale overrides |
| `driver.documents.expiry_warning_days` | R | default `[30, 7, 1]` |
| `driver.documents.expiry_grace_days` | R | default 7 |
| `driver.eligibility.min_rating` | R | default 4.0 |
| `driver.incentives.quest.*` | R | quest / bonus programs |
| `driver.incentives.guarantee.*` | R | hourly / daily guarantee floors |
| `driver.geo.fence_meters` | R | default 50 |
| `driver.erasure.keep_financial_years` | R | default 7 |
| Full per-key schema | — | [13 in driver-service README](../driver-service/README.md#13-configuration) |

### 10.8 `file-service`

| Key prefix | R/W | Notes |
|---|---|---|
| `file.upload.max_size_bytes` | R | default 25 MiB |
| `file.upload.allowed_mime_types` | R | default `[image/jpeg, image/png, image/webp, application/pdf]` |
| `file.upload.session_ttl_seconds` | R | default 900 |
| `file.virus_scan.provider` | R | `clamav` / `external` |
| `file.s3.bucket` | R | bucket name |
| Full per-key schema | — | [13 in file-service README](../file-service/README.md#13-configuration) |

### 10.9 `food-order-service`

| Key prefix | R/W | Notes |
|---|---|---|
| `food_order.cart.ttl_seconds` | R | default 1800 |
| `food_order.checkout.idempotency_ttl_seconds` | R | default 86400 |
| `food_order.queue.kitchen_ticket_state_redis_ttl` | R | default 300 |
| `food_order.refund.auto_approval_threshold_minor` | R | default 5000 |
| `food_order.erasure.keep_financial_years` | R | default 7 |
| Full per-key schema | — | [13 in food-order-service README](../food-order-service/README.md#13-configuration) |

### 10.10 `fraud-risk-service`

| Key prefix | R/W | Notes |
|---|---|---|
| `fraud_risk.scoring.{login,payment,dispatch}.model_id` | RW | active model UUID; operator-managed |
| `fraud_risk.threshold.{login,payment}.{allow,challenge,block}` | RW | per-stage score thresholds |
| `fraud_risk.velocity.payment.per_card_per_hour` | RW | default 10 |
| `fraud_risk.velocity.login.per_ip_per_hour` | RW | default 20 |
| `fraud_risk.threat_intel.feed_url` | R | external blocklist feed |
| Full per-key schema | — | [13 in fraud-risk-service README](../fraud-risk-service/README.md#13-configuration) |

### 10.11 `geolocation-service`

| Key prefix | R/W | Notes |
|---|---|---|
| `geo.zone.retention_days` | R | default 30 (hot), 365 (cold) |
| `geo.surge.window_minutes` | R | default 5 |
| `geo.surge.step` | R | default 0.25 |
| `geo.surge.bucket_count` | R | default 10 |
| `geo.geocode.cache_ttl_seconds` | R | default 30 d |
| `geo.eta.provider` | R | `mapbox` / `google` / `here` |
| Full per-key schema | — | [13 in geolocation-service README](../geolocation-service/README.md#13-configuration) |

### 10.12 `identity-service`

| Key prefix | R/W | Notes |
|---|---|---|
| `identity.session.refresh_token_ttl_seconds` | R | default 1800 |
| `identity.session.access_token_ttl_seconds` | R | default 600 |
| `identity.mfa.required_for_roles` | R | default `["platform.super_admin"]` |
| `identity.erasure.preserve_financial_years` | R | default 7 |
| `identity.super_admin_ip_allowlist` | R | consumed during `POST /admin/v1/identities/{id}/roles/platform.super_admin` |
| `identity.break_glass.cosigner_pool` | R | eligible co-signers |
| Full per-key schema | — | [13 in identity-service README](../identity-service/README.md#13-configuration) and the new 6 below. |

### 10.13 `ledger-service`

| Key prefix | R/W | Notes |
|---|---|---|
| `ledger.retention.years` | R | default 7 |
| `ledger.chart_of_accounts.path` | R | default `ledger/chart_of_accounts.json` |
| `ledger.reconciliation.cron` | R | default `0 4 * * *` (04:00 UTC) |
| `ledger.break_glass.adjustment_required_cosigner` | R | default `true` |
| Full per-key schema | — | [13 in ledger-service README](../ledger-service/README.md#13-configuration) |

### 10.14 `notification-service`

| Key prefix | R/W | Notes |
|---|---|---|
| `notification.template.{category}.{channel}.active_version` | RW | active immutable template version |
| `notification.preferences.defaults.{channel}` | R | default opt-in/out |
| `notification.quiet_hours.{user_segment}` | R | per-segment quiet hours |
| `notification.delivery.retry.max_attempts` | R | default 5 |
| `notification.delivery.retry.backoff_seconds` | R | default `[60, 300, 1800, 7200, 21600]` |
| `notification.dnd.provider_rate_limits.{channel}` | R | per-provider throttling |
| Full per-key schema | — | [13 in notification-service README](../notification-service/README.md#13-configuration) |

### 10.15 `payment-service`

| Key prefix | R/W | Notes |
|---|---|---|
| `payment.idempotency.ttl_seconds` | R | default 86400 |
| `payment.cancellation.fee_after_minutes` | R | per-city |
| `payment.cancellation.fee_amount` | R | per-city |
| `payment.saga.timeout_seconds` | R | default 30 |
| `payment.provider.{gateway}.enabled` | RW | gateway enable flag (46-gateway registry; admin-service writes) |
| `payment.provider.{gateway}.priority` | RW | routing priority |
| `payment.erasure.keep_financial_years` | R | default 7 |
| Full per-key schema | — | [13 in payment-service README](../payment-service/README.md#13-configuration) |

### 10.16 `pricing-service`

| Key prefix | R/W | Notes |
|---|---|---|
| `pricing.base_fare` | RW | per-city / per-ride-type base |
| `pricing.per_km` | RW | per-city / per-ride-type per-km |
| `pricing.per_min` | RW | per-city / per-ride-type per-minute |
| `pricing.min_fare.{city_id}` | RW | per-city floor |
| `pricing.surge.max_multiplier` | RW | per-city cap |
| `pricing.surge.step` | R | default 0.25 |
| `pricing.surge.bucket_index` | RW | per-bucket multiplier |
| `pricing.loyalty.frequent_rider.*` | RW | enabled / min_trips_30d / max_discount_pct / tiers |
| `pricing.rating_density.*` | RW | enabled / window / threshold |
| `pricing.geo_overrides.*` | R | cached projection |
| `pricing.tax.{jurisdiction}.{code}` | RW | tax rate catalog |
| `pricing.commission.pct` | RW | **locked** 0.20 (immutable; ADR-gated) |
| `pricing.commission.flat_minor.{currency}` | RW | **locked** per-currency default `{currency: 100}` minor |
| `pricing.commission.base` | RW | **locked** `gross` (immutable; ADR-gated) |
| `pricing.discount_bearer` | RW | **locked** `platform` (immutable; ADR-gated) |
| `deal.*` | RW | Make-a-Deal negotiation kernel (see [`../../shared/DEAL_FEATURE.md`](../../shared/DEAL_FEATURE.md) 8) |
| Full per-key schema | — | [13 in pricing-service README](../pricing-service/README.md#13-configuration) |

### 10.17 `reporting-service`

| Key prefix | R/W | Notes |
|---|---|---|
| `reporting.export.cron.{export_id}` | R | per-export schedule |
| `reporting.warehouse.snowflake.enabled` | R | sink toggles |
| `reporting.warehouse.bigquery.enabled` | R | |
| `reporting.warehouse.redshift.enabled` | R | |
| `reporting.dashboards.refresh_seconds` | R | default 30 |
| Full per-key schema | — | [13 in reporting-service README](../reporting-service/README.md#13-configuration) |

### 10.18 `restaurant-service`

| Key prefix | R/W | Notes |
|---|---|---|
| `restaurant.cuisine.list` | R | cuisine catalog |
| `restaurant.dietary.tags` | R | dietary tag catalog |
| `restaurant.spice.levels` | R | spice level catalog |
| `restaurant.menu.photo.max_size_bytes` | R | default 8 MiB |
| `restaurant.staff.max_per_branch` | R | default 50 |
| `restaurant.erasure.keep_financial_years` | R | default 7 |
| Full per-key schema | — | [13 in restaurant-service README](../restaurant-service/README.md#13-configuration) |

### 10.19 `search-service`

| Key prefix | R/W | Notes |
|---|---|---|
| `search.index.{vertical}.alias` | RW | index alias per vertical |
| `search.relevance.{vertical}.{field}.boost` | RW | per-field boost factor |
| `search.locale.{vertical}.supported` | RW | supported locale list |
| `search.cache.query.ttl_seconds` | R | default 60 |
| `search.query_log.retention_days` | R | default 30 |
| Full per-key schema | — | [13 in search-service README](../search-service/README.md#13-configuration) |

### 10.20 `trip-service`

| Key prefix | R/W | Notes |
|---|---|---|
| `trip.arrival.geofence_meters` | R | default 50 |
| `trip.location.stream_sample_hz` | R | default 0.2 |
| `trip.cancellation.driver_penalty_minor.{currency}` | RW | per-city |
| `trip.cancellation.driver_penalty_window_seconds` | R | default 120 |
| `trip.location.retention_seconds` | R | default 7200 |
| `trip.mid_trip.max_stops` | R | default 1 |
| `trip.mid_trip.dropoff_change_radius_meters` | R | default 5000 |
| `trip.fare.recompute_allow_pct` | R | default 5 |
| `trip.reward.driver.*` | RW | Phase 7 guaranteed-reward program |
| `trip.reward.user.*` | RW | user-side per-trip credit |
| `trip.reward.eval.timeout_ms` | R | default 500 |
| Full per-key schema | — | [13 in trip-service README](../trip-service/README.md#13-configuration) |

### 10.21 Cross-service keys

A handful of keys are **not** owned by any one service — they are
platform-wide and consumed by every reader:

| Key prefix | Owner | Consumers | Notes |
|---|---|---|---|
| `feature_flag.*` | `configuration-service` (legacy: ``configuration-service` (flags)` absorbed) | every service with a feature-flag evaluation path | the legacy `feature_flag.updated.v1` event family remains; canonical writes go through `configuration-service` |
| `platform.commission.*` (deprecated namespace) | — | — | use `pricing.commission.*` instead; the deprecated namespace is aliased for one quarter (per [`../../shared/TIME_BOUNDED_ALIASES.md`](../../shared/TIME_BOUNDED_ALIASES.md)) |
| `shared.lookup.*` | platform | every service via `LookupCacheInvalidator` | see [`../../shared/LOOKUPS.md`](../../shared/LOOKUPS.md) |
| `super_admin_allowlist.ips` (legacy) | — | — | use `admin.super_admin_allowlist.ips`; legacy alias expires 2026-11-01 |

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

