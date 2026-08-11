# Master Service Implementation Plan - Summary

> **Completion Status:** In Progress  
> **Total Services:** 21 active (20 surviving from the 58 → 20 consolidation on 2026-08-05 per [ADR-0017](architecture/adrs/0017-20-service-architecture.md); 1 added in Phase 7.7 — `chat-service` — on 2026-08-12; see [MIGRATION_HUB.md](MIGRATION_HUB.md) and [services/chat-service/PLAN.md](services/chat-service/PLAN.md))  
> **Documentation Created:** 2026-07-29  
> **Last updated:** 2026-08-12 (Phase 7.7 addendum)

## Generated Documentation

### 1. Master Service Plan
**File:** `MASTER_SERVICE_PLAN.md`

Comprehensive end-to-end plan including:
- Implementation strategy (6 phases over 40 weeks)
- Service dependency tiers (Tier 0-6)
- Domain-based implementation order (9 domains)
- Detailed service plans with tasks, integration points, and success criteria

**Currently includes detailed plans for:**
- configuration-service (complete with 8 phases, 40+ tasks)
- `configuration-service` (flags)
- api-gateway
- audit-service
- identity-service
- ledger-service

**Next steps:** Continue adding remaining 52 services with same structure

### 2. Service Integration Matrix
**File:** `SERVICE_INTEGRATION_MATRIX.md`

Complete integration dependency mapping:
- All 20 active services with tier, tech stack, dependencies
- Sync/async dependency visualization
- Quick reference table for integration planning
- Domain cluster organization

### 3. Implementation Phases
**File:** `IMPLEMENTATION_PHASES.md`

Week-by-week roadmap:
- **Phase 1 (Weeks 1-4):** Platform Foundation (10 services)
- **Phase 2 (Weeks 5-12):** Core Business & Identity (12 services)
- **Phase 3 (Weeks 13-20):** Ride-Hailing Domain (12 services)
- **Phase 4 (Weeks 21-28):** Food Marketplace (10 services)
- **Phase 5 (Weeks 29-34):** Food Delivery & Financial (9 services)
- **Phase 6 (Weeks 35-40):** Analytics & Enhancements (5 services)

Each phase includes deliverables and milestones.


## Service Tier Breakdown

### Tier 0: Foundation (2 services)
- configuration-service
- `configuration-service` (flags)

### Tier 1: Platform Core (8 services)
- api-gateway
- identity-service
- geolocation-service
- `geolocation-service` (zones)
- file-service
- `notification-service` (provider ACL)
- audit-service
- ledger-service

### Tier 2: Domain Foundations (13 services)
- `customer-service` (cross-persona profile)
- customer-service
- driver-service
- courier-service
- `driver-service` (vehicles)
- `customer-service` (addresses)
- `pricing-service` (tax)
- `pricing-service` (promotion)
- notification-service
- admin-service
- `admin-service` (support module)
- fraud-risk-service
- `restaurant-service` (merchant)

### Tier 3: Business Operations (13 services)
- pricing-service
- payment-service
- `payment-service` (wallet)
- restaurant-service
- `restaurant-service` (branch)
- `driver-service` (availability)
- `driver-service` (location)
- `courier-service` (tracking)
- `geolocation-service` (ETA/routing)
- `restaurant-service` (staff)
- `pricing-service` (loyalty rules) / `customer-service` (account)
- `trip-service` (scheduled)
- `trip-service` (safety)

### Tier 4: Core Business Logic (11 services)
- `restaurant-service` (menu)
- `restaurant-service` (inventory)
- `food-order-service` (cart)
- `trip-service` (ride-request)
- trip-service
- `driver-service` (dispatch)
- `payment-service` (driver earnings)
- `trip-service` / `food-order-service` / `search-service` (review projections)
- `driver-service` (incentives)

### Tier 5: Transaction Orchestration (7 services)
- `food-order-service` (checkout)
- food-order-service
- `food-order-service` (queue)
- `courier-service` (dispatch)
- `courier-service` (delivery)
- `payment-service` (ride saga)
- `payment-service` (food saga)
- `payment-service` (courier earnings)
- `payment-service` (merchant settlement)

### Tier 6: Analytics & Insights (4 services)
- search-service
- `reporting-service` (data lake)
- reporting-service
- `trip-service` (history)


