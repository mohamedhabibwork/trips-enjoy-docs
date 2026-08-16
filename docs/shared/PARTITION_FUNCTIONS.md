---
title: Partition Maintenance Functions
service: shared
status: canonical
last_updated: 2026-08-14
source_of_truth: yes
---

# Partition Maintenance Functions

> **Single source of truth** for the cross-service partition-maintenance
> contract. Every per-service Flyway migration, every per-service
> `PartitionMaintenanceJob`, and every per-service `WORKFLOWS.md`
> partition section MUST be consistent with this document.
>
> See also [`../architecture/DATABASE_ARCHITECTURE.md` §"Table Partitioning — Canonical Template" §12](../architecture/DATABASE_ARCHITECTURE.md)
> for the partitioning doctrine this contract implements.

## 1. Why database functions, not per-service loops

Before this contract existed, every Kotlin service that owned partitioned
tables shipped its own copy of `PartitionMaintenanceJob.kt`:

- `audit-service`
- `configuration-service`
- `identity-service`
- `ledger-service`
- `notification-service`

The five copies diverged:

| Service | `LocalDate`? | Bounds verify? | Emits outbox event? | Lock-acquire bug? |
|---------|--------------|----------------|---------------------|-------------------|
| audit | no (Calendar) | yes | yes (topic `audit.partition.maintained`) | no |
| configuration | yes | no | no | no |
| identity | no (Calendar) | no | no | no |
| ledger | no (Calendar) | yes | yes (but topic = `audit.partition.maintained`, event name = `audit.partition.maintained.v1` — **wrong namespace**) | yes — calls `pg_try_advisory_xact_lock` **twice** |
| notification | no (Calendar) | yes | yes (topic `notification.partition.maintained`) | no |

Eight inline `DO $$ … LOOP` pre-create blocks in Flyway migrations
duplicated the same logic in SQL. None of them verified `relpartbound`.
Two services (configuration, identity) skipped the verify-bounds step
entirely.

The contract below replaces all of that with:

- **One** PL/pgSQL function pair (`partman.ensure_partitions` +
  `partman.drop_expired_partitions`) installed once per service schema.
- **One** `pg_cron` schedule per partitioned parent.
- **One** thin Spring `@Scheduled` wrapper per service that calls the
  function, acquires the advisory lock, and emits the outbox event.

## 2. Two engines

| Engine | When | Reference SQL |
|--------|------|----------------|
| **Canonical PL/pgSQL** (default) | Every service that owns partitioned tables | [`sql/partition_functions.sql`](sql/partition_functions.sql) |
| **`pg_partman`** (opt-in) | Services that pre-committed to pg_partman in PLAN.md (`T-<SVC>-02` rows in MASTER_TASK.md). None have shipped it yet. | [`sql/partition_functions_pg_partman.sql`](sql/partition_functions_pg_partman.sql) |

`pg_partman` is **opt-in**, not required. A service adopts it by
installing the `pg_partman` extension in its V1 and calling
`partman.create_parent(...)` + `partman.run_maintenance(...)` instead of
`partman.ensure_partitions(...)`. Every other contract in this document
(advisory lock, outbox event, pg_cron) still applies.

> **Note on naming collision**: both `pg_partman` and the canonical
> functions live in a schema named `partman`. To avoid ambiguity, services
> adopting `pg_partman` install its schema as `pg_partman` (the upstream
> default) and call the canonical functions from the `public` namespace
> by alias. The canonical functions must be installed in a separate
> schema — see [`sql/partition_functions.sql`](sql/partition_functions.sql)
> for the install-as-`partman_<service>` recommendation.

## 3. Function signatures

### `partman.ensure_partitions(parent REGCLASS, horizon INT) → JSONB`

Pre-creates monthly child partitions for the parent table.

- **`parent`** — the partitioned parent table (e.g. `audit.events`).
- **`horizon`** — number of complete future months to maintain.
  `12` is the platform default (DATABASE_ARCHITECTURE §3).
- **Returns** `JSONB`:
  ```json
  {
    "parent": "audit.events",
    "created": 13,
    "skipped": 0,
    "verified": 13,
    "future_count": 12,
    "past_count": 1,
    "current_count": 1,
    "ran_at": "2026-08-14T02:00:00+00"
  }
  ```
