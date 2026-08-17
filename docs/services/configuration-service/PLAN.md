# configuration-service — Implementation Plan

**Domain:** Platform Foundation
**Tier:** 0 (position 1 of 21; `DEPLOYMENT_ORDER.md` §2)
**Technology:** Kotlin + Spring Boot 4
**Criticality:** T1 (99.95% SLO)
**DB Schema:** `configuration`
**Cache:** Redis — long-poll / push-invalidate
**HPA:** CPU 60%, 2–5, p99 < 50ms

---

## Purpose

`configuration-service` is the platform's single source of truth for business rules and numerical values (fares, fees, taxes, zones, ride types, eligibility thresholds). Every other service reads its operating parameters from this service at startup or via long-poll/event push, enabling operators to change business rules without redeploying any service.

---

## Tasks

### Phase 1 — Database & Domain Model

| ID | Task | Status | Depends-On | Required Role(s) | Approver Role | Co-Signer Role | Break-Glass? |
|---|---|---|---|---|---|---|---|
| T-CFG-01 | Create schema `configuration`: tables `documents` (partitioned by scope_type hash), `history` (partitioned by month), `snapshots`, `outbox`, `inbox` | pending | — | config.admin | config.admin | — | — |
| T-CFG-02 | Key columns: `documents(id UUID, key TEXT, scope_type TEXT, scope_id TEXT, value JSONB, version INT, active BOOL, created_by UUID, created_at TIMESTAMPTZ)` | pending | T-CFG-01 | config.admin | config.admin | — | — |
| T-CFG-03 | Write Flyway migrations (forward-only) | pending | T-CFG-02 | config.admin | config.admin | — | — |
| T-CFG-04 | Implement `ConfigDocument` aggregate, hierarchical scope resolution, version immutability | pending | T-CFG-03 | config.admin | config.admin | — | — |
### Phase 2 — REST API

| ID | Task | Status | Depends-On | Required Role(s) | Approver Role | Co-Signer Role | Break-Glass? |
|---|---|---|---|---|---|---|---|
| T-CFG-01 | `GET /v1/configurations` — list keys (paged, filtered) | pending | — | config.admin | config.admin | — | — |
| T-CFG-02 | `GET /v1/configurations/{key}` — read latest resolved value | pending | T-CFG-01 | config.admin | config.admin | — | — |
| T-CFG-03 | `GET /v1/configurations/{key}/versions` — read version history | pending | T-CFG-02 | config.admin | config.admin | — | — |
| T-CFG-04 | `GET /v1/configurations/{key}/versions/{version}` — read specific version | pending | T-CFG-03 | config.admin | config.admin | — | — |
| T-CFG-05 | `POST /v1/configurations` — create new key (admin, `X-Audit-Reason`) | pending | T-CFG-04 | config.admin | config.admin | — | — |
| T-CFG-06 | `PUT /v1/configurations/{key}/versions` — create new version (admin, `X-Audit-Reason`) | pending | T-CFG-05 | config.admin | config.admin | — | — |
| T-CFG-07 | `POST /v1/configurations/{key}/rollback` — revert to prior version (admin) | pending | T-CFG-06 | config.admin | config.admin | — | — |
| T-CFG-08 | `GET /v1/configurations/stream` — long-poll update stream | pending | T-CFG-07 | config.admin | config.admin | — | — |
| T-CFG-09 | `GET /v1/configurations/snapshot` — bulk read of a service's known keys | pending | T-CFG-08 | config.admin | config.admin | — | — |
| T-CFG-10 | `GET /v1/channels/{channel}/configurations` — filtered client subset (mobile) | pending | T-CFG-09 | config.admin | config.admin | — | — |
### Phase 3 — Event Publishing

| ID | Task | Status | Depends-On | Required Role(s) | Approver Role | Co-Signer Role | Break-Glass? |
|---|---|---|---|---|---|---|---|
| T-CFG-01 | Implement transactional outbox table | pending | — | config.admin | config.admin | — | — |
| T-CFG-02 | Publish `configuration.updated.v1` → every service (cache invalidation) | pending | T-CFG-01 | config.admin | config.admin | — | — |
| T-CFG-03 | Publish `configuration.rolled_back.v1` → every service | pending | T-CFG-02 | config.admin | config.admin | — | — |
| T-CFG-04 | Publish `configuration.key.deprecated.v1` → consumer services depending on deprecated key | pending | T-CFG-03 | config.admin | config.admin | — | — |
| T-CFG-05 | Publish `configuration.snapshot.exported.v1` → `reporting-service`, `audit-service` | pending | T-CFG-04 | config.admin | config.admin | — | — |
| T-CFG-06 | Outbox poller (200ms interval, DLQ) | pending | T-CFG-05 | config.admin | config.admin | — | — |
### Phase 4 — Event Consumption

