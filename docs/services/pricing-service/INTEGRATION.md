# Pricing Service — Integration Contract

## 1. Inbound APIs

All endpoints are versioned under `/v1`. Auth: bearer JWT (RS256,
Keycloak JWKS). Errors use the standard envelope.

### 1.1 `POST /v1/quotes`

- **Purpose**: Compute a `PriceQuote` for a ride or food order.
- **Auth**: Bearer JWT. Required scope: `pricing.quote`.
- **Idempotency**: `Idempotency-Key` required.
- **Request**:
  ```json
  {
    "product_type": "ride",
    "customer_id": "01HZX…",
    "city_id": "amsterdam",
    "ride_type": "economy",
    "pickup": { "lat": 52.37, "lon": 4.89 },
    "dropoff": { "lat": 52.39, "lon": 4.90 },
    "distance_km": 3.2,
    "duration_min": 9.5,
    "surge_zone_id": "01HZX…",
    "promotion_code": null,
    "tip_pct": null,
    "currency": "EUR",
    "tenant_id": "global",
    "scheduled_for": null
  }
  ```
- **Response (200)**:
  ```json
  {
    "quote_id": "01HZX9C5S3B1L7K0P2F8V4T6YDA",
    "product_type": "ride",
    "currency": "EUR",
    "expires_at": "2026-07-29T10:47:11.183Z",
    "lines": [
      { "code": "base_fare", "label": "Base fare", "amount_minor": 250 },
      { "code": "distance", "label": "Distance (3.2 km)", "amount_minor": 384 },
      { "code": "time", "label": "Time (9.5 min)", "amount_minor": 285 },
      { "code": "surge", "label": "Surge 1.5x", "amount_minor": 459 },
      { "code": "surge_density_adj", "label": "Surge — low driver rating in zone (-2%)", "amount_minor": 18 },
      { "code": "service_fee", "label": "Service fee", "amount_minor": 50 },
      { "code": "tax_origin", "label": "VAT 9% (Amsterdam)", "amount_minor": 113 },
      { "code": "tax_destination", "label": "VAT 21% (Rotterdam, reverse charge)", "amount_minor": 0 },
      { "code": "loyalty_frequent_rider", "label": "Frequent-rider (-5%, 12 trips in zone, gold tier)", "amount_minor": -82 }
    ],
    "subtotal_minor": 1566,
    "discount_minor": 82,
    "tax_minor": 113,
    "total_minor": 1597,
    "matched_surge_zone_id": "01HZX…",
    "matched_surge_version": 4123,
    "pricing_geo_overrides_matched": [
      {
        "geo_config_id": "01HZX9C5S3B1L7K0P2F8V4T6YDA",
        "rule_kind": "od_corridor",
        "version": 7,
        "scope": "city_to_city",
        "origin_zone_id": "01HZX…AMS",
        "destination_zone_id": "01HZX…RTM"
      }
    ],
    "rating_density": {
      "applied_pct": 2,
      "avg_rating": 4.1,
      "density_pct": 82,
      "cache_hit": true
    },
    "loyalty_discount": {
      "applied_pct": 5,
      "trip_count_30d": 12,
      "tier": "gold",
      "cache_hit": false
    },
    "config_snapshot": {
      "version": 4123,
      "values": {
        "pricing.base_fare": 250,
        "pricing.per_km": 120,
        "pricing.per_min": 30,
        "pricing.surge.max_multiplier": 3.0,
        "pricing.geo_overrides.matched.first.id": "01HZX9C5S3B1L7K0P2F8V4T6YDA",
        "pricing.geo_overrides.matched.first.version": 7,
        "tax.vat.nl.amsterdam.standard": 9,
        "tax.vat.nl.rotterdam.standard": 21,
        "pricing.rating_density.avg_rating": 4.1,
        "pricing.loyalty.frequent_rider.applied_pct": 5
      }
    },
    "promotion": null,
    "created_at": "2026-07-29T10:42:11.183Z"
  }
  ```
