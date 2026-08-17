# Master Implementation Plan — Index

> **Created:** 2026-07-29  
> **Updated:** 2026-08-12 (Phase 7.7 cross-cutting — added `chat-service` as the 21st active service per [`services/chat-service/PLAN.md`](services/chat-service/PLAN.md))  
> **Total active services:** 21 (20 from 58 → 20 consolidation per [ADR-0017](architecture/adrs/0017-20-service-architecture.md); 1 added in Phase 7.7)  
> **Timeline:** 44 weeks (Phase 1-6 = 40 weeks, Phase 7 = 4 weeks, Phase 7.5 = 2 weeks, Phase 7.6 Conductor = 1 sprint absorbed into Phase 7, Phase 7.7 Chat = 8 sprints in parallel)  
> **Status:** All 21 per-service `PLAN.md` files exist (20 surviving + `chat-service` Phase 7.7); this index binds them to the canonical order in `MASTER_PLAN.md`.

## 🧭 Master Plan (start here)

** [MASTER_PLAN.md](MASTER_PLAN.md)** — the single source of truth for **what**
is being built, **in what locked order**, and **where the per-service plan
lives**. Every one of the 21 active per-service `PLAN.md` files is linked from
there. The tables in `MASTER_PLAN.md` are the canonical implementation
order — do not re-order without updating that file.

**📄 [MASTER_TASK.md](MASTER_TASK.md)** — cross-service master task
registry. Every per-service `T-<SVC>-NN` task across the 21 active
services, Phase 7 / 7.5 / 7.6 cross-cutting addenda, plus the Conductor
workflow registry for the cross-cutting flows.

**📄 [MIGRATION_HUB.md](MIGRATION_HUB.md)** — the canonical 58 → 20
mapping, the 38 obsolete suites slated for deletion, the 6-month
compatibility window, and the dual-publish / replay / cutover policy.

### Per-service PLAN.md (all 21 active)

Every service has a `PLAN.md` in its `services/<svc>/` folder. Click
through from the master plan's **Per-service Plans** table, or jump
straight to a domain cluster below.