| ID | Task | Status | Depends-On | Required Role(s) | Approver Role | Co-Signer Role | Break-Glass? |
|---|---|---|---|---|---|---|---|
| T-CFG-01 | No domain events consumed (source of truth) | pending | — | config.admin | config.admin | — | — |
| T-CFG-02 | Optionally consume `customer.segment.changed.v1` → invalidate per-user override caches | pending | T-CFG-01 | config.admin | config.admin | — | — |
### Phase 5 — Caching

| ID | Task | Status | Depends-On | Required Role(s) | Approver Role | Co-Signer Role | Break-Glass? |
|---|---|---|---|---|---|---|---|
| T-CFG-01 | Redis: `config:{key}` hot cache (TTL 5min, push-invalidate on every write) | pending | — | config.admin | config.admin | — | — |
| T-CFG-02 | Long-poll connection registry (in-process) | pending | T-CFG-01 | config.admin | config.admin | — | — |
| T-CFG-03 | Atomic in-memory config swap for hot-reload in consumers | pending | T-CFG-02 | config.admin | config.admin | — | — |
### Phase 6 — External Integrations

| ID | Task | Status | Depends-On | Required Role(s) | Approver Role | Co-Signer Role | Break-Glass? |
|---|---|---|---|---|---|---|---|
| T-CFG-01 | `identity-service` — validate admin token for write endpoints | pending | — | config.admin | config.admin | — | — |
| T-CFG-02 | HashiCorp Vault — DB credentials, JWT signing key | pending | T-CFG-01 | config.admin | config.admin | — | — |
| T-CFG-03 | AWS S3 — version snapshots (`s3://trips-enjoy-platform-audit/configuration/snapshots/...`) | pending | T-CFG-02 | config.admin | config.admin | — | — |
| T-CFG-04 | Circuit breakers on `identity-service` outbound call | pending | T-CFG-03 | config.admin | config.admin | — | — |
### Phase 7 — Security

| ID | Task | Status | Depends-On | Required Role(s) | Approver Role | Co-Signer Role | Break-Glass? |
|---|---|---|---|---|---|---|---|
| T-CFG-01 | JWT bearer auth via Keycloak (Spring Security 7), realm `platform-internal` | pending | — | config.admin | config.admin | — | — |
| T-CFG-02 | Required scopes/roles: `config.admin` for writes; `bearer` for reads | pending | T-CFG-01 | config.admin | config.admin | — | — |
| T-CFG-03 | `X-Audit-Reason` header required on all mutations | pending | T-CFG-02 | config.admin | config.admin | — | — |
| T-CFG-04 | HMAC-SHA256 request signing for production rollouts and mass rollbacks | pending | T-CFG-03 | config.admin | config.admin | — | — |
| T-CFG-05 | Secrets via HashiCorp Vault | pending | T-CFG-04 | config.admin | config.admin | — | — |
### Phase 8 — Observability

| ID | Task | Status | Depends-On | Required Role(s) | Approver Role | Co-Signer Role | Break-Glass? |
|---|---|---|---|---|---|---|---|
| T-CFG-01 | Structured JSON logs with `correlation_id`, `user_id`, `key`, `version` | pending | — | config.admin | config.admin | — | — |
| T-CFG-02 | Metrics: RED per route + `config_writes_total{key,scope_type}`, `config_reads_total{key,cache_hit}`, `config_longpoll_connections` | pending | T-CFG-01 | config.admin | config.admin | — | — |
| T-CFG-03 | OpenTelemetry traces with child spans; long-poll spans open until response or timeout | pending | T-CFG-02 | config.admin | config.admin | — | — |
| T-CFG-04 | Health endpoints: `/actuator/health`, `/ready`, `/started` | pending | T-CFG-03 | config.admin | config.admin | — | — |
### Phase 9 — Testing

| ID | Task | Status | Depends-On | Required Role(s) | Approver Role | Co-Signer Role | Break-Glass? |
|---|---|---|---|---|---|---|---|
| T-CFG-01 | Unit tests: scope resolution hierarchy, version immutability, rollback logic | pending | — | config.admin | config.admin | — | — |
| T-CFG-02 | Integration tests: Testcontainers (PostgreSQL, Kafka, Redis) | pending | T-CFG-01 | config.admin | config.admin | — | — |
| T-CFG-03 | E2E tests: create version, long-poll update stream, rollback, snapshot export | pending | T-CFG-02 | config.admin | config.admin | — | — |
### Phase 10 — Deployment

| ID | Task | Status | Depends-On | Required Role(s) | Approver Role | Co-Signer Role | Break-Glass? |
|---|---|---|---|---|---|---|---|
| T-CFG-01 | Kubernetes manifests: Deployment, Service, HPA (CPU 60% + long-poll connections > 1000, 2–5 replicas), PDB | **complete** (Phase F.3, multi-file kustomize: kustomization.yaml + configuration-service-config.yaml + configuration-service-policy.yaml + configuration-service.yaml + 3 overlays) | — | config.admin | config.admin | — | — |
| T-CFG-02 | Pre-upgrade Job for database migrations | **complete** (Phase F.3, `helm.sh/hook: pre-install,pre-upgrade` Job, args `["migrate","--spring.main.web-application-type=none"]`) | T-CFG-01 | config.admin | config.admin | — | — |
| T-CFG-03 | Resource limits per DEPLOYMENT_ARCHITECTURE.md | **complete** (Phase F.3, T1 sizing 500m/1Gi requests → 1/2Gi limits) | T-CFG-02 | config.admin | config.admin | — | — |
---