## Technology Stack Distribution

### Kotlin + Spring Boot 4 (46 services)
Business cores, financial cores, integration sagas, CRUD services

**Examples:** configuration, customer, driver, payment, wallet, menu, cart, trip, food-order, delivery, restaurant-settlement

### Go (8 services)
Edge, hot path, high-throughput ingestion

**Examples:** api-gateway, driver-location, courier-tracking, eta-routing, geolocation, file, communication-gateway, driver-availability

### Python + FastAPI (4 services)
Math, ML, scoring, analytics

**Examples:** fraud-risk, courier-dispatch, driver-incentive, reporting

### Node.js / TypeScript (2 services, optional)
Configuration management

**Examples:** identity-service (can also be Kotlin)

## Critical Integration Events

### High-Volume Events (> 10k/sec)
- `driver.location.updated.v1`
- `courier.location.updated.v1`
- `audit.api.request.v1`

### Critical Path Events
- `payment.captured.v1` → ledger, wallet
- `trip.completed.v1` → ride-payment-integration → driver-earnings
- `delivery.completed.v1` → food-payment-integration → courier-earnings
- `configuration.updated.v1` → ALL services

### State Machine Events
- `trip.*` (7 states)
- `food.order.*` (9 states)
- `delivery.*` (7 states)
- `payment.*` (5 states)

## Implementation Approach

### Parallel Development Strategy
1. **Foundation team:** Tier 0-1 services (Weeks 1-4)
2. **Identity team:** User/driver/courier services (Weeks 5-8)
3. **Ride team:** Ride-hailing services (Weeks 13-20)
4. **Food team:** Food marketplace + delivery (Weeks 21-34)
5. **Platform team:** Analytics & enhancements (Weeks 35-40)

### Critical Path
```
configuration-service
  ↓
identity-service
  ↓
customer-service, driver-service
  ↓
pricing-service, payment-service
  ↓
`trip-service` (ride-request), trip-service
  ↓
`driver-service` (dispatch)
  ↓
`payment-service` (ride saga)
```

### Quality Gates
- [ ] All services have OpenAPI 3.x specs
- [ ] All services emit structured JSON logs
- [ ] All services have health/ready/started endpoints
- [ ] All services use outbox pattern for events
- [ ] All services use inbox pattern for idempotency
- [ ] All services have unit + integration + E2E tests
- [ ] All services meet SLO targets (T1: 99.95%, T2: 99.9%, T3: 99.5%)


## Reference Links

### Generated Plans
- [Master Service Plan](MASTER_SERVICE_PLAN.md) - Detailed service-by-service implementation
- [Service Integration Matrix](SERVICE_INTEGRATION_MATRIX.md) - Dependency mapping
- [Implementation Phases](IMPLEMENTATION_PHASES.md) - Week-by-week roadmap

### Architecture Documentation
- [System Overview](architecture/SYSTEM_OVERVIEW.md)
- [Microservices Map](architecture/MICROSERVICES_MAP.md)
- [Event Architecture](architecture/EVENT_ARCHITECTURE.md)
- [API Standards](architecture/API_STANDARDS.md)
- [Database Architecture](architecture/DATABASE_ARCHITECTURE.md)
- [Keycloak Architecture](architecture/KEYCLOAK_ARCHITECTURE.md)

### Service Documentation
Each service has 7 documentation files:
- README.md - Purpose, bounded context, responsibilities
- BRD.md - Business requirements
- SRS.md - Functional + non-functional requirements
- ERD.md - Data model
- INTEGRATION.md - APIs, events, contracts
- WORKFLOWS.md - Operational workflows
- TECH.md - Technology profile

**Example:** [configuration-service](services/configuration-service/)

## Next Steps

### 1. Complete Master Plan (High Priority)
Add detailed implementation plans for remaining 52 services:
- geolocation-service
- `geolocation-service` (zones)
- file-service
- `notification-service` (provider ACL)
- `customer-service` (cross-persona profile)
- ... (47 more)

### 2. Create Task Tracking System
- GitHub Issues/Projects
- Jira epics and stories
- Sprint planning aligned to phases