- **Idempotent** — running twice in the same window is safe; the second
  run reports `created: 0`.
- **Bounds-verified** — every `CREATE TABLE IF NOT EXISTS … PARTITION OF`
  is followed by a `pg_inherits.inhparent` + `pg_get_expr(relpartbound)`
  check; mismatch raises.

For tables with **daily** cadence (`trip.trip_location_points`,
`driver.driver_location_points`, `courier.courier_location_points`),
use `partman.ensure_partitions_daily(parent REGCLASS, horizon_days INT)`
from [`sql/partition_functions.sql`](sql/partition_functions.sql) §B.

### `partman.drop_expired_partitions(parent REGCLASS, retention INTERVAL, retention_class_filter TEXT DEFAULT NULL) → JSONB`

Drops child partitions whose upper bound is older than the retention window.

- **`parent`** — the partitioned parent.
- **`retention`** — e.g. `'7 years'` for `audit.audit_events` financial
  rows, `'1 year'` for `audit.audit_events` default rows.
- **`retention_class_filter`** — when the parent has a
  `retention_class` column with mixed values, only drop rows matching
  this filter. NULL drops rows of any class whose bound has expired AND
  whose `retention_class` matches the service-specific retention map.
- **Returns** `JSONB`:
  ```json
  {
    "parent": "audit.events",
    "dropped": 1,
    "skipped_hold": 0,
    "remaining_past_count": 13
  }
  ```

The drop step:

1. Reads `pg_class` for child partitions of `<parent>`.
2. For each child whose upper bound < `now() - retention`, asserts
   no row matches `litigation_hold = TRUE` (or the equivalent flag).
3. Runs `ALTER TABLE <parent> DETACH PARTITION <child> CONCURRENTLY`.
4. Runs `DROP TABLE <child>`.
5. Optionally archives the table to S3 first (out of scope for the
   canonical function; the service may extend it via a wrapper).

### `partman.partition_health(parent REGCLASS) → TABLE`

```sql
SELECT * FROM partman.partition_health('audit.events'::REGCLASS);
```

Returns one row per partitioned parent with:

| Column | Type | Meaning |
|--------|------|---------|
| `parent` | `text` | qualified parent name |
| `current_count` | `int` | children covering `now()` |
| `future_count` | `int` | children with lower bound > `now()` |
| `past_count` | `int` | children with upper bound ≤ `now()` |
| `today_missing` | `bool` | TRUE if no child contains `now()` |
| `oldest_past_lower` | `timestamptz` | lower bound of the earliest past child (for retention sweep) |

## 4. JSON return shape contract

Every function returns `JSONB`. The keys are stable and MUST NOT change
without an ADR — they are consumed by the per-service
`PartitionMaintenanceEventPublisher` to build the outbox event payload.

```json
{
  "parent": "<schema>.<table>",
  "created": <int>,
  "skipped": <int>,
  "verified": <int>,
  "future_count": <int>,
  "past_count": <int>,
  "current_count": <int>,
  "ran_at": "<ISO-8601 UTC>"
}
```

For `drop_expired_partitions` the same schema is used with
`dropped` and `skipped_hold` instead of `created`/`skipped`.

## 5. Advisory-lock contract

Every caller of `partman.ensure_partitions` MUST first acquire the
service's partition-maintenance advisory lock, exactly as today:

```sql
SELECT pg_try_advisory_xact_lock(hashtext('<schema>'), hashtext('partition'));
```

If the lock is not acquired, the caller skips silently. The lock is held
for the duration of the transaction (the `xact` flavour), so a crash
inside the function releases it on rollback.

This is the same lock the `PartitionMaintenanceJob` Spring wrapper
acquires; the pg_cron schedule acquires it inside the same `DO $$`
block as the function call.

## 6. Bounds-verification contract

`partman.ensure_partitions` runs the same verification step the
canonical template §5 already calls for:

```sql
DO $$
DECLARE
    v_parent REGCLASS := '<schema>.<table>'::REGCLASS;
    v_child  REGCLASS := '<expected_child>'::REGCLASS;
    v_expected TSTZRANGE := tstzrange('<from>', '<to>', '[)');
BEGIN
    IF (SELECT inhparent FROM pg_inherits WHERE inhrelid = v_child)
       IS DISTINCT FROM v_parent THEN
        RAISE EXCEPTION 'partition % is not attached to %', v_child::text, v_parent::text;
    END IF;
    IF NOT (SELECT relpartbound FROM pg_class WHERE oid = v_child)
              = v_expected THEN
        RAISE EXCEPTION 'partition % has unexpected bounds', v_child::text;
    END IF;
END $$;
```

