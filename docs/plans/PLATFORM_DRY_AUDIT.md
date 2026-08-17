# Platform DRY Refactor — Phased Plan

> **Created:** 2026-08-15
> **Companion to:** [`PLATFORM_DRY_AUDIT.md`](../shared/PLATFORM_DRY_AUDIT.md) (the audit findings, file paths, and drift inventory this plan executes against)
> **Cadence:** one PR per app per phase, explicit checkpoints between phases (per the established `service-by-service-graduate-with-checkpoints` feedback pattern)
> **Total wall-clock estimate:** 5–6 weeks (single contributor, sequential) or **3 weeks** (two contributors, parallel A–C + E)

## 0. How to read this plan

Every phase is sized to one work-week or less for a single contributor, with an explicit "exit criteria" before moving on. The phases are ordered so each one **unlocks the next**: Phase A removes pure duplication; Phase B aligns entities; Phase C aligns behavior; Phase D adds new platform modules; Phase E repeats the pattern for Go; Phase F for Python.

Per the established feedback pattern, no batched or "best-effort" runs. Every PR is buildable on its own. Every checkpoint verifies the previous phase didn't break tests, observability, or contracts.

| Phase | Duration | LOC removed | Apps touched | Risk | Blocking prerequisites |
|---|---|---|---|---|---|
| A — Tier 1 pure deletion | 2 days | ~4,000 | 14 Kotlin | Low | ADR-0026, ADR-0027 |
| B — Tier 2 shared entities | 1 week | ~1,900 | 9 Kotlin | Medium | ADR-0020, ADR-0023, ADR-0024 |
| C — Tier 3 domain migration | 1 week | ~3,800 | 14 Kotlin | Medium | ADR-0021, ADR-0022 |
| D — Tier 4 new modules | 2 weeks | ~1,750 | 14 Kotlin | Medium-High | Phases A–C complete |
| E — Go | 1 week | ~2,400 | 4 Go | Medium | Phase A complete (Kotlin request_id v7 + MDC settled) |
| F — Python | 3 days | ~250 | 1 Python | Low | Phase C complete (RFC 7807 shape settled) |

## Phase A — Tier 1 pure deletion (2 days)

**Goal:** delete every duplicate that the platform already provides, with no behavior change beyond the contract-drift items in §6 of the audit.

**ADR prerequisites (file before Phase A begins):**
- **ADR-0026** — `request_id` mint: UUIDv4 + request attribute (apps) → UUIDv7 + MDC (platform)
- **ADR-0027** — Testcontainers image tags: `apache/kafka-native:latest` (apps) → `confluentinc/cp-kafka:7.5.0` (platform)

### A.1 Pilot PR (customer-service)

Single-app PR that proves the pattern. customer-service has 35+ tests across 9 suites; if those stay green, the deletion is safe to apply to the other 13 apps.

Files to delete from `apps/customer-service/`:

```
src/main/kotlin/com/trips_enjoy/customer/config/RequestCorrelationFilter.kt
src/main/kotlin/com/trips_enjoy/customer/config/JacksonConfiguration.kt
src/main/kotlin/com/trips_enjoy/customer/config/MetricsConfiguration.kt
src/main/kotlin/com/trips_enjoy/customer/config/OpenApiConfiguration.kt
src/test/kotlin/com/trips_enjoy/customer/TestcontainersConfiguration.kt
```

Files to update in `apps/customer-service/src/main/resources/application.yml`:

```yaml
platform:
  observability:
    service: customer-service          # was hardcoded as a @Value
    env: ${ENV:dev}
    region: ${REGION:local}
  api-docs:
    title: "Trips Enjoy Customer Service API"
    version: "1.0.0"
    description: "Customer domain — profiles, addresses, loyalty, BFF wrappers"
    contact-name: "Platform Team"
    contact-email: "platform@trips-enjoy.com"
```

**Exit criteria for A.1:**
- `cd apps/customer-service && ./gradlew test` green
- `cd apps/customer-service && ./gradlew integrationTest` green (gated by `RUN_INTEGRATION=1`)
- `make build-spring` 15/15 green
- Audit emitted a UUIDv7 correlation_id on every endpoint (verify with `curl /v1/customers | jq .correlationId` — should be UUIDv7, not UUIDv4)

### A.2 Fan-out PRs (13 apps)

