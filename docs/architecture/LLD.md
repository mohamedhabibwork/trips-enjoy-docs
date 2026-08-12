# Low-Level Design (LLD)

> **Platform:** Uber-like ride-hailing + food-delivery + chat platform
> **Scope:** Component-level design — the patterns every service follows, the per-service application of those patterns, and where to find implementation specifics.
> **Companion to:** [`HLD.md`](HLD.md) (system-level architecture).
> **Format:** This document is a **navigational hub** for the per-service + shared patterns. Each section points to the canonical pattern doc and then to the per-service docs that apply it.

---

## 1. Service template — the contract

Every service in `services/<name>/` produces **8 docs** (the `PLAN.md` is also required per project convention):

| File | Purpose |
|------|---------|
| `README.md` | Purpose, bounded context, actors, dependencies, API/event summary, security, observability, scaling |
| `BRD.md` | Business context, objectives, stakeholders, business rules, KPIs, acceptance criteria |
| `SRS.md` | Functional / non-functional / security / data requirements, validation, state transitions, idempotency, DR |
| `ERD.md` | Per-service schema, entities, Mermaid ER diagram, DDL, indexes, soft delete, JSONB, partitioning, retention |
| `INTEGRATION.md` | Inbound APIs, outbound APIs, produced events, consumed events, reliability (timeout, retry, breaker, outbox) |
| `WORKFLOWS.md` | Mermaid sequence + state diagrams, happy/alternate/failure paths, compensations |
| `TECH.md` | Per-service technology profile (language, framework, libraries, cache, admin endpoints, RBAC) |
| `PLAN.md` | Implementation tasks (T-XXX-NN IDs), phase markers, dependencies |

**Canonical template:** [`architecture/SERVICE_DOC_TEMPLATE.md`](SERVICE_DOC_TEMPLATE.md). **Style rules:** kebab-case service names, snake_case tables/columns, PascalCase entities, `domain.entity.event.vN` events.

## 2. Cross-service communication patterns

### 2.1 Synchronous (REST)

| Pattern | Where | Detail |
|---------|-------|--------|
| Outbound HTTP | every service | per-target timeout (default 1s), retry 3x with exponential backoff, circuit breaker, bulkhead (see [`architecture/FAILURE_HANDLING.md`](FAILURE_HANDLING.md)) |
| Inbound HTTP | every service | Bearer JWT, `Idempotency-Key` on POST, OpenAPI 3.x spec, JSON error envelope (see [`architecture/API_STANDARDS.md`](API_STANDARDS.md)) |
| Per-class isolation | every service | CRITICAL / DEGRADABLE / BEST-EFFORT classes drive retry/circuit/fallback (see [`architecture/SERVICE_ISOLATION.md`](SERVICE_ISOLATION.md)) |
| Downstream error propagation | every service | forward / translate / degrade / reject per canonical error code catalog (see [`architecture/DOWNSTREAM_ERROR_CATALOG.md`](DOWNSTREAM_ERROR_CATALOG.md)) |
| WebSocket | `chat-service` only | WSS terminated at `api-gateway`; fan-out via Redis Pub/Sub across replicas |

### 2.2 Asynchronous (Kafka events)

| Pattern | Where | Detail |
|---------|-------|--------|
| Producer (outbox) | every service that publishes events | transactional outbox pattern (see [ADR-0009](adrs/0009-transactional-outbox.md)); polling worker drains to Kafka |
| Consumer (inbox) | every service that consumes events | inbox dedup on `event_id`; 3 retries with backoff; DLQ per topic |
| Event envelope | every event | `event_id`, `occurred_at`, `aggregate_id`, `data`, `correlation_id`, `causation_id` (see [`architecture/EVENT_ARCHITECTURE.md`](EVENT_ARCHITECTURE.md)) |
| Polymorphic `request_id` | cross-service sagas | one canonical id per business request, saga-keyed (see [ADR-0020](adrs/0020-polymorphic-request-id.md)) |
| Partition key | every event | `aggregate_id` (preserves per-aggregate order) |
| Saga | default | compensating transactions (see [ADR-0010](adrs/0010-saga-pattern.md)) |
| Conductor workflows | 17 named cross-cutting workflows | Netflix Conductor for ride rewards / deal / refund / onboarding / service-request (see [ADR-0018](adrs/0018-workflow-engine-conductor.md), [`shared/CONDUCTOR_WORKFLOWS.md`](../shared/CONDUCTOR_WORKFLOWS.md)) |

