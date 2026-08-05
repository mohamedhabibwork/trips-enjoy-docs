# Consistency Strategy

Distributed systems must choose where they need strong consistency and
where eventual consistency is acceptable. This document is the
platform's answer to that question, by case.

## The Rule of Thumb

- **Inside a single service**: strong consistency via PostgreSQL ACID
  transactions. Anything written in one transaction is durable and
  immediately visible to subsequent reads.
- **Across services**: eventual consistency via events, with explicit
  compensation and reconciliation.

We do NOT use two-phase commit between services. We do NOT share a
transactional database between services. We do NOT use cross-service
foreign keys.

## Where Strong Consistency Is Required

These invariants must hold across the system. They are enforced by
combinations of APIs, sagas, and the ledger.

| Invariant | Enforcement |
|-----------|-------------|
| Money is conserved (no creation, no destruction outside documented flows) | Double-entry ledger; every money-movement event is matched by a `ledger.posted.v1` |
| A payment is captured exactly once | Idempotency key on `payment.capture`; outbox in `payment-service`; inbox + dedup in consumer |
| A wallet's balance equals the sum of its postings | Reconciliation job in `reporting-service` |
| A driver is matched to one ride at a time | Driver state machine (`busy` / `available`); conflict resolved at dispatch time by row-level lock or advisory lock in `dispatch-service` |
| A food order has at most one courier assigned at a time | `courier-dispatch-service` row-level lock on the delivery aggregate; idempotency key on the assignment |
| A promotion is redeemed at most once per cart | Idempotency key in `promotion-service`; reconciliation job in `reporting-service` |
| A customer cannot have two active sessions of the same type | Keycloak session management; gateway checks |

## Where Eventual Consistency Is Acceptable

These are eventually consistent because the cost of stronger
consistency is too high (multi-region, throughput, or coordination
overhead):

| Invariant | Lag | Why eventual is fine |
|-----------|-----|---------------------|
| Driver rating reflects all completed trips | Hours | Aggregate; small lag is fine |
| Restaurant search index reflects menu changes | Seconds to minutes | Acceptable to users |
| `analytics-service` reflects business events | Minutes | OLAP; not on the hot path |
| Customer's trip history shows recently completed trips | Seconds to minutes | User just saw the trip complete in the app; the history view can lag |
| Loyalty points balance after a trip | Seconds | Customer sees it "soon" |
| Configuration change reaches all services | Seconds | Documented; long-poll + event |
| Notification is delivered | Seconds to minutes | Always async; outbox + retry |
| Driver location visible to dispatch | Sub-second | Geo-hashing + recent trail; consumer can be a few seconds behind |

## How We Get Eventual Consistency Safely

1. **Outbox** in the producer (atomic state change + event).
2. **Inbox + dedup** in the consumer (at-least-once is safe).
3. **Idempotency keys** on every non-idempotent operation.
4. **Reconciliation jobs** in `reporting-service` (or per-service)
   detect drift and repair.
5. **Observability** on lag (Kafka consumer lag, outbox lag,
   inbox size) — alert on anomalies.

## Case: Trip Completion + Payment + Driver Earning (Strong)

Sequence (orchestrated saga in `ride-payment-integration-service`):

```mermaid
sequenceDiagram
    participant TR as trip-service
    participant OR as ride-payment-integration
    participant PAY as payment-service
    participant DE as driver-earnings-service
    participant LD as ledger-service

    TR->>OR: trip.completed.v1 (event)
    OR->>PAY: capture(Idempotency-Key=trip:T:cap)
    PAY-->>OR: payment.captured.v1
    OR->>DE: accrue(Idempotency-Key=trip:T:earn)
    DE-->>OR: driver.earning.accrued.v1
    OR->>LD: post(ride_payment_saga_completed)
    LD-->>OR: ledger.posted.v1
    OR-->>TR: ride.payment.completed.v1
```

Strong guarantees:

- The capture is idempotent (same key → same result).
- The accrual is idempotent.
- The ledger posting is idempotent (account + posting-id is unique).
- The saga state is keyed by `trip_id`; a re-run is a no-op.

If capture fails:

```mermaid
sequenceDiagram
    participant TR as trip-service
    participant OR as ride-payment-integration
    participant PAY as payment-service
    participant NOT as notification-service
    participant SUP as support-service

    TR->>OR: trip.completed.v1
    OR->>PAY: capture(Idempotency-Key=trip:T:cap)
    PAY-->>OR: payment.failed.v1
    OR->>NOT: notify customer (payment failed)
    OR->>SUP: open ticket (payment_failed)
    OR-->>TR: ride.payment.failed.v1
```

Reconciliation:

- If `trip.completed.v1` was received but no `payment.captured` after
  5 minutes, the reconciliation job in `reporting-service` opens a
  ticket and pages the on-call.

## Case: Driver Rating Reflects All Trips (Eventual)

Sequence:

```mermaid
sequenceDiagram
    participant TR as trip-service
    participant RR as review-rating-service
    participant DR as driver-service
    participant CR as customer-service

    TR->>RR: trip.completed.v1
    RR->>RR: aggregate (per driver, per window)
    Note over RR: 1h timer
    RR->>DR: driver.rating.aggregated.v1
    DR-->>CR: driver profile updated
```

The driver rating in the customer app may lag the actual trip by up
to an hour. This is fine; ratings are a moving average and small
stale data is acceptable.

## Case: Restaurant Search Index (Eventual, Bounded)

Sequence:

```mermaid
sequenceDiagram
    participant MN as menu-service
    participant SR as search-service
    participant CC as customer-service

    MN->>SR: menu.updated.v1
    SR->>SR: reindex (async)
    SR-->>CC: query results
```

Acceptable lag: ≤ 30 seconds. Search-service reindexes within
seconds; in rare backpressure cases, the lag is bounded by a
monitor and alerted.

## Case: Configuration Rollout (Eventual, Bounded)

Sequence:

```mermaid
sequenceDiagram
    participant ADM as admin-service
    participant CFG as configuration-service
    participant PRC as pricing-service
    participant NOT as notification-service

    ADM->>CFG: PUT pricing.base_fare
    CFG-->>ADM: 200 OK (v+1)
    CFG->>PRC: configuration.updated.v1
    CFG->>NOT: configuration.updated.v1
    Note over PRC: cache invalidated, reload
    Note over NOT: cache invalidated, reload
```

Acceptable lag: ≤ 5 seconds. Long-poll + event keeps the lag
bounded; consumer-side versioning guarantees a service never applies
a half-loaded config.

## The "No Foreign Keys Across Services" Rule

Cross-service references are stored as UUID columns **without**
database-level foreign keys. This is the single most important rule
for keeping services deployable independently.

Why:

- A drop or rename of a column in service A would otherwise require
  a coordinated change in service B's database — defeating
  independent deploys.
- A backfill in service A would otherwise require service B's
  database to be available.
- Backup/restore of one service is impossible without coordinating
  with the other.

How we maintain referential integrity instead:

- **At write time**: the consumer of the reference validates it via
  API before persisting.
- **At event time**: the producer of the reference emits events
  (`*.suspended.v1`, `*.deleted.v1`) so consumers can react.
- **At reconciliation time**: a job in `reporting-service` detects
  dangling references and opens tickets for repair.

## Anti-Patterns Explicitly Avoided

- Strong-consistency expectations across service boundaries.
- Foreign keys between schemas.
- "Synchronous" event handling that pretends to be eventual.
- Reconciliation jobs that mutate state silently — every fix is
  audited and notified.
- A single shared "transactional" table that all services write to.
- "Just retry forever" — bounded retries with circuit breakers.
