# ADR-0016: Service Domain Consolidation (58 → 44)

- Status: Superseded by [ADR-0017](0017-20-service-architecture.md)
- Date: 2026-08-05
- Authors: Platform Architecture
- Deciders: Architecture Review Board
- Tags: consolidation, bounded-context, domains, ride, food, courier, payment

> **Superseded by ADR-0017.** This ADR was a half-step that reduced
> 58 → 44 by absorbing 14 operational satellites into 5 survivors.
> ADR-0017 supersedes it with the final 20-service catalog (38
> directories removed). The migration hub
> ([`../MIGRATION_HUB.md`](../../MIGRATION_HUB.md)) records both
> stages.

## Context and Problem Statement

The platform's microservices map grew to **58 services**, of which 14
were operational satellites around three concentrated domains —
**ride hailing drivers**, **food couriers**, and **payments** — that
do not carry their own bounded context. Each satellite brings its own
PostgreSQL schema, its own outbox/inbox, its own Kafka consumer group,
its own RBAC client, its own deployment unit, and a non-trivial
amount of cross-service chatter (mostly synchronous calls that exist
only because the satellites were separate processes).

The cost of the 58-service design is now measurably higher than the
benefit:

- Operational cost: 14 extra deploy pipelines, 14 extra replica sets,
  14 extra sets of on-call runbooks, 14 extra schema migrations to
  coordinate across the release train.
- Cognitive cost: a small number of platform engineers must reason
  about ownership of overlapping concepts (driver online state vs.
  driver location vs. driver incentive vs. driver earnings — all
  today are stored in 4 services that only ever share one database
  user).
- Correctness cost: the satellite services often need synchronous
  calls to the parent for single-fact reads, which shows up as
  `dispatch-service → driver-availability-service → driver-service`
  in the request trace. Circuit breakers help, but the failure modes
  are real and the latency is paid on the hot path.
- Coupling cost: the satellite services change schema at the same
  cadence as the parent, ship together, and are operated by the same
  team. The single-process boundary is not delivering decoupling.

The decision is whether to keep 58 services, merge the 14 satellites
into their natural parents (creating a 44-service catalog), or adopt
some intermediate reorganization.

## Decision Drivers

- Reduce per-feature change latency (fewer services to update for a
  single domain change).
- Preserve **bounded contexts** that genuinely carry separate
  release cadences or team ownership — keep those services independent.
- Preserve the platform's hard invariants: double-entry truth in
  `ledger-service`, the 46-gateway registry owned exclusively by
  `payment-service`, the immutable notification snapshot chain, the
  SUPER_ADMIN break-glass role, the canonical four-layer accounting
  model, and the platform partitioning conventions.
- Maintain **deep-link compatibility** for at least six months: every
  external URL, event topic, schema reference, and code path that
  callers depended on must continue to resolve to something
  authoritative.
- No breaking change to the Saga pattern, the Outbox pattern, or
  the per-service Postgres schema rule.
- Active service count must drop to **44**, with the 14 removed
  suites replaced by **appended sections** inside their parent
  service folders.

## Considered Options

- Option A — Keep all 58 services.
- Option B — Merge the 14 satellites into the 5 natural parents
  (chosen).
- Option C — Restructure into 7 new domain-aggregated services with
  new names.

## Decision Outcome

Chosen option: **Option B — "merge the 14 satellites into the 5
natural parents"**, because the satellites do not own independent
bounded contexts, the parents already carry the aggregate root, and
the merger preserves every public contract through compatibility
aliases for at least six months.

### Survivor mapping (14 → 5)

