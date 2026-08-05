# food-order-service — Integration Contract

## 1. Inbound APIs

### 1.1 `GET /v1/orders/{id}`

- **Purpose**: Read an order.
- **Auth**: Bearer JWT (the customer of the order, staff, or
  admin).
- **Response (200)**: full order including items, modifiers,
  add-ons, state history (optional), and snapshot.
- **Cached**: 30 s TTL in Redis, key `order:{id}`.

### 1.2 `GET /v1/orders/by-customer/{customer_id}`

- **Purpose**: List orders for a customer.
- **Auth**: the customer, or `client_credentials`.
- **Query params**: `state`, `from`, `to`, `cursor`, `limit`.

### 1.3 `GET /v1/orders/by-restaurant/{restaurant_id}`

- **Purpose**: List orders for a restaurant.
- **Auth**: `client_credentials`.

### 1.4 `GET /v1/orders/by-branch/{branch_id}`

- **Purpose**: List orders for a branch.
- **Auth**: `client_credentials`.

### 1.5 `POST /v1/orders/{id}/cancellation`

- **Purpose**: Customer cancels the order per the policy.
- **Auth**: the customer of the order.
- **Idempotency**: `Idempotency-Key` header **required**.
- **Request**: `{"reason_code": "changed_mind",
  "reason_text": "..."}`.
- **Response (200)**:
  ```json
  {
    "id": "01HZX...",
    "state": "cancelled",
    "cancellation_fee_minor": 500,
    "cancellation_refund_minor": 3095,
    "currency": "EUR"
  }
  ```
- **Errors**:
  - 401 `UNAUTHENTICATED`
  - 403 `FORBIDDEN`
  - 404 `ORDER_NOT_FOUND`
  - 409 `STATE_INVALID` (not in `placed`, `accepted`, or
    `preparing`)
  - 409 `CANCEL_NOT_ALLOWED` (after `ready`)
  - 422 `IDEMPOTENCY_KEY_REUSED`
  - 429 `RATE_LIMITED`

### 1.6 `GET /v1/orders/{id}/cancellation-fee`

- **Purpose**: Preview the cancellation fee.
- **Auth**: the customer of the order.
- **Response (200)**: `{"fee_minor": 500, "refund_minor": 3095,
  "currency": "EUR", "window": "partial"}`.

### 1.7 `POST /v1/orders/{id}/state-transition`

- **Purpose**: Manual state transition (admin / customer
  service / system).
- **Auth**: `support_agent`, `platform_admin`, or
  `client_credentials`.
- **Idempotency**: required.
- **Request**: `{"to_state": "delivered", "reason_code":
  "manual_override", "reason_text": "..."}`.
- **Errors**: 403, 404, 409 `STATE_INVALID`, 422
  `IDEMPOTENCY_KEY_REUSED`.

### 1.8 `GET /v1/orders/{id}/state-history`

- **Purpose**: Read the state history.
- **Auth**: the customer, staff, or admin.

### 1.9 `POST /v1/orders/{id}/deal` *(Make a Deal — Phase 7.5)*

- **Purpose**: Open a Make-a-Deal negotiation on an existing food
  order. The customer proposes a price (typically a discounted
  delivery fee); the response carries the fairness band that bounds
  the deal. Canonical spec:
  [`docs/shared/DEAL_FEATURE.md`](../../shared/DEAL_FEATURE.md) §5.
- **Auth**: Bearer JWT (order owner). Required scope: `food_order.deal`.
- **Idempotency**: `Idempotency-Key` required (format `deal:<order_id>:open`).
- **Pre-flight**: short-circuits with `404 DEAL_DISABLED_IN_CITY` unless `deal.enabled.{city_id}.{delivery_type}` is `true`.
- **Request**:
  ```json
  {
    "proposed_fare_minor": 800,
    "currency":           "EUR",
    "quote_id":           "01HZX9C8X1N4M5K7B8V3R0Q9D2H"
  }
  ```
- **Response (201)**:
  ```json
  {
    "deal_id":      "01HZX9C5S3B1L7K0P2F8V4T6YDA",
    "state":        "open",
    "order_id":     "01HZX9C8W6K0G3V2Y5N1Q4R7PB",
    "fairness_band": {
      "min_fare_minor": 500,
      "max_fare_minor": 1200,
      "currency":      "EUR"
    },
    "expires_at":  "2026-08-05T10:43:41.183Z",
    "current_round": 1
  }
  ```