13 separate PRs — one per Kotlin app. Each follows the A.1 pattern:

| App | Files deleted | LOC removed (approx) |
|---|---|---|
| `audit-service` | RequestCorrelationFilter, JacksonConfiguration, MetricsConfiguration, OpenApiConfiguration, TestcontainersConfiguration | ~140 |
| `configuration-service` | same + TestcontainersConfiguration | ~140 |
| `driver-service` | RequestCorrelationFilter, JacksonConfiguration, MetricsConfiguration, OpenApiConfiguration, TestcontainersConfiguration | ~140 |
| `fraud-risk-service` | same | ~140 |
| `identity-service` | RequestCorrelationFilter, JacksonConfiguration, OpenApiConfiguration, TestcontainersConfiguration | ~140 |
| `ledger-service` | RequestCorrelationFilter, JacksonConfiguration, OpenApiConfiguration, TestcontainersConfiguration | ~115 |
| `notification-service` | RequestCorrelationFilter, JacksonConfiguration, MetricsConfiguration, OpenApiConfiguration, KafkaProducerConfiguration, TestcontainersConfiguration | ~170 |
| `payment-service` | RequestCorrelationFilter, JacksonConfiguration, MetricsConfiguration, OpenApiConfiguration, KafkaProducerConfiguration, TestcontainersConfiguration | ~170 |
| `restaurant-service` | RequestCorrelationFilter, JacksonConfiguration, MetricsConfiguration, OpenApiConfiguration, TestcontainersConfiguration | ~140 |
| `trip-service` | same | ~140 |
| `food-order-service` | same | ~140 |
| `search-service` | same | ~140 |
| `courier-service` | same | ~140 |
| `pricing-service` | same | ~140 |

Plus `apps/courier-service/`, `apps/pricing-service/`, `apps/food-order-service/`, `apps/search-service/`, `apps/trip-service/`, `apps/restaurant-service/`, `apps/fraud-risk-service/` (Go app correction: those are Kotlin per `build.gradle.kts`).

**Special handling per app:**

- `identity-service`: the local `OpenApiConfiguration` wires an OAuth2 flow that other apps don't have. Before deletion, lift that flow into the platform `OpenApiConfiguration` behind a `platform.api-docs.oauth2.enabled=true` flag.
- `notification-service`: ships a custom `KafkaProducerConfiguration` because it uses `KafkaTemplate<String, NotificationEvent>` (typed). Delete the file and rely on Spring Boot auto-config + a `@Bean KafkaTemplate<String, String>` only if absolutely needed.
- `payment-service`: ships a custom `OpenApiConfiguration` with the `ApiProblem` envelope instead of `ProblemDetail`. Replace with platform `OpenApiConfiguration` after ADR-0022 lands.

**Checkpoint after A.2:**
- `make build-spring` 15/15 green
- All 13 apps have a single RequestCorrelationFilter (the platform one)
- All 13 apps emit UUIDv7 correlation_ids
- Test suite uses `confluentinc/cp-kafka:7.5.0` image
- ~4,000 LOC removed

### A.3 Phase A exit criteria

- [ ] 0 duplicate `RequestCorrelationFilter.kt` files remain in `apps/*/src/main/kotlin/**/config/`
- [ ] 0 duplicate `JacksonConfiguration.kt` files remain
- [ ] 0 duplicate `MetricsConfiguration.kt` files remain
- [ ] 0 duplicate `OpenApiConfiguration.kt` files remain
- [ ] 0 duplicate `TestcontainersConfiguration.kt` files remain
- [ ] All 15 Kotlin services compile + test green
- [ ] ADR-0026 + ADR-0027 merged to `docs/architecture/adrs/`
- [ ] Per-service STATUS.md updated: §4 Implementation Notes gain a line "Phase A — adopted platform-spring-boot starter for request_id, jackson, metrics, openapi, testcontainers"
- [ ] Per-service PLAN.md gains a `Phase 9 — Platform DRY (Tier 1)` section (append-only)

## Phase B — Tier 2 shared entities (1 week)

**Goal:** align the Outbox / Inbox / Idempotency table shapes across the apps that have them.

