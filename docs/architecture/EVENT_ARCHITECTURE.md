# Event Architecture

The platform is **event-driven** for cross-service integration. Synchronous
REST is used for read-your-writes within a workflow; events are used for
everything else.


```mermaid
flowchart LR
  subgraph Producer["Producer service"]
    db[("Outbox table<br/>PostgreSQL")]
    outbox["Outbox poller"]
    topic["Kafka topic<br/>domain.entity.event.vN"]
  end
  subgraph Broker["Kafka cluster (3.9, KRaft)"]
    t1["ride.trip.completed"]
    t2["ride.trip.state_changed"]
    t3["order.food.placed"]
    t4["payment.authorized"]
    t5["domain.*.vN — schema-registered, versioned in payload"]
  end
  subgraph Consumer["Consumer service"]
    cons["Kafka consumer<br/>(idempotent, dedup on event_id)"]
    dlq["DLQ topic"]
    cb["Circuit breaker<br/>(open on poison rate)"]
  end

  db --> outbox --> topic
  topic --> t1 & t2 & t3 & t4 & t5
  t1 --> cons
  cons -->|process| OK["Persist + emit derived events"]
  cons -->|poison| dlq
  cons -->|downstream down| cb
```

## Broker

**Apache Kafka** is the default event broker. See ADR-0005.

Topic creation is managed as code (one topic per event name, with
`vN` baked in). Partition count is set per topic based on the aggregate
throughput target. Replication factor ≥ 3 in production.

Naming convention:

- Topics: `<domain>.<entity>.<event>` (kebab-case segments). No version
  in the topic name. Versioning is in the payload.
- Example: `ride.trip.completed` topic carries `trip.completed.v1`,
  `trip.completed.v2`, etc.

This lets us roll forward consumers without renaming topics.

## Event Envelope (Shared Kernel)

Every event — domain, integration, audit — uses this envelope:

```json
{
  "event_id": "01HZX9C8W6K0G3V2Y5N1Q4R7PB",
  "event_name": "trip.completed.v1",
  "occurred_at": "2026-07-29T10:42:11.183Z",
  "schema_version": 1,
  "producer": "trip-service",
  "tenant_id": "global",
  "correlation_id": "01HZX9C7T0XK2P9F0V6E4B1MZA",
  "causation_id": "01HZX9C8K4D2H1A8N5J7V3R0Q9",
  "aggregate_type": "Trip",
  "aggregate_id": "01HZX9C5S3B1L7K0P2F8V4T6YDA",
  "data": { /* event-specific */ }
}
```

