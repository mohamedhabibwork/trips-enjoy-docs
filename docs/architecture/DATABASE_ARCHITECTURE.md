# Database Architecture


```mermaid
flowchart TB
  subgraph "Cluster A (shared, most services)"
    s1["schema: ride_request"]
    s2["schema: trip"]
    s3["schema: pricing"]
    s4["schema: customer"]
  end
  subgraph "Cluster B (isolated, payment)"
    p1["schema: payment<br/>(PCI-scope)"]
    p2["schema: wallet"]
    p3["schema: ledger"]
  end
  subgraph "Cluster C (isolated, analytics)"
    a1["schema: audit<br/>(append-only)"]
    a2["schema: analytics"]
  end
  subgraph Inside["Inside one schema"
    tbl["Tables (UUIDv7 PK)"]
    idx["Indexes (incl. partial<br/>WHERE deleted_at IS NULL)"]
    check["Check constraints<br/>+ generated columns"]
    soft["Soft-delete column"]
    audit["Audit columns<br/>(created_at, updated_at)"]
  end
  s1 & s2 & s3 & s4 -. "no cross-schema FKs" .-> s1 & s2 & s3 & s4
  p1 & p2 & p3 -. "no cross-cluster FKs" .-> p1 & p2 & p3
  tbl --> idx
  tbl --> check
  tbl --> soft
  tbl --> audit
```

## Database Per Service

- One PostgreSQL 18 instance per service (logical schema in a shared
  cluster is acceptable; one cluster can host many service schemas).
  Physical isolation (one cluster per service) is reserved for the
  most critical or noisiest services.
- Each service has exactly one **owner schema**; it MAY have
  additional **read schemas** owned by the same service for
  materialized views.
- No service may `SELECT` from another service's tables. Cross-service
  data is accessed via the owning service's API.
- Migration tooling is per-service: each service owns its migration
  set; cross-service migrations are forbidden.

### Why PostgreSQL 18

- Mature, ACID, JSONB, generated columns.
- PostGIS extension is the platform's geospatial engine.
- Strong logical replication for read replicas and reporting.
- Logical decoding for outbox patterns (Debezium).
- Declarative partitioning for high-volume tables.
- Strong tooling ecosystem (pg_dump, pgBackRest, pgaudit).

See ADR-0002.

## Naming Conventions

- **Schemas**: `<service_name_snake>` (e.g. `ride_request`, `trip`,
  `payment`).
- **Tables**: `snake_case`, plural for collections
  (`rides`, `trips`, `payments`).
- **Columns**: `snake_case`.
- **Primary keys**: `id UUID PRIMARY KEY` (UUIDv7 default for new
  services, UUIDv4 acceptable for existing). The column is always
  `id`, never `<table>_id`. Other tables referencing it use
  `<table>_id`.
- **Foreign keys (within a service)**: `<referenced_table>_id`
  (e.g. `trip_id`, `customer_id`). Always `REFERENCES` within the
  same schema.
- **Cross-service references**: `<entity>_id UUID NOT NULL` with **no
  database-level FK**. Enforced at the application layer.
- **Audit columns**: every mutable table has
  `created_at TIMESTAMPTZ NOT NULL DEFAULT now()`,
  `updated_at TIMESTAMPTZ NOT NULL DEFAULT now()`,
  `created_by UUID`,
  `updated_by UUID`.
- **Soft delete**: `deleted_at TIMESTAMPTZ NULL`. Default queries
  filter `WHERE deleted_at IS NULL`.
- **Money**: `amount_minor BIGINT NOT NULL`, `currency CHAR(3) NOT NULL`
  (ISO 4217).
- **Timestamps**: `TIMESTAMPTZ` only; never `TIMESTAMP`.

## Migrations

- Migration tool per service. Recommended: `golang-migrate` /
  `Flyway` / `dbmate` / `prisma migrate` — pick what fits the stack,
  but the migration history MUST be:
  - Versioned.
  - Idempotent (or strictly forward-only).
  - Stored in source control alongside the service code.
  - Reviewed in PRs.
