# ADR-0002: PostgreSQL 18 with One Schema per Service

- Status: Accepted
- Date: 2026-07-29
- Authors: Platform Architecture Team
- Deciders: Architecture Review Board
- Tags: database, persistence, postgres, schema, isolation

## Context and Problem Statement

The microservices architecture (ADR-0001) commits to per-service
ownership of data. The next decision is which database engine to
standardize on, how to physically/logically isolate each service's
data, and how to support the workload diversity across the 58
services: high-frequency geospatial writes (`driver-location-service`,
`courier-tracking-service`), high-consistency financial postings
(`ledger-service`), read-heavy history queries (`ride-history-service`),
and configuration polling (`configuration-service`).

The platform also needs PostGIS for geospatial queries on the same
engine that holds the operational data, and needs PITR + logical
replication to support the outbox pattern, read replicas, and the
`reporting-service` projection. A shared database (one engine, one
schema, many services) is the default failure mode; a multi-engine
zoo (MySQL for one service, MongoDB for another) is the opposite
failure mode. We need to pick the engine, the isolation unit, and the
operational rules.

## Decision Drivers

- ACID transactions are required for money (`payment-service`,
  `wallet-service`, `ledger-service`) and state machines (`trip-service`,
  `food-order-service`).
- PostGIS is needed for geospatial queries (`geolocation-service`,
  `zone-service`, `driver-location-service`). The geospatial queries
  must join with operational data (e.g. "drivers in this surge zone").
- High-frequency write workloads (10k+ writes/s on location streams)
  need partitioning, write-optimized storage, and a clear retention
  policy.
- Logical replication is needed for the outbox pattern (Debezium /
  logical decoding) and for read replicas.
- Operational maturity: 7-year retention for financial data, 30-day
  PITR for Tier-1, automated backups, restore drills.
- Per-service DB users with least privilege; no cross-schema reads.
- 58 services — engine diversity must be bounded; one engine, one set
  of operational practices, one team that knows it deeply.

## Considered Options

- **PostgreSQL 18, one schema per service** — single engine, single
  operational practice; logical isolation by schema, physical isolation
  available for the noisiest/most-critical services.
- **Shared PostgreSQL database, one schema per service** (already
  chosen above, but listed for contrast) — same engine, but no
  physical isolation at all.
- **MySQL 8 (with or without per-service instance)** — mature, but
  weaker PostGIS story, weaker JSONB, weaker window functions and
  generated columns, weaker logical replication.
- **Per-tenant database (one DB per merchant or per region)** — solves
  the wrong problem; we are multi-country but not multi-tenant in
  the data-tenancy sense.
- **Polyglot persistence (Postgres + MySQL + MongoDB + DynamoDB)** —
  freedom per service, operational complexity.

## Decision Outcome

Chosen option: "**PostgreSQL 18, one schema per service**", because
(a) it is the only engine that gives us ACID + PostGIS + logical
replication + JSONB + declarative partitioning + generated columns
on a single platform, (b) it is operationally mature and our team
has deep operational experience with it, and (c) it lets us avoid a
polyglot zoo (one engine, one set of migrations, one on-call
playbook). One schema per service is the default isolation; physical
isolation (one cluster per service) is reserved for the noisiest
workloads (`driver-location-service`, `courier-tracking-service`,
`audit-service`) where the write rate would otherwise starve other
tenants of the shared cluster.

### Consequences

- Good: One engine to operate, one set of migrations, one
  observability stack (`pg_stat_statements`, `pg_locks`,
  `pgBackRest`).
- Good: PostGIS in-database — the same engine that holds operational
  data holds the geospatial index, so we can write
  `ST_DWithin(zone.geom, driver.location, distance)` joins.
- Good: Declarative partitioning handles the location-stream
  workloads (`driver_location.locations` partition by day,
  `courier_tracking.locations` partition by day, `audit.events`
  partition by month) without operational gymnastics.
- Good: Logical replication enables Debezium-driven outbox in
  `payment-service`, `trip-service`, etc., and read replicas for
  `ride-history-service` and `reporting-service`.
- Good: Strong tooling: `pg_dump`, `pgBackRest`, `pgaudit`,
  `pg_stat_statements` — all mature, all in-house expertise.
- Bad: One engine means we cannot pick a different store for a
  genuinely different workload (e.g. time-series). (Mitigation: a
  read-only OLAP export to a separate analytics store; the operational
  store stays Postgres.)
- Bad: A noisy tenant on a shared cluster can affect others. We
  mitigate by physically isolating the noisiest services, by setting
  per-service statement timeouts (30s default), and by monitoring
  `db.connections.in_use` per schema.
- Bad: PostgreSQL has no native multi-region replication for
  transactional state. We accept this: regions own their own
  Postgres clusters, with cross-region replication limited to
  identity and configuration.
