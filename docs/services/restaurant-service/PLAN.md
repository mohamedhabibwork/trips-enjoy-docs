# restaurant-service — Implementation Plan

**Domain:** Food Marketplace
**Tier:** 1 (position 12 of 21; `DEPLOYMENT_ORDER.md` §2)
**Technology:** Kotlin/Spring
**Criticality:** T2 (99.9%)
**DB Schema:** `restaurant`
**Cache:** Redis — restaurant profile
**HPA:** CPU 60%, 2–4, p99 < 100ms

---

## Purpose

**Phase 4 — Food Marketplace.** Begin only after Phase 2 merchant/identity are live.

This PLAN.md is the source of truth for **how** `restaurant-service` is built. The 10-phase
backbone below mirrors the locked Phase 1-7 layout in `IMPLEMENTATION_PHASES.md`
plus the Phase 7 cross-cutting and Phase 7.5 Make-a-Deal addenda where this
service participates.

---

## Tasks

### Phase 1 — Database & Domain Model

| ID | Task | Status | Depends-On | Required Role(s) | Approver Role | Co-Signer Role | Break-Glass? |
|---|---|---|---|---|---|---|---|
| T-RES-01 | Create schema `restaurant`: tables per `ERD.md` (partitioned by time/zone/hash per data shape) | pending | — | restaurant.admin | restaurant.admin | — | — |
| T-RES-02 | Write Flyway/migrations (forward-only); install `pg_partman` if the parent table is time-partitioned | pending | T-RES-01 | restaurant.admin | restaurant.admin | — | — |
| T-RES-03 | Implement the aggregate root, immutability invariants, and append-only audit constraints | pending | T-RES-02 | restaurant.admin | restaurant.admin | — | — |
| T-RES-04 | Add `restaurant.outbox` and `restaurant.inbox` for reliable eventing | pending | T-RES-03 | restaurant.outbox, restaurant.inbox | restaurant.outbox | — | — |
### Phase 2 — REST API

| ID | Task | Status | Depends-On | Required Role(s) | Approver Role | Co-Signer Role | Break-Glass? |
|---|---|---|---|---|---|---|---|
| T-RES-01 | CRUD endpoints per `INTEGRATION.md` (versioned `/v1/...`) | pending | — | restaurant.admin | restaurant.admin | — | — |
| T-RES-02 | Idempotency-Key middleware on every mutating route | pending | T-RES-01 | restaurant.admin | restaurant.admin | — | — |
| T-RES-03 | Pagination + filtering on every list endpoint | pending | T-RES-02 | restaurant.admin | restaurant.admin | — | — |
| T-RES-04 | Health endpoints: `/actuator/health`, `/ready`, `/started` | pending | T-RES-03 | restaurant.admin | restaurant.admin | — | — |
### Phase 3 — Event Publishing

| ID | Task | Status | Depends-On | Required Role(s) | Approver Role | Co-Signer Role | Break-Glass? |
|---|---|---|---|---|---|---|---|
| T-RES-01 | Transactional outbox + poller (200 ms interval, DLQ) | pending | — | restaurant.admin | restaurant.admin | — | — |
| T-RES-02 | Publish events per the integration map below | pending | T-RES-01 | restaurant.admin | restaurant.admin | — | — |
| T-RES-03 | Avro schema registered in Schema Registry on first publish | pending | T-RES-02 | restaurant.admin | restaurant.admin | — | — |
### Phase 4 — Event Consumption

| ID | Task | Status | Depends-On | Required Role(s) | Approver Role | Co-Signer Role | Break-Glass? |
|---|---|---|---|---|---|---|---|
| T-RES-01 | Idempotent inbox; LSN/offset dedup window 7 days | pending | — | restaurant.admin | restaurant.admin | — | — |
| T-RES-02 | Single consumer per partition; pause-on-error with backoff | pending | T-RES-01 | restaurant.admin | restaurant.admin | — | — |
| T-RES-03 | Dead-letter topic after N retries | pending | T-RES-02 | restaurant.admin | restaurant.admin | — | — |
### Phase 5 — Caching

