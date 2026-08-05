## Plan: Add `PARTITION BY` + idempotent maintenance to every service

### Goal
Ensure every `docs/services/*/` doc set has (a) a `PARTITION BY` clause on every table whose shape is time-ordered or append-mostly, (b) `CREATE TABLE IF NOT EXISTS … PARTITION OF …` code so partitions can be safely pre-created, and (c) the operational workflow that maintains them. Establish a canonical template in platform-wide docs so all 58 services follow the same convention.

### Scope (driven by your answers)
- **Which tables get partitioned**: every table with `created_at` / `occurred_at` / `recorded_at` / `posted_at` / `received_at` / `completed_at` / `accrued_at` / `changed_at` / `placed_at` / `probed_at` (append-mostly by time). CRUD aggregates (customers, drivers, merchants, vehicles, addresses, menus, branches, …) stay unpartitioned, **but** every service's ERD must say so explicitly.
- **Doc locations to update**: `ERD.md`, `TECH.md`, `WORKFLOWS.md` for every service that owns a partitioned table; ERD-only updates for the services whose only decision is "no partitioning, here's why."
- **Platform-wide docs**: add the canonical partitioning template to `architecture/DATABASE_ARCHITECTURE.md` and a baseline entry to `shared/PLATFORM_BASELINE.md`.

### 1. Canonical template (added once, referenced everywhere)

Insert into `docs/architecture/DATABASE_ARCHITECTURE.md` (replacing the thin "High-Volume Tables — Partitioning" section):

- **Eligibility rules** — append-mostly, time-filtered queries, large size, finite retention, drop beats delete.
- **Approved cadence decision table** — daily for hot trails, weekly for state histories at > 1k rows/s, monthly for event/audit/financial logs, yearly for long-lived read models.
- **Parent DDL template** with `PARTITION BY RANGE (<utc_ts>)` and composite PK `(id, <partition_key>)`.
- **Child DDL template** using `CREATE TABLE IF NOT EXISTS <schema>.<parent>_<period> PARTITION OF <schema>.<parent> FOR VALUES FROM (…) TO (…)` plus a verification step (`pg_partition_tree` / `pg_inherits`).
- **Naming convention** — `_YYYY_MM_DD`, `_YYYY_wNN`, `_YYYY_MM`, `_YYYY`.
- **Pre-creation horizon** — N complete future periods; daily = 30 days, monthly = 12 months, yearly = 2 years.
- **Maintenance-job contract** — service-owned scheduled job, advisory lock, create→verify→archive→detach/drop sequence, retries, metrics, alert names.
- **Mixed-retention handling** — same time partition may not mix `financial` (7 y) and `default` (1 y) classes; the audit-service retention mismatch is explicitly fixed.
- **Default partition policy** — strictly optional; if used, must be drained by the next maintenance run.
- **Outbox policy** — unpartitioned by default; purged by poller; only partition on measured backlog threshold.

Insert into `docs/shared/PLATFORM_BASELINE.md` (new row in §2 Data baseline):

> | Table partitioning for high-volume append-mostly tables | Declarative `RANGE` by UTC timestamp; see canonical template in `DATABASE_ARCHITECTURE.md`; maintenance is a service-owned scheduled job | — |

### 2. Per-service updates — phased by current state

For each of the 58 services, I'll edit only the files that need updating and only the sections that are missing/stale. Categories:

**A. Already-partitioned, ERD/TECH/WORKFLOWS consistent** (≈20 services: audit, ledger, driver-location, courier-tracking, ride-history, notification, food-order, loyalty, fraud-risk, promotion, review-rating, tax, geolocation, restaurant-settlement, wallet, payment, analytics, configuration, feature-flag, communication-gateway): 
- Normalize DDL to use `CREATE TABLE IF NOT EXISTS … PARTITION OF …` and composite PK `(id, <partition_key>)`.
- Add `IF NOT EXISTS` everywhere a child partition is named.
- Fix the few known PK/partition-key inconsistencies (audit.events, audit.read_log, loyalty.transactions, loyalty.tier_history, loyalty.audit_log).
- Reconcile ride-history-service ERD (yearly) vs TECH (monthly) — pick yearly per volume, update TECH.
- Add the explicit "partition drop" line to WORKFLOWS.md if missing.

