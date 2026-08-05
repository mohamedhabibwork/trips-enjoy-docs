# Master Implementation Plan - Index

> **Created:** 2026-07-29  
> **Updated:** 2026-08-05  
> **Total Services:** 58  
> **Timeline:** 44 weeks (Phase 7 + 7.5 added)  
> **Status:** Comprehensive planning phase complete; every service has a PLAN.md

## 🧭 Master Plan (start here)

**📄 [MASTER_PLAN.md](MASTER_PLAN.md)** — the single source of truth for **what**
is being built, **in what locked order**, and **where the per-service plan
lives**. Every one of the 58 per-service `PLAN.md` files is linked from
there. The tables in `MASTER_PLAN.md` are the canonical implementation
order — do not re-order without updating that file.

**📄 [MASTER_PLAN_SUMMARY.md](MASTER_PLAN_SUMMARY.md)** — executive summary
of the legacy 6-phase plan (kept for history).

**📄 [MASTER_SERVICE_PLAN.md](MASTER_SERVICE_PLAN.md)** — detailed service
plans from the legacy pass (pre-Phase-7). The current per-service
implementation tasks live in each `services/<svc>/PLAN.md`.

### Per-service PLAN.md (all 58)

Every service has a `PLAN.md` in its `services/<svc>/` folder. Click
through from the master plan's **Per-service Plans** table, or jump
straight to a domain cluster below.