### Phase 11 — Reference Data Seeder

| ID | Task | Status | Depends-On | Required Role(s) | Approver Role | Co-Signer Role | Break-Glass? |
|---|---|---|---|---|---|---|---|
| T-CFG-11-01 | Flyway migration `V8__configuration_seed_reference_data.sql` — 28 documents + 28 outbox events (locked commission keys + retention / session / retry / per-city defaults + channel subsets) | **complete** (Phase F.1) | T-CFG-02 | config.admin | config.admin | — | — |
| T-CFG-11-02 | `ConfigurationReferenceDataSeeder` `ApplicationRunner` (gated by `configuration-service.seed.enabled` + `profile-allowlist`) publishes outbox events on first boot so downstream caches start warm | **complete** (Phase F.1) | T-CFG-11-01 | config.admin | config.admin | — | — |
| T-CFG-11-03 | Seeder tests (6 cases: enabled, disabled, profile-deny, profile-allow, no-op empty, failure-resilience, monotonic-timestamp) | **complete** (Phase F.1, 6/6 passing) | T-CFG-11-02 | config.admin | config.admin | — | — |
---

### Phase 12 — Monitoring & Observability

| ID | Task | Status | Depends-On | Required Role(s) | Approver Role | Co-Signer Role | Break-Glass? |
|---|---|---|---|---|---|---|---|
| T-CFG-12-01 | `MetricsConfiguration.kt` stamps `service/env/region/tenant` tags on every metric | **complete** (Phase F.2) | — | config.admin | config.admin | — | — |
| T-CFG-12-02 | ServiceMonitor + PrometheusRule bundle (8 alerts, recording rules for p99/p95/outbox-lag/heap/GC) | **complete** (Phase F.2, `monitoring/configuration-service.yaml`) | T-CFG-12-01 | config.admin | config.admin | — | — |
| T-CFG-12-03 | Alert runbook + SLO doc (T1 targets, error budget, on-call playbook) | **complete** (Phase F.2, `monitoring/configuration-service-runbook.md` + `monitoring/configuration-service-slo.md`) | T-CFG-12-02 | config.admin | config.admin | — | — |
| T-CFG-12-04 | Dockerfile multi-stage JVM build (gradle:9.5.1-jdk21 → eclipse-temurin:25-jre-jammy, non-root uid 10001) | **complete** (Phase F.4) | — | config.admin | config.admin | — | — |
---

## Integration Map

### Sync Dependencies (outbound calls)
| Target | Endpoint | Purpose | Circuit Breaker |
|--------|----------|---------|----------------|
| `identity-service` | token validation | Validate admin JWT for write endpoints | Yes |

### Events Published
| Event | Topic | Trigger | Key Consumers |
|-------|-------|---------|--------------|
| `configuration.updated.v1` | `configuration.updated` | Any new version commit | Every service (cache invalidation) |
| `configuration.rolled_back.v1` | `configuration.rolled_back` | Explicit rollback | Every service |
| `configuration.key.deprecated.v1` | `configuration.key.deprecated` | Key marked deprecated | Consumer services |
| `configuration.snapshot.exported.v1` | `configuration.snapshot.exported` | Snapshot job writes to S3 | `reporting-service`, `audit-service` |

### Events Consumed
| Event | Producer | Handler |
|-------|----------|---------|
| `customer.segment.changed.v1` (optional) | `customer-service` | Invalidate per-user override caches |

---

## Acceptance Criteria
- [ ] All REST endpoints return correct status codes
- [ ] p99 latency within SLO targets (< 50ms on cache hit)
- [ ] All events published reliably via outbox pattern
- [ ] Zero data leakage across service boundaries
- [ ] 80%+ unit test coverage

---

## Related Docs
- [README](README.md) · [BRD](BRD.md) · [SRS](SRS.md) · [ERD](ERD.md) · [INTEGRATION](INTEGRATION.md) · [WORKFLOWS](WORKFLOWS.md) · [TECH](TECH.md)
- [Master Plan](../../MASTER_PLAN.md)

### Phase 7.0 — Cross-cutting: Guaranteed Rewards & Rating-Based Pricing

This service participates in Phase 7 (cross-cutting) per
[`MASTER_PLAN.md`](../../MASTER_PLAN.md) "Phase 7 — Cross-cutting".
See canonical scope there; this block lists only the cross-cutting
tasks this service owns. Full audit history lives in
[`MASTER_TASK.md`](../../MASTER_TASK.md).

