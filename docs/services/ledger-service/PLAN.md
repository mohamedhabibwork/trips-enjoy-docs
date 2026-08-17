# ledger-service — Implementation Plan

**Domain:** Platform Foundation
**Tier:** 0 (position 8 of 21; `DEPLOYMENT_ORDER.md` §2)
**Technology:** Node/TS
**Criticality:** T0 (99.99%)
**DB Schema:** `ledger`
**Cache:** Redis — period balance cursor
**HPA:** CPU 60%, 2–5, p99 < 100ms

---

## Purpose

**Phase 1 — Platform Foundation.** This service is on the critical path; ship it before any consumer starts.

This PLAN.md is the source of truth for **how** `ledger-service` is built. The 10-phase
backbone below mirrors the locked Phase 1-7 layout in `IMPLEMENTATION_PHASES.md`
plus the Phase 7 cross-cutting and Phase 7.5 Make-a-Deal addenda where this
service participates.

---

## Tasks

### Phase 1 — Database & Domain Model

| ID | Task | Status | Depends-On | Required Role(s) | Approver Role | Co-Signer Role | Break-Glass? |
|---|---|---|---|---|---|---|---|
| T-LED-01 | Create schema `ledger`: tables per `ERD.md` (partitioned by time/zone/hash per data shape) | pending | — | ledger.admin | ledger.admin | — | — |
| T-LED-02 | Write Flyway/migrations (forward-only); install `pg_partman` if the parent table is time-partitioned | pending | T-LED-01 | ledger.admin | ledger.admin | — | — |
| T-LED-03 | Implement the aggregate root, immutability invariants, and append-only audit constraints | pending | T-LED-02 | ledger.admin | ledger.admin | — | — |
| T-LED-04 | Add `ledger.outbox` and `ledger.inbox` for reliable eventing | pending | T-LED-03 | ledger.outbox, ledger.inbox | ledger.outbox | — | — |
### Phase 2 — REST API

| ID | Task | Status | Depends-On | Required Role(s) | Approver Role | Co-Signer Role | Break-Glass? |
|---|---|---|---|---|---|---|---|
| T-LED-01 | CRUD endpoints per `INTEGRATION.md` (versioned `/v1/...`) | pending | — | ledger.admin | ledger.admin | — | — |
| T-LED-02 | Idempotency-Key middleware on every mutating route | pending | T-LED-01 | ledger.admin | ledger.admin | — | — |
| T-LED-03 | Pagination + filtering on every list endpoint | pending | T-LED-02 | ledger.admin | ledger.admin | — | — |
| T-LED-04 | Health endpoints: `/actuator/health`, `/ready`, `/started` | pending | T-LED-03 | ledger.admin | ledger.admin | — | — |
### Phase 3 — Event Publishing

| ID | Task | Status | Depends-On | Required Role(s) | Approver Role | Co-Signer Role | Break-Glass? |
|---|---|---|---|---|---|---|---|
| T-LED-01 | Transactional outbox + poller (200 ms interval, DLQ) | pending | — | ledger.admin | ledger.admin | — | — |
| T-LED-02 | Publish events per the integration map below | pending | T-LED-01 | ledger.admin | ledger.admin | — | — |
| T-LED-03 | Avro schema registered in Schema Registry on first publish | pending | T-LED-02 | ledger.admin | ledger.admin | — | — |
### Phase 4 — Event Consumption

| ID | Task | Status | Depends-On | Required Role(s) | Approver Role | Co-Signer Role | Break-Glass? |
|---|---|---|---|---|---|---|---|
| T-LED-01 | Idempotent inbox; LSN/offset dedup window 7 days | pending | — | ledger.admin | ledger.admin | — | — |
| T-LED-02 | Single consumer per partition; pause-on-error with backoff | pending | T-LED-01 | ledger.admin | ledger.admin | — | — |
| T-LED-03 | Dead-letter topic after N retries | pending | T-LED-02 | ledger.admin | ledger.admin | — | — |
### Phase 5 — Caching