- **Errors**:
  - 400 `VALIDATION_FAILED`.
  - 401 / 403.
  - 422 `RIDE_TYPE_UNKNOWN` / `ZONE_UNKNOWN` / `PROMOTION_INVALID`.
  - 503 `CIRCUIT_OPEN`.

### 1.2 `POST /v1/quotes/{quote_id}/re-quote`

- **Purpose**: Re-evaluate a prior quote against current rules.
- **Auth**: Bearer JWT. Required scope: `pricing.quote`.
- **Idempotency**: `Idempotency-Key` required.
- **Response (200)**: a new `PriceQuote` with a new `quote_id` and
  the prior `quote_id` in `data.previous_quote_id`.
- **Errors**: 401 / 403 / 404 `QUOTE_NOT_FOUND` / 409 `QUOTE_EXPIRED`.

### 1.3 `POST /v1/quotes/cancellation-fee`

- **Purpose**: Calculate the cancellation fee for a ride or order
  at a given stage.
- **Auth**: Bearer JWT. Required scope: `pricing.cancellation`.
- **Idempotency**: `Idempotency-Key` required.
- **Request**:
  ```json
  {
    "product_type": "ride",
    "ride_request_id": "01HZX…",
    "stage": "after_match_before_pickup",
    "elapsed_minutes": 1.0,
    "currency": "EUR"
  }
  ```
- **Response (200)**:
  ```json
  {
    "fee_minor": 500,
    "currency": "EUR",
    "policy": {
      "stage": "after_match_before_pickup",
      "amount_minor": 500
    },
    "config_snapshot": {
      "version": 4123,
      "values": {
        "pricing.cancellation.fee_after_minutes": 2,
        "pricing.cancellation.fee_amount": 500
      }
    }
  }
  ```
- **Errors**: 400 / 401 / 403 / 422.

### 1.4 `POST /v1/quotes/waiting-fee`

- **Purpose**: Calculate the waiting fee for an in-trip ride.
- **Auth**: Bearer JWT. Required scope: `pricing.cancellation`.
- **Request**:
  ```json
  {
    "trip_id": "01HZX…",
    "waiting_minutes": 5,
    "currency": "EUR"
  }
  ```
- **Response (200)**: `{ "fee_minor": 200, "currency": "EUR", "policy": {...}, "config_snapshot": {...} }`.
- **Errors**: 400 / 401 / 403.

### 1.5 `GET /v1/quotes/{quote_id}`

- **Purpose**: Read a prior quote (cache).
- **Auth**: Bearer JWT. Required scope: `pricing.quote`.
- **Response (200)**: the `PriceQuote`.
- **Errors**: 404 `QUOTE_NOT_FOUND` / 410 `QUOTE_EXPIRED`.

### 1.6 `POST /v1/quotes/snapshot/{snapshot_id}`

- **Purpose**: Admin: inspect a captured snapshot.
- **Auth**: Bearer JWT. Required role: `pricing.admin`.
- **Response (200)**: the snapshot JSON.
- **Errors**: 401 / 403 / 404.

### 1.7 `GET /v1/quotes/{quote_id}/fairness-band` *(Make a Deal — Phase 7.5)*

- **Purpose**: Return the per-quote fairness band that bounds Make-a-Deal
  negotiation. Used by ``trip-service` (ride-request)`, `food-order-service`, and
  any other deal-opening service to validate a proposed price against
  the geo-fenced fare band. Canonical spec:
  [`docs/shared/DEAL_FEATURE.md`](../../shared/DEAL_FEATURE.md) §5.
- **Auth**: Bearer JWT. Required scope: `pricing.read`.
- **Response (200)**:
  ```json
  {
    "min_fare_minor": 3000,
    "max_fare_minor": 5000,
    "currency":      "AED",
    "source": {
      "min": { "kind": "min_fare_override", "rule_id": "amsterdam-min", "version": 7 },
      "max": { "kind": "max_fare_override", "rule_id": "amsterdam-max", "version": 3 }
    },
    "config_snapshot": { "version": 42, "values": { /* pricing.* + config_snapshot keys */ } },
    "computed_at": "2026-08-05T10:42:11.183Z"
  }
  ```