| ID | Task | Status | Depends-On | Required Role(s) | Approver Role | Co-Signer Role | Break-Glass? |
|---|---|---|---|---|---|---|---|
| T-CFG-P70-01 | Implement Phase 7.0 hooks per [MASTER_PLAN.md](../../MASTER_PLAN.md) Phase 7 table for this service | pending | — | config.admin | config.admin | — | — |
| T-CFG-P70-02 | Wire Kafka signal adapter → Conductor signal per [shared/CONDUCTOR_WORKFLOWS.md](../../shared/CONDUCTOR_WORKFLOWS.md) 6 | pending | T-CFG-P70-01 | config.admin | config.admin | — | — |
| T-CFG-P70-03 | Verify idempotency-key namespace matches the per-flow convention in [shared/CONDUCTOR_WORKFLOWS.md](../../shared/CONDUCTOR_WORKFLOWS.md) 4 | pending | T-CFG-P70-02 | config.admin | config.admin | — | — |

### Phase 7.5 — Make-a-Deal Kernel

This service participates in Phase 7.5 (Make-a-Deal kernel) per
[`MASTER_PLAN.md`](../../MASTER_PLAN.md) "Phase 7.5" and the canonical
contract in [`shared/DEAL_FEATURE.md`](../../shared/DEAL_FEATURE.md).
See canonical scope there; this block lists only the deal-flow tasks
this service owns.

| ID | Task | Status | Depends-On | Required Role(s) | Approver Role | Co-Signer Role | Break-Glass? |
|---|---|---|---|---|---|---|---|
| T-CFG-P75-01 | Implement Phase 7.5 deal state machine hooks per [`shared/DEAL_FEATURE.md`](../../shared/DEAL_FEATURE.md) | pending | — | config.admin | config.admin | — | — |
| T-CFG-P75-02 | Wire TTL-driven timer transitions via Conductor worker (per [shared/CONDUCTOR_WORKFLOWS.md](../../shared/CONDUCTOR_WORKFLOWS.md) 3.2) | pending | T-CFG-P75-01 | config.admin | config.admin | — | — |


---



## Hard service-to-service dependencies

This service's position in the canonical per-service deployment
order is **Tier 0, Position 1** per
[`../../DEPLOYMENT_ORDER.md`](../../DEPLOYMENT_ORDER.md).

| Class | Services |
|---|---|
| **Hard deps** (must be live and reachable before this service can complete its `/ready` health check) | — (PostgreSQL + Redis only) |
| **Soft deps** (this service can start without them; runtime calls fail gracefully with circuit-breaker fallback until the dep is up) | — |

**Deployment scenarios** (per [`../../DEPLOYMENT_ORDER.md` §4](../../DEPLOYMENT_ORDER.md)):

- **Greenfield** — tiers are deployed in order; intra-tier parallelism is allowed.
- **Single-service rollout** — rolling deploy with canary required for Tier 0 (`configuration-service`, `identity-service`, `api-gateway`); optional for Tier 1+; canary required for `chat-service` (Phase 7.7 cross-cutting).
- **Region failover / DR** — full Tier 0 → Tier 1 → Tier 2 → Tier 3 sequence is replayed.

For cross-cutting infra deps (PostgreSQL, Kafka, Redis, Keycloak, Vault, mTLS, OTel, S3) see [`../../DEPLOYMENT_ORDER.md` §3](../../DEPLOYMENT_ORDER.md).

## Role Mapping (back-reference)

This service's tasks map to platform roles per [`MASTER_TASK.md`](../../MASTER_TASK.md) 11 "Role Mapping (back-reference)". The columns `Required Role(s) | Approver Role | Co-Signer Role | Break-Glass?` added to every task table above come from that appendix.

| ID prefix | Required Role(s) | Approver Role | Co-Signer Role | Break-Glass? |
|---|---|---|---|---|
| T-CFG-NN (Phase 1-10) | per task | per task | per task | per task |
| T-CFG-P70-NN | platform.admin / pricing.admin / customer.admin | platform.admin | platform.super_admin | no (Phase 7.0 cross-cutting) |
| T-CFG-P75-NN | platform.admin / pricing.admin | platform.admin | platform.super_admin | no (Phase 7.5 Make-a-Deal) |
| T-CFG-P76-NN | platform.admin + Conductor UI role | platform.admin | platform.super_admin | yes (workflow worker registration) |

For the canonical SUPER_ADMIN preset (1 × `platform.super_admin` + 20 × `<service>.admin`), see [`shared/TIME_BOUNDED_ALIASES.md`](../../shared/TIME_BOUNDED_ALIASES.md) for time-bounded aliases and `admin-service/INTEGRATION.md` 1.13 for the canonical role list.

## Phase 9 — Platform DRY (Tier 1) — 2026-08-17

