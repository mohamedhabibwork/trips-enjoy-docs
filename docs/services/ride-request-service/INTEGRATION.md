# ride-request-service — Integration Contract

## 1. Inbound APIs

### 1.1 `POST /v1/rides`

- **Purpose**: Create a ride request.
- **Auth**: Bearer JWT (required roles: `customer`).
- **Idempotency**: `Idempotency-Key` header required.
- **Request**:
  ```json
  {
    "pickup": {
      "lat": 25.2048,
      "lon": 55.2708,
      "address": "Dubai Mall, Downtown Dubai",
      "place_id": "here:123"
    },
    "dropoff": {
      "lat": 25.1419,
      "lon": 55.2282,
      "address": "Burj Al Arab, Umm Suqeim",
      "place_id": "here:456"
    },
    "ride_type": "economy",
    "payment_method_id": "01HZX9C5S3B1L7K0P2F8V4T6YDA",
    "scheduled_for": null,
    "promo_code": null
  }
  ```
- **Response (201)**:
  ```json
  {
    "id": "01HZX9C8W6K0G3V2Y5N1Q4R7PB",
    "state": "requested",
    "price_quote": {
      "quote_id": "01HZX9C8X1N4M5K7B8V3R0Q9D2H",
      "amount_minor": 4250,
      "currency": "AED",
      "expires_at": "2026-07-29T10:45:00.000Z",
      "surge_multiplier": 1.2,
      "distance_meters": 12500,
      "duration_seconds": 1080
    },
    "match_eta_seconds": 90,
    "created_at": "2026-07-29T10:42:11.183Z"
  }
  ```
- **Errors**:
  - 400 `VALIDATION_FAILED` — bad lat/lon, missing field.
  - 401 `UNAUTHENTICATED` — missing/invalid token.
  - 403 `FORBIDDEN` — not the owner (admin override required).
  - 403 `CUSTOMER_SUSPENDED` — `customer.suspended.v1` applied.
  - 422 `PICKUP_UNSERVED` / `DROPOFF_UNSERVED`.
  - 422 `RIDE_TYPE_NOT_ALLOWED` — city doesn't serve this ride type.
  - 422 `IDEMPOTENCY_KEY_REUSED` — same key, different body.
  - 429 `RATE_LIMITED` — too many requests.
  - 503 `DEPENDENCY_TIMEOUT` — `pricing-service` or
    `customer-service` is down.
- **Validation**: lat ∈ [-90, 90], lon ∈ [-180, 180]; `ride_type`
  in allowed set; `scheduled_for` ≥ now + 15min if present.

### 1.2 `GET /v1/rides/{id}`

- **Purpose**: Read a ride request.
- **Auth**: Bearer JWT (owner or admin or support).
- **Idempotency**: GET; safe.
- **Response (200)**:
  ```json
  {
    "id": "01HZX9C8W6K0G3V2Y5N1Q4R7PB",
    "state": "matched",
    "customer_id": "01HZX9C5S3B1L7K0P2F8V4T6YDA",
    "city_id": "01HZX9C8X1N4M5K7B8V3R0Q9D2H",
    "zone_id": "01HZX9C8W6K0G3V2Y5N1Q4R7PB",
    "ride_type": "economy",
    "pickup": { "...": "..." },
    "dropoff": { "...": "..." },
    "price_quote": { "...": "..." },
    "driver_id": "01HZX9C8K4D2H1A8N5J7V3R0Q9",
    "trip_id": "01HZX9C5S3B1L7K0P2F8V4T6YDA",
    "matched_at": "2026-07-29T10:42:45.000Z",
    "created_at": "2026-07-29T10:42:11.183Z"
  }
  ```
- **Errors**:
  - 401 `UNAUTHENTICATED`
  - 403 `FORBIDDEN` — not the owner.
  - 404 `NOT_FOUND`
- **Validation**: standard.

### 1.3 `POST /v1/rides/{id}/cancellation`

- **Purpose**: Customer cancels a request; service applies the fee
  policy and (if applicable) charges the fee.
- **Auth**: Bearer JWT (owner or admin/support with reason).
- **Idempotency**: `Idempotency-Key` header required.
- **Request**:
  ```json
  {
    "reason": "changed_my_mind"
  }
  ```