| ID | Task | Status | Depends-On | Required Role(s) | Approver Role | Co-Signer Role | Break-Glass? |
|---|---|---|---|---|---|---|---|
| T-LED-01 | Redis — period balance cursor | pending | — | ledger.admin | ledger.admin | — | — |
| T-LED-02 | Push-invalidate on every write that affects the cache key | pending | T-LED-01 | ledger.admin | ledger.admin | — | — |
| T-LED-03 | Stampede protection on hot keys (single-flight) | pending | T-LED-02 | ledger.admin | ledger.admin | — | — |
### Phase 6 — External Integrations

| ID | Task | Status | Depends-On | Required Role(s) | Approver Role | Co-Signer Role | Break-Glass? |
|---|---|---|---|---|---|---|---|
| T-LED-01 | Sync dependencies: _(none — source-of-truth tier)_ | pending | — | ledger.admin | ledger.admin | — | — |
| T-LED-02 | Circuit breakers on every outbound call (Resilience4j / polly) | pending | T-LED-01 | ledger.admin | ledger.admin | — | — |
| T-LED-03 | OAuth2 client credentials + mTLS for service-to-service | pending | T-LED-02 | ledger.admin | ledger.admin | — | — |
| T-LED-04 | HashiCorp Vault for DB credentials and signing keys | pending | T-LED-03 | ledger.admin | ledger.admin | — | — |
### Phase 7 — Security

| ID | Task | Status | Depends-On | Required Role(s) | Approver Role | Co-Signer Role | Break-Glass? |
|---|---|---|---|---|---|---|---|
| T-LED-01 | JWT bearer auth via Keycloak, realm `platform-internal` | pending | — | ledger.admin | ledger.admin | — | — |
| T-LED-02 | Required scopes/roles per `INTEGRATION.md` | pending | T-LED-01 | ledger.admin | ledger.admin | — | — |
| T-LED-03 | `X-Audit-Reason` header required on admin mutations | pending | T-LED-02 | ledger.admin | ledger.admin | — | — |
| T-LED-04 | Field-level encryption for PII (driver license, payment method) | pending | T-LED-03 | ledger.admin | ledger.admin | — | — |
### Phase 8 — Observability

| ID | Task | Status | Depends-On | Required Role(s) | Approver Role | Co-Signer Role | Break-Glass? |
|---|---|---|---|---|---|---|---|
| T-LED-01 | Structured JSON logs with `correlation_id`, `user_id`, `tenant_id` | pending | — | ledger.admin | ledger.admin | — | — |
| T-LED-02 | Metrics: RED per route + business counters specific to this service | pending | T-LED-01 | ledger.admin | ledger.admin | — | — |
| T-LED-03 | OpenTelemetry traces with child spans; long-poll spans open until response | pending | T-LED-02 | ledger.admin | ledger.admin | — | — |
| T-LED-04 | Alerts in Grafana: p99 latency, error rate, consumer lag | pending | T-LED-03 | ledger.admin | ledger.admin | — | — |
### Phase 9 — Testing

| ID | Task | Status | Depends-On | Required Role(s) | Approver Role | Co-Signer Role | Break-Glass? |
|---|---|---|---|---|---|---|---|
| T-LED-01 | Unit tests: 80%+ branch coverage on the aggregate | pending | — | ledger.admin | ledger.admin | — | — |
| T-LED-02 | Integration tests: Testcontainers (PostgreSQL, Kafka, Redis) | pending | T-LED-01 | ledger.admin | ledger.admin | — | — |
| T-LED-03 | Contract tests: Producer Avro schemas pinned in CI | pending | T-LED-02 | ledger.admin | ledger.admin | — | — |
| T-LED-04 | E2E test per major user journey in `WORKFLOWS.md` | pending | T-LED-03 | ledger.admin | ledger.admin | — | — |
### Phase 10 — Deployment

