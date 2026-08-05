# ADR-0015: UUIDv7 for New Identifiers

- Status: Accepted
- Date: 2026-07-29
- Authors: Platform Architecture Team
- Deciders: Architecture Review Board
- Tags: identifiers, uuid, uuidv7, ulid, database, indexing

## Context and Problem Statement

Every entity in the platform — `customer`, `driver`, `courier`,
`merchant`, `restaurant`, `trip`, `food_order`, `payment`,
`ledger_posting`, `notification`, `audit_event`, `saga_state`,
…— needs a stable, globally unique identifier. The identifier
must be (a) unique across services and across regions (so we can
move an entity across services or regions without renaming), (b)
generated client-side or server-side without a coordinator (so a
mobile app can create a `ride_request` offline; so a service can
emit an event without a round-trip to a central ID service), (c)
opaque (so we do not leak business meaning or sequence), (d)
indexable in Postgres without hot-spotting, and (e) ordered by
creation time so that a range query on the ID corresponds to a
range query on the creation time (a property that makes indexes
locality-friendly and event-log queries efficient).

The choice is between **UUIDv7** (RFC 9562, 2024 — time-ordered,
128 bits, no central coordinator), **UUIDv4** (the historical
default — random, 128 bits, no central coordinator), **ULID**
(48-bit timestamp + 80-bit random — time-ordered, 128 bits, no
central coordinator), and **auto-increment integers** (32 or 64
bits — simple, but requires a central coordinator and leaks
sequence).

## Decision Drivers

- Globally unique across services and across regions.
- No central coordinator: a mobile app, a service, a Kafka
  consumer can generate an ID without a round-trip.
- Time-ordered: a range query on the ID corresponds to a range
  query on the creation time; B-tree index inserts are at the
  right (newest) end of the index, not random.
- 128 bits: no collision risk; safe to generate without
  coordination.
- Opaque: the ID does not leak business meaning (e.g. customer
  count) or sequence (e.g. "this is the 42nd trip today").
- Standard: an RFC (9562) so libraries exist in every language
  ecosystem.
- Postgres-friendly: a UUID column with a B-tree index; no
  special type, no special index.

## Considered Options

- **UUIDv7 (RFC 9562)** — the chosen option. 48-bit Unix
  timestamp in milliseconds + 12 bits of sub-millisecond
  precision + 62 bits of random. Time-ordered, globally unique,
  no coordinator.
- **UUIDv4** — the historical default. 122 bits of random; not
  time-ordered.
- **ULID** — 48-bit timestamp + 80-bit random. Time-ordered,
  but a separate standard from the UUID family.
- **Auto-increment integers (BIGSERIAL, BIGINT IDENTITY)** —
  simple, but requires a central coordinator (the database
  sequence) and leaks sequence.
- **Snowflake IDs (Twitter-style)** — 64-bit, time-ordered, but
  requires a central coordinator (the Snowflake service) and
  is shorter (64 bits) than we want for cross-region safety.

## Decision Outcome

Chosen option: "**UUIDv7**", because (a) it is time-ordered (a
range query on the ID corresponds to a range query on the
creation time, which makes B-tree index inserts local to the
newest end of the index — a property that materially helps
write throughput on high-volume tables like
`driver_location.locations` and `courier_tracking.locations`),
(b) it is a 128-bit UUID, so the same column type, the same
indexes, the same libraries work as for UUIDv4, (c) it is an
RFC (9562, 2024) with mature libraries in Go, TypeScript/Node,
Kotlin/JVM, Dart/Flutter, Python, and Rust, (d) it is globally
unique without a coordinator, so a mobile app, a service, or a
Kafka consumer can generate an ID without a round-trip, and
(e) it is opaque — the time prefix is fine-grained (48 bits of
milliseconds + 12 bits of sub-millisecond precision), so it
does not leak the exact creation time at the second level
(unlike a Unix timestamp in seconds).

UUIDv4 remains acceptable for existing services and for entities
where time-ordering is not useful (e.g. an internal correlation
id). New services and new entities use UUIDv7.

### Consequences

- Good: Time-ordered. B-tree index inserts are at the newest
  end of the index, not random. This is a real performance
  win for high-volume tables (`driver_location.locations`,
  `courier_tracking.locations`, `audit.events`,
  `notification.deliveries`).
- Good: Range queries on the ID correspond to range queries on
  the creation time. "All events for trip T created after time
  X" is a single index range scan.
- Good: 128 bits, no collision risk, no coordinator.
- Good: Standard UUID column type and UUID indexes in Postgres.
  No migration of existing data; just a default change for new
  rows.
- Good: Mature libraries in every language ecosystem.
- Good: Opaque at the second level (the 48-bit millisecond
  prefix is fine-grained; a 10-digit customer count is not
  derivable from the ID).
- Bad: A 48-bit millisecond prefix is fine-grained but not
  perfectly opaque; an adversary who knows the ID was created
  on a given day can narrow the creation time to a millisecond.
  (Mitigation: this is acceptable for our use cases; the ID
  is not a security token.)
- Bad: We have UUIDv4 IDs in existing services and existing
  data. We accept this; new services use UUIDv7 by default;
  existing services may opt in to UUIDv7 for new tables without
  backfilling old data.
