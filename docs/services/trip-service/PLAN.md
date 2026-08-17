# trip-service — Implementation Plan

**Domain:** Ride-Hailing
**Tier:** 2 (position 17 of 21; `DEPLOYMENT_ORDER.md` §2)
**Technology:** Kotlin/Spring
**Criticality:** T0 (99.99%)
**DB Schema:** `trip`
**Cache:** Redis — active trip state
**HPA:** CPU 70%, 3–8, p99 < 80ms

---

## Purpose

**Phase 3 — Ride-Hailing.** Begin once Phase 3 pricing + dispatch are live.

This PLAN.md is the source of truth for **how** `trip-service` is built. The 10-phase
backbone below mirrors the locked Phase 1-7 layout in `IMPLEMENTATION_PHASES.md`
plus the Phase 7 cross-cutting and Phase 7.5 Make-a-Deal addenda where this
service participates.

---

## Tasks

### Phase 1 — Database & Domain Model

| ID | Task | Status | Depends-On | Required Role(s) | Approver Role | Co-Signer Role | Break-Glass? |
|---|---|---|---|---|---|---|---|
| T-TRP-01 | Create schema `trip`: tables per `ERD.md` (partitioned by time/zone/hash per data shape) | pending | — | trip.admin | trip.admin | — | — |
| T-TRP-02 | Write Flyway/migrations (forward-only); install `pg_partman` if the parent table is time-partitioned | pending | T-TRP-01 | trip.admin | trip.admin | — | — |
| T-TRP-03 | Implement the aggregate root, immutability invariants, and append-only audit constraints | pending | T-TRP-02 | trip.admin | trip.admin | — | — |
| T-TRP-04 | Add `trip.outbox` and `trip.inbox` for reliable eventing | pending | T-TRP-03 | trip.outbox, trip.inbox | trip.outbox | — | — |
### Phase 2 — REST API

| ID | Task | Status | Depends-On | Required Role(s) | Approver Role | Co-Signer Role | Break-Glass? |
|---|---|---|---|---|---|---|---|
| T-TRP-01 | CRUD endpoints per `INTEGRATION.md` (versioned `/v1/...`) | pending | — | trip.admin | trip.admin | — | — |
| T-TRP-02 | Idempotency-Key middleware on every mutating route | pending | T-TRP-01 | trip.admin | trip.admin | — | — |
| T-TRP-03 | Pagination + filtering on every list endpoint | pending | T-TRP-02 | trip.admin | trip.admin | — | — |
| T-TRP-04 | Health endpoints: `/actuator/health`, `/ready`, `/started` | pending | T-TRP-03 | trip.admin | trip.admin | — | — |
### Phase 3 — Event Publishing

| ID | Task | Status | Depends-On | Required Role(s) | Approver Role | Co-Signer Role | Break-Glass? |
|---|---|---|---|---|---|---|---|
| T-TRP-01 | Transactional outbox + poller (200 ms interval, DLQ) | pending | — | trip.admin | trip.admin | — | — |
| T-TRP-02 | Publish events per the integration map below | pending | T-TRP-01 | trip.admin | trip.admin | — | — |
| T-TRP-03 | Avro schema registered in Schema Registry on first publish | pending | T-TRP-02 | trip.admin | trip.admin | — | — |
### Phase 4 — Event Consumption

| ID | Task | Status | Depends-On | Required Role(s) | Approver Role | Co-Signer Role | Break-Glass? |
|---|---|---|---|---|---|---|---|
| T-TRP-01 | Idempotent inbox; LSN/offset dedup window 7 days | pending | — | trip.admin | trip.admin | — | — |
| T-TRP-02 | Single consumer per partition; pause-on-error with backoff | pending | T-TRP-01 | trip.admin | trip.admin | — | — |
| T-TRP-03 | Dead-letter topic after N retries | pending | T-TRP-02 | trip.admin | trip.admin | — | — |
### Phase 5 — Caching