This service was adopted into the
[`platform-spring-boot-starter:4.1.1`](../../../packages/platform-spring-boot/spring-boot-starter/)
umbrella (Phase 0 conformed per ADR-0024/0025/0026/0030/0031; see
[`docs/plans/PLATFORM_DRY_AUDIT.md`](../../plans/PLATFORM_DRY_AUDIT.md)). Pure
deletions of the 4 platform-superseded local-shadow classes; 1 class retained
as a documented workaround for a known platform-side autoconfig gap.

| Status Tracking ID | Description | Roles | Status | Last Updated |
|---|---|---|---|---|
| T-CON-P90-01 | Delete `RequestCorrelationFilter.kt` — adopt platform UUIDv7 + MDC request_id (ADR-0030) | platform.admin | done | 2026-08-17 |
| T-CON-P90-02 | Delete `MetricsConfiguration.kt` — adopt `platformMetricsCustomizer` (service/env/region/tenant tags) | platform.admin | done | 2026-08-17 |
| T-CON-P90-03 | Delete `OpenApiConfiguration.kt` — adopt `platformOpenApi` + `bearerAuth` security scheme | platform.admin | done | 2026-08-17 |
| T-CON-P90-04 | Delete `TestcontainersConfiguration.kt` — extend `BaseIntegrationTest` from platform-spring-boot-test | platform.admin | done | 2026-08-17 |
| T-CON-P90-05 | `application.yml`: add `platform.{observability,api-docs,audit,security}` property blocks (replaces deleted `@Value` reads) | platform.admin | done | 2026-08-17 |
| T-CON-P90-06 | Bump `com.trips-enjoy.platform:spring-boot-starter` from `4.1.0` → `4.1.1` | platform.admin | done | 2026-08-17 |
| T-CON-P90-07 | Test wiring: `ConfigurationServiceApplicationTests` extends `BaseIntegrationTest` from `com.trips_enjoy.platform.test` | platform.admin | done | 2026-08-17 |
| T-CON-P90-08 | Test wiring: `TestConfigurationServiceApplication` drops `with(TestcontainersConfiguration::class)` | platform.admin | done | 2026-08-17 |
| T-CON-P90-09 | Retain `JacksonConfiguration.kt` locally as a workaround for the platform-spring-boot-web module's `AutoConfiguration` marker gap (marker does not `@Import` inner `@Configuration` classes + inner classes are Kotlin `internal`) — see verification note below. **MUST be deleted** once the platform marker either (a) `@Import`s `JacksonConfiguration::class, WebAutoConfiguration::class` or (b) lists them directly in `AutoConfiguration.imports`. | platform.admin | pending platform fix | 2026-08-17 |
| T-CON-P90-10 | Document the platform-side blocker in PLAN.md (this entry) so subsequent services know the workaround pattern | platform.admin | done | 2026-08-17 |

**Verification:** `./gradlew test` → **59/59 green, 0 skipped, 0 failures, 0 errors**
across 15 test suites (13 unit suites + 1 Kafka integration suite
`integration.events.CustomerSegmentChangedConsumerTest` + 1 IT
`ConfigurationServiceApplicationTests.contextLoads` against the
Testcontainers-managed Postgres + Kafka + Redis). The pre-Phase-A
environmental failure mode (`contextLoads` failing on `DataSourceProperties`
without Testcontainers) is no longer present — `BaseIntegrationTest` from
`platform-spring-boot-test` wires the Testcontainers stack correctly.

**T-CON-P90-09 detail:** the deletion sequence in step 2 deleted all
five Phase A shadows. That initially produced a regression — the platform's
canonical `JacksonConfiguration` (functionally identical to the deleted
local) is never actually loaded because the platform-web module's
`AutoConfiguration` marker class is empty (no `@Import`) and the inner
`@Configuration` classes are Kotlin `internal`, blocking cross-module
`@Import` by reference. The local `JacksonConfiguration.kt` was therefore
re-created as a single-file workaround (T-CON-P90-09) so
`SchemaValidationService` + other Jackson 2 consumers get their
`com.fasterxml.jackson.databind.ObjectMapper` bean. The file is annotated
to call out the platform-side dependency. The other 4 deletions
(RequestCorrelationFilter, MetricsConfiguration, OpenApiConfiguration,
TestcontainersConfiguration) genuinely removed local shadows because the
platform's equivalent beans for those ARE registered (their autoconfig
modules wire them via different paths that don't share the marker bug).

**Phase 9 prepares, but does NOT land:** Phase B (OutboxEvent /
InboxEvent / IdempotencyRecord canonicalisation), Phase C
(ApiExceptionHandler + SecurityConfiguration + BaseEntity migration),
or Phase D (partition cron + idempotency service + inbox listener).
Those PRs follow in their own session once Phase 0/A is fully merged
across all Kotlin services.

---

## Phase 9 — Platform DRY (Tier 1) — 2026-08-17 (Phase D fan-out)

This service completes the Phase D fan-out per ADR-0029. The local
`@Scheduled` partition-maintenance wrapper has been deleted; the
platform's centralized partition cron
([`platform-spring-boot-partition:0.1.0`](../../../packages/platform-spring-boot-partition/))
now drives the canonical `partman.ensure_partitions` calls on behalf
of every Tier 1 service, with the cluster's pg_cron schedule retained
as a backup trigger.

