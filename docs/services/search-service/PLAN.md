# search-service — Implementation Plan

**Domain:** Analytics
**Tier:** 2 (position 19 of 21; `DEPLOYMENT_ORDER.md` §2)
**Technology:** Kotlin/Spring
**Criticality:** T2 (99.9%)
**DB Schema:** `search`
**Cache:** Redis — query cache
**HPA:** CPU 60%, 2–6, p99 < 200ms

---

## Purpose

**Phase 6 — Analytics & Enhancements.** Begin once the upstream event streams are stable.

This PLAN.md is the source of truth for **how** `search-service` is built. The 10-phase
backbone below mirrors the locked Phase 1-7 layout in `IMPLEMENTATION_PHASES.md`
plus the Phase 7 cross-cutting and Phase 7.5 Make-a-Deal addenda where this
service participates.

---

## Tasks

### Phase 1 — Database & Domain Model

| ID | Task | Status | Depends-On | Required Role(s) | Approver Role | Co-Signer Role | Break-Glass? |
|---|---|---|---|---|---|---|---|
| T-SRH-01 | Create schema `search`: tables per `ERD.md` (partitioned by time/zone/hash per data shape) | pending | — | search.admin | search.admin | — | — |
| T-SRH-02 | Write Flyway/migrations (forward-only); install `pg_partman` if the parent table is time-partitioned | pending | T-SRH-01 | search.admin | search.admin | — | — |
| T-SRH-03 | Implement the aggregate root, immutability invariants, and append-only audit constraints | pending | T-SRH-02 | search.admin | search.admin | — | — |
| T-SRH-04 | Add `search.outbox` and `search.inbox` for reliable eventing | pending | T-SRH-03 | search.outbox, search.inbox | search.outbox | — | — |
### Phase 2 — REST API

| ID | Task | Status | Depends-On | Required Role(s) | Approver Role | Co-Signer Role | Break-Glass? |
|---|---|---|---|---|---|---|---|
| T-SRH-01 | CRUD endpoints per `INTEGRATION.md` (versioned `/v1/...`) | pending | — | search.admin | search.admin | — | — |
| T-SRH-02 | Idempotency-Key middleware on every mutating route | pending | T-SRH-01 | search.admin | search.admin | — | — |
| T-SRH-03 | Pagination + filtering on every list endpoint | pending | T-SRH-02 | search.admin | search.admin | — | — |
| T-SRH-04 | Health endpoints: `/actuator/health`, `/ready`, `/started` | pending | T-SRH-03 | search.admin | search.admin | — | — |
### Phase 3 — Event Publishing

| ID | Task | Status | Depends-On | Required Role(s) | Approver Role | Co-Signer Role | Break-Glass? |
|---|---|---|---|---|---|---|---|
| T-SRH-01 | Transactional outbox + poller (200 ms interval, DLQ) | pending | — | search.admin | search.admin | — | — |
| T-SRH-02 | Publish events per the integration map below | pending | T-SRH-01 | search.admin | search.admin | — | — |
| T-SRH-03 | Avro schema registered in Schema Registry on first publish | pending | T-SRH-02 | search.admin | search.admin | — | — |
### Phase 4 — Event Consumption

| ID | Task | Status | Depends-On | Required Role(s) | Approver Role | Co-Signer Role | Break-Glass? |
|---|---|---|---|---|---|---|---|
| T-SRH-01 | Idempotent inbox; LSN/offset dedup window 7 days | pending | — | search.admin | search.admin | — | — |
| T-SRH-02 | Single consumer per partition; pause-on-error with backoff | pending | T-SRH-01 | search.admin | search.admin | — | — |
| T-SRH-03 | Dead-letter topic after N retries | pending | T-SRH-02 | search.admin | search.admin | — | — |
### Phase 5 — Caching

| ID | Task | Status | Depends-On | Required Role(s) | Approver Role | Co-Signer Role | Break-Glass? |
|---|---|---|---|---|---|---|---|
| T-SRH-01 | Redis — query cache | pending | — | search.admin | search.admin | — | — |
| T-SRH-02 | Push-invalidate on every write that affects the cache key | pending | T-SRH-01 | search.admin | search.admin | — | — |
| T-SRH-03 | Stampede protection on hot keys (single-flight) | pending | T-SRH-02 | search.admin | search.admin | — | — |
### Phase 6 — External Integrations

