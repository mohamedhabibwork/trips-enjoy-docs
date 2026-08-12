# ADR-0005: Apache Kafka as the Event Broker

- Status: Accepted
- Date: 2026-07-29
- Authors: Platform Architecture Team
- Deciders: Architecture Review Board
- Tags: events, kafka, broker, async, streaming

## Context and Problem Statement

The platform uses events for everything that does not need a
synchronous read-your-writes answer: state propagation across
services, integration events for downstream consumers, audit events,
analytics ingestion, location streams, and the notification fan-out.
The event catalog has hundreds of event types across 8 domains
(identity, geospatial, pricing, ride-hailing, food, delivery,
financial, platform) — see
[`EVENT_ARCHITECTURE.md`](../EVENT_ARCHITECTURE.md) and the per-domain
tables. The broker must handle per-aggregate ordering (every event
for one Trip must arrive in order at every consumer), high
throughput (10k+ writes/s on `driver.location.updated.v1` at peak),
replay (analytics rebuilds from the beginning of a topic), and
retention (7 years for financial topics). It must also be the
transport for the outbox pattern (ADR-0009), so a state change and
its event are committed atomically.

We need an event broker that supports partitioning, durable retention,
exactly the right delivery semantics for the outbox+inbox pattern
(at-least-once with idempotent consumers), and operational maturity
that lets us run it across multiple regions.

## Decision Drivers

- Per-aggregate ordering: every consumer must see the events for one
  `request_id` (or `payment_id` for non-request-scoped payments) in the order they
  were produced. After ADR-0020, polymorphic `request_id` is the canonical partition key for
  ride / food / delivery flows.
- Throughput: 10k+ writes/s sustained on location topics, peak bursts
  on `ride.request.created.v1` and `food.order.placed.v1`.
- Replay: ``reporting-service` (data lake)` and `reporting-service` must be able
  to rebuild from a topic's history.
- Retention: financial topics ≥ 7 years, audit ≥ 7 years, location
  ≥ 30 days, notification deliveries ≥ 90 days.
- Partitioning that survives broker failures (replication factor ≥ 3
  in production).
- Mature operational story: multi-broker cluster, ISR, controller
  failover, rolling upgrades without downtime.
- Open ecosystem: Debezium for CDC-based outbox, Schema Registry for
  schema evolution, Kafka Connect for sinks.
- Multi-region: per-region clusters with cross-region replication
  where required (identity, configuration).

## Considered Options

- **Apache Kafka** — partitioned, durable, replayable log; the
  default for high-throughput event streaming.
- **RabbitMQ** — mature AMQP broker with strong routing features.
- **NATS / NATS JetStream** — lightweight, cloud-native, with
  JetStream for persistence.
- **Apache Pulsar** — partitioned, durable, with tiered storage.
- **AWS Kinesis / Google Pub/Sub** — managed streaming on a single
  cloud.

## Decision Outcome

Chosen option: "**Apache Kafka**", because (a) it is the only broker
that gives us per-aggregate ordering (partition key = `aggregate_id`)
with high throughput (millions of messages/s per cluster), (b) its
log-based retention is exactly the right shape for the outbox
pattern (replay from the beginning of a topic), for analytics
rebuild, and for the 7-year retention on financial topics, (c) the
operational maturity (multi-broker ISR, rolling upgrades, MirrorMaker
for cross-region replication) is what we need for Tier-1
availability, and (d) the ecosystem (Debezium, Schema Registry, Kafka
Connect) is mature and aligns with the rest of our platform
(Postgres outbox, JSON Schema for events, Kafka-based sinks for
analytics).

### Consequences

- Good: Per-aggregate ordering via partition key. Every consumer
  sees the events for one `trip_id` in the order they were produced.
- Good: High throughput. 10k+ writes/s on a single topic is normal;
  we have headroom to 100k+ writes/s with more partitions.
- Good: Durable, replayable retention. The 7-year financial
  retention is a tiered-storage configuration, not a separate
  pipeline.
- Good: Outbox pattern fits cleanly. The poller (or Debezium) reads
  the outbox table and publishes to Kafka; at-least-once delivery
  with the consumer's inbox is the standard pattern.
- Good: Replay. ``reporting-service` (data lake)` and `reporting-service` reset
  consumer offsets to rebuild projections.
- Good: Operational maturity. ISR, replication factor ≥ 3, rolling
  upgrades, MirrorMaker for cross-region.
- Good: Schema evolution via the JSON Schema registry. We add
  optional fields freely; major version bumps ride alongside on
  the same topic for ≥ 6 months.
- Bad: Operational cost. A Kafka cluster per region is non-trivial
  (≥ 3 brokers, ZooKeeper or KRaft, monitoring, partitioning
  strategy). (Mitigation: a dedicated platform team that owns it;
  per-region runbooks; quarterly DR drills.)
- Bad: Partition rebalancing causes consumer lag spikes. We mitigate
  with cooperative-sticky assignors and by sizing partitions for
  peak throughput with headroom.
