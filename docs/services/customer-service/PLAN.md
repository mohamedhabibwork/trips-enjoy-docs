# customer-service — Implementation Plan

**Domain:** Identity & User
**Tier:** 1 (position 9 of 21; `DEPLOYMENT_ORDER.md` §2)
**Technology:** Kotlin/Spring
**Criticality:** T1 (99.95%)
**DB Schema:** `customer`
**Cache:** Redis — customer + segment
**HPA:** CPU 60%, 2–5, p99 < 80ms

---

## Purpose

**Phase 2 — Core Business & Identity.** Start as soon as the Phase 1 services it depends on are ready.

This PLAN.md is the source of truth for **how** `customer-service` is built. The 10-phase
backbone below mirrors the locked Phase 1-7 layout in `IMPLEMENTATION_PHASES.md`
plus the Phase 7 cross-cutting and Phase 7.5 Make-a-Deal addenda where this
service participates.

---

## Tasks

### Phase 1 — Database & Domain Model

| ID | Task | Status | Depends-On | Required Role(s) | Approver Role | Co-Signer Role | Break-Glass? |
|---|---|---|---|---|---|---|---|
| T-CUS-01 | Create schema `customer`: tables per `ERD.md` (partitioned by time/zone/hash per data shape) | pending | — | customer.admin | customer.admin | — | — |
| T-CUS-02 | Write Flyway/migrations (forward-only); install `pg_partman` if the parent table is time-partitioned | pending | T-CUS-01 | customer.admin | customer.admin | — | — |
| T-CUS-03 | Implement the aggregate root, immutability invariants, and append-only audit constraints | pending | T-CUS-02 | customer.admin | customer.admin | — | — |
| T-CUS-04 | Add `customer.outbox` and `customer.inbox` for reliable eventing | pending | T-CUS-03 | customer.outbox, customer.inbox | customer.outbox | — | — |
### Phase 2 — REST API

| ID | Task | Status | Depends-On | Required Role(s) | Approver Role | Co-Signer Role | Break-Glass? |
|---|---|---|---|---|---|---|---|
| T-CUS-01 | CRUD endpoints per `INTEGRATION.md` (versioned `/v1/...`) | pending | — | customer.admin | customer.admin | — | — |
| T-CUS-02 | Idempotency-Key middleware on every mutating route | pending | T-CUS-01 | customer.admin | customer.admin | — | — |
| T-CUS-03 | Pagination + filtering on every list endpoint | pending | T-CUS-02 | customer.admin | customer.admin | — | — |
| T-CUS-04 | Health endpoints: `/actuator/health`, `/ready`, `/started` | pending | T-CUS-03 | customer.admin | customer.admin | — | — |
### Phase 3 — Event Publishing

| ID | Task | Status | Depends-On | Required Role(s) | Approver Role | Co-Signer Role | Break-Glass? |
|---|---|---|---|---|---|---|---|
| T-CUS-01 | Transactional outbox + poller (200 ms interval, DLQ) | pending | — | customer.admin | customer.admin | — | — |
| T-CUS-02 | Publish events per the integration map below | pending | T-CUS-01 | customer.admin | customer.admin | — | — |
| T-CUS-03 | Avro schema registered in Schema Registry on first publish | pending | T-CUS-02 | customer.admin | customer.admin | — | — |
### Phase 4 — Event Consumption

| ID | Task | Status | Depends-On | Required Role(s) | Approver Role | Co-Signer Role | Break-Glass? |
|---|---|---|---|---|---|---|---|
| T-CUS-01 | Idempotent inbox; LSN/offset dedup window 7 days | pending | — | customer.admin | customer.admin | — | — |
| T-CUS-02 | Single consumer per partition; pause-on-error with backoff | pending | T-CUS-01 | customer.admin | customer.admin | — | — |
| T-CUS-03 | Dead-letter topic after N retries | pending | T-CUS-02 | customer.admin | customer.admin | — | — |
### Phase 5 — Caching

