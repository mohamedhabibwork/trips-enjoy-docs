# driver-service — Implementation Plan

**Domain:** Identity & User
**Tier:** 1 (position 10 of 21; `DEPLOYMENT_ORDER.md` §2)
**Technology:** Kotlin/Spring
**Criticality:** T1 (99.95%)
**DB Schema:** `driver`
**Cache:** Redis — driver profile
**HPA:** CPU 60%, 2–5, p99 < 80ms

---

## Purpose

**Phase 2 — Core Business & Identity.** Start as soon as the Phase 1 services it depends on are ready.

This PLAN.md is the source of truth for **how** `driver-service` is built. The 10-phase
backbone below mirrors the locked Phase 1-7 layout in `IMPLEMENTATION_PHASES.md`
plus the Phase 7 cross-cutting and Phase 7.5 Make-a-Deal addenda where this
service participates.

---

## Tasks

### Phase 1 — Database & Domain Model

| ID | Task | Status | Depends-On | Required Role(s) | Approver Role | Co-Signer Role | Break-Glass? |
|---|---|---|---|---|---|---|---|
| T-DRV-01 | Create schema `driver`: tables per `ERD.md` (partitioned by time/zone/hash per data shape) | pending | — | driver.admin | driver.admin | — | — |
| T-DRV-02 | Write Flyway/migrations (forward-only); install `pg_partman` if the parent table is time-partitioned | pending | T-DRV-01 | driver.admin | driver.admin | — | — |
| T-DRV-03 | Implement the aggregate root, immutability invariants, and append-only audit constraints | pending | T-DRV-02 | driver.admin | driver.admin | — | — |
| T-DRV-04 | Add `driver.outbox` and `driver.inbox` for reliable eventing | pending | T-DRV-03 | driver.outbox, driver.inbox | driver.outbox | — | — |
### Phase 2 — REST API

| ID | Task | Status | Depends-On | Required Role(s) | Approver Role | Co-Signer Role | Break-Glass? |
|---|---|---|---|---|---|---|---|
| T-DRV-01 | CRUD endpoints per `INTEGRATION.md` (versioned `/v1/...`) | pending | — | driver.admin | driver.admin | — | — |
| T-DRV-02 | Idempotency-Key middleware on every mutating route | pending | T-DRV-01 | driver.admin | driver.admin | — | — |
| T-DRV-03 | Pagination + filtering on every list endpoint | pending | T-DRV-02 | driver.admin | driver.admin | — | — |
| T-DRV-04 | Health endpoints: `/actuator/health`, `/ready`, `/started` | pending | T-DRV-03 | driver.admin | driver.admin | — | — |
### Phase 3 — Event Publishing

| ID | Task | Status | Depends-On | Required Role(s) | Approver Role | Co-Signer Role | Break-Glass? |
|---|---|---|---|---|---|---|---|
| T-DRV-01 | Transactional outbox + poller (200 ms interval, DLQ) | pending | — | driver.admin | driver.admin | — | — |
| T-DRV-02 | Publish events per the integration map below | pending | T-DRV-01 | driver.admin | driver.admin | — | — |
| T-DRV-03 | Avro schema registered in Schema Registry on first publish | pending | T-DRV-02 | driver.admin | driver.admin | — | — |
### Phase 4 — Event Consumption

| ID | Task | Status | Depends-On | Required Role(s) | Approver Role | Co-Signer Role | Break-Glass? |
|---|---|---|---|---|---|---|---|
| T-DRV-01 | Idempotent inbox; LSN/offset dedup window 7 days | pending | — | driver.admin | driver.admin | — | — |
| T-DRV-02 | Single consumer per partition; pause-on-error with backoff | pending | T-DRV-01 | driver.admin | driver.admin | — | — |
| T-DRV-03 | Dead-letter topic after N retries | pending | T-DRV-02 | driver.admin | driver.admin | — | — |
### Phase 5 — Caching

| ID | Task | Status | Depends-On | Required Role(s) | Approver Role | Co-Signer Role | Break-Glass? |
|---|---|---|---|---|---|---|---|
| T-DRV-01 | Redis — driver profile | pending | — | driver.admin | driver.admin | — | — |
| T-DRV-02 | Push-invalidate on every write that affects the cache key | pending | T-DRV-01 | driver.admin | driver.admin | — | — |
| T-DRV-03 | Stampede protection on hot keys (single-flight) | pending | T-DRV-02 | driver.admin | driver.admin | — | — |
### Phase 6 — External Integrations

