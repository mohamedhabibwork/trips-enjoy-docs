# Master Implementation Plan — Index

> **Created:** 2026-07-29  
> **Updated:** 2026-08-12 (Phase 7.7 cross-cutting — added `chat-service` as the 21st active service per [`services/chat-service/PLAN.md`](services/chat-service/PLAN.md))  
> **Total active services:** 21 (20 from 58 → 20 consolidation per [ADR-0017](architecture/adrs/0017-20-service-architecture.md); 1 added in Phase 7.7)  
> **Timeline:** 44 weeks (Phase 1-6 = 40 weeks, Phase 7 = 4 weeks, Phase 7.5 = 2 weeks, Phase 7.6 Conductor = 1 sprint absorbed into Phase 7, Phase 7.7 Chat = 8 sprints in parallel)  
> **Status:** All 21 per-service `PLAN.md` files exist (20 surviving + `chat-service` Phase 7.7); this index binds them to the canonical order in `MASTER_PLAN.md`.

## 🧭 Master Plan (start here)

** [MASTER_PLAN.md](MASTER_PLAN.md)** — the single source of truth for **what**
is being built, **in what locked order**, and **where the per-service plan
lives**. Every one of the 20 active per-service `PLAN.md` files is linked from
there. The tables in `MASTER_PLAN.md` are the canonical implementation
order — do not re-order without updating that file.

**📄 [MASTER_PLAN_SUMMARY.md](MASTER_PLAN_SUMMARY.md)** — executive summary
of the legacy 6-phase plan (kept for history).

**📄 [MASTER_SERVICE_PLAN.md](MASTER_SERVICE_PLAN.md)** — detailed service
plans from the legacy pass (pre-Phase-7). The current per-service
implementation tasks live in each `services/<svc>/PLAN.md`.

**📄 [MASTER_TASK.md](MASTER_TASK.md)** — cross-service master task
registry. Every per-service `T-<SVC>-NN` task across the 20 active
services, Phase 7 / 7.5 / 7.6 cross-cutting addenda, plus the Conductor
workflow registry for the cross-cutting flows.

**📄 [MIGRATION_HUB.md](MIGRATION_HUB.md)** — the canonical 58 → 20
mapping, the 38 obsolete suites slated for deletion, the 6-month
compatibility window, and the dual-publish / replay / cutover policy.

### Per-service PLAN.md (all 20 active)

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

## Legacy planning documents

> These predate the 20-service consolidation. They are kept for context
> but should not be used as the source of truth for what gets built
> first. The 58 → 20 mapping lives in [MIGRATION_HUB.md](MIGRATION_HUB.md).

### 1. Executive Summary
**📄 [MASTER_PLAN_SUMMARY.md](MASTER_PLAN_SUMMARY.md)**
- Overview of all planning documents
- Service tier breakdown (Tier 0-6)
- Technology stack distribution
- Critical integration events
- Implementation approach & strategy
- Quality gates & success metrics

### 2. Detailed Service Plans
**📄 [MASTER_SERVICE_PLAN.md](MASTER_SERVICE_PLAN.md)**
- Implementation strategy (6 phases, 40 weeks)
- Service dependency tiers
- Domain-based implementation order
- **Detailed plans for each service including:**
  - Purpose & responsibilities
  - Task breakdown (8-40 tasks per service)
  - Integration points (sync/async)
  - Success criteria
  - Related documentation links

### 3. Integration Dependencies
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
   - [ ] Follow detailed tasks in `MASTER_SERVICE_PLAN.md` (legacy) and each `services/<svc>/PLAN.md`
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

Per [ADR-0018](architecture/adrs/0018-conductor-workflow-engine.md).
15 services participate in 17 workflow IDs across 5 flow families.
See `shared/CONDUCTOR_WORKFLOWS.md` 3 and the per-service `PLAN.md`
`Phase 7.6` block for the registry.

---

**📌 Start Here:** [MASTER_PLAN.md](MASTER_PLAN.md)  
**📋 Service Details:** [MASTER_SERVICE_PLAN.md](MASTER_SERVICE_PLAN.md)  
**🔗 Dependencies:** [SERVICE_INTEGRATION_MATRIX.md](SERVICE_INTEGRATION_MATRIX.md)  
**📅 Timeline:** [IMPLEMENTATION_PHASES.md](IMPLEMENTATION_PHASES.md)  
**️ Migration Map (58 → 20):** [MIGRATION_HUB.md](MIGRATION_HUB.md)
