# Master Service Implementation Plan

> **Purpose:** End-to-end implementation plan for all 58 microservices with tasks, dependencies, and integration mappings.
> 
> **Updated:** 2026-08-04
>
> **Structure:** Each service includes implementation phases, task breakdown, integration links, and dependency chains.

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
2. `feature-flag-service` - Feature toggles
3. Keycloak (External) - Identity provider
4. Map Providers (External) - Geolocation APIs
5. Payment Providers (External) - Payment gateways

### Tier 1: Platform Core
6. `api-gateway` - Entry point
7. `identity-service` - Identity management
8. `geolocation-service` - Geospatial queries
9. `zone-service` - Service areas
10. `file-service` - File storage
11. `communication-gateway-service` - Multi-channel messaging
12. `audit-service` - Event logging
13. `ledger-service` - Financial ledger

### Tier 2: Domain Foundations
14. `user-profile-service` - User preferences
15. `customer-service` - Customer profiles
16. `driver-service` - Driver profiles
17. `courier-service` - Courier profiles
18. `vehicle-service` - Vehicle registry
19. `address-service` - Address management
20. `tax-service` - Tax calculations
21. `promotion-service` - Promotions & coupons
22. `notification-service` - Notification orchestration
23. `admin-service` - Admin operations
24. `support-service` - Support ticketing
25. `fraud-risk-service` - Risk scoring

### Tier 3: Business Operations
26. `pricing-service` - Dynamic pricing
27. `payment-service` - Payment orchestration
28. `wallet-service` - Wallet management
29. `merchant-service` - Merchant management
30. `restaurant-service` - Restaurant profiles
31. `branch-service` - Restaurant branches
32. `driver-availability-service` - Driver online status
33. `driver-location-service` - Location tracking
34. `courier-tracking-service` - Courier location tracking
35. `eta-routing-service` - ETA calculations

### Tier 4: Core Business Logic
36. `menu-service` - Menu management
37. `inventory-service` - Stock management
38. `cart-service` - Shopping cart
39. `ride-request-service` - Ride requests
40. `trip-service` - Trip management
41. `dispatch-service` - Ride dispatch
42. `driver-earnings-service` - Driver earnings
43. `restaurant-staff-service` - Staff management
44. `review-rating-service` - Reviews & ratings
45. `loyalty-service` - Loyalty program
46. `scheduled-ride-service` - Scheduled rides
47. `ride-safety-service` - Safety features

### Tier 5: Transaction Orchestration
48. `checkout-service` - Checkout orchestration
49. `food-order-service` - Food orders
50. `restaurant-order-mgmt-service` - Kitchen orders
51. `courier-dispatch-service` - Courier matching
52. `delivery-service` - Delivery orchestration
53. `ride-payment-integration-service` - Ride payment saga
54. `food-payment-integration-service` - Food payment saga
55. `driver-incentive-service` - Driver bonuses
56. `courier-earnings-service` - Courier earnings
57. `restaurant-settlement-service` - Merchant payouts

### Tier 6: Analytics & Insights
58. `search-service` - Search indexing
59. `analytics-service` - Data warehouse ingestion
60. `reporting-service` - BI & dashboards
61. `ride-history-service` - Historical trip data

---

## Domain-Based Implementation Order