| ID | Task | Status | Depends-On | Required Role(s) | Approver Role | Co-Signer Role | Break-Glass? |
|---|---|---|---|---|---|---|---|
| T-SRH-01 | Sync dependencies: restaurant-service, `restaurant-service` (menu), OpenSearch | pending | — | search.admin | search.admin | — | — |
| T-SRH-02 | Circuit breakers on every outbound call (Resilience4j / polly) | pending | T-SRH-01 | search.admin | search.admin | — | — |
| T-SRH-03 | OAuth2 client credentials + mTLS for service-to-service | pending | T-SRH-02 | search.admin | search.admin | — | — |
| T-SRH-04 | HashiCorp Vault for DB credentials and signing keys | pending | T-SRH-03 | search.admin | search.admin | — | — |
### Phase 7 — Security

| ID | Task | Status | Depends-On | Required Role(s) | Approver Role | Co-Signer Role | Break-Glass? |
|---|---|---|---|---|---|---|---|
| T-SRH-01 | JWT bearer auth via Keycloak, realm `platform-internal` | pending | — | search.admin | search.admin | — | — |
| T-SRH-02 | Required scopes/roles per `INTEGRATION.md` | pending | T-SRH-01 | search.admin | search.admin | — | — |
| T-SRH-03 | `X-Audit-Reason` header required on admin mutations | pending | T-SRH-02 | search.admin | search.admin | — | — |
| T-SRH-04 | Field-level encryption for PII (driver license, payment method) | pending | T-SRH-03 | search.admin | search.admin | — | — |
### Phase 8 — Observability

| ID | Task | Status | Depends-On | Required Role(s) | Approver Role | Co-Signer Role | Break-Glass? |
|---|---|---|---|---|---|---|---|
| T-SRH-01 | Structured JSON logs with `correlation_id`, `user_id`, `tenant_id` | pending | — | search.admin | search.admin | — | — |
| T-SRH-02 | Metrics: RED per route + business counters specific to this service | pending | T-SRH-01 | search.admin | search.admin | — | — |
| T-SRH-03 | OpenTelemetry traces with child spans; long-poll spans open until response | pending | T-SRH-02 | search.admin | search.admin | — | — |
| T-SRH-04 | Alerts in Grafana: p99 latency, error rate, consumer lag | pending | T-SRH-03 | search.admin | search.admin | — | — |
### Phase 9 — Testing

| ID | Task | Status | Depends-On | Required Role(s) | Approver Role | Co-Signer Role | Break-Glass? |
|---|---|---|---|---|---|---|---|
| T-SRH-01 | Unit tests: 80%+ branch coverage on the aggregate | pending | — | search.admin | search.admin | — | — |
| T-SRH-02 | Integration tests: Testcontainers (PostgreSQL, Kafka, Redis) | pending | T-SRH-01 | search.admin | search.admin | — | — |
| T-SRH-03 | Contract tests: Producer Avro schemas pinned in CI | pending | T-SRH-02 | search.admin | search.admin | — | — |
| T-SRH-04 | E2E test per major user journey in `WORKFLOWS.md` | pending | T-SRH-03 | search.admin | search.admin | — | — |
### Phase 10 — Deployment

| ID | Task | Status | Depends-On | Required Role(s) | Approver Role | Co-Signer Role | Break-Glass? |
|---|---|---|---|---|---|---|---|
| T-SRH-01 | Kubernetes manifests: Deployment, Service, HPA, PDB | pending | — | search.admin | search.admin | — | — |
| T-SRH-02 | Pre-upgrade Job for migrations | pending | T-SRH-01 | search.admin | search.admin | — | — |
| T-SRH-03 | Resource limits per `DEPLOYMENT_ARCHITECTURE.md` | pending | T-SRH-02 | search.admin | search.admin | — | — |
| T-SRH-04 | Smoke test in staging before production rollout | pending | T-SRH-03 | search.admin | search.admin | — | — |
---

## Integration Map

### Sync Dependencies (outbound calls)
| Target | Endpoint | Purpose | Circuit Breaker |
|--------|----------|---------|----------------|
| `restaurant-service` | per `INTEGRATION.md` | sync dependency | Yes |
| ``restaurant-service` (menu)` | per `INTEGRATION.md` | sync dependency | Yes |
| `OpenSearch` | per `INTEGRATION.md` | sync dependency | Yes |