**ADR prerequisites (file before Phase B begins):**
- **ADR-0020** — DLQ topic naming: `<topic>.dlq` (apps, 8/8) vs `<topic>.DLQ.v1` (platform)
- **ADR-0023** — Idempotency schema: canonical `(actor_id, idempotency_key)`
- **ADR-0024** — OutboxEvent schema: canonical columns `id, event_id, topic, partition_key, payload, headers, created_at, published_at, attempts, last_error, next_attempt_at`

### B.1 OutboxEvent canonicalization (3 days)

**Pilot app:** `audit-service` (smallest outbox payload).

`apps/audit-service/src/main/resources/db/migration/V_NN__adopt_platform_outbox.sql`:

```sql
-- Preserve existing data; switch to canonical column shape.
ALTER TABLE audit.outbox ADD COLUMN partition_key TEXT;
ALTER TABLE audit.outbox ADD COLUMN headers JSONB DEFAULT '{}'::JSONB;
ALTER TABLE audit.outbox ADD COLUMN next_attempt_at TIMESTAMPTZ;
ALTER TABLE audit.outbox ADD COLUMN last_error TEXT;
UPDATE audit.outbox SET partition_key = COALESCE(partition_key, event_id::TEXT);
UPDATE audit.outbox SET headers = COALESCE(headers, '{}'::JSONB);
UPDATE audit.outbox SET next_attempt_at = COALESCE(next_attempt_at, created_at);
ALTER TABLE audit.outbox ALTER COLUMN partition_key SET NOT NULL;
ALTER TABLE audit.outbox ALTER COLUMN next_attempt_at SET NOT NULL;
ALTER TABLE audit.outbox ALTER COLUMN created_at SET NOT NULL;
ALTER TABLE audit.outbox ALTER COLUMN event_id SET NOT NULL;
```

Delete `apps/audit-service/src/main/kotlin/com/trips_enjoy/audit/domain/OutboxEvent.kt` and replace with:

```kotlin
import com.trips_enjoy.platform.messaging.OutboxEvent as PlatformOutboxEvent
import com.trips_enjoy.platform.messaging.OutboxRepository

@Entity
@Table(name = "outbox", schema = "audit")
class OutboxEvent(...) : PlatformOutboxEvent()
```

Or — preferred — annotate the platform entity with `@Table(name = "outbox", schema = "audit")` via a service-local subclass that overrides only the table mapping.

### B.2 Fan-out (6 apps)

| App | Migrations added | Files deleted | LOC removed |
|---|---|---|---|
| `audit-service` | V_NN__adopt_platform_outbox.sql | OutboxEvent.kt, OutboxPublisher.kt | ~170 |
| `configuration-service` | V_NN__adopt_platform_outbox.sql | same | ~170 |
| `customer-service` | V_NN__adopt_platform_outbox.sql | same | ~170 |
| `ledger-service` | V_NN__adopt_platform_outbox.sql | same | ~170 |
| `notification-service` | V_NN__adopt_platform_outbox.sql | same | ~170 |
| `payment-service` | V_NN__adopt_platform_outbox.sql | same + InboxEvent.kt + IdempotencyKey.kt | ~250 |

### B.3 InboxEvent + Idempotency canonicalization (3 days)

Same pattern: write a V_NN migration per app that adds the canonical columns, copies the data over, drops the local entity, imports the platform one.

### B.4 Phase B exit criteria

- [ ] 6 apps use `com.trips_enjoy.platform.messaging.OutboxEvent`
- [ ] 7 apps use `com.trips_enjoy.platform.messaging.InboxEvent`
- [ ] 5 apps use `com.trips_enjoy.platform.data.IdempotencyRecord`
- [ ] No data loss: all existing audit event_id values preserved (verify via `SELECT count(*) FROM <schema>.outbox` pre + post migration)
- [ ] `make build-spring` 15/15 green
- [ ] Smoke test: publish one event from each app, consume from each downstream, verify dedup works
- [ ] ADR-0020 + ADR-0023 + ADR-0024 merged
- [ ] Per-service STATUS.md updated; per-service PLAN.md gains `Phase 9 — Tier 2` section

## Phase C — Tier 3 domain migration (1 week)

**Goal:** migrate all Kotlin domain entities to extend `com.trips_enjoy.platform.data.BaseEntity`; delete duplicate `ApiExceptionHandler` + `SecurityConfiguration` files.

