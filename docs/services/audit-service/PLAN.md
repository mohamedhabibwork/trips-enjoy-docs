# audit-service — Implementation Plan

**Domain:** Platform & Operations
**Tier:** 0 (position 3 of 21; `DEPLOYMENT_ORDER.md` §2)
**Technology:** Kotlin + Spring Boot 4 + Spring Kafka
**Criticality:** T2 (99.9% SLO)
**DB Schema:** `audit`
**Cache:** —
**HPA:** Kafka consumer lag, 2–8, 20k evt/s

---

## Purpose

`audit-service` is the platform's immutable audit log. It consumes every audit-relevant event from every service, persists them in an append-only, cryptographically hash-chained store, and exposes a strict-RBAC search API for compliance and security teams.

---

## Tasks

### Phase 1 — Database & Domain Model

| ID | Task | Status | Depends-On | Required Role(s) | Approver Role | Co-Signer Role | Break-Glass? |
|---|---|---|---|---|---|---|---|
| T-AUD-01 | Create schema `audit`: tables `events` (append-only, partitioned by month), `litigation_holds`, `outbox`, `inbox` | pending | — | audit.admin | audit.admin | — | — |
| T-AUD-02 | Key columns: `events(id UUID, event_id UUID UNIQUE, event_name TEXT, occurred_at TIMESTAMPTZ, producer TEXT, tenant_id TEXT, aggregate_type TEXT, aggregate_id UUID, subject_type TEXT, subject_id UUID, hash TEXT, prev_hash TEXT, data JSONB)` | pending | T-AUD-01 | audit.admin | audit.admin | — | — |
| T-AUD-03 | Write Flyway migrations (forward-only); DB grants: no UPDATE/DELETE on `audit.events` | pending | T-AUD-02 | audit.events | audit.events | — | — |
| T-AUD-04 | Implement `AuditEvent` aggregate (append-only), hash chain computation | pending | T-AUD-03 | audit.admin | audit.admin | — | — |
### Phase 2 — REST API

| ID | Task | Status | Depends-On | Required Role(s) | Approver Role | Co-Signer Role | Break-Glass? |
|---|---|---|---|---|---|---|---|
| T-AUD-01 | `POST /v1/audit/search` — search audit log (requires `audit.read`, `reason` param) | pending | — | audit.read | audit.read | — | — |
| T-AUD-02 | `GET /v1/audit/events/{id}` — read single event including hash and prev_hash | pending | T-AUD-01 | audit.admin | audit.admin | — | — |
| T-AUD-03 | `GET /v1/audit/verify/{id}` — verify hash chain up to event (requires `audit.admin`) | pending | T-AUD-02 | audit.admin | audit.admin | — | — |
| T-AUD-04 | `POST /v1/audit/litigation-hold` — create litigation hold (requires `audit.admin`, `Idempotency-Key`) | pending | T-AUD-03 | audit.admin | audit.admin | — | — |
### Phase 3 — Event Publishing

| ID | Task | Status | Depends-On | Required Role(s) | Approver Role | Co-Signer Role | Break-Glass? |
|---|---|---|---|---|---|---|---|
| T-AUD-01 | Implement transactional outbox table | pending | — | audit.admin | audit.admin | — | — |
| T-AUD-02 | Publish `audit.export.completed.v1` → topic `audit.export.completed` (nightly export success) | pending | T-AUD-01 | audit.export.completed | audit.export.completed | — | — |
| T-AUD-03 | Publish `audit.consumer.lag.v1` → topic `audit.consumer.lag` (periodic, every minute) | pending | T-AUD-02 | audit.consumer.lag | audit.consumer.lag | — | — |
| T-AUD-04 | Publish `audit.hash_chain.verified.v1` → topic `audit.hash_chain.verified` (daily verification job) | pending | T-AUD-03 | audit.hash_chain.verified | audit.hash_chain.verified | — | — |
| T-AUD-05 | Publish `audit.security.compliance_violation.v1` → topic `platform.audit.security` | pending | T-AUD-04 | platform.audit.security | platform.audit.security | — | — |
| T-AUD-06 | Publish `audit.security.break_glass_used.v1` → topic `platform.audit.security` | pending | T-AUD-05 | platform.audit.security | platform.audit.security | — | yes |
| T-AUD-07 | Publish `audit.retention.purge_completed.v1` → topic `platform.audit.retention` | pending | T-AUD-06 | platform.audit.retention | platform.audit.retention | — | — |
| T-AUD-08 | Outbox poller (200ms interval, DLQ) | pending | T-AUD-07 | audit.admin | audit.admin | — | — |
### Phase 4 — Event Consumption