### Domain 1: Platform Foundation (4 services)
Priority: **CRITICAL** - Must be completed first
- [configuration-service](#configuration-service)
- [feature-flag-service](#feature-flag-service)
- [api-gateway](#api-gateway)
- [audit-service](#audit-service)

### Domain 2: Identity & Profile (5 services)
Priority: **CRITICAL** - Required by all business services
- [identity-service](#identity-service)
- [user-profile-service](#user-profile-service)
- [customer-service](#customer-service)
- [driver-service](#driver-service)
- [courier-service](#courier-service)

### Domain 3: Geospatial (4 services)
Priority: **HIGH** - Required by ride and food domains
- [geolocation-service](#geolocation-service)
- [zone-service](#zone-service)
- [driver-location-service](#driver-location-service)
- [courier-tracking-service](#courier-tracking-service)

### Domain 4: Financial Core (5 services)
Priority: **CRITICAL** - Revenue-critical services
- [ledger-service](#ledger-service)
- [payment-service](#payment-service)
- [wallet-service](#wallet-service)
- [tax-service](#tax-service)
- [pricing-service](#pricing-service)

### Domain 5: Support Services (7 services)
Priority: **HIGH** - Cross-cutting concerns
- [vehicle-service](#vehicle-service)
- [address-service](#address-service)
- [file-service](#file-service)
- [communication-gateway-service](#communication-gateway-service)
- [notification-service](#notification-service)
- [admin-service](#admin-service)
- [support-service](#support-service)

### Domain 6: Ride-Hailing (12 services)
Priority: **HIGH** - Core business line
- [ride-request-service](#ride-request-service)
- [trip-service](#trip-service)
- [driver-availability-service](#driver-availability-service)
- [dispatch-service](#dispatch-service)
- [eta-routing-service](#eta-routing-service)
- [ride-payment-integration-service](#ride-payment-integration-service)
- [driver-earnings-service](#driver-earnings-service)
- [driver-incentive-service](#driver-incentive-service)
- [scheduled-ride-service](#scheduled-ride-service)
- [ride-safety-service](#ride-safety-service)
- [ride-history-service](#ride-history-service)
- [review-rating-service](#review-rating-service)

### Domain 7: Food Marketplace (10 services)
Priority: **HIGH** - Core business line
- [merchant-service](#merchant-service)
- [restaurant-service](#restaurant-service)
- [branch-service](#branch-service)
- [restaurant-staff-service](#restaurant-staff-service)
- [menu-service](#menu-service)
- [inventory-service](#inventory-service)
- [cart-service](#cart-service)
- [checkout-service](#checkout-service)
- [food-order-service](#food-order-service)
- [restaurant-order-mgmt-service](#restaurant-order-mgmt-service)

### Domain 8: Food Delivery (4 services)
Priority: **HIGH** - Completes food business
- [courier-dispatch-service](#courier-dispatch-service)
- [delivery-service](#delivery-service)
- [courier-earnings-service](#courier-earnings-service)
- [food-payment-integration-service](#food-payment-integration-service)
- [restaurant-settlement-service](#restaurant-settlement-service)

### Domain 9: Platform Enhancements (7 services)
Priority: **MEDIUM** - Feature enhancements
- [promotion-service](#promotion-service)
- [loyalty-service](#loyalty-service)
- [fraud-risk-service](#fraud-risk-service)
- [search-service](#search-service)
- [analytics-service](#analytics-service)
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
- All 58 services read configuration via REST API

**Event Dependencies:**
- **Publishes:**
  - `configuration.updated.v1` → All services
  - `configuration.rolled_back.v1` → All services
  - `configuration.key.deprecated.v1` → Consumer services
  - `configuration.snapshot.exported.v1` → `audit-service`, `reporting-service`
- **Consumes:**
  - `customer.segment.changed.v1` ← `customer-service`
  - `zone.surge.updated.v1` ← `zone-service`
  - `feature_flag.updated.v1` ← `feature-flag-service`

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

### feature-flag-service

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
- **Upstream:** None (Tier 0) | **Downstream:** All 58 services
- **Publishes:** `feature_flag.updated.v1` → all services | `feature_flag.disabled.v1` → all services
- **Consumes:** `customer.segment.changed.v1` ← customer-service
- **Docs:** [README](services/feature-flag-service/README.md) · [INTEGRATION](services/feature-flag-service/INTEGRATION.md) · [TECH](services/feature-flag-service/TECH.md)

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
- **Routes traffic to:** All 58 services
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
- [ ] **Consumer:** Subscribe to 50+ audit topics (see README §11 for full list)
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
- **Sync Callers:** driver-service, address-service, zone-service, branch-service, ride-safety-service
- **Publishes:** `geolocation.geocoded.v1`, `geolocation.eta.computed.v1` → zone, analytics
- **Docs:** [README](services/geolocation-service/README.md) · [INTEGRATION](services/geolocation-service/INTEGRATION.md) · [TECH](services/geolocation-service/TECH.md)

---

### zone-service

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
- **Docs:** [README](services/zone-service/README.md) · [INTEGRATION](services/zone-service/INTEGRATION.md)

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
- **Sync Callers:** menu-service (photos), vehicle-service (docs), user-profile-service (avatar), zone-service (shapes), ride-safety-service (incident photos)
- **Publishes:** `file.uploaded.v1`, `file.scanned.v1`, `file.deleted.v1`
- **Docs:** [README](services/file-service/README.md) · [INTEGRATION](services/file-service/INTEGRATION.md)

---

### communication-gateway-service

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
- **Sync Callers:** notification-service, ride-safety-service, support-service
- **External:** FCM, APNs, Twilio, AWS SES
- **Publishes:** `comms.sms.sent.v1`, `comms.email.sent.v1`, `comms.push.sent.v1` → audit
- **Docs:** [README](services/communication-gateway-service/README.md) · [INTEGRATION](services/communication-gateway-service/INTEGRATION.md)

---

### user-profile-service

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
- **Docs:** [README](services/user-profile-service/README.md) · [INTEGRATION](services/user-profile-service/INTEGRATION.md)

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
- **Sync Deps:** identity-service, vehicle-service, geolocation-service
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
- **Sync Deps:** identity-service, vehicle-service
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
  `trip:{trip_id}:reward:{grant|reversal}`.
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
- [`services/driver-earnings-service`](services/driver-earnings-service/README.md) —
  consume the grant as `type=guaranteed_topup`, the reversal as
  `type=correction`. Expose
  `GET /v1/drivers/{id}/period-eligible-earnings?window=hourly|daily`
  for `trip-service`.
- [`services/wallet-service`](services/wallet-service/README.md) —
  consume the user-side grant and credit/debit the customer wallet.
  Idempotency-key `trip:{trip_id}:reward:user:grant`.
- [`services/review-rating-service`](services/review-rating-service/README.md) —
  new `GET /v1/zones/{zone_id}/driver-rating?window_minutes=15` and
  `review.zone_aggregated.v1` event (debounced per zone).
- [`services/loyalty-service`](services/loyalty-service/README.md) —
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
§"Guaranteed Rewards — Driver Top-Up + Customer Credit".

See also:
- [`IMPLEMENTATION_PHASES.md`](IMPLEMENTATION_PHASES.md) — "Phase 7"
- [`PLAN_INDEX.md`](PLAN_INDEX.md) — "Phase 7 (Weeks 41-44)" entry

---

