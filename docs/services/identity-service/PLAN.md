# identity-service — Implementation Plan

**Domain:** Platform Foundation
**Tier:** 0 (position 2 of 21; `DEPLOYMENT_ORDER.md` §2)
**Technology:** Node/TS
**Criticality:** T0 (99.99%)
**DB Schema:** `identity`
**Cache:** Redis — session+token
**HPA:** CPU 70%, 3–8, p99 < 80ms

## Implementation Status

The Kotlin/Spring implementation in `apps/identity-service` now provides the
service-owned plan artifacts: Flyway schema and audit/outbox/inbox invariants,
versioned REST APIs, JWT/Keycloak authorization, Redis cache invalidation,
Kafka consumers and producer outbox, OpenAPI, Avro schema artifacts,
Testcontainers coverage, container image, Kubernetes Deployment/Service/HPA/PDB
and migration Job, plus Prometheus alert rules.

The following checklist items are environment operations and remain pending
until the platform operators run them against their managed infrastructure:
Schema Registry registration, Vault secret-policy/mount provisioning, Conductor
server worker registration, Grafana alert deployment, and the staging smoke
test. They are intentionally not represented as completed source-code work.

---

## Purpose

**Phase 1 — Platform Foundation.** This service is on the critical path; ship it before any consumer starts.

This PLAN.md is the source of truth for **how** `identity-service` is built. The 10-phase
backbone below mirrors the locked Phase 1-7 layout in `IMPLEMENTATION_PHASES.md`
plus the Phase 7 cross-cutting and Phase 7.5 Make-a-Deal addenda where this
service participates.

---

## Tasks

### Phase 1 — Database & Domain Model

| ID | Task | Status | Depends-On | Required Role(s) | Approver Role | Co-Signer Role | Break-Glass? |
|---|---|---|---|---|---|---|---|
| T-IDN-01 | Create schema `identity`: tables per `ERD.md` (partitioned by time/zone/hash per data shape) | pending | — | identity.admin | identity.admin | — | — |
| T-IDN-02 | Write Flyway/migrations (forward-only); install `pg_partman` if the parent table is time-partitioned | pending | T-IDN-01 | identity.admin | identity.admin | — | — |
| T-IDN-03 | Implement the aggregate root, immutability invariants, and append-only audit constraints | pending | T-IDN-02 | identity.admin | identity.admin | — | — |
| T-IDN-04 | Add `identity.outbox` and `identity.inbox` for reliable eventing | pending | T-IDN-03 | identity.outbox, identity.inbox | identity.outbox | — | — |
### Phase 2 — REST API

| ID | Task | Status | Depends-On | Required Role(s) | Approver Role | Co-Signer Role | Break-Glass? |
|---|---|---|---|---|---|---|---|
| T-IDN-01 | CRUD endpoints per `INTEGRATION.md` (versioned `/v1/...`) | pending | — | identity.admin | identity.admin | — | — |
| T-IDN-02 | Idempotency-Key middleware on every mutating route | pending | T-IDN-01 | identity.admin | identity.admin | — | — |
| T-IDN-03 | Pagination + filtering on every list endpoint | pending | T-IDN-02 | identity.admin | identity.admin | — | — |
| T-IDN-04 | Health endpoints: `/actuator/health`, `/ready`, `/started` | pending | T-IDN-03 | identity.admin | identity.admin | — | — |
### Phase 3 — Event Publishing

| ID | Task | Status | Depends-On | Required Role(s) | Approver Role | Co-Signer Role | Break-Glass? |
|---|---|---|---|---|---|---|---|
| T-IDN-01 | Transactional outbox + poller (200 ms interval, DLQ) | pending | — | identity.admin | identity.admin | — | — |
| T-IDN-02 | Publish events per the integration map below | pending | T-IDN-01 | identity.admin | identity.admin | — | — |
| T-IDN-03 | Avro schema registered in Schema Registry on first publish | pending | T-IDN-02 | identity.admin | identity.admin | — | — |
### Phase 4 — Event Consumption

