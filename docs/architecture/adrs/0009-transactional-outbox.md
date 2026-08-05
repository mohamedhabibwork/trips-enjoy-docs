# ADR-0009: Outbox Pattern for Event Publication

- Status: Accepted
- Date: 2026-07-29
- Authors: Platform Architecture Team
- Deciders: Architecture Review Board
- Tags: events, outbox, consistency, kafka, cdc

## Context and Problem Statement

The platform is event-driven: a state change in one service is
communicated to other services via events on Kafka (ADR-0005). The
invariant we must hold is: a state change and its event are
committed atomically. If we publish to Kafka and then commit the
state, a publish success + commit failure leaves a phantom event
with no state behind it; if we commit the state and then publish, a
commit success + publish failure leaves a state change with no
event, and downstream consumers never learn about it. Both are
unacceptable for our use cases (a `payment.captured.v1` without a
captured payment; a `trip.completed.v1` without a completed trip).

The decision is how to make the state change and the event
publication atomic: a shared transactional database (rejected — we
have database-per-service), a distributed transaction (rejected —
we do not do 2PC between services), a change-data-capture pipeline
that reads the WAL, or an application-level outbox table written
in the same transaction as the state change and then published
asynchronously.

## Decision Drivers

- Atomicity: the state change and the event must be committed
  together or not at all.
- Per-service ownership: every service owns its outbox; no
  cross-service coupling.
- At-least-once delivery to Kafka; the consumer's inbox (with
  `event_id` deduplication) handles duplicates.
- Durability: outbox rows survive a service crash; a separate
  publisher reads them and retries until success.
- Bounded lag: the publisher is fast (sub-second P99 from state
  change to event on Kafka).
- Operational maturity: the pattern is well-understood, the
  tooling is mature, and the failure modes are documented.
- Per-service isolation: the outbox is in the service's own
  database; no shared infrastructure for publication.

## Considered Options

- **Application-level outbox table, written in the same DB
  transaction, with a separate publisher** — the chosen option.
- **Change-data-capture (Debezium) reading the WAL and publishing
  to Kafka** — a specialized outbox.
- **Direct publish to Kafka in the same transaction as the state
  change** — using Kafka's transactional producer and
  Postgres's XA.
- **Two-phase commit between Postgres and Kafka** — distributed
  transaction across two systems.
- **Listen-to-yourself (the consumer queries the producer's API
  to validate)** — eventual consistency with reconciliation.

## Decision Outcome

Chosen option: "**Application-level outbox table, written in the
same DB transaction as the state change, with a separate publisher
that reads the outbox and publishes to Kafka**", because (a) it
guarantees the atomicity invariant (state change and event row
committed together) using only the service's own Postgres
transaction — no XA, no cross-system coordinator, (b) the
publisher is a per-service component that we can scale and monitor
independently, (c) at-least-once delivery to Kafka is paired with
the consumer's inbox (with `event_id` deduplication) to give us
end-to-end exactly-once effect, and (d) the failure modes are
well-understood and well-tested: a crash between the state change
and the publish leaves an unpublished outbox row, which the
publisher retries indefinitely.

For high-volume services (``driver-service` (location)`,
``courier-service` (tracking)`), we additionally support a
**Debezium-based outbox** as a specialized form: Debezium reads
the outbox table via logical replication and publishes to Kafka,
removing the application-level publisher from the hot path. The
pattern is the same; the transport is different.

### Consequences

- Good: Atomicity invariant holds. The state change and the
  outbox row are in the same Postgres transaction. If the
  transaction commits, both are durable; if it rolls back,
  neither exists.
- Good: No cross-system coordinator. No XA, no 2PC, no Kafka
  transactional producer needed (the at-least-once + inbox
  pattern gives us exactly-once effect end-to-end).
- Good: Per-service ownership. Each service has its own outbox
  table; the publisher is part of the service's deployment; no
  shared infrastructure.
- Good: Bounded lag. The publisher polls (or Debezium tails the
  WAL) with sub-second P99 from state change to event on Kafka.
- Good: Failure modes are clear. Crash between commit and publish:
  the outbox row remains; the publisher retries. Kafka unavailable:
  the outbox row remains; the publisher retries with backoff. The
  outbox grows until the broker is back, then drains.
- Good: Operational observability. `outbox.lag.seconds`,
  `outbox.unpublished.count`, `outbox.dlq.count` per service.
- Bad: The outbox is an additional table to operate, index, and
  purge. (Mitigation: documented in `EVENT_ARCHITECTURE.md`; a
  maintenance job purges rows 24h after broker confirmation.)
- Bad: The publisher is a per-service component that must be
  deployed and monitored. (Mitigation: a shared outbox-publisher
  library; each service instantiates it with its own config.)
- Bad: A bug in the publisher (e.g. an infinite retry on a
  permanent failure) can grow the outbox unboundedly. (Mitigation:
  the outbox DLQ; the publisher routes to a per-service
  `<service>.outbox.dlq` topic after N retries, and the on-call
  is alerted.)