| ID | Task | Status | Depends-On | Required Role(s) | Approver Role | Co-Signer Role | Break-Glass? |
|---|---|---|---|---|---|---|---|
| T-CUS-01 | Redis — customer + segment | pending | — | customer.admin | customer.admin | — | — |
| T-CUS-02 | Push-invalidate on every write that affects the cache key | pending | T-CUS-01 | customer.admin | customer.admin | — | — |
| T-CUS-03 | Stampede protection on hot keys (single-flight) | pending | T-CUS-02 | customer.admin | customer.admin | — | — |
### Phase 6 — External Integrations

| ID | Task | Status | Depends-On | Required Role(s) | Approver Role | Co-Signer Role | Break-Glass? |
|---|---|---|---|---|---|---|---|
| T-CUS-01 | Sync dependencies: identity-service, payment-service | pending | — | customer.admin | customer.admin | — | — |
| T-CUS-02 | Circuit breakers on every outbound call (Resilience4j / polly) | pending | T-CUS-01 | customer.admin | customer.admin | — | — |
| T-CUS-03 | OAuth2 client credentials + mTLS for service-to-service | pending | T-CUS-02 | customer.admin | customer.admin | — | — |
| T-CUS-04 | HashiCorp Vault for DB credentials and signing keys | pending | T-CUS-03 | customer.admin | customer.admin | — | — |
### Phase 7 — Security

| ID | Task | Status | Depends-On | Required Role(s) | Approver Role | Co-Signer Role | Break-Glass? |
|---|---|---|---|---|---|---|---|
| T-CUS-01 | JWT bearer auth via Keycloak, realm `platform-internal` | pending | — | customer.admin | customer.admin | — | — |
| T-CUS-02 | Required scopes/roles per `INTEGRATION.md` | pending | T-CUS-01 | customer.admin | customer.admin | — | — |
| T-CUS-03 | `X-Audit-Reason` header required on admin mutations | pending | T-CUS-02 | customer.admin | customer.admin | — | — |
| T-CUS-04 | Field-level encryption for PII (driver license, payment method) | pending | T-CUS-03 | customer.admin | customer.admin | — | — |
### Phase 8 — Observability

| ID | Task | Status | Depends-On | Required Role(s) | Approver Role | Co-Signer Role | Break-Glass? |
|---|---|---|---|---|---|---|---|
| T-CUS-01 | Structured JSON logs with `correlation_id`, `user_id`, `tenant_id` | pending | — | customer.admin | customer.admin | — | — |
| T-CUS-02 | Metrics: RED per route + business counters specific to this service | pending | T-CUS-01 | customer.admin | customer.admin | — | — |
| T-CUS-03 | OpenTelemetry traces with child spans; long-poll spans open until response | pending | T-CUS-02 | customer.admin | customer.admin | — | — |
| T-CUS-04 | Alerts in Grafana: p99 latency, error rate, consumer lag | pending | T-CUS-03 | customer.admin | customer.admin | — | — |
### Phase 9 — Testing

| ID | Task | Status | Depends-On | Required Role(s) | Approver Role | Co-Signer Role | Break-Glass? |
|---|---|---|---|---|---|---|---|
| T-CUS-01 | Unit tests: 80%+ branch coverage on the aggregate | pending | — | customer.admin | customer.admin | — | — |
| T-CUS-02 | Integration tests: Testcontainers (PostgreSQL, Kafka, Redis) | pending | T-CUS-01 | customer.admin | customer.admin | — | — |
| T-CUS-03 | Contract tests: Producer Avro schemas pinned in CI | pending | T-CUS-02 | customer.admin | customer.admin | — | — |
| T-CUS-04 | E2E test per major user journey in `WORKFLOWS.md` | pending | T-CUS-03 | customer.admin | customer.admin | — | — |
### Phase 10 — Deployment

