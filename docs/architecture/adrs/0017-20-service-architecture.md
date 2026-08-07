# ADR-0017: 20-Service Architecture (Supersedes ADR-0016)

- Status: Accepted
- Date: 2026-08-05
- Authors: Platform Architecture
- Deciders: Architecture Review Board
- Tags: consolidation, bounded-context, domains, ride, food, courier, payment, architecture-revision

## Context and Problem Statement

ADR-0016 reduced the platform from 58 services to 44 by absorbing
14 operational satellites into 5 survivor services (courier,
driver, food-order, restaurant, payment). That step did not
sufficiently reduce the operational cost of the platform:

- The four **ride-hailing booking/trip/safety/history** services
  (`ride-request-service`, `trip-service`, `ride-safety-service`,
  `ride-history-service`, `scheduled-ride-service`) ship together,
  share the same team, and have no independent bounded context.
- The **customer identity** service owns only customer profile; the
  cross-persona `user-profile-service` and the `address-service`
  are the same team and ship together.
- The **food marketplace** still splits across 11 services
  (`merchant-service`, `restaurant-service`, `branch-service`,
  `menu-service`, `inventory-service`, `restaurant-staff-service`,
  `cart-service`, `checkout-service`, `food-order-service`,
  `restaurant-order-mgmt-service`, `review-rating-service` (food
  projection)) with overlapping responsibilities and synchronous
  hops in the order hot path.
- The **delivery side** still has a separate `delivery-service`
  and `courier-dispatch-service` + `courier-tracking-service`.
- The **pricing & rules** cluster (`pricing-service`, `tax-service`,
  `promotion-service`, `loyalty-service`) carries redundant
  configuration hops and shares one team.
- The **geospatial & zones** split (`geolocation-service`,
  `eta-routing-service`, `zone-service`) has the same team and
  the same deployment cadence.
- The **notification** cluster still separates
  `notification-service` from the per-channel
  `communication-gateway-service`, which doubles the deployment
  surface for what is one product (notify).
- The **admin / support / reporting / analytics / feature-flag**
  cluster is 5 services for what is operationally one platform
  console with several permissions.
- The **review-rating-service** produces three logical projections
  (trip, food, search) that already live in the consumers.

ADR-0017 collapses all of the above into a final **20-service**
catalog. ADR-0016 is superseded.

## Decision Drivers

- Final operational surface: 20 services, each owned by exactly
  one team, each carrying exactly one bounded context.
- Preserve **every** public contract for at least six calendar
  months from 2026-08-05.
- Preserve the platform's hard invariants:
  - 46-gateway registry owned by `payment-service`.
  - Double-entry ledger owned exclusively by `ledger-service`.
  - Accounting four-layer model (customer wallet, provider,
    ledger, settlement).
  - Immutable notification template-version snapshot chain
    (preserved across `notification-service` and the absorbed
    `communication-gateway-service`).
  - SUPER_ADMIN break-glass; the 20-scope `<service>.admin`
    permission preset.
  - Per-service Postgres schema (or surviving equivalent);
    partitioning conventions.
  - Outbox / inbox / saga patterns.

## Considered Options

- Option A — Keep 44 (do nothing further).
- Option B — Restructure to 7 new mega-services.
- Option C — Final 20-service architecture (chosen).

## Decision Outcome

Chosen option: **Option C — 20-service architecture**.

### Final 20-service catalog

| # | Service | Owns |
|---|---------|------|
| 1 | `api-gateway` | (stateless edge) |
| 2 | `identity-service` | Keycloak identity (unchanged) |
| 3 | `file-service` | file / media metadata (unchanged) |
| 4 | `audit-service` | immutable audit log (unchanged) |
| 5 | `configuration-service` | config + feature flags (absorbs `feature-flag-service`) |
| 6 | `customer-service` | customer profile + KYC; absorbs `user-profile-service`, `address-service`; exposes loyalty account + profile exposure |
| 7 | `driver-service` | driver profile + KYC; absorbs `driver-availability-service`, `driver-location-service`, `dispatch-service`, `driver-incentive-service`, `vehicle-service` |
| 8 | `trip-service` | trip aggregate + ride-request + scheduled-ride + ride-safety + ride-history + trip review projection; absorbs `ride-request-service`, `scheduled-ride-service`, `ride-safety-service`, `ride-history-service`, plus the trip-review slice of `review-rating-service` |
| 9 | `pricing-service` | pricing engine + tax rules + promotion / coupon rules + loyalty rule capabilities; absorbs `tax-service`, `promotion-service`, `loyalty-service` (rule capability only — see note below) |
| 10 | `restaurant-service` | restaurant + merchant + branch + menu + inventory + staff; absorbs `merchant-service`, `branch-service`, `menu-service`, `inventory-service`, `restaurant-staff-service` |
| 11 | `food-order-service` | food order + cart + checkout + kitchen queue + food review projection; absorbs `cart-service`, `checkout-service`, `restaurant-order-mgmt-service`, plus the food-review slice of `review-rating-service` |
| 12 | `courier-service` | courier profile + dispatch + tracking + delivery; absorbs `courier-dispatch-service`, `courier-tracking-service`, `delivery-service` |
| 13 | `payment-service` | payment intents + 46 gateways + ride/food sagas + wallet + driver/courier earnings + restaurant settlement + COD money; absorbs `ride-payment-integration-service`, `food-payment-integration-service`, `wallet-service`, `driver-earnings-service`, `courier-earnings-service`, `restaurant-settlement-service` |
| 14 | `ledger-service` | double-entry ledger (independent; unchanged) |
| 15 | `geolocation-service` | geocoding + zones + ETA + routing; absorbs `eta-routing-service`, `zone-service` |
| 16 | `notification-service` | templates + delivery + immutable template-version snapshot chain + the absorbed provider anti-corruption layer; absorbs `communication-gateway-service` |
| 17 | `search-service` | search index coordination (kept) |
| 18 | `fraud-risk-service` | risk scores + blocklists (kept) |
| 19 | `admin-service` | operations console + **support** as a separately permissioned module (`support.admin` scope); absorbs `support-service` |
| 20 | `reporting-service` | data warehouse ingestion + read models + exports; absorbs `analytics-service` |