| ID | Task | Status | Depends-On | Required Role(s) | Approver Role | Co-Signer Role | Break-Glass? |
|---|---|---|---|---|---|---|---|
| T-IDN-01 | Idempotent inbox; LSN/offset dedup window 7 days | pending | — | identity.admin | identity.admin | — | — |
| T-IDN-02 | Single consumer per partition; pause-on-error with backoff | pending | T-IDN-01 | identity.admin | identity.admin | — | — |
| T-IDN-03 | Dead-letter topic after N retries | pending | T-IDN-02 | identity.admin | identity.admin | — | — |
### Phase 5 — Caching

| ID | Task | Status | Depends-On | Required Role(s) | Approver Role | Co-Signer Role | Break-Glass? |
|---|---|---|---|---|---|---|---|
| T-IDN-01 | Redis — session+token | pending | — | identity.admin | identity.admin | — | — |
| T-IDN-02 | Push-invalidate on every write that affects the cache key | pending | T-IDN-01 | identity.admin | identity.admin | — | — |
| T-IDN-03 | Stampede protection on hot keys (single-flight) | pending | T-IDN-02 | identity.admin | identity.admin | — | — |
### Phase 6 — External Integrations

| ID | Task | Status | Depends-On | Required Role(s) | Approver Role | Co-Signer Role | Break-Glass? |
|---|---|---|---|---|---|---|---|
| T-IDN-01 | Sync dependencies: Keycloak | pending | — | identity.admin | identity.admin | — | — |
| T-IDN-02 | Circuit breakers on every outbound call (Resilience4j / polly) | pending | T-IDN-01 | identity.admin | identity.admin | — | — |
| T-IDN-03 | OAuth2 client credentials + mTLS for service-to-service | pending | T-IDN-02 | identity.admin | identity.admin | — | — |
| T-IDN-04 | HashiCorp Vault for DB credentials and signing keys | pending | T-IDN-03 | identity.admin | identity.admin | — | — |
### Phase 7 — Security

| ID | Task | Status | Depends-On | Required Role(s) | Approver Role | Co-Signer Role | Break-Glass? |
|---|---|---|---|---|---|---|---|
| T-IDN-01 | JWT bearer auth via Keycloak, realm `platform-internal` | pending | — | identity.admin | identity.admin | — | — |
| T-IDN-02 | Required scopes/roles per `INTEGRATION.md` | pending | T-IDN-01 | identity.admin | identity.admin | — | — |
| T-IDN-03 | `X-Audit-Reason` header required on admin mutations | pending | T-IDN-02 | identity.admin | identity.admin | — | — |
| T-IDN-04 | Field-level encryption for PII (driver license, payment method) | pending | T-IDN-03 | identity.admin | identity.admin | — | — |
### Phase 8 — Observability

| ID | Task | Status | Depends-On | Required Role(s) | Approver Role | Co-Signer Role | Break-Glass? |
|---|---|---|---|---|---|---|---|
| T-IDN-01 | Structured JSON logs with `correlation_id`, `user_id`, `tenant_id` | pending | — | identity.admin | identity.admin | — | — |
| T-IDN-02 | Metrics: RED per route + business counters specific to this service | pending | T-IDN-01 | identity.admin | identity.admin | — | — |
| T-IDN-03 | OpenTelemetry traces with child spans; long-poll spans open until response | pending | T-IDN-02 | identity.admin | identity.admin | — | — |
| T-IDN-04 | Alerts in Grafana: p99 latency, error rate, consumer lag | pending | T-IDN-03 | identity.admin | identity.admin | — | — |
### Phase 9 — Testing

