# ADR-0011: OpenTelemetry for Traces, Metrics, and Logs

- Status: Accepted
- Date: 2026-07-29
- Authors: Platform Architecture Team
- Deciders: Architecture Review Board
- Tags: observability, opentelemetry, traces, metrics, logs, audit

> **Catalog revision (2026-08-05, appended per append-not-renumber):**
> the locked catalog is **20 services** per
> [ADR-0017](0017-20-service-architecture.md). The "58 services"
> figures in this ADR predate the 58 → 20 consolidation; the
> OpenTelemetry instrumentation, the exporter matrix, and the
> consequences below apply unchanged to the surviving 20-service
> catalog.

## Context and Problem Statement

The platform has 58 services emitting logs, metrics, and traces.
The on-call needs to find the cause of an incident across all of
them in minutes, not hours. We need a single instrumentation layer
that (a) captures traces, metrics, and logs with a consistent
schema, (b) propagates context (correlation id, trace id, user id)
across services and across the event bus, (c) integrates with the
auto-instrumentation available in the language ecosystems we use,
and (d) is vendor-neutral so we are not locked into a single
backend (Jaeger, Tempo, Datadog, New Relic, Honeycomb, etc.).

The decision is whether to standardize on OpenTelemetry (the
CNCF-backed, vendor-neutral standard), a proprietary APM
(Datadog, New Relic, Dynatrace, AppDynamics), or a mix of agents
(one per backend).

## Decision Drivers

- One instrumentation layer for traces, metrics, and logs; one
  set of SDKs to learn; one set of semantic conventions to
  follow.
- Vendor-neutral: we can swap the backend (Jaeger → Tempo →
  Datadog → Honeycomb) without re-instrumenting the services.
- Auto-instrumentation for the language ecosystems we use (Go,
  TypeScript/Node, Kotlin/JVM, Dart/Flutter, Python), including
  HTTP, gRPC, database drivers, Kafka producers and consumers.
- Context propagation across services and across Kafka (the
  `traceparent` header is propagated in HTTP and in Kafka
  message headers).
- Consistent attribute schema (`service.name`, `service.version`,
  `deployment.environment`, `region`, `correlation_id`,
  `user_id`).
- Mature, production-grade; the CNCF graduated OpenTelemetry in
  2024 and the SDKs are stable.
- Cost: open-source SDKs and a flexible backend (we are not
  paying per-host for a proprietary agent).

## Considered Options

- **OpenTelemetry (CNCF, vendor-neutral)** — the chosen option.
- **Datadog APM (or New Relic, Dynatrace, AppDynamics)** —
  proprietary APM.
- **Mix of agents (one per backend)** — Prometheus exporter for
  metrics, Jaeger client for traces, Fluent Bit for logs, each
  configured separately.
- **In-house instrumentation library** — write our own tracing
  and metrics.

## Decision Outcome

Chosen option: "**OpenTelemetry**", because (a) it is the only
option that gives us a single, vendor-neutral instrumentation
layer for traces, metrics, and logs, with consistent attribute
schemas and consistent context propagation, (b) the auto-instrumentation
for our language ecosystems is mature, (c) the backend is
swappable (we can start with Jaeger for traces, Victoria Metrics
for metrics, Loki for logs, and move to a managed backend
without re-instrumenting the services), and (d) the SDKs are
stable and CNCF-graduated. The platform team owns the OTel
collector configuration and the export pipeline; the service
teams use the OTel SDK and follow the platform's semantic
conventions.

### Consequences

- Good: One instrumentation layer. The platform's on-call
  runbook covers OTel; the service teams learn one SDK per
  language.
- Good: Vendor-neutral. The backend (Jaeger, Tempo, Datadog,
  Honeycomb) is a deployment choice; the services do not change.
- Good: Auto-instrumentation. HTTP, gRPC, Postgres, Redis, Kafka
  producers and consumers are auto-instrumented; the service
  code only adds custom spans for business operations.
- Good: Context propagation across services and across Kafka.
  The `traceparent` header is propagated in HTTP and in Kafka
  message headers; a trace is a trace, no matter where the
  span was emitted.
- Good: Consistent attribute schema. Every service emits
  `service.name`, `service.version`, `deployment.environment`,
  `region`, `correlation_id`; dashboards and alerts are
  consistent.
- Good: Cost. The SDKs are open-source; the collector is
  open-source; the backends are open-source (or we can use a
  managed one).
- Bad: The OTel collectors and exporters are an additional
  piece of infrastructure to operate. (Mitigation: a dedicated
  observability team that owns the collector fleet; documented
  runbooks for collector upgrades.)
