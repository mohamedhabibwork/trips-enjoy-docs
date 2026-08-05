# Modules

`platform-spring-boot-starter` is a **multi-module Gradle build**. Each
sub-module is independently published to the Maven repo so services
can pull in only what they need — but most services import the
umbrella starter and get everything.

## Module layout

```
packages/platform-spring-boot/
├── spring-boot-starter/                  # umbrella starter — what most services import
│   └── depends on: every other module below
│
├── platform-spring-boot-autoconfigure/   # the @AutoConfiguration imports
│   └── registers the rest of the modules
│
├── platform-spring-boot-web/              # HTTP, error model, correlation, logging filters
│   ├── RestTemplateConfig / WebClientConfig
│   ├── ProblemDetailHandler (RFC 7807)
│   ├── CorrelationIdFilter
│   ├── RequestLoggingFilter (PII-redacting)
│   └── GlobalExceptionHandler
│
├── platform-spring-boot-security/         # Keycloak, RBAC, admin-port contract
│   ├── SecurityAutoConfiguration
│   ├── KeycloakResourceServerConfig
│   ├── JwtRoleConverter
│   ├── PlatformAdminFilterChain (/admin/v1/**)
│   ├── PlatformPublicPaths (/healthz, /actuator/health)
│   └── @PreAuthorize helpers (hasAnyAdminRole, hasAnySupportRole, ...)
│
├── platform-spring-boot-data/             # JPA auditing, soft delete, optimistic lock
│   ├── BaseEntity (@MappedSuperclass)
│   ├── AuditingEntityListener
│   ├── SoftDeleteEntity / SoftDeleteRepository
│   ├── OptimisticLockingHelper
│   └── DataSource / HikariCP tuning
│
├── platform-spring-boot-money/            # Money value class + arithmetic
│   ├── @JvmInline value class Money(minor: Long, currency: Currency)
│   ├── BigDecimal ↔ Money converters
│   ├── Arithmetic ops (+, -, *, / with currency check)
│   ├── JSON serializer (Jackson)
│   └── JPA AttributeConverter (PostgreSQL NUMERIC)
│
├── platform-spring-boot-caching/          # Redis (Lettuce) + CacheManager
│   ├── RedisConfig
│   ├── JsonRedisSerializer (consistency across services)
│   ├── CacheManager with consistent key prefix
│   └── @Cacheable advice with property-driven TTL
│
├── platform-spring-boot-messaging/        # Kafka, schema registry, outbox, DLQ
│   ├── KafkaProducerConfig (idempotent, acks=all)
│   ├── KafkaConsumerConfig (EOS, manual ack)
│   ├── SchemaRegistryClient (Apicurio)
│   ├── DefaultErrorHandler + DlqPublishingRecoverer
│   ├── TransactionalOutbox entity + repository
│   ├── OutboxRelay bean (scheduled)
│   └── Avro / JSON Schema helpers
│
├── platform-spring-boot-observability/    # OTel, Micrometer, structured logs
│   ├── OpenTelemetryConfig
│   ├── MicrometerPrometheusConfig (with platform tags)
│   ├── StructuredJsonLogbackConfig
│   ├── TraceContextMdcFilter
│   ├── PlatformHealthIndicators (deps, queue lag, cache, outbox)
│   └── @Timed / @Counted annotations
│
├── platform-spring-boot-audit/            # audit.api.request.v1 + audit.admin.<service>.v1
│   ├── AuditEvent record (Avro / JSON Schema)
│   ├── AuditEventPublisher (Kafka)
│   ├── RequestAuditFilter (emits on every authenticated call)
│   ├── AdminAuditFilter (emits on every /admin/v1/**)
│   ├── PiiRedactor (config-driven redact list)
│   └── AuditTopicResolver
│
├── platform-spring-boot-error/            # error model + codes
│   ├── ErrorResponse (RFC 7807)
│   ├── ErrorCode enum (platform-wide)
│   ├── ErrorMessages (i18n, default EN + AR)
│   ├── BusinessException (with error code)
│   └── ValidationException (with field errors)
│
├── platform-spring-boot-api-docs/         # OpenAPI / Swagger
│   ├── SpringDocConfig
│   ├── BearerAuthSecurityScheme
│   ├── CommonSchemas (ErrorResponse, Money, Page, etc.)
│   ├── ServerUrlConfig (dev / staging / prod)
│   └── OperationCustomizer (auto-tag, auto-describe from annotations)
│
└── platform-spring-boot-test/             # test helpers
    ├── BaseIntegrationTest
    ├── TestKeycloakContainer
    ├── TestPostgresContainer
    ├── TestKafkaContainer
    ├── TestRedisContainer
    ├── TestOutboxRelay (paused)
    ├── JwtTestUtils (mint test tokens)
    ├── MockMvcExtensions
    └── Awaitility helpers
│
└── platform-spring-boot-lookup/           # shared lookup_types + lookups catalog
    ├── LookupAutoConfiguration
    ├── LookupType / Lookup JPA entities (+ Hibernate filters)
    ├── LookupRepository / LookupTypeRepository (Spring Data)
    ├── LookupAdminController (/admin/v1/lookups/**, RBAC-gated)
    ├── LookupPublicController (/v1/lookups/**, is_public only)
    ├── LookupCacheInvalidator (Kafka consumer for platform.lookup.*.v1)
    ├── LookupEventPublisher (outbox-backed)
    ├── liquibase/
    │   ├── lookup-types-changelog.yaml
    │   └── lookups-changelog.yaml
    └── BaseLookupService (for child-table extensions per service)
```

## Who depends on what

Most services import the umbrella starter:

```kotlin
implementation("com.trips-enjoy.platform:spring-boot-starter:4.1.0")
```

A handful of services (mostly Go/Python) only need the artifacts that
define contracts — `platform-spring-boot-error` for the `ErrorResponse`
schema in OpenAPI generation, `platform-spring-boot-money` for the
`Money` JSON shape. These are published as separate artifacts
(`platform-error-model`, `platform-money-model`) so non-JVM consumers
can import just the schema.

| Service type | Imports |
|---|---|
| Business core (Kotlin) | umbrella starter |
| Financial (Kotlin) | umbrella starter + `platform-spring-boot-money` (already included) |
| Edge / Go | `platform-error-model`, `platform-money-model` (JSON Schema only) |
| Edge / Python | same as Go |
| Frontend / BFF (TypeScript) | reads OpenAPI artifact generated by `platform-spring-boot-api-docs` |

## Module dependency rules

```
web       ← (nothing, root)
security  ← web
data      ← web
money     ← web
error     ← web
audit     ← security, error, money
caching   ← web, observability
messaging ← web, observability, error
observability ← web
api-docs  ← web, error, money
lookup    ← web, security, data, caching, messaging, audit
test      ← every module
```

The umbrella starter pulls in all of these transitively. A test
module that depends on the umbrella gets access to every helper.

## Versioning

Every sub-module shares the same version number (aligned with
Spring Boot). A service upgrades all modules together. See
[`VERSIONING.md`](./VERSIONING.md) for the policy.