- **Response (200)**:
  ```json
  {
    "id": "01HZX9C8W6K0G3V2Y5N1Q4R7PB",
    "state": "cancelled",
    "cancellation_fee": {
      "amount_minor": 0,
      "currency": "AED",
      "captured_at": null,
      "payment_intent_id": null
    },
    "cancelled_at": "2026-07-29T10:42:30.000Z"
  }
  ```
- **Errors**:
  - 401, 403, 404 standard.
  - 409 `STATE_INVALID` — already cancelled/expired, or driver at
    pickup (cancel not allowed).
  - 422 `IDEMPOTENCY_KEY_REUSED`.
  - 503 `DEPENDENCY_TIMEOUT` — `payment-service` is down; the
    cancellation is rejected (or queued per config).
- **Validation**: `reason` is free text but length-bounded (1..200).

### 1.4 `POST /v1/rides/{id}/rebook`

- **Purpose**: Rebook a previous request with a fresh quote.
- **Auth**: Bearer JWT (owner).
- **Idempotency**: `Idempotency-Key` header required.
- **Request**: empty body.
- **Response (201)**: same as `POST /v1/rides`.
- **Errors**: 401, 403, 404 standard; 422 if original parameters are
  no longer valid (e.g. zone now unserved).

### 1.5 `GET /v1/rides/{id}/quote`

- **Purpose**: Return a fresh quote without changing state.
- **Auth**: Bearer JWT (owner or system).
- **Response (200)**:
  ```json
  {
    "quote_id": "01HZX9C8X1N4M5K7B8V3R0Q9D2H",
    "amount_minor": 4500,
    "currency": "AED",
    "expires_at": "2026-07-29T10:46:00.000Z"
  }
  ```

### 1.6 `GET /v1/rides`

- **Purpose**: List the caller's recent requests.
- **Auth**: Bearer JWT (owner).
- **Query params**: `cursor`, `limit` (default 20, max 100),
  `state` (optional filter).
- **Response (200)**:
  ```json
  {
    "items": [ { "...": "..." } ],
    "next_cursor": "eyJ…",
    "has_more": false
  }
  ```

### 1.7 `POST /v1/rides/{id}/deal` *(Make a Deal — Phase 7.5)*

- **Purpose**: Open a Make-a-Deal negotiation on an existing ride
  request. The rider proposes a price; the response carries the
  fairness band that bounds the deal. Canonical spec:
  [`docs/shared/DEAL_FEATURE.md`](../../shared/DEAL_FEATURE.md) §5.
- **Auth**: Bearer JWT (owner). Required scope: `ride_request.deal`.
- **Idempotency**: `Idempotency-Key` header required (suggested
  format: `deal:<ride_request_id>:open`).