| ID | Task | Status | Depends-On | Required Role(s) | Approver Role | Co-Signer Role | Break-Glass? |
|---|---|---|---|---|---|---|---|
| T-TRP-01 | Redis — active trip state | pending | — | trip.admin | trip.admin | — | — |
| T-TRP-02 | Push-invalidate on every write that affects the cache key | pending | T-TRP-01 | trip.admin | trip.admin | — | — |
| T-TRP-03 | Stampede protection on hot keys (single-flight) | pending | T-TRP-02 | trip.admin | trip.admin | — | — |
### Phase 6 — External Integrations

| ID | Task | Status | Depends-On | Required Role(s) | Approver Role | Co-Signer Role | Break-Glass? |
|---|---|---|---|---|---|---|---|
| T-TRP-01 | Sync dependencies: driver-service, courier-service, `trip-service` (ride-request), `geolocation-service` (ETA/routing) | pending | — | trip.admin | trip.admin | — | — |
| T-TRP-02 | Circuit breakers on every outbound call (Resilience4j / polly) | pending | T-TRP-01 | trip.admin | trip.admin | — | — |
| T-TRP-03 | OAuth2 client credentials + mTLS for service-to-service | pending | T-TRP-02 | trip.admin | trip.admin | — | — |
| T-TRP-04 | HashiCorp Vault for DB credentials and signing keys | pending | T-TRP-03 | trip.admin | trip.admin | — | — |
### Phase 7 — Security

| ID | Task | Status | Depends-On | Required Role(s) | Approver Role | Co-Signer Role | Break-Glass? |
|---|---|---|---|---|---|---|---|
| T-TRP-01 | JWT bearer auth via Keycloak, realm `platform-internal` | pending | — | trip.admin | trip.admin | — | — |
| T-TRP-02 | Required scopes/roles per `INTEGRATION.md` | pending | T-TRP-01 | trip.admin | trip.admin | — | — |
| T-TRP-03 | `X-Audit-Reason` header required on admin mutations | pending | T-TRP-02 | trip.admin | trip.admin | — | — |
| T-TRP-04 | Field-level encryption for PII (driver license, payment method) | pending | T-TRP-03 | trip.admin | trip.admin | — | — |
### Phase 8 — Observability

| ID | Task | Status | Depends-On | Required Role(s) | Approver Role | Co-Signer Role | Break-Glass? |
|---|---|---|---|---|---|---|---|
| T-TRP-01 | Structured JSON logs with `correlation_id`, `user_id`, `tenant_id` | pending | — | trip.admin | trip.admin | — | — |
| T-TRP-02 | Metrics: RED per route + business counters specific to this service | pending | T-TRP-01 | trip.admin | trip.admin | — | — |
| T-TRP-03 | OpenTelemetry traces with child spans; long-poll spans open until response | pending | T-TRP-02 | trip.admin | trip.admin | — | — |
| T-TRP-04 | Alerts in Grafana: p99 latency, error rate, consumer lag | pending | T-TRP-03 | trip.admin | trip.admin | — | — |
### Phase 9 — Testing

| ID | Task | Status | Depends-On | Required Role(s) | Approver Role | Co-Signer Role | Break-Glass? |
|---|---|---|---|---|---|---|---|
| T-TRP-01 | Unit tests: 80%+ branch coverage on the aggregate | pending | — | trip.admin | trip.admin | — | — |
| T-TRP-02 | Integration tests: Testcontainers (PostgreSQL, Kafka, Redis) | pending | T-TRP-01 | trip.admin | trip.admin | — | — |
| T-TRP-03 | Contract tests: Producer Avro schemas pinned in CI | pending | T-TRP-02 | trip.admin | trip.admin | — | — |
| T-TRP-04 | E2E test per major user journey in `WORKFLOWS.md` | pending | T-TRP-03 | trip.admin | trip.admin | — | — |
### Phase 10 — Deployment