- Neutral: Each service has a migration tool of its choice
  (`golang-migrate`, `Flyway`, `dbmate`, `prisma migrate`) as long as
  migrations are versioned, forward-only, and reviewed in PRs.

### Confirmation

- All 58 services have a `migration` directory under source control
  and a `migrate` Kubernetes job that runs before the deployment.
- PITR drills succeed quarterly: restore the last 7 days from a
  service's backup, validate against a row-count check.
- `pg_stat_statements` review: top-20 slowest queries per service
  reviewed weekly; no production query over 1s without a
  documented reason.
- PostGIS adoption: every service that owns geospatial data
  (`geolocation-service`, `zone-service`, `driver-location-service`,
  `courier-tracking-service`, `address-service`) uses `GIST` indexes
  and `ST_DWithin` for filtering, not `ST_Distance`.

## Pros and Cons of the Options

### PostgreSQL 18, one schema per service

Single engine; one schema per service on a shared cluster; physical
isolation (`<service>.cluster.local`) reserved for the noisiest/
most-critical services. Schemas are owned by the service; no
service can `SELECT` from another's tables.

- Good: ACID, PostGIS, JSONB, generated columns, declarative
  partitioning, logical replication — all in one engine.
- Good: Operational maturity: `pgBackRest`, `pgaudit`,
  `pg_stat_statements`, `pg_locks` — battle-tested.
- Good: One engine, one set of operational practices; the platform
  team can carry a deep Postgres specialization.
- Good: Logical replication + Debezium enable the outbox pattern
  (ADR-0009) without a separate CDC pipeline.
- Bad: One engine caps our freedom if a service has a genuinely
  different shape (time-series, graph). We accept this and use a
  separate read model for analytics.
- Bad: A noisy tenant on a shared cluster can starve others. We
  mitigate via physical isolation for the noisiest services and
  per-service connection pool sizing.
- Bad: Operational excellence with one engine is a single point of
  failure for the team's expertise. We mitigate with a documented
  runbook and cross-training.

### Shared PostgreSQL database, all schemas in one cluster (no per-service physical isolation)

A subset of the above: same engine, but no option to physically
isolate a noisy service.

- Good: Cheapest in cluster count.
- Bad: `driver-location-service` and `audit-service` on the same
  cluster is a recipe for noisy-neighbor incidents. The
  location-stream workload is a write storm; the audit workload is
  append-mostly. We must physically separate them. So this option
  collapses to the chosen one with the "physical isolation where
  needed" exception.

### MySQL 8 (with or without per-service instance)

The other mature open-source RDBMS.

- Good: Operational maturity; many teams know it.
- Good: Per-service instance is operationally simple.
- Bad: PostGIS story is much weaker; we'd need a separate
  geospatial service or a different mapping engine.
- Bad: JSONB equivalent is weaker; no `tsvector`-equivalent in
  MySQL 8 without plugins.
- Bad: Logical replication for CDC is less mature than Postgres's
  logical decoding; Debezium support is good but not as battle-tested.
- Bad: We have stronger in-house Postgres expertise than MySQL
  expertise; switching would mean rebuilding operational muscle.

### Per-tenant database (one DB per merchant or per country)

The wrong axis of isolation.

- Good: Strong tenant isolation.
- Bad: We are not multi-tenant in the data-tenancy sense. Merchants
  do not own their data; they have a profile in the `merchant`
  schema. Per-merchant DBs would mean N databases for N merchants.
- Bad: A driver or customer who interacts with multiple merchants
  spans multiple databases; this breaks the bounded context.
- Bad: Operational cost of N×58 databases is untenable.

### Polyglot persistence (Postgres + MySQL + MongoDB + DynamoDB)

One engine per workload shape.

- Good: Right tool for the job (e.g. DynamoDB for session-like
  high-write data; Mongo for document-shaped data).
- Bad: Operational complexity. Each engine has its own backup, its
  own monitoring, its own on-call runbook.
- Bad: Cross-engine transactions are impossible; data gravity pulls
  us back to Postgres for any join-heavy query.
- Bad: 58 services × 4 engines = teams that must know 4 engines
  deeply. We do not have that headcount.

## References

- [`DATABASE_ARCHITECTURE.md`](../DATABASE_ARCHITECTURE.md) —
  schemas per service, migrations, indexing, partitioning,
  retention, PostGIS usage, PITR, connection management.
- [`MICROSERVICES_MAP.md`](../MICROSERVICES_MAP.md) — the schema
  name next to each service.
- [`CONSISTENCY_STRATEGY.md`](../CONSISTENCY_STRATEGY.md) — strong
  consistency inside a service (Postgres ACID) vs. eventual
  consistency across services.
- PostgreSQL 18 release notes — declarative partitioning,
  logical replication, JSONB improvements.
- PostGIS documentation — `GIST` indexes, `ST_DWithin`,
  `ST_Distance_Spheroid`, geography vs. geometry.