- **Errors**:
  - 401 `UNAUTHENTICATED`.
  - 403 `FORBIDDEN` — missing `pricing.read` scope.
  - 404 `QUOTE_NOT_FOUND`.
  - 410 `QUOTE_EXPIRED` — caller's deal has outlived the originating quote; the deal MUST be `expired` by the caller.
  - 503 `DEPENDENCY_TIMEOUT` — `configuration-service` or ``geolocation-service` (zones)` lookup timed out.
- **Resolution order** (most-specific wins, from §1.7 of `pricing-service/ERD.md`):
  1. `od_corridor` (pickup → dropoff corridor rule)
  2. `max_fare_override` *(new — added in Phase 7.5)*
  3. `min_fare_override` (existing)
  4. `base_fare_override`
  5. `per_km_override`
  6. `per_min_override`
  - If no `max_fare_override` matches, the ceiling defaults to `pricing.surge.max_multiplier * base_fare_total` from the quote's `config_snapshot`.
  - If no `min_fare_override` matches, the floor defaults to `pricing.min_fare.{city_id}`.
- **New rule_kind** added to `pricing.geo_overrides.rule_kind` enum:
  `('base_fare_override','per_km_override','per_min_override','surge_pressure','loyalty_discount','min_fare_override','max_fare_override','od_corridor')`.
  The `max_fare_override` row joins the existing schema with columns
  `(rule_id, scope_kind, scope_id, ride_type, currency, amount_minor, effective_from, effective_to, version, status)`.
  Admin-managed via `admin-service` (`POST /v1/admin/pricing/geo-overrides`).
- **Idempotency**: GET; safe. The band is **frozen at quote creation time**; subsequent `pricing.geo_overrides` churn does not retro-constrain open deals (they carry their own `config_snapshot`).

## 2. Outbound APIs

| Target | Method | URI | Purpose | Timeout | Retry | Circuit |
|--------|--------|-----|---------|---------|-------|---------|
| `configuration-service` | GET | `/v1/configurations/{key}` | read pricing rule | 1s | 3 | yes |
| ``pricing-service` (tax)` | POST | `/v1/tax/calculate` | read tax for jurisdiction (up to 2 calls per cross-border trip) | 1s | 3 | yes |
| ``pricing-service` (promotion)` | POST | `/v1/promotions/validate` | validate code | 1s | 3 | yes |
| `geolocation-service` | POST | `/v1/eta` | optional ETA fetch | 1s | 3 | yes |
| `admin-service` | GET | `/v1/admin/pricing/geo-config/{id}` | admin debug fetch by id (optional; live path is the async event) | 1s | 3 | yes |
| ``trip-service` / `food-order-service` / `search-service` (review projections)` | GET | `/v1/zones/{zone_id}/driver-rating?window_minutes=15` | B1 rating-density sub-pipeline | 1s | 3 | yes |
| ``pricing-service` (loyalty rules) / `customer-service` (account)` | GET | `/v1/accounts/{customer_id}/frequent-zones?window_days=30` | B2 loyalty sub-pipeline | 1s | 3 | yes |

All calls are wrapped in a circuit breaker. On `CIRCUIT_OPEN`, the
service falls back to the in-memory cache; if the cache is cold,
return 503.

## 3. Produced Events

### 3.1 `pricing.quote.created.v1`