| ID | Task | Status | Depends-On | Required Role(s) | Approver Role | Co-Signer Role | Break-Glass? |
|---|---|---|---|---|---|---|---|
| T-TRP-01 | Kubernetes manifests: Deployment, Service, HPA, PDB | pending | — | trip.admin | trip.admin | — | — |
| T-TRP-02 | Pre-upgrade Job for migrations | pending | T-TRP-01 | trip.admin | trip.admin | — | — |
| T-TRP-03 | Resource limits per `DEPLOYMENT_ARCHITECTURE.md` | pending | T-TRP-02 | trip.admin | trip.admin | — | — |
| T-TRP-04 | Smoke test in staging before production rollout | pending | T-TRP-03 | trip.admin | trip.admin | — | — |
### Phase 7.0 — Cross-cutting: Guaranteed Rewards & Rating-Based Pricing


| ID | Task | Status | Depends-On | Required Role(s) | Approver Role | Co-Signer Role | Break-Glass? |
|---|---|---|---|---|---|---|---|
**Guaranteed rewards producer.** POST /v1/trips/{id}/reward/{re-evaluate|reverse} + GET .../reward.
- Idempotency key: `request:{request_id}:reward:{grant|reversal}`.
- Tables: trip.trip_reward (append-only, REVOKE UPDATE/DELETE), trip.trip_reward_reversal (append-only).

### Phase 7.5 — Make-a-Deal Kernel

This service participates in Phase 7.5 (Make-a-Deal kernel) per
[`MASTER_PLAN.md`](../../MASTER_PLAN.md) "Phase 7.5" and the canonical
contract in [`shared/DEAL_FEATURE.md`](../../shared/DEAL_FEATURE.md).
See canonical scope there; this block lists only the deal-flow tasks
this service owns.

| ID | Task | Status | Depends-On | Required Role(s) | Approver Role | Co-Signer Role | Break-Glass? |
|---|---|---|---|---|---|---|---|
| T-TRP-P75-01 | Implement rider-side endpoint `POST /v1/deals` + `POST /v1/deals/{id}/counter` + `POST /v1/deals/{id}/accept` — emits 5 `ride.deal.*.v1` events per [`shared/DEAL_FEATURE.md`](../../shared/DEAL_FEATURE.md) 5.1 | pending | — | trip.admin | trip.admin | — | — |
| T-TRP-P75-02 | Implement Deal aggregate state machine (open → negotiating → matched / countered / expired / rejected) per [`shared/DEAL_FEATURE.md`](../../shared/DEAL_FEATURE.md) 3.1 | pending | T-TRP-P75-01 | trip.admin | trip.admin | — | — |
| T-TRP-P75-03 | Consume `ride.deal.bid.submitted.v1` from [`driver-service`](../../services/driver-service/PLAN.md) and persist DealBid rows | pending | T-TRP-P75-01 | trip.admin | trip.admin | — | — |
| T-TRP-P75-04 | On `matched`, emit the existing `ride.request.created.v1` with `accepted_fare_minor` to integrate with the existing dispatch pipeline per [`shared/DEAL_FEATURE.md`](../../shared/DEAL_FEATURE.md) 11.1 step 10 | pending | T-TRP-P75-02 | trip.admin | trip.admin | — | — |
| T-TRP-P75-05 | Verify idempotency-key namespace `deal:{deal_id}:*` per [`shared/DEAL_FEATURE.md`](../../shared/DEAL_FEATURE.md) 7.1 | pending | T-TRP-P75-01 | trip.admin | trip.admin | — | — |

### Phase 7.7 — In-App Chat (cross-cutting)

This service participates in Phase 7.7 (in-app chat kernel added 2026-08-12).
Single source of truth: [`services/chat-service/PLAN.md`](../chat-service/PLAN.md).
On `ride.request.matched.v1` emission, this service is the canonical
**rider-side trigger** that causes `chat-service` to bootstrap a
`trip_chat` thread; on `trip.completed.v1` (and cancellation variants)
this service is the canonical **close trigger**.