### Events Published
| Event | Topic | Trigger | Key Consumers |
|-------|-------|---------|--------------|


### Events Consumed
| Event | Producer | Handler |
|-------|----------|---------|
| `restaurant.updated` | see INTEGRATION.md | see INTEGRATION.md |
| `menu.updated` | see INTEGRATION.md | see INTEGRATION.md |
| `merchant.updated` | see INTEGRATION.md | see INTEGRATION.md |

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

### Phase 7.0 — Cross-cutting: Guaranteed Rewards & Rating-Based Pricing

This service participates in Phase 7 (cross-cutting) per
[`MASTER_PLAN.md`](../../MASTER_PLAN.md) "Phase 7 — Cross-cutting".
See canonical scope there; this block lists only the cross-cutting
tasks this service owns. Full audit history lives in
[`MASTER_TASK.md`](../../MASTER_TASK.md).

| ID | Task | Status | Depends-On | Required Role(s) | Approver Role | Co-Signer Role | Break-Glass? |
|---|---|---|---|---|---|---|---|
| T-SRH-P70-01 | Implement review-projection hook that emits `review.zone_aggregated.v1` (debounced per zone) from indexed reviews — Producer per [`MASTER_PLAN.md`](../../MASTER_PLAN.md) Phase 7 table row 129 | pending | — | search.admin | search.admin | — | — |
| T-SRH-P70-02 | Wire rating-density aggregation trigger via Conductor signal per [`shared/CONDUCTOR_WORKFLOWS.md`](../../shared/CONDUCTOR_WORKFLOWS.md) 6 | pending | T-SRH-P70-01 | search.admin | search.admin | — | — |
| T-SRH-P70-03 | Verify idempotency-key namespace matches the per-flow convention in [`shared/CONDUCTOR_WORKFLOWS.md`](../../shared/CONDUCTOR_WORKFLOWS.md) 4 | pending | T-SRH-P70-02 | search.admin | search.admin | — | — |

---



## Hard service-to-service dependencies

This service's position in the canonical per-service deployment
order is **Tier 2, Position 19** per
[`../../DEPLOYMENT_ORDER.md`](../../DEPLOYMENT_ORDER.md).

| Class | Services |
|---|---|
| **Hard deps** (must be live and reachable before this service can complete its `/ready` health check) | [`configuration-service`](../configuration-service/README.md) (search index config, OpenSearch endpoint) |
| **Soft deps** (this service can start without them; runtime calls fail gracefully with circuit-breaker fallback until the dep is up) | [`restaurant-service`](../restaurant-service/README.md) (menu data via Kafka events), [`trip-service`](../trip-service/README.md) (history events for review projections) |

**Deployment scenarios** (per [`../../DEPLOYMENT_ORDER.md` §4](../../DEPLOYMENT_ORDER.md)):

- **Greenfield** — tiers are deployed in order; intra-tier parallelism is allowed.
- **Single-service rollout** — rolling deploy with canary required for Tier 0 (`configuration-service`, `identity-service`, `api-gateway`); optional for Tier 1+; canary required for `chat-service` (Phase 7.7 cross-cutting).
- **Region failover / DR** — full Tier 0 → Tier 1 → Tier 2 → Tier 3 sequence is replayed.

For cross-cutting infra deps (PostgreSQL, Kafka, Redis, Keycloak, Vault, mTLS, OTel, S3) see [`../../DEPLOYMENT_ORDER.md` §3](../../DEPLOYMENT_ORDER.md).

## Role Mapping (back-reference)

This service's tasks map to platform roles per [`MASTER_TASK.md`](../../MASTER_TASK.md) 11 "Role Mapping (back-reference)". The columns `Required Role(s) | Approver Role | Co-Signer Role | Break-Glass?` added to every task table above come from that appendix.

| ID prefix | Required Role(s) | Approver Role | Co-Signer Role | Break-Glass? |
|---|---|---|---|---|
| T-SRH-NN (Phase 1-10) | per task | per task | per task | per task |
| T-SRH-P70-NN | platform.admin / pricing.admin / customer.admin | platform.admin | platform.super_admin | no (Phase 7.0 cross-cutting) |
| T-SRH-P75-NN | platform.admin / pricing.admin | platform.admin | platform.super_admin | no (Phase 7.5 Make-a-Deal) |
| T-SRH-P76-NN | platform.admin + Conductor UI role | platform.admin | platform.super_admin | yes (workflow worker registration) |

