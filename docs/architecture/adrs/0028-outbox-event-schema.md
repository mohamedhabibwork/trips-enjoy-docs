# ADR-0028: OutboxEvent schema (canonical 11-column shape)

- Status: Accepted
- Date: 2026-08-15
- Authors: Platform Architecture Team
- Deciders: Architecture Review Board
- Tags: outbox, messaging, data-model, contracts

> **Catalog revision (2026-08-15, appended per append-not-renumber):**
> this ADR locks the platform-wide canonical schema for the
> `outbox` table. Every service's outbox MUST use the canonical
> 11-column shape and the canonical `OutboxPublisher` poll
> semantics. The 6 services that ship a local `OutboxEvent`
> entity adopt the platform entity in
> `platform-spring-boot-messaging`; their local tables are
> migrated forward via a per-service V__ Flyway migration.

## Context and Problem Statement

6 of 14 Kotlin services ship a local `OutboxEvent` entity with
one of six distinct column shapes (per the audit at
[`shared/PLATFORM_DRY_AUDIT.md` §3.2 K-08](../../shared/PLATFORM_DRY_AUDIT.md)):

- `id` PK strategy: `@Id val id: UUID` (3 services) vs
  `@GeneratedValue UUID?` (3 services)
- `payload`: `String` (4 services) vs `Map<String, Any?>` (2 services)
- Schema naming: `audit.outbox` (4 services) vs
  `payment.outbox_events` (1 service) vs
  `configuration.outbox` (1 service)
- DLQ topic naming: `<topic>.dlq` (5 services) vs
  `<topic>.DLQ.v1` (1 service — see [ADR-0024](0024-dlq-topic-naming.md))
- Retry: 3-attempts-then-DLQ (3 services) vs exponential backoff
  up to 5 min (1 service) vs no retry (2 services)
- Headers column: present (1 service) vs absent (5 services)

The contract is load-bearing: every published event is keyed on
the outbox row's `event_id`. If `audit-service` reads
`outbox.event_id` as a UUID but `payment-service` reads
`outbox_events.event_id` as a VARCHAR, an end-to-end trace that
joins audit events to published events fails silently.

## Decision Drivers

- **Single canonical column shape.** All 21 services must agree
  on the 11 canonical columns.
- **UUIDv7 PK.** Every row's primary key is UUIDv7 per
  [ADR-0015](0015-uuidv7-for-ids.md); the
  `event_id` is the dedup key on the consumer side.
- **Idempotent publish.** The `OutboxPublisher` polls with
  `FOR UPDATE SKIP LOCKED` semantics so multiple service
  replicas don't double-publish.
- **Exponential backoff.** Retries with exponential backoff up
  to 5 min; DLQ on 6th attempt.

## Considered Options

1. **11-column canonical schema** (platform default; matches
   what `platform-spring-boot-messaging` already declares)
2. **Per-service schema; no canonical** (6 services' current
   state)