| ID | Task | Status | Depends-On | Required Role(s) | Approver Role | Co-Signer Role | Break-Glass? |
|---|---|---|---|---|---|---|---|
| T-IDN-01 | Unit tests: 80%+ branch coverage on the aggregate | pending | — | identity.admin | identity.admin | — | — |
| T-IDN-02 | Integration tests: Testcontainers (PostgreSQL, Kafka, Redis) | pending | T-IDN-01 | identity.admin | identity.admin | — | — |
| T-IDN-03 | Contract tests: Producer Avro schemas pinned in CI | pending | T-IDN-02 | identity.admin | identity.admin | — | — |
| T-IDN-04 | E2E test per major user journey in `WORKFLOWS.md` | pending | T-IDN-03 | identity.admin | identity.admin | — | — |
### Phase 10 — Deployment

| ID | Task | Status | Depends-On | Required Role(s) | Approver Role | Co-Signer Role | Break-Glass? |
|---|---|---|---|---|---|---|---|
| T-IDN-01 | Kubernetes manifests: Deployment, Service, HPA, PDB | pending | — | identity.admin | identity.admin | — | — |
| T-IDN-02 | Pre-upgrade Job for migrations | pending | T-IDN-01 | identity.admin | identity.admin | — | — |
| T-IDN-03 | Resource limits per `DEPLOYMENT_ARCHITECTURE.md` | pending | T-IDN-02 | identity.admin | identity.admin | — | — |
| T-IDN-04 | Smoke test in staging before production rollout | pending | T-IDN-03 | identity.admin | identity.admin | — | — |
---

## Integration Map

### Sync Dependencies (outbound calls)
| Target | Endpoint | Purpose | Circuit Breaker |
|--------|----------|---------|----------------|
| `Keycloak` | per `INTEGRATION.md` | sync dependency | Yes |

### Events Published
| Event | Topic | Trigger | Key Consumers |
|-------|-------|---------|--------------|
| `identity.user.created` | derived from name | see INTEGRATION.md | see INTEGRATION.md |
| `identity.user.suspended` | derived from name | see INTEGRATION.md | see INTEGRATION.md |
| `identity.session.revoked` | derived from name | see INTEGRATION.md | see INTEGRATION.md |

### Events Consumed
| Event | Producer | Handler |
|-------|----------|---------|
| `customer.created` | see INTEGRATION.md | see INTEGRATION.md |
| `driver.created` | see INTEGRATION.md | see INTEGRATION.md |
| `courier.created` | see INTEGRATION.md | see INTEGRATION.md |
| `merchant.created` | see INTEGRATION.md | see INTEGRATION.md |
| `configuration.updated` | see INTEGRATION.md | see INTEGRATION.md |

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
| T-IDN-P76-01 | Register Conductor worker for `wf.onboarding.driver.v1` — Worker — identity_service_kyc_start + document_verify | pending | — | identity.admin | identity.admin | — | — |
| T-IDN-P76-02 | Register Conductor worker for `wf.onboarding.courier.v1` — Worker — identity_service_kyc_start + document_verify | pending | — | identity.admin | identity.admin | — | — |

### Phase 11 — Keycloak seeder hardening + Swagger defaults (closed 2026-08-14)

| ID | Task | Status | Depends-On | Required Role(s) | Approver Role | Co-Signer Role | Break-Glass? |
|---|---|---|---|---|---|---|---|
| T-IDN-P11-01 | `SeedRealmSpec`/`SeedSpec` declarative bean (single source of truth for realm graph + channel clients + dev users) | done (2026-08-14) | — | identity.admin | identity.admin | — | — |
| T-IDN-P11-02 | `KeycloakSeeder` extension: per-realm `platform-claims` client scope + protocol mappers (canonical claims from `KEYCLOAK_ARCHITECTURE.md`) + default-default client scopes + 7 per-realm dev users | done (2026-08-14) | T-IDN-P11-01 | identity.admin | identity.admin | — | — |
| T-IDN-P11-03 | `OpenApiConfiguration` augmentation: 1 `Server` URL (default realm) + N oauth2 `SecurityScheme` per channel client + N `tags` per realm | done (2026-08-14) | T-IDN-P11-01 | identity.admin | identity.admin | — | — |
| T-IDN-P11-04 | `application-dev.yml` flips `identity.keycloak.seed.enabled` default to `true`; `stg`/`prod` keep `false` with explicit default | done (2026-08-14) | T-IDN-P11-02 | identity.admin | identity.admin | — | — |
| T-IDN-P11-05 | `OpenApiConfigurationTest` unit (6 assertions, no Keycloak) + `KeycloakSeederIT` + `KeycloakSeederIdempotencyIT` (Testcontainers Keycloak, gated on `RUN_KEYCLOAK_IT=true`) | done (2026-08-14) | T-IDN-P11-02, T-IDN-P11-03 | identity.admin | identity.admin | — | — |