- **No destructive migrations** without a multi-step plan:
  1. Add the new column or table.
  2. Backfill.
  3. Switch reads to the new shape.
  4. Switch writes.
  5. Drop the old column/table.
- **Long-running data migrations** are deployed as background jobs,
  not migrations.

## Indexing

- Every primary key is indexed (default).
- Every foreign key is indexed.
- Every column used in a `WHERE` clause of a hot query is indexed.
- Composite indexes for multi-column filters; column order matters.
- Partial indexes for soft-deleted tables:
  `CREATE INDEX … ON trips(customer_id) WHERE deleted_at IS NULL`.
- For text search, prefer GIN with `pg_trgm` or `tsvector` for small
  data; offload to `search-service` for large data.

## Constraints

- **NOT NULL** on every required column.
- **CHECK constraints** for enum-like values (`status IN (…)`).
- **Unique constraints** for natural keys (e.g. `email`).
- **Foreign keys** within the service.
- **No FKs** across service schemas.
- **No FKs** from a column whose owning service is different.

## Table Partitioning — Canonical Template

> **Single source of truth** for partitioning decisions across the
> platform. Every per-service `ERD.md` `## 9. Partitioning` section,
> `TECH.md` `## 3. Data layer` cadence annotation, and `WORKFLOWS.md`
> partition-maintenance section MUST be consistent with this template.

### 1. When to partition

Partition a table when **all** of the following are true:

- **Append-mostly** — rows are inserted by event/time and never
  updated in place (or only within a short window before being
  frozen). Examples: event/audit logs, location trails, state
  histories, saga steps, financial postings, evaluation logs.
- **Time-filtered hot queries** — the dominant access pattern is
  `WHERE <ts> BETWEEN … AND …`.
- **Finite retention** — there is a known retention window after
  which the data has no value (regulatory window, aggregation
  window, trail window).
- **Drop beats delete** — dropping an entire partition is faster,
  cheaper, and produces less WAL than row-level DELETE.

Do **not** partition when:

- The table is a CRUD aggregate root (`customers`, `drivers`,
  `merchants`, `vehicles`, `addresses`, `menus`, …) — access is
  key-based and updates are common.
- Total volume is bounded by business cardinality (≤ low millions
  per service) and access is identity-based, not time-windowed.
- The table is purged continuously by a poller (e.g. `outbox`,
  `inbox`) and stays small under healthy operation.

### 2. Approved strategies

| Strategy | When | Default cadence |
|----------|------|-----------------|
| `RANGE` by UTC time | The default and only platform strategy for partitioned tables | day / week / month / year (see §3) |
| `LIST` | Only as a sub-strategy when retention/lifecycle truly differs by a low-cardinality class (e.g. `retention_class`); requires an ADR | n/a |
| `HASH` | Not approved as a default. HASH is a distribution tool, not a retention mechanism; requires an ADR with measured rationale | n/a |

**Composite key requirement**: every unique constraint or primary
key on a partitioned parent MUST include the partition-key column.
PostgreSQL enforces this; a single-column PK on a partitioned
parent will fail at table-creation time.

### 3. Cadence decision table

| Cadence | Use for | Pre-create horizon | Retention window |
|---------|---------|--------------------|------------------|
| **Daily** | Hot location/trail tables (`driver_location.locations`, `courier_tracking.locations`, `trip.trip_location_points`), short-retention evaluation/provider-health logs (`feature_flag.evaluation_log`, `comms_gateway.provider_health`) | 30 days | 2 hours — 30 days (per service) |
| **Weekly** | High-velocity state histories (`delivery.delivery_state_history` at > 1k rows/s) | 8 weeks | 3 years |
| **Monthly** | Event/audit/financial logs (`audit.events`, `audit.read_log`, `ledger.postings`, `payment.payment_attempts`, `wallet.transactions`, `notification.deliveries`, `loyalty.transactions`, `fraud_risk.scores`, `fraud_risk.actions`, `reporting.export_jobs`, `reporting.drift_findings`, `support.actions`, per-service `audit_log`) | 12 months | 1 — 10 years (per data class) |
| **Yearly** | Long-lived read models (`ride_history.entries` at > 1M rows/year) | 2 years | 7 years |

