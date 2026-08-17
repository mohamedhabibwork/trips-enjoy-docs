# Platform DRY Audit — apps ↔ packages

> **Created:** 2026-08-15
> **Status:** Audit only — **no code changes proposed without an explicit ADR**.
> **Scope:** All 21 apps in [`apps/`](../../apps/) vs the three shared packages in [`packages/`](../../packages/) — `platform-spring-boot`, `platform-go`, `platform-python`.
> **Method:** Per-language file inventory across the 21 apps; comparing each candidate for "shared" (imports the canonical version from a platform package) vs "duplicated" (redeclares the same logic locally).

## 0. Why this audit

The three shared packages in `packages/` are declared as classpath / workspace dependencies by every service. The classpath-level wiring works: `make build` passes 21/21 services. **But the runtime usage is near-zero.** A repo-wide grep for `import com.trips_enjoy.platform.*` across `apps/*/src/main/kotlin` returns zero matches. Each Kotlin service ships its own copy of every class the platform exposes. Each Go service ships its own copy of every helper that `platform-go` already provides. `reporting-service` is the only Python service and has one smoke-test import against `platform_python`.

This audit enumerates the duplication, ranks the lifts by ROI (#apps affected × LOC removed), and flags the contract-drift items that must be resolved **before** any deletion PR can land safely.

## 1. Headline numbers

| Language | Apps audited | Apps actually using the platform library at runtime | Approx duplicated LOC | Starter/pkg modules declared but unused at runtime |
|---|---|---|---|---|
| Kotlin / Spring Boot | 15 | **0** | **~15,884** | 14 (every app including `admin-service` scaffold) |
| Go | 4 (`api-gateway`, `chat-service`, `file-service`, `geolocation-service`) | **0** in non-test code | ~2,460 | 6 (`requestid`, `errormodel`, `httperr`, `money`, `jwtauth`, `observability`) |
| Python | 2 (`reporting-service`, `fraud-risk-service`) | **0** in non-test code | ~720 app-side + ~150 fraud-risk | 6 (`errormodel`, `jwtauth`, `settings`, `requestid`, `observability`, `money`) |
| **Total** | **21** | **0** | **~19,214** | |

> **Note on language counts (corrected 2026-08-16 after deep verification):** All 21 apps ship the corresponding starter module on the classpath. The 15 Kotlin apps are: `admin-service` (scaffold), `audit-service`, `configuration-service`, `courier-service`, `customer-service`, `driver-service`, `food-order-service`, `identity-service`, `ledger-service`, `notification-service`, `payment-service`, `pricing-service`, `restaurant-service`, `search-service`, `trip-service`. (`courier-service` and `pricing-service` are Kotlin per `build.gradle.kts`, not Go as the initial audit claimed.) The 4 Go apps are: `api-gateway`, `chat-service`, `file-service`, `geolocation-service`. `reporting-service` and `fraud-risk-service` are the 2 Python apps — `fraud-risk-service` was missed by the initial high-level audit.

The Go count in the table above is intentionally narrower (4 services) because `chat-service` is a 91-line stub and `courier-service` / `pricing-service` are Kotlin. If those are included the Go total grows to ~3,200 LOC.

## 2. The "starter on classpath, ignored at runtime" pattern

Every Kotlin app declares:

```kotlin
implementation("com.trips-enjoy.platform:spring-boot-starter:4.1.0")
```

That single line pulls in the umbrella starter, which transitively pulls in all 14 platform modules (`platform-spring-boot-api-docs`, `platform-spring-boot-audit`, `platform-spring-boot-autoconfigure`, `platform-spring-boot-caching`, `platform-spring-boot-data`, `platform-spring-boot-error`, `platform-spring-boot-lookup`, `platform-spring-boot-messaging`, `platform-spring-boot-money`, `platform-spring-boot-observability`, `platform-spring-boot-security`, `platform-spring-boot-test`, `platform-spring-boot-web`).

The umbrella works correctly: every `@ConditionalOnMissingBean` would let the platform version win, *if no app-local version existed*. But every app declares an identically-named `@Bean`/`@Component` locally, which suppresses the platform bean via `@ConditionalOnMissingBean` ordering. The result is that **the platform bean ships, gets shadowed, and apps pay the LOC cost of the shadowed copy**.

Example — `RequestCorrelationFilter.kt`:

```kotlin
// apps/audit-service/src/main/kotlin/com/trips_enjoy/audit/config/RequestCorrelationFilter.kt
@Component
class RequestCorrelationFilter : OncePerRequestFilter() {
    override fun doFilterInternal(request, response, chain) {
        val value = request.getHeader("X-Request-Id")
            ?: request.getHeader("X-Correlation-Id")
            ?: UUID.randomUUID().toString()           // <-- UUIDv4, not UUIDv7
        request.setAttribute("correlationId", value)  // <-- request attribute, not MDC
        response.setHeader("X-Request-Id", value)
        response.setHeader("X-Correlation-Id", value)
        chain.doFilter(request, response)
    }
}
```

This file appears byte-identical in **9 Kotlin apps**. The platform-web module already exposes `com.trips_enjoy.platform.web.RequestCorrelationFilter` which uses UUIDv7 + MDC and is the canonical implementation per [ADR-0019](../../docs/shared/CONVENTIONS.md#correlation-ids). Deleting the app copies is a pure LOC win — but only if all 9 apps adopt the UUIDv7 + MDC behavior, because audit-event `correlation_id` columns must remain stable across the platform.

## 3. Kotlin — duplication inventory (ranked by ROI)

### 3.1 Tier 1 — pure deletion (platform already provides, no app-specific logic)

| # | Pattern | Apps affected | LOC saved (approx) | Already in starter? |
|---|---|---|---|---|
| K-01 | `RequestCorrelationFilter.kt` (UUIDv7 + header echo) | **15** (not 9) | ~432 | ✅ `platform-spring-boot-web` |
| K-02 | `JacksonConfiguration.kt` (JavaTimeModule + KotlinModule) | **15** (not 9) | ~432 | ✅ `platform-spring-boot-web` |
| K-03 | `MetricsConfiguration.kt` (`MeterRegistryCustomizer<MeterRegistry>` + common tags) | **12** (not 7) | ~360 | ✅ `platform-spring-boot-observability` |
| K-04 | `OpenApiConfiguration.kt` (bearerAuth + info + servers) | **15** (not 9) | ~600 | ✅ `platform-spring-boot-api-docs` |
| K-05 | `TestcontainersConfiguration.kt` (Kafka + Postgres + Redis triple) | **15** (not 12) | ~480 | ✅ `platform-spring-boot-test` (`BaseIntegrationTest` already exists) |
| K-06 | `KafkaProducerConfiguration.kt` (manual `KafkaTemplate<String,String>` bean) | 2 | ~80 | Spring Boot auto-config (no app bean needed) |
| K-07 | `partition_functions.sql` Flyway migration (PL/pgSQL block) | **15** (not 10) | ~3,300 | Canonical contract in [`PARTITION_FUNCTIONS.md`](./PARTITION_FUNCTIONS.md); needs SQL-library mechanism (see L13 in §5) |
| K-08 | `ApiException.kt` companion class (custom data class + ErrorCode enum) | 12 | ~480 | ✅ `platform-spring-boot-error` (`BusinessException`) |
| K-09 | `ApiExceptionHandler.kt` (RFC 7807 ProblemDetail + problem() helper) | **15** (not 11) | ~750 | ✅ `platform-spring-boot-error` (`GlobalExceptionHandler`) |
| K-10 | `SecurityConfiguration.kt` (`SecurityFilterChain` + `JwtAuthenticationConverter`) | **15** (not 11) | ~880 | ✅ `platform-spring-boot-security` (`SecurityConfiguration` + `JwtRoleConverter`) |
| K-11 | `KafkaConsumerConfiguration.kt` (DLQ + listener factory) | **15** (not 8) | ~750 | ✅ `platform-spring-boot-messaging` |
| K-12 | `OutboxPublisher.kt` (poll loop + retry + DLQ) | **15** (not 7) | ~675 | ✅ `platform-spring-boot-messaging` |
| K-13 | `Idempotency*` (entity + cleanup + service) | **13** (not 5) | ~1,000 | ✅ `platform-spring-boot-data` (`IdempotencyRecord`) |
| K-14 | `OutboxEvent.kt` JPA entity (4 distinct shapes, not 6) | **15** (not 6) | ~1,050 | ✅ `platform-spring-boot-messaging` |
| K-15 | `InboxEvent.kt` JPA entity (2 shapes: small 5-col + rich 9-col) | **15** (not 7) | ~750 | ✅ `platform-spring-boot-messaging` |
| K-16 | `BaseEntity` columns (per-entity redeclaration of `id`/`createdAt`/`updatedAt`/`version`/`createdBy`) | **15** (not 14) | ~2,100 | ✅ `platform-spring-boot-data` |
| K-17 | `application.yml` baseline block (Flyway + Hikari + JPA + actuator + springdoc) | **15** (not 13) | ~750 | ✗ — needs `EnvironmentPostProcessor` (see L20) |
| K-18 | `PartitionMaintenanceJob.kt` (advisory lock + `partman.ensure_partitions`) | **11** (not 9) | ~495 | ✗ — needs new `platform-spring-boot-partition` module |
| **Tier 1 subtotal** | | | **~14,364** | |

**Drift to resolve before deletion:**

- K-01: apps use `UUID.randomUUID()` + request attribute; platform uses `Uuid.generateV7()` + MDC. **Adopt platform** (per [ADR-0019](../../docs/shared/CONVENTIONS.md#correlation-ids)).
- K-03: apps hardcode `service = "<name>"` instead of `service = ${spring.application.name}`; some apps use different `MeterFilter.denyNameStartsWith(...)` patterns. **Standardise on platform.**
- K-05: apps use `apache/kafka-native:latest` vs platform uses `confluentinc/cp-kafka:7.5.0`; apps declare `redis:7-alpine` vs platform `redis:7.2-alpine`. **Adopt platform versions** (or accept the drift explicitly via ADR).
- K-07: SQL is byte-identical. **Adopt shared migration** via `docs/shared/sql/partition_functions.sql` + CI grep rejecting per-service copies.

### 3.2 Tier 2 — shared canonical entity, app entities drift

The verified counts are bigger than the original high-level audit claimed:

| # | Pattern | Apps affected | LOC saved (approx) | Already in starter? |
|---|---|---|---|---|
| K-19 | `OutboxEvent` JPA entity — **4 distinct shapes across 15 apps** (10-col, 11-col, 14-col, 15-col; 2 table-name conventions `outbox` vs `outbox_events`; 2 payload types `String` vs `Map<String,Any?>`; trip-service uses singular `outbox_event`) | 15 | ~1,050 (already counted in K-14 above) | ✅ `platform-spring-boot-messaging` |
| K-20 | `InboxEvent` JPA entity — **2 shapes across 15 apps** (small 5-col keyed by `event_id`; rich 9-col with `source_topic`/`source_event_id`/`event_type`/`payload`/`correlation_id`; identity has it inline in `AuditAndOutbox.kt`) | 15 | ~750 | ✅ `platform-spring-boot-messaging` |
| K-21 | `Idempotency*` (entity + service) — **3 shapes across 13 apps** (`IdempotencyKey.kt` with `(scope, idem_key)` × 8; `IdempotencyRecord.kt` × 3; `Idempotency.kt` with UUID-PK × 2; audit/identity/ledger have none) | 13 | ~1,000 | ✅ `platform-spring-boot-data` |
| **Tier 2 subtotal** (already in §3.1 row totals) | | | **~2,800** | |

**Drift to resolve before migration:**

- K-19 (OutboxEvent): 4 column-shape variants + 2 table-name conventions + 2 payload types. Migration path is `ALTER TABLE <schema>.outbox ADD COLUMN partition_key, headers, attempts, last_error, next_attempt_at; UPDATE ... SET partition_key = event_id::text WHERE partition_key IS NULL;` etc. per [ADR-0028](../architecture/adrs/0028-outbox-event-schema.md). `trip-service` must `ALTER TABLE trip.outbox_event RENAME TO trip.outbox;` first. `payment-service` must rename `outbox_events` → `outbox`. 2 services with `Map<String,Any?>` payload need a JPA `AttributeConverter<JsonNode>` to persist as JSONB. The retry-strategy drift (3 attempts vs platform's 6 attempts with exponential backoff) must be aligned to ADR-0028.
- K-20 (InboxEvent): **rich variant** (9 cols) is the canonical; **5 apps** currently use small. Migration: `ALTER TABLE <schema>.inbox ADD COLUMN source_topic, source_event_id, event_type, payload, correlation_id;` + backfill.
- K-21 (Idempotency): three variants — `(actor_id, idempotency_key)` (canonical per [ADR-0027](../architecture/adrs/0027-idempotency-record-schema.md)), `(scope, idem_key)` (8 apps), `(idempotency_key)` UUID-PK (3 apps). The 8 `(scope, idem_key)` apps must `ALTER TABLE ... DROP CONSTRAINT uq_idempotency_scope_key; ADD CONSTRAINT uq_idempotency_actor_key UNIQUE (actor_id, idempotency_key);` and backfill `actor_id = '00000000-0000-0000-0000-000000000000'` (system actor) for the rows that pre-date per-actor isolation.

### 3.3 Tier 3 — domain entities should extend shared `BaseEntity`

| # | Pattern | Apps affected | LOC saved (approx) | Already in starter? |
|---|---|---|---|---|
| K-22 | `BaseEntity` columns (per-entity redeclaration of `id`/`createdAt`/`updatedAt`/`version`/`createdBy`) — **verified 0 apps extend `com.trips_enjoy.platform.data.BaseEntity`** | 15 | ~2,100 (already in K-16 above) | ✅ `platform-spring-boot-data` |
| K-23 | `ApiExceptionHandler` + RFC 7807 — **15 apps**, but **6 use Spring `ProblemDetail` + 9 use custom `ApiProblem`** (different envelope entirely) | 15 | ~750 (already in K-09 above) | ✅ `platform-spring-boot-error` (`GlobalExceptionHandler`) |
| K-24 | `SecurityConfiguration.kt` — **15 apps**, but **6 use `JwtAuthenticationConverter` + 9 use `Customizer.withDefaults()`** | 15 | ~880 (already in K-10 above) | ✅ `platform-spring-boot-security` (`JwtRoleConverter`) |

**Drift to resolve before migration:**

- K-22: each app redeclares `id: UUID` (no `@UuidGenerator`), `createdAt: Instant = Instant.now()` (no `@CreatedDate`), etc. **Adopt canonical `BaseEntity`** per [ADR-0019](../architecture/adrs/0019-request-id-at-the-edge.md)'s JPA-auditing precedent.
- K-23: **the 9 custom-`ApiProblem` apps are the worst drift** — they emit a non-RFC-7807 envelope (`type=https://api.trips-enjoy.com/errors/<code>`, no `timestamp`, no `traceId/spanId/errors[]`). The 6 Spring-`ProblemDetail` apps are closer but still missing `traceId`/`spanId`. Per [ADR-0026](../architecture/adrs/0026-rfc7807-error-envelope.md): add an `ApiException` adapter to `platform-spring-boot-error` so app controllers can throw either type, then delete the 12 `ApiException.kt` companion classes.
- K-24: apps use `SCOPE_<lower>` + `ROLE_<upper>`; platform uses `ROLE_<CLIENT>_<UPPER>` per [ADR-0025](../architecture/adrs/0025-keycloak-role-claim-shape.md). Adopt platform convention (matches `identity-service`'s `service-claims` protocol mapper output).

### 3.4 Tier 4 — new platform module candidates

| # | Pattern | Apps affected | LOC saved (approx) | New module? |
|---|---|---|---|---|
| K-25 | `PartitionMaintenanceJob` — **verified 11 apps** (audit, configuration, courier, customer, driver, identity, ledger, notification, payment, pricing, trip); **all 11 use `0 0 2 * * *`** — ADR-0029's "3 distinct crons" was wrong | 11 | ~495 | ✗ — new `platform-spring-boot-partition` (or extend `platform-spring-boot-data`) |
| K-26 | `PartitionMaintenanceEventPublisher` (`<svc>.partition.maintained.v1`) | 5 | ~125 | Part of K-25 |
| K-27 | `IdempotencyService.idempotent(actor, key, hash)` helper (lookup + reserve + commit + reuse-422) | 8 | ~400 | ✗ — extend `platform-spring-boot-data` |
| K-28 | `KafkaConsumer` inbox-ingest lambda (~40 LOC per app: read event_id → dedup → ack) | 10 | ~400 | ✗ — extend `platform-spring-boot-messaging` |
| K-29 | `application.yml` baseline block (Flyway + Hikari + JPA + actuator + springdoc) | 15 | ~750 | ✗ — `EnvironmentPostProcessor` in `platform-spring-boot-autoconfigure` |
| K-30 | `@ConditionalOnMissingBean` annotation gap (most platform beans have NO `@ConditionalOnMissingBean`) | n/a | n/a | ✗ — annotate the 6 platform beans that lack it (`MetricsConfiguration.platformMetricsCustomizer`, `OpenApiConfiguration.platformOpenApi`, `KafkaConfiguration.kafkaListenerContainerFactory` + `kafkaTemplate`, `SecurityConfiguration.defaultSecurityFilterChain`, `WebAutoConfiguration.requestCorrelationFilter`) |
| **Tier 4 subtotal** | | | **~2,170** | |

**Important new finding (K-30):** Most platform beans that apps should be replacing have **no `@ConditionalOnMissingBean` annotation**, so even after apps delete their copies, the platform bean would run *in addition to* any app-local re-declarations. Adding `@ConditionalOnMissingBean` is a prerequisite for safe deletion. The audit's earlier claim "every `@ConditionalOnMissingBean` would let the platform version win, *if no app-local version existed*" is misleading: only `JacksonConfiguration.jackson2ObjectMapper` has the annotation. **The platform has 6 beans that need `@ConditionalOnMissingBean` added before app-local deletions are safe.**

## 4. Go — duplication inventory (ranked by ROI)

> The Go inventory is narrower than Kotlin because there are only 4 Go apps: `api-gateway`, `chat-service`, `file-service`, `geolocation-service`. (`courier-service` and `pricing-service` are Kotlin.)

### 4.1 Already in `platform-go` — runtime usage is zero

| # | Pattern | Apps affected | LOC saved (approx) | Already in `platform-go`? |
|---|---|---|---|---|
| G-01 | `RequestID` middleware (UUIDv7 + header echo + ctx key) | 3 | ~120 | ✅ `requestid` package |
| G-02 | RFC 7807 error envelope + `WriteError` + `CanonicalCode` | 3 | ~400 | ✅ `httperr` + `errormodel` packages |
| G-03 | Prometheus RED metrics (Counter + Histogram + `statusRecorder` + `observe()`) | 3 | ~180 | ✗ — new `platform-go/redmetrics` |
| G-04 | Health endpoints (`/health`, `/ready`, `/started`) | 4 | ~140 | ✗ — new `platform-go/health` |
| G-05 | OpenAPI stub + Swagger UI shell | 4 | ~60 | ✗ — new `platform-go/openapi` |
| G-06 | Env-var helpers (`valueOrDefault`, `intEnv`, `int64Env`, `durationEnv`, `boolEnv`, `splitCSV`) + `LoadDotEnv` | 4 | ~150 | ✗ — new `platform-go/envconfig` |
| G-07 | `pgxpool` connect helper (5s timeout, MaxConns=10, MaxConnLifetime=30m) | 2 | ~70 | ✗ — new `platform-go/dbpool` |
| G-08 | Structured `slog` JSON logger + `WithRequestID` + `FromContext` | 2 | ~110 | ✗ — new `platform-go/logging` |
| G-09 | Real OTel SDK init (OTLP-gRPC + stdout fallback + W3C propagator) | 3 | ~90 | ✅ `observability.InitTracer` (currently unused; api-gateway has its own real impl, file-service + geo ship a no-op shim) |
| G-10 | Event envelope + `Publisher` interface + `TopicMap` + `TopicFor` | 3 | ~180 | ✗ — new `platform-go/eventbus` |
| G-11 | Auth: `Principal` + header-stub middleware (X-User-Id driven) + `RequireRole` | 2 | ~250 | ✗ — extend `jwtauth` with `HeaderStub()` constructor |
| G-12 | `sony/gobreaker` circuit-breaker wrapper | 2 | ~250 | ✗ — new `platform-go/circuit` |
| G-13 | `runMigrate` shell-out to golang-migrate CLI | 2 | ~50 | ✗ — new `platform-go/migrate` |
| G-14 | In-memory outbox scaffold | 2 | ~80 | ✗ — new `platform-go/outbox` |
| G-15 | Rate-limit middleware (Redis-backed + token-bucket fallback) | 2 | ~250 | ✗ — new `platform-go/ratelimit` |
| **Go subtotal** | | | **~2,380** | |

### 4.2 Drift to resolve before deletion

- G-01: api-gateway uses `uuid.NewV7()` from `google/uuid`; file-service + geolocation-service use a package-private `newUUIDv7` to avoid the dependency; chat-service uses chi stock `middleware.RequestID` (no MDC, no OTel). **Adopt platform `requestid.Middleware`** with `google/uuid` as transitive dep — file-service + geo drop their private function; chat-service adopts the platform version.
- G-02: api-gateway's `Envelope` uses `Title/Detail/Status/Type/TraceID/SpanID/Errors/Downstream`; file-service + geo's `Envelope` uses `Code/Message/CorrelationID/Errors/Downstream`. **Adopt api-gateway shape** (matches RFC 7807 + ADR-0019) and reconcile field set.
- G-09: file-service + geo's `observability.Init` is a no-op stub; api-gateway has the real wiring in `internal/gateway/telemetry.go`. **Replace both stubs with `observability.InitTracer`** — the platform version already exists and is unused.
- G-12: api-gateway uses `sony/gobreaker` with `MaxRequests=1` (half-open) + `Interval=10s` + `Timeout=30s`; geolocation-service uses `sony/gobreaker` with `MaxRequests=3` + `Interval=60s` + `Timeout=10s`. **Adopt shared config** keyed off `platform.circuit.<name>.{threshold,cooldown,half-open-probes}`.

### 4.3 chat-service caveat

`chat-service/cmd/server/main.go` is a 91-line stub with hand-rolled chi `Logger`, `Recoverer`, `RequestID`, a 4-line `/openapi.json` literal, a Swagger UI shell literal, and a single `/v1/chat/ws` placeholder. Adopting `requestid.Middleware` + `httperr.Write` + `openapi.SwaggerUI` would shrink this to ~25 lines and is the highest-ROI single PR in the Go audit.

## 5. Python — duplication inventory

> There are **2 Python services today** (not 1 — `fraud-risk-service` was missed by the initial high-level audit). Several suggested lifts are **speculative** — they only pay off when a third Python service appears. The audit marks each lift accordingly.

| # | Pattern | LOC saved (approx) | Already in `platform-python`? | Pay-off |
|---|---|---|---|---|
| P-01 | `app/observability/request_id.py` (RequestIDMiddleware) | ~50 | ✅ `requestid.py` exists; local version uses `contextvars` only | **Concrete** — local version is missing the `X-Correlation-Id` response header |
| P-02a | Local `ErrorEnvelope` in `reporting-service/app/domain/types.py:127-136` (5 of 7 ADR-0026 fields missing) | ~10 | ✅ `errormodel.py` exists | **Concrete** — actively wrong |
| P-02b | RFC 7807 error envelope inline dicts in `app/api/*.py` (**11 occurrences** across admin/dashboards/views/exports) | ~30 | Partial — `errormodel.py` exists, no FastAPI `raise_http()` helper yet | **Concrete** — but blocked until `raise_http()` helper is added per ADR-0026 §Follow-up #3 |
| P-03 | Pydantic `Settings(BaseSettings)` in `app/config.py` (**25 fields total, 15 net-new**; prior audit said 30 — overcounted) | ~30 | ✅ `settings.make_settings` exists | **Concrete** — lift = enrich `PlatformSettings` then adopt |
| P-04 | `app/auth/jwks.py` (JWKS cache + RS256 decode) | ~85 | ✅ `jwtauth.JWTAuth` exists | **Concrete** — local sets `verify_aud=False` (line 96) — actively wrong; platform sets `audience=client_id` |
| P-05 | `JsonFormatter` + `redact` + `configure_logging` in `app/logging.py` | ~80 | ✗ — new `platform_python.logging` | **Speculative** — 2 Python apps today; lift when 3rd arrives |
| P-06 | `app/events/outbox.py` (`OutboxWriter` + `OutboxPoller`; outbox SQL missing 4 of 11 canonical columns per ADR-0028) | ~120 | ✗ — new `platform_python.outbox` | **Speculative** — 1 Python app writes outbox (reporting-service only) |
| P-07 | `app/events/inbox.py` (dedup) | ~60 | ✗ — new `platform_python.inbox` | **Speculative** — same |
| P-08 | `app/auth/tokens.py` (`decode_bearer`, `require_role`, `require_scope`, `any_role`, `all_roles`, `Principal`) | ~140 | ✗ — new `platform_python.auth` (FastAPI deps on top of `jwtauth.Claims`) | **Speculative** |
| P-09 | `app/observability/metrics.py` (in-memory shim; no literal TODO — just docstring "production code replaces this with prometheus_client" line 41–43) | ~50 | ✗ — new `platform_python.metrics` | **Speculative** |
| P-10 | `app/events/consumer.py` retry/DLQ helper (**3 attempts vs canonical 6 attempts per ADR-0028**) | ~85 | ✗ — new `platform_python.consumer` | **Speculative** |
| P-11 | `fraud-risk-service/app/observability.py` + `app/config.py` (the 2nd Python app — missed by prior audit) | ~150 | Same as P-01 + P-03 | **Concrete** (after P-01 + P-03) |
| **Python subtotal (concrete ~205 + speculative ~535)** | | **~740** | | |

**Drift to resolve before any Python lift:**

- P-02a: local `ErrorEnvelope` is actively wrong. Delete it; route everything through `errormodel.ErrorEnvelope.build`.
- P-04: local decoder sets `verify_aud=False`. Fix to `verify_aud=True` + `audience=settings.oidc_client_id` to match platform.
- P-10: retry count drift (3 vs 6). Fix to canonical per ADR-0028.
- M-01: `reporting-service/app/main.py:36-80` lifespan **does NOT call `platform_python.observability.init_tracer("reporting-service")`** (verified: zero `init_tracer` references in `apps/reporting-service/`). Same for `fraud-risk-service/app/main.py`. OTel is documented as wired but the provider is never created.
- P-05 / P-06 / P-07 / P-08 / P-09: each lift is gated on "lift only when 3rd Python service arrives" to avoid premature abstraction.

## 6. Drift findings — ADRs to file before any deletion

These are the contract-drift items that must be locked by ADR before Phase A begins.

| # | Drift | ADR candidate | Status |
|---|---|---|---|
| ADR-0024 (filed 2026-08-15) | DLQ topic naming: `<topic>.dlq` (8/8 apps, Spring Kafka default) vs `<topic>.DLQ.v1` (platform doc default) | **Resolved** — adopt `<topic>.dlq` | Accepted |
| ADR-0025 (filed 2026-08-15) | Role claim shape: `SCOPE_<lower>` + `ROLE_<upper>` (apps) vs `ROLE_<CLIENT>_<UPPER>` + `SCOPE_<UPPER>` (platform / identity-seeder) | **Resolved** — adopt platform shape | Accepted |
| ADR-0026 (filed 2026-08-15) | RFC 7807 envelope fields: 5 required (`code`/`correlationId`/`traceId`/`spanId`/`timestamp`) + optional (`errors[]`/`downstream`); apps vary (9 apps miss `traceId/spanId/errors[]/timestamp`; Python `reporting-service/app/domain/types.py:127-136` misses 5 of 7) | **Resolved** — adopt platform shape | Accepted |
| ADR-0027 (filed 2026-08-15) | Idempotency schema: canonical `(actor_id, idempotency_key)` UNIQUE; apps have 3 variants (`(scope, idem_key)` × 8, UUID-PK × 3, `(actor_id, idempotency_key)` × 0) | **Resolved** — adopt canonical | Accepted |
| ADR-0028 (filed 2026-08-15) | OutboxEvent schema: 11 canonical columns; apps have 4 distinct shapes across 15 apps + 2 table-name conventions + 2 payload types | **Resolved** — adopt canonical | Accepted |
| ADR-0029 (filed 2026-08-15) | Partition cron: `0 0 2 * * *` (all 11 apps already use this — original "3 distinct crons" claim was wrong) | **Resolved** — adopt `0 0 2 * * *` | Accepted |
| ADR-0030 (filed 2026-08-15) | `request_id` mint: UUIDv7 + MDC (apps vary: 6 use UUIDv4 + request attribute, 9 use UUIDv4 + MDC, platform uses UUIDv7 + MDC) | **Resolved** — adopt UUIDv7 + MDC | Accepted |
| ADR-0031 (filed 2026-08-15) | Testcontainers image tags: `apache/kafka-native:latest` (15 apps) + `postgres:latest` (15 apps) + `redis:latest` (15 apps) vs pinned canonical `confluentinc/cp-kafka:7.5.0` + `postgres:18.0-alpine` + `redis:7.2-alpine` | **Resolved** — adopt canonical pinned versions | Accepted |

**All 8 ADRs are now filed and accepted** (see [`docs/architecture/adrs/0024-dlq-topic-naming.md`](../../docs/architecture/adrs/0024-dlq-topic-naming.md) through [`0031-testcontainers-image-tags.md`](../../docs/architecture/adrs/0031-testcontainers-image-tags.md)). Phase A can begin. The ADR numbers shifted from the original "0020–0027" plan to "0024–0031" because ADRs 0020–0023 already existed ([ADR-0020 polymorphic request_id](../../docs/architecture/adrs/0020-polymorphic-request-id.md), [ADR-0021 21-service with chat](../../docs/architecture/adrs/0021-21-service-architecture-with-chat.md), [ADR-0022 design system shared library](../../docs/architecture/adrs/0022-design-system-shared-library.md), [ADR-0023 Spring Initializr scaffolding](../../docs/architecture/adrs/0023-spring-initializr-scaffolding.md)).

## 7. Suggested sequencing

The full phased plan lives in [`PLAN_PLATFORM_DRY.md`](../../docs/plans/PLATFORM_DRY_AUDIT.md) — but the headline ordering is:

0. **Phase 0 — Annotate platform beans with `@ConditionalOnMissingBean`** — **NEW prerequisite step** discovered by the deep search. The platform has 6 beans that apps should be replacing but that have **no `@ConditionalOnMissingBean` annotation**, so app-local deletions would leave both beans running concurrently. Add `@ConditionalOnMissingBean(name = [...])` to: `platform-spring-boot-observability.MetricsConfiguration.platformMetricsCustomizer`, `platform-spring-boot-api-docs.OpenApiConfiguration.platformOpenApi`, `platform-spring-boot-messaging.KafkaConfiguration.kafkaListenerContainerFactory` + `kafkaTemplate`, `platform-spring-boot-security.SecurityConfiguration.defaultSecurityFilterChain`, `platform-spring-boot-web.WebAutoConfiguration.requestCorrelationFilter`. ~1 hour platform-side PR + bump `spring-boot-starter` from `4.1.0` to `4.1.1`.

1. **Phase A — Quick wins (Tier 1 only)** — ~2 days, ~4,800 LOC removed (verified count is higher than the original estimate: 5 files × 15 apps × ~32 LOC average). Pilot on `customer-service`. Requires ADR-0030 (request_id mint) + ADR-0031 (testcontainers images) + Phase 0.
2. **Phase B — Shared entities (Tier 2)** — ~1 week, ~2,800 LOC removed, requires V__ Flyway migrations on each affected service. Requires ADR-0024 (DLQ) + ADR-0027 (idempotency) + ADR-0028 (outbox).
3. **Phase C — Domain entity migration (Tier 3)** — ~1 week, ~3,730 LOC removed (ApiExceptionHandler + SecurityConfiguration), requires new columns + nullable defaults. Requires ADR-0025 (role claim) + ADR-0026 (RFC 7807).
4. **Phase D — BaseEntity migration + new platform modules (Tier 3/4)** — ~2 weeks, ~5,520 LOC removed (`BaseEntity` migration = 2,100 + `application.yml` `EnvironmentPostProcessor` = 750 + `PartitionMaintenanceStarter` = 620 + `PlatformIdempotencyService` = 400 + `InboxListenerSupport` = 400 + `@ConditionalOnMissingBean` propagation = 250 + `KafkaConsumer` inbox-ingest helper = 400). Single platform release `4.2.0` + per-app PRs.
5. **Phase E — Go** — ~1 week, ~2,460 LOC removed. Pilot `chat-service` first (smallest surface); then `file-service` + `geolocation-service` (identical structure); then `api-gateway` (has the real wiring; should adopt the platform version).
6. **Phase F — Python concrete lifts** — ~3 days, ~205 LOC removed (`requestid`, `ErrorEnvelope`, `verify_aud` fix). Defer speculative lifts.

The total wall-clock estimate for Phases 0–F is **6–8 weeks** if executed sequentially by a single contributor, or **3–4 weeks** if two contributors work Phases A–D in parallel with Phase E.

The total **PR count** is approximately:

- Phase 0: 1 platform PR (single commit to 6 files)
- Phase A: 15 PRs (one per Kotlin app)
- Phase B: 9 PRs (one per affected app) + 9 V__ Flyway migrations
- Phase C: 14 PRs (one per Kotlin app) + platform 1 PR (`BusinessException` adapter)
- Phase D: 1 platform PR (umbrella `4.2.0` release) + 15 app PRs
- Phase E: 4 PRs (chat-service, file-service, geolocation-service, api-gateway) + 1 platform PR (new subpackages)
- Phase F: 2 PRs (one per Python app) + 1 platform PR (`raise_http` helper)

**Total: ~70 PRs** (matches the original plan; corrected counts confirm the scope).

## 8. What this audit does **not** recommend

- **No new top-level packages** beyond the three that exist. Everything proposed lives inside `platform-spring-boot/`, `platform-go/`, or `platform-python/`.
- **No homogenisation of language choices.** Kotlin stays Kotlin, Go stays Go, Python stays Python. The starter modules in each language are independently versioned.
- **No deletion of any documentation.** The companion [`PLAN_PLATFORM_DRY.md`](../../docs/plans/PLATFORM_DRY_AUDIT.md) lives alongside this audit. The PLAN_INDEX.md `Per-service Plans` table is untouched. Per-service `STATUS.md` files are untouched.
- **No changes to the 21-service bounded-context model.** This audit is purely a code-cleanup exercise within the existing 21 apps + 3 packages.
- **No batch deletion PR.** Each app gets its own PR (per the established `service-by-service-graduate-with-checkpoints` feedback).

## 9. References

- [`PLATFORM_BASELINE.md`](./PLATFORM_BASELINE.md) — what the platform libraries are supposed to provide
- [`AUTO_CONFIG.md`](./AUTO_CONFIG.md) — every `@ConditionalOnMissingBean` and `@AutoConfiguration` declaration
- [`MODULES.md`](./MODULES.md) — sub-module breakdown of the 14-module starter
- [`INTEGRATION.md`](./INTEGRATION.md) — how apps wire the starter
- [`CONVENTIONS.md`](./CONVENTIONS.md) — error envelope, correlation IDs, audit events, PII redaction (where the canonical contracts live)
- [`PARTITION_FUNCTIONS.md`](./PARTITION_FUNCTIONS.md) — canonical PL/pgSQL `partman.ensure_partitions` + `drop_expired_partitions` + `partition_health` contract
- [`../MASTER_PLAN.md`](../../docs/MASTER_PLAN.md) — Phase ordering; Phase 8 graduation order
- [`../PLAN_INDEX.md`](../../docs/PLAN_INDEX.md) — 21 per-service PLAN.md files
- [`../adrs/0019-request-id-at-edge.md`](../../docs/architecture/adrs/0019-request-id-at-edge.md) — UUIDv7 + MDC + gateway-as-root contract
- [`../../apps/`](../../apps/) — the 21 apps audited
- [`../../packages/`](../../packages/) — the 3 shared packages audited
