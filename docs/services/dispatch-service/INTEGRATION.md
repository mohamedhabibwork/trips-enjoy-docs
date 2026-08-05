# dispatch-service — Integration Contract

## 1. Inbound APIs

### 1.1 `POST /v1/dispatch/requests`

- **Purpose**: Create a match attempt (called by
  `ride-request-service`).
- **Auth**: Service-to-service JWT (`dispatch-service` client).
- **Idempotency**: `Idempotency-Key` required (the
  `ride_request_id` itself; one attempt per request).
- **Request**:
  ```json
  {
    "ride_request_id": "01HZX9C8W6K0G3V2Y5N1Q4R7PB",
    "city_id": "01HZX9C8X1N4M5K7B8V3R0Q9D2H",
    "zone_id": "01HZX9C8W6K0G3V2Y5N1Q4R7PB",
    "ride_type": "economy",
    "pickup": { "lat": 25.2048, "lon": 55.2708, "address": "..." },
    "dropoff": { "lat": 25.1419, "lon": 55.2282, "address": "..." },
    "correlation_id": "01HZX9C7T0XK2P9F0V6E4B1MZA"
  }
  ```
- **Response (201)**:
  ```json
  {
    "id": "01HZX9C8K4D2H1A8N5J7V3R0Q9",
    "state": "searching",
    "created_at": "2026-07-29T10:42:11.183Z"
  }
  ```
- **Errors**:
  - 400 `VALIDATION_FAILED`
  - 401 `UNAUTHENTICATED`
  - 409 `RIDE_REQUEST_ALREADY_HAS_ATTEMPT` — duplicate.
  - 503 `DEPENDENCY_TIMEOUT` — `driver-availability-service` is
    down.

### 1.2 `GET /v1/dispatch/attempts/{id}`

- **Purpose**: Read a match attempt.
- **Auth**: Bearer JWT (admin / support).
- **Response (200)**:
  ```json
  {
    "id": "...",
    "ride_request_id": "...",
    "state": "offering",
    "attempt_count": 2,
    "current_radius_m": 2250,
    "candidates_considered": [ ... ],
    "offers_sent": [
      { "offer_id": "...", "driver_id": "...", "sent_at": "...", "expires_at": "...", "response": "expired" }
    ],
    "matched_driver_id": null,
    "matched_at": null
  }
  ```
- **Errors**: 401, 403, 404.

### 1.3 `POST /v1/dispatch/attempts/{id}/cancel`

- **Purpose**: Admin cancels an attempt.
- **Auth**: Bearer JWT (admin) with `X-Audit-Reason`.
- **Response (200)**: attempt with `state=cancelled`.

### 1.4 `GET /v1/dispatch/drivers/{driver_id}/offers`

- **Purpose**: List the driver's pending offers.
- **Auth**: Bearer JWT (driver).
- **Response (200)**:
  ```json
  {
    "items": [
      { "offer_id": "...", "ride_request_id": "...", "pickup": {...}, "expires_at": "..." }
    ]
  }
  ```

### 1.5 `GET /v1/dispatch/drivers/{driver_id}/open-deals` *(Make a Deal — Phase 7.5)*

- **Purpose**: List the open deals the driver is eligible to bid on
  (the pull-discovery channel for the Make-a-Deal kernel; mirrors
  the existing `GET /v1/dispatch/drivers/{driver_id}/offers` for the
  non-deal flow).
- **Auth**: Bearer JWT (driver).
- **Query params**: `cursor`, `limit` (default 20, max 100),
  `zone_id` (optional filter).
- **Response (200)**:
  ```json
  {
    "items": [
      {
        "deal_id":        "01HZX9C5S3B1L7K0P2F8V4T6YDA",
        "ride_request_id": "01HZX9C8W6K0G3V2Y5N1Q4R7PB",
        "pickup":         { "lat": 25.2048, "lon": 55.2708, "address": "Dubai Mall" },
        "dropoff":        { "lat": 25.1419, "lon": 55.2282, "address": "Burj Al Arab" },
        "proposed_fare_minor": 3500,
        "currency":       "AED",
        "fairness_band": {
          "min_fare_minor": 3000,
          "max_fare_minor": 5000
        },
        "expires_at":     "2026-08-05T10:43:41.183Z"
      }
    ],
    "next_cursor": "eyJ…",
    "has_more":   false
  }
  ```
