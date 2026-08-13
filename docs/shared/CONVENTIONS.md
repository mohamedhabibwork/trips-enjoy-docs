# Conventions

The shared library enforces (and the 15 Spring Boot 4 services follow)
a small set of conventions. Every convention is *opt-out* via Spring
properties; the defaults are correct for the platform.

---

## 1. Error model — RFC 7807

All HTTP error responses use RFC 7807 `application/problem+json`,
extended with a `downstream` block when the error originates from
another service. The body shape:

```json
{
  "type": "https://platform.trips-enjoy.com/errors/payment-not-found",
  "title": "Payment not found",
  "status": 404,
  "detail": "No payment exists with id 'abc123' for this tenant.",
  "instance": "/v1/payments/abc123",
  "code": "PAYMENT_NOT_FOUND",
  "traceId": "8f4a9b2c1d0e3f5a6b7c8d9e0f1a2b3c",
  "spanId": "1a2b3c4d5e6f7a8b",
  "timestamp": "2026-07-29T07:12:34.567Z",
  "errors": [
    { "field": "amount", "message": "must be greater than 0", "code": "MIN_VALUE" }
  ]
}
```

| Field | Required | Source |
|---|---|---|
| `type` | yes | URL to a documentation page for this error class |
| `title` | yes | Human-readable summary, from `ErrorMessages` (i18n) |
| `status` | yes | HTTP status code |
| `detail` | yes | Service-specific human-readable detail |
| `instance` | yes | The URI that produced the error |
| `code` | yes | Stable machine identifier (SCREAMING_SNAKE_CASE) |
| `traceId` | yes | OpenTelemetry trace id |
| `spanId` | yes | OpenTelemetry span id |
| `timestamp` | yes | ISO-8601 UTC |
| `errors` | no | Array of field-level errors (validation only) |

### Error code → HTTP status mapping

The `ErrorCode` enum (in `platform-spring-boot-error`) maps each
machine identifier to an HTTP status, a default title, and a default
i18n message. Services throw `BusinessException(ErrorCode.X, ...)` and
the framework handles the rest.

```kotlin
throw BusinessException(
    ErrorCode.PAYMENT_NOT_FOUND,
    "No payment exists with id '$id' for this tenant.",
    params = mapOf("paymentId" to id),
)
```

Results in a 404 with the body above.

### Per-module conventions

- All `404`s use `RESOURCE_NOT_FOUND` unless the service has a more specific code.
- All `409`s use `CONFLICT` (e.g. duplicate idempotency key).
- All `422`s use `VALIDATION_FAILED` with `errors[]` populated.
- All `503`s come from the health endpoint or a circuit breaker.

### i18n

Error messages support `Accept-Language`. Default locale is `en`; the
platform also ships `ar` (Arabic, RTL). The framework picks the locale
from the request header, falling back to `en`.

### The `downstream` block (when the error is from another service)

When a service's error is caused by a downstream service (rather than
its own code), it MUST include a `downstream` block identifying the
original source:

```json
{
  "code": "DEPENDENCY_UNAVAILABLE",
  "message": "We can't process your request right now. Please try again.",
  "correlationId": "01HZX9C7T0XK2P9F0V6E4B1MZA",
  "traceId": "8f4a9b2c1d0e3f5a6b7c8d9e0f1a2b3c",
  "downstream": {
    "service": "payment-service",
    "code": "CIRCUIT_OPEN",
    "status": 503,
    "traceId": "8f4a9b2c1d0e3f5a6b7c8d9e0f1a2b3c",
    "latency_ms": 1,
    "attempt": 3
  }
}
```

The full catalog of codes, propagation rules, and per-class rules is
in
[`../architecture/DOWNSTREAM_ERROR_CATALOG.md`](../architecture/DOWNSTREAM_ERROR_CATALOG.md).
The decision of **whether to forward verbatim, translate, degrade, or
reject** when a downstream returns an error lives there.

### Shared error codes (every service supports these)