> **Loyalty rules vs. loyalty account exposure.** `pricing-service`
> absorbs the *rules* (earn / burn / tier math, eligibility,
> promo-binding) and is the source of truth for the loyalty
> pricing engine. The **loyalty account** (the per-user balance and
> the earn / burn history) is owned by `customer-service` — exposed
> to the rest of the platform under the canonical `loyalty_account`
> resource and surfaced in the customer profile projection. This
> keeps the loyalty math where it belongs (pricing) and the
> customer-side state where it belongs (customer).

### Removed services (38 — directory deleted after content absorption)

The following 38 directories are removed; their unique contracts
are absorbed into the survivors above and recorded in
[`../MIGRATION_HUB.md`](../MIGRATION_HUB.md):

`address-service`, `analytics-service`, `branch-service`,
`cart-service`, `checkout-service`,
`communication-gateway-service`, `courier-dispatch-service`,
`courier-earnings-service`, `courier-tracking-service`,
`delivery-service`, `dispatch-service`,
`driver-availability-service`, `driver-earnings-service`,
`driver-incentive-service`, `driver-location-service`,
`eta-routing-service`, `feature-flag-service`,
`food-payment-integration-service`, `inventory-service`,
`loyalty-service`, `menu-service`, `merchant-service`,
`promotion-service`, `restaurant-order-mgmt-service`,
`restaurant-settlement-service`, `restaurant-staff-service`,
`review-rating-service`, `ride-history-service`,
`ride-payment-integration-service`, `ride-request-service`,
`ride-safety-service`, `scheduled-ride-service`,
`support-service`, `tax-service`, `user-profile-service`,
`vehicle-service`, `wallet-service`, `zone-service`.

### What stays independent (preserved invariants)

- `ledger-service` — immutable double-entry truth. No content
  change.
- `identity-service` — Keycloak adapter. No content change.
- `file-service` — file / media metadata. No content change.
- `audit-service` — immutable audit log. No content change.
- `api-gateway` — stateless edge. No content change.

### Review-rating split

`review-rating-service` is removed. Its three logical projections
are absorbed as **read models** owned by their respective consumer
services:

- **Trip reviews** → `trip-service` (`trip.review.read.v1`).
- **Food reviews** → `food-order-service` (`food.review.read.v1`).
- **Search reviews** → `search-service` (search index document).

The review *write* path (`review.submitted.v1`,
`review.aggregated.v1`) becomes an event the absorbing services
consume; old topics remain published for the six-month
compatibility window.

### Compatibility window (≥ 6 months)

For at least six calendar months from 2026-08-05:

- Every removed service's REST endpoint is mounted on the
  absorbing service as a 301/308 redirect to the canonical path.
- Every removed service's event topic continues to be published
  by the absorbing service under the **same topic name and the
  same schema version**. New event versions may be added; old
  topics remain published.
- Every removed service's database schema remains readable as a
  view in the absorbing service's schema.
- Every removed service's metrics label namespace is preserved
  (e.g. `dispatch_*`, `wallet_*`, `merchant_payout_*`,
  `restaurant_order_mgmt_*`, `feature_flag_*`).
- The 46-gateway registry, the immutable notification template-
  version snapshot chain, the SUPER_ADMIN break-glass with
  20-scope `<service>.admin` membership, the accounting four-
  layer model, and the per-service partitioning conventions are
  preserved unchanged.

### Accounting four-layer model preserved

| Layer | Owner (after ADR-0017) |
|-------|------------------------|
| Layer 1 — Customer wallet | `payment-service` |
| Layer 2 — Provider side (46-gateway registry) | `payment-service` |
| Layer 3 — Double-entry ledger | `ledger-service` |
| Layer 4 — Settlement | `payment-service` |