| ID | Task | Status | Depends-On | Required Role(s) | Approver Role | Co-Signer Role | Break-Glass? |
|---|---|---|---|---|---|---|---|
| T-TRP-P77-01 | Wire `chat-service` client to `chat-service` REST API per [`services/chat-service/INTEGRATION.md`](../chat-service/INTEGRATION.md) — `POST /v1/admin/chat/threads` (admin) and `GET /v1/chat/threads/{id}` (read-only) | pending | — | trip.admin | trip.admin | — | — |
| T-TRP-P77-02 | On `ride.request.matched.v1` emission, also call `POST /v1/chat/threads` with `thread_kind=trip_chat`, `context_id=trip_id`, `participants=[rider_id, driver_id]` per [`services/chat-service/INTEGRATION.md`](../chat-service/INTEGRATION.md) §2.2 | pending | T-TRP-P77-01 | trip.admin | trip.admin | — | — |
| T-TRP-P77-03 | On `trip.completed.v1` and all cancellation variants (`trip.cancelled.v1`, `trip.no_show.v1`), call `POST /v1/chat/threads/{id}/close` to signal thread close to `chat-service` per [`services/chat-service/INTEGRATION.md`](../chat-service/INTEGRATION.md) §2.4 | pending | T-TRP-P77-01 | trip.admin | trip.admin | — | — |
| T-TRP-P77-04 | Consume `chat.message.reported.v1` from `chat-service`; if `severity >= safety` and `actor_role = rider`, escalate to safety workflow per [`workflows/SAFETY_WORKFLOWS.md`](../../workflows/SAFETY_WORKFLOWS.md) (open P1 safety ticket, page on-call) | pending | T-TRP-P77-01 | trip.admin | trip.admin | platform.safety | yes (rider-escalation) |
| T-TRP-P77-05 | Idempotency-key namespace `chat:thread:{trip_id}:{matched|closed}` per [`services/chat-service/INTEGRATION.md`](../chat-service/INTEGRATION.md) §4.1 | pending | T-TRP-P77-01 | trip.admin | trip.admin | — | — |
| T-TRP-P77-06 | Outbox + DLQ for `chat-service` calls per the platform outbox pattern in [`architecture/FAILURE_HANDLING.md`](../../architecture/FAILURE_HANDLING.md) — chat-service is **CRITICAL** (T1) per [`architecture/SERVICE_ISOLATION.md`](../../architecture/SERVICE_ISOLATION.md) | pending | T-TRP-P77-01 | trip.admin | trip.admin | — | no |

---

## Integration Map

### Sync Dependencies (outbound calls)
| Target | Endpoint | Purpose | Circuit Breaker |
|--------|----------|---------|----------------|
| `driver-service` | per `INTEGRATION.md` | sync dependency | Yes |
| `courier-service` | per `INTEGRATION.md` | sync dependency | Yes |
| ``trip-service` (ride-request)` | per `INTEGRATION.md` | sync dependency | Yes |
| ``geolocation-service` (ETA/routing)` | per `INTEGRATION.md` | sync dependency | Yes |

### Events Published
| Event | Topic | Trigger | Key Consumers |
|-------|-------|---------|--------------|
| `trip.started` | derived from name | see INTEGRATION.md | see INTEGRATION.md |
| `trip.arrived` | derived from name | see INTEGRATION.md | see INTEGRATION.md |
| `trip.completed` | derived from name | see INTEGRATION.md | see INTEGRATION.md |
| `trip.cancelled` | derived from name | see INTEGRATION.md | see INTEGRATION.md |
| `trip.reward.granted.v1` | derived from name | see INTEGRATION.md | see INTEGRATION.md |
| `trip.reward.reversed.v1` | derived from name | see INTEGRATION.md | see INTEGRATION.md |