- **Producer**: `pricing-service`.
- **Topic**: `pricing.quote.created`.
- **Trigger**: every successful quote.
- **Schema version**: 1.
- **Partition key**: `customer_id` (or `quote_id` if no customer).
- **Schema**:
  ```json
  {
    "event_id": "01HZX9C8W6K0G3V2Y5N1Q4R7PB",
    "event_name": "pricing.quote.created.v1",
    "occurred_at": "2026-07-29T10:42:11.183Z",
    "schema_version": 1,
    "producer": "pricing-service",
    "tenant_id": "global",
    "correlation_id": "01HZX9C7T0XK2P9F0V6E4B1MZA",
    "aggregate_type": "PriceQuote",
    "aggregate_id": "01HZX9C5S3B1L7K0P2F8V4T6YDA",
    "data": {
      "product_type": "ride",
      "ride_type": "economy",
      "city_id": "amsterdam",
      "currency": "EUR",
      "total_minor": 1704,
      "surge_multiplier": 1.5,
      "matched_surge_zone_id": "01HZX…",
      "config_snapshot": {
        "version": 4123,
        "values": { "pricing.base_fare": 250 }
      }
    }
  }
  ```
- **Retry**: outbox poller, 3 attempts.
- **DLQ**: `pricing.quote.created.dlq`.

### 3.2 `pricing.quote.expired.v1`

- **Producer**: `pricing-service`.
- **Topic**: `pricing.quote.expired`.
- **Trigger**: a quote's TTL elapses without being consumed.
- **Schema version**: 1.
- **Partition key**: `quote_id`.
- **Schema**: minimal — `quote_id`, `product_type`, `created_at`,
  `expired_at`.
- **Retry / DLQ**: outbox.

### 3.5 `pricing.rating_density.applied.v1`

- **Producer**: `pricing-service`.
- **Topic**: `pricing.rating_density.applied`.
- **Trigger**: a quote's surge line was composed with a
  rating-density surcharge (B1).
- **Schema version**: 1.
- **Partition key**: `zone_id` (so a consumer can aggregate by
  zone without re-shuffling).
- **Consumers**: ``reporting-service` (data lake)`, `reporting-service`.
- **Schema**:
  ```json
  {
    "event_id": "01HZX9C8W6K0G3V2Y5N1Q4R7PB",
    "event_name": "pricing.rating_density.applied.v1",
    "occurred_at": "2026-07-29T10:42:11.183Z",
    "schema_version": 1,
    "producer": "pricing-service",
    "tenant_id": "global",
    "correlation_id": "01HZX9C7T0X9F0V6E4B1MZA",
    "aggregate_type": "PriceQuote",
    "aggregate_id": "01HZX9C5S3B1L7K0P2F8V4T6YDA",
    "data": {
      "quote_id": "01HZX…",
      "city_id": "amsterdam",
      "zone_id": "01HZX…",
      "avg_rating": 4.1,
      "density_pct": 82,
      "applied_pct": 2,
      "composed_surge": 1.55,
      "cache_hit": true
    }
  }
  ```
- **Retry**: outbox poller, 3 attempts.
- **DLQ**: `pricing.rating_density.applied.dlq`.

### 3.6 `pricing.loyalty_discount.applied.v1`

- **Producer**: `pricing-service`.
- **Topic**: `pricing.loyalty_discount.applied`.
- **Trigger**: a loyalty frequent-rider discount was applied (B2).
- **Schema version**: 1.
- **Partition key**: `customer_id`.
- **Consumers**: ``reporting-service` (data lake)`, `reporting-service`.
- **Schema**:
  ```json
  {
    "event_id": "01HZX9C8W6K0G3V2Y5N1Q4R7PB",
    "event_name": "pricing.loyalty_discount.applied.v1",
    "occurred_at": "2026-07-29T10:42:11.183Z",
    "schema_version": 1,
    "producer": "pricing-service",
    "tenant_id": "global",
    "correlation_id": "01HZX9C7T0X9F0V6E4B1MZA",
    "aggregate_type": "PriceQuote",
    "aggregate_id": "01HZX9C5S3B1L7K0P2F8V4T6YDA",
    "data": {
      "quote_id": "01HZX…",
      "customer_id": "01HZX…",
      "zone_id": "01HZX…",
      "trip_count_30d": 12,
      "tier": "gold",
      "applied_pct": 5,
      "discount_minor": 82,
      "cache_hit": false
    }
  }
  ```
- **Retry**: outbox poller, 3 attempts.
- **DLQ**: `pricing.loyalty_discount.applied.dlq`.