The "next 30 days" wording is **only** correct for daily cadence.
For monthly/yearly, the horizon is "N complete future periods"
(e.g. "next 12 months") so the next calendar boundary is always
covered.

### 4. Parent DDL template

```sql
CREATE SCHEMA IF NOT EXISTS <schema_name>;

CREATE TABLE <schema_name>.<table_name> (
    id              UUID        NOT NULL,
    -- … domain columns …
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (id, created_at)            -- partition key MUST be in PK
) PARTITION BY RANGE (created_at);

-- Indexes are created on the parent; PostgreSQL propagates to children.
CREATE INDEX idx_<table>_<col>
    ON <schema_name>.<table_name> (<col>, created_at DESC);
```

Rules:

- The partition column MUST be `TIMESTAMPTZ` (UTC). Never
  `TIMESTAMP` (without time zone), never `DATE` for sub-day
  cadence.
- The partition column MUST be `NOT NULL`.
- For append-only tables, revoke `UPDATE` and `DELETE` from the
  application role (`REVOKE UPDATE, DELETE ON … FROM <app_role>`).
- Do not create a default partition unless the service explicitly
  documents a drain procedure; a default partition that silently
  accumulates data violates the retention contract.

### 5. Child DDL template (idempotent)

```sql
-- Idempotent: re-running the migration / maintenance job is safe.
CREATE TABLE IF NOT EXISTS <schema_name>.<table>_2026_07
    PARTITION OF <schema_name>.<table>
    FOR VALUES FROM ('2026-07-01 00:00:00+00') TO ('2026-08-01 00:00:00+00');

-- Verify the child is actually attached to the correct parent with
-- the expected bounds. IF NOT EXISTS only guards the name; it does
-- not verify bounds.
DO $$
DECLARE
    v_parent   REGCLASS := '<schema_name>.<table>'::REGCLASS;
    v_child    REGCLASS := '<schema_name>.<table>_2026_07'::REGCLASS;
    v_expected TSTZRANGE := tstzrange('2026-07-01 00:00:00+00',
                                      '2026-08-01 00:00:00+00',
                                      '[)');
BEGIN
    IF (SELECT inhparent FROM pg_inherits WHERE inhrelid = v_child)
       IS DISTINCT FROM v_parent THEN
        RAISE EXCEPTION 'partition % is not attached to %',
            v_child::text, v_parent::text;
    END IF;
    IF NOT (SELECT relpartbound FROM pg_class WHERE oid = v_child)
              = v_expected THEN
        RAISE EXCEPTION 'partition % has unexpected bounds', v_child::text;
    END IF;
END $$;
```

### 6. Naming convention

| Cadence | Child name pattern | Example |
|---------|--------------------|---------|
| Daily | `<table>_YYYY_MM_DD` | `locations_2026_07_29` |
| Weekly | `<table>_YYYY_wNN` | `delivery_state_history_2026_w30` |
| Monthly | `<table>_YYYY_MM` | `audit_events_2026_07` |
| Yearly | `<table>_YYYY` | `ride_history_entries_2026` |

The name is informational only; the **bounds** in `FOR VALUES FROM (…) TO (…)`
are the source of truth. Always emit the verification step after
`CREATE TABLE IF NOT EXISTS`.

### 7. Maintenance-job contract

Every service that owns a partitioned table runs a **service-owned
scheduled job** with these properties:

- **Schedule**: daily at `02:00 UTC` (before peak ingest).
- **Advisory lock**: `pg_try_advisory_xact_lock(<schema_hash>, <table_hash>)`
  to ensure only one instance runs at a time across the replica set.
- **Pre-create loop**: for each missing period in the next N
  complete future periods, run the idempotent child DDL from §5.
- **Verify loop**: after pre-create, assert `now()` falls in an
  existing partition. If not, page on-call.