| ID | Task | Status | Depends-On | Required Role(s) | Approver Role | Co-Signer Role | Break-Glass? |
|---|---|---|---|---|---|---|---|
| T-RES-01 | Redis — restaurant profile | pending | — | restaurant.admin | restaurant.admin | — | — |
| T-RES-02 | Push-invalidate on every write that affects the cache key | pending | T-RES-01 | restaurant.admin | restaurant.admin | — | — |
| T-RES-03 | Stampede protection on hot keys (single-flight) | pending | T-RES-02 | restaurant.admin | restaurant.admin | — | — |
### Phase 6 — External Integrations

| ID | Task | Status | Depends-On | Required Role(s) | Approver Role | Co-Signer Role | Break-Glass? |
|---|---|---|---|---|---|---|---|
| T-RES-01 | Sync dependencies: `restaurant-service` (merchant), geolocation-service | pending | — | restaurant.admin | restaurant.admin | — | — |
| T-RES-02 | Circuit breakers on every outbound call (Resilience4j / polly) | pending | T-RES-01 | restaurant.admin | restaurant.admin | — | — |
| T-RES-03 | OAuth2 client credentials + mTLS for service-to-service | pending | T-RES-02 | restaurant.admin | restaurant.admin | — | — |
| T-RES-04 | HashiCorp Vault for DB credentials and signing keys | pending | T-RES-03 | restaurant.admin | restaurant.admin | — | — |
### Phase 7 — Security

| ID | Task | Status | Depends-On | Required Role(s) | Approver Role | Co-Signer Role | Break-Glass? |
|---|---|---|---|---|---|---|---|
| T-RES-01 | JWT bearer auth via Keycloak, realm `platform-internal` | pending | — | restaurant.admin | restaurant.admin | — | — |
| T-RES-02 | Required scopes/roles per `INTEGRATION.md` | pending | T-RES-01 | restaurant.admin | restaurant.admin | — | — |
| T-RES-03 | `X-Audit-Reason` header required on admin mutations | pending | T-RES-02 | restaurant.admin | restaurant.admin | — | — |
| T-RES-04 | Field-level encryption for PII (driver license, payment method) | pending | T-RES-03 | restaurant.admin | restaurant.admin | — | — |
### Phase 8 — Observability

| ID | Task | Status | Depends-On | Required Role(s) | Approver Role | Co-Signer Role | Break-Glass? |
|---|---|---|---|---|---|---|---|
| T-RES-01 | Structured JSON logs with `correlation_id`, `user_id`, `tenant_id` | pending | — | restaurant.admin | restaurant.admin | — | — |
| T-RES-02 | Metrics: RED per route + business counters specific to this service | pending | T-RES-01 | restaurant.admin | restaurant.admin | — | — |
| T-RES-03 | OpenTelemetry traces with child spans; long-poll spans open until response | pending | T-RES-02 | restaurant.admin | restaurant.admin | — | — |
| T-RES-04 | Alerts in Grafana: p99 latency, error rate, consumer lag | pending | T-RES-03 | restaurant.admin | restaurant.admin | — | — |
### Phase 9 — Testing

| ID | Task | Status | Depends-On | Required Role(s) | Approver Role | Co-Signer Role | Break-Glass? |
|---|---|---|---|---|---|---|---|
| T-RES-01 | Unit tests: 80%+ branch coverage on the aggregate | pending | — | restaurant.admin | restaurant.admin | — | — |
| T-RES-02 | Integration tests: Testcontainers (PostgreSQL, Kafka, Redis) | pending | T-RES-01 | restaurant.admin | restaurant.admin | — | — |
| T-RES-03 | Contract tests: Producer Avro schemas pinned in CI | pending | T-RES-02 | restaurant.admin | restaurant.admin | — | — |
| T-RES-04 | E2E test per major user journey in `WORKFLOWS.md` | pending | T-RES-03 | restaurant.admin | restaurant.admin | — | — |
### Phase 10 — Deployment