- **Errors**: 401 / 403 / 404.

### 1.6 `POST /v1/dispatch/deals/{deal_id}/bids` *(Make a Deal — Phase 7.5)*

- **Purpose**: Driver submits a bid against an open deal. The
  driver's bid is validated against the deal's fairness band
  (acquired from `ride-request-service` via the `ride.deal.opened.v1`
  event payload) and recorded in `DealBid`.
- **Auth**: Bearer JWT (driver). Required scope: `dispatch.deal`.
- **Idempotency**: `Idempotency-Key` required (`deal:<deal_id>:bid`).
- **Request**:
  ```json
  {
    "amount_minor": 3800,
    "currency":     "AED"
  }
  ```
- **Response (201)**:
  ```json
  {
    "bid_id":        "01HZX9C8K4D2H1A8N5J7V3R0Q9",
    "deal_id":       "01HZX9C5S3B1L7K0P2F8V4T6YDA",
    "state":         "pending",
    "amount_minor":  3800,
    "currency":      "AED",
    "expires_at":    "2026-08-05T10:42:26.183Z"
  }
  ```
- **Errors**:
  - 400 `VALIDATION_FAILED`.
  - 401 / 403.
  - 404 `DEAL_NOT_FOUND` / `DEAL_NOT_OPEN`.
  - 409 `BIDDER_NOT_IN_BROADCAST` — driver was not invited to this broadcast round.
  - 422 `FARE_OUT_OF_BAND` — bid is outside the deal's fairness band.
  - 503 `DEPENDENCY_TIMEOUT`.

### 1.7 `POST /v1/dispatch/deals/{deal_id}/accept` *(Make a Deal — Phase 7.5)*

- **Purpose**: Driver accepts either the rider's original proposed
  fare OR the rider's most recent counter.
