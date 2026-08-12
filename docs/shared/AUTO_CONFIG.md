# Auto-configuration reference

Every auto-configuration the starter provides. Each entry shows:
- **What it does** (default behaviour)
- **Bean(s) registered**
- **Default property keys** (with their defaults)
- **How to override**
- **How to disable**

All auto-configurations are conditional on the relevant Spring Boot
starter being on the classpath. They never fight the user — every
default can be overridden by simply declaring your own bean of the
same type (the `@ConditionalOnMissingBean` rule applies everywhere).

The starter ships an `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`
file that lists every auto-configuration below. Spring Boot picks them
up automatically; no `@EnableAutoConfiguration` is needed in user
code.

---

## 1. Web auto-configuration

**Module**: `platform-spring-boot-web`

### Beans

| Bean | Type | Notes |
|---|---|---|
| `restTemplateBuilder` | `RestTemplateBuilder` | With timeouts, logging interceptor, correlation-id propagation (`X-Request-Id` + alias `X-Correlation-Id` outbound) |
| `webClientBuilder` | `WebClient.Builder` | With exchange filter for correlation + tracing (`X-Request-Id` + alias `X-Correlation-Id` outbound) |
| `problemDetailHandler` | `ProblemDetailHandler` | RFC 7807 `application/problem+json` for all unhandled exceptions |
| `correlationIdFilter` | `OncePerRequestFilter` | Reads `X-Request-Id` (then `X-Correlation-Id` alias) inbound; if both absent, generates UUIDv7. Sets MDC `requestId`, sets the OTel root-span attribute `platform.request_id`, sets **both** `X-Request-Id` and `X-Correlation-Id` as response headers, and stashes the value in a request attribute for the outbound interceptor. See [ADR-0019](../architecture/adrs/0019-request-id-at-the-edge.md). |
| `requestLoggingFilter` | `OncePerRequestFilter` | Structured-JSON request/response log with PII redaction |
| `pIIRedactor` | `PiiRedactor` | Field-name-driven; configurable |
| `globalExceptionHandler` | `RestControllerAdvice` | Maps `BusinessException`, `ValidationException`, `MethodArgumentNotValidException` to RFC 7807 |

### Defaults

| Property | Default | Effect |
|---|---|---|
| `platform.web.request-logging.enabled` | `true` | Log every request and response |
| `platform.web.request-logging.level` | `INFO` | Log level for request/response lines |
| `platform.web.request-logging.include-headers` | `false` | Don't include headers in logs (PII risk) |
| `platform.web.request-logging.include-body` | `false` | Don't include body in logs (PII risk) |
| `platform.web.request-logging.max-body-length` | `4096` | Cap body capture at 4 KB |
| `platform.web.problem-detail.use-expose-headers` | `true` | Include traceId/spanId in `exposeHeaders` |
| `spring.codec.max-in-memory-size` | `1MB` | WebClient max response size |
| `spring.codec.max-in-memory-size` (request) | `256KB` | WebClient max request size |

### Disable

```kotlin
// To disable the request-logging filter
@Bean
fun noOpRequestLoggingFilter(): FilterRegistrationBean<*> =
    FilterRegistrationBean<RequestLoggingFilter>().apply { setEnabled(false) }
```

---

## 2. Security auto-configuration

**Module**: `platform-spring-boot-security`

### Beans

| Bean | Type | Notes |
|---|---|---|
| `securityFilterChain` | `SecurityFilterChain` | Default chain: `/admin/v1/**` requires `platform.admin`; everything else requires authenticated JWT; public paths allowed |
| `adminFilterChain` | `SecurityFilterChain` | Dedicated chain for `/admin/v1/**` with stricter role enforcement |
| `keycloakJwtDecoder` | `JwtDecoder` | NimbusJwtDecoder backed by Keycloak JWKS, cached in Redis |
| `jwtRoleConverter` | `Converter<Jwt, Collection<GrantedAuthority>>` | Maps Keycloak `realm_access.roles` to Spring `ROLE_*` authorities |
| `jwtAuthenticationConverter` | `JwtAuthenticationConverter` | Wraps the role converter |
| `methodSecurityInterceptor` | `MethodInterceptor` | Enables `@PreAuthorize` everywhere |
| `securityObservation` | `ObservationRegistry` | Hooks security into Micrometer |

### Defaults

