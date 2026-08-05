# Review and Rating Service — Integration Contract

## 1. Inbound APIs

All endpoints are versioned under `/v1`. Auth: bearer JWT. Errors
use the standard envelope.

### 1.1 `POST /v1/reviews`

- **Purpose**: Submit a review.
- **Auth**: Bearer JWT. Required scope: `review.submit`.
- **Idempotency**: `Idempotency-Key` required.
- **Request**:
  ```json
  {
    "subject_type": "driver",
    "subject_id": "01HZX…",
    "source_type": "trip",
    "source_event_id": "01HZX…",
    "trip_id": "01HZX…",
    "rating": 5,
    "comment": "Great ride!",
    "tags": ["clean_car", "polite"]
  }
  ```
- **Response (201)**:
  ```json
  {
    "id": "01HZX9C5S3B1L7K0P2F8V4T6YDA",
    "subject_type": "driver",
    "subject_id": "01HZX…",
    "rating": 5,
    "created_at": "2026-07-29T10:42:11.183Z",
    "correlation_id": "01HZX9C7T0XK2P9F0V6E4B1MZA"
  }
  ```
- **Errors**:
  - 401 / 403.
  - 409 `REVIEW_ALREADY_SUBMITTED`.
  - 422 `SUBJECT_INVALID` / `SOURCE_INVALID` / `RATING_INVALID`.
  - 429 `RATE_LIMITED`.
  - 422 `IDEMPOTENCY_KEY_REUSED`.

### 1.2 `GET /v1/reviews/{id}`

- **Purpose**: Read a review.
- **Auth**: Bearer JWT. Required scope: `review.read`.
- **Response (200)**: the review JSON.
- **Errors**: 401 / 403 / 404.

### 1.3 `PATCH /v1/reviews/{id}`

- **Purpose**: Edit a review within the edit window (24h).
- **Auth**: Bearer JWT. Required scope: `review.submit`.
- **Idempotency**: `Idempotency-Key` required.
- **Request**: partial (only `rating` / `comment` / `tags`).
- **Response (200)**: the updated review.
- **Errors**: 401 / 403 / 404 / 409 `EDIT_WINDOW_EXPIRED`.

### 1.4 `DELETE /v1/reviews/{id}`

- **Purpose**: Soft delete (admin / owner).
- **Auth**: Bearer JWT. Required role: `review.admin` or owner.
- **Idempotency**: `Idempotency-Key` required.
- **Request**: `{ "reason": "Spam" }`.
- **Response (204)**.
- **Errors**: 401 / 403 / 404.

### 1.5 `POST /v1/reviews/{id}/reply`

- **Purpose**: Reply to a review.
- **Auth**: Bearer JWT. Required scope: `review.reply`. The
  caller's `subject_id` MUST match the review's `subject_id`.
- **Idempotency**: `Idempotency-Key` required.
- **Request**:
  ```json
  { "text": "Thank you for the feedback!" }
  ```
- **Response (201)**: the reply.
- **Errors**: 401 / 403 / 404 / 422.

### 1.6 `GET /v1/drivers/{id}/reviews`

- **Purpose**: List driver reviews.
- **Auth**: Bearer JWT. Required scope: `review.read`.
- **Query params**: `cursor`, `limit`.
- **Response (200)**: paginated list.

### 1.7 `GET /v1/couriers/{id}/reviews`

Same as 1.6 for couriers.

### 1.8 `GET /v1/restaurants/{id}/reviews`

Same as 1.6 for restaurants.

### 1.9 `GET /v1/drivers/{id}/rating`

- **Purpose**: Read aggregated rating.
- **Auth**: Bearer JWT. Required scope: `review.read`.
- **Response (200)**:
  ```json
  {
    "subject_type": "driver",
    "subject_id": "01HZX…",
    "window_days": 90,
    "avg_rating": 4.78,
    "review_count": 1234,
    "last_computed_at": "2026-07-29T10:00:00Z"
  }
  ```
- **Errors**: 401 / 403 / 404.

### 1.10 `GET /v1/couriers/{id}/rating`

Same as 1.9 for couriers.

### 1.11 `GET /v1/restaurants/{id}/rating`

Same as 1.9 for restaurants.

### 1.12 `GET /v1/zones/{zone_id}/driver-rating?window_minutes=15`

- **Purpose**: Return the zone-level driver `avg_rating` and
  `review_count` for the rolling window. Powers the
  `pricing-service` rating-density hot path.
- **Auth**: Bearer JWT. Required scope: `review.read` (service).
  Pricing hot path uses client-credentials with `pricing.read`.
- **Query params**: `window_minutes` (default 15, min 5, max 60).
- **Response (200)**:
  ```json
  {
    "zone_id": "01HZX…",
    "window_minutes": 15,
    "avg_rating": 4.12,
    "review_count": 87,
    "last_computed_at": "2026-08-04T10:42:11.183Z",
    "cache_hit": true,
    "correlation_id": "01HZX9C7T0XK2P9F0V6E4B1MZA"
  }
  ```
- **Errors**: 401 / 403 / 404.

## 2. Outbound APIs