| ID | Task | Status | Depends-On | Required Role(s) | Approver Role | Co-Signer Role | Break-Glass? |
|---|---|---|---|---|---|---|---|
| T-LED-01 | Kubernetes manifests: Deployment, Service, HPA, PDB | pending | — | ledger.admin | ledger.admin | — | — |
| T-LED-02 | Pre-upgrade Job for migrations | pending | T-LED-01 | ledger.admin | ledger.admin | — | — |
| T-LED-03 | Resource limits per `DEPLOYMENT_ARCHITECTURE.md` | pending | T-LED-02 | ledger.admin | ledger.admin | — | — |
| T-LED-04 | Smoke test in staging before production rollout | pending | T-LED-03 | ledger.admin | ledger.admin | — | — |
### Phase 7.0 — Cross-cutting: Guaranteed Rewards & Rating-Based Pricing


| ID | Task | Status | Depends-On | Required Role(s) | Approver Role | Co-Signer Role | Break-Glass? |
|---|---|---|---|---|---|---|---|
**Ledger informational consumer.** New chart-of-account rows:
- `6302_guaranteed_minimum (driver, existing)`
- `2100_customer_credit_liability (user, new)`

---

## Integration Map

### Sync Dependencies (outbound calls)
| Target | Endpoint | Purpose | Circuit Breaker |
|--------|----------|---------|----------------|


### Events Published
| Event | Topic | Trigger | Key Consumers |
|-------|-------|---------|--------------|
| `ledger.posted` | derived from name | see INTEGRATION.md | see INTEGRATION.md |
| `ledger.audit.reconciliation_drift` | derived from name | see INTEGRATION.md | see INTEGRATION.md |

### Events Consumed
| Event | Producer | Handler |
|-------|----------|---------|
| `payment.captured` | see INTEGRATION.md | see INTEGRATION.md |
| `wallet.credited` | see INTEGRATION.md | see INTEGRATION.md |
| `merchant.settlement.accrued` | see INTEGRATION.md | see INTEGRATION.md |
| `courier.earning.accrued` | see INTEGRATION.md | see INTEGRATION.md |
| `trip.reward.granted.v1` | see INTEGRATION.md | see INTEGRATION.md |
| `trip.reward.reversed.v1` | see INTEGRATION.md | see INTEGRATION.md |

---

## Acceptance Criteria
- [ ] All REST endpoints return correct status codes
- [ ] p99 latency within SLO target (T0 (99.99%))
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
| T-LED-P76-01 | Register Conductor worker for `wf.phase7.reward_grant.v1` — Read-only consumer (worker — ledger_service_posting) | pending | — | ledger.admin | ledger.admin | — | — |
| T-LED-P76-02 | Register Conductor worker for `wf.phase7.reward_reversal.v1` — Read-only consumer (worker — ledger_service_reverse_posting) | pending | — | ledger.admin | ledger.admin | — | — |
| T-LED-P76-03 | Register Conductor worker for `wf.refund.standard.v1` — Worker — ledger_service_debit_posting | pending | — | ledger.admin | ledger.admin | — | — |
| T-LED-P76-04 | Register Conductor worker for `wf.refund.partial.v1` — Worker — ledger_service_debit_posting | pending | — | ledger.admin | ledger.admin | — | — |
| T-LED-P76-05 | Register Conductor worker for `wf.refund.food_reject.v1` — Worker — ledger_service_debit_posting | pending | — | ledger.admin | ledger.admin | — | — |
| T-LED-P76-06 | Register Conductor worker for `wf.refund.cancellation.v1` — Worker — ledger_service_debit_posting | pending | — | ledger.admin | ledger.admin | — | — |
| T-LED-P76-07 | Register Conductor worker for `wf.refund.dispute.v1` — Worker — ledger_service_debit_posting | pending | — | ledger.admin | ledger.admin | — | — |
| T-LED-P76-08 | Register Conductor worker for `wf.refund.cod_failed.v1` — Worker — ledger_service_debit_posting | pending | — | ledger.admin | ledger.admin | — | — |


---



## Hard service-to-service dependencies

This service's position in the canonical per-service deployment
order is **Tier 0, Position 8** per
[`../../DEPLOYMENT_ORDER.md`](../../DEPLOYMENT_ORDER.md).