```yaml
# Keycloak
spring:
  security:
    oauth2:
      resourceserver:
        jwt:
          issuer-uri: ${KEYCLOAK_ISSUER_URI:https://keycloak.cloud.habib.cloud/realms/user}
          jwk-set-uri: ${KEYCLOAK_JWK_SET_URI:${spring.security.oauth2.resourceserver.jwt.issuer-uri}/protocol/openid-connect/certs}

# Custom platform
platform:
  security:
    public-paths:
      - /healthz
      - /actuator/health
      - /actuator/health/**
      - /actuator/prometheus
      - /v3/api-docs/**
      - /swagger-ui/**
      - /swagger-ui.html
    admin:
      base-path: /admin/v1
      min-role: platform.admin   # min role to even hit any /admin/v1/** endpoint
    jwt:
      cache:
        jwks-ttl: 600             # 10 min; longer than Keycloak's default rotation
      clock-skew-seconds: 60
    cors:
      allowed-origins: ${PLATFORM_CORS_ORIGINS:https://admin.platform.trips-enjoy.com,https://console.platform.trips-enjoy.com}
      allowed-methods: [GET, POST, PUT, PATCH, DELETE, OPTIONS]
      allowed-headers: [Authorization, Content-Type, X-Request-Id, X-Correlation-Id, Idempotency-Key]
      max-age: 3600
    csrf:
      enabled: false              # stateless API
```

### Role mapping

| Keycloak claim | Spring authority |
|---|---|
| `realm_access.roles[]` | `ROLE_<UPPER>` |
| `resource_access.<client>.roles[]` | `ROLE_<CLIENT>_<UPPER>` (for client-scoped roles) |

The role names in code: `@PreAuthorize("hasRole('platform.admin')")`
(Keycloak role `platform.admin` becomes Spring authority
`ROLE_platform.admin`).

### `@PreAuthorize` helpers

The library ships custom expressions for the most common checks:

```kotlin
@PreAuthorize("@platformSecurity.hasAnyAdminRole(authentication)")
fun forceCapture(...) { ... }

@PreAuthorize("@platformSecurity.hasAnySupportRole(authentication, 'reason_code_param')")
fun getCustomerContext(...) { ... }
```

`hasAnySupportRole` enforces that the call includes a `reason_code`
parameter; throws `BusinessException(ErrorCode.MISSING_REASON_CODE)`
if not. The reason_code is recorded in the audit event.

### Disable

To skip the default `SecurityFilterChain` (e.g. for a service that
needs a fully custom security setup):

```yaml
platform:
  security:
    enabled: false
```

---

## 3. Data JPA auto-configuration

**Module**: `platform-spring-boot-data`

### Beans

| Bean | Type | Notes |
|---|---|---|
| `dataSource` | `HikariDataSource` | Sensible HikariCP defaults for the platform |
| `liquibase` or `flyway` | depending on what's on classpath | Migrations runner |
| `jpaAuditingHandler` | `AuditingHandler` | Populates `createdAt`, `updatedAt`, `createdBy`, `updatedBy` |
| `auditorProvider` | `AuditorAware<String>` | Pulls `sub` from the current JWT |
| `transactionManager` | `PlatformTransactionManager` | With `@Transactional` event publishing to outbox |
| `jacksonObjectMapper` | `ObjectMapper` | JavaTime, Jdk8, PII-redaction modules |

### Defaults

```yaml
spring:
  datasource:
    hikari:
      maximum-pool-size: 20
      minimum-idle: 5
      connection-timeout: 30000
      max-lifetime: 1800000
      pool-name: ${spring.application.name}-pool
  jpa:
    open-in-view: false
    hibernate:
      ddl-auto: validate   # never auto-create; migrations only
    properties:
      hibernate:
        jdbc.time_zone: UTC
        format_sql: false
        jdbc.batch_size: 50
        order_inserts: true
        order_updates: true
```

### `BaseEntity` (in the library)

```kotlin
@MappedSuperclass
@EntityListeners(AuditingEntityListener::class)
abstract class BaseEntity {
    @Id
    var id: UUID = UUID.randomUUID()

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    lateinit var createdAt: Instant

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    lateinit var updatedAt: Instant

    @CreatedBy
    @Column(name = "created_by", nullable = false, updatable = false)
    lateinit var createdBy: String  // JWT sub

    @LastModifiedBy
    @Column(name = "updated_by", nullable = false)
    lateinit var updatedBy: String  // JWT sub

    @Version
    @Column(name = "version", nullable = false)
    var version: Long = 0

    @Column(name = "deleted_at")
    var deletedAt: Instant? = null  // soft-delete; null = live
}
```