- Bad: Topic proliferation. With hundreds of event types, we have
  hundreds of topics; we mitigate with naming conventions
  (`<domain>.<entity>.<event>`), topic-as-code (Terraform or
  equivalent), and a topic catalog.
- Neutral: At-least-once delivery. Consumers MUST be idempotent (the
  inbox pattern); this is a platform-wide rule, documented in
  `EVENT_ARCHITECTURE.md`.

### Confirmation

- Per-region Kafka cluster availability ≥ 99.95% (Tier-1 SLO).
- Consumer lag SLO: 99% of consumers within 5 seconds of the head of
  the topic; alert on sustained lag > 30 seconds.
- Outbox lag: the producer-side outbox lag (time from state change
  to event published) P99 < 1 second.
- Replay success: `reporting-service` rebuilds a projection from
  a topic's history in < 4 hours.
- 7-year retention is verified quarterly via a retention drill
  (read a 6-year-old event from `ledger.posted.v1` and validate
  its payload).

## Pros and Cons of the Options

### Apache Kafka

The chosen option. Partitioned, durable, replayable log; the
de-facto standard for high-throughput event streaming.

- Good: Per-aggregate ordering via partition key.
- Good: High throughput; headroom for peak bursts.
- Good: Durable, replayable retention (days to years).
- Good: Mature operational story (ISR, replication, rolling
  upgrades, MirrorMaker).
- Good: Ecosystem (Debezium, Schema Registry, Kafka Connect).
- Good: Multi-language client libraries.
- Bad: Operational cost (cluster per region; broker count; ZooKeeper
  or KRaft).
- Bad: Partition rebalancing can cause consumer lag spikes.
- Bad: Topic proliferation; needs a topic-as-code discipline.

### RabbitMQ

Mature AMQP broker with strong routing features (exchanges, queues,
bindings).

- Good: Mature; well-understood by many teams.
- Good: Strong routing primitives (topic, fanout, headers).
- Good: Per-queue ordering, but no per-key ordering across queues.
- Bad: Throughput is lower than Kafka for our high-volume topics
  (location, audit).
- Bad: No replay. Once a message is consumed, it's gone (unless we
  re-publish, which is fragile).
- Bad: Retention is short by default; long retention requires
  per-queue configuration and is not the broker's strength.
- Bad: Per-key ordering requires a queue per key, which is operationally
  expensive at our scale.

### NATS / NATS JetStream

Lightweight, cloud-native, with JetStream for persistence and
streaming.

- Good: Very low latency; minimal operational footprint.
- Good: JetStream adds persistence, replay, and per-subject
  ordering.
- Good: Simple to operate; small binary.
- Bad: Smaller ecosystem than Kafka; fewer off-the-shelf
  connectors.
- Bad: At our scale, NATS is less battle-tested than Kafka for
  sustained 10k+ writes/s per topic with 7-year retention.
- Bad: Operational tooling (monitoring, partition rebalancing,
  cross-region replication) is less mature.

### Apache Pulsar

Partitioned, durable, with tiered storage; designed to address some
of Kafka's operational pain.

- Good: Tiered storage (offload old segments to S3) — same shape as
  Kafka's tiered storage, but earlier to market.
- Good: Separated compute and storage brokers — easier to scale
  independently.
- Bad: Smaller community than Kafka; fewer off-the-shelf
  integrations.
- Bad: Operational tooling less mature.
- Bad: We have no in-house Pulsar expertise; the team is built
  around Kafka.

### AWS Kinesis / Google Pub/Sub

Managed streaming on a single cloud.

- Good: Fully managed; no cluster to run.
- Good: Pay-per-use pricing; auto-scaling.
- Bad: Per-shard ordering only; no per-aggregate ordering across
  shards without a per-aggregate shard, which is operationally
  expensive.
- Bad: Vendor lock-in to a single cloud; we deploy in EU and KSA
  regions and want a uniform broker.
- Bad: 7-year retention is not a first-class configuration; the
  managed offerings are tuned for shorter horizons.
- Bad: Replay is bounded (Kinesis: 365 days max; Pub/Sub: 7 days
  by default).

## References

- [`EVENT_ARCHITECTURE.md`](../EVENT_ARCHITECTURE.md) — broker choice,
  event envelope, partitioning, delivery semantics, schema evolution,
  outbox, inbox, DLQ, replay, anti-patterns.
- [`MICROSERVICES_MAP.md`](../MICROSERVICES_MAP.md) — every service's
  out events and async dependencies.
- [`CONSISTENCY_STRATEGY.md`](../CONSISTENCY_STRATEGY.md) — the
  outbox + inbox + idempotency-key model.
- ADR-0009 — outbox pattern in detail.
- ADR-0010 — saga pattern that consumes and produces events.
- Apache Kafka documentation — partitions, ISR, replication,
  retention, MirrorMaker, tiered storage.
- Debezium documentation — CDC-based outbox publication.