3. **6-column minimal schema** (3 services' current state)
4. **15-column maximal schema** (1 service — payment-service —
   includes `created_by` and rich `headers` JSONB)

## Decision Outcome

**Chosen option: option 1, 11-column canonical schema.**

Canonical column set:

```sql
CREATE TABLE <schema>.outbox (
    id               UUID         PRIMARY KEY,          -- UUIDv7 row PK
    event_id         UUID         NOT NULL UNIQUE,      -- UUIDv7 event ID (consumer dedup key)
    topic            TEXT         NOT NULL,             -- Kafka topic, e.g. "trip.lifecycle.v1"
    partition_key    TEXT         NOT NULL,             -- Kafka partition key (often event_id::text)
    payload          JSONB        NOT NULL,             -- Serialized event payload
    headers          JSONB        NOT NULL DEFAULT '{}'::JSONB,  -- Event envelope headers
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    published_at     TIMESTAMPTZ,                       -- NULL while pending
    attempts         INT          NOT NULL DEFAULT 0,
    last_error       TEXT,                              -- NULL unless retry failed
    next_attempt_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT uq_outbox_event_id UNIQUE (event_id),
    CONSTRAINT ck_outbox_state CHECK (
        (published_at IS NULL AND attempts >= 0) OR
        (published_at IS NOT NULL)
    )
);

CREATE INDEX idx_outbox_pending ON <schema>.outbox (next_attempt_at)
    WHERE published_at IS NULL;
```

Canonical `OutboxPublisher` semantics (lifted into
`platform-spring-boot-messaging`):

- Poll loop: every 1s, `SELECT ... FROM <schema>.outbox WHERE
  published_at IS NULL AND next_attempt_at <= now() ORDER BY
  next_attempt_at FOR UPDATE SKIP LOCKED LIMIT 100`.
- Publish: `KafkaTemplate.send(topic, partition_key, payload)`.
- On success: `UPDATE ... SET published_at = now()`.
- On failure: `UPDATE ... SET attempts = attempts + 1, last_error =
  <msg>, next_attempt_at = now() + (5 * 2^attempts) seconds`.
- After 6 attempts: send to `<topic>.dlq` and mark `published_at =
  now()` (terminal — manual replay only).

Migration path per service:

1. Add missing columns: `ALTER TABLE <schema>.outbox ADD COLUMN ...`
2. Backfill: `UPDATE <schema>.outbox SET partition_key = event_id::text
   WHERE partition_key IS NULL;` etc.
3. Set NOT NULL: `ALTER TABLE <schema>.outbox ALTER COLUMN partition_key
   SET NOT NULL;` etc.
4. Add CHECK constraint and partial index.

### Consequences

**Good:**
- Single canonical outbox schema across 6 services
- `event_id` UNIQUE constraint enforces dedup
- `FOR UPDATE SKIP LOCKED` semantics prevent double-publish on
  multi-replica deployments
- Exponential backoff up to 5 min before DLQ; no message loss
- 6 redundant `OutboxEvent.kt` files deleted (~720 LOC)
- 7 redundant `OutboxPublisher.kt` files adopt the platform
  helper (~350 LOC deleted in Phase D)

**Bad:**
- 1 service (`payment-service`) has table `outbox_events` not
  `outbox`; rename via `ALTER TABLE payment.outbox_events RENAME TO
  payment.outbox;`
- 2 services use `Map<String, Any?>` payload type; must add a
  JPA `AttributeConverter<JsonNode>` to persist as JSONB
- 3 services use 3-attempts-then-DLQ semantics; must update
  retry config to exponential backoff
- Existing unpublished events are preserved (migrated, not
  invalidated)

### Follow-up

- [ ] Update `shared/CONVENTIONS.md` §outbox to declare the
  canonical 11-column shape and the `OutboxPublisher` poll
  semantics.
- [ ] Update `shared/MODULES.md` to declare the platform
  `OutboxPublisher` as the canonical poll loop.
- [ ] Add `OutboxPublisherAutoConfiguration` to
  `platform-spring-boot-messaging` with default cron and retry
  config (Phase D).

## Pros and Cons of the Options

### 11-column canonical schema (chosen)

Matches platform `OutboxEvent`; covers every existing variant;
adds `partition_key`, `headers`, `next_attempt_at` as required
columns; provides exponential backoff.

### Per-service schema

Current state. Rejected because it defeats the purpose of a
shared library and creates cross-service join failures.

### 6-column minimal schema

Used by 3 services. Missing `partition_key` (must be derived at
publish time) and `next_attempt_at` (no retry backoff). Rejected
because it loses the `FOR UPDATE SKIP LOCKED` semantics and the
DLQ recovery story.

### 15-column maximal schema

Used by 1 service (payment-service). Adds `created_by` and rich
`headers`. Rejected as premature; the 11-column schema's `headers`
JSONB already accommodates rich headers.

## References

- [ADR-0009](0009-transactional-outbox.md) — Outbox pattern for
  event publication (the foundational ADR)
- [ADR-0024](0024-dlq-topic-naming.md) — DLQ topic naming
  (`<topic>.dlq`)
- [ADR-0015](0015-uuidv7-for-ids.md) — UUIDv7 for new identifiers
- [`shared/PLATFORM_DRY_AUDIT.md` §3.2 K-08](../../shared/PLATFORM_DRY_AUDIT.md)
  — the audit that flagged this drift
- [`shared/CONVENTIONS.md`](../shared/CONVENTIONS.md#outbox) —
  the canonical outbox contract