### 2.3 Idempotency

- **Every state-changing HTTP endpoint**: requires `Idempotency-Key` header; service stores `idempotency_key → response` for 24h.
- **Every Kafka consumer**: dedups on `event_id` via inbox table (1-day TTL default; configurable per consumer).
- **Sagas**: idempotency keys are `request:{request_id}:{step}:{id}` (e.g. `request:{request_id}:reward:user:grant`) — polymorphic across ride / food / driver / courier / merchant.

## 3. Per-service pattern application

Every service's `TECH.md`, `INTEGRATION.md`, `WORKFLOWS.md` apply the above patterns. Quick pointers:

| Service | TECH.md | INTEGRATION.md | WORKFLOWS.md |
|---------|---------|----------------|--------------|
| [`api-gateway`](../services/api-gateway/TECH.md) | [TECH](../services/api-gateway/TECH.md) | [INTEGRATION](../services/api-gateway/INTEGRATION.md) | [WORKFLOWS](../services/api-gateway/WORKFLOWS.md) |
| [`identity-service`](../services/identity-service/TECH.md) | [TECH](../services/identity-service/TECH.md) | [INTEGRATION](../services/identity-service/INTEGRATION.md) | [WORKFLOWS](../services/identity-service/WORKFLOWS.md) |
| [`file-service`](../services/file-service/TECH.md) | [TECH](../services/file-service/TECH.md) | [INTEGRATION](../services/file-service/INTEGRATION.md) | [WORKFLOWS](../services/file-service/WORKFLOWS.md) |
| [`audit-service`](../services/audit-service/TECH.md) | [TECH](../services/audit-service/TECH.md) | [INTEGRATION](../services/audit-service/INTEGRATION.md) | [WORKFLOWS](../services/audit-service/WORKFLOWS.md) |
| [`configuration-service`](../services/configuration-service/TECH.md) | [TECH](../services/configuration-service/TECH.md) | [INTEGRATION](../services/configuration-service/INTEGRATION.md) | [WORKFLOWS](../services/configuration-service/WORKFLOWS.md) |
| [`notification-service`](../services/notification-service/TECH.md) | [TECH](../services/notification-service/TECH.md) | [INTEGRATION](../services/notification-service/INTEGRATION.md) | [WORKFLOWS](../services/notification-service/WORKFLOWS.md) |
| [`admin-service`](../services/admin-service/TECH.md) | [TECH](../services/admin-service/TECH.md) | [INTEGRATION](../services/admin-service/INTEGRATION.md) | [WORKFLOWS](../services/admin-service/WORKFLOWS.md) |
| [`reporting-service`](../services/reporting-service/TECH.md) | [TECH](../services/reporting-service/TECH.md) | [INTEGRATION](../services/reporting-service/INTEGRATION.md) | [WORKFLOWS](../services/reporting-service/WORKFLOWS.md) |
| [`fraud-risk-service`](../services/fraud-risk-service/TECH.md) | [TECH](../services/fraud-risk-service/TECH.md) | [INTEGRATION](../services/fraud-risk-service/INTEGRATION.md) | [WORKFLOWS](../services/fraud-risk-service/WORKFLOWS.md) |
| [`customer-service`](../services/customer-service/TECH.md) | [TECH](../services/customer-service/TECH.md) | [INTEGRATION](../services/customer-service/INTEGRATION.md) | [WORKFLOWS](../services/customer-service/WORKFLOWS.md) |
| [`search-service`](../services/search-service/TECH.md) | [TECH](../services/search-service/TECH.md) | [INTEGRATION](../services/search-service/INTEGRATION.md) | [WORKFLOWS](../services/search-service/WORKFLOWS.md) |
| [`driver-service`](../services/driver-service/TECH.md) | [TECH](../services/driver-service/TECH.md) | [INTEGRATION](../services/driver-service/INTEGRATION.md) | [WORKFLOWS](../services/driver-service/WORKFLOWS.md) |
| [`trip-service`](../services/trip-service/TECH.md) | [TECH](../services/trip-service/TECH.md) | [INTEGRATION](../services/trip-service/INTEGRATION.md) | [WORKFLOWS](../services/trip-service/WORKFLOWS.md) |
| [`pricing-service`](../services/pricing-service/TECH.md) | [TECH](../services/pricing-service/TECH.md) | [INTEGRATION](../services/pricing-service/INTEGRATION.md) | [WORKFLOWS](../services/pricing-service/WORKFLOWS.md) |
| [`restaurant-service`](../services/restaurant-service/TECH.md) | [TECH](../services/restaurant-service/TECH.md) | [INTEGRATION](../services/restaurant-service/INTEGRATION.md) | [WORKFLOWS](../services/restaurant-service/WORKFLOWS.md) |
| [`food-order-service`](../services/food-order-service/TECH.md) | [TECH](../services/food-order-service/TECH.md) | [INTEGRATION](../services/food-order-service/INTEGRATION.md) | [WORKFLOWS](../services/food-order-service/WORKFLOWS.md) |
| [`courier-service`](../services/courier-service/TECH.md) | [TECH](../services/courier-service/TECH.md) | [INTEGRATION](../services/courier-service/INTEGRATION.md) | [WORKFLOWS](../services/courier-service/WORKFLOWS.md) |
| [`geolocation-service`](../services/geolocation-service/TECH.md) | [TECH](../services/geolocation-service/TECH.md) | [INTEGRATION](../services/geolocation-service/INTEGRATION.md) | [WORKFLOWS](../services/geolocation-service/WORKFLOWS.md) |
| [`payment-service`](../services/payment-service/TECH.md) | [TECH](../services/payment-service/TECH.md) | [INTEGRATION](../services/payment-service/INTEGRATION.md) | [WORKFLOWS](../services/payment-service/WORKFLOWS.md) |
| [`ledger-service`](../services/ledger-service/TECH.md) | [TECH](../services/ledger-service/TECH.md) | [INTEGRATION](../services/ledger-service/INTEGRATION.md) | [WORKFLOWS](../services/ledger-service/WORKFLOWS.md) |
| [`chat-service`](../services/chat-service/TECH.md) *(Phase 7.7)* | [TECH](../services/chat-service/TECH.md) | [INTEGRATION](../services/chat-service/INTEGRATION.md) | [WORKFLOWS](../services/chat-service/WORKFLOWS.md) |