| ID | Task | Status | Depends-On | Required Role(s) | Approver Role | Co-Signer Role | Break-Glass? |
|---|---|---|---|---|---|---|---|
| T-AUD-01 | Implement inbox table for deduplication (keyed by `event_id`) | pending | — | audit.admin | audit.admin | — | — |
| T-AUD-02 | Consume `admin.action.performed.v1` → append immutable row | pending | T-AUD-01 | audit.admin | audit.admin | — | — |
| T-AUD-03 | Consume `payment.*` events → append immutable rows (7-year retention) | pending | T-AUD-02 | audit.admin | audit.admin | — | — |
| T-AUD-04 | Consume `wallet.*`, `ledger.posted.v1` → append immutable rows (7-year retention) | pending | T-AUD-03 | audit.admin | audit.admin | — | — |
| T-AUD-05 | Consume `trip.*`, `ride.request.*`, `dispatch.*` → append immutable rows | pending | T-AUD-04 | audit.admin | audit.admin | — | — |
| T-AUD-06 | Consume `food.order.*`, `delivery.*` → append immutable rows | pending | T-AUD-05 | audit.admin | audit.admin | — | — |
| T-AUD-07 | Consume `identity.user.*`, `customer.*`, `driver.*`, `courier.*` → append immutable rows | pending | T-AUD-06 | audit.admin | audit.admin | — | — |
| T-AUD-08 | Consume `merchant.*`, `restaurant.*`, `configuration.updated.v1`, `feature_flag.updated.v1` → append | pending | T-AUD-07 | audit.admin | audit.admin | — | — |
| T-AUD-09 | Consume `promotion.*`, `loyalty.*`, `review.*`, `tax.*`, `pricing.quote.created.v1` → append | pending | T-AUD-08 | audit.admin | audit.admin | — | — |
| T-AUD-10 | Consume `notification.*`, `comms.*`, `support.ticket.*`, `fraud.*`, `file.*`, `zone.*` → append | pending | T-AUD-09 | audit.admin | audit.admin | — | — |
### Phase 5 — Caching

| ID | Task | Status | Depends-On | Required Role(s) | Approver Role | Co-Signer Role | Break-Glass? |
|---|---|---|---|---|---|---|---|
| T-AUD-01 | No caching (read path is direct from DB) | pending | — | audit.admin | audit.admin | — | — |
| T-AUD-02 | In-process daily verification result cache | pending | T-AUD-01 | audit.admin | audit.admin | — | — |
### Phase 6 — External Integrations

| ID | Task | Status | Depends-On | Required Role(s) | Approver Role | Co-Signer Role | Break-Glass? |
|---|---|---|---|---|---|---|---|
| T-AUD-01 | AWS S3 — nightly export to `s3://trips-enjoy-platform-audit/audit/exports/<yyyy>/<mm>/<dd>/` | pending | — | audit.admin | audit.admin | — | — |
| T-AUD-02 | HashiCorp Vault — DB credentials | pending | T-AUD-01 | audit.admin | audit.admin | — | — |
| T-AUD-03 | Circuit breakers not required (no synchronous outbound) | pending | T-AUD-02 | audit.admin | audit.admin | — | — |
### Phase 7 — Security

| ID | Task | Status | Depends-On | Required Role(s) | Approver Role | Co-Signer Role | Break-Glass? |
|---|---|---|---|---|---|---|---|
| T-AUD-01 | JWT bearer auth via Keycloak (Spring Security 7), realm `platform-internal` | pending | — | audit.admin | audit.admin | — | — |
| T-AUD-02 | Required scopes/roles: `audit.read` for compliance, `audit.admin` for security | pending | T-AUD-01 | audit.read, audit.admin | audit.admin | — | — |
| T-AUD-03 | Column-level encryption for sensitive PII fields (`pgcrypto`) | pending | T-AUD-02 | audit.admin | audit.admin | — | — |
| T-AUD-04 | No UPDATE/DELETE grants on `audit.events` table at DB level | pending | T-AUD-03 | audit.events | audit.events | — | — |
| T-AUD-05 | Secrets via HashiCorp Vault | pending | T-AUD-04 | audit.admin | audit.admin | — | — |
### Phase 8 — Observability