### Disable auditing

Don't extend `BaseEntity`. Or, for selective fields, set
`@CreatedDate` etc. on your own fields and the listener will populate
them.

### Soft-delete queries

The library provides `SoftDeleteRepository` that auto-filters
`deleted_at IS NULL`:

```kotlin
interface PaymentRepository : SoftDeleteRepository<Payment, UUID> {
    fun findByIdempotencyKey(key: String): Payment?
}
```

To include soft-deleted rows: `findByIdIncludingDeleted(id)`.

---

## 4. Money auto-configuration

**Module**: `platform-spring-boot-money`

### Beans

| Bean | Type | Notes |
|---|---|---|
| `moneyConverter` | `MoneyConverter` | JPA `AttributeConverter<Money, BigDecimal>` for `NUMERIC(19, 4)` |
| `moneyJsonSerializer` | `JsonSerializer<Money>` | Jackson serialiser — `{"amount":"19.99","currency":"USD"}` |
| `moneyJsonDeserializer` | `JsonDeserializer<Money>` | Jackson deserialiser |
| `moneyArithmeticService` | `MoneyArithmeticService` | `+`, `-`, `*`, `/`, `convertTo(currency)` with FX from `configuration-service` |

### API

```kotlin
@JvmInline
value class Money private constructor(
    val minor: Long,
    val currency: Currency,    // ISO 4217
) {
    companion object {
        fun of(amount: String, currency: String): Money = ...
        fun ofMinor(minor: Long, currency: String): Money = ...
        val ZERO_USD: Money = ofMinor(0, "USD")
    }

    operator fun plus(other: Money): Money { ... }     // rejects mixed currencies
    operator fun minus(other: Money): Money { ... }
    operator fun times(multiplier: Long): Money { ... }
    operator fun div(divisor: Long): Money { ... }
    fun convertTo(targetCurrency: String): Money      // FX from configuration-service
    fun toBigDecimal(): BigDecimal                    // for display only
}

fun BigDecimal.toMoney(currency: String): Money = ...
fun Long.toMoney(currency: String): Money = ...        // treats as minor units
```

### Override

The library doesn't expose knobs — `Money` is a value class. FX rates
are read from `configuration-service` (with a 5-minute cache). The
fallback when `configuration-service` is unavailable is the rates
embedded in the library at build time, with a warning log.

---

## 5. Caching auto-configuration

**Module**: `platform-spring-boot-caching`

### Beans

| Bean | Type | Notes |
|---|---|---|
| `redisConnectionFactory` | `LettuceConnectionFactory` | Single instance, configured from `spring.data.redis.*` |
| `redisTemplate` | `RedisTemplate<String, Any>` | With platform's `JsonRedisSerializer` |
| `cacheManager` | `RedisCacheManager` | With consistent key prefix, default TTL 30s |
| `keyGenerator` | `KeyGenerator` | `ClassName:methodName:arg1Hash:arg2Hash:...` |

### Defaults

```yaml
spring:
  data:
    redis:
      host: ${REDIS_HOST:redis.platform.svc.cluster.local}
      port: 6379
      timeout: 2000ms
      connect-timeout: 2000ms
      lettuce:
        pool:
          max-active: 16
          max-idle: 8
          min-idle: 2

platform:
  cache:
    default-ttl: 30s
    key-prefix: ${spring.application.name}:
    value-serializer: com.trips-enjoy.platform.cache.JsonRedisSerializer
```

### Per-cache TTL

```kotlin
@Cacheable(cacheNames = ["tariff"], key = "#cityId")
fun getTariff(cityId: UUID): Tariff { ... }
```

```yaml
platform:
  cache:
    configs:
      tariff:
        ttl: 60s
        prefix: pricing:
```

---

## 6. Messaging auto-configuration

**Module**: `platform-spring-boot-messaging`

### Beans

| Bean | Type | Notes |
|---|---|---|
| `kafkaTemplate` | `KafkaTemplate<String, Any>` | With schema-registry-aware serialiser |
| `producerFactory` | `DefaultKafkaProducerFactory` | Idempotent, acks=all, compression=lz4 |
| `consumerFactory` | `DefaultKafkaConsumerFactory` | EOS, manual ack, isolation=read_committed |
| `schemaRegistryClient` | `ApicurioRegistryClient` | Talks to the Apicurio schema registry |
| `kafkaListenerContainerFactory` | `ConcurrentKafkaListenerContainerFactory` | With default error handler + DLQ |
| `defaultErrorHandler` | `DefaultErrorHandler` | 3 retries with backoff; then DLQ |
| `dlqRecoverer` | `DlqPublishingRecoverer` | Publishes to `<original>.DLQ.v1` |
| `outboxRelay` | `OutboxRelay` | Scheduled job; reads `outbox_event`; publishes to Kafka |
| `outboxRepository` | `OutboxEventRepository` | Custom Spring Data repo |