> **Per-service language profile (canonical):** [`services/RECOMMENDATIONS.md`](../services/RECOMMENDATIONS.md).
> - **Edge / hot path** (Go): `api-gateway`, `geolocation-service`, `configuration-service`, `notification-service`, `chat-service`, `file-service`, `audit-service`, `courier-service` (tracking)
> - **Business core** (Kotlin + Spring Boot 4): most domain services
> - **Financial / correctness** (Kotlin + BigDecimal + jOOQ): `payment-service`, `ledger-service`, `pricing-service`
> - **Math / scoring / ML** (Python + FastAPI): `fraud-risk-service`, `driver-service` (incentives)

## 4. Database patterns

Canonical doc: [`architecture/DATABASE_ARCHITECTURE.md`](DATABASE_ARCHITECTURE.md).

| Pattern | Where used | Detail |
|---------|-----------|--------|
| UUIDv7 PK | every entity | time-orderable ([ADR-0015](adrs/0015-uuidv7-for-ids.md)) |
| Audit columns | every mutable table | `created_at`, `updated_at`, `created_by`, `updated_by`, `deleted_at` (optional) |
| Cross-service references | column UUIDs WITHOUT DB FK | per `DATA_OWNERSHIP.md`; integrity is enforced at the application boundary |
| Outbox table | every event producer | `outbox` schema per service; polling worker drains to Kafka |
| Inbox table | every event consumer | dedup by `event_id`; 1-day TTL |
| Soft delete | most business entities | `deleted_at` nullable; partial indexes `WHERE deleted_at IS NULL` |
| JSONB | flexible attribute columns | sparse / optional / varying-shape attributes; cite the shape in `ERD.md` 8 |
| Range partitioning | high-volume logs (attempts, history, outbox) | monthly partitions; retention drives drop |
| PostGIS | geo data only (`geolocation-service` zones, routes) | [ADR-0007](adrs/0007-postgis-for-geospatial.md) |
| Flyway migrations | every service | forward-only; deployed as separate job |