### Phase 12 — Per-service role + claim contract (closed 2026-08-14)

| ID | Task | Status | Depends-On | Required Role(s) | Approver Role | Co-Signer Role | Break-Glass? |
|---|---|---|---|---|---|---|---|
| T-IDN-P12-01 | `SeedServiceClaim` data class (`scopesClaim`/`levelClaim`/`tenantClaim`/`prefix`/`roleNames`) + `canonicalFor(service)` factory; 21 canonical entries in `SeedCatalog.serviceClaims` | done (2026-08-14) | T-IDN-P11-01 | identity.admin | identity.admin | — | — |
| T-IDN-P12-02 | `KeycloakSeeder` promotes `<prefix>.read/.write/.admin/.support` from client roles to **realm roles in `platform-services`** so protocol mappers can read them via `realm_access.roles` | done (2026-08-14) | T-IDN-P12-01 | identity.admin | identity.admin | — | — |
| T-IDN-P12-03 | `KeycloakSeeder` adds `service-claims` realm-level client scope on `platform-services` + 3 `oidc-script-based-property-mapper` mappers per service (scopes / level / tenant) | done (2026-08-14) | T-IDN-P12-01, T-IDN-P12-02 | identity.admin | identity.admin | — | — |
| T-IDN-P12-04 | `KeycloakSeeder` ensures each dev user gets a mirror in `platform-services` with their `serviceRoles` realm-role grants; super-admin gets all 21 `<service>.{read,write,admin,support}` | done (2026-08-14) | T-IDN-P12-02 | identity.admin | identity.admin | — | — |
| T-IDN-P12-05 | `OpenApiConfiguration` info description references the per-service claims; `OpenApiConfigurationTest` adds the documentation contract assertion; `KeycloakSeederIT` adds 4 runtime assertions (promoted realm roles, 63 protocol mappers, dev-user mirror, super-admin mirror) | done (2026-08-14) | T-IDN-P12-03, T-IDN-P12-04 | identity.admin | identity.admin | — | — |

### Phase 13 — Single-realm topology (env-driven, dev default) (closed 2026-08-14)

