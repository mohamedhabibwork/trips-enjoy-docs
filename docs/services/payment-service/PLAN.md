# payment-service — Implementation Plan

**Domain:** Financial Core
**Tier:** 1 (position 16 of 21; `DEPLOYMENT_ORDER.md` §2)
**Technology:** Kotlin/Spring
**Criticality:** T0 (99.99%)
**DB Schema:** `payment`
**Cache:** Redis — idempotency + risk throttle
**HPA:** CPU 70%, 3–8, p99 < 200ms

---

## Purpose

**Phase 3.** Coordinate start with the platform team.

This PLAN.md is the source of truth for **how** `payment-service` is built. The 10-phase
backbone below mirrors the locked Phase 1-7 layout in `IMPLEMENTATION_PHASES.md`
plus the Phase 7 cross-cutting and Phase 7.5 Make-a-Deal addenda where this
service participates.

---

## Tasks

### Phase 1 — Database & Domain Model

| ID | Task | Status | Depends-On | Required Role(s) | Approver Role | Co-Signer Role | Break-Glass? |
|---|---|---|---|---|---|---|---|
| T-PAY-01 | Create schema `payment`: tables per `ERD.md` (partitioned by time/zone/hash per data shape) | pending | — | payment.admin | payment.admin | — | — |
| T-PAY-02 | Write Flyway/migrations (forward-only); install `pg_partman` if the parent table is time-partitioned | pending | T-PAY-01 | payment.admin | payment.admin | — | — |
| T-PAY-03 | Implement the aggregate root, immutability invariants, and append-only audit constraints | pending | T-PAY-02 | payment.admin | payment.admin | — | — |
| T-PAY-04 | Add `payment.outbox` and `payment.inbox` for reliable eventing | pending | T-PAY-03 | payment.outbox, payment.inbox | payment.outbox | — | — |
### Phase 2 — REST API

| ID | Task | Status | Depends-On | Required Role(s) | Approver Role | Co-Signer Role | Break-Glass? |
|---|---|---|---|---|---|---|---|
| T-PAY-01 | CRUD endpoints per `INTEGRATION.md` (versioned `/v1/...`) | pending | — | payment.admin | payment.admin | — | — |
| T-PAY-02 | Idempotency-Key middleware on every mutating route | pending | T-PAY-01 | payment.admin | payment.admin | — | — |
| T-PAY-03 | Pagination + filtering on every list endpoint | pending | T-PAY-02 | payment.admin | payment.admin | — | — |
| T-PAY-04 | Health endpoints: `/actuator/health`, `/ready`, `/started` | pending | T-PAY-03 | payment.admin | payment.admin | — | — |
### Phase 3 — Event Publishing

| ID | Task | Status | Depends-On | Required Role(s) | Approver Role | Co-Signer Role | Break-Glass? |
|---|---|---|---|---|---|---|---|
| T-PAY-01 | Transactional outbox + poller (200 ms interval, DLQ) | pending | — | payment.admin | payment.admin | — | — |
| T-PAY-02 | Publish events per the integration map below | pending | T-PAY-01 | payment.admin | payment.admin | — | — |
| T-PAY-03 | Avro schema registered in Schema Registry on first publish | pending | T-PAY-02 | payment.admin | payment.admin | — | — |
### Phase 4 — Event Consumption

| ID | Task | Status | Depends-On | Required Role(s) | Approver Role | Co-Signer Role | Break-Glass? |
|---|---|---|---|---|---|---|---|
| T-PAY-01 | Idempotent inbox; LSN/offset dedup window 7 days | pending | — | payment.admin | payment.admin | — | — |
| T-PAY-02 | Single consumer per partition; pause-on-error with backoff | pending | T-PAY-01 | payment.admin | payment.admin | — | — |
| T-PAY-03 | Dead-letter topic after N retries | pending | T-PAY-02 | payment.admin | payment.admin | — | — |
### Phase 5 — Caching

| ID | Task | Status | Depends-On | Required Role(s) | Approver Role | Co-Signer Role | Break-Glass? |
|---|---|---|---|---|---|---|---|
| T-PAY-01 | Redis — idempotency + risk throttle | pending | — | payment.admin | payment.admin | — | — |
| T-PAY-02 | Push-invalidate on every write that affects the cache key | pending | T-PAY-01 | payment.admin | payment.admin | — | — |
| T-PAY-03 | Stampede protection on hot keys (single-flight) | pending | T-PAY-02 | payment.admin | payment.admin | — | — |
### Phase 6 — External Integrations