- **Pre-flight**: the service short-circuits with
  `404 DEAL_DISABLED_IN_CITY` unless
  `deal.enabled.{city_id}.{ride_type}` is `true` (per
  [`configuration-service` §4.5.1](../configuration-service/INTEGRATION.md#451-dealbandtenantcityride_type-schema-make-a-deal--phase-75)).
- **Request**:
  ```json
  {
    "proposed_fare_minor": 3500,
    "currency":           "AED",
    "quote_id":           "01HZX9C8X1N4M5K7B8V3R0Q9D2H"
  }
  ```
- **Response (201)**:
  ```json
  {
    "deal_id":      "01HZX9C5S3B1L7K0P2F8V4T6YDA",
    "state":        "open",
    "ride_request_id": "01HZX9C8W6K0G3V2Y5N1Q4R7PB",
    "fairness_band": {
      "min_fare_minor": 3000,
      "max_fare_minor": 5000,
      "currency":      "AED",
      "source": {
        "min": { "kind": "min_fare_override", "rule_id": "amsterdam-min", "version": 7 },
        "max": { "kind": "max_fare_override", "rule_id": "amsterdam-max", "version": 3 }
      }
    },
    "expires_at":   "2026-08-05T10:43:41.183Z",
    "current_round": 1
  }
  ```
- **Errors**:
  - 400 `VALIDATION_FAILED` — non-integer `amount_minor`, missing `quote_id`.
  - 401 / 403.
  - 404 `DEAL_DISABLED_IN_CITY` — feature flag is OFF.
  - 409 `RIDE_REQUEST_NOT_OPEN` — request already `matched`/`cancelled`/`expired`.
  - 410 `QUOTE_EXPIRED` — the originating quote outlived its TTL; the caller MUST re-quote.
  - 422 `FARE_BELOW_MIN` / `FARE_ABOVE_MAX` — proposed price out of band.
  - 503 `DEPENDENCY_TIMEOUT` — `pricing-service` (fairness-band) unreachable.

### 1.8 `POST /v1/deals/{id}/counter` *(Make a Deal — Phase 7.5)*

- **Purpose**: Rider submits a counter-offer against a specific
  driver bid, or against an existing rider counter that the driver
  has not yet accepted.
- **Auth**: Bearer JWT (deal owner). Required scope: `ride_request.deal`.
- **Idempotency**: `Idempotency-Key` required (format
  `deal:<deal_id>:counter`).
- **Request**:
  ```json
  {
    "bid_id":            "01HZX9C8K4D2H1A8N5J7V3R0Q9",
    "counter_fare_minor": 3700
  }
  ```
- **Response (200)**: updated `Deal` (state `countered`).
- **Errors**: 400 / 401 / 403 / 404 `DEAL_NOT_FOUND` / 409 `COUNTER_LIMIT_EXCEEDED` (max rounds hit) / 422 `FARE_OUT_OF_BAND`.

### 1.9 `POST /v1/deals/{id}/accept` *(Make a Deal — Phase 7.5)*

- **Purpose**: Rider accepts a driver bid (or a driver counter).
- **Auth**: Bearer JWT (deal owner). Required scope: `ride_request.deal`.
- **Idempotency**: `Idempotency-Key` required (`deal:<deal_id>:accept`).
- **Request**: `{ "bid_id": "01HZX9C8K4D2H1A8N5J7V3R0Q9" }`.
- **Response (200)**: `Deal` moved to `matched`; this service then emits `ride.request.created.v1` carrying `accepted_fare_minor` (per the existing `/v1/rides` flow).
- **Errors**: 400 / 401 / 403 / 404 / 409 `DEAL_NOT_NEGOTIATING` / 410 `BID_EXPIRED`.

### 1.10 `POST /v1/deals/{id}/reject` *(Make a Deal — Phase 7.5)*

- **Purpose**: Rider rejects a deal (all bids, or a specific bid).
- **Auth**: Bearer JWT (deal owner). Required scope: `ride_request.deal`.
- **Idempotency**: `Idempotency-Key` required (`deal:<deal_id>:reject`).
- **Request**: `{ "bid_id": "01HZX9C8K4D2H1A8N5J7V3R0Q9" | null }` (null = reject the whole deal).
- **Response (200)**: `Deal` moved to `rejected`; emits `ride.deal.rejected.v1`.
- **Errors**: 400 / 401 / 403 / 404 / 409 `DEAL_NOT_OPEN`.

### 1.11 `GET /v1/deals/{id}` *(Make a Deal — Phase 7.5)*

- **Purpose**: Read the deal state (rider polls for new bids / counters).
- **Auth**: Bearer JWT (deal owner or admin).
- **Response (200)**: the full `Deal` aggregate including `bids[]`, `counters[]`, `current_round`, `expires_at`.
- **Errors**: 401 / 403 / 404.

## 2. Outbound APIs

| Target | Method | URI | Purpose | Timeout | Retry | Circuit |
|--------|--------|-----|---------|---------|-------|---------|
| `customer-service` | GET | /v1/customers/{id} | validate customer | 1s | 2 | yes |
| `pricing-service` | POST | /v1/quotes | price quote | 1s | 2 | yes |
| `pricing-service` | GET | /v1/quotes/{quote_id}/fairness-band | deal fare band *(Make a Deal — Phase 7.5)* | 500ms | 1 | yes |
| `zone-service` | POST | /v1/zones/coverage | pickup/dropoff check | 500ms | 2 | yes |
| `dispatch-service` | POST | /v1/dispatch/requests | trigger matching | 1s | 0 | yes (failure → 202) |
| `driver-availability-service` | GET | /v1/availability/zone/{zone_id}/drivers | pre-check | 200ms | 1 | yes |
| `payment-service` | POST | /v1/payments/charge | cancellation fee | 1s | 2 | yes |

## 3. Produced Events

### 3.1 `ride.request.created.v1`

- **Producer**: `ride-request-service`.
- **Topic**: `ride.request.created`.
- **Trigger**: request persisted in `requested` state.
- **Schema version**: 1.
- **Partition key**: `ride_request_id`.
- **Consumers**: `dispatch-service`, `pricing-service` (telemetry),
  `audit-service`, `notification-service` (welcome push).
- **Schema**:
  ```json
  {
    "event_id": "01HZX9C8W6K0G3V2Y5N1Q4R7PB",
    "event_name": "ride.request.created.v1",
    "occurred_at": "2026-07-29T10:42:11.183Z",
    "schema_version": 1,
    "producer": "ride-request-service",
    "tenant_id": "global",
    "correlation_id": "01HZX9C7T0XK2P9F0V6E4B1MZA",
    "causation_id": null,
    "aggregate_type": "RideRequest",
    "aggregate_id": "01HZX9C8W6K0G3V2Y5N1Q4R7PB",
    "data": {
      "customer_id": "01HZX9C5S3B1L7K0P2F8V4T6YDA",
      "city_id": "01HZX9C8X1N4M5K7B8V3R0Q9D2H",
      "zone_id": "01HZX9C8W6K0G3V2Y5N1Q4R7PB",
      "ride_type": "economy",
      "pickup": { "lat": 25.2048, "lon": 55.2708, "address": "Dubai Mall" },
      "dropoff": { "lat": 25.1419, "lon": 55.2282, "address": "Burj Al Arab" },
      "price_quote": {
        "quote_id": "01HZX9C8X1N4M5K7B8V3R0Q9D2H",
        "amount_minor": 4250,
        "currency": "AED",
        "expires_at": "2026-07-29T10:45:00.000Z"
      },
      "scheduled_for": null
    }
  }
  ```
- **Retry**: outbox pattern, 3 attempts; DLQ
  `ride.request.created.dlq`.
- **Idempotency**: producer-side via outbox + DB transaction.

### 3.2 `ride.request.matched.v1`

- **Producer**: `ride-request-service`.
- **Topic**: `ride.request.matched`.
- **Trigger**: receipt of `dispatch.matched.v1` with a valid quote.
- **Schema version**: 1.
- **Partition key**: `ride_request_id`.
- **Consumers**: `trip-service`, `notification-service`,
  `audit-service`.
- **Schema**:
  ```json
  {
    "event_id": "...",
    "event_name": "ride.request.matched.v1",
    "aggregate_id": "<ride_request_id>",
    "data": {
      "driver_id": "...",
      "trip_id": "...",
      "matched_at": "..."
    }
  }
  ```
- **Retry**: outbox, 3 attempts; DLQ.

### 3.3 `ride.request.cancelled.v1`

- **Producer**: `ride-request-service`.
- **Topic**: `ride.request.cancelled`.
- **Trigger**: state transition to `cancelled`.
- **Schema version**: 1.
- **Partition key**: `ride_request_id`.
- **Consumers**: `notification-service`, `audit-service`,
  `pricing-service` (fee analytics).
- **Schema**:
  ```json
  {
    "event_id": "...",
    "event_name": "ride.request.cancelled.v1",
    "aggregate_id": "<ride_request_id>",
    "data": {
      "actor": "customer",
      "reason": "changed_my_mind",
      "cancellation_fee": {
        "amount_minor": 0,
        "currency": "AED",
        "captured_at": null
      }
    }
  }
  ```
- **Retry**: outbox, 3 attempts; DLQ.

### 3.4 `ride.request.expired.v1`

- **Producer**: `ride-request-service`.
- **Topic**: `ride.request.expired`.
- **Trigger**: state transition to `expired` after
  `dispatch.no_driver.v1` or match-timeout.
- **Schema version**: 1.
- **Partition key**: `ride_request_id`.
- **Consumers**: `notification-service`, `audit-service`,
  `dispatch-service` (next attempt).
- **Schema**:
  ```json
  {
    "event_id": "...",
    "event_name": "ride.request.expired.v1",
    "aggregate_id": "<ride_request_id>",
    "data": {
      "reason": "no_driver",
      "attempts": 5
    }
  }
  ```
- **Retry**: outbox, 3 attempts; DLQ.

### 3.5 `ride.deal.opened.v1` *(Make a Deal — Phase 7.5)*

- **Producer**: this service.
- **Topic**: `ride.deal`.
- **Trigger**: rider called `POST /v1/rides/{id}/deal` (§1.7); the deal was written in state `open` and the fairness-band snapshot was captured.
- **Schema version**: 1.
- **Partition key**: `deal_id` (= `aggregate_id`).
- **Consumers**: `dispatch-service`, `notification-service`, `audit-service`.
- **Schema**: see the canonical block in [`../../shared/DEAL_FEATURE.md`](../../shared/DEAL_FEATURE.md) §4.3. The `data` block includes `proposed_fare_minor`, `fairness_band`, `config_snapshot`, `quote_id`, `expires_at`, `current_round`.
- **Retry**: outbox, 3 attempts; DLQ `ride.deal.dlq`.

### 3.6 `ride.deal.countered.v1` *(Make a Deal — Phase 7.5)*

- **Producer**: this service (rider-initiated counter).
- **Topic**: `ride.deal`.
- **Trigger**: rider called `POST /v1/deals/{id}/counter` (§1.8).
- **Schema version**: 1.
- **Partition key**: `deal_id`.
- **Consumers**: `dispatch-service`, `notification-service`, `audit-service`.
- **Schema**:
  ```json
  {
    "event_id": "01HZX9C8W6K0G3V2Y5N1Q4R7PB",
    "event_name": "ride.deal.countered.v1",
    "occurred_at": "2026-08-05T10:42:11.183Z",
    "schema_version": 1,
    "producer": "ride-request-service",
    "tenant_id": "global",
    "correlation_id": "01HZX9C7T0XK2P9F0V6E4B1MZA",
    "aggregate_type": "Deal",
    "aggregate_id": "01HZX9C5S3B1L7K0P2F8V4T6YDA",
    "data": {
      "deal_id":            "01HZX9C5S3B1L7K0P2F8V4T6YDA",
      "bid_id":             "01HZX9C8K4D2H1A8N5J7V3R0Q9",
      "counter_id":         "01HZX9C8J2K4D5H7B8V3R0Q9D2H",
      "from_actor":         "rider",
      "counter_fare_minor": 3700,
      "currency":           "AED",
      "round_number":       2,
      "expires_at":         "2026-08-05T10:43:11.183Z"
    }
  }
  ```
- **Retry**: outbox, 3 attempts; DLQ `ride.deal.dlq`.

### 3.7 `ride.deal.accepted.v1` *(Make a Deal — Phase 7.5)*

- **Producer**: this service (rider accepted a bid / counter).
- **Topic**: `ride.deal`.
- **Trigger**: rider called `POST /v1/deals/{id}/accept` (§1.9) OR driver-side `dispatch.deal.accepted.v1` was consumed and the rider is the matching party.
- **Schema version**: 1.
- **Partition key**: `deal_id`.
- **Consumers**: `dispatch-service`, `notification-service`, `audit-service`, `pricing-service` (re-quotes for the recorded `accepted_fare_minor`).
- **Schema**:
  ```json
  {
    "event_id": "01HZX9C8W6K0G3V2Y5N1Q4R7PB",
    "event_name": "ride.deal.accepted.v1",
    "occurred_at": "2026-08-05T10:42:11.183Z",
    "schema_version": 1,
    "producer": "ride-request-service",
    "tenant_id": "global",
    "correlation_id": "01HZX9C7T0XK2P9F0V6E4B1MZA",
    "aggregate_type": "Deal",
    "aggregate_id": "01HZX9C5S3B1L7K0P2F8V4T6YDA",
    "data": {
      "deal_id":              "01HZX9C5S3B1L7K0P2F8V4T6YDA",
      "bid_id":               "01HZX9C8K4D2H1A8N5J7V3R0Q9",
      "accepted_fare_minor":  3700,
      "currency":             "AED",
      "driver_id":            "01HZX9C8X1N4M5K7B8V3R0Q9D2H",
      "rider_id":             "01HZX9C8X1N4M5K7B8V3R0Q9D2H",
      "config_snapshot":      { "version": 42, "values": { /* pricing.* + deal.* */ } }
    }
  }
  ```
- **Side effect**: this service then emits the existing `ride.request.created.v1` (§3.1) carrying `accepted_fare_minor` so the dispatch pipeline picks up the request at the agreed price.
- **Retry**: outbox, 3 attempts; DLQ `ride.deal.dlq`.

### 3.8 `ride.deal.rejected.v1` *(Make a Deal — Phase 7.5)*

- **Producer**: this service (rider rejected) OR `dispatch-service` (driver rejected).
- **Topic**: `ride.deal`.
- **Trigger**: rider called `POST /v1/deals/{id}/reject` (§1.10) OR driver rejected a counter.
- **Schema version**: 1.
- **Partition key**: `deal_id`.
- **Consumers**: counterpart + `notification-service` + `audit-service`.
- **Schema**: same envelope as 3.6; `data` includes `from_actor`, `reason` (`"rider_cancel"` / `"driver_reject"` / `"bid_timeout"`).
- **Retry**: outbox, 3 attempts; DLQ `ride.deal.dlq`.

### 3.9 `ride.deal.expired.v1` *(Make a Deal — Phase 7.5)*

- **Producer**: this service (holds the deal-window timer) OR `dispatch-service` (holds the bid-TTL timer).
- **Topic**: `ride.deal`.
- **Trigger**: deal-window TTL (`deal.window.ttl_seconds`) or bid TTL (`deal.bid.ttl_seconds`) elapsed without a match.
- **Schema version**: 1.
- **Partition key**: `deal_id`.
- **Consumers**: counterpart + `notification-service` + `audit-service`.
- **Schema**: same envelope; `data` includes `reason` (`"window_timeout"` / `"bid_timeout"` / `"max_rounds_exceeded"`), `last_state`.
- **Retry**: outbox, 3 attempts; DLQ `ride.deal.dlq`.

## 4. Consumed Events

### 4.1 `dispatch.matched.v1`

- **Producer**: `dispatch-service`.
- **Reason**: advance state from `requested` to `matched`.
- **Handler**: row-locks the request, validates quote TTL, sets
  `driver_id` and `trip_id`, emits `ride.request.matched.v1`.
- **Deduplication**: inbox on `event_id`.
- **Retry**: 3 with exponential backoff (200ms, 800ms, 3.2s + jitter).
- **Failure**: DLQ on persistent failure; reconciliation job in
  `reporting-service` alerts on stuck requests.

### 4.2 `dispatch.no_driver.v1`

- **Producer**: `dispatch-service`.
- **Reason**: abandon.
- **Handler**: mark `expired`, emit `ride.request.expired.v1`, request
  customer notification.
- **Deduplication**: inbox on `event_id`.
- **Retry**: 3; failure → DLQ.

### 4.3 `dispatch.offer.expired.v1`

- **Producer**: `dispatch-service`.
- **Reason**: re-attempt.
- **Handler**: increment `dispatch_attempts`, re-trigger
  `dispatch-service` with same quote (if still valid) or a fresh
  quote.
- **Deduplication**: inbox on `event_id`.
- **Retry**: 3; failure → DLQ.

### 4.4 `scheduled_ride.due.v1`

- **Producer**: `scheduled-ride-service`.
- **Reason**: materialise a scheduled ride.
- **Handler**: validate customer is still active and the zone is
  served, fetch a fresh quote, persist a new `requested` request,
  emit `ride.request.created.v1`.
- **Deduplication**: inbox on `event_id`.
- **Retry**: 3; failure → DLQ.

### 4.5 `customer.suspended.v1`

- **Producer**: `customer-service`.
- **Reason**: safety; cancel open requests.
- **Handler**: find all open requests for that customer, transition
  each to `cancelled` with `cancellation_actor='safety'`, no fee,
  emit `ride.request.cancelled.v1`.
- **Deduplication**: inbox on `event_id`.
- **Retry**: 3; failure → DLQ.

### 4.6 `customer.created.v1`

- **Producer**: `customer-service`.
- **Reason**: warm a small in-memory cache of customer segments.
- **Handler**: upsert cache entry.
- **Deduplication**: inbox on `event_id`.
- **Retry**: 3; failure → DLQ.

### 4.7 `dispatch.deal.bid.submitted.v1` *(Make a Deal — Phase 7.5)*

- **Producer**: `dispatch-service`.
- **Reason**: a driver submitted a bid against an open `Deal` owned by this service.
- **Handler**: append to `deal.bids[]`; if the deal is in state `open`, transition to `negotiating`; on the **first** bid, set `attempt_count = 1` for the broadcast round and notify the rider.
- **Deduplication**: inbox on `event_id`.
- **Retry**: 3; failure → DLQ `dispatch.deal.bid.submitted.dlq`.

### 4.8 `dispatch.deal.bid.expired.v1` *(Make a Deal — Phase 7.5)*

- **Producer**: `dispatch-service`.
- **Reason**: the bid-TTL (`deal.bid.ttl_seconds`) elapsed without the rider accepting/countering.
- **Handler**: mark the bid `expired`; if no live bids remain and no counter is open, transition the deal to `expired` and emit `ride.deal.expired.v1`.
- **Deduplication**: inbox on `event_id`.
- **Retry**: 3; failure → DLQ `dispatch.deal.bid.expired.dlq`.

### 4.9 `dispatch.deal.accepted.v1` *(Make a Deal — Phase 7.5)*

- **Producer**: `dispatch-service`.
- **Reason**: a driver accepted the rider's last counter.
- **Handler**: idempotent — transitions the deal to `matched` and emits `ride.deal.accepted.v1` (3.7) from this side. The downstream `ride.request.created.v1` carries the agreed `accepted_fare_minor`.
- **Deduplication**: inbox on `event_id`.
- **Retry**: 3; failure → DLQ `dispatch.deal.accepted.dlq`.

## 5. Reliability

- **Timeouts**: outbound 1s default; downstream DB 30s statement
  timeout.
- **Retries**: bounded 3, exponential backoff with jitter.
- **Circuit breakers**: every outbound call; default open after 5
  consecutive failures or 50% failure rate over 30s; half-open after
  30s.
- **Bulkheads**: per-downstream connection pool.
- **Outbox**: yes, `ride_request.outbox` table; poller publishes with
  3 attempts before DLQ.
- **Inbox**: yes, `ride_request.inbox` table; on every consumed
  event.
- **DLQ**: per topic (e.g. `ride.request.created.dlq`).
- **Reconciliation**: a daily job in `reporting-service` checks for
  `requested` rows older than `match_timeout_seconds + 5s` without a
  terminal state.

## 6. Correlation IDs

Every API request carries `X-Correlation-Id`. The service:
- Logs the id on every line within the request scope.
- Propagates it to outbound HTTP calls.
- Embeds it in the `correlation_id` field of every emitted event and
  in the Kafka header `correlation_id`.
- Reads it from the inbound event envelope and uses the same id for
  the resulting state changes.

## 7. Distributed Tracing

OpenTelemetry. One root span per inbound request. Each outbound HTTP
call, DB query, and Kafka publish is a child span. `traceparent` is
propagated through HTTP and Kafka headers. Sample rate: 100% for
errors, 10% for successes in production; 100% in staging.


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
| [`audit-service`](../audit-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [`configuration-service`](../configuration-service/README.md) | DEGRADABLE | degrade (cache / default / flag) |
| [`customer-service`](../customer-service/README.md) | CRITICAL | 503 `DEPENDENCY_UNAVAILABLE` |
| [`dispatch-service`](../dispatch-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [`driver-availability-service`](../driver-availability-service/README.md) | CRITICAL | 503 `DEPENDENCY_UNAVAILABLE` |
| [`driver-earnings-service`](../driver-earnings-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [`driver-location-service`](../driver-location-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [`eta-routing-service`](../eta-routing-service/README.md) | DEGRADABLE | degrade (cache / default / flag) |
| [`geolocation-service`](../geolocation-service/README.md) | DEGRADABLE | degrade (cache / default / flag) |
| [`notification-service`](../notification-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [`payment-service`](../payment-service/README.md) | CRITICAL | 503 `DEPENDENCY_UNAVAILABLE` |
| [`pricing-service`](../pricing-service/README.md) | CRITICAL | 503 `DEPENDENCY_UNAVAILABLE` |
| [`ride-payment-integration-service`](../ride-payment-integration-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [`scheduled-ride-service`](../scheduled-ride-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [`trip-service`](../trip-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [`zone-service`](../zone-service/README.md) | DEGRADABLE | degrade (cache / default / flag) |

### Downstream services that depend on this service

| Downstream | Class (from its perspective) |
|---|---|
| [`api-gateway`](../api-gateway/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`customer-service`](../customer-service/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`dispatch-service`](../dispatch-service/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`driver-availability-service`](../driver-availability-service/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`driver-incentive-service`](../driver-incentive-service/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`driver-service`](../driver-service/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`eta-routing-service`](../eta-routing-service/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`fraud-risk-service`](../fraud-risk-service/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`geolocation-service`](../geolocation-service/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`pricing-service`](../pricing-service/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`ride-history-service`](../ride-history-service/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`scheduled-ride-service`](../scheduled-ride-service/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`trip-service`](../trip-service/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
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

