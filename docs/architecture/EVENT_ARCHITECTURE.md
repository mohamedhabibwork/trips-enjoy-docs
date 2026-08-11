# Event Architecture

The platform is **event-driven** for cross-service integration.
Synchronous REST is used for read-your-writes within a workflow;
events are used for everything else. Producer and consumer columns
reference the **20 active services** per
[ADR-0017](adrs/0017-20-service-architecture.md); absorbed capabilities
live inside the surviving service's binary (e.g. driver location is
a worker inside `driver-service`, not a separate service).

```mermaid
flowchart LR
  subgraph Producer["Producer service"]
    db[("Outbox table<br/>PostgreSQL")]
    outbox["Outbox poller"]
    topic["Kafka topic<br/>domain.entity.event.vN"]
  end
  subgraph Broker["Kafka cluster (KRaft)"]
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

**Apache Kafka** is the default event broker. See
[ADR-0005](adrs/0005-kafka-as-event-broker.md).

Topic creation is managed as code (one topic per event name, with
`vN` baked in). Partition count is set per topic based on the
aggregate throughput target. Replication factor ≥ 3 in production.

Naming convention:

- Topics: `<domain>.<entity>.<event>` (kebab-case segments). No
  version in the topic name. Versioning is in the payload.
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
| `correlation_id` | string | yes | Traces the business request; same across the whole flow. **Equals the API-gateway-generated request id** ([ADR-0019](adrs/0019-request-id-at-the-edge.md)) and equals the `X-Request-Id` and `X-Correlation-Id` Kafka headers on the produced message. |
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
- Consumer-side: one consumer instance per partition, with
  at-least-once delivery. Consumers are responsible for ordering
  within a partition.

## Delivery Semantics

- **At-least-once** by default. Producers use the **outbox pattern**
  to ensure a state change and its event are committed atomically.
- **Consumers MUST be idempotent.** Use the `event_id` to dedupe (an
  `inbox` table keyed by `event_id` with a TTL).
- **No exactly-once at the platform level.** Where exactly-once is
  required (financial), it is achieved by combining:
  - Outbox in the producer.
  - Inbox + idempotency key in the consumer.
  - Idempotency keys on the downstream API call.
  - Reconciliation job in `reporting-service`.

## Event Catalog (Top-Level)

The full per-event detail lives in `EVENT_CATALOG.md` (one row per
event). Below is the high-level grouping by bounded context, with
producer / consumer columns mapped to the 20 active services.

### Identity & Profile

| Event | Producer | Consumers |
|-------|----------|-----------|
| `identity.user.created.v1` | `identity-service` | `customer-service`, `driver-service`, `courier-service`, `restaurant-service`, `audit-service`, `reporting-service` |
| `identity.user.suspended.v1` | `identity-service` | every service that owns a profile, `notification-service` |
| `identity.user.disabled.v1` | `identity-service` | every service that owns a profile, `admin-service` (support module) |
| `identity.session.revoked.v1` | `identity-service` | `audit-service`, `notification-service` |
| `customer.created.v1` | `customer-service` | `audit-service`, `reporting-service` |
| `customer.suspended.v1` | `customer-service` | `trip-service`, `food-order-service`, `payment-service` |
| `customer.segment.changed.v1` | `customer-service` | `pricing-service` (promotion / loyalty pricing), `notification-service` |
| `driver.created.v1` | `driver-service` | `audit-service`, `reporting-service` |
| `driver.approved.v1` | `driver-service` | `driver-service` (internal: availability, dispatch) |
| `driver.suspended.v1` | `driver-service` | `driver-service` (availability, dispatch), `trip-service` |
| `driver.document.expired.v1` | `driver-service` | `driver-service` (availability, dispatch), `fraud-risk-service` |
| `courier.created.v1` | `courier-service` | `audit-service`, `reporting-service` |
| `courier.approved.v1` | `courier-service` | `courier-service` (dispatch, tracking) |
| `courier.suspended.v1` | `courier-service` | `courier-service` (dispatch, delivery) |
| `vehicle.registered.v1` / `vehicle.approved.v1` / `vehicle.insurance.expired.v1` | `driver-service` (vehicle sub-aggregate) | `driver-service` (availability), `courier-service` |
| `address.created.v1` / `address.updated.v1` | `customer-service` (address sub-aggregate) | `customer-service` (cache invalidation), `food-order-service` (cart) |

### Geospatial & Zones

| Event | Producer | Consumers |
|-------|----------|-----------|
| `zone.updated.v1` | `geolocation-service` (zones sub-aggregate) | `pricing-service`, `driver-service` (dispatch), `courier-service` (dispatch) |
| `zone.surge.updated.v1` | `geolocation-service` (zones sub-aggregate) | `pricing-service`, `driver-service` (dispatch) |

### Platform

| Event | Producer | Consumers |
|-------|----------|-----------|
| `configuration.updated.v1` | `configuration-service` | every service (cache invalidation) |
| `feature_flag.updated.v1` | `configuration-service` (flags sub-aggregate) | every service |
| `lookup.updated.v1` | `configuration-service` (lookup administration) | every service (`shared/LOOKUPS.md` namespace) |
| `notification.sent.v1` / `notification.failed.v1` | `notification-service` | `admin-service` (support module), `audit-service` |
| `notification.published.v1` | `notification-service` (template snapshot bind per `shared/DEAL_FEATURE.md`-style audit chain) | `audit-service`, `reporting-service` |
| `comms.sms.sent.v1` / `comms.email.sent.v1` / `comms.push.sent.v1` | `notification-service` (preserved provider ACL) | `notification-service` (state), `audit-service` |
| `file.uploaded.v1` / `file.scanned.v1` | `file-service` | `customer-service`, `driver-service`, `courier-service`, `restaurant-service` |
| `admin.action.performed.v1` | `admin-service` | `audit-service` |
| `admin.super_admin.granted.v1` / `admin.super_admin.revoked.v1` | `admin-service` | `audit-service`, `notification-service` (pages security on-call) |
| `fraud.risk.scored.v1` | `fraud-risk-service` | `identity-service`, `payment-service`, `driver-service` (dispatch) |
| `fraud.account.blocked.v1` | `fraud-risk-service` | `identity-service` |
| `support.ticket.opened.v1` / `support.ticket.resolved.v1` | `admin-service` (support module) | `notification-service`, `audit-service` |

### Ride-Hailing

| Event | Producer | Consumers |
|-------|----------|-----------|
| `ride.request.created.v1` | `trip-service` (ride-request sub-aggregate) | `driver-service` (dispatch), `pricing-service`, `audit-service` |
| `ride.request.matched.v1` | `trip-service` (on dispatch match) | `trip-service` (trip state machine), `notification-service`, `audit-service` |
| `ride.request.cancelled.v1` | `trip-service` | `notification-service`, `audit-service`, `pricing-service` |
| `ride.request.expired.v1` | `trip-service` | `driver-service` (dispatch), `audit-service` |
| `driver.availability.online.v1` / `driver.availability.offline.v1` / `driver.availability.busy.v1` | `driver-service` (availability sub-aggregate) | `driver-service` (dispatch, location) |
| `driver.location.updated.v1` | `driver-service` (location sub-aggregate, partitioned by day) | `driver-service` (dispatch), `trip-service` (safety), `geolocation-service` (ETA/routing curated) |
| `dispatch.matched.v1` / `dispatch.no_driver.v1` / `dispatch.offer.expired.v1` | `driver-service` (dispatch sub-aggregate) | `trip-service`, `notification-service`, `audit-service` |
| `trip.started.v1` / `trip.arrived.v1` / `trip.completed.v1` / `trip.cancelled.v1` | `trip-service` | `payment-service` (ride saga), `payment-service` (driver earnings), `driver-service` (incentives), `customer-service` (loyalty account), review projections (`trip-service` + `food-order-service` + `search-service`), `trip-service` (history), `notification-service`, `audit-service` |
| `ride.payment.completed.v1` / `ride.payment.failed.v1` | `payment-service` (ride saga) | `payment-service` (driver earnings), `trip-service` (history), `audit-service`, `customer-service`, `admin-service` (support module) |
| `driver.earning.accrued.v1` | `payment-service` (driver earnings) | `trip-service` (history), `reporting-service`, `audit-service` |
| `driver.incentive.earned.v1` | `driver-service` (incentives) | `payment-service` (driver earnings) |
| `driver.withdrawal.requested.v1` / `driver.withdrawal.completed.v1` | `payment-service` (driver earnings) | `payment-service`, `audit-service` |
| `scheduled_ride.due.v1` | `trip-service` (scheduled sub-aggregate) | `trip-service` (ride-request) |
| `ride.safety.sos.v1` / `ride.safety.incident.v1` | `trip-service` (safety sub-aggregate) | `notification-service`, `admin-service` (support module), `audit-service` |
| `trip.reward.granted.v1` / `trip.reward.reversed.v1` | `trip-service` | `payment-service` (driver earnings + wallet), `ledger-service` (info), `notification-service`, `audit-service` |
| `trip.review.submitted.v1` / `trip.review.aggregated.v1` | `trip-service` (trip-review projection) | `driver-service` (rating), `courier-service` (rating), `reporting-service` |
| `driver.rating.aggregated.v1` | `trip-service` (review projections) | `driver-service` |

### Food Marketplace

| Event | Producer | Consumers |
|-------|----------|-----------|
| `merchant.created.v1` / `merchant.approved.v1` / `merchant.suspended.v1` | `restaurant-service` (merchant sub-aggregate) | `restaurant-service`, `payment-service` (merchant settlement), `audit-service` |
| `restaurant.created.v1` / `restaurant.approved.v1` / `restaurant.online.v1` / `restaurant.offline.v1` | `restaurant-service` | `search-service`, `audit-service`, `food-order-service` (cart) |
| `branch.created.v1` / `branch.updated.v1` / `branch.hours.changed.v1` / `branch.busy.v1` | `restaurant-service` (branch sub-aggregate) | `food-order-service` (cart), `courier-service` (dispatch), `search-service` |
| `menu.created.v1` / `menu.updated.v1` / `menu.item.price.changed.v1` / `menu.item.unavailable.v1` | `restaurant-service` (menu sub-aggregate) | `food-order-service` (cart), `search-service` |
| `inventory.item.out_of_stock.v1` / `inventory.item.restocked.v1` | `restaurant-service` (inventory sub-aggregate) | `restaurant-service` (menu), `food-order-service` (cart), `search-service` |
| `cart.created.v1` / `cart.updated.v1` / `cart.checked_out.v1` / `cart.abandoned.v1` | `food-order-service` (cart sub-aggregate) | `reporting-service` (data lake), `customer-service` (history) |
| `checkout.completed.v1` / `checkout.failed.v1` | `food-order-service` (checkout sub-aggregate) | `food-order-service` (cart clear / re-enable), `payment-service`, `audit-service` |
| `food.order.placed.v1` / `food.order.accepted.v1` / `food.order.rejected.v1` / `food.order.cancelled.v1` | `food-order-service` | `courier-service` (dispatch), `notification-service`, `payment-service` (food saga), `reporting-service`, `audit-service`, `customer-service` (history) |
| `food.order.preparing.v1` / `food.order.ready.v1` | `food-order-service` (queue sub-aggregate) | `courier-service` (dispatch), `notification-service` |
| `food.review.submitted.v1` / `food.review.aggregated.v1` | `food-order-service` (food-review projection) | `restaurant-service` (rating), `courier-service` (rating), `reporting-service` |

### Food Delivery & Couriers

| Event | Producer | Consumers |
|-------|----------|-----------|
| `delivery.courier.assigned.v1` / `delivery.dispatch.no_courier.v1` | `courier-service` (dispatch sub-aggregate) | `courier-service` (delivery), `food-order-service`, `notification-service` |
| `courier.location.updated.v1` | `courier-service` (location sub-aggregate, partitioned by day) | `courier-service` (dispatch), `courier-service` (delivery) |
| `delivery.pickup.v1` / `delivery.in_transit.v1` / `delivery.completed.v1` / `delivery.failed.v1` | `courier-service` (delivery sub-aggregate) | `notification-service`, `customer-service` (history), `payment-service` (food saga + courier earnings), `courier-service` (earnings accrual), review projections (`trip-service` + `food-order-service` + `search-service`), `audit-service` |
| `courier.earning.accrued.v1` / `courier.withdrawal.*.v1` | `payment-service` (courier earnings) | `reporting-service`, `audit-service`, `courier-service` (UI) |

### Requests (polymorphic parent events — per [ADR-0020](adrs/0020-polymorphic-request-id.md))

| Event | Owner | Producer | Consumers | Schema | Version | Partition / Routing Key | Ordering Requirement | Retention | Idempotency |
|-------|-------|----------|-----------|--------|---------|----------------------|---------------------|----------|-------------|
| `request.created.v1` | owning service (`trip-service` / `food-order-service` / `courier-service`) | owning service | `payment-service`, `ledger-service`, `notification-service`, `audit-service`, `reporting-service` | `{request_id, service, workflow_process_id, status, previous_status, correlation_id, occurred_at, actor_id, actor_type}` | v1 | `request_id` | Per `request_id`: strict order; across `request_id`s: no order | 90 days | Receivers use `request:{request_id}:{event}:{correlation_id}` idempotency keys |
| `request.matched.v1` | owning service | owning service | `payment-service`, `ledger-service`, `notification-service`, `audit-service`, `reporting-service` | same schema | v1 | `request_id` | Per `request_id`: strict order; across `request_id`s: no order | 90 days | same |
| `request.in_progress.v1` | owning service | owning service | `payment-service`, `ledger-service`, `notification-service`, `audit-service`, `reporting-service` | same schema | v1 | `request_id` | Per `request_id`: strict order; across `request_id`s: no order | 90 days | same |
| `request.completed.v1` | owning service | owning service | `payment-service`, `ledger-service`, `notification-service`, `audit-service`, `reporting-service` | same schema | v1 | `request_id` | Per `request_id`: strict order; across `request_id`s: no order | 90 days | same |
| `request.cancelled.v1` | owning service | owning service | `payment-service`, `ledger-service`, `notification-service`, `audit-service`, `reporting-service` | same schema | v1 | `request_id` | Per `request_id`: strict order; across `request_id`s: no order | 90 days | same |
| `request.failed.v1` | owning service | owning service | `payment-service`, `ledger-service`, `notification-service`, `audit-service`, `reporting-service` | same schema | v1 | `request_id` | Per `request_id`: strict order; across `request_id`s: no order | 90 days | same |
| `request.compensated.v1` | owning service | owning service | `payment-service`, `ledger-service`, `notification-service`, `audit-service`, `reporting-service` | same schema | v1 | `request_id` | Per `request_id`: strict order; across `request_id`s: no order | 90 days | same |

### Financial

| Event | Producer | Consumers |
|-------|----------|-----------|
| `payment.attempted.v1` | `payment-service` | `fraud-risk-service`, `audit-service` |
| `payment.authorized.v1` | `payment-service` | `payment-service` (ride saga + food saga + wallet) |
| `payment.captured.v1` | `payment-service` | `payment-service` (ride saga + food saga + wallet), `ledger-service`, `audit-service` |
| `payment.failed.v1` | `payment-service` | `payment-service` (ride saga + food saga), `notification-service` |
| `payment.refund.initiated.v1` / `payment.refund.completed.v1` | `payment-service` | `payment-service` (wallet), `ledger-service`, `audit-service` |
| `wallet.credited.v1` / `wallet.debited.v1` / `wallet.held.v1` / `wallet.released.v1` | `payment-service` (wallet sub-aggregate) | `ledger-service`, `customer-service`, `audit-service` |
| `ledger.posted.v1` | `ledger-service` | `reporting-service`, `audit-service` |
| `food.payment.completed.v1` / `food.payment.failed.v1` | `payment-service` (food saga) | `customer-service` (history), `payment-service` (merchant settlement + courier earnings), `admin-service` (support module), `notification-service`, `audit-service` |
| `merchant.settlement.accrued.v1` / `merchant.payout.scheduled.v1` / `merchant.payout.completed.v1` | `payment-service` (merchant settlement sub-aggregate) | `restaurant-service` (UI), `payment-service`, `audit-service` |
| `reconciliation.drift.found.v1` | `reporting-service` (reconciliation jobs) | `admin-service` (support module), `audit-service` |

### Pricing & Rules

| Event | Producer | Consumers |
|-------|----------|-----------|
| `pricing.quote.created.v1` | `pricing-service` | `reporting-service` (data lake) |
| `pricing.rating_density.applied.v1` | `pricing-service` | `reporting-service`, `audit-service` |
| `pricing.loyalty_discount.applied.v1` | `pricing-service` | `reporting-service`, `audit-service` |
| `pricing.geo_config.updated.v1` | `pricing-service` | `pricing-service` (cache), `reporting-service`, `audit-service` |
| `promotion.created.v1` / `promotion.disabled.v1` / `promotion.redeemed.v1` | `pricing-service` (promotion sub-aggregate) | `food-order-service` (cart), `reporting-service`, `audit-service` |
| `loyalty.points.earned.v1` / `loyalty.points.burned.v1` / `loyalty.tier.changed.v1` / `loyalty.frequent_zone.aggregated.v1` | `pricing-service` (loyalty pricing) + `customer-service` (loyalty account) | `customer-service` (UI), `pricing-service` (helper), `reporting-service`, `audit-service` |
| `tax.calculated.v1` | `pricing-service` (tax sub-aggregate) | `reporting-service`, `audit-service` |

## Schema Evolution

- **Within a major version**: producers may add optional fields.
  Consumers MUST ignore unknown fields. Use JSON Schema validation at
  ingress to enforce this contract.
- **Across major versions**: producers MAY publish both `*.v1` and
  `*.v2` events simultaneously for a deprecation window (≥ 6
  months). Consumers migrate, then deprecate `*.v1`. A consumer can
  opt to consume both and dispatch internally.
- **Removing a field**: requires a major version bump.
- **Renaming a field**: requires a major version bump.
- **Changing a field's type**: requires a major version bump.
- **Changing a partition key**: requires a new topic.

## Outbox Pattern (Producer Side)

Every service that emits events uses the **outbox pattern**:

1. Inside the same DB transaction that mutates state, also write a
   row to the `outbox` table (`event_id`, `topic`, `payload`,
   `headers`, `created_at`, `claimed_at`).
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

1. Insert `event_id` (no-op on duplicate → treat as already
   processed).
2. Process the event.
3. Update `processed_at`.

This is the consumer's idempotency key. The handler body MUST be
safe to re-run (idempotent) for full correctness in case the
consumer crashes between step 2 and 3.

## Dead-Letter Topics

Every topic has a paired `<topic>.dlq`. Consumers route a message
to the DLQ when:

- It cannot be deserialized (poison message).
- A handler raises an unhandled exception after N retries with
  exponential backoff.
- A business rule fails validation consistently.

DLQ messages are inspected via tooling (`replay-cli` or
`admin-service` → DLQ inspector UI). DLQ retention: 30 days.

## Replay

A topic's history is replayable by resetting consumer offsets (or
by a dedicated replay consumer that writes to a new topic). The
`reporting-service` (data lake) and `reporting-service` use this on
a fresh schema migration.

## Anti-Patterns Explicitly Avoided

- Event chains of more than 3 hops without a corresponding saga or
  observable flow.
- "Events" that are just internal method calls serialised — those
  are commands, use REST.
- Broadcasting an entire aggregate as the event payload — emit
  deltas + a stable `aggregate_id`, let consumers fetch the latest.
- Coupling consumers to the producer's internal class names —
  event payload schemas are owned by the event, not the service.


## Request events vs. domain events

The `request.*.v1` events are the **parent events** and the domain events (e.g., `trip.started.v1`, `food.order.placed.v1`, `delivery.courier.assigned.v1`) are the **children**. Consumers that only need request-level state subscribe to `request.*.v1`; consumers that need concrete-aggregate detail subscribe to the domain events.

Specifically:

- A `trip.started.v1` is emitted *after* `request.in_progress.v1` and carries the same `request_id` in its envelope's `aggregate_id` field.
- A `food.order.placed.v1` is emitted *after* `request.created.v1`.
- A `delivery.completed.v1` is emitted *after* `request.completed.v1`.

The `service` field in the request event payload (values: `trip`, `food_order`, `courier_delivery`) tells consumers which concrete aggregate to fetch if they need detail. This polymorphic pattern avoids the static-branch consumer problem: instead of writing `if service == 'trip' then fetch trip else if service == 'food_order' then fetch order`, consumers look up the owning service's REST API using `request_id`.

## Conductor Workflow Events vs Kafka Events

Per [ADR-0018](adrs/0018-workflow-engine-conductor.md), Conductor
orchestrates **17 workflows across 5 flow families** (Phase 7
rewards, Phase 7.5 Make-a-Deal, refund orchestration,
driver/courier onboarding, service-request) across 15 participating
services. Conductor's own event model is **separate** from the
platform's Kafka event catalog:

| Conductor surface | Kafka surface |
|---|---|
| Conductor task state (started / completed / failed / compensated) | Platform domain events (`trip.reward.granted.v1`, `payment.refund.completed.v1`, etc.) |
| Conductor workflow history export | Standard Kafka events (with versioning per "Schema Evolution") |
| Conductor `conductor-kafka-bridge` translation | Kafka signal source for Conductor |

The rule is: **Conductor orchestrates; the event catalog
publishes.** Workers in participating services publish completion
events through their existing **transactional outbox** (per
[ADR-0009](adrs/0009-transactional-outbox.md)), not via Conductor.
Conductor's `conductor.workflow.history.v1` Kafka topic is a mirror
for observability, not the authoritative event catalog.

Workflow versioning: Conductor's `version` field pins in-flight
runs to the version they started with (per
[`shared/CONDUCTOR_WORKFLOWS.md` 2](../shared/CONDUCTOR_WORKFLOWS.md)),
mirroring the event-version policy in "Schema Evolution".

DSL drift protection: every Conductor workflow's referenced Kafka
topics and event names must exist in this catalog; weekly CI
enforces the invariant.