| Phase | Services | Plans |
|-------|----------|-------|
| Phase 1 (Platform Foundation) | configuration, feature-flag, identity, geolocation, api-gateway, communication-gateway, file, audit, zone, ledger | [10 PLAN.md](MASTER_PLAN.md#phase-1--platform-foundation-weeks-14) |
| Phase 2 (Core Business & Identity) | user-profile, customer, driver, courier, vehicle, address, notification, admin, payment, wallet, tax, support, fraud-risk, promotion | [14 PLAN.md](MASTER_PLAN.md#phase-2--core-business--identity-weeks-512) |
| Phase 3 (Ride-Hailing) | pricing, driver-availability, driver-location, eta-routing, ride-request, dispatch, trip, ride-payment-integration, driver-earnings, scheduled-ride, ride-safety, ride-history, driver-incentive | [13 PLAN.md](MASTER_PLAN.md#phase-3--ride-hailing-domain-weeks-1320) |
| Phase 4 (Food Marketplace) | merchant, restaurant, branch, restaurant-staff, menu, inventory, cart, search, checkout, food-order, restaurant-order-mgmt | [11 PLAN.md](MASTER_PLAN.md#phase-4--food-marketplace-weeks-2128) |
| Phase 5 (Food Delivery & Financial) | courier-dispatch, courier-tracking, delivery, food-payment-integration, restaurant-settlement, courier-earnings | [6 PLAN.md](MASTER_PLAN.md#phase-5--food-delivery--financial-weeks-2934) |
| Phase 6 (Analytics & Enhancements) | analytics, reporting, loyalty, review-rating | [4 PLAN.md](MASTER_PLAN.md#phase-6--analytics--enhancements-weeks-3540) |
| Phase 7 (Cross-cutting) | 13 services see Phase 7.0 block in their PLAN.md | [details](MASTER_PLAN.md#phase-7--cross-cutting-guaranteed-rewards--rating-based-pricing-weeks-4144) |
| Phase 7.5 (Make-a-Deal) | 9 services see Phase 7.5 block in their PLAN.md | [details](MASTER_PLAN.md#phase-75--make-a-deal-kernel-weeks-4142-parallel-with-phase-7) |

If you ever need to find a single PLAN.md, use the alphabetical table in
[MASTER_PLAN.md §"Per-service Plans"](MASTER_PLAN.md#per-service-plans-alphabetical).

---

## Legacy planning documents

> These predate the master plan below. They are kept for context but
> should not be used as the source of truth for what gets built first.

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

**Currently documented (10+ services):**
- configuration-service ✓
- feature-flag-service ✓
- api-gateway ✓
- audit-service ✓
- identity-service ✓
- ledger-service ✓
- geolocation-service ✓
- zone-service ✓
- file-service ✓
- communication-gateway-service ✓
- user-profile-service ✓
- customer-service ✓
- driver-service ✓
- courier-service ✓

**Remaining:** 44 services (follow same template structure)

### 3. Integration Dependencies
**📄 [SERVICE_INTEGRATION_MATRIX.md](SERVICE_INTEGRATION_MATRIX.md)**
- Complete integration matrix table (58 rows)
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

### 🏗️ Platform Foundation (10 services)
Tier 0-1 | **Must implement first**
- configuration-service
- feature-flag-service
- api-gateway
- audit-service
- identity-service
- ledger-service
- geolocation-service
- zone-service
- file-service
- communication-gateway-service

[📋 Phase 1 Details](IMPLEMENTATION_PHASES.md#phase-1-platform-foundation-weeks-1-4)

### 👥 Identity & User Management (7 services)
Tier 2 | **Depends on: Platform Foundation**
- user-profile-service
- customer-service
- driver-service
- courier-service
- vehicle-service
- address-service
- merchant-service

[📋 Phase 2 Details](IMPLEMENTATION_PHASES.md#phase-2-core-business--identity-weeks-5-12)

### 💰 Financial Core (5 services)
Tier 2-3 | **Revenue-critical**
- ledger-service (Tier 1)
- payment-service
- wallet-service
- tax-service
- pricing-service

[📋 Phase 2-3 Details](IMPLEMENTATION_PHASES.md)

### 🚗 Ride-Hailing (12 services)
Tier 3-5 | **Core business line #1**
- ride-request-service
- trip-service
- driver-availability-service
- driver-location-service
- dispatch-service
- eta-routing-service
- ride-payment-integration-service
- driver-earnings-service
- driver-incentive-service
- scheduled-ride-service
- ride-safety-service
- ride-history-service

[📋 Phase 3 Details](IMPLEMENTATION_PHASES.md#phase-3-ride-hailing-domain-weeks-13-20)

### 🍔 Food Marketplace (10 services)
Tier 3-5 | **Core business line #2**
- restaurant-service
- branch-service
- restaurant-staff-service
- menu-service
- inventory-service
- cart-service
- checkout-service
- food-order-service
- restaurant-order-mgmt-service
- search-service

[📋 Phase 4 Details](IMPLEMENTATION_PHASES.md#phase-4-food-marketplace-weeks-21-28)

### 🚴 Food Delivery (4 services)
Tier 3-5 | **Completes food business**
- courier-dispatch-service
- courier-tracking-service
- delivery-service
- courier-earnings-service

[📋 Phase 5 Details](IMPLEMENTATION_PHASES.md#phase-5-food-delivery--financial-weeks-29-34)

### 💵 Financial Settlement (2 services)
Tier 5 | **Revenue reconciliation**
- food-payment-integration-service
- restaurant-settlement-service

[📋 Phase 5 Details](IMPLEMENTATION_PHASES.md#phase-5-food-delivery--financial-weeks-29-34)

### 📊 Platform Support (6 services)
Tier 2-3 | **Cross-cutting**
- notification-service
- admin-service
- support-service
- fraud-risk-service
- promotion-service
- loyalty-service

[📋 Phase 2 & 6 Details](IMPLEMENTATION_PHASES.md)

### 📈 Analytics & Insights (5 services)
Tier 6 | **Observability & BI**
- search-service
- analytics-service
- reporting-service
- ride-history-service
- review-rating-service

[📋 Phase 6 Details](IMPLEMENTATION_PHASES.md#phase-6-analytics--enhancements-weeks-35-40)

---

## Key Integration Patterns

### Event Choreography
- **Outbox Pattern:** All services use transactional outbox for reliable event publishing
- **Inbox Pattern:** All services use idempotent inbox for duplicate detection
- **Saga Pattern:** Payment integration services orchestrate distributed transactions

### Synchronous Integration
- **Circuit Breakers:** All REST calls protected with circuit breakers
- **Timeouts:** Aggressive timeouts (1-5s typical)
- **Retries:** Bounded retries with exponential backoff

### Data Consistency
- **Event Sourcing:** Audit service maintains full event log
- **CQRS:** Read models in search, analytics, reporting, ride-history services
- **Eventual Consistency:** Services eventually consistent via events
- **Strong Consistency:** Within service boundaries via database transactions

---

## Implementation Tools & Standards

### Development Standards
- **API:** OpenAPI 3.x for all REST endpoints
- **Events:** Avro schemas via Confluent Schema Registry
- **Logging:** Structured JSON with correlation IDs
- **Tracing:** OpenTelemetry with Jaeger/Tempo
- **Metrics:** Prometheus + Grafana

### Technology Choices
- **Kotlin Services:** Spring Boot 4, Spring Data JPA, Spring Security 7
- **Go Services:** net/http + chi, pgx v5, go-redis v9
- **Python Services:** FastAPI 0.115+, NumPy, asyncpg
- **Databases:** PostgreSQL 18 per service
- **Caching:** Redis per service or shared cluster
- **Messaging:** Kafka with Avro schemas

### Quality Requirements
- **Test Coverage:** 80%+ per service
- **SLO:** T1=99.95%, T2=99.9%, T3=99.5%
- **Security:** OAuth2/OIDC via Keycloak, mTLS service-to-service
- **Observability:** Health/Ready/Started endpoints, structured logs, RED metrics

---

## Next Actions

### For Implementation Teams

1. **Week 0 (Preparation):**
   - [ ] Review all planning documents
   - [ ] Setup development environments
   - [ ] Configure CI/CD pipelines
   - [ ] Provision infrastructure (K8s, PostgreSQL, Kafka, Redis)
   - [ ] Setup Keycloak realms

2. **Week 1-4 (Foundation Phase):**
   - [ ] Begin Tier 0-1 service implementation
   - [ ] Follow detailed tasks in MASTER_SERVICE_PLAN.md
   - [ ] Daily standups per team
   - [ ] Weekly integration checkpoints

3. **Ongoing:**
   - [ ] Track progress in project management tool
   - [ ] Update service status in planning documents
   - [ ] Conduct weekly architecture reviews
   - [ ] Monthly steering committee updates

### For Documentation

- [ ] Complete remaining 44 service detailed plans
- [ ] Create API design templates
- [ ] Create database migration templates
- [ ] Create testing strategy per service type
- [ ] Create deployment runbooks

## Phase 7 (Weeks 41-44) — Cross-cutting feature: Guaranteed Rewards & Rating-Based Pricing

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
- `review-rating-service` — `GET /v1/zones/{zone_id}/driver-rating?window_minutes=15`
- `loyalty-service` — `GET /v1/accounts/{customer_id}/frequent-zones?window_days=30`
- `driver-earnings-service` — `GET /v1/drivers/{id}/period-eligible-earnings?window=hourly|daily`
- `trip-service` — `POST /v1/trips/{id}/reward/{re-evaluate|reverse}` + `GET .../reward`

### Files touched (~60)

- `trip-service` 7 files; `pricing-service` 7; `admin-service` 7
- `driver-earnings-service`, `wallet-service`, `review-rating-service`,
  `loyalty-service`, `configuration-service`, `customer-service`,
  `notification-service`, `audit-service`, `analytics-service`,
  `ledger-service` — 5/5/5/5/2/1/2/2/1/3 files respectively
- 5 cross-service workflow docs (`ACCOUNTING_WORKFLOWS.md`,
  `RIDE_WORKFLOWS.md`, `PAYMENT_WORKFLOWS.md`,
  `SERVICE_INTEGRATION_MATRIX.md`, `architecture/EVENT_ARCHITECTURE.md`)
- 5 repo-level docs (this file + 4 master-plan docs)
- `docs/services/README.md` catalog touch-up

---

**📌 Start Here:** [MASTER_PLAN_SUMMARY.md](MASTER_PLAN_SUMMARY.md)  
**📋 Service Details:** [MASTER_SERVICE_PLAN.md](MASTER_SERVICE_PLAN.md)  
**🔗 Dependencies:** [SERVICE_INTEGRATION_MATRIX.md](SERVICE_INTEGRATION_MATRIX.md)  
**📅 Timeline:** [IMPLEMENTATION_PHASES.md](IMPLEMENTATION_PHASES.md)