This closes the verification gap in `configuration-service` and
`identity-service` (which skipped the step pre-refactor).

## 7. Per-service Spring wrapper contract

Each of the five Kotlin services keeps its `PartitionMaintenanceJob`,
shrunk to ~10 lines:

```kotlin
@Component
class PartitionMaintenanceJob(
    private val jdbc: JdbcTemplate,
    @Value("\${<service>.partition.horizon-months:12}") private val horizon: Int,
) {
    @Scheduled(cron = "\${<service>.partition.cron:0 0 2 * * *}")
    fun ensurePartitions() {
        val acquired = jdbc.queryForObject(
            "SELECT pg_try_advisory_xact_lock(hashtext('<schema>'), hashtext('partition'))",
            Boolean::class.java,
        ) ?: return
        if (!acquired) return
        parents.forEach { parent ->
            jdbc.queryForObject(
                "SELECT partman.ensure_partitions(?::REGCLASS, ?)",
                String::class.java,
                parent, horizon,
            )
        }
    }
}
```

Responsibilities, in order:

1. Acquire the advisory lock (skip if held by another replica).
2. For each partitioned parent the service owns, `SELECT partman.ensure_partitions(?, ?)`.
3. Hand the returned JSON to `PartitionMaintenanceEventPublisher` to emit
   the outbox event.

The Spring wrapper is the **fallback trigger**. The primary trigger is
the pg_cron schedule in §8.

## 8. pg_cron schedule contract

Every Flyway migration that introduces a partitioned parent MUST also
schedule a pg_cron job against it:

```sql
CREATE EXTENSION IF NOT EXISTS pg_cron;

SELECT cron.schedule(
    '<schema>.partition.<table>.ensure',
    '0 2 * * *',
    $$ SELECT partman.ensure_partitions('<schema>.<table>'::REGCLASS, 12) $$);
```

Rules:

- **Schedule**: `0 2 * * *` (02:00 UTC daily, before peak ingest).
- **Job name**: `<schema>.partition.<table>.ensure` (or `.drop_expired`
  for retention sweeps on `'0 3 * * 0'` — weekly).
- **Inside the cron expression**: `$$ ... $$` dollar-quoted so single
  quotes inside the SQL do not collide with the cron schedule parser.
- **Idempotent schedule**: every Flyway migration that adds the cron job
  MUST first `SELECT cron.unschedule('<schema>.partition.<table>.ensure')`
  to make the migration safe to re-run.

The pg_cron install lives in two places (defense in depth):

1. `scripts/db-init.sh` — runs as the Postgres superuser at cluster
   bootstrap, idempotent (`CREATE EXTENSION IF NOT EXISTS pg_cron`).
2. Each service's V__partition_functions.sql — runs as the application
   role inside Flyway; the `IF NOT EXISTS` guard makes it a no-op when
   `db-init.sh` already wired it.

## 9. Mixed-retention handling

Tables with `retention_class` (`audit.audit_events`) MUST NOT mix
retention classes inside one partition. The canonical rule from
DATABASE_ARCHITECTURE §8 applies:

- For single-class tables (`payment.payment_attempts`,
  `ledger.journal_entries`): the `drop_expired_partitions` call has
  `retention_class_filter = NULL`.
- For mixed tables (`audit.audit_events`): call
  `drop_expired_partitions` **per retention class**:
  ```sql
  SELECT partman.drop_expired_partitions(
      'audit.audit_events'::REGCLASS,
      INTERVAL '7 years',
      retention_class_filter := 'financial');
  SELECT partman.drop_expired_partitions(
      'audit.audit_events'::REGCLASS,
      INTERVAL '1 year',
      retention_class_filter := 'default');
  ```
  Two pg_cron schedules, two Spring wrapper entries.

Litigation/legal hold is checked **before** DETACH. The function reads
the `litigation_hold` flag on each row of the candidate child and aborts
with `skipped_hold: N` if any are held.