For the canonical SUPER_ADMIN preset (1 × `platform.super_admin` + 20 × `<service>.admin`), see [`shared/TIME_BOUNDED_ALIASES.md`](../../shared/TIME_BOUNDED_ALIASES.md) for time-bounded aliases and `admin-service/INTEGRATION.md` 1.13 for the canonical role list.

## Phase 9 — Platform DRY (Tier 1) — 2026-08-17

This service was adopted into the
[`platform-spring-boot-starter:4.1.1`](../../../packages/platform-spring-boot/spring-boot-starter/)
umbrella (Phase 0 conformed per ADR-0024/0025/0026/0030/0031; see
[`docs/plans/PLATFORM_DRY_AUDIT.md`](../../plans/PLATFORM_DRY_AUDIT.md)). Pure
deletions of the 5 local-shadow classes; no functional behaviour change.

| Status Tracking ID | Description | Roles | Status | Last Updated |
|---|---|---|---|---|
| T-SRH-P90-01 | Delete `RequestCorrelationFilter.kt` — adopt platform UUIDv7 + MDC `request_id` (ADR-0030) | platform.admin | done | 2026-08-17 |
| T-SRH-P90-02 | Delete `JacksonConfiguration.kt` — adopt platform Jackson + `@ConditionalOnMissingBean` | platform.admin | done | 2026-08-17 |
| T-SRH-P90-03 | Delete `MetricsConfiguration.kt` — adopt platform `MeterRegistryCustomizer` driven by `platform.observability.{service,env,region}` | platform.admin | done | 2026-08-17 |
| T-SRH-P90-04 | Delete `OpenApiConfiguration.kt` — adopt `platformOpenApi` bean fed by `platform.api-docs.{title,version,description,contact-*}`, preserves 4 `Server` entries + `bearer-jwt` security scheme | platform.admin | done | 2026-08-17 |
| T-SRH-P90-05 | Delete `TestcontainersConfiguration.kt` (Kafka + Postgres + Redis `@ServiceConnection` beans) — extend `BaseIntegrationTest` from `platform-spring-boot-test` in `SearchServiceApplicationTests` | platform.admin | done | 2026-08-17 |
| T-SRH-P90-06 | `application.yml`: add `platform.{observability,api-docs,audit,security}` property blocks (replaces deleted `@Value` reads in `MetricsConfiguration`) | platform.admin | done | 2026-08-17 |
| T-SRH-P90-07 | Bump `com.trips-enjoy.platform:spring-boot-starter` from `4.1.0` → `4.1.1` | platform.admin | done | 2026-08-17 |
| T-SRH-P90-08 | `TestSearchServiceApplication` drops `with(TestcontainersConfiguration::class)` — platform auto-config wires Testcontainers via the starter umbrella | platform.admin | done | 2026-08-17 |

**Verification:** `./gradlew test` → **26 tests run, 0 skipped, 0 failures** across **2 test classes**:
`SearchDomainTest` (25 unit tests, all green) and `SearchServiceApplicationTests` (1 IT `contextLoads`, green).
Confirmed identical to the pre-Phase-A baseline (verified via `git stash` + baseline re-run → 1 + 25, 0 failures). No new IT additions; the only Spring context test continues to rely on the platform Testcontainers auto-configuration via `BaseIntegrationTest`.

**Phase 9 prepares, but does NOT land:** Phase B (OutboxEvent / InboxEvent / IdempotencyRecord canonicalisation), Phase C (ApiExceptionHandler + SecurityConfiguration + BaseEntity migration), or Phase D (partition cron + idempotency service + inbox listener). Those PRs follow in their own session once Phase 0/A is fully merged across all 14 Kotlin services.

## Phase 9 + 10 — Platform DRY (Tier 2: Phase B + C + D) — 2026-08-17

search-service is the next graduate (after customer-service) of the Tier 2
fan-out of the platform-DRY initiative onto the canonical outbox /
idempotency / BaseEntity / SecurityConfiguration / platform-spring-boot-
partition surfaces. All 14 platform cantons are now adopted.

### Phase B — canonical outbox / inbox / idempotency (already landed in `7bc9037`)