### 3.7 `pricing.geo_overrides.matched.v1`

- **Producer**: `pricing-service`.
- **Topic**: `pricing.geo_overrides.matched`.
- **Trigger**: a quote matched ≥ 1 geo-config override (B3).
- **Schema version**: 1.
- **Partition key**: `geo_config_id` (the first / most-specific match).
- **Consumers**: ``reporting-service` (data lake)`, `reporting-service`.
- **Schema**:
  ```json
  {
    "event_id": "01HZX9C8W6K0G3V2Y5N1Q4R7PB",
    "event_name": "pricing.geo_overrides.matched.v1",
    "occurred_at": "2026-07-29T10:42:11.183Z",
    "schema_version": 1,
    "producer": "pricing-service",
    "tenant_id": "global",
    "correlation_id": "01HZX9C7T0X9F0V6E4B1MZA",
    "aggregate_type": "PriceQuote",
    "aggregate_id": "01HZX9C5S3B1L7K0P2F8V4T6YDA",
    "data": {
      "quote_id": "01HZX…",
      "matched_count": 1,
      "first_match": {
        "geo_config_id": "01HZX…",
        "rule_kind": "od_corridor",
        "version": 7,
        "scope": "city_to_city",
        "origin_zone_id": "01HZX…AMS",
        "destination_zone_id": "01HZX…RTM"
      },
      "all_matches": ["01HZX…"]
    }
  }
  ```
- **Retry**: outbox poller, 3 attempts.
- **DLQ**: `pricing.geo_overrides.matched.dlq`.

### 3.3 `pricing.quote.created.v1`