| Phase | Services (20-service catalog) | Anchor |
|-------|------------------------------|--------|
| Phase 1 (Platform Foundation) | `configuration-service`, `identity-service`, `api-gateway`, `notification-service` (provider ACL worker), `file-service`, `audit-service`, `geolocation-service`, `ledger-service` | [8 PLAN.md](MASTER_PLAN.md#phase-1--platform-foundation-weeks-14) |
| Phase 2 (Core Business & Identity) | `customer-service`, `driver-service`, `courier-service`, `notification-service`, `admin-service`, `payment-service`, `fraud-risk-service`, `pricing-service` | [8 PLAN.md](MASTER_PLAN.md#phase-2--core-business--identity-weeks-512) |
| Phase 3 (Ride-Hailing) | `trip-service`, `geolocation-service` (ETA/routing worker) | [2 PLAN.md](MASTER_PLAN.md#phase-3--ride-hailing-domain-weeks-1320) |
| Phase 4 (Food Marketplace) | `restaurant-service`, `food-order-service`, `search-service` | [3 PLAN.md](MASTER_PLAN.md#phase-4--food-marketplace-weeks-2128) |
| Phase 5 (Food Delivery & Financial hardening) | `payment-service` (financial hardening; already in Phase 2) | [1 PLAN.md](MASTER_PLAN.md#phase-5--food-delivery--financial-weeks-2934) |
| Phase 6 (Analytics & Enhancements) | `reporting-service` (data lake + BI workers) | [1 PLAN.md](MASTER_PLAN.md#phase-6--analytics--enhancements-weeks-3540) |
| Phase 7 (Cross-cutting) | 13 services ship a `Phase 7.0` block in their PLAN.md | [details](MASTER_PLAN.md#phase-7--cross-cutting-guaranteed-rewards--rating-based-pricing-weeks-4144) |
| Phase 7.5 (Make-a-Deal) | 9 services ship a `Phase 7.5` block in their PLAN.md | [details](MASTER_PLAN.md#phase-75--make-a-deal-kernel-weeks-4142-parallel-with-phase-7) |
| Phase 7.6 (Conductor — Netflix Conductor adoption per ADR-0018) | 15 services ship a `Phase 7.6` Conductor block in their PLAN.md | [details](shared/CONDUCTOR_WORKFLOWS.md#phase-76-conductor-rollout) |
| **Phase 7.7 (In-App Chat — cross-cutting)** | **`chat-service`** ships as the 21st service; 6 services (trip, food-order, courier, restaurant, notification, admin, fraud-risk) ship a `Phase 7.7` block in their PLAN.md | [details](services/chat-service/PLAN.md) |

If you ever need to find a single PLAN.md, use the alphabetical table in
[MASTER_PLAN.md "Per-service Plans"](MASTER_PLAN.md#per-service-plans-alphabetical).

---

## Reference documents

> The 58 → 20 consolidation history lives in [MIGRATION_HUB.md](MIGRATION_HUB.md).

### 1. Integration Dependencies
**📄 [SERVICE_INTEGRATION_MATRIX.md](SERVICE_INTEGRATION_MATRIX.md)**
- Complete integration matrix table (20 rows)
- Quick reference: Tier, Tech, Sync Deps, Async Consumes/Produces
- Links to integration documentation
- Domain clusters

### 4. Week-by-Week Roadmap
**📄 [IMPLEMENTATION_PHASES.md](IMPLEMENTATION_PHASES.md)**
- **Phase 1 (Weeks 1-4):** Platform Foundation
- **Phase 2 (Weeks 5-12):** Core Business & Identity
- **Phase 3 (Weeks 13-20):** Ride-Hailing Domain
- **Phase 4 (Weeks 21-28):** Food Marketplace
- **Phase 5 (Weeks 29-34):** Food Delivery & Financial
- **Phase 6 (Weeks 35-40):** Analytics & Enhancements

---

## Quick Navigation by Domain

The 20 active services are organized into the eight domains below. Each
domain cluster links to its members' `PLAN.md`. Internal workers
(e.g. `payment-service` driver-earnings / wallet / ride-saga /
food-saga / merchant-settlement / courier-earnings workers) are part of
the survivor service and are documented inside that survivor's suite.

### 🏗️ Platform Foundation (8 services)
Tier 0–1 | **Must implement first**
- [configuration-service](services/configuration-service/PLAN.md) — Tier 0 (Kotlin/Spring)
- [identity-service](services/identity-service/PLAN.md) — Tier 1 (Node/TS; Keycloak bridge)
- [api-gateway](services/api-gateway/PLAN.md) — Tier 1 (Go/Envoy)
- [notification-service](services/notification-service/PLAN.md) — Tier 1→2 (provider ACL worker in Phase 1, orchestration in Phase 2)
- [file-service](services/file-service/PLAN.md) — Tier 1 (Go)
- [audit-service](services/audit-service/PLAN.md) — Tier 1 (Go)
- [geolocation-service](services/geolocation-service/PLAN.md) — Tier 1→3 (zones worker in Phase 1, ETA/routing in Phase 3)
- [ledger-service](services/ledger-service/PLAN.md) — Tier 1 (Node/TS)

[📋 Phase 1 Details](IMPLEMENTATION_PHASES.md#phase-1-platform-foundation-weeks-1-4)

### 👥 Identity & Customer Surfaces (1 service, 8 personas)
Tier 2 | **Depends on: Platform Foundation**
- [customer-service](services/customer-service/PLAN.md) — Tier 2 (Kotlin/Spring) — absorbs `user-profile-service`, `address-service`, plus customer loyalty account exposure; cross-persona profile, addresses, devices, preferences, privacy.

[📋 Phase 2 Details](IMPLEMENTATION_PHASES.md#phase-2-core-business--identity-weeks-5-12)

### 🚗 Driver Operations (1 service, multiple internal workers)
Tier 2 | **Depends on: Platform Foundation**
- [driver-service](services/driver-service/PLAN.md) — Tier 2 (Kotlin/Spring) — absorbs availability, location, dispatch, incentive, and `vehicle-service`; driver/KYC, vehicles, matching, location, deals, incentive evaluation.

### 🚴 Courier Operations (1 service)
Tier 2 | **Depends on: Platform Foundation**
- [courier-service](services/courier-service/PLAN.md) — Tier 2 (Kotlin/Spring) — absorbs courier dispatch/tracking and delivery; courier/KYC, location, matching/deals, pickup/delivery, proof, COD domain confirmation. Earnings/COD money stay in payment.

### 🛡️ Operations & Admin (1 service, multiple modules)
Tier 2 | **Depends on: Platform Foundation**
- [admin-service](services/admin-service/PLAN.md) — Tier 2 (Kotlin/Spring) — management plane + separately permissioned support case module; identity remains sole Keycloak bridge.

### 💰 Financial Core (2 services)
Tier 2–3 | **Revenue-critical**
- [payment-service](services/payment-service/PLAN.md) — Tier 3 (Kotlin/Spring) — sole owner of all operational money: 46 gateway drivers/registry, intents/methods/attempts, auth/capture/void/refund/dispute, ride/food sagas, wallet, COD reconciliation, tips, driver/courier earnings + withdrawals, merchant payables/settlements/disputes/payouts, operational reconciliation.
- [ledger-service](services/ledger-service/PLAN.md) — Tier 1 (Node/TS) — sole immutable double-entry journal / chart-of-accounts authority.

### 🛡️ Risk (1 service)
Tier 2 | **Independent scoring, advises payment**
- [fraud-risk-service](services/fraud-risk-service/PLAN.md) — Tier 2 (Python/FastAPI)

### 💵 Pricing (1 service, absorbs tax/promotion/loyalty-rules)
Tier 3 | **Immutable price/tax/discount snapshots**
- [pricing-service](services/pricing-service/PLAN.md) — Tier 3 (Kotlin/Spring) — absorbs `tax-service`, `promotion-service`, loyalty rules from former pricing/customer split; quote/fare/delivery fee, immutable tax snapshots, promotions/redemptions, rating-density, geo overrides, loyalty pricing.

[📋 Phase 2-3 Details](IMPLEMENTATION_PHASES.md)

### 🚕 Ride-Hailing (2 services)
Tier 3–4 | **Core business line #1**
- [trip-service](services/trip-service/PLAN.md) — Tier 4 (Kotlin/Spring) — absorbs ride-request, scheduled-ride, safety, history, and trip reviews; request-to-trip, SOS/share-trip, guaranteed rewards, history.
- [geolocation-service](services/geolocation-service/PLAN.md) — ETA/routing worker added in Phase 3 (Go)

[📋 Phase 3 Details](IMPLEMENTATION_PHASES.md#phase-3-ride-hailing-domain-weeks-13-20)

### 🍔 Food Marketplace (3 services)
Tier 3–6 | **Core business line #2**
- [restaurant-service](services/restaurant-service/PLAN.md) — Tier 3 (Kotlin/Spring) — absorbs merchant, branch, menu, inventory, staff, and restaurant operations.
- [food-order-service](services/food-order-service/PLAN.md) — Tier 5 (Kotlin/Spring) — absorbs cart, checkout, restaurant-order management, non-payment orchestration, and food-side reviews.
- [search-service](services/search-service/PLAN.md) — Tier 6 (Kotlin/Spring) — specialized cross-domain search/index context and discovery projections; not a transactional writer.

[📋 Phase 4 Details](IMPLEMENTATION_PHASES.md#phase-4-food-marketplace-weeks-21-28)

### 📊 Analytics & Insights (1 service, multi-worker)
Tier 6 | **Observability & BI**
- [reporting-service](services/reporting-service/PLAN.md) — Tier 6 (Python/FastAPI) — absorbs analytics/warehouse ingestion and report/read-model projections; never writes transactional domain state.

[📋 Phase 6 Details](IMPLEMENTATION_PHASES.md#phase-6-analytics--enhancements-weeks-35-40)

---

## Key Integration Patterns

### Event Choreography
- **Outbox Pattern:** All services use transactional outbox for reliable event publishing
- **Inbox Pattern:** All services use idempotent inbox for duplicate detection
- **Saga Pattern:** `payment-service` orchestrates ride/food payment, refund, wallet, COD, settlement, and earnings sagas
- **Conductor Workflows (Phase 7.6):** Netflix Conductor per ADR-0018 — see `shared/CONDUCTOR_WORKFLOWS.md`

### Synchronous Integration
- **Circuit Breakers:** All REST calls protected with circuit breakers
- **Timeouts:** Aggressive timeouts (1-5s typical)
- **Retries:** Bounded retries with exponential backoff
- **Idempotency-Key header:** Required on every mutating REST route

### Data Consistency
- **Event Sourcing:** `audit-service` maintains full event log
- **CQRS:** Read models in `search-service` and `reporting-service`
- **Eventual Consistency:** Services eventually consistent via events
- **Strong Consistency:** Within service boundaries via database transactions; `ledger-service` is the only cross-service source of money truth

---

## Implementation Tools & Standards

### Development Standards
- **API:** OpenAPI 3.x for all REST endpoints
- **Events:** Versioned (`domain.entity.event.v1`); Avro/JSON Schema via Confluent Schema Registry
- **Logging:** Structured JSON with `requestId` (the API-gateway-issued business id, per [ADR-0019](architecture/adrs/0019-request-id-at-the-edge.md)) and `traceId` (the OTel W3C trace id, **distinct from** `requestId`)
- **Tracing:** OpenTelemetry → Jaeger/Tempo
- **Metrics:** Prometheus + Grafana

### Technology Choices
- **Kotlin Services:** Spring Boot 4, Spring Data JPA, Spring Security 7, jOOQ/Exposed
- **Go Services:** net/http + chi, pgx v5, go-redis v9
- **Python Services:** FastAPI 0.115+, NumPy, asyncpg
- **Node/TS Services:** NestJS, Prisma
- **Databases:** PostgreSQL 19 per service (no cross-service FKs)
- **Caching:** Redis per service or shared cluster
- **Messaging:** Kafka with Avro/JSON-Schema
- **Orchestration:** Netflix Conductor (Phase 7.6+) — `shared/CONDUCTOR_WORKFLOWS.md`

### Quality Requirements
- **Test Coverage:** 80%+ per service
- **SLO:** T0=99.99%, T1=99.95%, T2=99.9%, T3=99.5%
- **Security:** OAuth2/OIDC via Keycloak, mTLS service-to-service
- **Observability:** Health/Ready/Started endpoints, structured logs, RED metrics, OpenTelemetry

---

## Next Actions

### For Implementation Teams

1. **Week 0 (Preparation):**
   - [ ] Review all planning documents
   - [ ] Setup development environments
   - [ ] Configure CI/CD pipelines
   - [ ] Provision infrastructure (K8s, PostgreSQL, Kafka, Redis, Conductor)
   - [ ] Setup Keycloak realms

2. **Week 1-4 (Foundation Phase):**
   - [ ] Begin Tier 0-1 service implementation per `MASTER_PLAN.md` Phase 1
   - [ ] Follow detailed tasks in each `services/<svc>/PLAN.md`
   - [ ] Daily standups per team
   - [ ] Weekly integration checkpoints

3. **Ongoing:**
   - [ ] Track progress in project management tool
   - [ ] Update service status in planning documents
   - [ ] Conduct weekly architecture reviews
   - [ ] Monthly steering committee updates

### For Documentation

- [ ] Confirm all 20 per-service `PLAN.md` files have `Phase 7.0` / `Phase 7.5` / `Phase 7.6` blocks where applicable
- [ ] Confirm all 20 per-service `BRD.md` files have `BR--NNN` IDs and acceptance criteria
- [ ] Confirm all 20 per-service `SRS.md` files have `FR--NNN` / `NFR--NNN` / `SEC--NNN` / `DATA--NNN` IDs
- [ ] Confirm all 20 per-service `ERD.md` files have a Mermaid `erDiagram` plus a `schema.<service>` DDL block
- [ ] Confirm all 20 per-service `WORKFLOWS.md` files have at least one `sequenceDiagram` and one `stateDiagram-v2`

---

## Phase 7 (Weeks 41–44) — Cross-cutting feature: Guaranteed Rewards & Rating-Based Pricing

This phase covers a single **cross-cutting feature** that touches many
existing services rather than introducing new ones. The
`trip-service` and `pricing-service` docs are the canonical sources
for the new behavior; the dependent services carry the consumer
rows, event handlers, and chart-of-account extensions.

### What changed

- **Trip-service guaranteed rewards** (per-trip + hourly + daily
  floor for both driver and user), emitted as
  `trip.reward.granted.v1` and `trip.reward.reversed.v1`. Chart-of-
  accounts: `6302_guaranteed_minimum` (driver, existing) and
  `2100_customer_credit_liability` (user, new).
- **Pricing-service rating-density surge-pressure** sub-pipeline —
  composes multiplicatively with the existing zone surge, capped by
  `pricing.surge.max_multiplier`.
- **Pricing-service frequent-rider loyalty discount** sub-pipeline
  — applied AFTER the promotion, BEFORE tax, capped at
  `pricing.min_fare.{city_id}`.
- **Pricing-service per-location and OD-pair overrides** via the new
  `admin-service` geo-config API. Cross-border trips produce
  both `tax_origin` and `tax_destination` line items.

### New events (consolidated)

- `trip.reward.granted.v1`, `trip.reward.reversed.v1`
- `pricing.rating_density.applied.v1`, `pricing.loyalty_discount.applied.v1`,
  `pricing.geo_overrides.matched.v1`, `pricing.geo_config.updated.v1`
- (helper) `review.zone_aggregated.v1`, `loyalty.frequent_zone.aggregated.v1`

### New APIs

- `admin-service` — `/v1/admin/pricing/geo-config[...]`
- `trip-service` / `food-order-service` / `search-service` (review projections) — `GET /v1/zones/{zone_id}/driver-rating?window_minutes=15`
- `pricing-service` (loyalty rules) / `customer-service` (account) — `GET /v1/accounts/{customer_id}/frequent-zones?window_days=30`
- `payment-service` (driver-earnings worker) — `GET /v1/drivers/{id}/period-eligible-earnings?window=hourly|daily`
- `trip-service` — `POST /v1/trips/{id}/reward/{re-evaluate|reverse}` + `GET .../reward`

### Files touched (~60)

- `trip-service` 7 files; `pricing-service` 7; `admin-service` 7
- `payment-service` (driver-earnings + wallet workers), `trip-service` / `food-order-service` / `search-service` (review projections),
  `pricing-service` (loyalty rules) / `customer-service` (account), `configuration-service`, `customer-service`,
  `notification-service`, `audit-service`, `reporting-service` (data lake worker),
  `ledger-service` — 5/5/5/5/2/1/2/2/1/3 files respectively
- 5 cross-service workflow docs (`ACCOUNTING_WORKFLOWS.md`,
  `RIDE_WORKFLOWS.md`, `PAYMENT_WORKFLOWS.md`,
  `SERVICE_INTEGRATION_MATRIX.md`, `architecture/EVENT_ARCHITECTURE.md`)
- 5 repo-level docs (this file + 4 master-plan docs)
- `docs/services/README.md` catalog touch-up

---

## Phase 7.5 (Weeks 41–42, parallel with Phase 7) — Make-a-Deal Kernel

Embedded per-service negotiation kernel (`docs/shared/DEAL_FEATURE.md`).
9 participating services, each owns its deal rows and event production.
No central binary.

## Phase 7.6 (sprint absorbed into Phase 7) — Netflix Conductor Adoption

Per [ADR-0018](architecture/adrs/0018-workflow-engine-conductor.md).
15 services participate in 17 workflow IDs across 5 flow families.
See `shared/CONDUCTOR_WORKFLOWS.md` 3 and the per-service `PLAN.md`
`Phase 7.6` block for the registry.

---

**📌 Start Here:** [MASTER_PLAN.md](MASTER_PLAN.md)  
**🔗 Dependencies:** [SERVICE_INTEGRATION_MATRIX.md](SERVICE_INTEGRATION_MATRIX.md)  
**📅 Timeline:** [IMPLEMENTATION_PHASES.md](IMPLEMENTATION_PHASES.md)  

---

## Architecture design docs

- **[`architecture/HLD.md`](architecture/HLD.md)** — High-Level Design (system-level architecture, navigational hub)
- **[`architecture/LLD.md`](architecture/LLD.md)** — Low-Level Design (component-level patterns, navigational hub)
- **[`architecture/ARCHITECTURE.md`](architecture/ARCHITECTURE.md)** — architectural style and non-negotiables
- **[`architecture/SYSTEM_OVERVIEW.md`](architecture/SYSTEM_OVERVIEW.md)** — plain-English summary
**️ Migration Map (58 → 20):** [MIGRATION_HUB.md](MIGRATION_HUB.md)

---

## STATUS.md composition contract

> **Appended 2026-08-14.** Every active service ships a
> `STATUS.md` next to its `README.md` / `BRD.md` / `SRS.md` /
> `ERD.md` / `INTEGRATION.md` / `WORKFLOWS.md` / `TECH.md` /
> `PLAN.md` / `SKELETON.<ext>`. The contract is documented in
> [`architecture/SERVICE_DOC_TEMPLATE.md`](architecture/SERVICE_DOC_TEMPLATE.md)
> "STATUS.md template".

`STATUS.md` is a **reader-rendered composition** of fields from
the canonical sources listed below — never an independent source
of truth. When any source changes, the corresponding `STATUS.md`
section must be regenerated. Drift between `STATUS.md` and its
sources fails the docs-QA check (`make status-md-check`).

| Field group | Source of truth (canonical) |
|---|---|
| Identity | [`services/README.md`](services/README.md) + `<service>/README.md` §1–2 |
| Tech profile | `<service>/TECH.md` + [`services/RECOMMENDATIONS.md`](services/RECOMMENDATIONS.md) §2 |
| Implementation lifecycle | [`DEPLOYMENT_ORDER.md` §8.2](DEPLOYMENT_ORDER.md) — single canonical implementation registry |
| Documentation completeness | filesystem scan of `docs/services/<service>/` + `git log -1 --format=%ci -- <file>` per row |
| Contract snapshot | `<service>/INTEGRATION.md` §1–4 + [`SERVICE_INTEGRATION_MATRIX.md`](SERVICE_INTEGRATION_MATRIX.md) |
| Security / RBAC | `<service>/TECH.md` §10 + [`services/RECOMMENDATIONS.md`](services/RECOMMENDATIONS.md) §6.2a |
| Plan snapshot | `<service>/PLAN.md` (header lines 3–9 + phase blocks) |

### Doc-QA invariants (enforced by `make status-md-check`)

- **Exactly 21 `STATUS.md` files** — one per active service in the
  locked 21-service catalog.
- **Each file has 8 numbered sections** matching the template in
  `architecture/SERVICE_DOC_TEMPLATE.md` (Identity, Tech profile,
  Implementation lifecycle, Documentation completeness, Contract
  snapshot, Security / RBAC, Plan snapshot, Cross-links).
- **"Implementation lifecycle" row count** equals the row count of
  [`DEPLOYMENT_ORDER.md` §8.2](DEPLOYMENT_ORDER.md) (one row per
  service); the values in the column are copied from §8.2 verbatim.
- **No duplicated facts.** Every value is either a pointer to a
  canonical source or a verbatim copy of a canonical value (e.g.
  the lifecycle row text). Never restate facts that already live
  in another doc.

### Regeneration procedure (today: manual)

1. **Identity** — copy from `services/README.md` + the service's
   `README.md` §1–2 + the `MICROSERVICES_MAP.md` row.
2. **Tech profile** — copy from `<service>/TECH.md` §1, §4, §7, §8
   + `RECOMMENDATIONS.md` §2 + `<service>/README.md` §18.
3. **Implementation lifecycle** — copy the row verbatim from
   `DEPLOYMENT_ORDER.md` §8.2. For graduates, append the
   implementation memory pointer from the project memory index.
4. **Documentation completeness** — `ls docs/services/<service>/`
   + `git log -1 --format=%ci -- docs/services/<service>/<file>`
   per file, rounded to the day.
5. **Contract snapshot** — count APIs / events from
   `<service>/INTEGRATION.md` §1–4; copy sync deps from
   `SERVICE_INTEGRATION_MATRIX.md`; copy workflows from
   `services/README.md` "By workflow participation".
6. **Security / RBAC** — copy from `<service>/TECH.md` §10 + the
   22-role SUPER_ADMIN preset per `services/RECOMMENDATIONS.md`
   §6.2a.
7. **Plan snapshot** — copy the 7-row header block from
   `<service>/PLAN.md`; grep for `### Phase 7.<x>` blocks;
   count task rows by status (`pending` / `in_progress` /
   `done` / `blocked`).
8. **Cross-links** — fixed per template; only the
   implementation-memory pointer is service-specific.

Future work (post-launch): a `make status-md` target that
regenerates all 21 `STATUS.md` files from the canonical sources
in one shot, mirroring `apps/<service>/` filesystem ground truth
via `find`.

---

## Phase 9 — Platform DRY (apps ↔ packages refactor)

> **Created:** 2026-08-15 (audit-only; no code changes until ADR-0020…0027 land)
> **Plan:** [`docs/plans/PLATFORM_DRY_AUDIT.md`](plans/PLATFORM_DRY_AUDIT.md)
> **Audit:** [`docs/shared/PLATFORM_DRY_AUDIT.md`](shared/PLATFORM_DRY_AUDIT.md)
> **Status:** Audit complete (~15,200 duplicated LOC identified across the 21 apps). All 6 phases (A–F) are blocked on the 8 contract-drift ADRs in the audit §6.

### What this phase is

A pure code-cleanup exercise that lifts duplication from the 21 apps into the 3 shared packages (`packages/platform-spring-boot/`, `packages/platform-go/`, `packages/platform-python/`). It does not introduce any new top-level packages, does not change the 20-service bounded-context model, and does not homogenise language choices.

### Per-service PLAN.md additions (append-only)

Every one of the 21 per-service `PLAN.md` files gains a Phase 9 block when (and only when) its turn comes. The block template is:

```markdown
## Phase 9 — Platform DRY

> **Status:** pending | in_progress | done
> **Subsections adopted:** <list, e.g. "A.1 RequestCorrelationFilter, A.1 JacksonConfiguration">

- [ ] Adopted `platform-spring-boot` / `platform-go` / `platform-python` for: <list>
- [ ] Files deleted: <list with LOC>
- [ ] V_NN__migrations added: <list>
- [ ] `make build` green
- [ ] Per-service tests green
```

The block is appended at the end of the existing Phase 7.7 section. No existing section is renumbered. No existing deep link breaks.

### When Phase 9 begins

Phase 9 does not start until the 8 ADRs in the audit §6 are merged. Filing those ADRs is the first concrete task. Once they're merged, execution follows the phased plan in [`docs/plans/PLATFORM_DRY_AUDIT.md`](plans/PLATFORM_DRY_AUDIT.md):

1. **Phase A** — Tier 1 pure deletion (~4,000 LOC, 2 days, 14 Kotlin apps, ADR-0026 + ADR-0027)
2. **Phase B** — Tier 2 shared entities (~1,900 LOC, 1 week, 9 Kotlin apps, ADR-0020 + ADR-0023 + ADR-0024)
3. **Phase C** — Tier 3 domain migration (~3,800 LOC, 1 week, 14 Kotlin apps, ADR-0021 + ADR-0022)
4. **Phase D** — Tier 4 new modules (~1,750 LOC, 2 weeks, 14 Kotlin apps)
5. **Phase E** — Go (~2,400 LOC, 1 week, 4 Go apps)
6. **Phase F** — Python concrete lifts (~250 LOC, 3 days, 1 Python app)

### Why audit-only is the right starting state

Per the established `verify-graduate-shipped` and `docs-only-verify-fill` feedback patterns, a cross-cutting refactor that touches 21 apps + 3 packages + the build system should not start with code changes. The audit enumerates every site, ranks every lift by ROI, and locks the 8 contract-drift items behind ADRs before any deletion. That sequencing is what made the prior lift-forward pattern work across the Phase 8 graduates.