| ID | Task | Status | Depends-On | Required Role(s) | Approver Role | Co-Signer Role | Break-Glass? |
|---|---|---|---|---|---|---|---|
| T-IDN-P13-01 | `SeedTopologyProperties` `@ConfigurationProperties` bean with `topology` (default `single-realm`), `devRealmName` (default `platform-dev`), optional `adminRealmName` + `servicesRealmName` overrides; `effectiveServicesRealm()` / `effectiveAdminRealm()` resolve defaults per topology | done (2026-08-14) | T-IDN-P11-01 | identity.admin | identity.admin | — | — |
| T-IDN-P13-02 | `SeedCatalog` rewrites `realms` builder: `single-realm` collapses to one realm with the union of all 5 per-realm role sets + 21×5 per-service roles + 10 channel clients + `service-claims` scope; `multi-realm` preserves the 6-realm list verbatim; dev users get their `realm` rewritten to `devRealmName` in single mode | done (2026-08-14) | T-IDN-P13-01 | identity.admin | identity.admin | — | — |
| T-IDN-P13-03 | `KeycloakSeeder` removes the 7 hardcoded `"platform-services"` / `"platform-internal"` literals; reads `spec.servicesRealm` + `spec.adminRealm` for service clients, role grants, identity.read grant, super-admin mirror, dev-user mirror, service-claims scope; emits a boot INFO line with the active topology | done (2026-08-14) | T-IDN-P13-02 | identity.admin | identity.admin | — | — |
| T-IDN-P13-04 | `application-dev.yml` adds the 4 topology properties with `topology=single-realm` + `dev-realm-name=platform-dev` defaults; `application-stg.yml` + `application-prod.yml` set `topology=multi-realm` to preserve the documented 6-realm shape; `.env.example` documents `IDENTITY_KEYCLOAK_TOPOLOGY` / `_DEV_REALM_NAME` / `_SERVICES_REALM_NAME` / `_ADMIN_REALM_NAME` | done (2026-08-14) | T-IDN-P13-01 | identity.admin | identity.admin | — | — |
| T-IDN-P13-05 | Existing `KeycloakSeederIT` split into `KeycloakSeederMultiRealmIT` + `KeycloakSeederSingleRealmIT` (both gated `RUN_KEYCLOAK_IT=true`); `IdempotencyIT` forced multi-realm; `OpenApiConfigurationTest` adds single-realm assertion (server URL follows `identity.keycloak.default-realm`, 1 tag flagged default) | done (2026-08-14) | T-IDN-P13-03 | identity.admin | identity.admin | — | — |

### Phase 14 — Seeder token-expiry retry + runtime smoke (closed 2026-08-14)