| Class | Services |
|---|---|
| **Hard deps** (must be live and reachable before this service can complete its `/ready` health check) | [`configuration-service`](../configuration-service/README.md) (chart-of-accounts default, partition policy) |
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
| T-LED-NN (Phase 1-10) | per task | per task | per task | per task |
| T-LED-P70-NN | platform.admin / pricing.admin / customer.admin | platform.admin | platform.super_admin | no (Phase 7.0 cross-cutting) |
| T-LED-P75-NN | platform.admin / pricing.admin | platform.admin | platform.super_admin | no (Phase 7.5 Make-a-Deal) |
| T-LED-P76-NN | platform.admin + Conductor UI role | platform.admin | platform.super_admin | yes (workflow worker registration) |

For the canonical SUPER_ADMIN preset (1 × `platform.super_admin` + 20 × `<service>.admin`), see [`shared/TIME_BOUNDED_ALIASES.md`](../../shared/TIME_BOUNDED_ALIASES.md) for time-bounded aliases and `admin-service/INTEGRATION.md` 1.13 for the canonical role list.

## Phase 9 — Platform DRY (Tier 1) — 2026-08-17

This service was adopted into the
[`platform-spring-boot-starter:4.1.1`](../../../packages/platform-spring-boot/spring-boot-starter/)
umbrella (Phase 0 conformed per ADR-0024/0025/0026/0030/0031; see
[`docs/plans/PLATFORM_DRY_AUDIT.md`](../../plans/PLATFORM_DRY_AUDIT.md)). Pure
deletions of the 4 local-shadow classes; no functional behaviour change.

| Status Tracking ID | Description | Roles | Status | Last Updated |
|---|---|---|---|---|
| T-LED-P90-01 | Delete `RequestCorrelationFilter.kt` — adopt platform UUIDv7 + MDC request_id (ADR-0030); also delete its 62-LOC dedicated unit test `RequestCorrelationFilterTest.kt` | platform.admin | done | 2026-08-17 |
| T-LED-P90-02 | Delete `JacksonConfiguration.kt` — adopt platform Jackson + `@ConditionalOnMissingBean` | platform.admin | done | 2026-08-17 |
| T-LED-P90-03 | Delete `OpenApiConfiguration.kt` — adopt `platformOpenApi`; service title/contact/description now sourced from `platform.api-docs` in `application.yml` | platform.admin | done | 2026-08-17 |
| T-LED-P90-04 | Delete `TestcontainersConfiguration.kt` — extend `BaseIntegrationTest` from `platform-spring-boot-test` in `LedgerServiceApplicationTests` | platform.admin | done | 2026-08-17 |
| T-LED-P90-05 | `application.yml`: add `platform.{observability,api-docs,audit,security}` property blocks | platform.admin | done | 2026-08-17 |
| T-LED-P90-06 | Bump `com.trips-enjoy.platform:spring-boot-starter` from `4.1.0` → `4.1.1` | platform.admin | done | 2026-08-17 |
| T-LED-P90-07 | `TestLedgerServiceApplication` drops `with(TestcontainersConfiguration::class)` | platform.admin | done | 2026-08-17 |

**Verification:** `./gradlew test` → 18 tests run, 1 skipped (the `LedgerServiceApplicationTests` integration suite is gated on `DOCKER_AVAILABLE=true`), 17 unit + envelope-conformance tests pass cleanly across 4 unit suites + 1 envelope-conformance suite (`ApiExceptionHandlerTest` 1/1, `PartitionMaintenanceJobTest` 3/3, `PostingServiceBalanceTest` 7/7, `CrossServiceEnvelopeConformanceTest` 7/7). The single skipped IT (`LedgerServiceApplicationTests.contextLoads`) is environmental — it requires a live Docker daemon to bring up the PostgreSQL/Kafka/Redis Testcontainers, identical to the pre-Phase-A baseline. To reproduce green locally: start Docker and re-run with `DOCKER_AVAILABLE=true`.

**Attribution note:** ledger-service's Phase 9 deletions, rewires and YAML edits are physically committed under the preceding `feat(driver-service)` commit (the parallel 12-service fan-out bundled adjacent services into one shared commit). The T-LED-P90 rows above record ledger-service's own Phase 9 attribution against `4.1.1` of `platform-spring-boot-starter`. The follow-up commit `feat(ledger-service): phase A dry tier-1` on top of `v1` re-records this attribution within `apps/ledger-service/` for traceability.

