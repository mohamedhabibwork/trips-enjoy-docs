# Integration guide

How a Spring Boot service adopts `platform-spring-boot-starter`. Most
of this is "add one line; everything else is automatic." The rest is
what to do *after* — opt in to features, override defaults, write
service-specific code against the conventions.

> **Scaffolding.** This guide assumes the service was already
> scaffolded from Spring Initializr using the canonical recipe in
> [`../services/SPRING_INITIALIZR.md`](../services/SPRING_INITIALIZR.md)
> (per [ADR-0023](../architecture/adrs/0023-spring-initializr-scaffolding.md)).
> The Initializr scaffold supplies the generic Spring Boot 4
> build; this guide covers **what to add on top** to make the
> scaffold a `trips-enjoy` service.

---

## 1. The one-line install

```kotlin
// services/<your-service>/build.gradle.kts
dependencies {
    implementation("com.trips-enjoy.platform:spring-boot-starter:4.1.0")
}
```

That's it. The starter pulls in:
- Spring Boot 4 + Spring Framework 7 + Spring Security 7
- Hibernate 7, jOOQ 3.20 (transitively, only if you opt in via `jOOQ`),
  HikariCP, Spring Kafka 4
- Lettuce (Redis), Micrometer, OpenTelemetry SDK
- Apicurio schema-registry client, Flyway 11
- Every Kotlin coroutine + reactor starter you might need

Service-specific deps (`payment-provider-sdk`, `mapbox-sdk`, `stripe`)
are added in the service's own `build.gradle.kts`, not the starter.

---

## 2. Standard service `build.gradle.kts`

The platform ships a Gradle **convention plugin** (`platform-conventions`)
that applies the common setup. Services apply the plugin and add only
the service-specific pieces.

```kotlin
// services/payment-service/build.gradle.kts
plugins {
    id("platform-spring-boot-conventions")
    kotlin("plugin.spring") version "2.4.10"
}

dependencies {
    // The starter
    implementation("com.trips-enjoy.platform:spring-boot-starter:4.1.0")

    // Service-specific
    implementation("com.stripe:stripe-java:24.0.0")

    // Tests
    testImplementation("com.trips-enjoy.platform:platform-spring-boot-test:4.1.0")
    testImplementation("org.testcontainers:postgresql")
    testImplementation("org.testcontainers:kafka")
    testImplementation("org.testcontainers:junit-jupiter")
}
```

The convention plugin sets up:
- Java 25 toolchain
- Kotlin 2.4.x with the Spring compiler plugin
- Spring Boot 4 dependency-management BOM
- Test sources, test logging, JUnit 5
- `ktlint` + `detekt` + the platform's rules
- OTel agent config
- Docker image build via `jib` or `bootBuildImage`
- Version catalogs in `gradle/libs.versions.toml`

---

## 3. Standard service `application.yml`

A minimal config — most of the platform defaults work out of the box:

```yaml
spring:
  application:
    name: payment-service   # becomes the service name, audit topic suffix, log tag, etc.

  datasource:
    url: jdbc:postgresql://${DB_HOST:postgres.payment.svc.cluster.local}:5432/payment
    username: ${DB_USER:payment}
    password: ${DB_PASSWORD}

  jpa:
    database-platform: org.hibernate.dialect.PostgreSQLDialect

  flyway:
    enabled: true
    locations: classpath:db/migration

platform:
  api-docs:
    title: Payment Service API
```

If the service needs to override a default, add the property under
`platform.<area>.*`. See [`AUTO_CONFIG.md`](./AUTO_CONFIG.md) for the
full list.

---

## 4. Standard service package layout