| Removed service | Absorbing service | Capability absorbed |
|-----------------|-------------------|---------------------|
| `courier-dispatch-service` | `courier-service` | courier matching, assignment ledger, batched offers, no-courier handling |
| `courier-tracking-service` | `courier-service` | high-frequency courier location stream, `courier_location` schema, curated `courier.location.updated.v1` |
| `dispatch-service` | `driver-service` | ride matching, match-attempt ledger, offer/accept/expire flow, fairness |
| `driver-availability-service` | `driver-service` | driver online state machine, current shift, accepted ride types, current zone |
| `driver-location-service` | `driver-service` | high-frequency driver location stream, `driver_location` schema, curated `driver.location.updated.v1` |
| `driver-incentive-service` | `driver-service` | quests, bonuses, surge guarantees, eligibility (operational capability only) |
| `restaurant-order-mgmt-service` | `food-order-service` | restaurant-side queue, accept/reject timer, prep state, ready signal |
| `wallet-service` | `payment-service` | wallet balance, holds, top-ups, statement |
| `ride-payment-integration-service` | `payment-service` | ride payment saga orchestration |
| `food-payment-integration-service` | `payment-service` | food payment saga orchestration |
| `courier-earnings-service` | `payment-service` | courier earnings ledger, withdrawals |
| `driver-earnings-service` | `payment-service` | driver earnings ledger, withdrawals |
| `restaurant-settlement-service` | `payment-service` | merchant payable, payout runs, disputes |
| `restaurant-staff-service` | `restaurant-service` | staff invitations, role assignments, devices |

### What stays independent (preserved invariants)

- `ledger-service` — immutable double-entry truth.
- `pricing-service` — pricing engine.
- `tax-service` — jurisdiction rules.
- `fraud-risk-service` — risk scores / blocklists.
- `reporting-service` — materialised read models.
- `notification-service` — immutable notification snapshot chain.
- `configuration-service` / `feature-flag-service` / `file-service`
  / `audit-service` / `analytics-service` / `support-service` /
  `admin-service` / `communication-gateway-service` — shared platform
  services (kept; SUPER_ADMIN break-glass unchanged).
- `delivery-service` — delivery aggregate (delivery is its own bounded
  context, distinct from courier and food).
- `trip-service` — trip aggregate (trip is its own bounded context,
  distinct from ride-request and driver).
- `ride-request-service` — ride-request aggregate.
- `scheduled-ride-service`, `ride-safety-service`, `ride-history-service`,
  `eta-routing-service`, `vehicle-service` — keep.

### Compatibility aliases (≥ 6 months)

For at least six calendar months from acceptance:

- The old service paths under
  `docs/services/<removed-service>/README.md` resolve via the
  migration hub (`docs/MIGRATION_HUB.md`) and from each survivor's
  "Removed predecessor capability" appendix. Deep links to
  `<removed-service>/<file>.md` continue to work via the hub's
  redirect table.
- Every event produced by a removed service is now produced by the
  absorbing service under the **same topic name and the same schema
  version** for the compatibility window. New event versions may be
  added; old topics remain published for at least six months.
- Old REST endpoint paths
  (`/v1/dispatches`, `/v1/locations`, `/v1/wallets`,
  `/v1/ride-payments`, `/v1/food-payments`, etc.) are mounted on the
  absorbing service as 301/308 redirects to the canonical path under
  the absorbing service's base URL.
- Database schemas belonging to removed services are renamed into
  the absorbing service's schema (`courier_tracking` → `courier`;
  `driver_location` → `driver`; `driver_availability` → `driver`;
  `driver_incentive` → `driver`; `dispatch` → `driver`;
  `courier_dispatch` → `courier`; `wallet` → `payment`;
  `ride_payment_integration` → `payment`;
  `food_payment_integration` → `payment`;
  `courier_earnings` → `payment`; `driver_earnings` → `payment`;
  `restaurant_settlement` → `payment`;
  `restaurant_order_mgmt` → `food_order`; `restaurant_staff` →
  `restaurant`) and the old schema names remain readable as views
  for the compatibility window.

### Accounting four-layer model preserved

The merger **does not collapse the four-layer accounting model**:

1. Customer wallet → now owned by `payment-service` (was `wallet-service`).
2. Provider side → unchanged, `payment-service` (same team, same SLA).
3. Double-entry ledger → unchanged, `ledger-service` (independent).
4. Settlement → now owned by `payment-service` (was
   `restaurant-settlement-service`).

### Partitioning conventions preserved

Every partitioned table moves with its owning capability:

- `courier_tracking.locations` (RANGE on `recorded_at`, monthly) →
  `courier.locations`.
- `driver_location.locations` (RANGE on `recorded_at`, monthly) →
  `driver.locations`.
- `courier_dispatch.assignments` (RANGE on `assigned_at`, monthly) →
  `courier.assignments`.
- `dispatch.match_attempts` (RANGE on `started_at`, monthly) →
  `driver.match_attempts`.