## 5. Event patterns

Canonical doc: [`architecture/EVENT_ARCHITECTURE.md`](EVENT_ARCHITECTURE.md).

| Pattern | Detail |
|---------|--------|
| Topic naming | `<domain>.<entity>.<event>.v<N>` (e.g. `payment.attempted.v1`) |
| Envelope | `{ "event_id": UUIDv7, "occurred_at": RFC3339, "aggregate_id": UUID, "data": {...}, "correlation_id": UUID, "causation_id": UUID }` |
| Partition key | `aggregate_id` |
| Headers | `traceparent` (W3C OTel), `request_id` (ADR-0020 polymorphic), `causation_id`, `event_id` |
| Versioning | additive changes stay in `v1`; breaking → `v2`; old topic drained before cutover |
| DLQ | per topic `<topic>.dlq`; replay via admin tooling |
| Replay | reporting-service materializes domain events into queryable views ([`architecture/EVENT_ARCHITECTURE.md`](EVENT_ARCHITECTURE.md)) |
| Outbox → publish | transaction writes business rows + outbox row; poller drains outbox → Kafka with retries |

## 6. API patterns

Canonical doc: [`architecture/API_STANDARDS.md`](API_STANDARDS.md).

| Pattern | Detail |
|---------|--------|
| Versioning | `/v1/<resource>` URI; breaking changes get `/v2` |
| Auth | Bearer JWT (validated at the gateway); service-account JWTs for service-to-service |
| Idempotency | `Idempotency-Key` header required on every state-changing endpoint; stored 24h |
| Error envelope | `{ "code": "PLATFORM_CODE", "message": "human-readable", "correlationId": "uuid", "details": [...] }` |
| Pagination | cursor-based for large lists; page+size for admin tables |
| Time | RFC3339 UTC; display in user TZ at the edge |
| Currency | minor units (cents) + `currency` (ISO 4217) |
| IDs | UUIDv7; cross-service IDs are plain columns without DB FK |

## 7. Security patterns

Canonical doc: [`architecture/SECURITY_ARCHITECTURE.md`](SECURITY_ARCHITECTURE.md).

| Pattern | Detail |
|---------|--------|
| AuthN | Keycloak (Bearer JWT) at the gateway; mTLS inside the mesh |
| AuthZ | RBAC scopes `<service>.admin`; cross-cutting `platform.super_admin`, `support.admin` |
| Secrets | Vault per-path `secret/<service>/<key>`; rotated quarterly |
| PII | marked in ERD.md; encrypted at rest; retention documented |
| PCI | `payment-service` is PCI-DSS SAQ-A — gateway-hosted fields only; never store PAN/CVV |
| Webhook signature | per-gateway scheme (HMAC-SHA256/512, RSA-SHA256, MD5, SHA-256, PayPal SDK, PayMob HMAC, Kashier HMAC, none) — see `payment-service/GATEWAYS.md` |
| Break-glass | super-admin grant/revoke requires co-signature |