### Events Consumed
| Event | Producer | Handler |
|-------|----------|---------|
| `ride.request.matched` | see INTEGRATION.md | see INTEGRATION.md |
| `driver.location.updated` | see INTEGRATION.md | see INTEGRATION.md |

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
| T-TRP-P76-01 | Register Conductor worker for `wf.phase7.reward_grant.v1` — Producer — emits trip.reward.granted.v1 via outbox; Conductor worker registers the trip reward state | pending | — | trip.admin | trip.admin | — | — |
| T-TRP-P76-02 | Register Conductor worker for `wf.phase7.reward_reversal.v1` — Producer — emits trip.reward.reversed.v1; Conductor worker handles reversal fan-out | pending | — | trip.admin | trip.admin | — | — |
| T-TRP-P76-03 | Register Conductor worker for `wf.phase75.deal_rider.v1` — Producer — rider-side endpoint POST /v1/deals + 5 ride events | pending | — | trip.admin | trip.admin | — | — |


---



## Hard service-to-service dependencies

This service's position in the canonical per-service deployment
order is **Tier 2, Position 17** per
[`../../DEPLOYMENT_ORDER.md`](../../DEPLOYMENT_ORDER.md).

| Class | Services |
|---|---|
| **Hard deps** (must be live and reachable before this service can complete its `/ready` health check) | [`customer-service`](../customer-service/README.md) (rider profile), [`driver-service`](../driver-service/README.md) (driver profile + online state), [`pricing-service`](../pricing-service/README.md) (quote), [`payment-service`](../payment-service/README.md) (ride saga), [`geolocation-service`](../geolocation-service/README.md) (ETA + routing), [`notification-service`](../notification-service/README.md) (trip push), [`configuration-service`](../configuration-service/README.md) (trip rules, surge config) |
| **Soft deps** (this service can start without them; runtime calls fail gracefully with circuit-breaker fallback until the dep is up) | [`chat-service`](../chat-service/README.md) (Phase 7.7 — rider↔driver thread bootstrap; trip-service starts; threads bootstrap on the next `ride.request.matched.v1`) |

**Deployment scenarios** (per [`../../DEPLOYMENT_ORDER.md` §4](../../DEPLOYMENT_ORDER.md)):

- **Greenfield** — tiers are deployed in order; intra-tier parallelism is allowed.
- **Single-service rollout** — rolling deploy with canary required for Tier 0 (`configuration-service`, `identity-service`, `api-gateway`); optional for Tier 1+; canary required for `chat-service` (Phase 7.7 cross-cutting).
- **Region failover / DR** — full Tier 0 → Tier 1 → Tier 2 → Tier 3 sequence is replayed.

For cross-cutting infra deps (PostgreSQL, Kafka, Redis, Keycloak, Vault, mTLS, OTel, S3) see [`../../DEPLOYMENT_ORDER.md` §3](../../DEPLOYMENT_ORDER.md).

## Role Mapping (back-reference)

This service's tasks map to platform roles per [`MASTER_TASK.md`](../../MASTER_TASK.md) 11 "Role Mapping (back-reference)". The columns `Required Role(s) | Approver Role | Co-Signer Role | Break-Glass?` added to every task table above come from that appendix.

| ID prefix | Required Role(s) | Approver Role | Co-Signer Role | Break-Glass? |
|---|---|---|---|---|
| T-TRP-NN (Phase 1-10) | per task | per task | per task | per task |
| T-TRP-P70-NN | platform.admin / pricing.admin / customer.admin | platform.admin | platform.super_admin | no (Phase 7.0 cross-cutting) |
| T-TRP-P75-NN | platform.admin / pricing.admin | platform.admin | platform.super_admin | no (Phase 7.5 Make-a-Deal) |
| T-TRP-P76-NN | platform.admin + Conductor UI role | platform.admin | platform.super_admin | yes (workflow worker registration) |