| ID | Task | Status | Depends-On | Required Role(s) | Approver Role | Co-Signer Role | Break-Glass? |
|---|---|---|---|---|---|---|---|
| T-RES-01 | Kubernetes manifests: Deployment, Service, HPA, PDB | pending | — | restaurant.admin | restaurant.admin | — | — |
| T-RES-02 | Pre-upgrade Job for migrations | pending | T-RES-01 | restaurant.admin | restaurant.admin | — | — |
| T-RES-03 | Resource limits per `DEPLOYMENT_ARCHITECTURE.md` | pending | T-RES-02 | restaurant.admin | restaurant.admin | — | — |
| T-RES-04 | Smoke test in staging before production rollout | pending | T-RES-03 | restaurant.admin | restaurant.admin | — | — |
### Phase 7.7 — In-App Chat (cross-cutting, *passive*)

This service participates in Phase 7.7 (in-app chat kernel added 2026-08-12)
as a **passive participant** — it does not bootstrap or close chat threads.
Single source of truth: [`services/chat-service/PLAN.md`](../chat-service/PLAN.md).
The `food_order_chat` thread is bootstrapped by `food-order-service` (which
emits `food.order.accepted.v1`); this service contributes the
**restaurant-side participant profile** (display name, locale, photo)
that `chat-service` resolves via `GET /v1/identities/{id}` against the
internal-worker `restaurant-service (staff)` mapping.

| ID | Task | Status | Depends-On | Required Role(s) | Approver Role | Co-Signer Role | Break-Glass? |
|---|---|---|---|---|---|---|---|
| T-RES-P77-01 | Expose `GET /v1/restaurants/{id}/chat-profile` (read-only) for `chat-service` participant resolution — returns `display_name`, `locale`, `avatar_url`, `is_online` (cached in Redis with TTL 5m); consumed by `chat-service` per [`services/chat-service/INTEGRATION.md`](../chat-service/INTEGRATION.md) §3.1 | pending | — | restaurant.admin | restaurant.admin | — | — |
| T-RES-P77-02 | Consume `chat.user.erased.v1` from `chat-service` to apply GDPR-forget on the restaurant side per [`architecture/DATA_OWNERSHIP.md`](../../architecture/DATA_OWNERSHIP.md) — remove cached chat-profile entry, emit `chat.user.erased.acknowledged.v1` for the audit consumer | pending | T-RES-P77-01 | restaurant.admin | restaurant.admin | platform.privacy | yes (P0 GDPR) |
| T-RES-P77-03 | No direct chat emission from this service. All chat-related events are produced by `chat-service` itself per [`services/chat-service/INTEGRATION.md`](../chat-service/INTEGRATION.md) §3 | — | — | — | — | — | — |

---

## Integration Map

### Sync Dependencies (outbound calls)
| Target | Endpoint | Purpose | Circuit Breaker |
|--------|----------|---------|----------------|
| ``restaurant-service` (merchant)` | per `INTEGRATION.md` | sync dependency | Yes |
| `geolocation-service` | per `INTEGRATION.md` | sync dependency | Yes |

### Events Published
| Event | Topic | Trigger | Key Consumers |
|-------|-------|---------|--------------|
| `restaurant.created` | derived from name | see INTEGRATION.md | see INTEGRATION.md |
| `restaurant.approved` | derived from name | see INTEGRATION.md | see INTEGRATION.md |
| `restaurant.online` | derived from name | see INTEGRATION.md | see INTEGRATION.md |
| `restaurant.offline` | derived from name | see INTEGRATION.md | see INTEGRATION.md |

### Events Consumed
| Event | Producer | Handler |
|-------|----------|---------|
| `merchant.approved` | see INTEGRATION.md | see INTEGRATION.md |

---