- **Auth**: Bearer JWT (driver). Required scope: `dispatch.deal`.
- **Idempotency**: `Idempotency-Key` required (`deal:<deal_id>:accept`).
- **Request**: `{ "counter_id": "01HZX9C8J2K4D5H7B8V3R0Q9D2H" | null }` (null = accept the rider's original `proposed_fare_minor`).
- **Response (200)**: updated `DealBid` (state `accepted`) and the deal moves to `matched`.
- **Errors**: 400 / 401 / 403 / 404 / 409 `DEAL_NOT_NEGOTIATING` / 410 `BID_EXPIRED`.

### 1.8 `POST /v1/dispatch/deals/{deal_id}/bid/{bid_id}/reject` *(Make a Deal — Phase 7.5)*

- **Purpose**: Driver rejects a rider counter (or the rider's
  original proposed fare).
- **Auth**: Bearer JWT (driver). Required scope: `dispatch.deal`.
- **Idempotency**: `Idempotency-Key` required.
- **Response (200)**: `DealBid` state `rejected`; emits `dispatch.deal.rejected.v1`.
- **Errors**: 400 / 401 / 403 / 404 / 409 `DEAL_NOT_OPEN`.

## 2. Outbound APIs

| Target | Method | URI | Purpose | Timeout | Retry | Circuit |
|--------|--------|-----|---------|---------|-------|---------|
| `driver-availability-service` | POST | /v1/availability/zone/{zone_id}/online-drivers | list online drivers | 500ms | 1 | yes |
| `driver-location-service` | GET | /v1/location/zone/{zone_id}/current | last known positions | 300ms | 1 | yes |
| `eta-routing-service` | POST | /v1/routing/eta | ETA to pickup | 500ms | 2 | yes |
| `driver-service` | GET | /v1/drivers/{id}/rating | fairness tie-breaker | 300ms | 1 | yes |

## 3. Produced Events

### 3.1 `dispatch.matched.v1`

- **Topic**: `dispatch.matched`.
- **Partition key**: `ride_request_id`.
- **Consumers**: `ride-request-service`, `trip-service`,
  `notification-service`.
- **Schema**:
  ```json
  {
    "event_id": "...",
    "event_name": "dispatch.matched.v1",
    "aggregate_id": "<ride_request_id>",
    "data": {
      "attempt_id": "...",
      "driver_id": "...",
      "offer_id": "...",
      "matched_at": "..."
    }
  }
  ```
- **Retry**: outbox, 3; DLQ.

### 3.2 `dispatch.no_driver.v1`

- **Topic**: `dispatch.no_driver`.
- **Partition key**: `ride_request_id`.
- **Consumers**: `ride-request-service`, `notification-service`.
- **Schema**:
  ```json
  {
    "event_id": "...",
    "event_name": "dispatch.no_driver.v1",
    "aggregate_id": "<ride_request_id>",
    "data": {
      "attempt_id": "...",
      "attempt_count": 5,
      "no_driver_at": "..."
    }
  }
  ```

### 3.3 `dispatch.offer.expired.v1`

- **Topic**: `dispatch.offer.expired`.
- **Partition key**: `ride_request_id`.
- **Consumers**: `ride-request-service` (re-attempt), `dispatch-service`
  (next candidate).
- **Schema**:
  ```json
  {
    "event_id": "...",
    "event_name": "dispatch.offer.expired.v1",
    "aggregate_id": "<ride_request_id>",
    "data": {
      "attempt_id": "...",
      "offer_id": "...",
      "driver_id": "...",
      "expired_at": "..."
    }
  }
  ```

### 3.4 `dispatch.deal.bid.submitted.v1` *(Make a Deal — Phase 7.5)*

- **Producer**: this service.
- **Topic**: `dispatch.deal`.
- **Trigger**: driver called `POST /v1/dispatch/deals/{deal_id}/bids` (§1.6); the bid was validated against the fairness band and persisted in `DealBid`.
- **Schema version**: 1.
- **Partition key**: `deal_id` (= `aggregate_id`).
- **Consumers**: `ride-request-service`, `notification-service`, `audit-service`.
- **Schema**:
  ```json
  {
    "event_id": "01HZX9C8W6K0G3V2Y5N1Q4R7PB",
    "event_name": "dispatch.deal.bid.submitted.v1",
    "occurred_at": "2026-08-05T10:42:11.183Z",
    "schema_version": 1,
    "producer": "dispatch-service",
    "tenant_id": "global",
    "correlation_id": "01HZX9C7T0XK2P9F0V6E4B1MZA",
    "aggregate_type": "Deal",
    "aggregate_id": "01HZX9C5S3B1L7K0P2F8V4T6YDA",
    "data": {
      "deal_id":      "01HZX9C5S3B1L7K0P2F8V4T6YDA",
      "bid_id":       "01HZX9C8K4D2H1A8N5J7V3R0Q9",
      "driver_id":    "01HZX9C8X1N4M5K7B8V3R0Q9D2H",
      "amount_minor": 3800,
      "currency":     "AED",
      "attempt_count": 1,
      "expires_at":   "2026-08-05T10:42:26.183Z"
    }
  }
  ```
- **Retry**: outbox, 3 attempts; DLQ `dispatch.deal.dlq`.

### 3.5 `dispatch.deal.bid.expired.v1` *(Make a Deal — Phase 7.5)*

- **Producer**: this service (holds the bid-TTL timer).
- **Topic**: `dispatch.deal`.
- **Trigger**: `deal.bid.ttl_seconds` elapsed without the rider acting on the bid.
- **Schema version**: 1.
- **Partition key**: `deal_id`.
- **Consumers**: `ride-request-service`, `notification-service`, `audit-service`.
- **Schema**: same envelope; `data` includes `bid_id`, `reason: "bid_timeout"`.
- **Retry**: outbox, 3 attempts; DLQ `dispatch.deal.dlq`.

### 3.6 `dispatch.deal.accepted.v1` *(Make a Deal — Phase 7.5)*

- **Producer**: this service (driver accepted the rider's counter).
- **Topic**: `dispatch.deal`.
- **Trigger**: driver called `POST /v1/dispatch/deals/{deal_id}/accept` (§1.7).
- **Schema version**: 1.
- **Partition key**: `deal_id`.
- **Consumers**: `ride-request-service`, `notification-service`, `audit-service`, `pricing-service`.
- **Schema**: same envelope as `ride.deal.accepted.v1`; actor = `driver`.
- **Retry**: outbox, 3 attempts; DLQ `dispatch.deal.dlq`.

### 3.7 `dispatch.deal.rejected.v1` *(Make a Deal — Phase 7.5)*

- **Producer**: this service (driver rejected a counter or the rider's original proposed fare).
- **Topic**: `dispatch.deal`.
- **Trigger**: driver called `POST /v1/dispatch/deals/{deal_id}/bid/{bid_id}/reject` (§1.8).
- **Schema version**: 1.
- **Partition key**: `deal_id`.
- **Consumers**: `ride-request-service`, `notification-service`, `audit-service`.
- **Schema**: same envelope; `data` includes `bid_id`, `reason: "driver_reject"`.
- **Retry**: outbox, 3 attempts; DLQ `dispatch.deal.dlq`.

## 4. Consumed Events

### 4.1 `ride.request.created.v1`

- **Producer**: `ride-request-service`.
- **Reason**: begin a match attempt.
- **Handler**: create attempt; begin search.
- **Deduplication**: inbox on `event_id`; UNIQUE on
  `ride_request_id`.
- **Retry**: 3; failure → DLQ.

### 4.2 `ride.request.cancelled.v1`

- **Producer**: `ride-request-service`.
- **Reason**: abandon.
- **Handler**: mark attempt as `cancelled`; stop the search;
  expire any active offer.
- **Deduplication**: inbox on `event_id`.
- **Retry**: 3; failure → DLQ.

### 4.3 `driver.availability.offline.v1`

- **Producer**: `driver-availability-service`.
- **Reason**: drop the candidate.
- **Handler**: if the offline driver is the current offer, expire
  the offer and try the next candidate. If the driver is in the
  candidate list, remove them.
- **Deduplication**: inbox on `event_id`.
- **Retry**: 3; failure → DLQ.

### 4.4 `driver.location.updated.v1` (curated)

- **Producer**: `driver-location-service`.
- **Reason**: refresh candidate positions.
- **Handler**: update the candidate's position in the in-flight
  list; if the candidate is currently the offer, re-evaluate the
  ETA (offer stays open; we do not extend the TTL).
- **Deduplication**: inbox on `event_id`.
- **Retry**: 3; failure → DLQ.

### 4.5 `configuration.updated.v1`

- **Producer**: `configuration-service`.
- **Reason**: reload config.
- **Handler**: cache invalidation.
- **Deduplication**: inbox on `event_id`.
- **Retry**: 3; failure → DLQ.

### 4.6 `ride.deal.opened.v1` *(Make a Deal — Phase 7.5)*

- **Producer**: `ride-request-service`.
- **Reason**: a rider opened a deal — this service must enumerate eligible drivers and broadcast the deal.
- **Handler**: persist a `DealAttempt` (state `offering`); enumerate drivers via `driver-availability-service` + `driver-location-service` within `deal.broadcast.radius_m`; pick `deal.broadcast.max_concurrent_drivers` by score (proximity + rating); emit `dispatch.deal.bid_requested.v1`-style internal signal (no public event) to the drivers; record the invitation set in `DealAttempt.candidates_considered[]`.
- **Deduplication**: inbox on `event_id`.
- **Retry**: 3; failure → DLQ `ride.deal.dlq`.

### 4.7 `ride.deal.countered.v1` *(Make a Deal — Phase 7.5)*

- **Producer**: `ride-request-service` (rider counters a driver bid).
- **Reason**: notify the targeted driver that the rider has counter-offered.
- **Handler**: append to `deal.counters[]`; emit a push notification to the targeted driver; the driver-side `DealBid` enters `countered` state.
- **Deduplication**: inbox on `event_id`.
- **Retry**: 3; failure → DLQ `ride.deal.dlq`.

### 4.8 `ride.deal.accepted.v1` *(Make a Deal — Phase 7.5)*

- **Producer**: `ride-request-service` (rider accepted a driver bid).
- **Reason**: the deal is matched; this service must stop the bid-TTL sweepers and emit `dispatch.matched.v1` (the existing event) to the matched driver.
- **Handler**: idempotent — if the local `DealAttempt.state` is `matched`, this is a no-op.
- **Deduplication**: inbox on `event_id`.
- **Retry**: 3; failure → DLQ `ride.deal.dlq`.

### 4.9 `ride.deal.rejected.v1` *(Make a Deal — Phase 7.5)*

- **Producer**: `ride-request-service` (rider rejected the deal).
- **Reason**: stop the bid-TTL sweepers; mark the deal as terminal.
- **Handler**: cancel all live bids; transition `DealAttempt` to `cancelled`.
- **Deduplication**: inbox on `event_id`.
- **Retry**: 3; failure → DLQ `ride.deal.dlq`.

### 4.10 `ride.deal.expired.v1` *(Make a Deal — Phase 7.5)*

- **Producer**: `ride-request-service` (deal-window TTL elapsed).
- **Reason**: stop the bid-TTL sweepers; mark the deal as terminal.
- **Handler**: cancel all live bids; transition `DealAttempt` to `no_bid`.
- **Deduplication**: inbox on `event_id`.
- **Retry**: 3; failure → DLQ `ride.deal.dlq`.

## 5. Reliability

- **Timeouts**: outbound 300–500ms; DB 30s.
- **Retries**: bounded 3 on event handlers.
- **Circuit breakers**: per downstream.
- **Bulkheads**: per downstream connection pool.
- **Outbox**: `dispatch.outbox` table.
- **Inbox**: `dispatch.inbox` table.
- **DLQ**: per topic.
- **Reconciliation**: a daily job in `reporting-service` checks for
  attempts in `searching` or `offering` for more than 5 minutes
  (anomalous) and for `assignment_ledger` rows that never got a
  `trip_id` (anomalous).

## 6. Correlation IDs

Every request carries `X-Correlation-Id`. The service:
- Logs the id on every line within the request scope.
- Propagates it to outbound calls.
- Embeds it in every emitted event and Kafka header.
- Reads it from the inbound event envelope and uses the same id for
  the resulting state changes.

## 7. Distributed Tracing

OpenTelemetry. One root span per match attempt. Each candidate
evaluation, ETA call, and Kafka publish is a child span.
`traceparent` is propagated. Sample rate: 100% for errors, 10% for
successes in production.


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
| [`configuration-service`](../configuration-service/README.md) | DEGRADABLE | degrade (cache / default / flag) |
| [`driver-availability-service`](../driver-availability-service/README.md) | CRITICAL | 503 `DEPENDENCY_UNAVAILABLE` |
| [`driver-location-service`](../driver-location-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [`driver-service`](../driver-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [`eta-routing-service`](../eta-routing-service/README.md) | DEGRADABLE | degrade (cache / default / flag) |
| [`notification-service`](../notification-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [`pricing-service`](../pricing-service/README.md) | CRITICAL | 503 `DEPENDENCY_UNAVAILABLE` |
| [`ride-request-service`](../ride-request-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [`trip-service`](../trip-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |

### Downstream services that depend on this service

| Downstream | Class (from its perspective) |
|---|---|
| [`api-gateway`](../api-gateway/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`branch-service`](../branch-service/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`courier-dispatch-service`](../courier-dispatch-service/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`courier-service`](../courier-service/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`courier-tracking-service`](../courier-tracking-service/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`delivery-service`](../delivery-service/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`driver-availability-service`](../driver-availability-service/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`driver-location-service`](../driver-location-service/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`driver-service`](../driver-service/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`eta-routing-service`](../eta-routing-service/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`food-order-service`](../food-order-service/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`fraud-risk-service`](../fraud-risk-service/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`restaurant-order-mgmt-service`](../restaurant-order-mgmt-service/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`restaurant-service`](../restaurant-service/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`ride-request-service`](../ride-request-service/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`scheduled-ride-service`](../scheduled-ride-service/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`trip-service`](../trip-service/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`vehicle-service`](../vehicle-service/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
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