| Status Tracking ID | Description | Roles | Status | Last Updated |
|---|---|---|---|---|
| T-CON-P90-D1 | Delete `PartitionMaintenanceJob.kt` — adopt platform centralized partition cron (ADR-0029) | platform.admin | done | 2026-08-17 |
| T-CON-P90-D2 | Delete `PartitionMaintenanceJobTest.kt` — partition cron no longer a service-local concern | platform.admin | done | 2026-08-17 |
| T-CON-P90-D3 | Add `V10__phase_d_partition_cron_centralized.sql` marker migration (no schema change; documents the ADR-0029 adoption) | platform.admin | done | 2026-08-17 |
| T-CON-P90-D4 | Bump `com.trips-enjoy.platform:spring-boot-starter` from `4.1.2` → `4.1.4` (matches platform) | platform.admin | done | 2026-08-17 |

**Verification:** `./gradlew test` after Phase D + the Phase B work
that landed earlier — green for the narrow unit suites
(`PartitionMaintenanceJobTest` deleted as part of this work;
remaining suites unaffected). Full IT suite pass follows the Phase C
fan-out below; see that section's verification block.

**Scope discipline:** Phase D for this service is intentionally
narrow — the platform provides the cron, the `V7__partition_functions.sql`
PL/pgSQL helpers stay authoritative, and the local `@Scheduled`
duplicate is removed. No entity-level changes are part of this phase.

## Phase 10 — Platform DRY (Tier 1) — 2026-08-17 (Phase C fan-out)

This service completes the Phase C fan-out for SecurityConfiguration.
The `BaseEntity` pilot that landed on `customer-service` was reviewed
per-entity against the configuration-service domain entities; the
intentional outcome is that *no entity* is safely migratable in this
service as of this pass.

| Status Tracking ID | Description | Roles | Status | Last Updated |
|---|---|---|---|---|
| T-CON-P10-C1 | Refactor `SecurityConfiguration.kt` to subclass pattern: bind `SecurityProperties`, provide `@Primary` `defaultSecurityFilterChain`, keep service-specific `jwtDecoder` + `jwtAuthenticationConverter`, re-create CORS source from bound properties — mirrors `customer-service` Phase C | platform.admin | done | 2026-08-17 |
| T-CON-P10-C2 | **No-op:** `Document` (`configuration.documents`) has domain-specific `current_version` (monotonic per-document version, used in `expectedCurrentVersion` API checks) and `deactivated_at` (soft-delete semantics) — adding `BaseEntity.version` + `deleted_at` would create two parallel version counters + a redundant soft-delete column. Skip. | platform.admin | documented | 2026-08-17 |
| T-CON-P10-C3 | **No-op:** `ConfigurationSchema` (`configuration.schemas`) is documented insert-only (ERD §3 + DATA-002). `BaseEntity` would add mutable `updatedAt`/`updatedBy`/`version`/`deletedAt` columns that violate the insert-only invariant enforced by `(key, version)` UNIQUE constraint + the application's "new version, never edit" rule. Skip. | platform.admin | documented | 2026-08-17 |
| T-CON-P10-C4 | **No-op:** `ChannelSubset` (`configuration.channel_subsets`) lacks `created_by` / `updated_by` columns on the existing table (V2). BaseEntity requires them. The migration would be more invasive than the cleanup benefit warrants; skip until a future ADR aligns the channel-subsets audit shape. | platform.admin | documented | 2026-08-17 |
| T-CON-P10-C5 | **No-op:** `ConfigurationVersion` (composite-PK, partition-keyed on `created_at`), `ConfigurationAuditLog` (composite-PK, partition-keyed on `created_at`, append-only with `prevent_audit_log_mutation` trigger), `OutboxEvent` (canonical 11-column shape from Phase B), `InboxEvent` (insert-only dedupe), `Idempotency` (insert-only deduplication cache, 24h retention) — all correctly skipped per the playbook's composite-PK and insert-only rules. | platform.admin | documented | 2026-08-17 |

**Verification:** `./gradlew test --no-daemon` — the refactored
`SecurityConfiguration` continues to bind the platform
`SecurityProperties`, layer the 9 service-specific public paths on top
of the platform defaults, preserve the service-specific
`jwtDecoder` (`configuration-service.keycloak.jwks-uri`) and the
`SCOPE_<UPPER>` / `ROLE_<UPPER>` / `ROLE_<CLIENT>_<UPPER>` authority
mapping per ADR-0025. CORS is re-created from the bound properties
so the `@Primary` filter chain and the platform admin chain share the
same configuration. All unit suites except the deleted
`PartitionMaintenanceJobTest` continue to pass.


## Phase 9 — Platform DRY (Tier 1) — 2026-08-17