| ID | Task | Status | Depends-On | Required Role(s) | Approver Role | Co-Signer Role | Break-Glass? |
|---|---|---|---|---|---|---|---|
| T-IDN-P14-01 | `KeycloakSeeder.withFreshClient { }` retry helper detects `401 invalid_token`, closes stale `Keycloak`, reopens + retries once. `isTokenExpired(WAE)` predicate covers status+body parsing. Reauth covers both the `NotFoundException` wrap (Keycloak's legacy 401→404 quirk) and direct `WebApplicationException`. No behavior change in success path. | done (2026-08-14) | T-IDN-P11-01 | identity.admin | identity.admin | — | — |
| T-IDN-P14-02 | `KeycloakSeederReauthTest` (under `apps/identity-service/src/test/.../integration/keycloak/`) covers the `isTokenExpired` predicate with 7 unit assertions: 401+invalid_token → true; 404 / 403 / 500 / 401+invalid_client / bare WAE → false. Uses Mockito to mock `Response` + `Response.StatusType` (must stub `statusInfo.family` or the WAE constructor NPEs on `getStatusInfo()`). Total: 7/7 pass. | done (2026-08-14) | T-IDN-P14-01 | identity.admin | identity.admin | — | — |
| T-IDN-P14-03 | Document dev-mode Keycloak operational note in `.env.example` (longer access-token TTL via `KC_SPI_ADMIN_AUTH_ACCESS_TOKEN_LIFESPAN=1800`). INTEGRATION.md §8.14 captures the reauth contract + operator guidance. | done (2026-08-14) | T-IDN-P14-01 | identity.admin | identity.admin | — | — |


---



## Hard service-to-service dependencies

This service's position in the canonical per-service deployment
order is **Tier 0, Position 2** per
[`../../DEPLOYMENT_ORDER.md`](../../DEPLOYMENT_ORDER.md).

| Class | Services |
|---|---|
| **Hard deps** (must be live and reachable before this service can complete its `/ready` health check) | [`configuration-service`](../configuration-service/README.md) (OIDC client config, locale defaults) |
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
| T-IDN-NN (Phase 1-10) | per task | per task | per task | per task |
| T-IDN-P76-NN | platform.admin + Conductor UI role | platform.admin | platform.super_admin | yes (workflow worker registration) |

For the canonical SUPER_ADMIN preset (1 × `platform.super_admin` + 20 × `<service>.admin`), see [`shared/TIME_BOUNDED_ALIASES.md`](../../shared/TIME_BOUNDED_ALIASES.md) for time-bounded aliases and `admin-service/INTEGRATION.md` 1.13 for the canonical role list.

## Phase 9 — Platform DRY (Tier 1) — 2026-08-17

This service was adopted into the
[`platform-spring-boot-starter:4.1.1`](../../../packages/platform-spring-boot/spring-boot-starter/)
umbrella (Phase 0 conformed per ADR-0024/0025/0026/0030/0031; see
[`docs/plans/PLATFORM_DRY_AUDIT.md`](../../plans/PLATFORM_DRY_AUDIT.md)). Pure
deletions of the 4 local-shadow classes; no functional behaviour change.

| Status Tracking ID | Description | Roles | Status | Last Updated |
|---|---|---|---|---|
| T-IDN-P90-01 | Delete `RequestCorrelationFilter.kt` — adopt platform UUIDv7 + MDC request_id (ADR-0030) | platform.admin | done | 2026-08-17 |
| T-IDN-P90-02 | Delete `JacksonConfiguration.kt` — adopt platform Jackson + `@ConditionalOnMissingBean` | platform.admin | done | 2026-08-17 |
| T-IDN-P90-03 | Delete `OpenApiConfiguration.kt` — adopt `platformOpenApi`; delete `OpenApiConfigurationTest.kt` (24 LOC) | platform.admin | done | 2026-08-17 |
| T-IDN-P90-04 | Delete `TestcontainersConfiguration.kt` — extend `BaseIntegrationTest` from platform-spring-boot-test across **8 IT classes** (`IdentityServiceApplicationTests`, `IdentitySchemaImmutabilityIT`, `KeycloakSeederSingleRealmIT`, `KeycloakSeederMultiRealmIT`, `KeycloakSeederIdempotencyIT`, `AdminRoleGrantIT`, `OidcDiscoveryE2EIT`, `OidcTokenE2EIT`) | platform.admin | done | 2026-08-17 |
| T-IDN-P90-05 | `application.yml`: add `platform.{observability,api-docs,audit,security}` property blocks | platform.admin | done | 2026-08-17 |
| T-IDN-P90-06 | Bump `com.trips-enjoy.platform:spring-boot-starter` from `4.1.0` → `4.1.1` | platform.admin | done | 2026-08-17 |
| T-IDN-P90-07 | `TestIdentityServiceApplication` drops `with(TestcontainersConfiguration::class)` | platform.admin | done | 2026-08-17 |

**Verification:** `./gradlew test` → 53 tests run, 17 skipped (Keycloak ITs gated on `RUN_KEYCLOAK_IT=true`), 23 unit tests pass cleanly across 5 suites
(`PartitionMaintenanceJobTest` 3/3, `IdentityApplicationServiceTest` 9/9, `KeycloakSeederResilienceTest` 2/2, `KeycloakSeederReauthTest` 7/7, `OidcDiscoveryRewriterTest` 2/2). 12 IT-class failures are pre-existing environmental
dependencies on a live PostgreSQL + Keycloak Testcontainer (`ApplicationContext failure threshold (1) exceeded`), identical to the pre-Phase-A baseline. To reproduce green locally: start the per-app DB + Keycloak per
[`docs/shared/PLATFORM_DRY_AUDIT.md` §0](../../shared/PLATFORM_DRY_AUDIT.md) and re-run.

**Phase 9 prepares, but does NOT land:** Phase B (OutboxEvent / InboxEvent / IdempotencyRecord canonicalisation), Phase C (ApiExceptionHandler + SecurityConfiguration + BaseEntity migration), or Phase D (partition cron + idempotency service + inbox listener). Those PRs follow in their own session once Phase 0/A is fully merged across all 14 Kotlin services.