### Defaults

```yaml
spring:
  kafka:
    bootstrap-servers: ${KAFKA_BOOTSTRAP:kafka.platform.svc.cluster.local:9092}
    producer:
      acks: all
      properties:
        enable.idempotence: true
        compression.type: lz4
        max.in.flight.requests.per.connection: 5
        delivery.timeout.ms: 120000
    consumer:
      enable-auto-commit: false
      auto-offset-reset: earliest
      isolation-level: read_committed
      properties:
        spring.json.trusted.packages: com.trips-enjoy.platform.events

platform:
  messaging:
    schema-registry:
      url: ${SCHEMA_REGISTRY_URL:http://apicurio.platform.svc.cluster.local:8080}
    outbox:
      poll-interval: 100ms
      batch-size: 200
      topic-prefix: ''            # events emitted with the topic from the row
    dlq:
      topic-suffix: .DLQ.v1
    retry:
      max-attempts: 3
      backoff-ms: 500
```

### Transactional outbox

```kotlin
@Service
class CapturePaymentService(
    private val outbox: OutboxRepository,
    private val paymentRepo: PaymentRepository,
) {
    @Transactional
    fun capture(paymentId: UUID) {
        val payment = paymentRepo.findById(paymentId).orElseThrow()
        payment.capturedAt = Instant.now()
        paymentRepo.save(payment)

        outbox.append(
            topic = "payment.intent.captured.v1",
            key = paymentId.toString(),
            payload = PaymentCapturedEvent(paymentId, payment.amount, payment.currency),
        )
        // ↑ This row + the payment update are in the SAME DB transaction.
        // ↑ Either both commit, or neither does.
    }
}
```

The `OutboxRelay` (scheduled) reads the table and publishes to Kafka.
The publish is at-least-once; consumers must be idempotent.

### Disable outbox

```yaml
platform:
  messaging:
    outbox:
      enabled: false
```

Then services can publish directly via `KafkaTemplate.send(...)`.
You lose the atomicity guarantee.

---

## 7. Observability auto-configuration

**Module**: `platform-spring-boot-observability`

### Beans

| Bean | Type | Notes |
|---|---|---|
| `openTelemetry` | `OpenTelemetry` | SDK with OTLP exporter, resource attributes |
| `tracer` | `Tracer` | For `@WithSpan` and manual spans |
| `observationRegistry` | `ObservationRegistry` | Hooks into Micrometer |
| `meterRegistry` | `MeterRegistry` | Prometheus + common tags |
| `platformHealthContributor` | `HealthContributor` | Aggregates: DB, Kafka, Redis, outbox lag |
| `jsonLogbackEncoder` | `LogbackEncoder` | Structured JSON to stdout |
| `traceContextMdcFilter` | `OncePerRequestFilter` | Puts traceId/spanId in MDC |

### Defaults

```yaml
management:
  endpoints:
    web:
      exposure:
        include: health, info, prometheus
  endpoint:
    health:
      show-details: when_authorized
      probes:
        enabled: true
  metrics:
    tags:
      service: ${spring.application.name}
      env: ${PLATFORM_ENV:dev}
      region: ${PLATFORM_REGION:local}
    distribution:
      percentiles-histogram:
        http.server.requests: true
      percentiles:
        http.server.requests: 0.5, 0.9, 0.95, 0.99
  otlp:
    tracing:
      endpoint: ${OTEL_EXPORTER_OTLP_ENDPOINT:http://otel-collector.observability.svc:4318/v1/traces}
    metrics:
      endpoint: ${OTEL_EXPORTER_OTLP_ENDPOINT:http://otel-collector.observability.svc:4318/v1/metrics}

platform:
  observability:
    resource-attributes:
      service.namespace: platform
      service.version: ${SERVICE_VERSION:0.0.0-local}
    sampling:
      probability: 1.0          # 100% in dev; lower in prod via env
```

### Custom spans

```kotlin
@WithSpan("payment.capture")
fun capture(paymentId: UUID) {
    // The span is auto-created; attributes are added via @SpanAttribute
}
```