**ADR prerequisites:**
- **ADR-0021** — Role claim shape: `SCOPE_<lower>` + `ROLE_<upper>` (apps) → `ROLE_<UPPER>_<UPPER>` (platform)
- **ADR-0022** — RFC 7807 envelope fields: apps missing `traceId/spanId/timestamp/errors[]/downstream` → adopt platform shape

### C.1 BaseEntity migration (3 days)

**Pilot app:** `audit-service` (smallest entity count).

For every `@Entity` in `apps/audit-service/src/main/kotlin/com/trips_enjoy/audit/domain/`:

1. Remove the local `id`, `createdAt`, `updatedAt`, `version`, `createdBy`, `updatedBy`, `deletedAt` columns.
2. Extend `com.trips_enjoy.platform.data.BaseEntity`.
3. Add a V_NN migration per entity: `ALTER TABLE audit.<entity> ADD COLUMN created_by TEXT;` etc. (all columns are nullable).
4. Keep `@Column(name = "...")` annotations to preserve existing column names where they differ from the platform defaults.

**Exit criteria for C.1:**
- All audit-service entities extend `BaseEntity`
- No `@Version` annotations remain in audit-service source
- No `@CreatedDate` / `@LastModifiedDate` annotations remain (replaced by `BaseEntity`'s `@DateTimeFormat`-aware fields)
- All audit-service tests pass

### C.2 ApiExceptionHandler deletion (2 days)

**Strategy:** Add an `ApiException` adapter to `platform-spring-boot-error` so app controllers can throw either `BusinessException` (new) or `ApiException(code, status)` (existing) without changing the global handler.

`packages/platform-spring-boot/platform-spring-boot-error/src/main/kotlin/com/trips_enjoy/platform/error/ApiException.kt`:

```kotlin
package com.trips_enjoy.platform.error

class ApiException(
    val code: String,
    val httpStatus: HttpStatus,
    override val message: String,
    val details: Map<String, Any?> = emptyMap(),
) : RuntimeException(message)
```

Then delete 11 `ApiExceptionHandler.kt` files + 11 `ApiException.kt` companion classes. The platform `GlobalExceptionHandler` already handles `BusinessException`; add a sibling handler for `ApiException` that produces the same RFC 7807 envelope (now ADR-0022-compliant).

### C.3 SecurityConfiguration deletion (2 days)

**Pilot app:** `customer-service` (which uses the default Keycloak claim shape).

Replace `apps/customer-service/src/main/kotlin/com/trips_enjoy/customer/config/SecurityConfiguration.kt` with the platform `com.trips_enjoy.platform.security.SecurityConfiguration` plus a thin subclass that overrides only the `permitAll` paths and the `/admin/v1/**` matcher.

```kotlin
@Configuration
@EnableWebSecurity
class SecurityConfiguration : PlatformSecurityConfiguration() {
    override fun publicPaths(): Array<String> = arrayOf(
        "/healthz", "/actuator/health", "/v3/api-docs", "/swagger-ui.html", "/swagger-ui/**",
    )
    override fun adminPathPrefix(): String = "/admin/v1/"
}
```

### C.4 Phase C exit criteria

- [ ] All 14 Kotlin apps' domain entities extend `BaseEntity`
- [ ] All 11 `ApiExceptionHandler.kt` files deleted; only platform `GlobalExceptionHandler` remains
- [ ] All 11 `SecurityConfiguration.kt` files deleted (replaced by 1-2 line subclasses)
- [ ] JWT roles + scopes in test tokens use `ROLE_<UPPER>_<UPPER>` shape per ADR-0021
- [ ] All RFC 7807 responses carry `traceId`, `spanId`, `timestamp`, `errors[]`, `downstream` per ADR-0022
- [ ] `make build-spring` 15/15 green
- [ ] ADR-0021 + ADR-0022 merged
- [ ] Per-service STATUS.md + PLAN.md updated

## Phase D — Tier 4 new platform modules (2 weeks)

**Goal:** add `platform-spring-boot-partition` and the `EnvironmentPostProcessor` for YAML baseline; delete the per-app `PartitionMaintenanceJob` + `application.yml` boilerplate.

### D.1 New module: `platform-spring-boot-partition` (1 week)

Create `packages/platform-spring-boot/platform-spring-boot-partition/` with:

- `PartitionMaintenanceStarter` — `@Component` that scans `@Value("${platform.partition.<schema>.tables}")` and runs `partman.ensure_partitions(...)` + `partman.drop_expired_partitions(...)` on a single cron `0 0 2 * * *`.
- `PartitionMaintenanceEventPublisher` — emits `<service>.partition.maintained.v1` keyed off `spring.application.name`.
- `PartitionMaintenanceProperties` — `@ConfigurationProperties("platform.partition")` with per-schema `{tables, retention-days, horizon-months, cron}`.
- `PartitionMaintenanceAutoConfiguration` — gated on `@ConditionalOnProperty("platform.partition.enabled=true")`.

Then delete `PartitionMaintenanceJob.kt` from 9 apps + `PartitionMaintenanceEventPublisher.kt` from 5 apps.

Each app's `application.yml` gains:

```yaml
platform:
  partition:
    enabled: true
    cron: "0 0 2 * * *"
    schemas:
      audit:
        tables: outbox, audit_event, inbox
        retention-days: 90
        horizon-months: 3
```

### D.2 IdempotencyService helper (2 days)

Extend `platform-spring-boot-data` with:

```kotlin
@Component
class PlatformIdempotencyService(
    private val repo: IdempotencyRepository,
) {
    fun <T> idempotent(
        scope: String,
        actorId: String,
        key: UUID,
        requestHash: String,
        block: () -> T,
    ): T {
        // 1. lookup existing record by (actor_id, idempotency_key)
        // 2. if exists + same hash → return cached response
        // 3. if exists + different hash → throw ApiException("IDEMPOTENCY_KEY_REUSED", 422)
        // 4. else: insert PENDING row, run block, update with response + COMPLETED
    }
}
```

Delete 8 app-local `IdempotencyService.kt` files; update call sites to use `platformIdempotencyService.idempotent(scope, actorId, key, hash) { ... }`.

### D.3 KafkaConsumer inbox-ingest helper (2 days)

Extend `platform-spring-boot-messaging` with `InboxListenerSupport`:

```kotlin
abstract class InboxListenerSupport {
    @Autowired lateinit var inboxRepository: InboxRepository

    protected fun ingest(
        topic: String,
        eventId: UUID,
        payload: String,
        correlationId: String,
        handler: () -> Unit,
    ) {
        // 1. inboxRepository.saveIfAbsent(InboxEvent(eventId, topic, payload, correlationId))
        // 2. if duplicate → return
        // 3. handler()
    }
}
```

Replace 3 app-local inbox-ingest lambdas.

### D.4 application.yml baseline (2 days)

Add `EnvironmentPostProcessor` in `platform-spring-boot-autoconfigure` that pre-populates missing properties with sensible defaults: `spring.flyway.enabled=true`, `spring.jpa.open-in-view=false`, `management.endpoints.web.exposure.include=health,info,prometheus`, `springdoc.api-docs.path=/v3/api-docs`, etc.

Each app's `application.yml` shrinks from ~50 LOC baseline to ~15 LOC of service-specific properties.

### D.5 Phase D exit criteria

- [ ] New `platform-spring-boot-partition` module builds + tests green
- [ ] 9 apps use the new partition starter; 9 `PartitionMaintenanceJob.kt` files deleted
- [ ] 8 apps use the new `PlatformIdempotencyService`; 8 `IdempotencyService.kt` files deleted
- [ ] 3 apps use `InboxListenerSupport`; 3 inbox-ingest lambdas deleted
- [ ] `application.yml` baseline shrunk from ~50 LOC to ~15 LOC in 13 apps
- [ ] `make build-spring` 15/15 green
- [ ] Per-service STATUS.md + PLAN.md updated

## Phase E — Go (1 week)

**Goal:** lift Go duplication into `platform-go` and replace per-service implementations.

### E.1 chat-service pilot (1 day)

`chat-service/cmd/server/main.go` is 91 lines. Replace with:

```go
package main

import (
    "net/http"
    "github.com/go-chi/chi/v5"
    "github.com/trips-enjoy/platform-go/health"
    "github.com/trips-enjoy/platform-go/openapi"
    "github.com/trips-enjoy/platform-go/requestid"
)

func main() {
    r := chi.NewRouter()
    r.Use(requestid.Middleware)
    r.Get("/health", health.Handler("chat-service", nil))
    r.Get("/openapi.json", openapi.Handler)
    r.Get("/docs", openapi.SwaggerUI("/openapi.json"))
    // /v1/chat/ws and /v1/status left untouched (Phase 7.7 feature work)
    http.ListenAndServe(":8104", r)
}
```

**Exit criteria:** chat-service compiles + tests pass; reduced to ~25 lines.

### E.2 file-service + geolocation-service (3 days)

Both have identical structure. Lift:
- `requestid.Middleware` (replace `internal/httpapi/request_id.go`)
- `httperr.WriteError` + `errormodel.New` (delete `internal/httperr/` + `internal/apierr/`)
- `observability.InitTracer` (replace no-op shim)
- `migrate.Run` (replace `runMigrate`)
- `envconfig` helpers (replace `valueOrDefault` etc.)
- `pgxpool.Connect` (replace `internal/db/pool.go`)
- `logging.New` (replace `internal/observability/log.go`)

### E.3 api-gateway (3 days)

api-gateway has the most complete Go wiring. Replace:
- `internal/gateway/telemetry.go` → `observability.InitTracer`
- `internal/gateway/errors.go` → `httperr.WriteError` + `errormodel.New`
- `internal/gateway/request_id.go` → `requestid.Middleware`
- `internal/gateway/ratelimit.go` → new `platform-go/ratelimit`
- `internal/gateway/circuit.go` → new `platform-go/circuit`

Audit-emission builder (`audit.admin.api_gateway.v1`) gets a small helper in `platform-go/eventbus`.

### E.4 Phase E exit criteria

- [ ] `chat-service` `cmd/server/main.go` ≤ 30 lines
- [ ] `file-service` + `geolocation-service` use `requestid.Middleware`, `httperr.Write`, `observability.InitTracer`, `migrate.Run`, `envconfig.*`, `dbpool.Connect`, `logging.New`
- [ ] `api-gateway` uses `observability.InitTracer`, `httperr.Write`, `requestid.Middleware`, `ratelimit.*`, `circuit.*`, `eventbus.*`
- [ ] All Go services emit UUIDv7 request_ids consistent with Kotlin
- [ ] All Go services emit RFC 7807 envelopes consistent with Kotlin
- [ ] `make build` 21/21 green
- [ ] Per-service STATUS.md updated; Go-side PLAN.md gain Phase 9 sections

## Phase F — Python (3 days)

**Goal:** lift the concrete Python duplication; defer speculative lifts.

### F.1 Concrete lifts (3 days)

- **F.1.1** (1 hour): replace `apps/reporting-service/app/observability/request_id.py` with `platform_python.requestid.RequestIDMiddleware`. Delete the local file.
- **F.1.2** (3 hours): add `raise_http(code, detail, request, **extra)` helper to `platform_python.errormodel`. Replace 6 inline `raise HTTPException(...)` calls in `apps/reporting-service/app/api/*.py`. Delete local `ErrorEnvelope` in `app/domain/types.py:127-136`.
- **F.1.3** (30 min): call `platform_python.observability.init_tracer("reporting-service")` from `app/main.py` lifespan. Currently OTel is documented but the provider is never created.
- **F.1.4** (2 hours): enrich `platform_python.settings.PlatformSettings` with the 30 service-specific fields from `apps/reporting-service/app/config.py`. Adopt `make_settings("reporting-service", "REPORTING_SERVICE")`.
- **F.1.5** (3 hours): wrap `apps/reporting-service/app/auth/jwks.py` around `platform_python.jwtauth.JWTAuth`. Fix local `verify_aud=False` → `verify_aud=True` per platform convention.

### F.2 Deferred lifts (parked)

The following are parked until a second Python service arrives:

- `platform_python.logging` (JsonFormatter + redact) — single app; defer
- `platform_python.outbox` + `platform_python.inbox` — single app; defer
- `platform_python.auth` FastAPI dep factories — single app; defer
- `platform_python.metrics` — single app uses in-memory shim with TODO; defer
- `platform_python.consumer` (retry + DLQ helper) — single app; defer

### F.3 Phase F exit criteria

- [ ] `apps/reporting-service/app/observability/request_id.py` deleted; uses `platform_python.requestid`
- [ ] All 6 inline RFC 7807 dicts in `app/api/*.py` replaced with `errormodel.raise_http`
- [ ] `apps/reporting-service/app/domain/types.py` no longer defines `ErrorEnvelope`
- [ ] `app/main.py` lifespan calls `observability.init_tracer`
- [ ] `app/config.py` subclasses `PlatformSettings`
- [ ] `app/auth/jwks.py` wraps `jwtauth.JWTAuth`
- [ ] `make build` 21/21 green
- [ ] `reporting-service` STATUS.md + PLAN.md updated

## 11. Cumulative exit criteria (post Phase F)

- [ ] All 6 ADRs (ADR-0020 through ADR-0027) merged to `docs/architecture/adrs/`
- [ ] All 21 apps' STATUS.md + PLAN.md gain a Phase 9 section (append-only, never renumbered)
- [ ] All 21 apps' tests pass; no behavior regressions in production contracts (Kafka topic names, REST paths, RFC 7807 envelope, correlation IDs, idempotency semantics)
- [ ] `make build` 21/21 green; `make build-spring` 15/15 green; `make build-go` 5/5 green; `make build-python` 1/1 green
- [ ] Total LOC removed: ~15,200 (audit baseline) — net negative
- [ ] Zero app-local copies of `RequestCorrelationFilter`, `JacksonConfiguration`, `MetricsConfiguration`, `OpenApiConfiguration`, `TestcontainersConfiguration`, `ApiExceptionHandler`, `SecurityConfiguration`, `PartitionMaintenanceJob`, `IdempotencyService` remain
- [ ] All three platform packages (`platform-spring-boot`, `platform-go`, `platform-python`) are imported and used at runtime by every app

## 12. Open questions to resolve before each phase

Before starting **Phase A**: confirm ADR-0026 (UUIDv7 + MDC) and ADR-0027 (Testcontainers image pin). Without these, deleting `RequestCorrelationFilter` and `TestcontainersConfiguration` would silently change observable behavior.

Before starting **Phase B**: confirm ADR-0020 (DLQ naming). Currently 8/8 apps use `<topic>.dlq`; the platform uses `<topic>.DLQ.v1`. The right move is to rename the platform's default to match, but that affects `api-gateway`'s existing DLQ consumers.

Before starting **Phase C**: confirm ADR-0021 (role claim shape) and ADR-0022 (RFC 7807 fields). Both require coordinating with `identity-service` seeder changes — the `service-claims` protocol mappers may need to emit roles in the new shape.

Before starting **Phase D**: confirm the cron schedule in ADR-0025 (`0 0 2 * * *` everywhere). Currently 3 apps use different times; aligning them in one shot requires a cron restart on every service simultaneously.

Before starting **Phase E**: confirm Go apps' readiness for shared modules. `chat-service` is a stub (low risk). `file-service` + `geolocation-service` are identical (medium risk — break one, break the other). `api-gateway` is the highest-risk (real production wiring).

Before starting **Phase F**: confirm `reporting-service` is the only Python service for the next quarter. If a 2nd Python service is on the roadmap, the speculative lifts in §F.2 should be promoted to F.1.

## 13. References

- [`PLATFORM_DRY_AUDIT.md`](../shared/PLATFORM_DRY_AUDIT.md) — the audit this plan executes
- [`MASTER_PLAN.md`](../MASTER_PLAN.md) — Phase ordering context; Phase 9 is appended after Phase 8
- [`PLAN_INDEX.md`](../PLAN_INDEX.md) — 21 per-service PLAN.md files (each gains a Phase 9 section during this refactor)
- [`architecture/adrs/`](../architecture/adrs/) — where the 8 new ADRs land
- [`shared/PARTITION_FUNCTIONS.md`](../shared/PARTITION_FUNCTIONS.md) — canonical PL/pgSQL contract for Phase D
- [`shared/CONVENTIONS.md`](../shared/CONVENTIONS.md) — error envelope, correlation IDs, audit events (the contracts Phase C adopts)
- [`architecture/adrs/0019-request-id-at-edge.md`](../architecture/adrs/0019-request-id-at-edge.md) — UUIDv7 + MDC convention Phase A adopts
- [`packages/platform-spring-boot/`](../../packages/platform-spring-boot/) — the 14-module starter Phase A–D enriches
- [`packages/platform-go/`](../../packages/platform-go/) — the 6-subpkg workspace Phase E extends
- [`packages/platform-python/`](../../packages/platform-python/) — the 6-subpkg wheel Phase F extends