| ID | Task | Status | Depends-On | Required Role(s) | Approver Role | Co-Signer Role | Break-Glass? |
|---|---|---|---|---|---|---|---|
| T-PAY-01 | Sync dependencies: Payment Provider | pending | — | payment.admin | payment.admin | — | — |
| T-PAY-02 | Circuit breakers on every outbound call (Resilience4j / polly) | pending | T-PAY-01 | payment.admin | payment.admin | — | — |
| T-PAY-03 | OAuth2 client credentials + mTLS for service-to-service | pending | T-PAY-02 | payment.admin | payment.admin | — | — |
| T-PAY-04 | HashiCorp Vault for DB credentials and signing keys | pending | T-PAY-03 | payment.admin | payment.admin | — | — |
### Phase 7 — Security

| ID | Task | Status | Depends-On | Required Role(s) | Approver Role | Co-Signer Role | Break-Glass? |
|---|---|---|---|---|---|---|---|
| T-PAY-01 | JWT bearer auth via Keycloak, realm `platform-internal` | pending | — | payment.admin | payment.admin | — | — |
| T-PAY-02 | Required scopes/roles per `INTEGRATION.md` | pending | T-PAY-01 | payment.admin | payment.admin | — | — |
| T-PAY-03 | `X-Audit-Reason` header required on admin mutations | pending | T-PAY-02 | payment.admin | payment.admin | — | — |
| T-PAY-04 | Field-level encryption for PII (driver license, payment method) | pending | T-PAY-03 | payment.admin | payment.admin | — | — |
### Phase 8 — Observability

| ID | Task | Status | Depends-On | Required Role(s) | Approver Role | Co-Signer Role | Break-Glass? |
|---|---|---|---|---|---|---|---|
| T-PAY-01 | Structured JSON logs with `correlation_id`, `user_id`, `tenant_id` | pending | — | payment.admin | payment.admin | — | — |
| T-PAY-02 | Metrics: RED per route + business counters specific to this service | pending | T-PAY-01 | payment.admin | payment.admin | — | — |
| T-PAY-03 | OpenTelemetry traces with child spans; long-poll spans open until response | pending | T-PAY-02 | payment.admin | payment.admin | — | — |
| T-PAY-04 | Alerts in Grafana: p99 latency, error rate, consumer lag | pending | T-PAY-03 | payment.admin | payment.admin | — | — |
### Phase 9 — Testing

| ID | Task | Status | Depends-On | Required Role(s) | Approver Role | Co-Signer Role | Break-Glass? |
|---|---|---|---|---|---|---|---|
| T-PAY-01 | Unit tests: 80%+ branch coverage on the aggregate | pending | — | payment.admin | payment.admin | — | — |
| T-PAY-02 | Integration tests: Testcontainers (PostgreSQL, Kafka, Redis) | pending | T-PAY-01 | payment.admin | payment.admin | — | — |
| T-PAY-03 | Contract tests: Producer Avro schemas pinned in CI | pending | T-PAY-02 | payment.admin | payment.admin | — | — |
| T-PAY-04 | E2E test per major user journey in `WORKFLOWS.md` | pending | T-PAY-03 | payment.admin | payment.admin | — | — |
### Phase 10 — Deployment

| ID | Task | Status | Depends-On | Required Role(s) | Approver Role | Co-Signer Role | Break-Glass? |
|---|---|---|---|---|---|---|---|
| T-PAY-01 | Kubernetes manifests: Deployment, Service, HPA, PDB | pending | — | payment.admin | payment.admin | — | — |
| T-PAY-02 | Pre-upgrade Job for migrations | pending | T-PAY-01 | payment.admin | payment.admin | — | — |
| T-PAY-03 | Resource limits per `DEPLOYMENT_ARCHITECTURE.md` | pending | T-PAY-02 | payment.admin | payment.admin | — | — |
| T-PAY-04 | Smoke test in staging before production rollout | pending | T-PAY-03 | payment.admin | payment.admin | — | — |
---

## Integration Map

### Sync Dependencies (outbound calls)
| Target | Endpoint | Purpose | Circuit Breaker |
|--------|----------|---------|----------------|
| `Payment Provider` | per `INTEGRATION.md` | sync dependency | Yes |

### Events Published
| Event | Topic | Trigger | Key Consumers |
|-------|-------|---------|--------------|
| `payment.attempted` | derived from name | see INTEGRATION.md | see INTEGRATION.md |
| `payment.authorized` | derived from name | see INTEGRATION.md | see INTEGRATION.md |
| `payment.captured` | derived from name | see INTEGRATION.md | see INTEGRATION.md |
| `payment.failed` | derived from name | see INTEGRATION.md | see INTEGRATION.md |
| `payment.refund.completed` | derived from name | see INTEGRATION.md | see INTEGRATION.md |

### Events Consumed
| Event | Producer | Handler |
|-------|----------|---------|
| `customer.suspended` | see INTEGRATION.md | see INTEGRATION.md |

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