| Code | Status | Meaning |
|---|---|---|
| `VALIDATION_FAILED` | 400 | Schema/field error (populates `errors[]`) |
| `UNAUTHENTICATED` | 401 | Missing/invalid bearer |
| `FORBIDDEN` | 403 | RBAC denied |
| `NOT_FOUND` | 404 | Generic resource not found |
| `CONFLICT` | 409 | State conflict |
| `IDEMPOTENCY_KEY_REUSED` | 422 | Same key, different body |
| `RATE_LIMITED` | 429 | Token bucket exhausted |
| `BUSINESS_RULE_VIOLATION` | 422 | Domain rule broken |
| `STATE_INVALID` | 409 | State machine doesn't allow the transition |
| `INTERNAL_ERROR` | 500 | This service's own code failed unexpectedly |
| `DEPENDENCY_UNAVAILABLE` | 503 | CRITICAL downstream is down or circuit-open (MUST include `downstream`) |
| `DEPENDENCY_TIMEOUT` | 504 | Downstream timed out after retries (MUST include `downstream`) |
| `BAD_GATEWAY` | 502 | Downstream returned unparseable response |
| `CIRCUIT_OPEN` | 503 | The outbound call's circuit breaker is open |
| `BULKHEAD_FULL` | 503 | The outbound call's bulkhead pool is exhausted |

Service-specific codes (`RIDE_REQUEST_CUSTOMER_SUSPENDED`,
`PAYMENT_CARD_DECLINED`, etc.) are added to the catalog on a per-service
basis. The complete catalog, propagation rules, and translation tables
are in
[`../architecture/DOWNSTREAM_ERROR_CATALOG.md`](../architecture/DOWNSTREAM_ERROR_CATALOG.md).

---

## 2. Correlation IDs

Every request gets a **request id** (a.k.a. correlation id) that
flows from the edge to every downstream service call, every
emitted event, every log line, and every OpenTelemetry span. The
canonical contract is [ADR-0019](../architecture/adrs/0019-request-id-at-the-edge.md);
this section is the shared library's implementation of that
contract.

`X-Request-Id` and `X-Correlation-Id` are **aliases**: clients may
send either, the gateway prefers `X-Request-Id` if both are sent,
and every downstream service writes **both** to outbound calls,
events, logs, and OTel so a back-compat consumer reading either
header still gets the same value.

| Where | Behaviour |
|---|---|
| Inbound header | Read `X-Request-Id`; if absent, read `X-Correlation-Id`; if both absent, generate a UUIDv7 |
| MDC | Put under key `requestId`; every JSON log line carries it |
| Outbound HTTP | Add **both** `X-Request-Id` and `X-Correlation-Id` to every `RestTemplate` / `WebClient` call (via interceptor) |
| Outbound Kafka | Set Kafka headers `X-Request-Id` **and** `X-Correlation-Id` on every produced message |
| Response | Return as **both** `X-Request-Id` and `X-Correlation-Id` response headers (same value) |
| OpenTelemetry | Bound to the request's root span as the attribute `platform.request_id`; the OTel `trace_id` (W3C `traceparent`) is **distinct** from the request id — one request has one request id and one trace id, but they are not the same value |
| Retry | The id is **stable** — a retried request keeps the same id; the audit topic is partitioned by `correlation_id` so the request lands on the same partition |

### Root of the request

The first hop that touches a request is the **API gateway**. The
gateway is the canonical root that accepts or generates the
request id; every downstream service inherits the value via the
shared library's `correlationIdFilter`. The notification-service
outbox is idempotent on this id (see
`services/notification-service/WORKFLOWS.md` line 499), and the
audit topic's partition key is this id — both contracts are now
load-bearing **by spec**, not by coincidence.

### Trace propagation

OpenTelemetry context is propagated:
- HTTP: `traceparent` and `tracestate` headers (W3C Trace Context)
- Kafka: headers `traceparent` + `tracestate` (the `propagators=kafka` OTel config)
- Async (coroutines / `@Async`): captured at submit time, restored at execute time

The W3C `traceparent` is the OTel **trace id**; the
`X-Request-Id` (alias `X-Correlation-Id`) is the business
**request id**. Both ride on the same request; the trace id opens
the trace UI; the request id ties a log line, an audit event, a
downstream call, and a Kafka message together.

### Override

```yaml
platform:
  correlation:
    primary-header: X-Request-Id     # default; legacy alias X-Correlation-Id is always accepted inbound and set outbound
    alias-header: X-Correlation-Id   # default; accepted inbound, set outbound
    mdc-key: requestId               # default
    propagate-outbound: true         # default
    kafka-headers: [X-Request-Id, X-Correlation-Id]   # both set on every produced message
```

To disable MDC propagation (e.g. for a stateless read-only path):
set `platform.correlation.mdc-key=` (empty).

To switch the canonical header (advanced; not recommended):
`platform.correlation.primary-header: X-Anything`. The alias
header is still accepted and set unless overridden via
`platform.correlation.alias-header=`.

---