| ID | Task | Status | Depends-On | Required Role(s) | Approver Role | Co-Signer Role | Break-Glass? |
|---|---|---|---|---|---|---|---|
| T-DRV-01 | Sync dependencies: identity-service, `driver-service` (vehicles), geolocation-service | pending | — | driver.admin | driver.admin | — | — |
| T-DRV-02 | Circuit breakers on every outbound call (Resilience4j / polly) | pending | T-DRV-01 | driver.admin | driver.admin | — | — |
| T-DRV-03 | OAuth2 client credentials + mTLS for service-to-service | pending | T-DRV-02 | driver.admin | driver.admin | — | — |
| T-DRV-04 | HashiCorp Vault for DB credentials and signing keys | pending | T-DRV-03 | driver.admin | driver.admin | — | — |
### Phase 7 — Security

| ID | Task | Status | Depends-On | Required Role(s) | Approver Role | Co-Signer Role | Break-Glass? |
|---|---|---|---|---|---|---|---|
| T-DRV-01 | JWT bearer auth via Keycloak, realm `platform-internal` | pending | — | driver.admin | driver.admin | — | — |
| T-DRV-02 | Required scopes/roles per `INTEGRATION.md` | pending | T-DRV-01 | driver.admin | driver.admin | — | — |
| T-DRV-03 | `X-Audit-Reason` header required on admin mutations | pending | T-DRV-02 | driver.admin | driver.admin | — | — |
| T-DRV-04 | Field-level encryption for PII (driver license, payment method) | pending | T-DRV-03 | driver.admin | driver.admin | — | — |
### Phase 8 — Observability

| ID | Task | Status | Depends-On | Required Role(s) | Approver Role | Co-Signer Role | Break-Glass? |
|---|---|---|---|---|---|---|---|
| T-DRV-01 | Structured JSON logs with `correlation_id`, `user_id`, `tenant_id` | pending | — | driver.admin | driver.admin | — | — |
| T-DRV-02 | Metrics: RED per route + business counters specific to this service | pending | T-DRV-01 | driver.admin | driver.admin | — | — |
| T-DRV-03 | OpenTelemetry traces with child spans; long-poll spans open until response | pending | T-DRV-02 | driver.admin | driver.admin | — | — |
| T-DRV-04 | Alerts in Grafana: p99 latency, error rate, consumer lag | pending | T-DRV-03 | driver.admin | driver.admin | — | — |
### Phase 9 — Testing

| ID | Task | Status | Depends-On | Required Role(s) | Approver Role | Co-Signer Role | Break-Glass? |
|---|---|---|---|---|---|---|---|
| T-DRV-01 | Unit tests: 80%+ branch coverage on the aggregate | pending | — | driver.admin | driver.admin | — | — |
| T-DRV-02 | Integration tests: Testcontainers (PostgreSQL, Kafka, Redis) | pending | T-DRV-01 | driver.admin | driver.admin | — | — |
| T-DRV-03 | Contract tests: Producer Avro schemas pinned in CI | pending | T-DRV-02 | driver.admin | driver.admin | — | — |
| T-DRV-04 | E2E test per major user journey in `WORKFLOWS.md` | pending | T-DRV-03 | driver.admin | driver.admin | — | — |
### Phase 10 — Deployment

| ID | Task | Status | Depends-On | Required Role(s) | Approver Role | Co-Signer Role | Break-Glass? |
|---|---|---|---|---|---|---|---|
| T-DRV-01 | Kubernetes manifests: Deployment, Service, HPA, PDB | pending | — | driver.admin | driver.admin | — | — |
| T-DRV-02 | Pre-upgrade Job for migrations | pending | T-DRV-01 | driver.admin | driver.admin | — | — |
| T-DRV-03 | Resource limits per `DEPLOYMENT_ARCHITECTURE.md` | pending | T-DRV-02 | driver.admin | driver.admin | — | — |
| T-DRV-04 | Smoke test in staging before production rollout | pending | T-DRV-03 | driver.admin | driver.admin | — | — |
---

## Integration Map

### Sync Dependencies (outbound calls)
| Target | Endpoint | Purpose | Circuit Breaker |
|--------|----------|---------|----------------|
| `identity-service` | per `INTEGRATION.md` | sync dependency | Yes |
| ``driver-service` (vehicles)` | per `INTEGRATION.md` | sync dependency | Yes |
| `geolocation-service` | per `INTEGRATION.md` | sync dependency | Yes |

