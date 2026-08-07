# ADR-0019: Request id at the edge (X-Request-Id / X-Correlation-Id aliases)

- Status: Accepted
- Date: 2026-08-07
- Authors: Platform Architecture Team
- Deciders: Architecture Review Board
- Tags: observability, gateway, correlation, tracing, idempotency

> **Catalog revision (2026-08-07, appended per append-not-renumber):**
> this ADR codifies the API gateway as the canonical root generator of
> the platform's per-request id, propagating it to every downstream
> call, event, log line, and OpenTelemetry span. It applies to the
> locked **20-service** catalog per
> [ADR-0017](0017-20-service-architecture.md).

## Context and Problem Statement

The platform's 20-service architecture has a partial and
inconsistent documentation contract for the per-request id:

- [`shared/CONVENTIONS.md`](../CONVENTIONS.md) 2 standardizes the
  shared library on `X-Request-Id` (read, MDC, response, Kafka
  header) — this is the actual implementation in
  `platform-spring-boot-starter`.
- 19 of 20 per-service `INTEGRATION.md` 6 sections state the
  inbound header as `X-Correlation-Id`.
- The API gateway's `INTEGRATION.md` 6 uses both names.
- [`architecture/API_STANDARDS.md`](../API_STANDARDS.md) 6 lists
  both `X-Request-Id` and `X-Correlation-Id` as standard request
  headers.
- [`shared/PLATFORM_BASELINE.md`](../../shared/PLATFORM_BASELINE.md)
  names the header as `X-Correlation-Id` only.

The contract is already **load-bearing**:

- The notification service's outbox is **idempotent on the gateway
  request id** ([`services/notification-service/WORKFLOWS.md`](../../services/notification-service/WORKFLOWS.md)
  line 499: "idempotent on the gateway request id"). A regenerating
  gateway would break outbox dedup.
- The audit topic's partition key is `correlation_id` per
  [`services/api-gateway/INTEGRATION.md` 3.1](../../services/api-gateway/INTEGRATION.md);
  the `correlation_id` must equal the request id for end-to-end
  ordering.
- The shared library's `correlationIdFilter` already binds the
  value to the MDC, the response header, the outbound HTTP
  interceptor, and the Kafka producer; the runtime is in place —
  only the documentation is inconsistent.

We need one canonical, platform-wide contract that (a) names the
header unambiguously, (b) names the single root generator, (c)
defines the propagation targets, (d) names the id format, (e)
defines the relationship with the OpenTelemetry trace id, and (f)
guarantees idempotency on retries.

## Decision Drivers

- **One canonical generator.** Exactly one component (the gateway)
  accepts or generates the id. Every downstream service inherits
  the value, never creates its own.
- **One canonical format.** UUIDv7 per
  [ADR-0015](0015-uuidv7-for-ids.md) — time-ordered, sortable, no
  PII.
- **One propagation rule.** The id flows through HTTP, Kafka, audit
  events, logs, and OTel — every downstream observable artefact
  carries the same value.
- **Zero client deprecation.** The platform already has clients
  sending `X-Request-Id` (mobile/web) and others sending
  `X-Correlation-Id` (older channels, partner B2B). Renaming would
  force a coordinated deprecation across channels.
- **Idempotency on retries.** A retried request with the same
  client-supplied id (or the same `Idempotency-Key`) must keep
  the same request id; the audit topic is partitioned by
  `correlation_id` and a re-generated id would re-shard the
  request.
- **Distinct from the OTel trace id.** The platform's two
  observability ids must be clearly separated: the **request id**
  is the business correlation (one per business request), the
  **trace id** is the OTel W3C `traceparent` (one per request
  scope, may change on retry if the client re-traces).

## Considered Options

- **Pick `X-Request-Id`, drop `X-Correlation-Id`.** Rename the
  legacy header across every per-service `INTEGRATION.md` and the
  shared library. Breaks every existing client that sends
  `X-Correlation-Id`; forces a deprecation window; high churn.
- **Pick `X-Correlation-Id`, drop `X-Request-Id`.** Rename the
  shared library's read header, the AUTO_CONFIG CORS allow-list,
  the response header, and the Kafka header. Breaks every client
  that already sends `X-Request-Id`; the notification-service
  outbox idempotency contract would need to be re-keyed.