### Phase 7.0 — Cross-cutting: Guaranteed Rewards & Rating-Based Pricing

This service participates in Phase 7 (cross-cutting) per
[`MASTER_PLAN.md`](../../MASTER_PLAN.md) "Phase 7 — Cross-cutting".
See canonical scope there; this block lists only the cross-cutting
tasks this service owns. Full audit history lives in
[`MASTER_TASK.md`](../../MASTER_TASK.md).

| ID | Task | Status | Depends-On | Required Role(s) | Approver Role | Co-Signer Role | Break-Glass? |
|---|---|---|---|---|---|---|---|
| T-PAY-P70-01 | Implement Phase 7.0 hooks per [MASTER_PLAN.md](../../MASTER_PLAN.md) Phase 7 table for this service | pending | — | payment.admin | payment.admin | — | — |
| T-PAY-P70-02 | Wire Kafka signal adapter → Conductor signal per [shared/CONDUCTOR_WORKFLOWS.md](../../shared/CONDUCTOR_WORKFLOWS.md) 6 | pending | T-PAY-P70-01 | payment.admin | payment.admin | — | — |
| T-PAY-P70-03 | Verify idempotency-key namespace matches the per-flow convention in [shared/CONDUCTOR_WORKFLOWS.md](../../shared/CONDUCTOR_WORKFLOWS.md) 4 | pending | T-PAY-P70-02 | payment.admin | payment.admin | — | — |

### Phase 7.6 — Conductor Workers

This service runs Conductor workers for the following workflows per
[ADR-0018](../../architecture/adrs/0018-workflow-engine-conductor.md)
and [`shared/CONDUCTOR_WORKFLOWS.md`](../../shared/CONDUCTOR_WORKFLOWS.md).
The full worker contract (task names, idempotency-key namespaces,
Kafka signal mapping, compensation responsibilities) is in
[`INTEGRATION.md`](./INTEGRATION.md) "Conductor Workers".

| ID | Task | Status | Depends-On | Required Role(s) | Approver Role | Co-Signer Role | Break-Glass? |
|---|---|---|---|---|---|---|---|
| T-PAY-P76-01 | Register Conductor worker for `wf.refund.standard.v1` — Orchestrator + capture_reversal worker | pending | — | payment.admin | payment.admin | — | — |
| T-PAY-P76-02 | Register Conductor worker for `wf.refund.partial.v1` — Orchestrator + capture_reversal worker | pending | — | payment.admin | payment.admin | — | — |
| T-PAY-P76-03 | Register Conductor worker for `wf.refund.food_reject.v1` — Orchestrator + capture_reversal worker | pending | — | payment.admin | payment.admin | — | — |
| T-PAY-P76-04 | Register Conductor worker for `wf.refund.cancellation.v1` — Orchestrator + capture_reversal worker | pending | — | payment.admin | payment.admin | — | — |
| T-PAY-P76-05 | Register Conductor worker for `wf.refund.dispute.v1` — Orchestrator + capture_reversal + chargeback worker | pending | — | payment.admin | payment.admin | — | — |
| T-PAY-P76-06 | Register Conductor worker for `wf.refund.cod_failed.v1` — Orchestrator + capture_reversal worker | pending | — | payment.admin | payment.admin | — | — |


---



## Hard service-to-service dependencies

This service's position in the canonical per-service deployment
order is **Tier 1, Position 16** per
[`../../DEPLOYMENT_ORDER.md`](../../DEPLOYMENT_ORDER.md).

| Class | Services |
|---|---|
| **Hard deps** (must be live and reachable before this service can complete its `/ready` health check) | [`configuration-service`](../configuration-service/README.md) (gateway registry, idempotency config), [`ledger-service`](../ledger-service/README.md) (postings — every capture + refund is a balanced double-entry posting), [`pricing-service`](../pricing-service/README.md) (tax line items) |
| **Soft deps** (this service can start without them; runtime calls fail gracefully with circuit-breaker fallback until the dep is up) | [`customer-service`](../customer-service/README.md) (wallet owner lookup), [`restaurant-service`](../restaurant-service/README.md) (merchant settlement) |

**Deployment scenarios** (per [`../../DEPLOYMENT_ORDER.md` §4](../../DEPLOYMENT_ORDER.md)):

- **Greenfield** — tiers are deployed in order; intra-tier parallelism is allowed.
- **Single-service rollout** — rolling deploy with canary required for Tier 0 (`configuration-service`, `identity-service`, `api-gateway`); optional for Tier 1+; canary required for `chat-service` (Phase 7.7 cross-cutting).
- **Region failover / DR** — full Tier 0 → Tier 1 → Tier 2 → Tier 3 sequence is replayed.

For cross-cutting infra deps (PostgreSQL, Kafka, Redis, Keycloak, Vault, mTLS, OTel, S3) see [`../../DEPLOYMENT_ORDER.md` §3](../../DEPLOYMENT_ORDER.md).