```kotlin
@WithSpan
fun process(@SpanAttribute("paymentId") paymentId: UUID) { ... }
```

---

## 8. Audit auto-configuration

**Module**: `platform-spring-boot-audit`

Cross-references: [`../services/RECOMMENDATIONS.md` 6.6](../services/RECOMMENDATIONS.md#66-audit-log)

### Beans

| Bean | Type | Notes |
|---|---|---|
| `auditEventPublisher` | `AuditEventPublisher` | Wraps the outbox — events are written to `outbox_event` with topic `audit.api.request.v1` or `audit.admin.<service>.v1` |
| `requestAuditFilter` | `OncePerRequestFilter` | After the security filter; emits `audit.api.request.v1` for every authenticated request |
| `adminAuditFilter` | `OncePerRequestFilter` | After the admin security filter; emits `audit.admin.<service>.v1` for every `/admin/v1/**` call |
| `piiRedactor` | `PiiRedactor` | Field-name-driven; applied to captured bodies before emit |
| `auditTopicResolver` | `AuditTopicResolver` | Resolves `audit.api.request.v1` vs `audit.admin.<service>.v1` based on path |

### Defaults

See [`CONVENTIONS.md` 3](./CONVENTIONS.md#3-audit-emission).

### Disable

```yaml
platform:
  audit:
    api:
      enabled: false
    admin:
      enabled: false
```

---

## 9. Error model auto-configuration

**Module**: `platform-spring-boot-error`

### Beans

| Bean | Type | Notes |
|---|---|---|
| `errorResponseBuilder` | `ErrorResponseBuilder` | Builds RFC 7807 responses |
| `errorCodeRegistry` | `ErrorCodeRegistry` | All `ErrorCode`s from the library + service-contributed |
| `errorMessageSource` | `MessageSource` | i18n error messages (EN + AR) |
| `businessExceptionHandler` | `ExceptionHandler` | Maps `BusinessException` → `ProblemDetail` |
| `validationExceptionHandler` | `ExceptionHandler` | Maps `MethodArgumentNotValidException` → `ProblemDetail` with `errors[]` |

### Defaults

i18n bundles ship in `platform-spring-boot-error/src/main/resources/i18n/`:
- `errors.properties` (default English)
- `errors_ar.properties` (Arabic)
- `errors_fr.properties` (French) — future

---

## 10. API docs auto-configuration

**Module**: `platform-spring-boot-api-docs`

### Beans

| Bean | Type | Notes |
|---|---|---|
| `openApi` | `OpenAPI` | SpringDoc-customised with platform defaults |
| `bearerAuthScheme` | `SecurityScheme` | OAuth2 / Keycloak bearer token |
| `commonSchemas` | `GroupedOpenApi` | `ErrorResponse`, `Money`, `Page`, `Idempotency-Key` |
| `serverUrlConfig` | `Server` list | dev / staging / prod URLs from `application.yml` |

### Defaults

```yaml
springdoc:
  api-docs:
    path: /v3/api-docs
  swagger-ui:
    path: /swagger-ui.html
    operations-sorter: alpha
    tags-sorter: alpha
  show-actuator: false

platform:
  api-docs:
    title: ${spring.application.name} API
    description: Auto-generated API docs for ${spring.application.name}
    contact: platform-api@uber.com
    servers:
      - url: https://${spring.application.name}.platform.trips-enjoy.com
        description: production
      - url: https://${spring.application.name}.staging.platform.trips-enjoy.com
        description: staging
      - url: http://localhost:8080
        description: local
```

### Adding service-specific docs

```kotlin
@Operation(summary = "Capture a payment", description = "...")
@PreAuthorize("hasRole('platform.finance')")
@PostMapping("/v1/payments/{id}/capture")
fun capture(@PathVariable id: UUID): PaymentResponse { ... }
```

SpringDoc picks up the annotations automatically; the starter applies
common tags (`service: payment-service`) and the bearer-auth scheme.

---

## 11. Test auto-configuration

**Module**: `platform-spring-boot-test` (`testImplementation` scope)

Documented in [`TESTING.md`](./TESTING.md).

## Related docs

- [`../shared/PLATFORM_BASELINE.md`](../shared/PLATFORM_BASELINE.md) — single source for PostgreSQL 19, Kafka, Keycloak, etc.
- [`../shared/CONVENTIONS.md`](../shared/CONVENTIONS.md) — code conventions and naming
- [`../architecture/SYSTEM_OVERVIEW.md`](../architecture/SYSTEM_OVERVIEW.md) — plain-English platform summary