## 3. Audit emission

The library auto-emits two audit event topics, per the platform
contract in
[`../services/RECOMMENDATIONS.md` 6.6](../services/RECOMMENDATIONS.md#66-audit-log).

### `audit.api.request.v1` — every authenticated request

| Field | Source |
|---|---|
| `audit_id` | UUIDv7, generated |
| `timestamp` | now (UTC) |
| `actor_id` | JWT `sub` claim |
| `actor_username` | JWT `preferred_username` claim |
| `actor_type` | `customer` / `driver` / `courier` / `merchant` / `staff` / `service` |
| `service` | `spring.application.name` |
| `endpoint` | `METHOD path` |
| `target_resource` | parsed from path (e.g. `payment:abc123`) |
| `result` | `success` / `failure` (from response status) |
| `duration_ms` | measured at filter level |
| `request_id` | correlation id |
| `trace_id` / `span_id` | from OTel context |
| `client_ip` | from `X-Forwarded-For` (first) or `RemoteAddr` |
| `user_agent` | from `User-Agent` header |

Bodies are *not* captured by default (PII risk). To capture, set
`platform.audit.api.capture-body=true` AND add the path to
`platform.audit.api.capture-body-paths` (whitelist).

### `audit.admin.<service>.v1` — every `/admin/v1/**` call

Same fields plus:
- `roles` — array of role names from the JWT
- `action` — the operation (e.g. `force-capture`, `blocklist-add`)
- `reason_code` — **required** for PII access; pulled from query/body

### Emit mechanism

Both topics are emitted to Kafka via the **transactional outbox
pattern**:
1. The audit event is written to the `outbox_event` table in the same
   DB transaction as the business write.
2. The `OutboxRelay` bean (scheduled, every 100ms) reads the table and
   publishes to Kafka.
3. This guarantees "no business write without an audit event" and "no
   audit event without a business write" — atomicity.

### Override

```yaml
platform:
  audit:
    api:
      enabled: true
      topic: audit.api.request.v1
      capture-body: false
      capture-body-paths:
        - /v1/payments/*/refund    # only capture bodies for these paths
    admin:
      enabled: true
      topic-prefix: audit.admin.
      require-reason-code: true   # 400 if reason_code is missing on PII access
```

---

## 4. PII redaction

The platform has a strict PII policy: PII fields are **scrubbed by
default** and only exposed to specific roles (see
[`../services/RECOMMENDATIONS.md` 6.5](../services/RECOMMENDATIONS.md#65-data-access-by-role-platform-wide)).

### Redaction patterns

| Pattern | Field | Default redaction |
|---|---|---|
| Email | `email` | `j***@example.com` |
| Phone | `phone` | `+1-555-***-1234` |
| Name | `firstName`, `lastName`, `fullName` | `J*** D**` |
| Address | `street`, `addressLine1` | `1** M** St` |
| Card | `cardNumber` | `**** **** **** 1234` |
| Coordinates | `lat`, `lon` (when paired with home) | rounded to 0.01 (~1 km) |

### Where redaction happens

1. **At the API boundary** — `@Redact` annotation on a DTO field runs
   the configured redactor before serialisation.
2. **In logs** — the request-logging filter applies the same redaction
   to any field in the body or headers that matches the platform's
   PII field list.
3. **In audit events** — bodies captured for `audit.api.request.v1`
   pass through the redactor first.
4. **In error responses** — `detail` strings have PII substituted
   before being included (e.g. `User abc123` instead of full email).

### Configure

```yaml
platform:
  pii:
    fields:
      - email
      - phone
      - firstName
      - lastName
    custom-redactors:
      customer-segment: com.trips-enjoy.platform.pii.SegmentRedactor
```

---

## 5. Money convention

Money in the platform is a `Money` value class — not a `BigDecimal`,
not a `double`. See [`MODULES.md` platform-spring-boot-money](./MODULES.md).

```kotlin
val price: Money = Money.of("19.99", "USD")     // stores 1999 minor units
val total: Money = price * 3                     // 5997 USD
val aed: Money = price.convertTo("AED")         // 73.37 AED (uses FX from configuration-service)
```

Rules:
- Stored as **minor units** (Long) — never floating point.
- Currency code is **ISO 4217** (3 letters).
- Arithmetic **rejects** mixed currencies (compile-time error from
  Kotlin's type system where possible, runtime check otherwise).
- Serialised as a JSON object `{"amount": "19.99", "currency": "USD"}` over the wire.
- Persisted as `NUMERIC(19, 4)` in PostgreSQL via `MoneyConverter`.
- All 7 financial services use this. The other 39 use it for any
  monetary field (delivery fees, cancellation fees, etc.).

---

## 6. Logging

Every log line is **structured JSON** to stdout.

```json
{
  "timestamp": "2026-07-29T07:12:34.567Z",
  "level": "INFO",
  "logger": "com.uber.payment.PaymentService",
  "thread": "http-nio-8080-exec-3",
  "message": "Payment captured",
  "service": "payment-service",
  "env": "prod",
  "region": "us-east-1",
  "requestId": "0190a3b4-7c8d-7abc-9def-0123456789ab",
  "traceId": "8f4a9b2c1d0e3f5a6b7c8d9e0f1a2b3c",
  "spanId": "1a2b3c4d5e6f7a8b",
  "paymentId": "abc123",
  "amount": "19.99",
  "currency": "USD"
}
```

Always present: `timestamp`, `level`, `logger`, `thread`, `message`,
`service`, `env`, `region`, `requestId`, `traceId`, `spanId`.

### Rules

- **No `System.out.println`** — use SLF4J.
- **No PII in logs** — the redactor (4) catches most; services should
  also use `@Redact` on any DTO field that could land in a log.
- **No raw exceptions in `message`** — log the message and let the
  exception attach as a separate field via `%x` in Logback.
- **No multiline messages** — JSON breaks if the message contains a
  newline. The library auto-escapes.

### Log levels

| Level | Use for |
|---|---|
| `ERROR` | The request failed AND the system is in an unexpected state. Alertable. |
| `WARN` | The request failed AND it's a known recoverable case (e.g. retry succeeded, idempotency replay). |
| `INFO` | The request succeeded at the business level. Default for happy-path events. |
| `DEBUG` | Diagnostic detail. Off in prod. |
| `TRACE` | Verbose diagnostic. Off unless explicitly enabled. |

---

## 7. Naming

| Thing | Convention | Example |
|---|---|---|
| Service name | `<bounded-context>-service` | `payment-service` |
| DB schema | `<service>` (no `-service`) | `payment` |
| Audit topic | `audit.<kind>.<service>.v<n>` | `audit.admin.payment.v1` |
| Kafka topic | `<domain>.<entity>.<action>.v<n>` | `payment.intent.captured.v1` |
| REST namespace | `/v<n>/...` (public), `/admin/v<n>/...` (admin) | `/v1/payments`, `/admin/v1/payments` |
| Service catalog endpoint | `GET /v1/admin/services` on `admin-service` | the cross-service catalog |
| Permission preset endpoint | `GET /v1/admin/presets` on `admin-service` | currently enumerates the `SUPER_ADMIN` preset |
| Super-admin grant | `POST /v1/admin/identity/grant-super-admin` on `admin-service` | fan-out via `identity-service` |
| Cache key | `<service>:<entity>:<id>[:<sub>]` | `payment:intent:abc123:status` |
| MDC key | `camelCase` | `requestId`, `paymentId` |
| Logback pattern | structured fields only | — |
| Env var | `UPPER_SNAKE_CASE`, `PLATFORM_` prefix for cross-cutting | `PLATFORM_AUDIT_API_ENABLED` |
| Maven artifact | `<group>:<artifact>:<version>` | `com.trips-enjoy.platform:spring-boot-starter:4.1.0` |

### Permission presets

The platform exposes **permission presets** as documentation +
operator-UI conveniences. The current set is enumerated at
`GET /v1/admin/presets` on `admin-service`. Each preset maps to a
concrete list of Keycloak realm roles; the realm roles remain the
source of truth for enforcement (see `RECOMMENDATIONS.md` 6.2).
The only preset today is `SUPER_ADMIN` = `platform.super_admin` +
the 21 `<service>.admin` scopes (one per active service post-Phase 7.7). Per-service preset membership is
declared in each service's `TECH.md` 10.7 (append-only — never
renumber the section).

---

## 8. API versioning

- **URL versioning** for the public API: `/v1/...`, `/v2/...`.
- **Topic versioning** for events: `payment.intent.captured.v1`.
- **Schema versioning** in Apicurio / Confluent: `1.0.0` (SemVer).
- A breaking change to a public API bumps the URL version. Old
  version keeps working for at least 6 months.
- A breaking change to an event bumps the topic version. Old topic
  keeps working for at least 3 months.
- A breaking change to a database schema requires a migration; old
  columns are not dropped until the next major version.