**Phase 9 prepares, but does NOT land:** Phase B (OutboxEvent / InboxEvent / IdempotencyRecord canonicalisation), Phase C (ApiExceptionHandler + SecurityConfiguration + BaseEntity migration), or Phase D (partition cron + idempotency service + inbox listener). Those PRs follow in their own session once Phase 0/A is fully merged across all 14 Kotlin services.

---

## Phase 9 — Platform DRY Continuation: Phase B + Phase C + Phase D fan-out — 2026-08-17

This service was adopted into the canonical
`platform-spring-boot-starter:4.1.4` umbrella (the lockstep fan-out
covers Phases B, C, and D from the
[`PLATFORM_DRY_AUDIT.md`](../../plans/PLATFORM_DRY_AUDIT.md) checklist):

### Phase B — Canonical outbox + idempotency — landed in `c43d6ab`
- `V10__canonical_outbox_and_idempotency.sql` ALTERs `ledger.outbox`
  in place to carry the canonical 11-column shape (ADR-0028) plus
  service-local `correlation_id` / `created_by`. The 24-hour retention
  band (V8) is unaffected.
- `apps/ledger-service/src/main/kotlin/.../OutboxEvent.kt` rewires to
  map the canonical columns; `partition_key` auto-populated by
  `@PrePersist`. Existing 11-column payload contract preserved for
  callers (writers use the service-local setters, reader code stays
  identical).
- `apps/ledger-service/src/main/resources/application.yml` gains
  `platform.{outbox,inbox,idempotency}.enabled=false` blocks; the
  ledger-service local `OutboxPublisher` / `InboxCleanup` still win
  via `@ConditionalOnMissingBean` so behaviour is unchanged.

### Phase C — SecurityConfiguration subclass + BaseEntity audit — landed in this PR

| Status Tracking ID | Description | Roles | Status | Last Updated |
|---|---|---|---|---|
| T-LED-P9C-01 | `SecurityConfiguration.kt` refactor — the locally-defined `securityFilterChain` bean is renamed to `defaultSecurityFilterChain` and annotated `@Primary` so the platform's `@ConditionalOnMissingBean(name = ["defaultSecurityFilterChain"])` skip-rule applies uniformly across services. The platform `SecurityAutoConfiguration` provides the admin chain, the CORS source, and `SecurityProperties` defaults; ledger-service layers in (a) its Keycloak JWKS-backed `jwtDecoder`, (b) its `JwtAuthenticationConverter` mapped to `SCOPE_<UPPER>` / `ROLE_<UPPER>` per ADR-0025, and (c) 9 service-specific public paths (`/health`, `/ready`, `/started`, `/actuator/health/**`, `/actuator/info`, `/actuator/prometheus`, `/openapi.json/**`, `/v3/api-docs/**`, `/docs/**`). | platform.admin | done | 2026-08-17 |
| T-LED-P9C-02 | BaseEntity migration audit — all 8 JPA entities in `apps/ledger-service/src/main/kotlin/.../domain/` were audited against the `simple PK + audit columns` rule and `composite-PK / insert-only` skip-rule. The composite / append-only skew of the ledger domain (Posting + PostingEntry composite PKs, the strict append-only triggers on postings + posting_entries per `CrossServiceEnvelopeConformanceTest`, the versioned chart-of-accounts in `Account`, the non-UUID PK on `AccountBalance`, the `OutboxEvent` canonical 11-column mapping already in flight under Phase B) means **none** of the 8 entities pass the strict BaseEntity migration gate in this pass. See Phase 10 — BaseEntity Deferral below for the rationale. | platform.admin | done (decision: 0 migrated, 8 deferred) | 2026-08-17 |