## 10. Outbox event contract

Every service emits **one** outbox event per maintenance run, always
under the schema-namespaced name:

| Service | Topic | Event name | Payload schema |
|---------|-------|------------|----------------|
| audit-service | `audit.partition.maintained` | `audit.partition.maintained.v1` | `{"schema": "audit", "created": N, "dropped": M}` |
| configuration-service | `configuration.partition.maintained` | `configuration.partition.maintained.v1` | `{"schema": "configuration", "created": N, "dropped": M}` |
| identity-service | `identity.partition.maintained` | `identity.partition.maintained.v1` | `{"schema": "identity", "created": N, "dropped": M}` |
| ledger-service | `ledger.partition.maintained` | `ledger.partition.maintained.v1` | `{"schema": "ledger", "created": N, "dropped": M}` |
| notification-service | `notification.partition.maintained` | `notification.partition.maintained.v1` | `{"schema": "notification", "created": N, "dropped": M}` |

This **fixes the bug** where `ledger.PartitionMaintenanceJob` emitted
under `audit.partition.maintained.v1` / topic
`audit.partition.maintained`. After this contract every service uses
its own namespace.

The payload `data.created` and `data.dropped` come from the JSON
returned by `partman.ensure_partitions` and
`partman.drop_expired_partitions` respectively.

## 11. Testing requirements

Each service that owns partitioned tables MUST ship two new tests
(append to existing test scaffolding, not replace):

### 11.1 `PartitionMaintenanceJobTest.kt` (unit)

- Mocks `JdbcTemplate`.
- Asserts the advisory-lock failure path returns without throwing.
- Asserts `SELECT partman.ensure_partitions(...)` is called for each
  declared parent with the configured horizon.

### 11.2 `PartitionFunctionsIT.kt` (Testcontainers integration)

- Boots Testcontainers Postgres (already wired in each service's
  `TestcontainersConfiguration`).
- Runs Flyway migrations end-to-end.
- Calls `SELECT partman.ensure_partitions('<schema>.<table>'::REGCLASS, 12)`.
- Asserts `pg_inherits` contains exactly `1 + 1 + 12 = 14` children
  (one past + one current + 12 future).
- Asserts `pg_get_expr(c.relpartbound, c.oid)` round-trips the literal
  `FROM (…) TO (…)` for each child.
- Calls the function a second time and asserts
  `created: 0, skipped: 14, verified: 14` in the JSON.
- Asserts `SELECT * FROM partman.partition_health('<schema>.<table>'::REGCLASS)`
  returns `today_missing: FALSE`.

## 12. Migration install order

The canonical function pair installs itself idempotently. To upgrade
an existing cluster:

1. Deploy the new V__partition_functions.sql migration (canonical
   functions + `CREATE EXTENSION IF NOT EXISTS pg_cron` + the
   `cron.schedule` calls). The function exists as `CREATE OR REPLACE`.
2. The migration ALSO replaces the inline `DO $$ … LOOP` blocks in
   older migrations **only if** the service has not yet shipped to
   production. For services already in production, leave the old
   DO-blocks in place; the new function is a strict superset and the
   old blocks remain harmless.
3. Deploy the slimmed-down `PartitionMaintenanceJob.kt` and the new
   `PartitionMaintenanceEventPublisher.kt`. The wrapper's first run
   after deploy acquires the advisory lock, calls the function, and
   emits the namespaced outbox event.

For a greenfield deploy (no production data yet), the V2/V3/V4
migrations should be edited to remove the inline DO-blocks entirely
and rely on the V__partition_functions.sql + cron schedule.

## See also

- [`../architecture/DATABASE_ARCHITECTURE.md` §"Table Partitioning — Canonical Template" §1–§11](../architecture/DATABASE_ARCHITECTURE.md)
- [`sql/partition_functions.sql`](sql/partition_functions.sql) — canonical PL/pgSQL reference DDL
- [`sql/partition_functions_pg_partman.sql`](sql/partition_functions_pg_partman.sql) — pg_partman opt-in alternative
- [`PLATFORM_BASELINE.md`](./PLATFORM_BASELINE.md) §2 Data baseline (pg_cron + ensure_partitions row)
- Each per-service `WORKFLOWS.md` §"Monthly Partition Maintenance" / §"Partition Lifecycle"