- **Errors**: 400 / 401 / 403 / 404 `DEAL_DISABLED_IN_CITY` / 409 `ORDER_NOT_OPEN` / 410 `QUOTE_EXPIRED` / 422 `FARE_OUT_OF_BAND` / 503.

### 1.10 `POST /v1/deals/{id}/counter` *(Make a Deal — Phase 7.5)*

- **Purpose**: Customer submits a counter-offer against a courier bid.
- **Auth**: Bearer JWT (deal owner). Required scope: `food_order.deal`.
- **Idempotency**: `Idempotency-Key` required (`deal:<deal_id>:counter`).
- **Request**: `{ "bid_id": "01HZX9C8K4D2H1A8N5J7V3R0Q9", "counter_fare_minor": 900 }`.
- **Response (200)**: updated `Deal` (state `countered`).
- **Errors**: 400 / 401 / 403 / 404 / 409 `COUNTER_LIMIT_EXCEEDED` / 422 `FARE_OUT_OF_BAND`.

### 1.11 `POST /v1/deals/{id}/accept` *(Make a Deal — Phase 7.5)*

- **Purpose**: Customer accepts a courier bid.
- **Auth**: Bearer JWT (deal owner). Required scope: `food_order.deal`.
- **Idempotency**: `Idempotency-Key` required (`deal:<deal_id>:accept`).
- **Request**: `{ "bid_id": "01HZX9C8K4D2H1A8N5J7V3R0Q9" }`.
- **Response (200)**: `Deal` moved to `matched`; this service then emits `food.order.placed.v1` (§3.1) carrying `accepted_fare_minor`.
- **Errors**: 400 / 401 / 403 / 404 / 409 `DEAL_NOT_NEGOTIATING` / 410 `BID_EXPIRED`.

### 1.12 `POST /v1/deals/{id}/reject` *(Make a Deal — Phase 7.5)*

- **Purpose**: Customer rejects a deal or a specific bid.
- **Auth**: Bearer JWT (deal owner). Required scope: `food_order.deal`.
- **Idempotency**: `Idempotency-Key` required (`deal:<deal_id>:reject`).
- **Request**: `{ "bid_id": "…" | null }` (null = reject the whole deal).
- **Response (200)**: `Deal` moved to `rejected`; emits `food.deal.rejected.v1`.
- **Errors**: 400 / 401 / 403 / 404 / 409 `DEAL_NOT_OPEN`.

### 1.13 `GET /v1/deals/{id}` *(Make a Deal — Phase 7.5)*

- **Purpose**: Read the deal state (customer polls for new bids / counters).
- **Auth**: Bearer JWT (deal owner or admin).
- **Response (200)**: the full `Deal` aggregate including `bids[]`, `counters[]`, `current_round`, `expires_at`.
- **Errors**: 401 / 403 / 404.

## 2. Outbound APIs

| Target | Method | URI | Purpose | Timeout | Retry | Circuit |
|--------|--------|-----|---------|---------|-------|---------|
| `customer-service` | GET | /v1/customers/{id} | verify customer | 1 s | 3 | yes |
| `restaurant-service` | GET | /v1/restaurants/{id} | verify restaurant | 1 s | 3 | yes |
| `branch-service` | GET | /v1/branches/{id} | verify branch | 1 s | 3 | yes |
| `cart-service` | GET | /v1/carts/{id} | read cart (rare) | 1 s | 3 | yes |
| `notification-service` | POST | /v1/notifications | notify customer | 1 s | 3 | yes |
| `pricing-service` | GET | /v1/quotes/{quote_id}/fairness-band | deal fare band *(Make a Deal — Phase 7.5)* | 500 ms | 1 | yes |

## 3. Produced Events

### 3.1 `food.order.placed.v1`

- **Producer**: `food-order-service`.
- **Topic**: `food_order.food_order.placed`.
- **Trigger**: order created on `checkout.completed.v1`.
- **Schema version**: 1.
- **Partition key**: `order.id`.
- **Consumers**: `restaurant-order-mgmt-service`,
  `notification-service`, `analytics-service`, `audit-service`.