- Bad: Some advanced features (e.g. continuous profiling,
  real-user monitoring) are still maturing in OTel. We
  mitigate by adopting them as they stabilize, or by using
  a managed backend that fills the gaps.
- Bad: Custom backends (Datadog, New Relic) have richer
  out-of-the-box dashboards and alerting. We mitigate with
  Grafana dashboards and Alertmanager (or equivalent) on top
  of Prometheus-format metrics; the OTel collector exports
  to both.
- Neutral: Audit is a separate pillar; we use Kafka events →
  `audit-service` rather than OTel logs, because audit must be
  immutable and append-only with 7-year retention (see
  [`OBSERVABILITY.md`](../OBSERVABILITY.md)).

### Confirmation

- 100% of services use the OTel SDK and the platform's
  semantic conventions; verified by a CI lint that checks
  for the required attributes on every span.
- Trace sampling: 100% of error traces retained; 10% of
  success traces in production; 100% in staging.
- Context propagation: 100% of cross-service HTTP calls
  propagate `traceparent`; 100% of Kafka events carry the
  `traceparent` header (or the equivalent in the event
  envelope).
- P99 trace query latency: < 5 seconds from "what just
  happened" to a list of spans in the trace UI.
- SLO burn-rate alerts fire on synthetic load tests; verified
  by a quarterly alert drill.

## Pros and Cons of the Options

### OpenTelemetry (CNCF, vendor-neutral)

The chosen option. A single set of SDKs and APIs for traces,
metrics, and logs; a vendor-neutral data model; a collector that
exports to many backends.

- Good: One instrumentation layer; one set of SDKs; one set of
  semantic conventions.
- Good: Vendor-neutral; backend is swappable.
- Good: Auto-instrumentation for the language ecosystems we
  use.
- Good: Context propagation across services and across Kafka.
- Good: CNCF-graduated; stable SDKs.
- Good: Open-source; no per-host pricing.
- Bad: Collectors and exporters are additional infrastructure.
- Bad: Some advanced features (continuous profiling, RUM) are
  still maturing.
- Bad: Custom backends have richer out-of-the-box dashboards
  (mitigated by Grafana + Prometheus + Alertmanager).

### Datadog APM (or New Relic, Dynatrace, AppDynamics)

Proprietary APM.

- Good: Excellent out-of-the-box dashboards, alerting, and
  correlation across signals.
- Good: Continuous profiling, RUM, synthetic monitoring
  integrated.
- Good: Low operational burden; SaaS.
- Bad: Per-host pricing scales linearly with our replica count;
  at 58 services × N replicas per region, this is a material
  line item.
- Bad: Vendor lock-in; switching is a re-instrumentation
  project.
- Bad: Limited export; we cannot easily take our traces to
  another backend.

### Mix of agents (one per backend)

Prometheus exporter for metrics, Jaeger client for traces,
Fluent Bit for logs, each configured separately.

- Good: Best-of-breed per signal.
- Good: No vendor lock-in.
- Bad: Inconsistent attribute schemas across signals; the
  trace's `trace_id` is not the same as the log's
  `correlation_id` without a manual mapping.
- Bad: Multiple SDKs to learn; multiple agents to operate.
- Bad: Context propagation is harder; each agent has its own
  conventions.

### In-house instrumentation library

Write our own tracing and metrics.

- Good: Full control.
- Good: Tailored exactly to our needs.
- Bad: We become the maintainer of an observability library.
  This is a multi-year investment with diminishing returns;
  we'd be rebuilding what OTel gives us for free.
- Bad: No ecosystem (auto-instrumentation, exporters,
  collectors).
- Bad: No community; security patches are on us.

## References

- [`OBSERVABILITY.md`](../OBSERVABILITY.md) — the three pillars
  + audit + business; the OTel compatibility section; the
  per-service RED, USE, and business metrics; the SLOs; the
  alerting policy.
- [`ARCHITECTURE.md`](../ARCHITECTURE.md) — observability in
  the cross-cutting decisions; correlation id, request id,
  trace id, user id on every log line.
- [`EVENT_ARCHITECTURE.md`](../EVENT_ARCHITECTURE.md) — the
  event envelope's `correlation_id` and the Kafka header
  propagation of `traceparent`.
- [`SECURITY_ARCHITECTURE.md`](../SECURITY_ARCHITECTURE.md) —
  audit logs as a domain event; immutable, append-only.
- OpenTelemetry documentation — SDK, collector, semantic
  conventions, exporters, auto-instrumentation.
- W3C Trace Context — `traceparent` and `tracestate` headers.
- CNCF, *OpenTelemetry Graduates to CNCF Graduated Project* —
  stability and production-readiness.