| ID | Task | Status | Depends-On | Required Role(s) | Approver Role | Co-Signer Role | Break-Glass? |
|---|---|---|---|---|---|---|---|
| T-AUD-01 | Structured JSON logs with `correlation_id` | pending | — | audit.admin | audit.admin | — | — |
| T-AUD-02 | Metrics: RED per route + `audit_events_ingested_total{topic}`, `audit_consumer_lag{topic,partition}`, `audit_export_seconds`, `audit_hash_chain_status` | pending | T-AUD-01 | audit.admin | audit.admin | — | — |
| T-AUD-03 | OpenTelemetry traces with child spans per event for DB insert, hash computation | pending | T-AUD-02 | audit.admin | audit.admin | — | — |
| T-AUD-04 | Health endpoints: `/actuator/health`, `/ready`, `/started` | pending | T-AUD-03 | audit.admin | audit.admin | — | — |
### Phase 9 — Testing

| ID | Task | Status | Depends-On | Required Role(s) | Approver Role | Co-Signer Role | Break-Glass? |
|---|---|---|---|---|---|---|---|
| T-AUD-01 | Unit tests: hash chain computation, inbox deduplication, retention policy | pending | — | audit.admin | audit.admin | — | — |
| T-AUD-02 | Integration tests: Testcontainers (PostgreSQL, Kafka) | pending | T-AUD-01 | audit.admin | audit.admin | — | — |
| T-AUD-03 | E2E tests: ingest event, search, verify hash chain, litigation hold | pending | T-AUD-02 | audit.admin | audit.admin | — | — |
### Phase 10 — Deployment

| ID | Task | Status | Depends-On | Required Role(s) | Approver Role | Co-Signer Role | Break-Glass? |
|---|---|---|---|---|---|---|---|
| T-AUD-01 | Kubernetes manifests: Deployment, Service, HPA (Kafka consumer lag, 2–8 replicas), PDB | pending | — | audit.admin | audit.admin | — | — |
| T-AUD-02 | Pre-upgrade Job for database migrations | pending | T-AUD-01 | audit.admin | audit.admin | — | — |
| T-AUD-03 | Resource limits per DEPLOYMENT_ARCHITECTURE.md | pending | T-AUD-02 | audit.admin | audit.admin | — | — |
---

## Integration Map

### Sync Dependencies (outbound calls)
| Target | Endpoint | Purpose | Circuit Breaker |
|--------|----------|---------|----------------|
| AWS S3 | PUT | Nightly export | No (managed retry) |

### Events Published
| Event | Topic | Trigger | Key Consumers |
|-------|-------|---------|--------------|
| `audit.export.completed.v1` | `audit.export.completed` | Nightly export success | `reporting-service` |
| `audit.consumer.lag.v1` | `audit.consumer.lag` | Periodic (every minute) | Monitoring |
| `audit.hash_chain.verified.v1` | `audit.hash_chain.verified` | Daily verification | `admin-service` |
| `audit.security.compliance_violation.v1` | `platform.audit.security` | Compliance violation detected | `fraud-risk-service`, `admin-service` |
| `audit.security.break_glass_used.v1` | `platform.audit.security` | Break-glass admin action | `fraud-risk-service`, `notification-service` |
| `audit.retention.purge_completed.v1` | `platform.audit.retention` | Retention purge job | `admin-service` |

### Events Consumed
| Event | Producer | Handler |
|-------|----------|---------|
| `admin.action.performed.v1` | `admin-service` | Append immutable row |
| `payment.captured.v1` | `payment-service` | Append immutable row (7-year retention) |
| `ledger.posted.v1` | `ledger-service` | Append immutable row (7-year retention) |
| `trip.completed.v1` | `trip-service` | Append immutable row |
| `customer.suspended.v1` | `customer-service` | Append immutable row |
| `food.order.delivered.v1` | ``courier-service` (delivery)` | Append immutable row |
| All `*.audit.*` topics | All services | Append immutable row |