- `wallet.ledger_entries` (RANGE on `created_at`, monthly) →
  `payment.wallet_entries`.
- `driver_earnings.earnings` / `courier_earnings.earnings` (RANGE on
  `accrued_at`, monthly) → `payment.driver_earnings` /
  `payment.courier_earnings`.
- `restaurant_settlement.payouts` (RANGE on `scheduled_for`, monthly)
  → `payment.merchant_payouts`.

Pre-creation depth, retention, naming convention, and the
service-owned maintenance-job contract are unchanged.

### Immutable notification snapshot chain

The notification snapshot chain is **append-only and appends across
all topics**. `notification-service` continues to receive
`payment.captured.v1`, `payment.refund.completed.v1`,
`delivery.completed.v1`, `trip.completed.v1`, `food.order.*.v1`,
`driver.incentive.earned.v1`, `courier.incentive.earned.v1` — only
the producer changes from the removed service to the survivor.

### SUPER_ADMIN break-glass

`admin-service` keeps the `SUPER_ADMIN` role; the break-glass path
calls the survivor service, not the removed service.

### Gateway registry

The 46-gateway registry remains the single source of truth owned by
`payment-service`; the registry file
`services/payment-service/GATEWAYS.md` is unchanged.

### Consequences

- Good: 14 fewer services to deploy, monitor, paginate, and on-call.
- Good: one DB schema per parent domain → simpler migration story.
- Good: smaller blast radius for changes — feature work touches one
  service.
- Bad: a single team's surface area grows. Mitigated by appending
  capability sections inside the survivor's docs and by the
  migration hub.
- Bad: short-term reconciliation overhead while aliases run in
  parallel. Mitigated by the six-month compatibility window and the
  ADR-indexed migration hub.
- Neutral: every removed directory is **deleted** after its content
  has been absorbed into a survivor's appendix and the migration hub
  (per the user's revised retirement policy: delete, do not retain
  as a retired suite).

### Confirmation

- `MICROSERVICES_MAP.md` service count reads **44**.
- `docs/MIGRATION_HUB.md` exists and links every removed capability
  to its absorbing section.
- All removed-service paths resolve through the hub.
- Survivor suites carry a "Removed predecessor capability"
  appendix with the absorbed schemas, events, and endpoints.
- Six-month compatibility window observed for old event topics,
  old REST paths, and old schema names.
- One `git grep` for any of the 14 removed names returns hits only
  in the migration hub, the ADR, history-style references in
  surviving docs, and the surrounding architecture catalog (i.e.
  no orphan operational references).

## Pros and Cons of the Options

### Option A — Keep all 58 services

- Good: zero migration cost.
- Bad: the 14 satellites do not pay for themselves; they only
  carry schema, runbook, and on-call cost.

### Option B — Merge the 14 satellites into the 5 parents

- Good: simplest bounded-context alignment.
- Good: reduces the deployment surface area by 14.
- Bad: short-term reconciliation overhead.

### Option C — Restructure into 7 new domain-aggregated services

- Good: even larger consolidation.
- Bad: requires renaming and breaks every external contract;
  the migration cost is higher than the merger benefit because the
  five parents already exist and are stable.

## References

- [`../MIGRATION_HUB.md`](../../MIGRATION_HUB.md) — the single
  authoritative map from removed service → survivor capability.
- [`../MICROSERVICES_MAP.md`](../MICROSERVICES_MAP.md) — 44-service
  catalog.
- [`../SERVICE_ISOLATION.md`](../SERVICE_ISOLATION.md) — failure
  isolation contracts (unchanged for survivors).
- [`../DATA_OWNERSHIP.md`](../DATA_OWNERSHIP.md) — schema
  re-ownership table.
- [`../EVENT_ARCHITECTURE.md`](../EVENT_ARCHITECTURE.md) — outbox
  / inbox compatibility window.
- [`../../shared/PLATFORM_BASELINE.md`](../../shared/PLATFORM_BASELINE.md)
  — Postgres 18, Kafka, Keycloak, Redis, OpenTelemetry, Vault,
  deployment, DR baseline.
- ADR-0001 — microservices architecture.
- ADR-0002 — postgres per service (preserved; schemas consolidated
  by rename-into-parent, not by elimination).
- ADR-0013 — double-entry ledger (preserved; ledger-service stays
  independent).