- **Treat both as aliases (chosen).** Keep both header names; the
  gateway prefers `X-Request-Id` if both are sent, generates a
  UUIDv7 if neither, and sets both as response headers. The shared
  library's `correlationIdFilter` accepts either inbound and sets
  both outbound. No client breaks; the platform has a single
  contract; the per-service `INTEGRATION.md` 6 wording remains
  correct as an alias form.
- **Introduce a new `X-Trace-Id`.** A third header that means the
  same thing. Worse than the alias approach on every axis.

## Decision Outcome

Chosen option: "**Treat both as aliases**", because (a) it gives
the platform one canonical contract (this ADR + `CONVENTIONS.md` 2)
without breaking any existing client, (b) it matches the shared
library's actual implementation, (c) the notification-service
outbox idempotency contract becomes load-bearing **by spec** rather
than by coincidence, (d) per-service `INTEGRATION.md` 6 sections
that say `X-Correlation-Id` are now explicitly correct as an
alias form, and (e) the rename effort is reduced to a documentation
sweep (deferred) instead of a coordinated deprecation.

### The contract

> The API gateway is the **canonical root generator** of the
> platform's per-request id. On every inbound HTTP request, the
> gateway reads `X-Request-Id`; if absent, reads `X-Correlation-Id`;
> if both are absent, generates a UUIDv7. The same value is then
> (a) **returned** in the response as both `X-Request-Id` and
> `X-Correlation-Id`; (b) **added** to every outbound HTTP request
> to a downstream service (as both headers); (c) **added** as a
> Kafka header on every event the gateway produces (as both
> `X-Request-Id` and `X-Correlation-Id`); (d) **written** to the
> `correlation_id` field of every audit event; (e) **bound** to
> the OpenTelemetry root span as the attribute
> `platform.request_id`; (f) **put** in the gateway's log MDC
> under the key `requestId`; and (g) is **stable for retries** —
> a retried request keeps the same id. If both `X-Request-Id` and
> `X-Correlation-Id` are sent, the gateway uses the value of
> `X-Request-Id` and sets both response headers to that value.

### Consequences

- Good: One contract; one source of truth (this ADR +
  `CONVENTIONS.md` 2); zero client deprecation; the
  notification-service outbox idempotency contract is now
  load-bearing by spec.
- Good: Every downstream service inherits the value automatically
  via the shared library's `correlationIdFilter`; no per-service
  code change is required for the propagation half of the contract.