| Target | Method | URI | Purpose | Timeout | Retry | Circuit |
|--------|--------|-----|---------|---------|-------|---------|
| `trip-service` | GET | `/v1/trips/{id}` | read trip context | 1s | 3 | yes |
| `food-order-service` | GET | `/v1/orders/{id}` | read order context | 1s | 3 | yes |
| `notification-service` | POST | `/v1/notifications/send` | schedule prompt | 1s | 3 | yes |

## 3. Produced Events

### 3.1 `review.submitted.v1`

- **Producer**: `review-rating-service`.
- **Topic**: `review.submitted`.
- **Trigger**: a review is submitted.
- **Schema version**: 1.
- **Partition key**: `subject_id` (within the `subject_type`).
- **Schema**:
  ```json
  {
    "event_id": "01HZX9C8W6K0G3V2Y5N1Q4R7PB",
    "event_name": "review.submitted.v1",
    "occurred_at": "2026-07-29T10:42:11.183Z",
    "schema_version": 1,
    "producer": "review-rating-service",
    "tenant_id": "global",
    "correlation_id": "01HZX9C7T0XK2P9F0V6E4B1MZA",
    "aggregate_type": "Review",
    "aggregate_id": "01HZX9C5S3B1L7K0P2F8V4T6YDA",
    "data": {
      "subject_type": "driver",
      "subject_id": "01HZX…",
      "source_type": "trip",
      "trip_id": "01HZX…",
      "rating": 5,
      "no_rating": false,
      "flagged": false
    }
  }
  ```
- **Retry / DLQ**: outbox / `review.submitted.dlq`.

### 3.2 `review.aggregated.v1`

- **Producer**: `review-rating-service`.
- **Topic**: `review.aggregated`.
- **Trigger**: an aggregation is updated (debounced).
- **Schema version**: 1.
- **Partition key**: `subject_id`.
- **Schema**:
  ```json
  {
    "event_id": "01HZX9C8W6K0G3V2Y5N1Q4R7PB",
    "event_name": "review.aggregated.v1",
    "occurred_at": "2026-07-29T10:42:11.183Z",
    "schema_version": 1,
    "producer": "review-rating-service",
    "tenant_id": "global",
    "correlation_id": "01HZX9C7T0XK2P9F0V6E4B1MZA",
    "aggregate_type": "ReviewAggregation",
    "aggregate_id": "01HZX9C5S3B1L7K0P2F8V4T6YDA",
    "data": {
      "subject_type": "driver",
      "subject_id": "01HZX…",
      "window_days": 90,
      "avg_rating": 4.78,
      "review_count": 1234
    }
  }
  ```
- **Retry / DLQ**: outbox.

### 3.3 `review.submitted.v1`