### Events Published
| Event | Topic | Trigger | Key Consumers |
|-------|-------|---------|--------------|
| `driver.created` | derived from name | see INTEGRATION.md | see INTEGRATION.md |
| `driver.approved` | derived from name | see INTEGRATION.md | see INTEGRATION.md |
| `driver.suspended` | derived from name | see INTEGRATION.md | see INTEGRATION.md |

### Events Consumed
| Event | Producer | Handler |
|-------|----------|---------|
| `vehicle.registered` | see INTEGRATION.md | see INTEGRATION.md |
| `document.expiring` | see INTEGRATION.md | see INTEGRATION.md |

---

## Acceptance Criteria
- [ ] All REST endpoints return correct status codes
- [ ] p99 latency within SLO target (T1 (99.95%))
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
| T-DRV-P76-01 | Register Conductor worker for `wf.phase75.deal_driver.v1` — Producer — driver-side endpoint + 4 dispatch events | pending | — | driver.admin | driver.admin | — | — |
| T-DRV-P76-02 | Register Conductor worker for `wf.onboarding.driver.v1` — Orchestrator + activation worker | pending | — | driver.admin | driver.admin | — | — |

### Phase 7.5 — Make-a-Deal Kernel

This service participates in Phase 7.5 (Make-a-Deal kernel) per
[`MASTER_PLAN.md`](../../MASTER_PLAN.md) "Phase 7.5" and the canonical
contract in [`shared/DEAL_FEATURE.md`](../../shared/DEAL_FEATURE.md).
See canonical scope there; this block lists only the deal-flow tasks
this service owns.

| ID | Task | Status | Depends-On | Required Role(s) | Approver Role | Co-Signer Role | Break-Glass? |
|---|---|---|---|---|---|---|---|
| T-DRV-P75-01 | Implement driver-side endpoint `POST /v1/dispatch/deals/{deal_id}/bids` + `POST /v1/dispatch/deals/{deal_id}/accept` + `GET /v1/dispatch/drivers/{id}/open-deals` — emits 4 `dispatch.deal.*.v1` events per [`shared/DEAL_FEATURE.md`](../../shared/DEAL_FEATURE.md) 5.1 | pending | — | driver.admin | driver.admin | — | — |
| T-DRV-P75-02 | Implement DealBid aggregate state machine (pending → accepted/rejected/countered/expired) per [`shared/DEAL_FEATURE.md`](../../shared/DEAL_FEATURE.md) 3.1 | pending | T-DRV-P75-01 | driver.admin | driver.admin | — | — |
| T-DRV-P75-03 | Consume `ride.deal.opened.v1` from [`trip-service`](../../services/trip-service/PLAN.md); enumerate drivers in `deal.broadcast.radius_m` and pick top `deal.broadcast.max_concurrent_drivers` per [`shared/DEAL_FEATURE.md`](../../shared/DEAL_FEATURE.md) 11.1 step 5 | pending | T-DRV-P75-01 | driver.admin | driver.admin | — | — |
| T-DRV-P75-04 | Verify idempotency-key namespace `deal:{deal_id}:bid:{bid_id}:*` per [`shared/DEAL_FEATURE.md`](../../shared/DEAL_FEATURE.md) 7.1 | pending | T-DRV-P75-01 | driver.admin | driver.admin | — | — |

---



## Hard service-to-service dependencies

This service's position in the canonical per-service deployment
order is **Tier 1, Position 10** per
[`../../DEPLOYMENT_ORDER.md`](../../DEPLOYMENT_ORDER.md).

| Class | Services |
|---|---|
| **Hard deps** (must be live and reachable before this service can complete its `/ready` health check) | [`customer-service`](../customer-service/README.md) (KYC contract), [`identity-service`](../identity-service/README.md) (Keycloak user + KYC verification) |
| **Soft deps** (this service can start without them; runtime calls fail gracefully with circuit-breaker fallback until the dep is up) | [`notification-service`](../notification-service/README.md) (driver assignment push, KYC status push) |

**Deployment scenarios** (per [`../../DEPLOYMENT_ORDER.md` §4](../../DEPLOYMENT_ORDER.md)):

- **Greenfield** — tiers are deployed in order; intra-tier parallelism is allowed.
- **Single-service rollout** — rolling deploy with canary required for Tier 0 (`configuration-service`, `identity-service`, `api-gateway`); optional for Tier 1+; canary required for `chat-service` (Phase 7.7 cross-cutting).
- **Region failover / DR** — full Tier 0 → Tier 1 → Tier 2 → Tier 3 sequence is replayed.

For cross-cutting infra deps (PostgreSQL, Kafka, Redis, Keycloak, Vault, mTLS, OTel, S3) see [`../../DEPLOYMENT_ORDER.md` §3](../../DEPLOYMENT_ORDER.md).