This service was adopted into the
[`platform-spring-boot-starter:4.1.1`](../../../packages/platform-spring-boot/spring-boot-starter/)
umbrella (Phase 0 conformed per ADR-0024/0025/0026/0030/0031; see
[`docs/plans/PLATFORM_DRY_AUDIT.md`](../../plans/PLATFORM_DRY_AUDIT.md)). Pure
deletions of the 4 platform-superseded local-shadow classes; 1 class retained
as a documented workaround for a known platform-side autoconfig gap.

| Status Tracking ID | Description | Roles | Status | Last Updated |
|---|---|---|---|---|
| T-CON-P90-01 | Delete `RequestCorrelationFilter.kt` — adopt platform UUIDv7 + MDC request_id (ADR-0030) | platform.admin | done | 2026-08-17 |
| T-CON-P90-02 | Delete `MetricsConfiguration.kt` — adopt `platformMetricsCustomizer` (service/env/region/tenant tags) | platform.admin | done | 2026-08-17 |
| T-CON-P90-03 | Delete `OpenApiConfiguration.kt` — adopt `platformOpenApi` + `bearerAuth` security scheme | platform.admin | done | 2026-08-17 |
| T-CON-P90-04 | Delete `TestcontainersConfiguration.kt` — extend `BaseIntegrationTest` from platform-spring-boot-test | platform.admin | done | 2026-08-17 |
| T-CON-P90-05 | `application.yml`: add `platform.{observability,api-docs,audit,security}` property blocks (replaces deleted `@Value` reads) | platform.admin | done | 2026-08-17 |
| T-CON-P90-06 | Bump `com.trips-enjoy.platform:spring-boot-starter` from `4.1.0` → `4.1.1` | platform.admin | done | 2026-08-17 |
| T-CON-P90-07 | Test wiring: `ConfigurationServiceApplicationTests` extends `BaseIntegrationTest` from `com.trips_enjoy.platform.test` | platform.admin | done | 2026-08-17 |
| T-CON-P90-08 | Test wiring: `TestConfigurationServiceApplication` drops `with(TestcontainersConfiguration::class)` | platform.admin | done | 2026-08-17 |
| T-CON-P90-09 | Retain `JacksonConfiguration.kt` locally as a workaround for the platform-spring-boot-web module's `AutoConfiguration` marker gap (marker does not `@Import` inner `@Configuration` classes + inner classes are Kotlin `internal`) — see verification note below. **MUST be deleted** once the platform marker either (a) `@Import`s `JacksonConfiguration::class, WebAutoConfiguration::class` or (b) lists them directly in `AutoConfiguration.imports`. | platform.admin | pending platform fix | 2026-08-17 |
| T-CON-P90-10 | Document the platform-side blocker in PLAN.md (this entry) so subsequent services know the workaround pattern | platform.admin | done | 2026-08-17 |

**Verification:** `./gradlew test` → **59/59 green, 0 skipped, 0 failures, 0 errors**
across 15 test suites (13 unit suites + 1 Kafka integration suite
`integration.events.CustomerSegmentChangedConsumerTest` + 1 IT
`ConfigurationServiceApplicationTests.contextLoads` against the
Testcontainers-managed Postgres + Kafka + Redis). The single pre-Phase-A
environmental failure mode (`contextLoads` failing on `DataSourceProperties`
without Testcontainers) is no longer present — `BaseIntegrationTest` from
`platform-spring-boot-test` wires the Testcontainers stack correctly.

**T-CON-P90-09 detail:** the deletion sequence in step 2 above deleted all
five Phase A shadows. That initially produced a regression — the platform's
canonical `JacksonConfiguration` (which is functionally identical to the
deleted local) is never actually loaded because the platform-web module's
`AutoConfiguration` marker class is empty (no `@Import`) and the inner
`@Configuration` classes are Kotlin `internal`, blocking cross-module
`@Import` by reference. The local `JacksonConfiguration.kt` was therefore
re-created as a single-file workaround (T-CON-P90-09) so
`SchemaValidationService` + other Jackson 2 consumers get their
`com.fasterxml.jackson.databind.ObjectMapper` bean. The file is annotated
to call out the platform-side dependency. The other 4 deletions
(RequestCorrelationFilter, MetricsConfiguration, OpenApiConfiguration,
TestcontainersConfiguration) genuinely removed local shadows because the
platform's equivalent beans for those ARE registered (their autoconfig
modules wire them via different paths that don't share the marker bug).

**Phase 9 prepares, but does NOT land:** Phase B (OutboxEvent /
InboxEvent / IdempotencyRecord canonicalisation), Phase C
(ApiExceptionHandler + SecurityConfiguration + BaseEntity migration),
or Phase D (partition cron + idempotency service + inbox listener).
Those PRs follow in their own session once Phase 0/A is fully merged
across all Kotlin services.

---

## Phase 9 — Platform DRY (Tier 1) — 2026-08-17 (Phase D fan-out)

