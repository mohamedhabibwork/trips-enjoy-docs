# ADR-0029: Partition maintenance cron schedule (canonical `0 0 2 * * *`)

- Status: Accepted
- Date: 2026-08-15
- Authors: Platform Architecture Team
- Deciders: Architecture Review Board
- Tags: partitioning, scheduling, observability, platform

> **Catalog revision (2026-08-15, appended per append-not-renumber):**
> this ADR locks the platform-wide canonical cron schedule for
> partition maintenance. Every Kotlin service's
> `PartitionMaintenanceJob` MUST run at `0 0 2 * * *` (02:00 UTC
> daily), matching `pg_cron` and the existing
> [`shared/PARTITION_FUNCTIONS.md`](../shared/PARTITION_FUNCTIONS.md)
> contract. The 9 services that ship a local `PartitionMaintenanceJob`
> adopt the platform `PartitionMaintenanceStarter`; their cron
> expressions are aligned.

## Context and Problem Statement

The [`shared/PARTITION_FUNCTIONS.md`](../shared/PARTITION_FUNCTIONS.md)
declares the canonical PL/pgSQL functions
(`partman.ensure_partitions`, `partman.drop_expired_partitions`,
`partman.partition_health`) and the canonical schedule:

> `pg_cron @ 02:00 UTC` + Spring `@Scheduled` fallback.

But 9 of 14 Kotlin services ship a local `PartitionMaintenanceJob`
with one of three distinct cron expressions:

| Service | Cron | UTC equivalent |
|---|---|---|
| `audit-service` | `0 0 2 * * *` | 02:00 UTC ✓ |
| `configuration-service` | `0 0 2 * * *` | 02:00 UTC ✓ |
| `customer-service` | `0 0 2 * * *` | 02:00 UTC ✓ |
| `identity-service` | `0 0 3 * * *` | 03:00 UTC ✗ |
| `ledger-service` | `0 0 2 * * *` | 02:00 UTC ✓ |
| `notification-service` | `0 0 1 * * *` | 01:00 UTC ✗ |
| `driver-service` | `0 0 2 * * *` | 02:00 UTC ✓ |
| `payment-service` | `0 0 2 * * *` | 02:00 UTC ✓ |

The audit at [`shared/PLATFORM_DRY_AUDIT.md` §6 ADR-0025](../../shared/PLATFORM_DRY_AUDIT.md)
flagged this drift. The contract is load-bearing: partition
maintenance is a global platform operation that runs against all
21 services' databases. If services run at different times, a
monitoring dashboard that aggregates "services with healthy
partitions" gets misleading results.

## Decision Drivers

- **Single global schedule.** All 21 services run partition
  maintenance at the same UTC time so monitoring can aggregate.
- **`pg_cron` alignment.** 02:00 UTC matches the
  [`shared/PARTITION_FUNCTIONS.md`](../shared/PARTITION_FUNCTIONS.md)
  contract and the production `pg_cron` schedule.
- **Off-peak hour.** 02:00 UTC is the lowest-traffic hour across
  the platform's regional deployments (NA, EU, MENA).

## Considered Options

1. **`0 0 2 * * *`** (02:00 UTC daily; platform canonical)
2. **`0 0 3 * * *`** (03:00 UTC; `identity-service` only)
3. **`0 0 1 * * *`** (01:00 UTC; `notification-service` only)
4. **Per-service schedules; no canonical** (rejected — defeats
   the platform-wide monitoring)

## Decision Outcome

**Chosen option: option 1, `0 0 2 * * *`.**

- Every Kotlin service's `PartitionMaintenanceJob` runs at
  02:00 UTC daily.
- The platform `PartitionMaintenanceStarter` (Phase D) reads the
  schedule from `platform.partition.cron` with default
  `0 0 2 * * *`.
- The Spring `@Scheduled` annotation is replaced with the
  platform component, eliminating 9 redundant job files.

### Consequences

**Good:**
- Single canonical cron across 9 services
- Monitoring dashboards (`platform.partition.health` Prometheus
  gauge) aggregate uniformly
- `pg_cron` alignment means the PL/pgSQL functions and the Spring
  fallback run at the same time
- 9 redundant `PartitionMaintenanceJob.kt` files deleted
  (~450 LOC, Phase D)

**Bad:**
- 2 services (`identity-service`, `notification-service`) must
  update their cron to 02:00 UTC. The change is a single-line
  configuration tweak (`@Scheduled(cron = "0 0 2 * * *")`).
- A single coordinated restart window is required (all 9 services
  must redeploy within a 1-hour window to avoid a 1-hour gap
  where some services run at 02:00 and others still run at 01:00
  or 03:00). Rollout plan: rolling restart in 30-minute windows.

### Follow-up

- [ ] Update `identity-service`'s `application.yml` (or its
  `@Scheduled` annotation) to use `0 0 2 * * *`.
- [ ] Update `notification-service`'s `application.yml` to use
  `0 0 2 * * *`.
- [ ] Add `platform.partition.cron` property to
  `platform-spring-boot-partition` (Phase D) with default
  `0 0 2 * * *`.
- [ ] Update `platform/k8s/services/*.yaml` `PrometheusRule` to
  alert on `time() % 86400 == 7200` (02:00 UTC) consistently.

## Pros and Cons of the Options

### `0 0 2 * * *` (chosen)

Matches `pg_cron`, matches the canonical contract, off-peak hour
for the platform's three regional deployments.

### `0 0 3 * * *` (identity-service)

One hour later. No documented reason for the deviation; rejected
because the canonical schedule must win.

### `0 0 1 * * *` (notification-service)

One hour earlier. Same — rejected because the canonical schedule
must win.

## References

- [`shared/PARTITION_FUNCTIONS.md`](../shared/PARTITION_FUNCTIONS.md)
  — the canonical PL/pgSQL contract (where 02:00 UTC is declared)
- [`shared/PLATFORM_DRY_AUDIT.md` §6 ADR-0025](../../shared/PLATFORM_DRY_AUDIT.md)
  — the audit that flagged this drift
- [`shared/PLATFORM_BASELINE.md`](../shared/PLATFORM_BASELINE.md)
  — platform-wide scheduling baseline
- [`platform/k8s/`](../platform/k8s/) — K8s manifests where the
  per-service `PrometheusRule` is defined