---

## Acceptance Criteria
- [ ] All REST endpoints return correct status codes
- [ ] p99 latency within SLO targets
- [ ] All events published reliably via outbox pattern
- [ ] Zero data leakage across service boundaries
- [ ] 80%+ unit test coverage

---

## Related Docs
- [README](README.md) · [BRD](BRD.md) · [SRS](SRS.md) · [ERD](ERD.md) · [INTEGRATION](INTEGRATION.md) · [WORKFLOWS](WORKFLOWS.md) · [TECH](TECH.md)
- [Master Plan](../../MASTER_PLAN.md)

### Phase 7.6 — Conductor Workers

This service runs Conductor workers for the following workflows per
[ADR-0018](../../architecture/adrs/0018-workflow-engine-conductor.md)
and [`shared/CONDUCTOR_WORKFLOWS.md`](../../shared/CONDUCTOR_WORKFLOWS.md).
The full worker contract (task names, idempotency-key namespaces,
Kafka signal mapping, compensation responsibilities) is in
[`INTEGRATION.md`](./INTEGRATION.md) "Conductor Workers".

| ID | Task | Status | Depends-On | Required Role(s) | Approver Role | Co-Signer Role | Break-Glass? |
|---|---|---|---|---|---|---|---|
| T-AUD-P76-01 | Register Conductor worker for `wf.phase7.reward_grant.v1` — Read-only consumer (worker — audit_service_reward_row) | pending | — | audit.admin | audit.admin | — | — |
| T-AUD-P76-02 | Register Conductor worker for `wf.phase7.reward_reversal.v1` — Read-only consumer (worker — audit_service_reward_reversal_row) | pending | — | audit.admin | audit.admin | — | — |
| T-AUD-P76-03 | Register Conductor worker for `wf.onboarding.driver.v1` — Read-only consumer | pending | — | audit.admin | audit.admin | — | — |
| T-AUD-P76-04 | Register Conductor worker for `wf.onboarding.courier.v1` — Read-only consumer | pending | — | audit.admin | audit.admin | — | — |
| T-AUD-P76-05 | Register Conductor worker for `wf.phase75.deal_rider.v1` — Worker — audit_service_deal_transition (audit.deal_transition.v1) | pending | — | audit.admin | audit.admin | — | — |
| T-AUD-P76-06 | Register Conductor worker for `wf.phase75.deal_driver.v1` — Worker — audit_service_deal_transition | pending | — | audit.admin | audit.admin | — | — |
| T-AUD-P76-07 | Register Conductor worker for `wf.phase75.deal_food.v1` — Worker — audit_service_deal_transition | pending | — | audit.admin | audit.admin | — | — |

### Phase 7.0 — Cross-cutting: Guaranteed Rewards & Rating-Based Pricing

This service participates in Phase 7 (cross-cutting) per
[`MASTER_PLAN.md`](../../MASTER_PLAN.md) "Phase 7 — Cross-cutting".
See canonical scope there; this block lists only the cross-cutting
tasks this service owns. Full audit history lives in
[`MASTER_TASK.md`](../../MASTER_TASK.md).

| ID | Task | Status | Depends-On | Required Role(s) | Approver Role | Co-Signer Role | Break-Glass? |
|---|---|---|---|---|---|---|---|
| T-AUD-P70-01 | Implement `audit.trip_reward.v1` row writer that consumes `trip.reward.granted.v1` and `trip.reward.reversed.v1` from [`trip-service`](../../services/trip-service/PLAN.md) per [`MASTER_PLAN.md`](../../MASTER_PLAN.md) Phase 7 table row 136 | pending | — | audit.admin | audit.admin | — | — |
| T-AUD-P70-02 | Verify idempotency-key namespace matches the per-flow convention in [`shared/CONDUCTOR_WORKFLOWS.md`](../../shared/CONDUCTOR_WORKFLOWS.md) 4 | pending | T-AUD-P70-01 | audit.admin | audit.admin | — | — |

### Phase 7.5 — Make-a-Deal Kernel