### Partitioning conventions preserved

Every partitioned table from a removed service is renamed into the
absorbing service's schema and continues to use the same monthly
pre-creation depth and retention window.

### Notification template-version snapshot chain

The `notification.template_version_snapshot` chain remains
append-only and is owned by `notification-service`. The absorbed
`communication-gateway-service` provider anti-corruption layer is
re-mounted inside `notification-service` and continues to call the
same providers with the same `template_version_snapshot_id` value.

### SUPER_ADMIN break-glass

`admin-service` keeps the `SUPER_ADMIN` permission preset. The
preset membership is **1 × `platform.super_admin` + 20 ×
`<service>.admin` scopes** (one per survivor). Grant / revoke
endpoints are unchanged and require break-glass co-signature.

### Review-rating split detail

- `review.submitted.v1` is emitted by the customer / trip / food
  endpoints in `customer-service`, `trip-service`, and
  `food-order-service` respectively; the original single topic is
  preserved for six months under the absorbing service.
- `review.aggregated.v1` is emitted by the absorbing service for
  its slice; the rating read-model column on the target entity
  (driver / restaurant / menu / trip / food-order) continues to
  exist for the compatibility window.

### Consequences

- Good: 38 fewer services to deploy, monitor, paginate, on-call.
- Good: one DB schema per parent domain; simpler migration story.
- Good: smaller blast radius for changes — feature work touches one
  service.
- Good: `ledger-service`, `identity-service`, `file-service`,
  `audit-service`, `api-gateway` are stable; this ADR only
  re-baselines their dependencies.
- Bad: short-term reconciliation overhead while aliases run in
  parallel.
- Neutral: every removed directory is **deleted** after its content
  has been absorbed into a survivor's "Removed predecessor
  capability" appendix and the migration hub (per the
  documentation-only migration policy).
- Neutral: ADR-0016 is superseded by this ADR; the migration hub
  records both stages.

### Confirmation

- `MICROSERVICES_MAP.md` "Service Count Summary" reads **20**.
- `docs/MIGRATION_HUB.md` lists 38 absorbed services mapped to the
  20 survivors.
- All 20 active service directories exist; the 38 removed
  directories do not exist.
- Survivor suites carry a "Removed predecessor capability"
  appendix with the absorbed schemas, events, and endpoints.
- Six-month compatibility window observed for old event topics,
  old REST paths, and old schema names.
- `git grep` for any of the 38 removed names returns hits only
  in the migration hub, in the absorbing service's appendix, in
  the ADR-0016 / ADR-0017 narrative, and in the surrounding
  architecture catalog (i.e. no orphan operational references).

## Pros and Cons of the Options

### Option A — Keep 44

- Good: no further migration.
- Bad: 44 services still cost more than 20 for the same workload;
  ADR-0016 was a half-step.

### Option B — 7 mega-services

- Good: even larger consolidation.
- Bad: requires renaming and breaks every external contract;
  per-domain bounded contexts collapse.

### Option C — 20 services

- Good: each survivor carries exactly one bounded context.
- Good: 38 directories are deletable after content absorption.
- Good: the 5 stable services (identity, file, audit, ledger,
  api-gateway) are untouched.
- Bad: short-term reconciliation overhead.

## References

- [`../MIGRATION_HUB.md`](../MIGRATION_HUB.md) — the single
  authoritative map from removed service → survivor capability.
- [`../MICROSERVICES_MAP.md`](../MICROSERVICES_MAP.md) — 20-service
  catalog.
- [`../DATA_OWNERSHIP.md`](../DATA_OWNERSHIP.md) — schema
  re-ownership table.
- [`../EVENT_ARCHITECTURE.md`](../EVENT_ARCHITECTURE.md) — outbox
  / inbox compatibility window.
- [`../SERVICE_ISOLATION.md`](../SERVICE_ISOLATION.md) — failure
  isolation contracts (unchanged for survivors).
- [`../DATABASE_ARCHITECTURE.md`](../DATABASE_ARCHITECTURE.md) —
  partitioning conventions.
- [`../../shared/PLATFORM_BASELINE.md`](../../shared/PLATFORM_BASELINE.md)
  — Postgres 18, Kafka, Keycloak, Redis, OpenTelemetry, Vault,
  deployment, DR baseline.
- ADR-0001 — microservices architecture.
- ADR-0002 — postgres per service (preserved; schemas consolidated
  by rename-into-parent, not by elimination).
- ADR-0013 — double-entry ledger (preserved; `ledger-service` stays
  independent).
- ADR-0016 — superseded by this ADR (the prior 14-into-5 step).

## Supersession

This ADR supersedes ADR-0016 in full. The migration hub records
both stages. ADR-0016's status is updated to **Superseded by
ADR-0017**.