## 8. Observability patterns

Canonical doc: [`architecture/OBSERVABILITY.md`](OBSERVABILITY.md).

| Pattern | Detail |
|---------|--------|
| Logs | JSON to stdout with `correlation_id`, `request_id`, `service`, `trace_id` |
| Metrics | RED (rate, errors, duration) + business KPIs per service |
| Traces | OpenTelemetry; one root span per request; propagated through Kafka headers |
| Health | `/health` (liveness), `/ready` (readiness with dependencies), `/started` (startup probe) |
| Audit | `audit-service` consumes every `*.audit.*` + high-value events ([`SERVICE_INTEGRATION_MATRIX.md`](../SERVICE_INTEGRATION_MATRIX.md)) |
| Alerting | per-service SLO breach + DLQ growth + consumer lag |

## 9. Per-service stack baseline

Every service's `TECH.md` documents its specific stack. The platform-wide baseline (mandatory for all 21 services) is:

| Concern | Choice |
|---------|--------|
| Runtime | Per profile — Go 1.22 / Kotlin 2.2 on Spring Boot 4 / Node 22 / Python 3.12 |
| Database | PostgreSQL 19 (one schema per service) |
| Cache / sessions / rate-limit | Redis 7 (per-service instance) |
| Event broker | Apache Kafka |
| Identity | Keycloak (via `identity-service`) |
| Secrets | HashiCorp Vault |
| Observability | OpenTelemetry SDK → OTel Collector → Jaeger / Tempo / Prometheus / Loki |
| Container | Docker (multi-stage builds) |
| Orchestration | Kubernetes |

Canonical: [`shared/PLATFORM_BASELINE.md`](../shared/PLATFORM_BASELINE.md).

## 10. Testing patterns

Canonical doc: [`shared/TESTING.md`](../shared/TESTING.md).

| Layer | Tooling |
|-------|---------|
| Unit | JUnit 5 / Kotest (Kotlin); `testing` package (Go); `pytest` (Python) |
| Integration | Testcontainers (PostgreSQL, Kafka, Redis, Keycloak, Vault) |
| Contract | Pact (provider + consumer in CI) |
| E2E | docker-compose dev stack + REST/WebSocket scripts |
| Load | k6 / Gatling for hot-path services |
| Security | OWASP ZAP + Vault scan; dependency CVE scan |
| Mutation | PIT (Kotlin); go-mutesting (Go) |

## 11. Deployment patterns

Canonical doc: [`architecture/DEPLOYMENT_ARCHITECTURE.md`](DEPLOYMENT_ARCHITECTURE.md).

| Pattern | Detail |
|---------|--------|
| Image | `registry.platform.io/<service>:<version>` |
| Manifests | per-service Kustomize overlay per env |
| HPA | per-service metric (RPS / lag / latency) |
| Migrations | separate job before rollout; forward-only |
| Secrets | Vault sidecar / CSI driver |
| Probes | `/health`, `/ready`, `/started` |
| Rollout | blue/green per release; canary for payment-service + ledger-service |
| Rollback | image tag rollback (1 click); data migrations require ADR + manual |

## 12. Local dev patterns

Canonical doc: per-service `TECH.md` 17 (Local Development) + [`architecture/SERVICE_DOC_TEMPLATE.md`](SERVICE_DOC_TEMPLATE.md) §17.

| Tool | Purpose |
|------|---------|
| docker-compose | per-service dev stack (PostgreSQL, Kafka, Redis, Keycloak) |
| seed data | per-service `seeds/` (where applicable — e.g. notification-service, payment-service) |
| mock gateway server | `payment-service` ships a mock that emulates all 46 gateways behind a uniform REST API |
| hot reload | Tilt / skaffold (Kotlin); Air / Reflex (Go); uvicorn --reload (Python) |
| fixtures | per-test factory functions; no DB seed in test runs |

## 13. Shared library