This service participates in Phase 7.5 (Make-a-Deal kernel) per
[`MASTER_PLAN.md`](../../MASTER_PLAN.md) "Phase 7.5" and the canonical
contract in [`shared/DEAL_FEATURE.md`](../../shared/DEAL_FEATURE.md).
See canonical scope there; this block lists only the deal-flow tasks
this service owns.

| ID | Task | Status | Depends-On | Required Role(s) | Approver Role | Co-Signer Role | Break-Glass? |
|---|---|---|---|---|---|---|---|
| T-AUD-P75-01 | Implement deal-transition consumer that consumes all 12 `*.deal.*.v1` events and writes `audit.deal_transition.v1` per [`shared/DEAL_FEATURE.md`](../../shared/DEAL_FEATURE.md) 4.1 | pending | — | audit.admin | audit.admin | — | — |
| T-AUD-P75-02 | Wire TTL-driven deal-expired transitions to Conductor signal per [`shared/CONDUCTOR_WORKFLOWS.md`](../../shared/CONDUCTOR_WORKFLOWS.md) 3.2 | pending | T-AUD-P75-01 | audit.admin | audit.admin | — | — |

---



## Hard service-to-service dependencies

This service's position in the canonical per-service deployment
order is **Tier 0, Position 3** per
[`../../DEPLOYMENT_ORDER.md`](../../DEPLOYMENT_ORDER.md).

| Class | Services |
|---|---|
| **Hard deps** (must be live and reachable before this service can complete its `/ready` health check) | [`configuration-service`](../configuration-service/README.md) (Kafka topic config, retention policy) |
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
| T-AUD-NN (Phase 1-10) | per task | per task | per task | per task |
| T-AUD-P70-NN | platform.admin / pricing.admin / customer.admin | platform.admin | platform.super_admin | no (Phase 7.0 cross-cutting) |
| T-AUD-P75-NN | platform.admin / pricing.admin | platform.admin | platform.super_admin | no (Phase 7.5 Make-a-Deal) |
| T-AUD-P76-NN | platform.admin + Conductor UI role | platform.admin | platform.super_admin | yes (workflow worker registration) |

For the canonical SUPER_ADMIN preset (1 × `platform.super_admin` + 20 × `<service>.admin`), see [`shared/TIME_BOUNDED_ALIASES.md`](../../shared/TIME_BOUNDED_ALIASES.md) for time-bounded aliases and `admin-service/INTEGRATION.md` 1.13 for the canonical role list.

## Phase 9 — Platform DRY (Tier 1) — 2026-08-17

This service was adopted into the
[`platform-spring-boot-starter:4.1.1`](../../../packages/platform-spring-boot/spring-boot-starter/)
umbrella (Phase 0 conformed per ADR-0024/0025/0026/0030/0031; see
[`docs/plans/PLATFORM_DRY_AUDIT.md`](../../plans/PLATFORM_DRY_AUDIT.md)). Pure
deletions of the 5 local-shadow classes; no functional behaviour change.

| Status Tracking ID | Description | Roles | Status | Last Updated |
|---|---|---|---|---|
| T-AUD-P90-01 | Delete `RequestCorrelationFilter.kt` — adopt platform UUIDv7 + MDC request_id (ADR-0030) | platform.admin | done | 2026-08-17 |
| T-AUD-P90-02 | Delete `JacksonConfiguration.kt` — adopt platform Jackson + `@ConditionalOnMissingBean` | platform.admin | done | 2026-08-17 |
| T-AUD-P90-03 | Delete `OpenApiConfiguration.kt` — adopt `platformOpenApi` bean (no shadow-specific test existed) | platform.admin | done | 2026-08-17 |
| T-AUD-P90-04 | Delete `MetricsConfiguration.kt` — adopt platform `MeterRegistryCustomizer` common-tag pipeline | platform.admin | done | 2026-08-17 |
| T-AUD-P90-05 | Delete `TestcontainersConfiguration.kt` — extend `BaseIntegrationTest` from platform-spring-boot-test across **1 IT class** (`AuditServiceApplicationTests`) | platform.admin | done | 2026-08-17 |
| T-AUD-P90-06 | `application.yml`: add `platform.{observability,api-docs,audit,security}` property blocks | platform.admin | done | 2026-08-17 |
| T-AUD-P90-07 | Bump `com.trips-enjoy.platform:spring-boot-starter` from `4.1.0` → `4.1.1` | platform.admin | done | 2026-08-17 |
| T-AUD-P90-08 | `TestAuditServiceApplication` drops `with(TestcontainersConfiguration::class)` | platform.admin | done | 2026-08-17 |