**B. Time-stamped tables but no partitioning doc** (≈10 services: dispatch-service, driver-availability-service, driver-incentive-service, eta-routing-service, pricing-service, ride-payment-integration-service, ride-safety-service, scheduled-ride-service, courier-dispatch-service, courier-earnings-service): 
- Add `PARTITION BY RANGE (<ts>)` to ERD.md parent tables (`availability_history`, `assignment_ledger`, `saga_steps`, `safety_*`, `assignment_*`).
- Add §9 Partitioning section + §10 Data Retention table.
- Add the "Daily/Monthly Partition Maintenance" workflow in WORKFLOWS.md.
- Add the cadence line to TECH.md §3 Data layer.

**C. Explicitly "No table is partitioned"** (12 services: address, branch, cart, checkout, inventory, menu, merchant, restaurant-order-mgmt, restaurant, restaurant-staff, user-profile, vehicle): 
- No code change. Verify the existing §9 still says "no partitioning, with reason." Make the reason concrete (volume + retention + access pattern).
- Add a one-line TECH.md §3 annotation: "**Partitioning**: none (justified: <reason>)." so the audit catalogue is consistent.

**D. Mixed/unclear** (services with empty Partitioning sections — api-gateway, dispatch, driver-availability, driver-incentive, eta-routing, pricing, ride-payment-integration, ride-safety, scheduled-ride): same as B.

### 3. Required edits inside each file

**ERD.md** — for every partitioned parent:
- `PARTITION BY RANGE (<ts>)` clause at the end of the `CREATE TABLE`.
- Composite PK `(id, <partition_key>)` instead of single-column `id UUID PRIMARY KEY`.
- A child example using `CREATE TABLE IF NOT EXISTS … PARTITION OF … FOR VALUES FROM (…) TO (…)`.
- §9 Partitioning section: table → strategy → cadence → retention → maintenance-owner.

**TECH.md** — §3 Data layer:
- One-line cadence annotation: "monthly RANGE partitions on `<col>`", "daily RANGE partitions on `<col>`", or "no partitioning (justified: …)".

**WORKFLOWS.md** — new § "`<Cadence>` Partition Maintenance" (only when the service owns partitioned tables):
- Objective, schedule (`02:00 UTC`), participating service, prerequisites.
- Mermaid sequence: pre-create loop → drop loop.
- Alternate / failure paths.
- Business rules (horizon, archive before drop).
- Optional `audit.partition.maintained.v1` emission.

### 4. Execution order
1. Update `docs/architecture/DATABASE_ARCHITECTURE.md` (canonical template + retention reconciliation).
2. Update `docs/shared/PLATFORM_BASELINE.md` (one new row).
3. Group A: 20 already-partitioned services — fix DDL (`IF NOT EXISTS`, composite PK), reconcile TECH/ERD inconsistencies, ensure WORKFLOWS has the maintenance section.
4. Group B/D: ~10 services with time-stamped tables but missing partitioning — add `PARTITION BY`, §9, §10, WORKFLOWS maintenance, TECH annotation.
5. Group C: 12 unpartitioned services — TECH.md annotation + verify ERD §9 still has the explicit "no partitioning" justification.

### 5. Deliverable per service
- `ERD.md` — updated DDL with `PARTITION BY` + composite PK + `IF NOT EXISTS` children + §9 Partitioning.
- `TECH.md` — cadence annotation in §3.
- `WORKFLOWS.md` — maintenance workflow (only when partitioned).
- `architecture/DATABASE_ARCHITECTURE.md` — canonical template.
- `shared/PLATFORM_BASELINE.md` — baseline row.

### 6. Things I will NOT do
- Will not introduce `LIST` or `HASH` partitioning (none exists in current docs; not requested).
- Will not change retention numbers except to reconcile documented contradictions in the canonical template.
- Will not modify the outbox partitioning policy (default: unpartitioned).
- Will not change any unrelated doc section.

### 7. Risk & reversibility
- Edits are scoped to Partitioning / Data layer / Data Retention / Maintenance sections; the rest of each file is untouched.
- All changes are documentation; no migration is run.
- A single platform template means a follow-up fix in one place propagates by reference, not by re-editing 58 files.
- Inconsistencies found (audit retention-class mix, ride-history cadence) are reconciled in the canonical template + the affected ERD/TECH, not silently propagated.

### 8. What you'll review
After implementation I'll show:
- The diff of `DATABASE_ARCHITECTURE.md` (canonical template) and `PLATFORM_BASELINE.md` (baseline row).
- A coverage table: 58 services × status (already-partitioned / newly-partitioned / explicitly-not-partitioned) × sections updated.
- A list of every consistency fix made (PK additions, `IF NOT EXISTS` added, retention conflicts resolved).