## Acceptance Criteria
- [ ] All REST endpoints return correct status codes
- [ ] p99 latency within SLO target (T2 (99.9%))
- [ ] All events published reliably via outbox pattern
- [ ] Zero data leakage across service boundaries
- [ ] 80%+ unit test coverage
- [ ] OpenAPI 3.x spec published and validated
- [ ] `INTEGRATION.md` is the source of truth for endpoints and events

---

## Related Docs
- [README](README.md) · [BRD](BRD.md) · [SRS](SRS.md) · [ERD](ERD.md) · [INTEGRATION](INTEGRATION.md) · [WORKFLOWS](WORKFLOWS.md) · [TECH](TECH.md)
- [Master Plan](../../MASTER_PLAN.md)
- [Implementation Phases](../../IMPLEMENTATION_PHASES.md)
- [Service Integration Matrix](../../SERVICE_INTEGRATION_MATRIX.md)

### Phase 7.6 — Conductor Workers

This service runs Conductor workers for the following workflows per
[ADR-0018](../../architecture/adrs/0018-workflow-engine-conductor.md)
and [`shared/CONDUCTOR_WORKFLOWS.md`](../../shared/CONDUCTOR_WORKFLOWS.md).
The full worker contract (task names, idempotency-key namespaces,
Kafka signal mapping, compensation responsibilities) is in
[`INTEGRATION.md`](./INTEGRATION.md) "Conductor Workers".

| ID | Task | Status | Depends-On | Required Role(s) | Approver Role | Co-Signer Role | Break-Glass? |
|---|---|---|---|---|---|---|---|
| T-RES-P76-01 | Register Conductor worker for `wf.refund.food_reject.v1` — Read-only consumer | pending | — | restaurant.admin | restaurant.admin | — | — |


---



## Hard service-to-service dependencies

This service's position in the canonical per-service deployment
order is **Tier 1, Position 12** per
[`../../DEPLOYMENT_ORDER.md`](../../DEPLOYMENT_ORDER.md).

| Class | Services |
|---|---|
| **Hard deps** (must be live and reachable before this service can complete its `/ready` health check) | [`customer-service`](../customer-service/README.md) (merchant KYC contract), [`identity-service`](../identity-service/README.md) (Keycloak merchant user) |
| **Soft deps** (this service can start without them; runtime calls fail gracefully with circuit-breaker fallback until the dep is up) | [`notification-service`](../notification-service/README.md) (merchant onboarding email) |

**Deployment scenarios** (per [`../../DEPLOYMENT_ORDER.md` §4](../../DEPLOYMENT_ORDER.md)):

- **Greenfield** — tiers are deployed in order; intra-tier parallelism is allowed.
- **Single-service rollout** — rolling deploy with canary required for Tier 0 (`configuration-service`, `identity-service`, `api-gateway`); optional for Tier 1+; canary required for `chat-service` (Phase 7.7 cross-cutting).
- **Region failover / DR** — full Tier 0 → Tier 1 → Tier 2 → Tier 3 sequence is replayed.

For cross-cutting infra deps (PostgreSQL, Kafka, Redis, Keycloak, Vault, mTLS, OTel, S3) see [`../../DEPLOYMENT_ORDER.md` §3](../../DEPLOYMENT_ORDER.md).

## Role Mapping (back-reference)

This service's tasks map to platform roles per [`MASTER_TASK.md`](../../MASTER_TASK.md) 11 "Role Mapping (back-reference)". The columns `Required Role(s) | Approver Role | Co-Signer Role | Break-Glass?` added to every task table above come from that appendix.

| ID prefix | Required Role(s) | Approver Role | Co-Signer Role | Break-Glass? |
|---|---|---|---|---|
| T-RES-NN (Phase 1-10) | per task | per task | per task | per task |
| T-RES-P70-NN | platform.admin / pricing.admin / customer.admin | platform.admin | platform.super_admin | no (Phase 7.0 cross-cutting) |
| T-RES-P75-NN | platform.admin / pricing.admin | platform.admin | platform.super_admin | no (Phase 7.5 Make-a-Deal) |
| T-RES-P76-NN | platform.admin + Conductor UI role | platform.admin | platform.super_admin | yes (workflow worker registration) |