**Verification:** `./gradlew test` → 40 tests run, 0 skipped, 39 unit tests pass cleanly across 10 suites (`AuditIngestServiceTest` 6/6, `AuditVerifyServiceTest` 3/3, `AuditDevDataSeederTest` 6/6, `IngestionMetricsTest` 3/3, `PartitionMaintenanceJobTest` 3/3, `LocalFsExporterTest` 1/1, `LitigationHoldServiceTest` 3/3, `HashChainTest` 8/8, `RetentionClassTest` 4/4, `ApiExceptionHandlerTest` 2/2). 1 IT-class failure (`AuditServiceApplicationTests.contextLoads()`) is pre-existing environmental dependency on a live PostgreSQL Testcontainer (`Failed to determine a suitable driver class` against `application-dev.yml`'s default `0.0.0.0:5432`) — identical to the pre-Phase-A baseline and matches the identity-service failure mode on the same `BaseIntegrationTest` base class.

**Phase 9 prepares, but does NOT land:** Phase B (OutboxEvent / InboxEvent / IdempotencyRecord canonicalisation), Phase C (ApiExceptionHandler + SecurityConfiguration + BaseEntity migration), or Phase D (partition cron + idempotency service + inbox listener). Those PRs follow in their own session once Phase 0/A is fully merged across all 14 Kotlin services.

## Phase 10 — Platform DRY (Tier 2: Phases B/C/D) — 2026-08-17

Continues the [`PLATFORM_DRY_AUDIT.md`](../../plans/PLATFORM_DRY_AUDIT.md)
programme from the Phase 9 (Tier 1) deletions into the canonical
outbox contract (Phase B), the security/entity conformance work
(Phase C), and the centralised partition cron (Phase D). The service
tracks `platform:spring-boot-starter:4.1.4`.

### Phase B — canonical outbox (ADR-0028)

| Status Tracking ID | Description | Roles | Status | Last Updated |
|---|---|---|---|---|
| T-AUD-P100-01 | `V6__canonical_outbox_and_idempotency.sql`: add `event_id`, `partition_key`, `headers`, `next_attempt_at`, `correlation_id`, `created_by` to `audit.outbox` | platform.admin | done | 2026-08-17 |
| T-AUD-P100-02 | Align local `OutboxEvent.kt` with the 11-column canonical contract; auto-populate `event_id` / `partition_key` via `@PrePersist` + `init` | platform.admin | done | 2026-08-17 |
| T-AUD-P100-03 | Serialize canonical `headers` JSONB with proper JSON escaping | platform.admin | done | 2026-08-17 |
| T-AUD-P100-04 | `application.yml`: add `platform.{outbox,inbox,idempotency}` blocks, all `enabled: false` so the local publisher/inbox/idempotency paths still win | platform.admin | done | 2026-08-17 |

### Phase C — BaseEntity + SecurityConfiguration conformance

| Status Tracking ID | Description | Roles | Status | Last Updated |
|---|---|---|---|---|
| T-AUD-P100-05 | Assess all 5 entities for `BaseEntity` migration — **no-op, none are safely migratable** (rationale below) | platform.admin | done | 2026-08-17 |
| T-AUD-P100-06 | Refactor `SecurityConfiguration` to consume the platform-owned `SecurityProperties.publicPaths`, layered with the audit-service-specific paths | platform.admin | done | 2026-08-17 |
| T-AUD-P100-07 | Add `SecurityConfigurationTest` (6 tests) guarding public-path union + case-preserving authority mapping | platform.admin | done | 2026-08-17 |

**T-AUD-P100-05 — why the `BaseEntity` migration is a no-op here.** All
five entities are disqualified, and forcing any of them would break a
schema or compliance invariant:

| Entity | Table | Blocker |
|---|---|---|
| `AuditEvent` | `audit.events` | Composite `@IdClass` PK `(id, created_at)`, mandatory because the parent is `PARTITION BY RANGE (created_at)` — PostgreSQL requires the partition key in every UNIQUE constraint. `BaseEntity` declares a single `@Id`. |
| `AuditReadLog` | `audit.read_log` | Same partitioned-parent composite PK rule. |
| `LitigationHold` | `audit.litigation_hold` | Append-only; the `litigation_hold_immutable` trigger (V3) raises on UPDATE/DELETE, so `@Version` and `updatedAt`/`updatedBy` can never be written. |
| `InboxEvent` | `audit.inbox` | Natural PK is `event_id` (the dedup key); there is no surrogate `id` column to inherit. |
| `OutboxEvent` | `audit.outbox` | Phase B canonical contract; the column set is deliberately explicit and shared with the platform outbox schema. |

**T-AUD-P100-06 — two deliberate departures from the customer-service
pilot (`e744e1a`).** Both avoid concrete regressions:

1. **Authority casing is preserved.** All 11 `@PreAuthorize` expressions in
   `AuditController` / `AdminAuditController` assert lowercase dotted
   authorities (`ROLE_audit.read`, `ROLE_platform.super_admin`). The
   platform `JwtRoleConverter` emits `ROLE_<UPPER>`, which would rewrite
   `ROLE_audit.read` to `ROLE_AUDIT.READ` and make every audit endpoint
   return 403. The local converter is retained; adopting the platform
   converter requires a coordinated rewrite of all 11 expressions.
2. **The platform admin filter chain is not activated.** It matches
   `/admin/v1/**` and gates the subtree on
   `hasRole(platform.security.admin.min-role)` = `audit.admin`, but
   `AdminAuditController` intentionally admits `platform.admin` /
   `platform.super_admin` *without* `audit.admin` on
   `POST /admin/v1/audit/search` and `POST /admin/v1/audit/export`. A
   chain-level check would reject those callers before method security
   runs. Admin authorization stays per-endpoint.

CORS is also intentionally left unwired: this service had none before, and
opening cross-origin access to an immutable audit log is a security
decision that should be reviewed on its own rather than bundled into a DRY
refactor.

### Phase D — centralised partition maintenance (ADR-0029)

| Status Tracking ID | Description | Roles | Status | Last Updated |
|---|---|---|---|---|
| T-AUD-P100-08 | Delete local `PartitionMaintenanceJob.kt` + `PartitionMaintenanceJobTest.kt`; adopt `platform-spring-boot-partition` (landed with the platform module in the pilot commit) | platform.admin | done | 2026-08-17 |
| T-AUD-P100-09 | Bump `spring-boot-starter` `4.1.2` → `4.1.4` | platform.admin | done | 2026-08-17 |
| T-AUD-P100-10 | `V7__platform_partition_module_adoption.sql` — Flyway marker recording the cutover | platform.admin | done | 2026-08-17 |
| T-AUD-P100-11 | `application.yml`: add `platform.partition` block, pinning `retention-months: 84` | platform.admin | done | 2026-08-17 |

**T-AUD-P100-11 — the retention pin is a correctness requirement.** The
platform `dropExpiredPartitions` step calls the two-argument
`partman.drop_expired_partitions(parent, interval)` overload, which takes
no `retention_class_filter` and therefore cannot distinguish this
service's 7-year `financial` retention class from its 1-year `default`
class. Inheriting the platform default of `retention-months: 36` would
have dropped `audit.events` financial partitions four years early and
broken the SEC retention guarantee. Pinning 84 months (7 years) keeps the
coarse platform sweep safe; the finer per-class sweep remains with the
V5 `pg_cron` schedules, which are unchanged. Litigation holds continue to
be honoured — the two-argument overload still skips any child holding a
`litigation_hold = TRUE` row. Over-retention is recoverable; premature
deletion of audit data is not.

V5 itself is deliberately left untouched (its comments still name the
removed `PartitionMaintenanceJob`): the migration is already applied, so
editing even a comment would change its Flyway checksum and fail
validation on existing databases. The cutover is recorded in V7 instead.

**Verification:** `./gradlew test` → 43 tests, 1 failed. The single
failure is `AuditServiceApplicationTests.contextLoads()`, the same
pre-existing environmental failure recorded in Phase 9 (`Failed to
determine a suitable driver class` — no DataSource / Docker available for
Testcontainers). Test count rose 37 → 43 with the 6 new
`SecurityConfigurationTest` cases, all passing.