## Role Mapping (back-reference)

This service's tasks map to platform roles per [`MASTER_TASK.md`](../../MASTER_TASK.md) 11 "Role Mapping (back-reference)". The columns `Required Role(s) | Approver Role | Co-Signer Role | Break-Glass?` added to every task table above come from that appendix.

| ID prefix | Required Role(s) | Approver Role | Co-Signer Role | Break-Glass? |
|---|---|---|---|---|
| T-DRV-NN (Phase 1-10) | per task | per task | per task | per task |
| T-DRV-P70-NN | platform.admin / pricing.admin / customer.admin | platform.admin | platform.super_admin | no (Phase 7.0 cross-cutting) |
| T-DRV-P75-NN | platform.admin / pricing.admin | platform.admin | platform.super_admin | no (Phase 7.5 Make-a-Deal) |
| T-DRV-P76-NN | platform.admin + Conductor UI role | platform.admin | platform.super_admin | yes (workflow worker registration) |

For the canonical SUPER_ADMIN preset (1 × `platform.super_admin` + 20 × `<service>.admin`), see [`shared/TIME_BOUNDED_ALIASES.md`](../../shared/TIME_BOUNDED_ALIASES.md) for time-bounded aliases and `admin-service/INTEGRATION.md` 1.13 for the canonical role list.

## Phase 9 — Platform DRY (Tier 1) — 2026-08-17

This service was adopted into the
[`platform-spring-boot-starter:4.1.1`](../../../packages/platform-spring-boot/spring-boot-starter/)
umbrella (Phase 0 conformed per ADR-0024/0025/0026/0030/0031; see
[`docs/plans/PLATFORM_DRY_AUDIT.md`](../../plans/PLATFORM_DRY_AUDIT.md)). Pure
deletions of the 5 local-shadow classes; no functional behaviour change.

| Status Tracking ID | Description | Roles | Status | Last Updated |
|---|---|---|---|---|
| T-DRV-P90-01 | Delete `RequestCorrelationFilter.kt` — adopt platform UUIDv7 + MDC request_id (ADR-0030) | platform.admin | done | 2026-08-17 |
| T-DRV-P90-02 | Delete `JacksonConfiguration.kt` — adopt platform Jackson + `@ConditionalOnMissingBean` | platform.admin | done | 2026-08-17 |
| T-DRV-P90-03 | Delete `MetricsConfiguration.kt` — adopt `platformMetricsCustomizer` | platform.admin | done | 2026-08-17 |
| T-DRV-P90-04 | Delete `OpenApiConfiguration.kt` — adopt `platformOpenApi` | platform.admin | done | 2026-08-17 |
| T-DRV-P90-05 | Delete `TestcontainersConfiguration.kt` — extend `BaseIntegrationTest` from platform-spring-boot-test | platform.admin | done | 2026-08-17 |
| T-DRV-P90-06 | `application.yml`: add `platform.{observability,api-docs,audit,security}` property blocks (replaces deleted `@Value` reads) | platform.admin | done | 2026-08-17 |
| T-DRV-P90-07 | Bump `com.trips-enjoy.platform:spring-boot-starter` from `4.1.0` → `4.1.1` | platform.admin | done | 2026-08-17 |
| T-DRV-P90-08 | Test wiring: `DriverServiceApplicationTests` extends `BaseIntegrationTest` from `com.trips_enjoy.platform.test` | platform.admin | done | 2026-08-17 |
| T-DRV-P90-09 | Test wiring: `TestDriverServiceApplication` drops `with(TestcontainersConfiguration::class)` | platform.admin | done | 2026-08-17 |

**Verification:** `./gradlew test` → **42 tests, 0 skipped, 42 passed** across
3 suites (`DriverStateMachineTest` 21/21, `DriverDocumentAndIdempotencyTest`
20/20, `DriverServiceApplicationTests` 1/1). The single
`contextLoads()` exercises the full Spring context with Testcontainers
(Postgres + Kafka + Redis) wired by the platform auto-configuration; all
three containers start and Flyway migrates `driver` schema v3 successfully.

**Phase 9 prepares, but does NOT land:** Phase B (OutboxEvent /
InboxEvent / IdempotencyRecord canonicalisation), Phase C (ApiExceptionHandler
+ SecurityConfiguration + BaseEntity migration), or Phase D (partition cron
+ idempotency service + inbox listener). Those PRs follow in their own
session once Phase 0/A is fully merged across all 14 Kotlin services.