| ID | Task | Status | Depends-On | Required Role(s) | Approver Role | Co-Signer Role | Break-Glass? |
|---|---|---|---|---|---|---|---|
| T-CUS-01 | Kubernetes manifests: Deployment, Service, HPA, PDB | pending | — | customer.admin | customer.admin | — | — |
| T-CUS-02 | Pre-upgrade Job for migrations | pending | T-CUS-01 | customer.admin | customer.admin | — | — |
| T-CUS-03 | Resource limits per `DEPLOYMENT_ARCHITECTURE.md` | pending | T-CUS-02 | customer.admin | customer.admin | — | — |
| T-CUS-04 | Smoke test in staging before production rollout | pending | T-CUS-03 | customer.admin | customer.admin | — | — |
### Phase 7.0 — Cross-cutting: Guaranteed Rewards & Rating-Based Pricing


| ID | Task | Status | Depends-On | Required Role(s) | Approver Role | Co-Signer Role | Break-Glass? |
|---|---|---|---|---|---|---|---|
**Customer credit mirror.** Expose credit balance on the customer read model.

---

## Integration Map

### Sync Dependencies (outbound calls)
| Target | Endpoint | Purpose | Circuit Breaker |
|--------|----------|---------|----------------|
| `identity-service` | per `INTEGRATION.md` | sync dependency | Yes |
| `payment-service` | per `INTEGRATION.md` | sync dependency | Yes |

### Events Published
| Event | Topic | Trigger | Key Consumers |
|-------|-------|---------|--------------|
| `customer.created` | derived from name | see INTEGRATION.md | see INTEGRATION.md |
| `customer.updated` | derived from name | see INTEGRATION.md | see INTEGRATION.md |
| `customer.suspended` | derived from name | see INTEGRATION.md | see INTEGRATION.md |

### Events Consumed
| Event | Producer | Handler |
|-------|----------|---------|
| `identity.user.created` | see INTEGRATION.md | see INTEGRATION.md |
| `payment.method.saved` | see INTEGRATION.md | see INTEGRATION.md |
| `trip.reward.granted.v1 (user-side)` | see INTEGRATION.md | see INTEGRATION.md |

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
| T-CUS-P76-01 | Register Conductor worker for `wf.refund.standard.v1` — Worker — customer-notification side-effect | pending | — | customer.admin | customer.admin | — | — |
| T-CUS-P76-02 | Register Conductor worker for `wf.refund.partial.v1` — Worker — customer-notification side-effect | pending | — | customer.admin | customer.admin | — | — |


---



## Hard service-to-service dependencies

This service's position in the canonical per-service deployment
order is **Tier 1, Position 9** per
[`../../DEPLOYMENT_ORDER.md`](../../DEPLOYMENT_ORDER.md).

| Class | Services |
|---|---|
| **Hard deps** (must be live and reachable before this service can complete its `/ready` health check) | [`identity-service`](../identity-service/README.md) (Keycloak user lookup at signup), [`configuration-service`](../configuration-service/README.md) (KYC config, loyalty tier config, preferred-locale default) |
| **Soft deps** (this service can start without them; runtime calls fail gracefully with circuit-breaker fallback until the dep is up) | [`notification-service`](../notification-service/README.md) (welcome email, KYC status push) |

**Deployment scenarios** (per [`../../DEPLOYMENT_ORDER.md` §4](../../DEPLOYMENT_ORDER.md)):

- **Greenfield** — tiers are deployed in order; intra-tier parallelism is allowed.
- **Single-service rollout** — rolling deploy with canary required for Tier 0 (`configuration-service`, `identity-service`, `api-gateway`); optional for Tier 1+; canary required for `chat-service` (Phase 7.7 cross-cutting).
- **Region failover / DR** — full Tier 0 → Tier 1 → Tier 2 → Tier 3 sequence is replayed.

For cross-cutting infra deps (PostgreSQL, Kafka, Redis, Keycloak, Vault, mTLS, OTel, S3) see [`../../DEPLOYMENT_ORDER.md` §3](../../DEPLOYMENT_ORDER.md).

## Role Mapping (back-reference)

This service's tasks map to platform roles per [`MASTER_TASK.md`](../../MASTER_TASK.md) 11 "Role Mapping (back-reference)". The columns `Required Role(s) | Approver Role | Co-Signer Role | Break-Glass?` added to every task table above come from that appendix.