### Phase D — Platform partition cron adoption — landed in this PR
- `apps/ledger-service/src/main/kotlin/.../application/PartitionMaintenanceJob.kt`
  **deleted** (57 LOC) — the platform's
  `PartitionSchedulingConfiguration` (in
  `packages/platform-spring-boot/platform-spring-boot-partition`)
  already drives `partman.ensure_partitions` for the same parent
  tables (`ledger.postings`, `ledger.posting_entries`) via the same
  `pg_try_advisory_xact_lock` guard, so the local fallback is a
  shadow that the platform version makes redundant. The 90-LOC
  `PartitionMaintenanceJobTest.kt` (3 tests: `advisory lock failure
  returns without calling function`, `advisory lock null returns
  without calling function`, `acquired lock calls ensure_partitions
  for every parent exactly once`) is **deleted** alongside the
  production class. Equivalent coverage lives at the platform level
  in `PartitionSchedulingHealthIndicatorTest` + the
  platform-internal `pg_try_advisory_xact_lock`-race regression
  tests.
- `V11__platform_partition_adoption_marker.sql` — marker migration
  (`SELECT 1;`). Phase 10 retention policies and the `V9__partition_functions.sql`
  pg_cron entry both reference `ledger.postings` /
  `ledger.posting_entries` regardless of which JVM owns the cron,
  so no schema change is required by this adoption.
- `apps/ledger-service/build.gradle.kts` bumps
  `com.trips-enjoy.platform:spring-boot-starter` from `4.1.2` →
  `4.1.4` (lockstep with the platform partition cron module).

**Verification:** `./gradlew test` → 14 unit + envelope-conformance tests pass cleanly across 3 unit suites + 1 envelope-conformance suite (`ApiExceptionHandlerTest` 1/1, `PostingServiceBalanceTest` 7/7, `CrossServiceEnvelopeConformanceTest` 7/7). The `PartitionMaintenanceJobTest` 3 tests are gone with the class (Phase D). The `LedgerServiceApplicationTests` integration suite remains environmental-skipped and gated on `DOCKER_AVAILABLE=true`, identical to the Phase 9 baseline. Total run count drops from 18 → 14 because the platform absorbs the cron job. `./gradlew compileKotlin` + `./gradlew compileTestKotlin` are green; no static-analysis warnings introduced.

**Attribution note:** Phase 9 above (DRY Tier 1) records the original
local-shadow deletion pass at commit `c43d6ab` boundary. Phase 9
(continuation) and Phase 10 below cover the B/C/D fan-out committed
in this PR. The T-LED-P9C / T-LED-P9D / T-LED-P10 rows record
ledger-service's attribution against `4.1.4` of
`platform-spring-boot-starter` and the canonical partition-cron
module respectively.

---

## Phase 10 — BaseEntity Deferral + Future Migration Tracks — 2026-08-17