- **Producer**: this service.
- **Topic**: `pricing.quote.created`.
- **Trigger**: A price quote is created (in response to a ride request, food order, or scheduled ride).
- **Schema version**: 1.
- **Partition key**: `quote_id`.
- **Consumers**: ``reporting-service` (data lake)`, `audit-service`.
- **Schema**:

  ```json
  {
    "event_id": "01HZX9C8W6K0G3V2Y5N1Q4R7PB",
    "event_name": "pricing.quote.created.v1",
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
- **DLQ**: `pricing.quote.created.dlq`.


### 3.4 `pricing.surge.zone_updated.v1`

- **Producer**: this service.
- **Topic**: `pricing.surge`.
- **Trigger**: Surge pricing for a zone is updated.
- **Schema version**: 1.
- **Partition key**: `zone_id`.
- **Consumers**: ``reporting-service` (data lake)`.
- **Schema**:

  ```json
  {
    "event_id": "01HZX9C8W6K0G3V2Y5N1Q4R7PB",
    "event_name": "pricing.surge.zone_updated.v1",
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
- **DLQ**: `pricing.surge.dlq`.



## 4. Consumed Events

### 3.8 `pricing.fairness_band.computed.v1` *(Make a Deal — Phase 7.5)*

- **Producer**: this service.
- **Topic**: `pricing.fairness_band`.
- **Trigger**: a fairness band is computed for a quote (via `GET /v1/quotes/{id}/fairness-band`). Emitted regardless of whether the deal opens, so the audit chain captures every band resolution.
- **Schema version**: 1.
- **Partition key**: `quote_id`.
- **Consumers**: `audit-service`, ``reporting-service` (data lake)`.
- **Schema**:
  ```json
  {
    "event_id": "01HZX9C8W6K0G3V2Y5N1Q4R7PB",
    "event_name": "pricing.fairness_band.computed.v1",
    "occurred_at": "2026-08-05T10:42:11.183Z",
    "schema_version": 1,
    "producer": "pricing-service",
    "tenant_id": "global",
    "correlation_id": "01HZX9C7T0XK2P9F0V6E4B1MZA",
    "aggregate_type": "Quote",
    "aggregate_id": "01HZX9C8X1N4M5K7B8V3R0Q9D2H",
    "data": {
      "quote_id":        "01HZX9C8X1N4M5K7B8V3R0Q9D2H",
      "min_fare_minor":  3000,
      "max_fare_minor":  5000,
      "currency":        "AED",
      "source": {
        "min": { "kind": "min_fare_override", "rule_id": "amsterdam-min", "version": 7 },
        "max": { "kind": "max_fare_override", "rule_id": "amsterdam-max", "version": 3 }
      },
      "config_snapshot": { "version": 42, "values": { /* … */ } }
    }
  }
  ```
- **Retry**: outbox, 3 attempts.
- **DLQ**: `pricing.fairness_band.dlq`.

### 4.1 `configuration.updated.v1`

- **Producer**: `configuration-service`.
- **Reason**: business rule values changed; in-memory cache stale.
- **Handler**: invalidate cache for the affected key; reload on next
  read.
- **Deduplication**: inbox on `event_id`.
- **Retry**: 3 with backoff.
- **Failure**: DLQ.

### 4.2 `zone.surge.updated.v1`

- **Producer**: ``geolocation-service` (zones)`.
- **Reason**: surge multiplier changed.
- **Handler**: update `pricing.surge_cache`; reload in-memory.
- **Deduplication**: inbox.
- **Retry**: 3.
- **Failure**: DLQ.

### 4.3 `menu.item.price.changed.v1`

- **Producer**: ``restaurant-service` (menu)`.
- **Reason**: a food cart's quote is stale.
- **Handler**: invalidate any cached quote for the affected branch;
  the next call to `POST /v1/quotes` re-computes.
- **Deduplication**: inbox.
- **Retry**: 3.
- **Failure**: DLQ.

### 4.4 `tax.calculated.v1`

- **Producer**: ``pricing-service` (tax)`.
- **Reason**: tax rules refreshed.
- **Handler**: invalidate tax cache; reload on next read.
- **Deduplication**: inbox.
- **Retry**: 3.
- **Failure**: DLQ.

### 4.5 `pricing.geo_config.updated.v1`

- **Producer**: `admin-service`.
- **Reason**: a per-location override, OD-pair corridor, or other
  geo-config record was created, updated, disabled, or rolled back.
- **Handler**: invalidate the in-memory `pricing.rule_bindings` hash;
  reload on next quote (matches the existing surge reload pattern).
  Idempotency on `(geo_config_id, version)`.
- **Deduplication**: inbox on `event_id`.
- **Retry**: 3 with backoff.
- **Failure**: DLQ.

### 4.6 `review.zone_aggregated.v1`

- **Producer**: ``trip-service` / `food-order-service` / `search-service` (review projections)`.
- **Reason**: a zone's aggregated driver rating changed (debounced).
- **Handler**: warm `pricing.rating_density_cache` for
  `(city_id, zone_id, window_end_minute)`; TTL 15 minutes.
- **Deduplication**: inbox on `event_id`.
- **Retry**: 3.
- **Failure**: DLQ.

### 4.7 `loyalty.frequent_zone.aggregated.v1`

- **Producer**: ``pricing-service` (loyalty rules) / `customer-service` (account)`.
- **Reason**: a customer's frequent-zone aggregation changed
  (debounced daily).
- **Handler**: warm `pricing.loyalty_frequent_cache` for
  `(customer_id, zone_id)`; TTL 30 days.
- **Deduplication**: inbox on `event_id`.
- **Retry**: 3.
- **Failure**: DLQ.

## 5. Reliability

- **Timeouts**: HTTP 1s; DB 30s; Kafka publish 5s.
- **Retries**: bounded 3 with exponential backoff + jitter; respects
  `Retry-After`.
- **Circuit breakers**: every outbound; on open, fall back to
  in-memory cache; on cache miss, 503.
- **Bulkheads**: separate outbound pool per dependency.
- **Outbox**: yes, table `pricing.outbox`, polled every 200ms.
- **Inbox**: yes, table `pricing.inbox`.
- **DLQ**: every topic above has a paired DLQ with 30-day retention.
- **Reconciliation**: hourly job verifies `config_snapshot` is
  reproducible from the current rules; drift opens a `support.ticket`.

## 6. Correlation IDs

Every request carries `X-Correlation-Id`; the service returns it in
the response header and embeds it in the event envelope.

## 7. Distributed Tracing

OpenTelemetry: one root span per request; child spans for config,
tax, promotion, surge, distance. `traceparent` propagated through
Kafka headers. Sample rate 100% for errors, 10% for successes.

### 3.3 `pricing.quote.expired.v1`

- **Producer**: `pricing-service`.
- **Topic**: `pricing.quote.expired`.
- **Trigger**: a quote's TTL elapsed without being consumed.
- **Schema version**: 1.
- **Partition key**: `quote_id`.
- **Schema**:
  ```json
  {
    "event_id": "01HZX9C8W6K0G3V2Y5N1Q4R7PB",
    "event_name": "pricing.quote.expired.v1",
    "occurred_at": "2026-07-29T10:47:11.183Z",
    "schema_version": 1,
    "producer": "pricing-service",
    "tenant_id": "global",
    "correlation_id": "01HZX9C7T0XK2P9F0V6E4B1MZA",
    "aggregate_type": "PriceQuote",
    "aggregate_id": "01HZX9C5S3B1L7K0P2F8V4T6YDA",
    "data": {
      "product_type": "ride",
      "quote_id": "01HZX…",
      "total_minor": 1704,
      "currency": "EUR",
      "expired_at": "2026-07-29T10:47:11.183Z"
    }
  }
  ```
- **Retry / DLQ**: outbox.


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
| [``reporting-service` (data lake)`](../`reporting-service` (data lake)/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [``food-order-service` (cart)`](../`food-order-service` (cart)/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [``food-order-service` (checkout)`](../`food-order-service` (checkout)/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [`configuration-service`](../configuration-service/README.md) | DEGRADABLE | degrade (cache / default / flag) |
| [``payment-service` (courier earnings)`](../`payment-service` (courier earnings)/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [``payment-service` (driver earnings)`](../`payment-service` (driver earnings)/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [`food-order-service`](../food-order-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [`geolocation-service`](../geolocation-service/README.md) | DEGRADABLE | degrade (cache / default / flag) |
| [``restaurant-service` (menu)`](../`restaurant-service` (menu)/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [`payment-service`](../payment-service/README.md) | CRITICAL | 503 `DEPENDENCY_UNAVAILABLE` |
| [``pricing-service` (promotion)`](../`pricing-service` (promotion)/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [``trip-service` (ride-request)`](../`trip-service` (ride-request)/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [``pricing-service` (tax)`](../`pricing-service` (tax)/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [``payment-service` (wallet)`](../`payment-service` (wallet)/README.md) | CRITICAL | 503 `DEPENDENCY_UNAVAILABLE` |
| [``geolocation-service` (zones)`](../`geolocation-service` (zones)/README.md) | DEGRADABLE | degrade (cache / default / flag) |

### Downstream services that depend on this service

| Downstream | Class (from its perspective) |
|---|---|
| [``food-order-service` (cart)`](../`food-order-service` (cart)/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [``food-order-service` (checkout)`](../`food-order-service` (checkout)/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`configuration-service`](../configuration-service/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`customer-service`](../customer-service/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [``driver-service` (dispatch)`](../`driver-service` (dispatch)/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [``driver-service` (incentives)`](../`driver-service` (incentives)/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [``geolocation-service` (ETA/routing)`](../`geolocation-service` (ETA/routing)/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`food-order-service`](../food-order-service/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [``pricing-service` (loyalty rules) / `customer-service` (account)`](../`pricing-service` (loyalty rules) / `customer-service` (account)/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [``pricing-service` (promotion)`](../`pricing-service` (promotion)/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [``trip-service` (ride-request)`](../`trip-service` (ride-request)/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [``trip-service` (scheduled)`](../`trip-service` (scheduled)/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [``pricing-service` (tax)`](../`pricing-service` (tax)/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`trip-service`](../trip-service/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [``geolocation-service` (zones)`](../`geolocation-service` (zones)/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |

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