- **Schema**:
  ```json
  {
    "event_id": "01HZX...",
    "event_name": "food.order.placed.v1",
    "occurred_at": "2026-07-29T10:42:11.183Z",
    "schema_version": 1,
    "producer": "food-order-service",
    "tenant_id": "global",
    "correlation_id": "01HZX...",
    "aggregate_type": "FoodOrder",
    "aggregate_id": "01HZX...",
    "data": {
      "order_id": "01HZX...",
      "customer_id": "01HZX...",
      "branch_id": "01HZX...",
      "restaurant_id": "01HZX...",
      "address_id": "01HZX...",
      "payment_intent_id": "01HZX...",
      "checkout_session_id": "01HZX...",
      "total_minor": 3639,
      "currency": "EUR",
      "slot_start_at": "2026-07-29T12:00:00Z",
      "slot_end_at": "2026-07-29T12:30:00Z",
      "items": [
        { "product_id": "01HZX...", "quantity": 2, "unit_price_minor": 1295 }
      ]
    }
  }
  ```
- **DLQ**: `food_order.food_order.placed.dlq`.

### 3.2 `food.order.accepted.v1`

Same envelope, `data.accepted_at`, `data.accepted_by_kc_sub`.

### 3.3 `food.order.rejected.v1`

Same envelope, `data.rejected_at`,
`data.reason_code`, `data.reason_text`,
`data.rejected_by_kc_sub`.

### 3.4 `food.order.preparing.v1`

Same envelope, `data.preparing_at`.

### 3.5 `food.order.ready.v1`

Same envelope, `data.ready_at`. Consumed by
`courier-dispatch-service`.

### 3.6 `food.order.cancelled.v1`

Same envelope, `data.cancelled_at`,
`data.cancellation_fee_minor`,
`data.cancellation_refund_minor`,
`data.reason_code`, `data.cancellation_actor_kc_sub`.
Consumed by `food-payment-integration-service` for refund.

### 3.7 `food.deal.opened.v1` *(Make a Deal — Phase 7.5)*

- **Producer**: this service.
- **Topic**: `food.deal`.
- **Trigger**: customer called `POST /v1/orders/{id}/deal` (§1.9); the deal was written in state `open` and the fairness-band snapshot was captured.
- **Schema version**: 1.
- **Partition key**: `deal_id` (= `aggregate_id`).
- **Consumers**: `courier-dispatch-service`, `notification-service`, `audit-service`.
- **Schema**: see the canonical block in [`../../shared/DEAL_FEATURE.md`](../../shared/DEAL_FEATURE.md) §4.3. The `data` block includes `proposed_fare_minor`, `fairness_band`, `config_snapshot`, `quote_id`, `expires_at`, `current_round`, `order_id`.
- **Retry**: outbox, 3 attempts; DLQ `food.deal.dlq`.

### 3.8 `food.deal.countered.v1` *(Make a Deal — Phase 7.5)*

- **Producer**: this service (customer-initiated counter).
- **Topic**: `food.deal`.
- **Schema version**: 1.
- **Partition key**: `deal_id`.
- **Consumers**: `courier-dispatch-service`, `notification-service`, `audit-service`.
- **Schema**: same envelope as `ride.deal.countered.v1` (`../../shared/DEAL_FEATURE.md` §4.3); `data` includes `bid_id`, `counter_id`, `from_actor: "customer"`, `counter_fare_minor`, `round_number`.
- **Retry**: outbox, 3 attempts; DLQ `food.deal.dlq`.

### 3.9 `food.deal.accepted.v1` *(Make a Deal — Phase 7.5)*

- **Producer**: this service (customer accepted a courier bid).
- **Topic**: `food.deal`.
- **Schema version**: 1.
- **Partition key**: `deal_id`.
- **Consumers**: `courier-dispatch-service`, `notification-service`, `audit-service`, `pricing-service`.
- **Schema**: same envelope as `ride.deal.accepted.v1`; `data` includes `bid_id`, `accepted_fare_minor`, `courier_id`, `customer_id`, `order_id`.
- **Side effect**: this service then emits the existing `food.order.placed.v1` (§3.1) carrying `accepted_fare_minor` so the existing pickup/delivery pipeline picks up the order at the agreed price.
- **Retry**: outbox, 3 attempts; DLQ `food.deal.dlq`.

### 3.10 `food.deal.rejected.v1` *(Make a Deal — Phase 7.5)*