- **Drop loop**: for each period older than the retention window
  AND with homogeneous retention class AND no legal hold, archive
  the data (e.g. to S3 per §12), then `DETACH PARTITION … CONCURRENTLY`
  followed by `DROP TABLE …`.
- **Retry**: DDL retries with exponential backoff (3 attempts, 1 s /
  4 s / 16 s). Persistent failure pages on-call.
- **Metrics**: `partition_maintenance_runs_total`,
  `partition_maintenance_failures_total`,
  `partition_maintenance_lag_seconds`,
  `partition_count{result="future"}`, `partition_count{result="past"}`.
- **Alert**: critical if today's partition is missing; warning if
  the future-partition count drops below the configured horizon;
  warning if the expired-partition count exceeds the configured
  backlog.
- **Optional event**: emit `audit.partition.maintained.v1` with
  `{schema, table, created, dropped, ts}` for cross-service auditing.

### 8. Retention enforcement and mixed retention

A child partition is droppable only when **all** rows in it have
the same retention class AND none are on legal hold.

- For tables with a single retention class
  (`payment.payment_attempts`, `wallet.transactions`,
  `ledger.postings`): the entire partition is droppable when its
  upper bound is older than the retention window.
- For tables with mixed retention classes
  (`audit.events` has `retention_class IN ('financial', 'default')`):
  partitions must NOT mix retention classes across rows. Services
  with mixed retention MUST subpartition by retention class
  (`PARTITION BY LIST (retention_class)` with sub-partitions of
  `PARTITION BY RANGE (created_at)`), OR move rows with the
  shorter retention class into a separate parent
  (`audit.events_default`), OR drop only the rows whose retention
  has expired before dropping the partition.
- For tables with litigation/legal hold: held rows must be moved
  to a separate hold store before any drop; the maintenance job
  checks the `litigation_hold` flag (or equivalent) before issuing
  a drop.

### 9. Outbox policy

- The default is **unpartitioned**. Outbox tables are short-lived:
  rows are purged 24 h after `published_at` by a poller.
- Partition the outbox only when the measured backlog exceeds a
  documented threshold (e.g. > 10 M unpublished rows during a
  Kafka outage). Doing so requires an ADR and the canonical
  template above.
- Retention rows that say "outbox purged by partition drop" while
  the DDL is unpartitioned are incorrect; either remove the
  "partition drop" wording or add the partitioning.

### 10. Reconciliation with per-service docs

The following platform-wide retention statements are the canonical
values; any per-service doc that disagrees MUST reconcile to these:

| Data class | Canonical retention | How enforced |
|------------|---------------------|--------------|
| Driver location trail | 48 h | partition drop (daily partitions) |
| Courier location trail | 30 d | partition drop (daily partitions) |
| Audit events (financial) | 7 y | partition drop (monthly partitions, after legal-hold check) |
| Audit events (default) | 1 y | partition drop (monthly partitions) |
| Ledger postings | 10 y | partition drop (monthly partitions, legal-hold aware) |
| Payment attempts | 7 y | partition drop (monthly partitions) |
| Wallet transactions | 7 y | partition drop (monthly partitions) |
| Notification deliveries | 1 y | partition drop (monthly partitions; body nulled at 90 d) |

### 11. Testing

Service tests must cover:

- INSERT into a partitioned parent lands in the correct child.
- A timestamp on a partition boundary (`2026-07-31 23:59:59.999+00`
  vs `2026-08-01 00:00:00+00`) routes to the expected child.
- The maintenance job is safe to run twice in the same window
  (`CREATE TABLE IF NOT EXISTS` is a no-op the second time).
- The maintenance job's drop loop respects legal hold.
- Future-partition count recovers after the job runs.
- `EXPLAIN` shows partition pruning for the hot queries.

## JSONB

- Use for **truly schemaless** fields: provider response payloads,
  feature flag evaluation context, configuration payloads.
- **Do NOT** use for fields that are queried in WHERE clauses often
  (use proper columns + indexes).
- For required fields, prefer columns over JSONB.

## Data Retention