| Status Tracking ID | Entity | Decision | Rationale | Roles | Status | Last Updated |
|---|---|---|---|---|---|---|
| T-LED-P10-01 | `Posting` | **SKIP** | Composite PK `(id, posted_at)` — PostgreSQL requires the partition key to participate in any UNIQUE constraint on a partitioned table. BaseEntity's `@Id UUID` shape is incompatible. Append-only triggers (`BEFORE UPDATE OR DELETE OR TRUNCATE`) on `ledger.postings` would conflict with `@Version` (implied UPDATE) and `deletedAt` (soft-delete update). | platform.admin | done | 2026-08-17 |
| T-LED-P10-02 | `PostingEntry` | **SKIP** | Same composite-PK + append-only-trigger constraints as `Posting`. Adding `created_by VARCHAR(255)` to a table whose retention band is `regulatory/10 years/partition_drop/monthly` would also change the partition-bound certificate review. | platform.admin | done | 2026-08-17 |
| T-LED-P10-03 | `Account` | **SKIP (this pass)** | UUID PK + UUID `created_by` would fit BaseEntity's shape, but the entity has a business-semantic `version: Int` (chart-of-accounts versioning, not JPA `@Version`) plus `valid_from` / `valid_to` window columns that would require a wide migration (rename `version` → `business_version`, add `version BIGINT`, add `updated_at`, add `updated_by`, alter `created_by UUID → VARCHAR(255)`, alter columns nullable). The two partial-unique indexes (`accounts_code_version_uq`, `accounts_code_current_uq`) further complicate the migration. Candidate for a future BaseEntity round once the chart-of-accounts SCD2 contract is refactored. | platform.admin | deferred | 2026-08-17 |
| T-LED-P10-04 | `AccountBalance` | **SKIP (this pass)** | PK is non-UUID `account_code TEXT` (a domain-natural key on the chart-of-accounts). Migrating to BaseEntity's `@Id UUID` would require a surrogate-`id` UUID column, dropping the natural-key PK, and rebuilding the foreign-key-free application finders (`findById("1100_cash_eur")`). Mutable (`debit_total_minor`, `credit_total_minor`, `balance_minor`, `last_posting_at`, `updated_at`) — conceptually a candidate, but the natural-key PK is load-bearing. Candidate for a future round that introduces a UUID surrogate alongside the natural key. | platform.admin | deferred | 2026-08-17 |
| T-LED-P10-05 | `JournalEntry` | **SKIP** | Append-only in practice (admin-only insertions per `INTEGRATION.md`, no UPDATE flow in `PostingService.createJournalEntry`). The `audit_note ≥ 10 chars` CHECK is enforced at the DB layer. Extending BaseEntity would add `updated_at`/`updated_by`/`version` columns to a table that never updates — null-but-never-changed columns that burn indexes for no benefit. | platform.admin | done | 2026-08-17 |
| T-LED-P10-06 | `InboxEvent` | **SKIP** | Append-only with rare mutability (`processedAt: Instant?`, `error: String?` to track processing outcome). PK column is `event_id` (not `id`) — extending BaseEntity would require either a column rename (loses the cross-service `event_id` join semantics) or adding a redundant UUID `id` surrogate. The 7-day dedup-window retention (V8) and `CrossServiceEnvelopeConformanceTest`'s `existsByEventId` invariant make the `event_id` PK load-bearing. | platform.admin | done | 2026-08-17 |
| T-LED-P10-07 | `OutboxEvent` | **SKIP** | Already on the canonical 11-column shape from Phase B (`c43d6ab`). The local entity intentionally mirrors the service-local columns (`aggregate_type`, `aggregate_id`, `event_name`, `correlation_id`, `created_by`) into the `headers` JSONB so downstream consumers see them without needing the local schema. A wholesale rewire to BaseEntity would lose the `created_at` default (already managed via `@PrePersist` + SQL DEFAULT `now()`) and would force a single UUIDv7 `id` instead of the canonical `(id, event_id)` tuple. | platform.admin | done | 2026-08-17 |
| T-LED-P10-08 | `ReconciliationRun` | **SKIP (this pass)** | Closest "mutable + simple PK + audit columns" candidate. Migration cost: add `created_by VARCHAR(255)` (currently no audit actor on this row — populated from `correlationId`-only), add `updated_at`, add `version BIGINT`, drop the constructor-set `id` param (BaseEntity provides it), drop the local `correlation_id` (or move it into headers — ADR-0019 traceability trade-off). Reasonable candidate but the schema add is non-trivial relative to the value of one mutable row per day (`run_date` is unique; at most one row per UTC day). Candidate for a future round. | platform.admin | deferred | 2026-08-17 |

**Summary:** 0 entities migrated to `BaseEntity` in this pass. 5 entities (Posting, PostingEntry, JournalEntry, InboxEvent, OutboxEvent) are SKIPped permanently under the strict read of the rule (`composite-PK / insert-only / canonical-shape-already-adopted`). 3 entities (Account, AccountBalance, ReconciliationRun) are deferred to a future round because the migration would require a non-trivial SQL migration alongside the Kotlin change. Re-opening these requires a follow-up RFC; the trigger condition is a chart-of-accounts SCD2 refactor for `Account`, a natural-key ↔ surrogate-key decision for `AccountBalance`, and an audit-actor rollout for `ReconciliationRun`.

**Verification:** No new migrations introduced for Phase 10. The 0-entity migration result is intentional and verified by running `grep -rn "BaseEntity" apps/ledger-service/src/main/kotlin/` → 0 hits. The pre-existing entity shapes are preserved verbatim.