| ID prefix | Required Role(s) | Approver Role | Co-Signer Role | Break-Glass? |
|---|---|---|---|---|
| T-CUS-NN (Phase 1-10) | per task | per task | per task | per task |
| T-CUS-P70-NN | platform.admin / pricing.admin / customer.admin | platform.admin | platform.super_admin | no (Phase 7.0 cross-cutting) |
| T-CUS-P75-NN | platform.admin / pricing.admin | platform.admin | platform.super_admin | no (Phase 7.5 Make-a-Deal) |
| T-CUS-P76-NN | platform.admin + Conductor UI role | platform.admin | platform.super_admin | yes (workflow worker registration) |

For the canonical SUPER_ADMIN preset (1 × `platform.super_admin` + 20 × `<service>.admin`), see [`shared/TIME_BOUNDED_ALIASES.md`](../../shared/TIME_BOUNDED_ALIASES.md) for time-bounded aliases and `admin-service/INTEGRATION.md` 1.13 for the canonical role list.

## Phase 9 — Platform DRY (Tier 1) — 2026-08-17

This service was adopted into the
[`platform-spring-boot-starter:4.1.1`](../../../packages/platform-spring-boot/spring-boot-starter/)
umbrella (Phase 0 conformed per ADR-0024/0025/0026/0030/0031; see
[`docs/plans/PLATFORM_DRY_AUDIT.md`](../../plans/PLATFORM_DRY_AUDIT.md)). Pure
deletions of the 5 local-shadow classes; no functional behaviour change.

| Status Tracking ID | Description | Roles | Status | Last Updated |
|---|---|---|---|---|
| T-CUS-P90-01 | Delete `RequestCorrelationFilter.kt` — adopt platform UUIDv7 + MDC request_id (ADR-0030) | platform.admin | done | 2026-08-17 |
| T-CUS-P90-02 | Delete `JacksonConfiguration.kt` — adopt platform Jackson + `@ConditionalOnMissingBean` | platform.admin | done | 2026-08-17 |
| T-CUS-P90-03 | Delete `MetricsConfiguration.kt` — adopt `platformMetricsCustomizer` | platform.admin | done | 2026-08-17 |
| T-CUS-P90-04 | Delete `OpenApiConfiguration.kt` — adopt `platformOpenApi` | platform.admin | done | 2026-08-17 |
| T-CUS-P90-05 | Delete `TestcontainersConfiguration.kt` — extend `BaseIntegrationTest` from platform-spring-boot-test | platform.admin | done | 2026-08-17 |
| T-CUS-P90-06 | `application.yml`: add `platform.{observability,api-docs,audit,security}` property blocks (replaces deleted `@Value` reads) | platform.admin | done | 2026-08-17 |
| T-CUS-P90-07 | Bump `com.trips-enjoy.platform:spring-boot-starter` from `4.1.0` → `4.1.1` | platform.admin | done | 2026-08-17 |
| T-CUS-P90-08 | Test wiring: `CustomerServiceApplicationTests` extends `BaseIntegrationTest` from `com.trips_enjoy.platform.test` | platform.admin | done | 2026-08-17 |
| T-CUS-P90-09 | Test wiring: `TestCustomerServiceApplication` drops `with(TestcontainersConfiguration::class)` | platform.admin | done | 2026-08-17 |

**Verification:** `./gradlew test` → 34/35 green. The single failure
(`CustomerServiceApplicationTests.contextLoads`) is a `DataSourceProperties`
`BeanCreationException` that requires a live PostgreSQL on `localhost:5432`
— pre-existing environmental dependency, unrelated to Phase A. To reproduce
green: start the per-app DB per
[`docs/shared/PLATFORM_DRY_AUDIT.md` §0](../../shared/PLATFORM_DRY_AUDIT.md)
and re-run.

**Phase 9 prepares, but does NOT land:** Phase B (OutboxEvent /
InboxEvent / IdempotencyRecord canonicalisation), Phase C (ApiExceptionHandler
+ SecurityConfiguration + BaseEntity migration), or Phase D (partition cron
+ idempotency service + inbox listener). Those PRs follow in their own
session once Phase 0/A is fully merged across all 14 Kotlin services.