For the canonical SUPER_ADMIN preset (1 × `platform.super_admin` + 20 × `<service>.admin`), see [`shared/TIME_BOUNDED_ALIASES.md`](../../shared/TIME_BOUNDED_ALIASES.md) for time-bounded aliases and `admin-service/INTEGRATION.md` 1.13 for the canonical role list.

## Phase 9 — Platform DRY (Tier 1) — 2026-08-17

This service was adopted into the
[`platform-spring-boot-starter:4.1.1`](../../../packages/platform-spring-boot/spring-boot-starter/)
umbrella (Phase 0 conformed per ADR-0024/0025/0026/0030/0031; see
[`docs/plans/PLATFORM_DRY_AUDIT.md`](../../plans/PLATFORM_DRY_AUDIT.md)). Pure
deletions of the 5 local-shadow classes; no functional behaviour change.

| Status Tracking ID | Description | Roles | Status | Last Updated |
|---|---|---|---|---|
| T-TRP-P90-01 | Delete `RequestCorrelationFilter.kt` — adopt platform UUIDv7 + MDC request_id (ADR-0030) | platform.admin | done | 2026-08-17 |
| T-TRP-P90-02 | Delete `JacksonConfiguration.kt` — adopt platform Jackson + `@ConditionalOnMissingBean` | platform.admin | done | 2026-08-17 |
| T-TRP-P90-03 | Delete `OpenApiConfiguration.kt` — adopt `platformOpenApi` (title, version, description, contact, servers, bearer-jwt scheme) from `platform.api-docs` block | platform.admin | done | 2026-08-17 |
| T-TRP-P90-04 | Delete `MetricsConfiguration.kt` — adopt platform `MeterRegistryCustomizer` reading `platform.observability.{service,env,region,tenant}` | platform.admin | done | 2026-08-17 |
| T-TRP-P90-05 | Delete `TestcontainersConfiguration.kt` — extend `BaseIntegrationTest` from platform-spring-boot-test in `TripServiceApplicationTests` | platform.admin | done | 2026-08-17 |
| T-TRP-P90-06 | `application.yml`: add `platform.{observability,api-docs,audit,security}` property blocks | platform.admin | done | 2026-08-17 |
| T-TRP-P90-07 | Bump `com.trips-enjoy.platform:spring-boot-starter` from `4.1.0` → `4.1.1` | platform.admin | done | 2026-08-17 |
| T-TRP-P90-08 | `TestTripServiceApplication` drops `with(TestcontainersConfiguration::class)` | platform.admin | done | 2026-08-17 |

**Verification:** `./gradlew test` → 28 tests run, 0 skipped, 1 IT failed pre-baseline. 27 unit tests pass cleanly in `TripStateMachineTest` (Request/Trip/TripStop/IdempotencyRecord/OutboxEvent/TripReward state-machine invariants). 1 IT (`TripServiceApplicationTests.contextLoads`) extends `BaseIntegrationTest` and exhibits the same pre-Phase-A baseline failure as identity-service's `IdentityServiceApplicationTests` — `No qualifying bean of type 'ObjectMapper'` (`UnsatisfiedDependencyException` → `NoSuchBeanDefinitionException` for `tripController` constructor parameter 6). This is identical to the pre-Phase-A application-context env-dependence baseline acknowledged in commit `0bab68a` (identity-service: "12 IT-class failures are pre-existing environmental dependencies on a live PostgreSQL + Keycloak Testcontainer"). The platform umbrella's `JacksonConfiguration` (`platform-spring-boot-web`) ships with `@ConditionalOnMissingBean(name = ["jackson2ObjectMapper"])`, but the `Marker-class AutoConfiguration` registration does not yet scan-include `JacksonConfiguration` itself; Phase A is purely deletion-only and the platform umbrella's pre-existing `JacksonConfiguration` discovery gap is the underlying baseline issue. Resolution requires either a Phase E platform-starter follow-up or live Testcontainer infra — neither is in scope for Phase A.