| Field | Type | Required | Purpose |
|-------|------|----------|---------|
| `event_id` | ULID/UUIDv7 string | yes | Unique id; used for deduplication by consumers |
| `event_name` | string | yes | `<domain.entity.event.vN>` — e.g. `trip.completed.v1` |
| `occurred_at` | RFC3339 UTC | yes | When the event was emitted (producer's clock) |
| `schema_version` | int | yes | Major version of the event payload schema |
| `producer` | string | yes | Service that produced the event |
| `tenant_id` | string | yes | For multi-tenancy; `global` is the default |
| `correlation_id` | string | yes | Traces the business request; same across the whole flow |
| `causation_id` | string | no | `event_id` of the event that caused this one (if any) |
| `aggregate_type` | string | yes | Logical aggregate name (e.g. `Trip`, `FoodOrder`) |
| `aggregate_id` | string | yes | Aggregate id; used as the partition key |
| `data` | object | yes | Event-specific payload (see catalog) |

`event_id` MUST be unique per logical event. Producers use ULID or
UUIDv7 to ensure time-orderability within a producer.

## Partitioning

- **Partition key = `aggregate_id`.** This guarantees per-aggregate
  ordering across the topic.
- Producer-side: hash on `aggregate_id` (already a string).
- Consumer-side: one consumer instance per partition, with at-least-once
  delivery. Consumers are responsible for ordering within a partition.

## Delivery Semantics

- **At-least-once** by default. Producers use the **outbox pattern** to
  ensure a state change and its event are committed atomically.
- **Consumers MUST be idempotent.** Use the `event_id` to dedupe (an
  `inbox` table keyed by `event_id` with a TTL).
- **No exactly-once at the platform level.** Where exactly-once is
  required (financial), it is achieved by combining:
  - Outbox in the producer.
  - Inbox + idempotency key in the consumer.
  - Idempotency keys on the downstream API call.
  - Reconciliation job in `reporting-service`.

## Event Catalog (Top-Level)

The full list lives in `EVENT_CATALOG.md` (one row per event). Below is
the high-level grouping.

### Identity & Profile

| Event | Producer | Consumers |
|-------|----------|-----------|
| `identity.user.created.v1` | `identity-service` | ``customer-service` (cross-persona profile)`, `customer-service`, `driver-service`, `courier-service`, ``restaurant-service` (merchant)`, `audit-service`, ``reporting-service` (data lake)` |
| `identity.user.suspended.v1` | `identity-service` | every service that owns a profile, `notification-service` |
| `identity.user.disabled.v1` | `identity-service` | every service that owns a profile, ``admin-service` (support module)` |
| `identity.session.revoked.v1` | `identity-service` | `audit-service` |
| `customer.created.v1` | `customer-service` | `audit-service`, ``reporting-service` (data lake)` |
| `customer.suspended.v1` | `customer-service` | ``trip-service` (ride-request)`, `food-order-service`, ``food-order-service` (cart)`, `payment-service` |
| `customer.segment.changed.v1` | `customer-service` | ``pricing-service` (promotion)`, ``pricing-service` (loyalty rules) / `customer-service` (account)`, `pricing-service` |
| `driver.created.v1` | `driver-service` | `audit-service`, ``reporting-service` (data lake)` |
| `driver.approved.v1` | `driver-service` | ``driver-service` (availability)`, ``driver-service` (dispatch)` |
| `driver.suspended.v1` | `driver-service` | ``driver-service` (availability)`, ``driver-service` (dispatch)`, ``trip-service` (ride-request)` |
| `driver.document.expired.v1` | `driver-service` | ``driver-service` (availability)`, ``driver-service` (dispatch)` |
| `courier.created.v1` | `courier-service` | `audit-service`, ``reporting-service` (data lake)` |
| `courier.approved.v1` | `courier-service` | ``courier-service` (dispatch)`, ``courier-service` (tracking)` |
| `courier.suspended.v1` | `courier-service` | ``courier-service` (dispatch)`, ``courier-service` (delivery)` |
| `vehicle.registered.v1` | ``driver-service` (vehicles)` | `driver-service`, `courier-service` |
| `vehicle.approved.v1` | ``driver-service` (vehicles)` | `driver-service`, `courier-service` |
| `vehicle.insurance.expired.v1` | ``driver-service` (vehicles)` | `driver-service`, `courier-service`, ``driver-service` (availability)` |
| `address.created.v1` / `address.updated.v1` | ``customer-service` (addresses)` | `customer-service` (cache invalidation) |

### Geospatial & Zones

| Event | Producer | Consumers |
|-------|----------|-----------|
| `zone.updated.v1` | ``geolocation-service` (zones)` | `pricing-service`, ``driver-service` (dispatch)`, ``courier-service` (dispatch)` |
| `zone.surge.updated.v1` | ``geolocation-service` (zones)` | `pricing-service`, ``driver-service` (dispatch)` |

### Platform

| Event | Producer | Consumers |
|-------|----------|-----------|
| `configuration.updated.v1` | `configuration-service` | every service (cache invalidation) |
| `feature_flag.updated.v1` | ``configuration-service` (flags)` | every service |
| `notification.sent.v1` / `notification.failed.v1` | `notification-service` | ``admin-service` (support module)`, `audit-service` |
| `comms.sms.sent.v1` / `comms.email.sent.v1` / `comms.push.sent.v1` | ``notification-service` (provider ACL)` | `notification-service`, `audit-service` |
| `file.uploaded.v1` / `file.scanned.v1` | `file-service` | `customer-service`, `driver-service`, `courier-service`, ``restaurant-service` (merchant)` |
| `admin.action.performed.v1` | `admin-service` | `audit-service` |
| `fraud.risk.scored.v1` | `fraud-risk-service` | `identity-service`, `payment-service`, ``driver-service` (dispatch)` |
| `fraud.account.blocked.v1` | `fraud-risk-service` | `identity-service` |
| `support.ticket.opened.v1` / `support.ticket.resolved.v1` | ``admin-service` (support module)` | `notification-service`, `audit-service` |

### Ride-Hailing

| Event | Producer | Consumers |
|-------|----------|-----------|
| `ride.request.created.v1` | ``trip-service` (ride-request)` | ``driver-service` (dispatch)`, `pricing-service`, `audit-service` |
| `ride.request.matched.v1` | ``trip-service` (ride-request)` (on dispatch match) | `trip-service`, `notification-service`, `audit-service` |
| `ride.request.cancelled.v1` | ``trip-service` (ride-request)` | `notification-service`, `audit-service`, `pricing-service` (fee calc) |
| `ride.request.expired.v1` | ``trip-service` (ride-request)` | ``driver-service` (dispatch)`, `audit-service` |
| `driver.availability.online.v1` / `driver.availability.offline.v1` | ``driver-service` (availability)` | ``driver-service` (dispatch)`, ``driver-service` (location)` |
| `driver.availability.busy.v1` | ``driver-service` (availability)` | ``driver-service` (dispatch)` |
| `driver.location.updated.v1` | ``driver-service` (location)` | ``driver-service` (dispatch)`, ``trip-service` (safety)`, ``geolocation-service` (ETA/routing)` (curated) |
| `dispatch.matched.v1` | ``driver-service` (dispatch)` | ``trip-service` (ride-request)`, `trip-service`, `notification-service` |
| `dispatch.no_driver.v1` | ``driver-service` (dispatch)` | ``trip-service` (ride-request)`, `notification-service` |
| `dispatch.offer.expired.v1` | ``driver-service` (dispatch)` | ``trip-service` (ride-request)`, ``driver-service` (dispatch)` (next attempt) |
| `trip.started.v1` | `trip-service` | ``payment-service` (ride saga)`, ``pricing-service` (loyalty rules) / `customer-service` (account)`, ``trip-service` (safety)`, `notification-service`, ``trip-service` (history)` |
| `trip.arrived.v1` | `trip-service` | `notification-service` |
| `trip.completed.v1` | `trip-service` | ``payment-service` (ride saga)`, ``payment-service` (driver earnings)`, ``driver-service` (incentives)`, ``pricing-service` (loyalty rules) / `customer-service` (account)`, ``trip-service` / `food-order-service` / `search-service` (review projections)`, ``trip-service` (history)`, `notification-service`, `audit-service` |
| `trip.cancelled.v1` | `trip-service` | ``payment-service` (ride saga)`, `notification-service`, `audit-service` |
| `ride.payment.completed.v1` | ``payment-service` (ride saga)` | ``payment-service` (driver earnings)`, ``trip-service` (history)`, `audit-service`, `customer-service` (history) |
| `ride.payment.failed.v1` | ``payment-service` (ride saga)` | ``admin-service` (support module)`, `notification-service`, `audit-service` |
| `driver.earning.accrued.v1` | ``payment-service` (driver earnings)` | ``trip-service` (history)`, `reporting-service` |
| `driver.incentive.earned.v1` | ``driver-service` (incentives)` | ``payment-service` (driver earnings)` |
| `driver.withdrawal.requested.v1` / `driver.withdrawal.completed.v1` | ``payment-service` (driver earnings)` | `payment-service`, `audit-service` |
| `scheduled_ride.due.v1` | ``trip-service` (scheduled)` | ``trip-service` (ride-request)` |
| `ride.safety.sos.v1` / `ride.safety.incident.v1` | ``trip-service` (safety)` | `notification-service`, ``admin-service` (support module)`, `audit-service` |
| `trip.reward.granted.v1` | `trip-service` | ``payment-service` (driver earnings)`, ``payment-service` (wallet)`, `ledger-service` (info), `notification-service`, `audit-service` |
| `trip.reward.reversed.v1` | `trip-service` | ``payment-service` (driver earnings)`, ``payment-service` (wallet)`, `ledger-service` (info), `notification-service`, `audit-service` |

### Food Marketplace

| Event | Producer | Consumers |
|-------|----------|-----------|
| `merchant.created.v1` / `merchant.approved.v1` / `merchant.suspended.v1` | ``restaurant-service` (merchant)` | `restaurant-service`, ``payment-service` (merchant settlement)`, `audit-service` |
| `restaurant.created.v1` | `restaurant-service` | ``restaurant-service` (branch)`, ``restaurant-service` (menu)`, `search-service`, `audit-service` |
| `restaurant.approved.v1` | `restaurant-service` | `search-service`, ``restaurant-service` (menu)` |
| `restaurant.online.v1` / `restaurant.offline.v1` | `restaurant-service` | ``food-order-service` (cart)`, `search-service`, ``courier-service` (dispatch)` |
| `branch.created.v1` / `branch.updated.v1` / `branch.hours.changed.v1` | ``restaurant-service` (branch)` | ``restaurant-service` (menu)`, ``food-order-service` (cart)`, ``courier-service` (dispatch)`, `search-service` |
| `branch.busy.v1` | ``restaurant-service` (branch)` | ``courier-service` (dispatch)`, ``food-order-service` (cart)` |
| `menu.created.v1` / `menu.updated.v1` | ``restaurant-service` (menu)` | ``food-order-service` (cart)`, `search-service`, ``restaurant-service` (inventory)` |
| `menu.item.price.changed.v1` | ``restaurant-service` (menu)` | ``food-order-service` (cart)` (re-quote) |
| `menu.item.unavailable.v1` | ``restaurant-service` (menu)` | ``food-order-service` (cart)` (remove from cart), `search-service` |
| `inventory.item.out_of_stock.v1` / `inventory.item.restocked.v1` | ``restaurant-service` (inventory)` | ``restaurant-service` (menu)`, ``food-order-service` (cart)`, `search-service` |
| `cart.created.v1` / `cart.updated.v1` / `cart.checked_out.v1` / `cart.abandoned.v1` | ``food-order-service` (cart)` | ``reporting-service` (data lake)`, `customer-service` (history) |
| `checkout.completed.v1` | ``food-order-service` (checkout)` | `food-order-service`, ``food-order-service` (cart)` (clear), `audit-service` |
| `checkout.failed.v1` | ``food-order-service` (checkout)` | ``food-order-service` (cart)` (re-enable), `notification-service` |
| `food.order.placed.v1` | `food-order-service` | ``food-order-service` (queue)`, `notification-service`, ``reporting-service` (data lake)`, `audit-service` |
| `food.order.accepted.v1` | `food-order-service` (on accept) | `notification-service`, `customer-service` (history) |
| `food.order.rejected.v1` | `food-order-service` (on reject) | ``payment-service` (food saga)` (refund), `notification-service` |
| `food.order.preparing.v1` | `food-order-service` (via ``food-order-service` (queue)`) | `notification-service` |
| `food.order.ready.v1` | `food-order-service` (via ``food-order-service` (queue)`) | ``courier-service` (dispatch)`, `notification-service` |
| `food.order.cancelled.v1` | `food-order-service` | ``payment-service` (food saga)` (refund), `notification-service` |

### Food Delivery & Couriers

| Event | Producer | Consumers |
|-------|----------|-----------|
| `delivery.courier.assigned.v1` | ``courier-service` (dispatch)` | ``courier-service` (delivery)`, `food-order-service`, `notification-service` |
| `delivery.dispatch.no_courier.v1` | ``courier-service` (dispatch)` | `food-order-service`, `notification-service` |
| `courier.location.updated.v1` | ``courier-service` (tracking)` | ``courier-service` (dispatch)`, ``courier-service` (delivery)` |
| `delivery.pickup.v1` | ``courier-service` (delivery)` | `notification-service` |
| `delivery.in_transit.v1` | ``courier-service` (delivery)` | `notification-service`, `customer-service` (history) |
| `delivery.completed.v1` | ``courier-service` (delivery)` | ``payment-service` (food saga)`, ``payment-service` (courier earnings)`, `customer-service` (history), `notification-service`, ``trip-service` / `food-order-service` / `search-service` (review projections)` |
| `delivery.failed.v1` | ``courier-service` (delivery)` | `food-order-service`, ``payment-service` (food saga)` (refund), `notification-service` |
| `courier.earning.accrued.v1` / `courier.withdrawal.*.v1` | ``payment-service` (courier earnings)` | `reporting-service`, `audit-service` |

### Financial

| Event | Producer | Consumers |
|-------|----------|-----------|
| `payment.attempted.v1` | `payment-service` | `fraud-risk-service`, `audit-service` |
| `payment.authorized.v1` | `payment-service` | ``payment-service` (ride saga)`, ``payment-service` (food saga)`, ``payment-service` (wallet)` |
| `payment.captured.v1` | `payment-service` | ``payment-service` (ride saga)`, ``payment-service` (food saga)`, ``payment-service` (wallet)`, `ledger-service`, `audit-service` |
| `payment.failed.v1` | `payment-service` | ``payment-service` (ride saga)`, ``payment-service` (food saga)`, `notification-service` |
| `payment.refund.initiated.v1` / `payment.refund.completed.v1` | `payment-service` | ``payment-service` (wallet)`, `ledger-service`, `audit-service` |
| `wallet.credited.v1` / `wallet.debited.v1` / `wallet.held.v1` / `wallet.released.v1` | ``payment-service` (wallet)` | `ledger-service`, `customer-service`, `audit-service` |
| `ledger.posted.v1` | `ledger-service` | `reporting-service`, `audit-service` |
| `food.payment.completed.v1` | ``payment-service` (food saga)` | `customer-service` (history), ``payment-service` (merchant settlement)`, ``payment-service` (courier earnings)`, `audit-service` |
| `food.payment.failed.v1` | ``payment-service` (food saga)` | ``admin-service` (support module)`, `notification-service` |
| `merchant.settlement.accrued.v1` | ``payment-service` (merchant settlement)` | ``restaurant-service` (merchant)` (UI), `audit-service` |
| `merchant.payout.scheduled.v1` / `merchant.payout.completed.v1` | ``payment-service` (merchant settlement)` | ``restaurant-service` (merchant)`, `payment-service`, `audit-service` |

### Pricing & Rules

| Event | Producer | Consumers |
|-------|----------|-----------|
| `pricing.quote.created.v1` | `pricing-service` | ``reporting-service` (data lake)` |
| `pricing.rating_density.applied.v1` | `pricing-service` | ``reporting-service` (data lake)`, `reporting-service` |
| `pricing.loyalty_discount.applied.v1` | `pricing-service` | ``reporting-service` (data lake)`, `reporting-service` |
| `pricing.geo_config.updated.v1` | `pricing-service` | `pricing-service`, ``reporting-service` (data lake)`, `audit-service` |
| `promotion.created.v1` / `promotion.disabled.v1` | ``pricing-service` (promotion)` | ``food-order-service` (cart)`, `pricing-service` |
| `promotion.redeemed.v1` | ``pricing-service` (promotion)` | ``reporting-service` (data lake)`, `audit-service` |
| `loyalty.points.earned.v1` / `loyalty.points.burned.v1` / `loyalty.tier.changed.v1` | ``pricing-service` (loyalty rules) / `customer-service` (account)` | `customer-service` (UI), ``reporting-service` (data lake)` |
| `loyalty.frequent_zone.aggregated.v1` | ``pricing-service` (loyalty rules) / `customer-service` (account)` | `pricing-service` (helper) |
| `tax.calculated.v1` | ``pricing-service` (tax)` | ``reporting-service` (data lake)` |
| `review.submitted.v1` / `review.aggregated.v1` | ``trip-service` / `food-order-service` / `search-service` (review projections)` | `driver-service` (rating), `courier-service` (rating), `restaurant-service` (rating), ``reporting-service` (data lake)` |
| `review.zone_aggregated.v1` | ``trip-service` / `food-order-service` / `search-service` (review projections)` | `pricing-service` (helper) |

## Schema Evolution

- **Within a major version**: producers may add optional fields.
  Consumers MUST ignore unknown fields. Use JSON Schema validation at
  ingress to enforce this contract.
- **Across major versions**: producers MAY publish both `*.v1` and
  `*.v2` events simultaneously for a deprecation window (≥ 6 months).
  Consumers migrate, then deprecate `*.v1`. A consumer can opt to consume
  both and dispatch internally.
- **Removing a field**: requires a major version bump.
- **Renaming a field**: requires a major version bump.
- **Changing a field's type**: requires a major version bump.
- **Changing a partition key**: requires a new topic.

## Outbox Pattern (Producer Side)

Every service that emits events uses the **outbox pattern**:

1. Inside the same DB transaction that mutates state, also write a row
   to the `outbox` table (`event_id`, `topic`, `payload`, `headers`,
   `created_at`, `claimed_at`).
2. A separate poller (or Debezium-style logical decoding) reads the
   outbox, publishes to Kafka, and marks the row as published.
3. If publishing fails, the row remains and is retried.
4. Outbox rows are purged after the broker confirms (e.g. 24h).

This guarantees: a state change and its event are atomic. The
alternative (publish-then-commit) loses events when the commit fails.

## Inbox Pattern (Consumer Side)

Every consumer maintains an `inbox` table:

- `event_id` (PK)
- `consumer`
- `received_at`
- `processed_at` (nullable)
- `error` (nullable)

On receive:

1. Insert `event_id` (no-op on duplicate → treat as already processed).
2. Process the event.
3. Update `processed_at`.

This is the consumer's idempotency key. The handler body MUST be safe
to re-run (idempotent) for full correctness in case the consumer crashes
between step 2 and 3.

## Dead-Letter Topics

Every topic has a paired `<topic>.dlq`. Consumers route a message to the
DLQ when:

- It cannot be deserialized (poison message).
- A handler raises an unhandled exception after N retries with
  exponential backoff.
- A business rule fails validation consistently.

DLQ messages are inspected via tooling (`replay-cli` or
`admin-service` → DLQ inspector UI). DLQ retention: 30 days.

## Replay

A topic's history is replayable by resetting consumer offsets (or by a
dedicated replay consumer that writes to a new topic). The
``reporting-service` (data lake)` and `reporting-service` use this on a fresh
schema migration.

## Anti-Patterns Explicitly Avoided

- Event chains of more than 3 hops without a corresponding saga or
  observable flow.
- "Events" that are just internal method calls serialised — those are
  commands, use REST.
- Broadcasting an entire aggregate as the event payload — emit
  deltas + a stable `aggregate_id`, let consumers fetch the latest.
- Coupling consumers to the producer's internal class names — event
  payload schemas are owned by the event, not the service.