### 3. Team Formation
- **Foundation Team:** 3-4 engineers
- **Identity Team:** 2-3 engineers  
- **Ride Team:** 4-5 engineers
- **Food Team:** 4-5 engineers
- **Platform Team:** 2-3 engineers

### 4. Infrastructure Setup
- Kubernetes clusters per environment
- PostgreSQL 19 instances per service
- Kafka cluster with 50+ topics
- Redis clusters
- Keycloak multi-realm setup
- CI/CD pipelines per service
- Observability stack (OpenTelemetry, Prometheus, Grafana, Loki)

### 5. Development Environment
- Docker Compose profiles per service
- Seed data for all services
- Local Keycloak with test realms
- Local Kafka with test topics

## Success Metrics

### Development Velocity
- Services deployed per week: Target 1.5 services
- Sprint velocity: 40 story points per 2-week sprint
- Code review turnaround: < 24 hours

### Quality Metrics
- Test coverage: > 80% per service
- SLO achievement: T1 99.95%, T2 99.9%, T3 99.5%
- P99 latency: Within documented targets
- Zero critical security vulnerabilities
- All services pass load testing

### Operational Metrics
- Deployment frequency: Daily (per service)
- Mean time to recovery: < 30 minutes
- Change failure rate: < 5%
- Lead time for changes: < 1 week

---

**Last Updated:** 2026-08-04  
**Version:** 1.1  
**Status:** Phase 7 (Guaranteed Rewards + Rating-Based Pricing + Geo-Configured Pricing) spec-complete; implementation pending

## Phase 7 — Guaranteed Rewards & Rating-Based Pricing

- **`trip-service`** — per-trip + hourly/daily guaranteed rewards for
  driver and user. New events `trip.reward.granted.v1` and
  `trip.reward.reversed.v1`. Append-only `trip.trip_reward` and
  `trip.trip_reward_reversal` tables (REVOKE UPDATE/DELETE on
  both, mirroring the `ledger.postings` reversal rule).
- **`pricing-service`** — rating-density surge-pressure sub-pipeline
  (consumes `review.zone_aggregated.v1`, produces
  `pricing.rating_density.applied.v1`); frequent-rider loyalty
  discount sub-pipeline (consumes
  `loyalty.frequent_zone.aggregated.v1`, produces
  `pricing.loyalty_discount.applied.v1`); per-location and OD-pair
  overrides via `admin-service` (`pricing.geo_config.updated.v1`,
  `pricing.geo_overrides.matched.v1`). Cross-border trips produce
  both `tax_origin` and `tax_destination` line items.
- **`admin-service`** — new geo-config producer at
  `/v1/admin/pricing/geo-config[...]` (create / read / patch /
  disable / rollback / list); rollback requires break-glass and
  writes a new `pricing.rule_bindings_history` row.
- **``payment-service` (driver earnings)`** — consume grant as
  `type=guaranteed_topup`; expose
  `GET /v1/drivers/{id}/period-eligible-earnings?window=hourly|daily`.
- **``payment-service` (wallet)`** — consume the user-side grant, credit/debit the
  wallet.
- **``trip-service` / `food-order-service` / `search-service` (review projections)`** — new zone-aggregated driver-rating API
  and `review.zone_aggregated.v1` event.
- **``pricing-service` (loyalty rules) / `customer-service` (account)`** — new frequent-zones API and
  `loyalty.frequent_zone.aggregated.v1` event.
- **`ledger-service`** — informational consumer; new chart-of-account
  sub-account `2100_customer_credit_liability` for the user-side
  credit; driver top-up uses the existing `6302_guaranteed_minimum`.
- **`configuration-service`** — hosts the new config-key families
  (`trip.reward.*`, `pricing.rating_density.*`,
  `pricing.loyalty.frequent_rider.*`, `pricing.geo_overrides.*`).
- Cross-doc consistency on the 17-service accounting-impact list
  preserved; the canonical cross-service view is in
  [`workflows/ACCOUNTING_WORKFLOWS.md`](workflows/ACCOUNTING_WORKFLOWS.md)
  "Guaranteed Rewards — Driver Top-Up + Customer Credit".

See [`IMPLEMENTATION_PHASES.md`](IMPLEMENTATION_PHASES.md) "Phase 7"
and [`PLAN_INDEX.md`](PLAN_INDEX.md) for the per-service file list.