```kotlin
package com.uber.payment

// 1. The application
@SpringBootApplication
class PaymentApplication

fun main(args: Array<String>) {
    runApplication<PaymentApplication>(*args)
}

// 2. The aggregate root
@Entity
@Table(name = "payment_intent")
class PaymentIntent(
    @EmbeddedId
    var id: PaymentId = PaymentId(UUID.randomUUID()),
    @Embedded
    var money: Money = Money.ZERO_USD,
    @Enumerated(EnumType.STRING)
    var status: PaymentStatus = PaymentStatus.PENDING,
) : BaseEntity()  // <-- from the library

// 3. The command service (writes)
@Service
class PaymentCommandService(
    private val repo: PaymentIntentRepository,
    private val outbox: OutboxRepository,
) {
    @Transactional
    fun capture(id: PaymentId) {
        val payment = repo.findById(id).orElseThrow {
            BusinessException(ErrorCode.PAYMENT_NOT_FOUND, "No payment '$id'.")
        }
        payment.status = PaymentStatus.CAPTURED
        repo.save(payment)

        outbox.append(
            topic = "payment.intent.captured.v1",
            key = id.value.toString(),
            payload = PaymentCapturedEvent(id, payment.money),
        )
    }
}

// 4. The query service (reads)
@Service
class PaymentQueryService(
    private val repo: PaymentIntentRepository,
) {
    @Cacheable(cacheNames = ["payment"], key = "#id")
    fun get(id: PaymentId): PaymentIntent =
        repo.findById(id).orElseThrow {
            BusinessException(ErrorCode.PAYMENT_NOT_FOUND, "No payment '$id'.")
        }
}

// 5. The REST controller
@RestController
@RequestMapping("/v1/payments")
class PaymentController(
    private val commands: PaymentCommandService,
    private val queries: PaymentQueryService,
) {
    @GetMapping("/{id}")
    fun get(@PathVariable id: UUID): PaymentResponse =
        queries.get(PaymentId(id)).toResponse()

    @PostMapping("/{id}/capture")
    fun capture(@PathVariable id: UUID): PaymentResponse =
        commands.capture(PaymentId(id)).toResponse()
}

// 6. The admin controller
@RestController
@RequestMapping("/admin/v1/payments")
@PreAuthorize("hasAnyRole('platform.admin', 'platform.finance', 'payment.admin')")
class PaymentAdminController(...) { ... }
```

That's the full skeleton. Notice the patterns:
- `@Transactional` for writes; `@Cacheable` for reads.
- `BusinessException` for domain errors → automatic RFC 7807.
- `OutboxRepository.append(...)` to emit events transactionally.
- All controllers are `@RestController`, no XML config, no manual
  serialization.

---

## 5. Standard service migrations

```sql
-- services/payment-service/src/main/resources/db/migration/V1__init.sql
CREATE TABLE payment_intent (
    id              UUID PRIMARY KEY,
    amount_minor    BIGINT NOT NULL,
    currency        CHAR(3) NOT NULL,
    status          VARCHAR(32) NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL,
    updated_at      TIMESTAMPTZ NOT NULL,
    created_by      VARCHAR(255) NOT NULL,
    updated_by      VARCHAR(255) NOT NULL,
    version         BIGINT NOT NULL DEFAULT 0,
    deleted_at      TIMESTAMPTZ
);

CREATE INDEX idx_payment_intent_status ON payment_intent (status) WHERE deleted_at IS NULL;
```

The library's `BaseEntity` enforces the audit and soft-delete columns.
You don't write those by hand.

---

## 6. What to do when you need a new cross-cutting feature

The library is the single place for cross-cutting code. **Do not
duplicate.** The process:

1. Open an issue: `[shared] <feature>`.
2. If the platform team agrees, they add it to the library behind a
   property (default ON; service can opt out).
3. Service teams upgrade to the new library version.

If you find yourself copy-pasting a class into your service, **stop
and add it to the library first**.

---

## 7. Per-stack differences

This document is for the **Kotlin / Spring Boot 4** stack. The
**Go** and **Python** stacks are out of scope for the starter — they
are documented in each service's `TECH.md`. They share the platform's
**contracts** (the JSON schemas, the audit topic, the error model)
but not the code.

| Concern | Kotlin (starter) | Go | Python |
|---|---|---|---|
| Error model | `ProblemDetail` (RFC 7807) | `ErrorResponse` (RFC 7807) | `ErrorResponse` (RFC 7807) |
| Audit | `audit.api.request.v1` via outbox | Same topic, published directly | Same topic, published via outbox |
| Correlation | `X-Request-Id` (alias `X-Correlation-Id`) + OTel attr `platform.request_id` | Same | Same |
| Money | `Money` value class | `money-go` package (auto-generated from JSON Schema) | `pydantic` model |
| OpenAPI | SpringDoc | `oapi-codegen` from the same spec | `pydantic-openapi` from the same spec |

The schemas (`ErrorResponse`, `Money`, `Page`, `Idempotency-Key`) are
generated once from a canonical source and consumed by all three
stacks. See the `platform-schemas/` repo (separate).

## Related docs

- [`../shared/PLATFORM_BASELINE.md`](../shared/PLATFORM_BASELINE.md) — single source for PostgreSQL 19, Kafka, Keycloak, etc.
- [`../shared/CONVENTIONS.md`](../shared/CONVENTIONS.md) — code conventions and naming
- [`../architecture/SYSTEM_OVERVIEW.md`](../architecture/SYSTEM_OVERVIEW.md) — plain-English platform summary