`V4__canonical_outbox_and_idempotency.sql` introduces the canonical
`event_id` + `partition_key` columns on top of `search.outbox` (ADR-0028)
and the canonical `search.idempotency` table (ADR-0027). The local
`OutboxEvent` was rewritten to map the canonical columns; `@PrePersist`
auto-populates `event_id`, `partition_key`, and the `headers` JSONB.
`application.yml` gains `platform.{outbox,inbox,idempotency}.enabled=false`
blocks ready for the platform auto-configuration to be flipped on.

### Phase C — BaseEntity migration + SecurityConfiguration refactor (this commit)

| Status Tracking ID | Description | Roles | Status | Last Updated |
|---|---|---|---|---|
| T-SRH-P91-01 | Migrate `RelevanceConfig` to `BaseEntity` — V6 column migration (`created_by`/`updated_by` UUID → VARCHAR(255), `row_version` → `version`, `deleted_at` added). The 5 insert-only entities (`query_log`, `index_health`, `outbox`, `inbox`, `idempotency_keys`) intentionally NOT migrated; they use `@Id UUID` and do not extend `BaseEntity`. | platform.admin | done | 2026-08-17 |
| T-SRH-P91-02 | Migrate `ReindexJob` to `BaseEntity` — same V6 column migration, drops the manual `rowVersion` counter in favour of `BaseEntity.version` | platform.admin | done | 2026-08-17 |
| T-SRH-P91-03 | Update `SearchDomainTest` to construct the migrated entities without `id` / `createdBy` / `rowVersion` constructor params (now inherited from `BaseEntity`) | platform.admin | done | 2026-08-17 |
| T-SRH-P91-04 | Refactor `SecurityConfiguration.kt` to the platform-subclass pattern: inject `SecurityProperties`, combine `platform.publicPaths` + 8 service-specific paths, keep the service-specific authority rules (`/v1/admin/**` → `search.admin`, `/v1/search/**` → `search.read`/`SCOPE_search.read`/`customer.write`/`driver.write`), preserve the `search.security.enabled` toggle, register `@Primary` so the platform's `defaultSecurityFilterChain` `@ConditionalOnMissingBean` picks up the subclass | platform.admin | done | 2026-08-17 |

### Phase D — platform-spring-boot-partition adoption (this commit)

| Status Tracking ID | Description | Roles | Status | Last Updated |
|---|---|---|---|---|
| T-SRH-P92-01 | Confirm no service-local `PartitionMaintenanceJob` exists (search-service has no partitioned tables — V3 declares the `search.*` family as not-partitioned by design; search-service is a thin OpenSearch wrapper) | platform.admin | done | 2026-08-17 |
| T-SRH-P92-02 | Add `V5__platform_spring_boot_partition_adoption.sql` marker migration advancing `flyway_schema_history` past the platform 4.1.4 bump | platform.admin | done | 2026-08-17 |
| T-SRH-P92-03 | Bump `com.trips-enjoy.platform:spring-boot-starter` from `4.1.2` → `4.1.4` (matches platform 4.1.4 published in `8928c30`) | platform.admin | done | 2026-08-17 |

### Phase 10 — Deployment status post-Tier-2

| ID | Task | Status | Depends-On | Required Role(s) | Approver Role | Co-Signer Role | Break-Glass? |
|---|---|---|---|---|---|---|---|
| T-SRH-10-01 | Re-run `./gradlew test` baseline after Phase B/C/D fan-out — confirm 26/26 green (no IT regressions introduced by the new `BaseEntity` column shape) | — | search.admin | platform.admin | search.admin | — | — |
| T-SRH-10-02 | Confirm `flyway_schema_history` advances to V6 in dev / stg / prod Postgres (`V5` marker, `V6` BaseEntity column shape) | T-SRH-10-01 | search.admin | platform.admin | — | — | — |
| T-SRH-10-03 | Confirm `SecurityConfiguration` `@Primary` filter chain is the only `defaultSecurityFilterChain` bean in the context (the platform's `@ConditionalOnMissingBean` should be silenced) | T-SRH-10-01 | search.admin | platform.admin | — | — | — |
| T-SRH-10-04 | Roll forward to production behind the `search.security.enabled=true` flag (the dev-only `false` escape hatch is preserved) | T-SRH-10-02 | search.admin | platform.admin | platform.super_admin | no | — |