| Data class | Retention | Deletion |
|------------|-----------|----------|
| Trip, FoodOrder | 7 years (financial) | Soft delete, then hard delete |
| Payment | 7 years (financial) | Soft delete, then hard delete |
| Ledger | 10 years | Append-only; never deleted |
| Driver location | 30 days hot, then aggregated to hourly cells for 1 year | Partition drop |
| Courier location | 30 days hot, then aggregated to hourly cells for 1 year | Partition drop |
| Audit events | 7 years (financial) / 1 year (others) | Partition drop |
| Notification deliveries | 90 days | Partition drop |
| Session/refresh tokens | Until expiry + 7 days | Hard delete |
| KYC documents | Until account closure + 5 years | Hard delete with audit |
| Failed login attempts | 90 days | Hard delete |
| Cart (abandoned) | 30 days | Hard delete |

## Soft Delete

- Used for entities with audit/regulatory needs: Customer, Driver,
  Courier, Merchant, Restaurant, Vehicle, Trip, FoodOrder, Payment.
- `deleted_at TIMESTAMPTZ NULL`.
- All read queries include `WHERE deleted_at IS NULL` (enforced by
  repository pattern, not by view).
- Hard delete is a separate, batched, audited operation; used only
  after retention.

## Geospatial (PostGIS)

- Used in:
  - `geolocation-service` for geocoding cache and geofence joins.
  - ``geolocation-service` (zones)` for service zone polygons.
  - ``driver-service` (location)` and ``courier-service` (tracking)` for
    nearest-driver queries (with `ST_DWithin` over a recent trail).
  - ``customer-service` (addresses)` for normalized point storage.
- Schema: `geometry(Point, 4326)` for points,
  `geometry(Polygon, 4326)` for zones.
- Index: `GIST` on geometry columns.
- Distance queries: `ST_DWithin(geog, geog, distance_meters)`.
- Avoid `ST_Distance` for filtering (no index use); use `ST_DWithin`.

## High-Frequency Writes — Driver and Courier Location

- The default schema is wrong for location: a write per second per
  driver × 10k drivers is 10k writes/s. We use a dedicated schema:
  - `driver_location.locations` and `courier_tracking.locations` are
    range-partitioned by day.
  - A separate `current_location` table holds the last known position
    per driver/courier (UPSERT by `id`).
  - The "stream" view joins the two for "where is X right now + last
    5 minutes."
  - Writes are also forwarded to Kafka (`driver.location.updated.v1`)
    for consumers; the database is the source of truth, Kafka is the
    propagation.

## Read Replicas

- Each Tier-1 service MAY have ≥ 1 read replica in the same region
  for read-heavy queries (e.g. ``trip-service` (history)`).
- Replicas are managed by the platform's DBA; failover is automated.
- The application MUST tolerate read-replica lag (typically < 1s); the
  service code may pin a read to the primary when strong consistency
  is required.

## Backup and Recovery

- Continuous WAL archiving.
- Nightly full snapshot (pgBackRest).
- 7-day PITR for all services.
- 30-day PITR for Tier-1 services.
- Quarterly restore drill in staging.

## Connection Management

- Per-service connection pool sized to the service's concurrency.
- PgBouncer in front of PostgreSQL for connection multiplexing.
- Statement timeout: 30s default; tighter per service where needed.
- Idle-in-transaction timeout: 60s.

## Observability

- `pg_stat_statements` enabled; top-20 slowest queries reviewed weekly.
- Slow query log threshold: 200ms.
- Per-service metric: `db.query.duration`, `db.connections.in_use`,
  `db.transactions.open`.
- Locks and lock waits surfaced via `pg_locks`.

## Anti-Patterns Explicitly Avoided

- `SELECT *` in production code.
- N+1 queries (use eager loading or batched IN queries).
- Cross-service joins (use API composition or denormalized read
  models).
- Long-running transactions (> 5s); split into smaller units.
- Using the DB for messaging — that's what outbox + Kafka are for.
- Storing application-level "is_deleted" booleans instead of
  `deleted_at` timestamps.
- UUIDv4 only because "we always did it" — use UUIDv7 for new services.