- Neutral: We accept at-least-once delivery to Kafka. Consumers
  must be idempotent (inbox pattern). This is the platform-wide
  rule.

### Confirmation

- 100% of services that emit events use the outbox pattern. A
  PR review checks for `INSERT INTO outbox (...)` in the same
  transaction as the state change; a CI lint flags direct
  `kafka.Producer.send()` in service code.
- Outbox lag P99 < 1 second (time from state change to event on
  Kafka).
- Outbox lag P99.9 < 5 seconds; alert on sustained lag > 30
  seconds.
- Outbox DLQ: zero messages in steady state; alert on the first
  message.
- Recovery: kill the publisher mid-commit; on restart, the
  outbox drains with no event loss; verified by a chaos test
  in staging.

## Pros and Cons of the Options

### Application-level outbox table, written in the same DB transaction

The chosen option. The outbox table is in the service's own
Postgres schema. The state-change transaction also writes an
outbox row (`event_id`, `topic`, `payload`, `headers`,
`created_at`, `claimed_at`). A separate publisher reads the
outbox, publishes to Kafka, and marks the row as published.

- Good: Atomicity via a single Postgres transaction. No XA, no
  cross-system coordinator.
- Good: Per-service ownership; the outbox is the service's
  table.
- Good: At-least-once to Kafka + consumer inbox = exactly-once
  effect.
- Good: Bounded lag; clear failure modes.
- Good: Operational observability (`outbox.lag.seconds`,
  `outbox.unpublished.count`).
- Bad: Additional table to operate, index, and purge.
- Bad: Per-service publisher to deploy and monitor.
- Bad: A buggy publisher can grow the outbox unboundedly
  (mitigated by the outbox DLQ).

### Change-data-capture (Debezium) reading the WAL

A specialized form of the outbox: Debezium tails the Postgres WAL
(or reads the outbox table via logical replication) and publishes
to Kafka. The application still writes to the outbox table; the
transport is Debezium instead of an application-level publisher.

- Good: Same atomicity guarantee (outbox table in the same
  transaction).
- Good: Removes the application-level publisher from the hot
  path; lower latency.
- Good: Mature (Debezium is battle-tested for this).
- Bad: An additional piece of infrastructure (Debezium
  Connect) per service or per cluster.
- Bad: Operational complexity (Debezium connectors, schema
  registry, connector restarts).
- Bad: We use this for high-volume services only
  (``driver-service` (location)`, ``courier-service` (tracking)`);
  for most services, the application-level publisher is
  simpler.

### Direct publish to Kafka in the same transaction

Write to Postgres, then publish to Kafka with a transactional
producer, then commit.

- Good: Simpler than an outbox table (no extra table, no
  publisher).
- Bad: Kafka's transactional producer requires a transaction
  coordinator and is not the same as Postgres's transaction;
  there is no atomic commit across the two systems.
- Bad: A publish success + commit failure leaves a phantom
  event. This is the failure mode we are trying to avoid.
- Bad: Kafka's transactional producer adds significant latency
  and reduces throughput.

### Two-phase commit between Postgres and Kafka

XA across the two systems.

- Good: True atomicity across the two systems.
- Bad: XA is fragile; coordinator-coupled; a single point of
  failure.
- Bad: Kafka does not natively support XA.
- Bad: We explicitly do not use 2PC between services (see
  [`CONSISTENCY_STRATEGY.md`](../CONSISTENCY_STRATEGY.md)).

### Listen-to-yourself (the consumer queries the producer's API to validate)

The consumer reads from Kafka; if the event references a state
the consumer cannot find via the producer's API, the consumer
retries or opens a ticket.

- Good: No outbox table; no publisher.
- Bad: The state change is committed before the event is
  published; a publish failure leaves a state change with no
  event, and downstream consumers never learn about it.
- Bad: The "is the state really there?" check is itself a
  distributed-systems problem (the API may be eventually
  consistent too).
- Bad: We are back to the "publish-then-commit vs. commit-then-
  publish" dilemma; neither is atomic.

## References

- [`EVENT_ARCHITECTURE.md`](../EVENT_ARCHITECTURE.md) — outbox
  pattern in detail; inbox pattern on the consumer side; DLQ
  pattern; replay; schema evolution.
- [`CONSISTENCY_STRATEGY.md`](../CONSISTENCY_STRATEGY.md) —
  the strong-consistency cases (trip completion + payment +
  driver earning) that motivate the outbox.
- [`FAILURE_HANDLING.md`](../FAILURE_HANDLING.md) — the
  failure modes (transient, permanent, capacity, poison,
  cascading) and their handlers.
- ADR-0005 — Kafka as the event broker.
- ADR-0010 — saga pattern, which uses the outbox for its
  forward and compensation steps.
- Debezium documentation — outbox connector, logical
  replication, schema evolution.
- Chris Richardson, *Microservices Patterns* — outbox pattern,
  event-driven architecture.