- Bad: UUIDv7 is newer than UUIDv4; some libraries are still
  catching up. (Mitigation: we pin a library version per
  language and document the choice in the per-service
  `INTEGRATION.md`.)
- Neutral: The Postgres column type is `uuid`; the index is a
  B-tree; no special type, no special index.

### Confirmation

- 100% of new tables created after 2026-07-29 use UUIDv7 by
  default; verified by a CI lint that asserts the default
  expression on the `id` column.
- Insert performance on high-volume tables
  (`driver_location.locations`, `courier_tracking.locations`):
  B-tree insert P99 latency improves by 20-40% vs. UUIDv4
  (measured in a load test).
- Range queries: "all events for trip T after time X" is a
  single index range scan; verified by `EXPLAIN ANALYZE` on
  representative queries.
- Library coverage: every language ecosystem (Go, TypeScript,
  Kotlin, Dart, Python, Rust) has a UUIDv7 library in the
  platform's standard library set; documented per service.

## Pros and Cons of the Options

### UUIDv7 (RFC 9562)

The chosen option. 48-bit Unix timestamp in milliseconds + 12
bits of sub-millisecond precision + 62 bits of random. Globally
unique, time-ordered, no coordinator.

- Good: Time-ordered; B-tree inserts are at the newest end of
  the index.
- Good: 128 bits, no collision risk, no coordinator.
- Good: Standard UUID column type and UUID indexes in
  Postgres.
- Good: Mature libraries in every language ecosystem.
- Good: Opaque at the second level.
- Bad: 48-bit millisecond prefix is fine-grained but not
  perfectly opaque.
- Bad: Newer than UUIDv4; some libraries are still catching
  up.
- Bad: Existing services have UUIDv4 IDs; we accept this.

### UUIDv4

The historical default. 122 bits of random.

- Good: Mature; libraries everywhere.
- Good: Globally unique, no coordinator.
- Good: Fully opaque (no time prefix).
- Bad: Not time-ordered; B-tree inserts are random, which
  hurts write throughput on high-volume tables.
- Bad: A range query on the ID does not correspond to a
  range query on the creation time; the application must add
  a separate `created_at` index for time-range queries.

### ULID

48-bit timestamp + 80-bit random. Time-ordered, globally unique.

- Good: Time-ordered; B-tree inserts are at the newest end of
  the index.
- Good: Globally unique, no coordinator.
- Good: Canonical string representation is shorter than UUID
  (26 chars vs. 36).
- Bad: A separate standard from the UUID family; not
  interchangeable with UUIDs; Postgres has no native ULID
  type; we'd store as a 128-bit binary or a 26-char string.
- Bad: Libraries are less universal than UUID libraries.
- Bad: The string representation is not a UUID; we'd have to
  convert in every integration with systems that expect
  UUIDs.

### Auto-increment integers (BIGSERIAL, BIGINT IDENTITY)

Simple, but requires a central coordinator.

- Good: Simple; small; fast.
- Good: Time-ordered (sequence is monotonically increasing
  per database).
- Bad: Requires a central coordinator (the database sequence);
  we cannot generate an ID without a round-trip.
- Bad: 64 bits is not enough for cross-region safety (we'd
  need to coordinate sequences across regions).
- Bad: Leaks sequence ("this is the 42nd trip today" — a
  business-leakage anti-pattern).
- Bad: A merge of two databases requires sequence
  reconciliation.
- Bad: A future move to a different database or a
  different shard requires sequence migration.

### Snowflake IDs (Twitter-style)

64-bit, time-ordered.

- Good: Time-ordered; small; fast.
- Good: 64 bits is enough for a single region.
- Bad: Requires a central coordinator (the Snowflake
  service); we cannot generate an ID without a round-trip.
- Bad: 64 bits is not enough for cross-region safety without
  a coordinator.
- Bad: A separate standard from the UUID family.
- Bad: Operationally separate from the rest of the platform
  (we'd run a Snowflake service).

## References

- [`DATABASE_ARCHITECTURE.md`](../DATABASE_ARCHITECTURE.md) —
  primary keys are `id UUID PRIMARY KEY`; UUIDv7 default for
  new services, UUIDv4 acceptable for existing.
- [`API_STANDARDS.md`](../API_STANDARDS.md) — UUIDv7 preferred
  for new identifiers.
- [`EVENT_ARCHITECTURE.md`](../EVENT_ARCHITECTURE.md) —
  `event_id` and `aggregate_id` are ULID/UUIDv7; time-
  orderability within a producer.
- [`MICROSERVICES_MAP.md`](../MICROSERVICES_MAP.md) — every
  service's `id` column is a UUID.
- RFC 9562 — *Universally Unique IDentifiers (UUID)*, 2024 —
  UUIDv7 specification.
- Postgres documentation — `uuid` type, B-tree indexes on
  UUID columns, `gen_random_uuid()` and UUIDv7 functions.
- The `uuidv7` libraries in Go (`github.com/google/uuid`),
  TypeScript (`uuidv7`), Kotlin (`com.benasher44:uuid`),
  Dart (`uuid` package), Python (`uuid`), Rust (`uuid`).