- Good: The OTel root span carries the request id as
  `platform.request_id`; a single query ("show me everything for
  request id `01HZX…`") surfaces the log line, the audit event,
  the downstream calls, and the trace.
- Bad: Two header names on every request and response (cosmetic;
  ~60 bytes per request).
- Bad: The shared library's `correlationIdFilter` is a Kotlin/Spring
  implementation; the API gateway is Go and must re-implement the
  same contract (see `services/api-gateway/PLAN.md` Phase 8a and
  `services/api-gateway/TECH.md` "Request-id filter (Go)").
- Neutral: Per-service `INTEGRATION.md` 6 sections that say
  `X-Correlation-Id` remain correct under this ADR — the
  per-service wording is now explicitly the alias form. A future
  sweep can re-word them but is not required.
- Neutral: The audit topic's partition key is `correlation_id` per
  `services/api-gateway/INTEGRATION.md` 3.1; this ADR makes that
  partition key equal to the request id by construction, so a
  retried request lands on the same partition and is processed in
  order.

### Confirmation

- 100% of authenticated gateway responses carry both
  `X-Request-Id` and `X-Correlation-Id` set to the same value.
- 100% of `audit.api.request.v1` events carry a `correlation_id`
  equal to the response's request id.
- 100% of cross-service HTTP calls from the gateway carry both
  `X-Request-Id` and `X-Correlation-Id` set to the same value.
- 100% of events the gateway produces carry both `X-Request-Id`
  and `X-Correlation-Id` as Kafka headers.
- A synthetic test (see `services/api-gateway/PLAN.md` Phase 8a
  T-GW-07) sends no headers and asserts the chain:
  response headers = audit `correlation_id` = OTel
  `platform.request_id` = one UUIDv7.
- A second synthetic test sends `X-Request-Id: <known-A>` and
  `X-Correlation-Id: <known-B>` (different values) and asserts
  the response and audit event carry `<known-A>` in both headers.
- A third synthetic test sends the same `X-Request-Id` on a
  retried request and asserts the audit topic's partition key
  (and the audit event) is the same on both attempts.

## Pros and Cons of the Options

### Pick X-Request-Id, drop X-Correlation-Id

- Good: One canonical header name; the platform's docs read
  consistently.
- Bad: Every existing client that sends `X-Correlation-Id` breaks
  (mobile legacy, partner B2B, several internal scripts).
- Bad: A 6-month deprecation window with feature flags; high
  coordination cost.
- Bad: The shared library's AUTO_CONFIG `allowed-headers` list
  has to add `X-Correlation-Id` for back-compat anyway.

### Pick X-Correlation-Id, drop X-Request-Id

- Good: One canonical header name; the per-service `INTEGRATION.md`
  sections (19 of 20) are already correct.
- Bad: The shared library's actual code reads `X-Request-Id`; a
  header rename is a code change in every Spring Boot service.
- Bad: Breaks every client that already sends `X-Request-Id`
  (newer mobile/web, several SDKs).
- Bad: The notification-service outbox dedup key is renamed,
  invalidating in-flight idempotency windows.

### Treat both as aliases (chosen)

- Good: Zero client breaks; zero coordinated deprecation.
- Good: Matches the shared library's actual code; the per-service
  `INTEGRATION.md` sections that say `X-Correlation-Id` are
  explicitly the alias form and remain correct.
- Good: The platform has one canonical contract (this ADR +
  `CONVENTIONS.md` 2) that is enforceable by tests.
- Good: The notification-service outbox idempotency contract
  becomes load-bearing by spec.
- Bad: Two header names on every request and response
  (cosmetic).
- Bad: The Go gateway must re-implement the alias contract
  manually; the shared library's Kotlin filter does not apply.

### Introduce X-Trace-Id

- Good: A new, neutral header that no client sends today.
- Bad: Three header names that all mean the same thing; worse than
  the alias approach on every axis.
- Bad: Breaks every existing client; no benefit over the alias
  approach.

## References

- [ADR-0008](0008-api-gateway.md) — API gateway at the edge
  (the host of this contract).
- [ADR-0011](0011-opentelemetry-observability.md) — OpenTelemetry
  for traces, metrics, and logs (the trace context the request id
  is distinct from).
- [ADR-0015](0015-uuidv7-for-ids.md) — UUIDv7 for new identifiers
  (the format of generated ids).
- [ADR-0017](0017-20-service-architecture.md) — 20-service
  architecture (the catalog this ADR applies to).
- [`shared/CONVENTIONS.md`](../CONVENTIONS.md) 2 — the shared
  library's implementation of the alias contract.
- [`architecture/API_STANDARDS.md`](../API_STANDARDS.md) 6, 10
  — the standard headers table and the gateway's correlation-id
  paragraph.
- [`architecture/OBSERVABILITY.md`](../OBSERVABILITY.md) —
  observability pillars + the request-id / trace-id relationship.
- [`architecture/EVENT_ARCHITECTURE.md`](../EVENT_ARCHITECTURE.md)
  — the event envelope's `correlation_id` field and the
  Kafka-header propagation rule.
- [`architecture/SERVICE_DOC_TEMPLATE.md`](../SERVICE_DOC_TEMPLATE.md)
  6 — the per-service INTEGRATION template (the alias note is
  appended under this ADR).
- [`services/api-gateway/INTEGRATION.md` 3.1](../../services/api-gateway/INTEGRATION.md)
  — the audit event whose `correlation_id` equals the request id.
- [`services/api-gateway/PLAN.md`](../../services/api-gateway/PLAN.md)
  Phase 8a — the seven tasks that implement this contract.
- [`services/notification-service/WORKFLOWS.md`](../../services/notification-service/WORKFLOWS.md)
  line 499 — the notification-service outbox dedup contract that
  this ADR makes load-bearing by spec.
- W3C Trace Context — `traceparent` and `tracestate` headers.
