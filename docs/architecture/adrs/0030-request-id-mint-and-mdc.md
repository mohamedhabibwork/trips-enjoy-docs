# ADR-0030: Request ID mint (UUIDv7 + MDC binding)

- Status: Accepted
- Date: 2026-08-15
- Authors: Platform Architecture Team
- Deciders: Architecture Review Board
- Tags: observability, correlation, tracing, mdc, contracts

> **Catalog revision (2026-08-15, appended per append-not-renumber):**
> this ADR locks the platform-wide contract for `request_id`
> generation when no upstream id is supplied. Every service MUST
> mint UUIDv7 (per [ADR-0015](0015-uuidv7-for-ids.md)) and bind
> it to MDC under the key `requestId`. The 9 services that ship
> a local `RequestCorrelationFilter.kt` adopt the platform filter
> in `platform-spring-boot-web`; their UUIDv4 + request-attribute
> pattern is replaced.

## Context and Problem Statement

The [`shared/CONVENTIONS.md`](../shared/CONVENTIONS.md#correlation-ids)
and [`ADR-0019`](0019-request-id-at-the-edge.md) declare the
canonical contract:

- The API gateway is the **root** generator
- The `request_id` is a UUIDv7 value
- It propagates via `X-Request-Id` (alias `X-Correlation-Id`)
- It binds to MDC under the key `requestId`
- It propagates to every downstream HTTP call, Kafka event, log
  line, and OpenTelemetry span

But 9 of 14 Kotlin services ship a local `RequestCorrelationFilter`
that mints **UUIDv4** when no upstream id is supplied and binds
the value to a **request attribute** (not MDC):

```kotlin
val value = request.getHeader("X-Request-Id")
    ?: request.getHeader("X-Correlation-Id")
    ?: UUID.randomUUID().toString()    // <-- UUIDv4
request.setAttribute("correlationId", value)   // <-- request attribute, not MDC
```

The audit at [`shared/PLATFORM_DRY_AUDIT.md` §6 ADR-0026](../../shared/PLATFORM_DRY_AUDIT.md)
flagged this drift. The contract is load-bearing:

- Log aggregation (Loki) keys on the `requestId` MDC field; a
  service that binds to a request attribute instead produces log
  lines with `requestId=""` (empty), breaking end-to-end tracing.
- Kafka event envelope `correlation_id` field (per
  [`shared/CONVENTIONS.md`](../shared/CONVENTIONS.md#event-envelope))
  must equal the MDC `requestId`; if the service reads from the
  request attribute, the value is empty.
- OpenTelemetry span attribute `platform.request_id` (per
  `services/notification-service/INTEGRATION.md`) is bound from
  MDC; if MDC is empty, the OTel attribute is missing.

## Decision Drivers

- **UUIDv7 for time-ordered IDs.** [ADR-0015](0015-uuidv7-for-ids.md)
  mandates UUIDv7 for all new identifiers; `request_id` is no
  exception. UUIDv7's time-ordered bits enable log-aggregation
  tooling to efficiently sort by time without parsing the
  timestamp.
- **MDC binding for log correlation.** SLF4J's MDC is the
  standard mechanism for binding per-request values to log lines;
  every log library on the platform supports it.
- **Spring Boot 4 OTel integration.** Spring Boot 4's
  OpenTelemetry auto-config reads MDC and binds to OTel span
  attributes; binding to a request attribute instead breaks this.

## Considered Options

1. **UUIDv7 + MDC** (platform canonical; matches
   [`ADR-0019`](0019-request-id-at-the-edge.md) +
   `platform-spring-boot-web` `RequestCorrelationFilter`)
2. **UUIDv4 + request attribute** (9 of 14 services' current
   default)
3. **UUIDv4 + MDC** (rejected — UUIDv4 is non-time-ordered)
4. **Per-service random generation; no MDC** (rejected —
   breaks log correlation)

## Decision Outcome

**Chosen option: option 1, UUIDv7 + MDC.**

- Every service's `RequestCorrelationFilter` reads
  `X-Request-Id` (or `X-Correlation-Id` alias) from the request
  header.
- If absent, mints a UUIDv7 via `kotlin.uuid.Uuid.generateV7()`
  (Kotlin) or `uuid.NewV7()` (Go) or `uuid.uuid7()` (Python 3.14+).
- Binds to MDC under the key `requestId` (and clears MDC after
  the request completes).
- Sets `X-Request-Id` and `X-Correlation-Id` response headers.
- Propagates to outbound HTTP (via `RestTemplate` /
  `WebClient` interceptor) and Kafka (via producer record header).
- The 9 redundant `RequestCorrelationFilter.kt` files are deleted.

### Consequences

**Good:**
- Single canonical request_id minting + binding
- Log aggregation (Loki / Elasticsearch) keys consistently on
  MDC `requestId`
- OTel span attribute `platform.request_id` is uniformly populated
- Kafka event envelope `correlation_id` is uniformly populated
- 9 redundant `RequestCorrelationFilter.kt` files deleted
  (~225 LOC)

**Bad:**
- 9 services must replace `UUID.randomUUID()` with
  `Uuid.generateV7()` (Kotlin 2.4.10 stdlib).
- 9 services must replace `request.setAttribute("correlationId",
  value)` with MDC binding: `MDC.put("requestId", value)`.
- A `try { chain.doFilter(...) } finally { MDC.remove("requestId") }`
  must be added to ensure MDC cleanup.

### Follow-up

- [ ] Update `shared/CONVENTIONS.md` §correlation-ids to declare
  the UUIDv7 + MDC contract as binding.
- [ ] Update `services/RECOMMENDATIONS.md` §6.2a to declare
  `requestId` MDC key as the canonical log field.
- [ ] Update 9 service `RequestCorrelationFilter.kt` files to
  the platform version (Phase A pilot PR on `customer-service`).
- [ ] Update Kotlin version pin to 2.4.10+ across all 14
  services (already done per `platform-spring-boot-starter
  Kotlin 2.4 upgrade` memory entry 2026-08-14).

## Pros and Cons of the Options

### UUIDv7 + MDC (chosen)

Time-ordered, MDC-compatible, matches platform conventions.
Single canonical source of truth.

### UUIDv4 + request attribute

Current 9-of-14 default. Loses time-ordering and breaks log
correlation. Rejected.

### UUIDv4 + MDC

Half-step: MDC binding is right but UUIDv4 is wrong. Rejected
because UUIDv7 is mandated by [ADR-0015](0015-uuidv7-for-ids.md).

## References

- [ADR-0015](0015-uuidv7-for-ids.md) — UUIDv7 for new identifiers
- [ADR-0019](0019-request-id-at-the-edge.md) — Request id at the
  edge (the gateway-injected principal contract)
- [ADR-0011](0011-opentelemetry-observability.md) — OpenTelemetry
  (the OTel span attribute source)
- [`shared/CONVENTIONS.md`](../shared/CONVENTIONS.md#correlation-ids)
  — the canonical request_id contract
- [`shared/PLATFORM_DRY_AUDIT.md` §6 ADR-0026](../../shared/PLATFORM_DRY_AUDIT.md)
  — the audit that flagged this drift
- [memory entry 2026-08-14: UUID v7 polyglot](../README.md) —
  UUIDv7 stdlib / library adoption across Kotlin / Go / Python
