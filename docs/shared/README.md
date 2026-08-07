# `platform-spring-boot-starter` — Shared Library

> One dependency, every cross-cutting concern, all 46 Spring Boot services
> in the platform.

The `platform-spring-boot-starter` is a **multi-module Spring Boot 4
starter** that the 58 Kotlin/Spring Boot services in this platform
depend on. It is the single place where cross-cutting concerns live:

- **Web** — error model, correlation IDs, request logging, PII redaction.
- **Security** — Keycloak resource server, RBAC, the `/admin/v1/**`
  contract from [`../services/RECOMMENDATIONS.md` 6](../services/RECOMMENDATIONS.md#6-admin-endpoints--rbac).
- **Data** — JPA auditing, soft-delete, optimistic locking, `Money` value
  class.
- **Observability** — OpenTelemetry, Micrometer, structured JSON logs.
- **Caching** — Redis (Lettuce), `CacheManager` with a consistent JSON
  serializer.
- **Messaging** — Kafka producer/consumer, transactional outbox,
  schema-registry client, DLQ.
- **Audit** — auto-emit `audit.api.request.v1` and
  `audit.admin.<service>.v1`.
- **Money** — `@JvmInline value class Money` with arithmetic and
  serialisation.
- **Error model** — RFC 7807 `application/problem+json`.
- **API docs** — SpringDoc OpenAPI with platform defaults.
- **Test helpers** — `BaseIntegrationTest`, JWT minting, Testcontainers.

Add it to a service with one line; override anything via Spring
properties; everything is documented and tested in one place.

---

## 1. Coordinates

| Field | Value |
|---|---|
| Maven group | `com.trips-enjoy.platform` |
| Starter artifact | `spring-boot-starter` |
| Version | tracks the platform's [Spring Boot 4 line](../services/RECOMMENDATIONS.md#5-cross-cutting-tooling-language-agnostic) (currently `4.1.x`) |
| Java / Kotlin | JDK **25** (min 21) / Kotlin **2.2.x** |
| Spring Boot | **4.x** (latest 4.1.x) |
| Build | Gradle 9 (Kotlin DSL) |
| Source root | `packages/platform-spring-boot/` |

The 9 sub-modules are listed in [`MODULES.md`](./MODULES.md).

---

## 2. Why a shared library

A platform with 46 Spring Boot services that all need Keycloak auth,
OpenTelemetry traces, RFC 7807 errors, Kafka producers, JPA auditing,
and `Money` arithmetic has three options for delivering that:

| Option | Cost | Risk |
|---|---|---|
| Copy-paste per service | Cheap to start, expensive to keep in sync | Drift across services; bug fixes need 46 PRs |
| Framework code review per service | Moderate | Slow; humans miss things; subjective |
| **Shared library + auto-configuration** | One-time investment; cheapest over time | Requires discipline; needs tests; needs deprecation policy |

This repo picks the third option. The shared library is the only
mechanism by which cross-cutting behaviour is added to a service. New
cross-cutting concerns are added **to the library first**, then
adopted by services via a single version bump.

---

## 3. Adding the starter to a service

```kotlin
// services/payment-service/build.gradle.kts
dependencies {
    implementation("com.trips-enjoy.platform:spring-boot-starter:4.1.0")
}
```

That's it. Every cross-cutting concern below is now active for the
service. To opt out or override, see [`AUTO_CONFIG.md`](./AUTO_CONFIG.md).

Full integration walkthrough in [`INTEGRATION.md`](./INTEGRATION.md).

---

## 4. What you get out of the box

| Concern | Default behaviour | Override key |
|---|---|---|
| HTTP error responses | RFC 7807 `application/problem+json` | `platform.error.*` |
| Correlation ID | Read from `X-Request-Id`; generated if missing; put in MDC | `platform.correlation.*` |
| Request logging | Structured JSON; PII fields redacted | `platform.logging.*` |
| AuthN | Keycloak resource server (JWKS cached in Redis) | `spring.security.oauth2.resourceserver.jwt.issuer-uri` |
| AuthZ — public | `GET /healthz`, `GET /actuator/health` only | `platform.security.public-paths` |
| AuthZ — admin | `/admin/v1/**` requires `platform.admin` minimum (per-service) | `platform.security.admin.*` |
| JPA auditing | `BaseEntity` with `createdAt`, `updatedAt`, `version`, `createdBy`, `updatedBy` | `@MappedSuperclass` opt-in |
| Soft delete | `deleted_at TIMESTAMPTZ` on entities that opt in | `platform.data.soft-delete.*` |
| Money | `@JvmInline value class Money(val minor: Long, val currency: Currency)` | always-on |
| OpenTelemetry | Auto-instrumented for HTTP, JDBC, Kafka, R2DBC | `OTEL_*` env vars |
| Micrometer | Prometheus exposition at `/actuator/prometheus` with platform tags | `management.metrics.tags.*` |
| Logs | JSON to stdout; trace context in MDC | `platform.logging.format` |
| Kafka producer | idempotent, acks=all, schema-registry-aware | `spring.kafka.producer.*` |
| Kafka consumer | EOS, manual ack, schema-registry-aware | `spring.kafka.consumer.*` |
| Outbox | Table `outbox_event` + relay bean; EOS guarantee | `platform.outbox.*` |
| Redis | Lettuce, JSON serializer, 30s default TTL | `spring.data.redis.*` |
| Audit — request | `audit.api.request.v1` auto-emitted on every authenticated request | `platform.audit.api.enabled` |
| Audit — admin | `audit.admin.<service>.v1` auto-emitted on every `/admin/v1/**` call | `platform.audit.admin.enabled` |
| OpenAPI | SpringDoc with bearer-auth security scheme, `/v3/api-docs`, Swagger UI at `/swagger-ui.html` | `springdoc.*` |
| Tests | `BaseIntegrationTest` with Postgres + Kafka + Redis + Keycloak | `testImplementation(...)` |
| **Shared catalog** | `lookup_types` + `lookups` table pair, `/admin/v1/lookups/**` admin-port endpoints, `/v1/lookups/**` public read, `platform.lookup.*.v1` events, `LookupCacheInvalidator`, RBAC role wiring | `platform.lookup.*` (see [`LOOKUPS.md`](./LOOKUPS.md)) |

Each row is documented in detail in [`AUTO_CONFIG.md`](./AUTO_CONFIG.md).

---

## 5. What is *not* in the shared library

Things that belong to a *single service* are not in the library:

- **Domain entities** — `PaymentIntent` lives in `payment-service`, not the library.
- **Domain state machines** — Spring Statemachine config is service-specific.
- **External SDK wrappers** — payment-provider SDK, map provider, FCM/APNs/Twilio. These are service-specific adapters.
- **Per-service caches** — the *fact* of caching is generic; the *keys* and *TTLs* are not.
- **Per-service business config** — tariff rules, surge zones, cuisine categories. These live in `configuration-service`.
- **Per-service domain data** — `PaymentIntent`, `Trip`, `FoodOrder`. The *shape* of the shared enumeration catalog is in the library (see [`LOOKUPS.md`](./LOOKUPS.md)); the *rows* that belong to a single bounded context still live in that service's own schema.

The shared library gives every service the *machinery*; each service
contributes its own *content*.

---

## 6. Doc set in this directory

| File | Purpose |
|---|---|
| [`README.md`](./README.md) | This file — overview, coordinates, the one-line install |
| [`MODULES.md`](./MODULES.md) | Sub-module breakdown; what each module exports |
| [`CONVENTIONS.md`](./CONVENTIONS.md) | Error model, correlation IDs, audit events, PII redaction |
| [`AUTO_CONFIG.md`](./AUTO_CONFIG.md) | Every auto-configuration; defaults; override keys |
| [`INTEGRATION.md`](./INTEGRATION.md) | How to add the starter; per-stack differences (Kotlin) |
| [`TESTING.md`](./TESTING.md) | `BaseIntegrationTest`, JWT minting, Testcontainers |
| [`VERSIONING.md`](./VERSIONING.md) | SemVer policy, deprecation rules, upgrade process |
| [`ROADMAP.md`](./ROADMAP.md) | What's in / out / planned |
| [`OSS_DEPENDENCIES.md`](./OSS_DEPENDENCIES.md) | **Open-source dependencies & license attribution** — platform-wide OSS projects + per-language OSS library catalogue with SPDX IDs; per-service OSS bundle index; NOTICE / THIRD-PARTY-LICENSES generation guidance; license compatibility matrix (internal SaaS vs on-prem) |
| [`LOOKUPS.md`](./LOOKUPS.md) | **Shared `lookup_types` + `lookups` catalog** — one pair of tables + one event stream + one admin-port contract used by every service; per-service schema copy, platform-wide stable `code` namespace, hierarchical via `parent_id` self-FK; `platform.lookup.*.v1` event family; RBAC + cache invalidation + extension pattern |
| [`TYPE_CATALOG.md`](./TYPE_CATALOG.md) | **Platform-wide type vocabulary** — ride types (Enjoy Economy / VIP / XL / Comfort / Assist), courier vehicle types, food delivery types, customer and merchant segments; brand label → catalog key → CHECK constraint → `pricing-service.rule_bindings` mapping. Sibling to [`LOOKUPS.md`](./LOOKUPS.md) which defines the underlying catalog mechanism. |

For the platform-wide context, see also
[`../../main.md`](../../main.md) (technology baseline) and
[`../services/RECOMMENDATIONS.md`](../services/RECOMMENDATIONS.md)
(language, framework, admin pattern, version baseline).
