# Master Service Implementation Plan

> **Purpose:** End-to-end implementation plan for all 20 active microservices (38 consolidated per ADR-0017) with tasks, dependencies, and integration mappings.
> 
> **Updated:** 2026-08-04
>
> **Structure:** Each service includes implementation phases, task breakdown, integration links, and dependency chains.
>
> ⚠️ **LEGACY DISCLAIMER (2026-08-06):** This document predates the locked 58 → 20 consolidation
> (see [ADR-0017](architecture/adrs/0017-20-service-architecture.md) and [MIGRATION_HUB.md](MIGRATION_HUB.md)).
> The `Phase N` sections and tasks below are preserved for history. The canonical source of truth
> for active implementation order is **[MASTER_PLAN.md](MASTER_PLAN.md)**, and the canonical
> per-service tasks live in each `services/<svc>/PLAN.md`. The absorbed-service references
> (e.g. ``payment-service` (wallet)`, ``trip-service` (ride-request)`, ``restaurant-service` (merchant)`)
> are retained as **internal-worker** aliases inside the surviving service; the obsolete
> directory listings for these absorbed names have been deleted.

---

## Table of Contents

1. [Implementation Strategy](#implementation-strategy)
2. [Service Dependency Tiers](#service-dependency-tiers)
3. [Domain-Based Implementation Order](#domain-based-implementation-order)
4. [Per-Service Plans](#per-service-plans)
5. [Integration Matrix](#integration-matrix)
6. [Critical Path Analysis](#critical-path-analysis)

---

## Implementation Strategy

### Phased Approach

**Phase 1: Foundation (Weeks 1-4)**
- Platform services (identity, configuration, feature flags)
- Shared geospatial services
- API Gateway
- Audit & observability

**Phase 2: Core Business (Weeks 5-12)**
- User management (customer, driver, courier)
- Vehicle & address management
- Pricing & tax engines
- Payment & wallet infrastructure

**Phase 3: Ride-Hailing (Weeks 13-20)**
- Ride request & trip management
- Driver availability & location tracking
- Dispatch & matching
- Ride payment integration

**Phase 4: Food Marketplace (Weeks 21-28)**
- Merchant & restaurant management
- Menu & inventory
- Cart & checkout
- Food order management

**Phase 5: Food Delivery (Weeks 29-34)**
- Courier dispatch & tracking
- Delivery orchestration
- Courier earnings

**Phase 6: Advanced Features (Weeks 35-40)**
- Analytics & reporting
- Fraud & risk management
- Loyalty & incentives
- Search & recommendations

---

## Service Dependency Tiers

Services organized by dependency depth (Tier 0 = no dependencies, Tier N = depends on Tier N-1):

### Tier 0: Foundation Services (No External Dependencies)
1. `configuration-service` - Base configuration
2. ``configuration-service` (flags)` - Feature toggles
3. Keycloak (External) - Identity provider
4. Map Providers (External) - Geolocation APIs
5. Payment Providers (External) - Payment gateways

### Tier 1: Platform Core
6. `api-gateway` - Entry point
7. `identity-service` - Identity management
8. `geolocation-service` - Geospatial queries
9. ``geolocation-service` (zones)` - Service areas
10. `file-service` - File storage
11. ``notification-service` (provider ACL)` - Multi-channel messaging
12. `audit-service` - Event logging
13. `ledger-service` - Financial ledger

### Tier 2: Domain Foundations
14. `customer-service` - Customer profiles + cross-persona + addresses + loyalty account (absorbs ``customer-service` (cross-persona profile)`, ``customer-service` (addresses)`)
15. `driver-service` - Driver profiles + KYC + online + location + match + incentives + vehicles (absorbs ``driver-service` (availability)`, ``driver-service` (location)`, ``driver-service` (dispatch)`, ``driver-service` (incentives)`, ``driver-service` (vehicles)`)
16. `courier-service` - Courier profiles + dispatch + tracking + delivery (absorbs ``courier-service` (dispatch)`, ``courier-service` (tracking)`, ``courier-service` (delivery)`)
17. `notification-service` - Notification orchestration + absorbed provider ACL (absorbs ``notification-service` (provider ACL)`)
18. `admin-service` - Admin operations + support module (absorbs ``admin-service` (support module)`)
19. `fraud-risk-service` - Risk scoring

### Tier 3: Business Operations
20. `pricing-service` - Dynamic pricing + tax + promotions + loyalty rules (absorbs ``pricing-service` (tax)`, ``pricing-service` (promotion)`, loyalty-rules of ``pricing-service` (loyalty rules) / `customer-service` (account)`)
21. `payment-service` - Payment orchestration + wallet + sagas + earnings + settlement + COD (absorbs ``payment-service` (wallet)`, ``payment-service` (ride saga)`, ``payment-service` (food saga)`, ``payment-service` (driver earnings)`, ``payment-service` (courier earnings)`, ``payment-service` (merchant settlement)`)
22. `restaurant-service` - Restaurant + merchant + branch + menu + inventory + staff (absorbs ``restaurant-service` (merchant)`, ``restaurant-service` (branch)`, ``restaurant-service` (menu)`, ``restaurant-service` (inventory)`, ``restaurant-service` (staff)`)
23. `geolocation-service` - Geocode + ETA + routing + zones (absorbs ``geolocation-service` (ETA/routing)`, ``geolocation-service` (zones)`)

### Tier 4: Core Business Logic
24. `trip-service` - Trip + ride-request + scheduled + safety + history + trip reviews (absorbs ``trip-service` (ride-request)`, ``trip-service` (scheduled)`, ``trip-service` (safety)`, ``trip-service` (history)`, trip-review slice of ``trip-service` / `food-order-service` / `search-service` (review projections)`)
25. `food-order-service` - Food orders + cart + checkout + queue + food reviews (absorbs ``food-order-service` (cart)`, ``food-order-service` (checkout)`, ``food-order-service` (queue)`, food-review slice of ``trip-service` / `food-order-service` / `search-service` (review projections)`)
26. `search-service` - Search indexing + search reviews (absorbs search-review slice of ``trip-service` / `food-order-service` / `search-service` (review projections)`)

### Tier 5: Transaction Orchestration
27. (none — `payment-service` covers this tier post-consolidation)

### Tier 6: Analytics & Insights
28. `reporting-service` - BI + dashboards + data lake ingestion (absorbs ``reporting-service` (data lake)`)

---

## Domain-Based Implementation Order

### Domain 1: Platform Foundation (4 services)
Priority: **CRITICAL** - Must be completed first
- [configuration-service](#configuration-service)
- [`configuration-service` (flags)](services/configuration-service/PLAN.md)
- [api-gateway](#api-gateway)
- [audit-service](#audit-service)

### Domain 2: Identity & Profile (5 services)
Priority: **CRITICAL** - Required by all business services
- [identity-service](#identity-service)
- [`customer-service` (cross-persona profile)](services/customer-service/PLAN.md)
- [customer-service](#customer-service)
- [driver-service](#driver-service)
- [courier-service](#courier-service)

### Domain 3: Geospatial (4 services)
Priority: **HIGH** - Required by ride and food domains
- [geolocation-service](#geolocation-service)
- [`geolocation-service` (zones)](services/geolocation-service/PLAN.md)
- [`driver-service` (location)](services/driver-service/PLAN.md)
- [`courier-service` (tracking)](services/courier-service/PLAN.md)

### Domain 4: Financial Core (5 services)
Priority: **CRITICAL** - Revenue-critical services
- [ledger-service](#ledger-service)
- [payment-service](#payment-service)
- [`payment-service` (wallet)](services/payment-service/PLAN.md)
- [`pricing-service` (tax)](services/pricing-service/PLAN.md)
- [pricing-service](#pricing-service)

### Domain 5: Support Services (7 services)
Priority: **HIGH** - Cross-cutting concerns
- [`driver-service` (vehicles)](services/driver-service/PLAN.md)
- [`customer-service` (addresses)](services/customer-service/PLAN.md)
- [file-service](#file-service)
- [`notification-service` (provider ACL)](services/notification-service/PLAN.md)
- [notification-service](#notification-service)
- [admin-service](#admin-service)
- [`admin-service` (support module)](services/admin-service/PLAN.md)

### Domain 6: Ride-Hailing (12 services)
Priority: **HIGH** - Core business line
- [`trip-service` (ride-request)](services/trip-service/PLAN.md)
- [trip-service](#trip-service)
- [`driver-service` (availability)](services/driver-service/PLAN.md)
- [`driver-service` (dispatch)](services/driver-service/PLAN.md)
- [`geolocation-service` (ETA/routing)](services/geolocation-service/PLAN.md)
- [`payment-service` (ride saga)](services/payment-service/PLAN.md)
- [`payment-service` (driver earnings)](services/payment-service/PLAN.md)
- [`driver-service` (incentives)](services/driver-service/PLAN.md)
- [`trip-service` (scheduled)](services/trip-service/PLAN.md)
- [`trip-service` (safety)](services/trip-service/PLAN.md)
- [`trip-service` (history)](services/trip-service/PLAN.md)
- [`trip-service` / `food-order-service` / `search-service` (review projections)](services/trip-service/PLAN.md) — see [trip](services/trip-service/PLAN.md), [food](services/food-order-service/PLAN.md), [search](services/search-service/PLAN.md)

### Domain 7: Food Marketplace (10 services)
Priority: **HIGH** - Core business line
- [`restaurant-service` (merchant)](services/restaurant-service/PLAN.md)
- [restaurant-service](#restaurant-service)
- [`restaurant-service` (branch)](services/restaurant-service/PLAN.md)
- [`restaurant-service` (staff)](services/restaurant-service/PLAN.md)
- [`restaurant-service` (menu)](services/restaurant-service/PLAN.md)
- [`restaurant-service` (inventory)](services/restaurant-service/PLAN.md)
- [`food-order-service` (cart)](services/food-order-service/PLAN.md)
- [`food-order-service` (checkout)](services/food-order-service/PLAN.md)
- [food-order-service](#food-order-service)
- [`food-order-service` (queue)](services/food-order-service/PLAN.md)

### Domain 8: Food Delivery (4 services)
Priority: **HIGH** - Completes food business
- [`courier-service` (dispatch)](services/courier-service/PLAN.md)
- [`courier-service` (delivery)](services/courier-service/PLAN.md)
- [`payment-service` (courier earnings)](services/payment-service/PLAN.md)
- [`payment-service` (food saga)](services/payment-service/PLAN.md)
- [`payment-service` (merchant settlement)](services/payment-service/PLAN.md)

### Domain 9: Platform Enhancements (7 services)
Priority: **MEDIUM** - Feature enhancements
- [`pricing-service` (promotion)](services/pricing-service/PLAN.md)
- [`pricing-service` (loyalty rules) / `customer-service` (account)](services/pricing-service/PLAN.md) — see [pricing](services/pricing-service/PLAN.md), [customer](services/customer-service/PLAN.md)
- [fraud-risk-service](#fraud-risk-service)
- [search-service](#search-service)
- [`reporting-service` (data lake)](services/reporting-service/PLAN.md)
- [reporting-service](#reporting-service)

---

## Per-Service Plans


### configuration-service

**Domain:** Platform Foundation  
**Tier:** 0 (No dependencies)  
**Priority:** CRITICAL  
**Technology:** Kotlin + Spring Boot 4  
**Criticality:** T1 (99.95% SLO)

#### Purpose
Single source of truth for business rules and numerical values (fares, fees, taxes, zones, ride types). Enables operators to change business rules without redeploying services.

#### Implementation Tasks

##### Phase 1: Foundation (Week 1)
- [ ] **Task 1.1:** Setup project structure
  - Create Kotlin Spring Boot 4 project with Gradle
  - Configure multi-stage Docker build with JRE-25
  - Setup Flyway migrations directory
  - Link: `services/configuration-service/TECH.md`
  
- [ ] **Task 1.2:** Database schema
  - Create PostgreSQL schema `configuration`
  - Implement tables: `documents`, `history`, `outbox`, `inbox`
  - Add partitioning for `history` (by month)
  - Add partitioning for `documents` (by scope_type)
  - Link: `services/configuration-service/ERD.md`
  
- [ ] **Task 1.3:** Core domain model
  - Implement `ConfigurationDocument` aggregate
  - Implement `ConfigurationVersion` value object
  - Implement scope resolution (hierarchical)
  - Add schema validation per key
  - Link: `services/configuration-service/README.md#3-responsibilities`

##### Phase 2: REST API (Week 1-2)
- [ ] **Task 2.1:** Read endpoints
  - `GET /v1/configurations/{key}` - Latest value
  - `GET /v1/configurations/{key}/versions` - History
  - `GET /v1/configurations/snapshot` - Bulk read
  - Link: `services/configuration-service/INTEGRATION.md#11-get-v1configurationskey`
  
- [ ] **Task 2.2:** Write endpoints
  - `POST /v1/configurations` - Create key
  - `PUT /v1/configurations/{key}/versions` - New version
  - `POST /v1/configurations/{key}/rollback` - Rollback
  - `POST /v1/configurations/{key}/deprecate` - Mark deprecated
  - Link: `services/configuration-service/INTEGRATION.md#12-put-v1configurationskeybe`
  
- [ ] **Task 2.3:** Long-poll streaming
  - `GET /v1/configurations/stream` - Update stream
  - Implement connection pool management
  - Add backpressure handling
  - Link: `services/configuration-service/INTEGRATION.md#16-get-v1configurationsstream`
  
- [ ] **Task 2.4:** Client-facing endpoints
  - `GET /v1/channels/{channel}/configurations` - Filtered subset
  - Implement channel-based filtering rules
  - Link: `services/configuration-service/INTEGRATION.md#18-get-v1channelschannelconfigurations`

##### Phase 3: Event Integration (Week 2)
- [ ] **Task 3.1:** Event publishing (Outbox pattern)
  - Implement transactional outbox table
  - Create outbox poller worker
  - Publish `configuration.updated.v1`
  - Publish `configuration.rolled_back.v1`
  - Publish `configuration.key.deprecated.v1`
  - Publish `configuration.snapshot.exported.v1`
  - Link: `services/configuration-service/INTEGRATION.md#3-produced-events`
  
- [ ] **Task 3.2:** Event consumption (Inbox pattern)
  - Implement inbox table for deduplication
  - Consume `customer.segment.changed.v1` → cache invalidation
  - Consume `zone.surge.updated.v1` → cache invalidation
  - Consume `feature_flag.updated.v1` → cache invalidation
  - Link: `services/configuration-service/INTEGRATION.md#4-consumed-events`

##### Phase 4: Caching & Performance (Week 2)
- [ ] **Task 4.1:** Redis integration
  - Setup Redis client
  - Implement cache-aside pattern
  - Add push-invalidation on updates
  - TTL strategy: 5 min for hot keys
  - Link: `services/configuration-service/TECH.md#4-cache`
  
- [ ] **Task 4.2:** Query optimization
  - Add indexes on `(scope_type, scope_id, key)`
  - Implement read replicas for history queries
  - Add connection pooling
  - Link: `services/configuration-service/ERD.md`

##### Phase 5: Security & Admin (Week 2-3)
- [ ] **Task 5.1:** Authentication
  - Integrate with Keycloak (Spring Security 7)
  - JWT validation with RS256
  - Required scopes: `config.read`, `config.admin`, `config.audit`
  - Link: `services/configuration-service/TECH.md#6-security`
  
- [ ] **Task 5.2:** Authorization
  - RBAC enforcement on write endpoints
  - Require `X-Audit-Reason` header for mutations
  - HMAC signature validation for high-value changes
  - Link: `services/configuration-service/README.md#14-security`
  
- [ ] **Task 5.3:** Admin endpoints
  - `POST /admin/v1/config/{key}/rollback` - Admin rollback
  - `GET /admin/v1/config/history` - Full history
  - `POST /admin/v1/config/bulk-publish` - Batch publish
  - Emit audit events for all admin actions
  - Link: `services/configuration-service/TECH.md#10-admin-endpoints--rbac`

##### Phase 6: Observability (Week 3)
- [ ] **Task 6.1:** Logging
  - Structured JSON logs to stdout
  - Fields: correlation_id, user_id, key, version, latency_ms
  - Link: `services/configuration-service/README.md#15-observability`
  
- [ ] **Task 6.2:** Metrics
  - RED metrics per route
  - Custom: `config_writes_total`, `config_reads_total`, `config_longpoll_connections`
  - Micrometer → Prometheus
  - Link: `services/configuration-service/TECH.md#7-observability`
  
- [ ] **Task 6.3:** Tracing
  - OpenTelemetry integration
  - Trace context propagation via Kafka
  - Sample rate: 100% errors, 10% success
  - Link: `services/configuration-service/INTEGRATION.md#7-distributed-tracing`
  
- [ ] **Task 6.4:** Health checks
  - `/actuator/health`, `/actuator/ready`, `/actuator/started`
  - Check DB, Redis, Kafka, Keycloak JWKS
  - Link: `services/configuration-service/TECH.md#7-observability`

##### Phase 7: Operations (Week 3-4)
- [ ] **Task 7.1:** Reconciliation job
  - Daily job: compare S3 snapshot vs DB state
  - Emit `reconciliation.drift.found.v1` on mismatch
  - Open support ticket on drift
  - Link: `services/configuration-service/INTEGRATION.md#5-reliability`
  
- [ ] **Task 7.2:** Snapshot export
  - Nightly cron job (3 AM)
  - Export to S3: `s3://trips-enjoy-platform-audit/configuration/snapshots/<yyyy>/<mm>/<dd>/`
  - Emit `configuration.snapshot.exported.v1`
  - Link: `services/configuration-service/README.md#13-configuration`
  
- [ ] **Task 7.3:** Deployment automation
  - K8s manifests (Deployment, Service, ConfigMap, Secret)
  - HPA: CPU > 60%, 2-5 replicas
  - Pre-upgrade Job for migrations
  - Link: `services/configuration-service/README.md#18-deployment`
  
- [ ] **Task 7.4:** Disaster recovery
  - Setup PITR with 5-minute RPO
  - Warm standby in another region
  - 30-day backup retention
  - Link: `services/configuration-service/README.md#19-disaster-recovery`

##### Phase 8: Testing (Week 4)
- [ ] **Task 8.1:** Unit tests
  - Test scope resolution logic
  - Test version conflict handling
  - Test schema validation
  - Coverage target: 80%+
  
- [ ] **Task 8.2:** Integration tests
  - Testcontainers for PostgreSQL, Redis, Kafka
  - Test outbox poller
  - Test long-poll behavior
  - Test idempotency
  
- [ ] **Task 8.3:** E2E tests
  - Test full CRUD cycle
  - Test rollback scenario
  - Test cache invalidation
  - Test event propagation

#### Integration Points

**Upstream Dependencies (Synchronous):**
- None (Tier 0 service)

**Downstream Consumers (Synchronous):**
- All 20 active services read configuration via REST API

**Event Dependencies:**
- **Publishes:**
  - `configuration.updated.v1` → All services
  - `configuration.rolled_back.v1` → All services
  - `configuration.key.deprecated.v1` → Consumer services
  - `configuration.snapshot.exported.v1` → `audit-service`, `reporting-service`
- **Consumes:**
  - `customer.segment.changed.v1` ← `customer-service`
  - `zone.surge.updated.v1` ← ``geolocation-service` (zones)`
  - `feature_flag.updated.v1` ← ``configuration-service` (flags)`

**External Integrations:**
- Keycloak (authentication)
- AWS S3 (snapshot export)
- HashiCorp Vault (secrets)

#### Success Criteria
- [ ] All REST endpoints operational
- [ ] Long-poll streaming working with 1000+ concurrent connections
- [ ] Event outbox/inbox patterns implemented
- [ ] Cache hit rate > 95% for hot keys
- [ ] P99 latency < 50ms for cached reads
- [ ] P99 latency < 200ms for uncached reads
- [ ] 99.95% SLO achieved
- [ ] Zero data loss on rollback

#### Related Documentation
- [README](services/configuration-service/README.md)
- [BRD](services/configuration-service/BRD.md)
- [SRS](services/configuration-service/SRS.md)
- [ERD](services/configuration-service/ERD.md)
- [INTEGRATION](services/configuration-service/INTEGRATION.md)
- [WORKFLOWS](services/configuration-service/WORKFLOWS.md)
- [TECH](services/configuration-service/TECH.md)
- [Architecture: Configuration](architecture/CONFIGURATION_ARCHITECTURE.md)

---

### `configuration-service` (flags)

**Domain:** Platform Foundation | **Tier:** 0 | **Tech:** Kotlin + Spring Boot 4 | **Criticality:** T2

#### Purpose
Platform-wide feature toggles, boolean/multivariate flags, percentage rollouts, A/B experiments, and kill switches managed centrally.

#### Tasks
- [ ] **DB:** Create `feature_flag` schema with tables: `flags`, `rules`, `evaluation_log` (partitioned by day), `outbox`, `inbox`
- [ ] **Domain:** Implement Flag aggregate, Rule evaluator, sticky assignment via murmur3 hashing
- [ ] **API:** `POST /v1/flags`, `PUT /v1/flags/{key}`, `POST /v1/flags/{key}/rules`, `DELETE /v1/flags/{key}/rules/{id}`
- [ ] **API:** `POST /v1/flags/{key}/evaluate` (sync evaluation), `GET /v1/flags/stream` (long-poll), `GET /v1/channels/{channel}/flags`
- [ ] **Events:** Publish `feature_flag.updated.v1`, `feature_flag.disabled.v1`, `feature_flag.experiment.started.v1`
- [ ] **Events:** Consume `customer.segment.changed.v1` → invalidate segment cache; consume `customer.created.v1` → pre-warm cache
- [ ] **Cache:** Redis — flag evaluation results (TTL 30s, push-invalidate)
- [ ] **Security:** Keycloak JWT, `flag.admin` role, HMAC for kill switch operations
- [ ] **Admin:** `/admin/v1/flags/{key}/force-disable`, `/admin/v1/flags/bulk-update`
- [ ] **Observability:** Metrics `flag_evaluations_total{key, variant}`, traces, health checks
- [ ] **K8s:** HPA CPU > 60%, 2-8 replicas, p99 < 20ms

#### Integration Links
- **Upstream:** None (Tier 0) | **Downstream:** All 20 active services
- **Publishes:** `feature_flag.updated.v1` → all services | `feature_flag.disabled.v1` → all services
- **Consumes:** `customer.segment.changed.v1` ← customer-service
- **Docs:** [README](services/configuration-service/README.md) · [INTEGRATION](services/configuration-service/INTEGRATION.md) · [TECH](services/configuration-service/TECH.md)

---

### api-gateway

**Domain:** Platform Foundation | **Tier:** 1 | **Tech:** Go + Envoy | **Criticality:** T1

#### Purpose
Single stateless north-south edge: TLS termination, JWT validation, claim translation, rate limiting, request routing, WAF rules. Owns no business data.

#### Tasks
- [ ] **Config:** Deploy Envoy with custom JWT filter, claim-to-header translation
- [ ] **Auth:** Validate RS256 JWT against Keycloak JWKS; enforce `iss`, `aud`, `exp`, `nbf`, `sub`
- [ ] **Auth:** Translate claims to `X-User-Id`, `X-User-Type`, `X-Roles`, `X-Scopes`, `X-Tenant-Id`
- [ ] **Rate Limiting:** Per-token, per-IP, per-route via Redis counters
- [ ] **Routing:** Load route table from `configuration-service`; hot-reload on `configuration.updated.v1`
- [ ] **Revocation:** Redis revocation set lookup on every request; consume `identity.session.revoked.v1`
- [ ] **Events:** Publish `audit.api.request.v1` for every authenticated request
- [ ] **Events:** Publish `gateway.rate_limit.exceeded.v1` and `gateway.circuit_breaker.opened.v1`
- [ ] **OpenAPI:** Aggregate all downstream specs at `/docs` and `/openapi.json`
- [ ] **Observability:** Metrics `gateway_requests_total`, `gateway_rate_limit_rejections_total`, circuit breaker state
- [ ] **K8s:** HPA RPS 5-100 replicas, p99 < 5ms, PDB `minAvailable: 3`

#### Integration Links
- **Routes traffic to:** All 20 active services
- **Sync Deps:** identity-service (introspect), Keycloak (JWKS)
- **Publishes:** `audit.api.request.v1` → audit-service | `gateway.rate_limit.exceeded.v1` → fraud-risk-service
- **Consumes:** `identity.session.revoked.v1`, `identity.user.suspended.v1`, `identity.user.disabled.v1`, `configuration.updated.v1`
- **Docs:** [README](services/api-gateway/README.md) · [INTEGRATION](services/api-gateway/INTEGRATION.md) · [TECH](services/api-gateway/TECH.md)

---

### audit-service

**Domain:** Platform Foundation | **Tier:** 1 | **Tech:** Go + Kafka | **Criticality:** T2

#### Purpose
Immutable audit log. Consumes every audit-relevant event from every service. Append-only with cryptographic hash chain. Strict-RBAC search API.

#### Tasks
- [ ] **DB:** Create `audit` schema, append-only `events` table (monthly partition), hash chain column
- [ ] **DB:** Reject UPDATE/DELETE at grant level; grant INSERT only
- [ ] **Consumer:** Subscribe to 50+ audit topics (see README 11 for full list)
- [ ] **Hash Chain:** Each row references previous row's SHA-256 hash
- [ ] **API:** `POST /v1/audit/search` (admin), `GET /v1/audit/events/{id}`, `GET /v1/audit/verify/{id}`
- [ ] **Export:** Nightly S3 export to `s3://trips-enjoy-platform-audit/audit/exports/<yyyy>/<mm>/<dd>/`
- [ ] **Retention:** 7 years for financial events, 1 year default
- [ ] **Observability:** Metrics `audit_events_ingested_total{topic}`, `audit_consumer_lag`, hash chain health
- [ ] **K8s:** HPA on Kafka lag, 2-8 replicas

#### Integration Links
- **Consumes (subset):** `admin.action.performed.v1`, `payment.*.v1`, `wallet.*.v1`, `ledger.posted.v1`, `trip.*.v1`, `food.order.*.v1`, `identity.user.*.v1`, `configuration.updated.v1`
- **Produces:** None (operational events only: `audit.consumer.lag.v1`, `audit.export.completed.v1`)
- **Docs:** [README](services/audit-service/README.md) · [INTEGRATION](services/audit-service/INTEGRATION.md) · [TECH](services/audit-service/TECH.md)

---

### identity-service

**Domain:** Platform Foundation | **Tier:** 1 | **Tech:** Node 20 / TypeScript | **Criticality:** T1

#### Purpose
Thin Keycloak adapter. Maps `kc_sub` → `identity_id`. Caches JWT claims. Propagates suspension, disablement, session revocation. GDPR erasure.

#### Tasks
- [ ] **DB:** Create `identity` schema: `identities` (kc_sub → identity_id), `identity_claims`, `identity_claim_history` (monthly partition)
- [ ] **DB:** Enable soft-delete on `identities`; column-level encryption for name/email/phone
- [ ] **SPI:** Deploy custom Keycloak EventListenerProvider JAR (user lifecycle → Kafka)
- [ ] **API:** `GET /v1/identities/{id}`, `GET /v1/identities?kc_sub=...`, `POST /v1/identities/introspect`
- [ ] **API:** `POST /v1/identities/{id}/suspend`, `POST /v1/identities/{id}/disable`, `POST /v1/identities/{id}/erase`
- [ ] **API:** `POST /v1/identities/{id}/logout-everywhere`, `GET /v1/identities/{id}/sessions`
- [ ] **Events:** Publish `identity.user.created.v1`, `identity.user.suspended.v1`, `identity.user.disabled.v1`, `identity.session.revoked.v1`
- [ ] **Events:** Consume `customer.created.v1`, `driver.created.v1`, `courier.created.v1`, `merchant.created.v1` → upsert identity row
- [ ] **GDPR:** Anonymize row on erase; preserve `identity_id` tombstone; retain financial refs
- [ ] **Cache:** Redis claim cache (TTL 5m, event-invalidate)
- [ ] **K8s:** HPA CPU > 60%, 2-8 replicas, p99 < 50ms

#### Integration Links
- **Sync Deps:** Keycloak admin API (write path only)
- **Publishes:** `identity.user.created.v1` → user-profile, customer, driver, courier, merchant | `identity.user.suspended.v1` → all profile services, api-gateway | `identity.session.revoked.v1` → api-gateway, notification
- **Consumes:** `customer.created.v1`, `driver.created.v1`, `courier.created.v1`, `merchant.created.v1`, `restaurant.created.v1`, `configuration.updated.v1`
- **Docs:** [README](services/identity-service/README.md) · [INTEGRATION](services/identity-service/INTEGRATION.md) · [KEYCLOAK_ARCHITECTURE](architecture/KEYCLOAK_ARCHITECTURE.md)

---

### ledger-service

**Domain:** Financial Core | **Tier:** 1 | **Tech:** Kotlin + Spring Boot 4 + jOOQ | **Criticality:** T1

#### Purpose
Platform's authoritative double-entry financial ledger. Source of truth for every money movement. Never calls other services. Pure persistence layer.

#### Tasks
- [ ] **DB:** Create `ledger` schema: `accounts` (chart of accounts), `postings` (monthly partition), `entries` (debit/credit pairs), `inbox`
- [ ] **DB:** Grant INSERT-only on `postings`/`entries`; no UPDATE/DELETE at DB level
- [ ] **DB:** `BigDecimal` / `NUMERIC(24,8)` for all money columns; enforce debit = credit per posting
- [ ] **Domain:** Implement `Posting` aggregate (balanced debit/credit), account types (asset/liability/equity/revenue/expense)
- [ ] **API:** `POST /v1/postings`, `GET /v1/postings/{id}`, `GET /v1/accounts`, `GET /v1/accounts/{code}/balance`
- [ ] **API:** `GET /v1/reports/trial-balance`, `GET /v1/reports/balance-sheet`, `GET /v1/reports/income-statement`
- [ ] **API:** `POST /v1/journal-entries` (admin only, with audit note)
- [ ] **Events:** Publish `ledger.posted.v1` for every posting; publish `ledger.audit.reconciliation_drift.v1`
- [ ] **Events:** Consume all money-movement events: `payment.captured.v1`, `wallet.credited.v1`, `merchant.settlement.accrued.v1`, `courier.earning.accrued.v1`, `driver.earning.accrued.v1`
- [ ] **Reconciliation:** Daily job vs wallet/earnings/settlement services; alert on drift
- [ ] **Cache:** Redis hot account balances
- [ ] **K8s:** HPA CPU > 60%, 3-15 replicas, p99 < 500ms, 10-year data retention

#### Integration Links
- **Sync Deps:** None (pure persistence)
- **Sync Callers:** payment, wallet, restaurant-settlement, courier-earnings, driver-earnings, food-payment-integration, ride-payment-integration
- **Publishes:** `ledger.posted.v1` → reporting, audit | `ledger.audit.reconciliation_drift.v1` → admin, support
- **Consumes:** `payment.captured.v1`, `payment.refund.completed.v1`, `wallet.credited.v1`, `wallet.debited.v1`, `merchant.settlement.accrued.v1`, `courier.earning.accrued.v1`, `driver.earning.accrued.v1`
- **Docs:** [README](services/ledger-service/README.md) · [INTEGRATION](services/ledger-service/INTEGRATION.md) · [DATABASE_ARCHITECTURE](architecture/DATABASE_ARCHITECTURE.md)

---

### geolocation-service

**Domain:** Geospatial | **Tier:** 1 | **Tech:** Go + PostGIS | **Criticality:** T1

#### Purpose
Geocode addresses to lat/long, compute ETAs, cache route data. Calls external map providers (Google/Mapbox/HERE). Source of truth for normalized location data.

#### Tasks
- [ ] **DB:** Create `geolocation` schema (PostGIS), tables: `geocode_cache` (TTL 30d), `eta_cache` (TTL 60s), `city_lookup_cache` (TTL 7d)
- [ ] **API:** `POST /v1/geocode` - address → lat/long | `POST /v1/reverse-geocode` - lat/long → address
- [ ] **API:** `GET /v1/eta?origin=&destination=` - ETA computation | `GET /v1/route?origin=&destination=`
- [ ] **API:** `GET /v1/city?lat=&lng=` - lookup city by coordinate
- [ ] **Cache:** Redis geocode cache (TTL 30d), ETA cache (TTL 60s, surge-aware), last-city (TTL 7d)
- [ ] **Events:** Publish `geolocation.geocoded.v1`, `geolocation.eta.computed.v1`
- [ ] **External:** Integrate Google Maps / Mapbox / HERE APIs; circuit breakers; fallback provider
- [ ] **Observability:** Metrics `geolocation_cache_hit_ratio{type}`, `geolocation_provider_latency_ms`, map provider errors
- [ ] **K8s:** HPA RPS 3-30 replicas, p99 < 30ms (cache hit)

#### Integration Links
- **Sync Deps:** Map provider (external)
- **Sync Callers:** driver-service, `customer-service` (addresses), `geolocation-service` (zones), `restaurant-service` (branch), `trip-service` (safety)
- **Publishes:** `geolocation.geocoded.v1`, `geolocation.eta.computed.v1` → zone, analytics
- **Docs:** [README](services/geolocation-service/README.md) · [INTEGRATION](services/geolocation-service/INTEGRATION.md) · [TECH](services/geolocation-service/TECH.md)

---

### `geolocation-service` (zones)

**Domain:** Geospatial | **Tier:** 1 | **Tech:** Kotlin + Spring Boot 4 + PostGIS | **Criticality:** T1

#### Purpose
Defines cities, service zones, surge zones, restricted zones, and zone operating hours. Primary source of "where we operate" for all services.

#### Tasks
- [ ] **DB:** Create `zone` schema with PostGIS geometry: `cities`, `service_zones`, `surge_zones`, `restricted_zones`, `zone_hours`
- [ ] **API:** CRUD for all zone types; `GET /v1/zones/lookup?lat=&lng=` - point-in-polygon query
- [ ] **API:** `GET /v1/zones/{id}/surge` - current surge factor; `PATCH /v1/zones/{id}/surge` (admin)
- [ ] **API:** `GET /v1/zones/{id}/hours` - operating hours; `GET /v1/zones/active?lat=&lng=`
- [ ] **Events:** Publish `zone.updated.v1`, `zone.surge.updated.v1` → configuration, pricing, branch
- [ ] **Cache:** Redis active polygons (TTL 1h, push-invalidate)
- [ ] **Sync Deps:** geolocation-service (coordinate validation on creation)
- [ ] **File Integration:** Import zone shapes from uploaded GeoJSON via file-service
- [ ] **Admin:** Import/export zone shapes; bulk surge update
- [ ] **K8s:** HPA CPU > 60%, 2-5 replicas, p99 < 200ms

#### Integration Links
- **Sync Deps:** geolocation-service
- **Publishes:** `zone.updated.v1` → branch, configuration | `zone.surge.updated.v1` → pricing, configuration
- **Consumes:** None (root entity)
- **Docs:** [README](services/geolocation-service/README.md) · [INTEGRATION](services/geolocation-service/INTEGRATION.md)

---

### file-service

**Domain:** Platform | **Tier:** 1 | **Tech:** Go + S3 | **Criticality:** T2

#### Purpose
File upload, storage, virus scanning, and presigned URL management. Metadata only in PostgreSQL; binary files in S3.

#### Tasks
- [ ] **DB:** Create `file` schema: `files` (metadata, scan status, owner), upload session state
- [ ] **API:** `POST /v1/files/upload` - initiate multipart upload → presigned S3 URL
- [ ] **API:** `POST /v1/files/{id}/complete` - finalize upload; trigger ClamAV scan
- [ ] **API:** `GET /v1/files/{id}` - metadata; `GET /v1/files/{id}/url` - presigned download URL
- [ ] **API:** `DELETE /v1/files/{id}` - soft delete + S3 delete
- [ ] **Virus Scan:** Async ClamAV scan after upload; update scan status; reject on threat
- [ ] **Events:** Publish `file.uploaded.v1`, `file.scanned.v1`, `file.deleted.v1`
- [ ] **Cache:** Redis upload session state (TTL during upload)
- [ ] **Security:** Owner-only access; admin override; signed URLs with TTL
- [ ] **K8s:** HPA RPS 3-30 replicas, p99 < 100ms (presigned URL mint)

#### Integration Links
- **Sync Deps:** S3, ClamAV
- **Sync Callers:** `restaurant-service` (menu) (photos), `driver-service` (vehicles) (docs), `customer-service` (cross-persona profile) (avatar), `geolocation-service` (zones) (shapes), `trip-service` (safety) (incident photos)
- **Publishes:** `file.uploaded.v1`, `file.scanned.v1`, `file.deleted.v1`
- **Docs:** [README](services/file-service/README.md) · [INTEGRATION](services/file-service/INTEGRATION.md)

---

### `notification-service` (provider ACL)

**Domain:** Platform | **Tier:** 1 | **Tech:** Go + Kafka | **Criticality:** T2

#### Purpose
Multi-channel message delivery (push, SMS, email). Routes to FCM, APNs, Twilio, AWS SES. Stateless; tracks delivery receipts in Redis.

#### Tasks
- [ ] **DB:** Create `comms_gateway` schema (stateless mostly): `provider_credentials`, `provider_health`, `send_log`
- [ ] **API:** `POST /v1/messages/push` - FCM/APNs push; `POST /v1/messages/sms` - Twilio SMS
- [ ] **API:** `POST /v1/messages/email` - AWS SES email; `POST /v1/messages/bulk` - batch send
- [ ] **Kafka Consumer:** Consume from `notification-service` outbound channel; fan out to providers
- [ ] **Dedup:** Redis delivery receipt dedup window (TTL 24h)
- [ ] **Circuit Breakers:** Per-provider circuit breakers; fallback routing
- [ ] **Events:** Publish `comms.sms.sent.v1`, `comms.email.sent.v1`, `comms.push.sent.v1`
- [ ] **Provider Health:** Track provider error rates; alert on degradation; automatic failover
- [ ] **K8s:** HPA RPS 3-50 replicas, p99 < 100ms

#### Integration Links
- **Sync Callers:** notification-service, `trip-service` (safety), `admin-service` (support module)
- **External:** FCM, APNs, Twilio, AWS SES
- **Publishes:** `comms.sms.sent.v1`, `comms.email.sent.v1`, `comms.push.sent.v1` → audit
- **Docs:** [README](services/notification-service/README.md) · [INTEGRATION](services/notification-service/INTEGRATION.md)

---

### `customer-service` (cross-persona profile)

**Domain:** Identity & Profile | **Tier:** 2 | **Tech:** Kotlin + Spring Boot 4 | **Criticality:** T2

#### Purpose
Stores user language preferences, notification preferences, device list, and avatar reference. Cross-cutting profile data for all user types.

#### Tasks
- [ ] **DB:** Create `user_profile` schema: `profiles`, `devices`, `notification_preferences`
- [ ] **API:** `GET /v1/profiles/{id}`, `PATCH /v1/profiles/{id}`, `PUT /v1/profiles/{id}/avatar`
- [ ] **API:** `GET /v1/profiles/{id}/devices`, `POST /v1/profiles/{id}/devices`, `DELETE /v1/profiles/{id}/devices/{device_id}`
- [ ] **API:** `GET /v1/profiles/{id}/notification-preferences`, `PATCH /v1/profiles/{id}/notification-preferences`
- [ ] **Events:** Consume `identity.user.created.v1` → create profile row
- [ ] **Events:** Publish `user.profile.updated.v1` → notification-service (preference changes)
- [ ] **Cache:** Redis profile (TTL 5m, event-invalidate)
- [ ] **File Integration:** Avatar upload via file-service presigned URL
- [ ] **K8s:** HPA CPU > 60%, 2-10 replicas, p99 < 200ms

#### Integration Links
- **Sync Deps:** identity-service, file-service (avatar)
- **Publishes:** `user.profile.updated.v1` → notification-service
- **Consumes:** `identity.user.created.v1` ← identity-service
- **Docs:** [README](services/customer-service/README.md) · [INTEGRATION](services/customer-service/INTEGRATION.md)

---

### customer-service

**Domain:** Identity & Profile | **Tier:** 2 | **Tech:** Kotlin + Spring Boot 4 | **Criticality:** T1

#### Purpose
Customer business profile: KYC tier, default payment method refs, lifetime value, suspension status. Source of truth for `customer_id`.

#### Tasks
- [ ] **DB:** Create `customer` schema: `customers`, `customer_payment_methods`, `customer_stats`
- [ ] **API:** `POST /v1/customers`, `GET /v1/customers/{id}`, `PATCH /v1/customers/{id}`
- [ ] **API:** `POST /v1/customers/{id}/suspend`, `POST /v1/customers/{id}/reinstate`
- [ ] **API:** `GET /v1/customers/{id}/payment-methods`, `POST /v1/customers/{id}/payment-methods`
- [ ] **Events:** Consume `identity.user.created.v1` → create customer row
- [ ] **Events:** Consume `payment.method.saved.v1` → update default payment method
- [ ] **Events:** Publish `customer.created.v1`, `customer.updated.v1`, `customer.suspended.v1`
- [ ] **Cache:** Redis profile (TTL 5m)
- [ ] **Fraud Integration:** Trigger fraud check on KYC tier change
- [ ] **K8s:** HPA CPU > 60%, 2-10 replicas, p99 < 200ms

#### Integration Links
- **Sync Deps:** identity-service, payment-service (ref only)
- **Publishes:** `customer.created.v1` → identity, driver, courier | `customer.suspended.v1` → payment, support, identity
- **Consumes:** `identity.user.created.v1`, `payment.method.saved.v1`
- **Docs:** [README](services/customer-service/README.md) · [INTEGRATION](services/customer-service/INTEGRATION.md)

---

### driver-service

**Domain:** Identity & Profile | **Tier:** 2 | **Tech:** Kotlin + Spring Boot 4 | **Criticality:** T1

#### Purpose
Driver business profile: KYC, document expiry, eligibility per city, ratings. Source of truth for `driver_id`.

#### Tasks
- [ ] **DB:** Create `driver` schema: `drivers`, `driver_documents`, `driver_eligibility`, `driver_ratings_snapshot`
- [ ] **API:** `POST /v1/drivers`, `GET /v1/drivers/{id}`, `PATCH /v1/drivers/{id}`
- [ ] **API:** `POST /v1/drivers/{id}/approve`, `POST /v1/drivers/{id}/suspend`, `POST /v1/drivers/{id}/reinstate`
- [ ] **API:** `GET /v1/drivers/{id}/documents`, `POST /v1/drivers/{id}/documents/{type}`
- [ ] **API:** `GET /v1/drivers/{id}/eligibility` - can-drive-in-city lookup
- [ ] **Events:** Consume `vehicle.registered.v1` → link vehicle to driver
- [ ] **Events:** Consume `document.expiring.v1` → trigger renewal workflow
- [ ] **Events:** Publish `driver.created.v1`, `driver.approved.v1`, `driver.suspended.v1`, `driver.document.expired.v1`
- [ ] **Cache:** Redis profile (TTL 5m)
- [ ] **K8s:** HPA CPU > 60%, 2-10 replicas, p99 < 200ms

#### Integration Links
- **Sync Deps:** identity-service, `driver-service` (vehicles), geolocation-service
- **Publishes:** `driver.created.v1` → identity | `driver.approved.v1` → driver-availability | `driver.suspended.v1` → driver-availability, trip, api-gateway
- **Consumes:** `vehicle.registered.v1`, `document.expiring.v1`
- **Docs:** [README](services/driver-service/README.md) · [INTEGRATION](services/driver-service/INTEGRATION.md)

---

### courier-service

**Domain:** Identity & Profile | **Tier:** 2 | **Tech:** Kotlin + Spring Boot 4 | **Criticality:** T1

#### Purpose
Courier business profile: KYC, vehicle type (bike/moto/cargo), shift schedule. Source of truth for `courier_id`.

#### Tasks
- [ ] **DB:** Create `courier` schema: `couriers`, `courier_documents`, `courier_shifts`, `courier_vehicles`
- [ ] **API:** `POST /v1/couriers`, `GET /v1/couriers/{id}`, `PATCH /v1/couriers/{id}`
- [ ] **API:** `POST /v1/couriers/{id}/approve`, `POST /v1/couriers/{id}/suspend`
- [ ] **API:** `GET /v1/couriers/{id}/availability`, `POST /v1/couriers/{id}/shift-schedule`
- [ ] **Events:** Consume `vehicle.registered.v1` → link vehicle to courier
- [ ] **Events:** Publish `courier.created.v1`, `courier.approved.v1`, `courier.suspended.v1`, `courier.shift.scheduled.v1`
- [ ] **Cache:** Redis claim cache (TTL 5m)
- [ ] **K8s:** HPA CPU > 60%, 2-5 replicas, p99 < 200ms

#### Integration Links
- **Sync Deps:** identity-service, `driver-service` (vehicles)
- **Publishes:** `courier.created.v1` → identity | `courier.approved.v1` → courier tracking | `courier.suspended.v1` → courier-dispatch
- **Consumes:** `vehicle.registered.v1`
- **Docs:** [README](services/courier-service/README.md) · [INTEGRATION](services/courier-service/INTEGRATION.md)

---

## Phase 7 — Cross-cutting feature: Guaranteed Rewards & Rating-Based Pricing (Weeks 41-44)

This phase is intentionally cross-cutting: rather than introducing a
new service, it layers new behavior onto the existing
`trip-service` and `pricing-service` and pushes the cross-service
agreement into the workflow docs and the 17-service
accounting-impact list.

- [`services/trip-service`](services/trip-service/README.md) —
  per-trip + hourly + daily guaranteed rewards for driver and
  user (configurable), emitted as `trip.reward.granted.v1` and
  `trip.reward.reversed.v1`. Append-only `trip.trip_reward` and
  `trip.trip_reward_reversal` tables. Idempotency-key
  `request:{request_id}:reward:{grant|reversal}`.
- [`services/pricing-service`](services/pricing-service/README.md) —
  three new sub-pipelines:
  * **B1 rating-density surge-pressure** — composes multiplicatively
    with the zone surge, capped at `pricing.surge.max_multiplier`.
    Adds `pricing.rating_density.applied.v1`.
  * **B2 frequent-rider loyalty discount** — applied AFTER the
    promotion, BEFORE tax, capped at `pricing.min_fare.{city_id}`.
    Adds `pricing.loyalty_discount.applied.v1`.
  * **B3 per-location and OD-pair overrides** — sourced from
    `admin-service`'s geo-config API. Adds
    `pricing.geo_overrides.matched.v1`. Cross-border trips produce
    both `tax_origin` and `tax_destination` line items.
- [`services/admin-service`](services/admin-service/README.md) —
  new producer `/v1/admin/pricing/geo-config[...]` with
  create / read / patch / disable / rollback / list. The rollback
  endpoint requires break-glass and writes a new
  `pricing.rule_bindings_history` row (never UPDATE/DELETE).
- [`services/payment-service`](services/payment-service/README.md) (driver-earnings worker) —
  consume the grant as `type=guaranteed_topup`, the reversal as
  `type=correction`. Expose
  `GET /v1/drivers/{id}/period-eligible-earnings?window=hourly|daily`
  for `trip-service`.
- [`services/payment-service`](services/payment-service/README.md) (wallet worker) —
  consume the user-side grant and credit/debit the customer wallet.
  Idempotency-key `request:{request_id}:reward:user:grant`.
- [`services/trip-service`](services/trip-service/README.md) /
  [`food-order-service`](services/food-order-service/README.md) /
  [`search-service`](services/search-service/README.md) (review projections) —
  new `GET /v1/zones/{zone_id}/driver-rating?window_minutes=15` and
  `review.zone_aggregated.v1` event (debounced per zone).
- [`services/pricing-service`](services/pricing-service/README.md) (loyalty rules) /
  [`customer-service`](services/customer-service/README.md) (account) —
  new `GET /v1/accounts/{customer_id}/frequent-zones?window_days=30`
  and `loyalty.frequent_zone.aggregated.v1` event.
- [`services/configuration-service`](services/configuration-service/README.md) —
  hosts the new config-key families (`trip.reward.*`,
  `pricing.rating_density.*`, `pricing.loyalty.frequent_rider.*`,
  `pricing.geo_overrides.*`).
- [`services/ledger-service`](services/ledger-service/README.md) —
  informational consumer of the two reward events; chart-of-account
  extensions: `6302_guaranteed_minimum` (existing) for the driver
  side and `2100_customer_credit_liability` (new) for the user
  side.

Cross-doc consistency is preserved on the 17-service
accounting-impact list per the project's
[[trips-enjoy-services-with-accounting-impact-section]] memory; the
canonical cross-service view lives in
[`workflows/ACCOUNTING_WORKFLOWS.md`](workflows/ACCOUNTING_WORKFLOWS.md)
"Guaranteed Rewards — Driver Top-Up + Customer Credit".

See also:
- [`IMPLEMENTATION_PHASES.md`](IMPLEMENTATION_PHASES.md) — "Phase 7"
- [`PLAN_INDEX.md`](PLAN_INDEX.md) — "Phase 7 (Weeks 41-44)" entry

---