Per `services/README.md` §28, the canonical shared library is the `platform-spring-boot-starter` (Java/Kotlin services). The Go services share idiomatic patterns but no JAR. The Python services share patterns via requirements pinning.

Canonical: [`shared/README.md`](../shared/README.md).

| Shared module | Consumers | Detail |
|---------------|-----------|--------|
| `platform-spring-boot-starter` | 12 Kotlin/Spring services | cross-cutting Spring Boot code (security, outbox, inbox, idempotency, observability, error envelope, JWT validation) |
| `platform-go-kit` | 6 Go services | idiomatic helpers (JWT, OTel, Kafka, Redis, PostgreSQL via pgx) |
| `platform-py-common` | 2 Python services | FastAPI auth, OTel, Redis, asyncpg |

## 14. Conventions (enforced)

Canonical doc: [`shared/CONVENTIONS.md`](../shared/CONVENTIONS.md).

- Service names: `kebab-case`, suffix `-service`
- DB tables / columns: `snake_case`
- Logical entities: `PascalCase` (not in code)
- REST URIs: `/v1/<resource>` (URI-versioned); breaking → `/v2`
- Events: `domain.entity.event.vN`
- Errors: JSON envelope, machine-readable `code`, `correlationId`, `details[]`
- Currency: minor units internally; formatting at the edge
- Time: RFC3339 UTC; display in user TZ at edge
- IDs: UUID columns without DB FKs across service boundaries
- Per the polymorphic-request_id refactor (ADR-0020): idempotency keys for sagas are `request:{request_id}:{step}:{id}`

## 15. Where to go next

- **System-level architecture (HLD)** → [`HLD.md`](HLD.md)
- **Per-service documentation contract** → [`architecture/SERVICE_DOC_TEMPLATE.md`](SERVICE_DOC_TEMPLATE.md)
- **Service catalog** → [`services/README.md`](../services/README.md)
- **Tech map** → [`services/RECOMMENDATIONS.md`](../services/RECOMMENDATIONS.md)
- **Service template (fields required per doc type)** → [`architecture/SERVICE_DOC_TEMPLATE.md`](SERVICE_DOC_TEMPLATE.md)
- **Service catalog (table form)** → [`architecture/MICROSERVICES_MAP.md`](MICROSERVICES_MAP.md)
- **Failure handling** → [`architecture/FAILURE_HANDLING.md`](FAILURE_HANDLING.md)
- **Service isolation** → [`architecture/SERVICE_ISOLATION.md`](SERVICE_ISOLATION.md)
- **Error catalog** → [`architecture/DOWNSTREAM_ERROR_CATALOG.md`](DOWNSTREAM_ERROR_CATALOG.md)
- **Conductor workflows** → [`shared/CONDUCTOR_WORKFLOWS.md`](../shared/CONDUCTOR_WORKFLOWS.md)
- **Type catalog (canonical brand → catalog key mapping)** → [`shared/TYPE_CATALOG.md`](../shared/TYPE_CATALOG.md)
- **OSS attribution** → [`shared/OSS_DEPENDENCIES.md`](../shared/OSS_DEPENDENCIES.md)
- **Platform baseline** → [`shared/PLATFORM_BASELINE.md`](../shared/PLATFORM_BASELINE.md)

---

## 16. Source docs this LLD consolidates

This LLD is a navigational summary of the following authoritative docs (full content lives in each):