- **Producer**: this service (customer rejected) OR `courier-dispatch-service` (courier rejected).
- **Topic**: `food.deal`.
- **Schema version**: 1.
- **Partition key**: `deal_id`.
- **Consumers**: counterpart + `notification-service` + `audit-service`.
- **Schema**: same envelope; `data` includes `from_actor`, `reason` (`"customer_cancel"` / `"courier_reject"` / `"bid_timeout"`).
- **Retry**: outbox, 3 attempts; DLQ `food.deal.dlq`.

### 3.11 `food.deal.expired.v1` *(Make a Deal — Phase 7.5)*

- **Producer**: this service (holds the deal-window timer) OR `courier-dispatch-service` (holds the bid-TTL timer).
- **Topic**: `food.deal`.
- **Schema version**: 1.
- **Partition key**: `deal_id`.
- **Consumers**: counterpart + `notification-service` + `audit-service`.
- **Schema**: same envelope; `data` includes `reason` (`"window_timeout"` / `"bid_timeout"` / `"max_rounds_exceeded"`), `last_state`.
- **Retry**: outbox, 3 attempts; DLQ `food.deal.dlq`.

## 4. Consumed Events

### 4.1 `checkout.completed.v1`

- **Producer**: `checkout-service`.
- **Reason**: create the order.
- **Handler**: create the order in `state = placed` with the
  snapshot from the session; emit `food.order.placed.v1`.
- **Deduplication**: inbox on `event_id`; the
  `checkout_session_id` UNIQUE constraint catches duplicates
  that bypass the inbox.

### 4.2 `food.order.accepted.v1` (from `restaurant-order-mgmt-service`)

- **Producer**: `restaurant-order-mgmt-service`.
- **Reason**: restaurant accepted.
- **Handler**: row-level lock; set `state = accepted`,
  `accepted_at = now()`, `accepted_by_kc_sub`; insert
  `order_state_history`; emit `food.order.accepted.v1` (echo).
- **Idempotency**: inbox dedup.

### 4.3 `food.order.rejected.v1`

- **Producer**: `restaurant-order-mgmt-service`.
- **Reason**: restaurant rejected.
- **Handler**: row-level lock; set `state = rejected`,
  `rejected_at`, `rejection_reason_code`,
  `rejection_reason_text`; insert `order_state_history`;
  emit `food.order.rejected.v1`.
- **Idempotency**: inbox dedup.

### 4.4 `food.order.preparing.v1`

- Same as 4.2 for `preparing`.

### 4.5 `food.order.ready.v1`

- Same as 4.2 for `ready`.

### 4.6 `delivery.courier.assigned.v1`

- **Producer**: `courier-dispatch-service` or
  `delivery-service`.
- **Reason**: courier assigned.
- **Handler**: row-level lock; set `state = courier_assigned`,
  `courier_assigned_at`, `delivery_id`; insert
  `order_state_history`.

### 4.7 `delivery.pickup.v1`

- Same for `picked_up`.

### 4.8 `delivery.completed.v1`

- Same for `delivered`.

### 4.9 `payment.captured.v1`

- **Producer**: `payment-service`.
- **Reason**: informational.
- **Handler**: log only; the `food-payment-integration-service`
  orchestrates the capture.

### 4.10 `payment.refund.completed.v1`

- **Producer**: `payment-service`.
- **Reason**: informational; a refund was issued for a
  cancelled / rejected order.

### 4.11 `delivery.deal.bid.submitted.v1` *(Make a Deal — Phase 7.5)*

- **Producer**: `courier-dispatch-service`.
- **Reason**: a courier submitted a bid against an open deal.
- **Handler**: append to `deal.bids[]`; if the deal is in state `open`, transition to `negotiating`; notify the customer.
- **Deduplication**: inbox on `event_id`.
- **Retry**: 3; failure → DLQ `delivery.deal.bid.submitted.dlq`.

### 4.12 `delivery.deal.bid.expired.v1` *(Make a Deal — Phase 7.5)*

- **Producer**: `courier-dispatch-service`.
- **Reason**: the bid-TTL elapsed without the customer acting on the bid.
- **Handler**: mark the bid `expired`; if no live bids remain and no counter is open, transition the deal to `expired` and emit `food.deal.expired.v1`.
- **Deduplication**: inbox on `event_id`.
- **Retry**: 3; failure → DLQ `delivery.deal.bid.expired.dlq`.

### 4.13 `delivery.deal.accepted.v1` *(Make a Deal — Phase 7.5)*