For the canonical SUPER_ADMIN preset (1 × `platform.super_admin` + 20 × `<service>.admin`), see [`shared/TIME_BOUNDED_ALIASES.md`](../../shared/TIME_BOUNDED_ALIASES.md) for time-bounded aliases and `admin-service/INTEGRATION.md` 1.13 for the canonical role list.

## Phase 9 — Platform DRY (Tier 1) — 2026-08-17

This service was adopted into the
[`platform-spring-boot-starter:4.1.1`](../../../packages/platform-spring-boot/spring-boot-starter/)
umbrella (Phase 0 conformed per ADR-0024/0025/0026/0030/0031; see
[`docs/plans/PLATFORM_DRY_AUDIT.md`](../../plans/PLATFORM_DRY_AUDIT.md)). Pure
deletions of the 5 local-shadow classes; no functional behaviour change.

| Status Tracking ID | Description | Roles | Status | Last Updated |
|---|---|---|---|---|
| T-RES-P90-01 | Delete `RequestCorrelationFilter.kt` — adopt platform UUIDv7 + MDC request_id (ADR-0030) | platform.admin | done | 2026-08-17 |
| T-RES-P90-02 | Delete `JacksonConfiguration.kt` — adopt platform Jackson + `@ConditionalOnMissingBean` | platform.admin | done | 2026-08-17 |
| T-RES-P90-03 | Delete `MetricsConfiguration.kt` — adopt `platformMetricsCustomizer` | platform.admin | done | 2026-08-17 |
| T-RES-P90-04 | Delete `OpenApiConfiguration.kt` — adopt `platformOpenApi` | platform.admin | done | 2026-08-17 |
| T-RES-P90-05 | Delete `TestcontainersConfiguration.kt` — extend `BaseIntegrationTest` from platform-spring-boot-test | platform.admin | done | 2026-08-17 |
| T-RES-P90-06 | `application.yml`: add `platform.{observability,api-docs,audit,security}` property blocks (replaces deleted `@Value` reads) | platform.admin | done | 2026-08-17 |
| T-RES-P90-07 | Bump `com.trips-enjoy.platform:spring-boot-starter` from `4.1.0` → `4.1.1` | platform.admin | done | 2026-08-17 |
| T-RES-P90-08 | Test wiring: `RestaurantServiceApplicationTests` extends `BaseIntegrationTest` from `com.trips_enjoy.platform.test` | platform.admin | done | 2026-08-17 |
| T-RES-P90-09 | Test wiring: `TestRestaurantServiceApplication` drops `with(TestcontainersConfiguration::class)` | platform.admin | done | 2026-08-17 |

**Verification:** `./gradlew test` → 43 tests run, 0 skipped, 0 failures, 0 errors. `RestaurantIdempotencyTest` 13/13 pass, `RestaurantStateMachineTest` 29/29 pass, `RestaurantServiceApplicationTests.contextLoads` 1/1 pass
(Testcontainers spun up Postgres + Kafka + Redis in ~48 s, validating the
`BaseIntegrationTest` integration end-to-end). 5 shadow classes deleted
(~159 LOC): `RequestCorrelationFilter.kt` (35), `JacksonConfiguration.kt`
(23), `MetricsConfiguration.kt` (22), `OpenApiConfiguration.kt` (47),
`TestcontainersConfiguration.kt` (32).

**Phase 9 prepares, but does NOT land:** Phase B (OutboxEvent /
InboxEvent / IdempotencyRecord canonicalisation), Phase C (ApiExceptionHandler
+ SecurityConfiguration + BaseEntity migration), or Phase D (partition cron
++ idempotency service + inbox listener). Those PRs follow in their own
session once Phase 0/A is fully merged across all 14 Kotlin services.