- [`architecture/SERVICE_DOC_TEMPLATE.md`](SERVICE_DOC_TEMPLATE.md) — 8-doc contract per service
- [`architecture/EVENT_ARCHITECTURE.md`](EVENT_ARCHITECTURE.md) — event catalog, envelope, partition key, DLQ, versioning
- [`architecture/DATABASE_ARCHITECTURE.md`](DATABASE_ARCHITECTURE.md) — per-service schema, UUIDv7, audit columns, JSONB, partitioning
- [`architecture/API_STANDARDS.md`](API_STANDARDS.md) — REST conventions, error envelope, idempotency, pagination
- [`architecture/SECURITY_ARCHITECTURE.md`](SECURITY_ARCHITECTURE.md) — authn/authz, secrets, PII, PCI
- [`architecture/FAILURE_HANDLING.md`](FAILURE_HANDLING.md) — retry, circuit, outbox, saga, DLQ
- [`architecture/SERVICE_ISOLATION.md`](SERVICE_ISOLATION.md) — per-class isolation rules
- [`architecture/DOWNSTREAM_ERROR_CATALOG.md`](DOWNSTREAM_ERROR_CATALOG.md) — canonical error code catalog
- [`architecture/CONSISTENCY_STRATEGY.md`](CONSISTENCY_STRATEGY.md) — per-context consistency model
- [`architecture/CONFIGURATION_ARCHITECTURE.md`](CONFIGURATION_ARCHITECTURE.md) — config hierarchy, override rules
- [`architecture/DEPLOYMENT_ARCHITECTURE.md`](DEPLOYMENT_ARCHITECTURE.md) — Docker, K8s, HPA, blue/green, canary
- [`architecture/OBSERVABILITY.md`](OBSERVABILITY.md) — logs, metrics, traces, alerting
- [`architecture/KEYCLOAK_ARCHITECTURE.md`](KEYCLOAK_ARCHITECTURE.md) — realms, clients, token flow
- [`architecture/VALIDATION_REPORT.md`](VALIDATION_REPORT.md) — open risks
- [`shared/CONVENTIONS.md`](../shared/CONVENTIONS.md) — naming, formatting, conventions
- [`shared/PLATFORM_BASELINE.md`](../shared/PLATFORM_BASELINE.md) — PostgreSQL 19, Kafka, Keycloak, Redis, OTel, Vault
- [`shared/OSS_DEPENDENCIES.md`](../shared/OSS_DEPENDENCIES.md) — OSS license catalogue
- [`shared/CONDUCTOR_WORKFLOWS.md`](../shared/CONDUCTOR_WORKFLOWS.md) — Conductor workflow registry
- [`shared/TYPE_CATALOG.md`](../shared/TYPE_CATALOG.md) — shared types, ride types, platform-margin doctrine
- [`shared/DEAL_FEATURE.md`](../shared/DEAL_FEATURE.md) — Make-a-Deal kernel spec
- [`shared/TESTING.md`](../shared/TESTING.md) — testing strategy
- [`shared/README.md`](../shared/README.md) — shared library overview
- [`services/README.md`](../services/README.md) — service catalog
- [`services/RECOMMENDATIONS.md`](../services/RECOMMENDATIONS.md) — per-service tech map
- All 21 `services/*/TECH.md`, `INTEGRATION.md`, `WORKFLOWS.md` files (see §3 above)
- All 21 `services/*/ERD.md` files (per-service data model)
- [`SERVICE_INTEGRATION_MATRIX.md`](../SERVICE_INTEGRATION_MATRIX.md)
- ADRs: [ADR-0001](adrs/0001-microservices-architecture.md), [ADR-0002](adrs/0002-postgres-per-service.md), [ADR-0004](adrs/0004-rest-as-primary-api.md), [ADR-0005](adrs/0005-kafka-as-event-broker.md), [ADR-0006](adrs/0006-redis-for-cache-and-rate.md), [ADR-0007](adrs/0007-postgis-for-geospatial.md), [ADR-0008](adrs/0008-api-gateway.md), [ADR-0009](adrs/0009-transactional-outbox.md), [ADR-0010](adrs/0010-saga-pattern.md), [ADR-0014](adrs/0014-externalize-configuration.md), [ADR-0015](adrs/0015-uuidv7-for-ids.md), [ADR-0018](adrs/0018-workflow-engine-conductor.md), [ADR-0019](adrs/0019-request-id-at-the-edge.md), [ADR-0020](adrs/0020-polymorphic-request-id.md)