- **Producer**: `courier-dispatch-service`.
- **Reason**: a courier accepted the customer's last counter.
- **Handler**: idempotent — transitions the deal to `matched` and emits `food.deal.accepted.v1` (3.9) from this side. The downstream `food.order.placed.v1` carries the agreed `accepted_fare_minor`.
- **Deduplication**: inbox on `event_id`.
- **Retry**: 3; failure → DLQ `delivery.deal.accepted.dlq`.

## 5. Reliability

- **Timeouts**: HTTP 1 s; DB 30 s; Kafka 5 s.
- **Retries**: 3 attempts with exponential backoff and jitter;
  Idempotency-Key on all non-idempotent calls.
- **Circuit breakers**: standard 5/30 s.
- **Bulkheads**: per-downstream connection pools.
- **Outbox**: yes, `food_order.outbox`.
- **Inbox**: yes, `food_order.inbox`.
- **DLQ**: every topic has a paired `.dlq`; 30-day retention.
- **Reconciliation**: daily job in `reporting-service` checks
  for orders in `placed` for more than 10 minutes (the
  `restaurant-order-mgmt-service` accept timer should have
  fired) and for orders in `preparing` for more than 2 hours
  (the kitchen should have marked ready).

## 6. Correlation IDs

All requests carry `X-Correlation-Id`; the service propagates
it to outbound calls and embeds it in the event envelope.

## 7. Distributed Tracing

OpenTelemetry SDK; one root span per request; named
`POST /v1/orders/{id}/cancellation`, etc. Propagated through
Kafka. Sample 100% on errors, 10% on success in production.

## 8. Threat Surface (per `SECURITY_ARCHITECTURE.md` §18)

| Threat | Mitigation |
|--------|------------|
| Spoofing | mTLS + JWT |
| Tampering (admin state transition) | HMAC-SHA256 signature |
| Repudiation | audit log + state history |
| Information disclosure | no PII beyond the customer's id |
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
| [`analytics-service`](../analytics-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [`audit-service`](../audit-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [`branch-service`](../branch-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [`cart-service`](../cart-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [`checkout-service`](../checkout-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [`configuration-service`](../configuration-service/README.md) | DEGRADABLE | degrade (cache / default / flag) |
| [`courier-dispatch-service`](../courier-dispatch-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [`customer-service`](../customer-service/README.md) | CRITICAL | 503 `DEPENDENCY_UNAVAILABLE` |
| [`delivery-service`](../delivery-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [`dispatch-service`](../dispatch-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [`feature-flag-service`](../feature-flag-service/README.md) | DEGRADABLE | degrade (cache / default / flag) |
| [`food-payment-integration-service`](../food-payment-integration-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [`menu-service`](../menu-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [`notification-service`](../notification-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [`payment-service`](../payment-service/README.md) | CRITICAL | 503 `DEPENDENCY_UNAVAILABLE` |
| [`pricing-service`](../pricing-service/README.md) | CRITICAL | 503 `DEPENDENCY_UNAVAILABLE` |
| [`restaurant-order-mgmt-service`](../restaurant-order-mgmt-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [`restaurant-service`](../restaurant-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [`review-rating-service`](../review-rating-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |

### Downstream services that depend on this service

| Downstream | Class (from its perspective) |
|---|---|
| [`api-gateway`](../api-gateway/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`branch-service`](../branch-service/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`cart-service`](../cart-service/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`checkout-service`](../checkout-service/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`courier-dispatch-service`](../courier-dispatch-service/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`courier-earnings-service`](../courier-earnings-service/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`customer-service`](../customer-service/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`delivery-service`](../delivery-service/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`food-payment-integration-service`](../food-payment-integration-service/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`fraud-risk-service`](../fraud-risk-service/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`inventory-service`](../inventory-service/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`loyalty-service`](../loyalty-service/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`menu-service`](../menu-service/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`notification-service`](../notification-service/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`pricing-service`](../pricing-service/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`promotion-service`](../promotion-service/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`restaurant-order-mgmt-service`](../restaurant-order-mgmt-service/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`restaurant-service`](../restaurant-service/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`review-rating-service`](../review-rating-service/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`tax-service`](../tax-service/README.md) | _see [`SERVICE_ISOLATION.md` §2](../../architecture/SERVICE_ISOLATION.md)_ |
| _…and 1 more_ | |

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

