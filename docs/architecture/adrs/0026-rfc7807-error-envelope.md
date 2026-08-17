# ADR-0026: RFC 7807 error envelope fields (canonical 5-field extension)

- Status: Accepted
- Date: 2026-08-15
- Authors: Platform Architecture Team
- Deciders: Architecture Review Board
- Tags: api, errors, rfc7807, observability, contracts

> **Catalog revision (2026-08-15, appended per append-not-renumber):**
> this ADR locks the platform-wide contract for RFC 7807 error
> envelope extensions. Every error response from every HTTP
> service must carry five extension fields — `code`, `correlationId`,
> `traceId`, `spanId`, `timestamp` — plus optional `errors[]` and
> `downstream`. The platform's 21 services adopt this shape; the 11
> redundant `ApiExceptionHandler.kt` files are deleted; the platform
> `GlobalExceptionHandler` becomes the canonical producer.

## Context and Problem Statement

The [`shared/CONVENTIONS.md`](../shared/CONVENTIONS.md#error-model)
declares the canonical RFC 7807 error envelope shape as:

```json
{
  "type": "urn:trips-enjoy:error:<code>",
  "title": "Not Found",
  "status": 404,
  "detail": "Customer 123e4567-... not found",
  "instance": "/v1/customers/123e4567-...",
  "code": "CUSTOMER_NOT_FOUND",
  "correlationId": "018f3e4a-9c10-7891-b234-...",
  "traceId": "0af7651916cd43dd8448eb211c80319c",
  "spanId": "b7ad6b7169203331",
  "timestamp": "2026-08-15T14:32:11.483Z",
  "errors": [...],
  "downstream": {...}
}
```

But 11 of 14 Kotlin services ship their own
`ApiExceptionHandler.kt` that produces a `ProblemDetail` with only
**3 of 5** required fields: `code`, `correlationId`, and `timestamp`.
The `traceId` and `spanId` are missing — services either don't read
the OTel context or don't propagate it to the response. The `errors[]`
array (for validation failures with multiple field errors) is also
missing.

The Python `reporting-service` ships a local `ErrorEnvelope` class
in `app/domain/types.py:127-136` that is **actively wrong**: it's
missing `traceId`, `spanId`, `timestamp`, `errors[]`, and `downstream`
(5 of 7 fields).

The audit at [`shared/PLATFORM_DRY_AUDIT.md` §6 ADR-0022](../../shared/PLATFORM_DRY_AUDIT.md)
flagged this as drift that must be resolved before deleting the
11 redundant handlers. Without convergence, a downstream consumer
that keys on `traceId` to correlate an error with a distributed
trace would silently get `null` from 11 of 14 services.

## Decision Drivers

- **Distributed-trace correlation.** Every error response must
  carry `traceId` and `spanId` so that a single error report can
  be traced end-to-end across the platform.
- **Validation-error aggregation.** The `errors[]` array carries
  per-field validation failures; downstream consumers (mobile apps,
  web clients) use this for inline form validation.
- **Downstream attribution.** The `downstream` object identifies
  which upstream service returned the error (relevant when the
  gateway returns a 502 because `payment-service` returned a 503).

## Considered Options

1. **Full 5-field envelope (`code`, `correlationId`, `traceId`,
   `spanId`, `timestamp`) + optional `errors[]` and `downstream`**
   (platform canonical; matches `shared/CONVENTIONS.md`)
2. **3-field envelope (`code`, `correlationId`, `timestamp`)**
   (11 of 14 services' current default)
3. **No extension fields; pure RFC 7807 base** (rejected —
   loses correlation IDs)

## Decision Outcome

**Chosen option: option 1, full envelope.**

- Every RFC 7807 response carries `code`, `correlationId`, `traceId`,
  `spanId`, `timestamp` as required extension fields.
- `errors[]` is populated for `400 Bad Request` and `422
  Unprocessable Entity` responses with per-field validation
  failures; absent otherwise.
- `downstream` is populated for `502 Bad Gateway` and `504 Gateway
  Timeout` responses with the upstream service name + status +
  trace id; absent otherwise.
- The platform `GlobalExceptionHandler` (in
  `platform-spring-boot-error`) becomes the canonical producer;
  11 service-local `ApiExceptionHandler.kt` files are deleted.

### Consequences

**Good:**
- Single canonical RFC 7807 envelope across 21 services
- Distributed-trace correlation works end-to-end
- Validation-error aggregation is uniform
- 11 redundant handler files deleted (~880 LOC)
- Python `reporting-service` local `ErrorEnvelope` deleted

**Bad:**
- 11 services must update their `ApiException(code, status)`
  companion classes to throw the platform `BusinessException`
  (or the platform `ApiException` adapter added in Phase C)
- 11 services must ensure OTel context is bound to MDC at the
  point of exception (Spring Boot 4 auto-binds via
  `application.properties` + `management.tracing.sampling.probability=1.0`)
- Python `reporting-service` must replace 6 inline
  `raise HTTPException(...)` calls with the platform helper

### Follow-up

- [ ] Update `shared/CONVENTIONS.md` §error-model to declare the
  5 required fields and the conditional `errors[]`/`downstream`
  rules.
- [ ] Add `BusinessException` and `ApiException` adapter to
  `platform-spring-boot-error` so app controllers can throw
  either type without code changes.
- [ ] Add `raise_http(code, detail, request, **extra)` helper to
  `platform_python.errormodel` for the Python lift.
- [ ] Update `reporting-service`'s `app/domain/types.py:127-136`
  to delete the local `ErrorEnvelope` class and import from
  `platform_python.errormodel`.

## Pros and Cons of the Options

### Full 5-field envelope (chosen)

Matches `shared/CONVENTIONS.md`, matches what
`platform-spring-boot-error` `GlobalExceptionHandler` already
emits, and is the only option that supports distributed-trace
correlation and validation-error aggregation.

### 3-field envelope

What 11 of 14 services currently default to. Loses trace
correlation and validation aggregation; rejected because the
downstream consumer (mobile apps, BI dashboards) already relies
on `traceId` and `errors[]`.

### Pure RFC 7807 base

Strict adherence to the spec, no extensions. Rejected because
the `correlationId` field is load-bearing for the
[`ADR-0019`](0019-request-id-at-the-edge.md) gateway-injected
principal contract.

## References

- [ADR-0019](0019-request-id-at-the-edge.md) — request id at the
  edge (the gateway-injected `correlationId` source)
- [ADR-0011](0011-opentelemetry-observability.md) — OpenTelemetry
  for traces, metrics, and logs (the `traceId`/`spanId` source)
- [`shared/CONVENTIONS.md`](../shared/CONVENTIONS.md#error-model) —
  the canonical RFC 7807 envelope declaration
- [`shared/PLATFORM_DRY_AUDIT.md` §6 ADR-0022](../../shared/PLATFORM_DRY_AUDIT.md)
  — the audit that flagged this drift
- [RFC 7807](https://www.rfc-editor.org/rfc/rfc7807) — the
  underlying spec