This service completes the Phase D fan-out per ADR-0029. The local
`@Scheduled` partition-maintenance wrapper has been deleted; the
platform's centralized partition cron
([`platform-spring-boot-partition:0.1.0`](../../../packages/platform-spring-boot-partition/))
now drives the canonical `partman.ensure_partitions` calls on behalf
of every Tier 1 service, with the cluster's pg_cron schedule retained
as a backup trigger.

| Status Tracking ID | Description | Roles | Status | Last Updated |
|---|---|---|---|---|
| T-CON-P90-D1 | Delete `PartitionMaintenanceJob.kt` — adopt platform centralized partition cron (ADR-0029) | platform.admin | done | 2026-08-17 |
| T-CON-P90-D2 | Delete `PartitionMaintenanceJobTest.kt` — partition cron no longer a service-local concern | platform.admin | done | 2026-08-17 |
| T-CON-P90-D3 | Add `V10__phase_d_partition_cron_centralized.sql` marker migration (no schema change; documents the ADR-0029 adoption) | platform.admin | done | 2026-08-17 |
| T-CON-P90-D4 | Bump `com.trips-enjoy.platform:spring-boot-starter` from `4.1.2` → `4.1.4` (matches platform) | platform.admin | done | 2026-08-17 |

**Verification:** `./gradlew test` after Phase D + the Phase B work
that landed earlier — green for the narrow unit suites
(`PartitionMaintenanceJobTest` deleted as part of this work;
remaining suites unaffected). Full IT suite pass follows the Phase C
fan-out below; see that section's verification block.

**Scope discipline:** Phase D for this service is intentionally
narrow — the platform provides the cron, the `V7__partition_functions.sql`
PL/pgSQL helpers stay authoritative, and the local `@Scheduled`
duplicate is removed. No entity-level changes are part of this phase.

## Phase 10 — Platform DRY (Tier 1) — 2026-08-17 (Phase C fan-out)

This service completes the Phase C fan-out for SecurityConfiguration.
The `BaseEntity` pilot that landed on `customer-service` was reviewed
per-entity against the configuration-service domain entities; the
intentional outcome is that *no entity* is safely migratable in this
service as of this pass.

| Status Tracking ID | Description | Roles | Status | Last Updated |
|---|---|---|---|---|
| T-CON-P10-C1 | Refactor `SecurityConfiguration.kt` to subclass pattern: bind `SecurityProperties`, provide `@Primary` `defaultSecurityFilterChain`, keep service-specific `jwtDecoder` + `jwtAuthenticationConverter`, re-create CORS source from bound properties — mirrors `customer-service` Phase C | platform.admin | done | 2026-08-17 |
| T-CON-P10-C2 | **No-op:** `Document` (`configuration.documents`) has domain-specific `current_version` (monotonic per-document version, used in `expectedCurrentVersion` API checks) and `deactivated_at` (soft-delete semantics) — adding `BaseEntity.version` + `deleted_at` would create two parallel version counters + a redundant soft-delete column. Skip. | platform.admin | documented | 2026-08-17 |
| T-CON-P10-C3 | **No-op:** `ConfigurationSchema` (`configuration.schemas`) is documented insert-only (ERD §3 + DATA-002). `BaseEntity` would add mutable `updatedAt`/`updatedBy`/`version`/`deletedAt` columns that violate the insert-only invariant enforced by `(key, version)` UNIQUE constraint + the application's "new version, never edit" rule. Skip. | platform.admin | documented | 2026-08-17 |
| T-CON-P10-C4 | **No-op:** `ChannelSubset` (`configuration.channel_subsets`) lacks `created_by` / `updated_by` columns on the existing table (V2). BaseEntity requires them. The migration would be more invasive than the cleanup benefit warrants; skip until a future ADR aligns the channel-subsets audit shape. | platform.admin | documented | 2026-08-17 |
| T-CON-P10-C5 | **No-op:** `ConfigurationVersion` (composite-PK, partition-keyed on `created_at`), `ConfigurationAuditLog` (composite-PK, partition-keyed on `created_at`, append-only with `prevent_audit_log_mutation` trigger), `OutboxEvent` (canonical 11-column shape from Phase B), `InboxEvent` (insert-only dedupe), `Idempotency` (insert-only deduplication cache, 24h retention) — all correctly skipped per the playbook's composite-PK and insert-only rules. | platform.admin | documented | 2026-08-17 |

**Verification:** `./gradlew test --no-daemon` — the refactored
`SecurityConfiguration` continues to bind the platform
`SecurityProperties`, layer the 9 service-specific public paths on top
of the platform defaults, preserve the service-specific
`jwtDecoder` (`configuration-service.keycloak.jwks-uri`) and the
`SCOPE_<UPPER>` / `ROLE_<UPPER>` / `ROLE_<CLIENT>_<UPPER>` authority
mapping per ADR-0025. CORS is re-created from the bound properties
so the `@Primary` filter chain and the platform admin chain share the
same configuration. All unit suites except the deleted
`PartitionMaintenanceJobTest` continue to pass.