## Role Mapping (back-reference)

This service's tasks map to platform roles per [`MASTER_TASK.md`](../../MASTER_TASK.md) 11 "Role Mapping (back-reference)". The columns `Required Role(s) | Approver Role | Co-Signer Role | Break-Glass?` added to every task table above come from that appendix.

| ID prefix | Required Role(s) | Approver Role | Co-Signer Role | Break-Glass? |
|---|---|---|---|---|
| T-PAY-NN (Phase 1-10) | per task | per task | per task | per task |
| T-PAY-P70-NN | platform.admin / pricing.admin / customer.admin | platform.admin | platform.super_admin | no (Phase 7.0 cross-cutting) |
| T-PAY-P75-NN | platform.admin / pricing.admin | platform.admin | platform.super_admin | no (Phase 7.5 Make-a-Deal) |
| T-PAY-P76-NN | platform.admin + Conductor UI role | platform.admin | platform.super_admin | yes (workflow worker registration) |

For the canonical SUPER_ADMIN preset (1 × `platform.super_admin` + 20 × `<service>.admin`), see [`shared/TIME_BOUNDED_ALIASES.md`](../../shared/TIME_BOUNDED_ALIASES.md) for time-bounded aliases and `admin-service/INTEGRATION.md` 1.13 for the canonical role list.

## Phase 9 — Platform DRY (Tier 1) — 2026-08-17

This service was adopted into the
[`platform-spring-boot-starter:4.1.1`](../../../packages/platform-spring-boot/spring-boot-starter/)
umbrella (Phase 0 conformed per ADR-0024/0025/0026/0030/0031; see
[`docs/plans/PLATFORM_DRY_AUDIT.md`](../../plans/PLATFORM_DRY_AUDIT.md)). Pure
deletions of the 5 local-shadow classes (~220 LOC); no functional behaviour change.

| Status Tracking ID | Description | Roles | Status | Last Updated |
|---|---|---|---|---|
| T-PAY-P90-01 | Delete `RequestCorrelationFilter.kt` (48 LOC) — adopt platform UUIDv7 + MDC request_id (ADR-0030) | platform.admin | done | 2026-08-17 |
| T-PAY-P90-02 | Delete `JacksonConfiguration.kt` (29 LOC) — adopt platform Jackson + `@ConditionalOnMissingBean` | platform.admin | done | 2026-08-17 |
| T-PAY-P90-03 | Delete `MetricsConfiguration.kt` (38 LOC) — adopt `platformMetricsCustomizer` | platform.admin | done | 2026-08-17 |
| T-PAY-P90-04 | Delete `OpenApiConfiguration.kt` (73 LOC) — adopt `platformOpenApi` | platform.admin | done | 2026-08-17 |
| T-PAY-P90-05 | Delete `TestcontainersConfiguration.kt` (32 LOC) — extend `BaseIntegrationTest` from platform-spring-boot-test | platform.admin | done | 2026-08-17 |
| T-PAY-P90-06 | `application.yml`: add `platform.{observability,api-docs,audit,security}` property blocks (replaces deleted `@Value` reads) | platform.admin | done | 2026-08-17 |
| T-PAY-P90-07 | Bump `com.trips-enjoy.platform:spring-boot-starter` from `4.1.0` → `4.1.1` | platform.admin | done | 2026-08-17 |
| T-PAY-P90-08 | Test wiring: `PaymentServiceApplicationTests` extends `BaseIntegrationTest` from `com.trips_enjoy.platform.test` | platform.admin | done | 2026-08-17 |
| T-PAY-P90-09 | Test wiring: `TestPaymentServiceApplication` drops `with(TestcontainersConfiguration::class)` | platform.admin | done | 2026-08-17 |

**Verification:** `./gradlew test` → 74 tests, 0 skipped, 0 failures, 0 errors
across 6 suites (`IdempotencyTest` 7/7, `PaymentIntentStateMachineTest` 15/15,
`WalletBalanceInvariantTest` 16/16, `GatewayDriverTest` 24/24,
`EarningsAndSettlementTest` 11/11, `PaymentServiceApplicationTests.contextLoads`
1/1 — contextLoads passed in this environment against the live PostgreSQL +
Kafka Testcontainer started by `BaseIntegrationTest`'s Spring context).

**Phase 9 prepares, but does NOT land:** Phase B (OutboxEvent /
InboxEvent / IdempotencyRecord canonicalisation), Phase C (ApiExceptionHandler
+ SecurityConfiguration + BaseEntity migration), or Phase D (partition cron
+ idempotency service + inbox listener). Those PRs follow in their own
session once Phase 0/A is fully merged across all 14 Kotlin services.