- **Producer**: this service.
- **Topic**: `review.submitted`.
- **Trigger**: A customer submits a review (ride, food, courier).
- **Schema version**: 1.
- **Partition key**: `review_id`.
- **Consumers**: `driver-service` (rating), `courier-service` (rating), `restaurant-service` (rating), `notification-service`.
- **Schema**:

  ```json
  {
    "event_id": "01HZX9C8W6K0G3V2Y5N1Q4R7PB",
    "event_name": "review.submitted.v1",
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
- **DLQ**: `review.submitted.dlq`.


### 3.4 `review.aggregated.v1`

- **Producer**: this service.
- **Topic**: `review.aggregated`.
- **Trigger**: The aggregated rating is recomputed (every 1h timer per aggregate).
- **Schema version**: 1.
- **Partition key**: `aggregate_id`.
- **Consumers**: `driver-service`, `courier-service`, `restaurant-service`, `analytics-service`.
- **Schema**:

  ```json
  {
    "event_id": "01HZX9C8W6K0G3V2Y5N1Q4R7PB",
    "event_name": "review.aggregated.v1",
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
- **DLQ**: `review.aggregated.dlq`.

### 3.5 `review.zone_aggregated.v1`

- **Producer**: `review-rating-service`.
- **Topic**: `review.zone_aggregated`.
- **Trigger**: a zone's aggregated driver rating was recomputed
  (debounced per zone, every 15-minute window).
- **Schema version**: 1.
- **Partition key**: `zone_id`.
- **Consumers**: `pricing-service` (warms the rating-density
  cache), `analytics-service`, `reporting-service`.
- **Schema**:
  ```json
  {
    "event_id": "01HZX9C8W6K0G3V2Y5N1Q4R7PB",
    "event_name": "review.zone_aggregated.v1",
    "occurred_at": "2026-08-04T10:42:11.183Z",
    "schema_version": 1,
    "producer": "review-rating-service",
    "tenant_id": "global",
    "correlation_id": "01HZX9C7T0XK2P9F0V6E4B1MZA",
    "aggregate_type": "ReviewZoneAggregation",
    "aggregate_id": "01HZX9C5S3B1L7K0P2F8V4T6YDA",
    "data": {
      "zone_id": "01HZX…",
      "city_id": "amsterdam",
      "window_minutes": 15,
      "window_end": "2026-08-04T10:30:00Z",
      "avg_rating": 4.12,
      "review_count": 87,
      "delta_vs_prior": -0.04
    }
  }
  ```
- **Retry**: outbox, 3 attempts.
- **DLQ**: `review.zone_aggregated.dlq`.

## 4. Consumed Events

### 4.1 `trip.completed.v1`

- **Producer**: `trip-service`.
- **Reason**: schedule a prompt.
- **Handler**: insert into `prompts` with
  `scheduled_for = now + PROMPT_DELAY_HOURS`; dedup on
  `(customer_id, source_event_id)`.
- **Deduplication / Retry / Failure**: inbox / 3 / DLQ.

### 4.2 `food.order.delivered.v1`

- **Producer**: `delivery-service`.
- **Reason**: schedule a prompt.
- **Handler**: same as 4.1 with `order_id`.
- **Deduplication / Retry / Failure**: same.

### 4.3 `trip.completed.v1`

- **Producer**: `trip-service`.
- **Reason**: Trip finished; prompt review.
- **Handler**: Schedule prompt.
- **Deduplication / Retry / Failure**: inbox keyed by
  `event_id` / 3 with exponential backoff / DLQ.


### 4.4 `food.order.delivered.v1`

- **Producer**: `delivery-service`.
- **Reason**: Order delivered; prompt review.
- **Handler**: Schedule prompt.
- **Deduplication / Retry / Failure**: inbox keyed by
  `event_id` / 3 with exponential backoff / DLQ.


### 4.5 `configuration.updated.v1`

- **Producer**: `configuration-service`.
- **Reason**: Aggregation window / prompt rules changed.
- **Handler**: Reload config.
- **Deduplication / Retry / Failure**: inbox keyed by
  `event_id` / 3 with exponential backoff / DLQ.



## 5. Reliability

- **Timeouts**: HTTP 1s; DB 30s; Kafka publish 5s.
- **Retries**: bounded 3 with exponential backoff + jitter.
- **Circuit breakers**: every outbound; on `CIRCUIT_OPEN`, fall back
  to cached values; on cache miss, the submit endpoint returns 503
  unless idempotent.
- **Bulkheads**: separate outbound pool per dependency.
- **Outbox**: yes.
- **Inbox**: yes.
- **DLQ**: every topic above has a paired DLQ.
- **Reconciliation**: hourly job verifies `aggregations` against
  `reviews`; drift opens a `support.ticket`.

## 6. Correlation IDs

Every request carries `X-Correlation-Id`; the service returns it in
the response header and embeds it in the event envelope.

## 7. Distributed Tracing

OpenTelemetry: one root span per request; child spans for DB, Redis,
Kafka. `traceparent` propagated through Kafka headers. Sample rate
100% for errors, 10% for successes.

### 3.3 `review.aggregated.v1`

- **Producer**: `review-rating-service`.
- **Topic**: `review.aggregated`.
- **Trigger**: an aggregation is updated (debounced).
- **Schema version**: 1.
- **Partition key**: `subject_id`.
- **Schema**:
  ```json
  {
    "event_id": "01HZX9C8W6K0G3V2Y5N1Q4R7PB",
    "event_name": "review.aggregated.v1",
    "occurred_at": "2026-07-29T10:42:11.183Z",
    "schema_version": 1,
    "producer": "review-rating-service",
    "tenant_id": "global",
    "correlation_id": "01HZX9C7T0XK2P9F0V6E4B1MZA",
    "aggregate_type": "ReviewAggregation",
    "aggregate_id": "01HZX9C5S3B1L7K0P2F8V4T6YDA",
    "data": {
      "subject_type": "driver",
      "subject_id": "01HZX…",
      "window_days": 90,
      "avg_rating": 4.78,
      "review_count": 1234
    }
  }
  ```
- **Retry / DLQ**: outbox.

### 4.3 `trip.completed.v1`

- **Producer**: `trip-service`.
- **Reason**: schedule a review prompt.
- **Handler**: insert into `prompts` with
  `scheduled_for = now + PROMPT_DELAY_HOURS`; dedup on
  `(customer_id, source_event_id)`.
- **Deduplication**: inbox on `event_id`.
- **Retry**: 3 with backoff.
- **Failure**: DLQ.

### 4.4 `food.order.delivered.v1`

- **Producer**: `delivery-service`.
- **Reason**: schedule a review prompt for the food order.
- **Handler**: same as 4.3 with `order_id`.
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
| [`analytics-service`](../analytics-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [`courier-service`](../courier-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [`delivery-service`](../delivery-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [`driver-service`](../driver-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [`food-order-service`](../food-order-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [`notification-service`](../notification-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [`restaurant-service`](../restaurant-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [`support-service`](../support-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [`trip-service`](../trip-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |

### Downstream services that depend on this service

| Downstream | Class (from its perspective) |
|---|---|
| [`courier-service`](../courier-service/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`customer-service`](../customer-service/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`delivery-service`](../delivery-service/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`driver-service`](../driver-service/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`food-order-service`](../food-order-service/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`restaurant-service`](../restaurant-service/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`ride-history-service`](../ride-history-service/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
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

