# ADR-0004: REST as the Primary Synchronous API Style

- Status: Accepted
- Date: 2026-07-29
- Authors: Platform Architecture Team
- Deciders: Architecture Review Board
- Tags: api, rest, http, sync, contract

## Context and Problem Statement

The microservices platform has **20 services** (per the locked
catalog in [ADR-0017](0017-20-service-architecture.md)) that must
call each other synchronously for read-your-writes within a
workflow (e.g. `trip-service` (ride-request sub-aggregate) reads
`customer-service` and `pricing-service` in the request path)
and that must expose a public API to mobile and web channels
through the `api-gateway`. We need to pick a primary synchronous
API style that the entire platform standardizes on so that the
gateway, the SDK generators, the contract tests, the OTel
auto-instrumentation, and the developer experience are all
aligned. The alternatives are well-trodden: REST/JSON over HTTP,
gRPC (HTTP/2 + Protobuf), GraphQL, and GraphQL federation. The
platform already uses Kafka (ADR-0005) for asynchronous
integration; the synchronous style is a separate decision.

The decision affects every service, every channel, and every
partner integration. The full contract for it lives in
[`API_STANDARDS.md`](../API_STANDARDS.md).

## Decision Drivers

- Universal client support: mobile (iOS, Android, Flutter), web
  (browsers), partner servers, and internal tools must all consume
  the same API style with first-class SDKs.
- Human-readable on the wire for debugging (logs, curl, browser dev
  tools). The on-call engineer at 3am needs to be able to reproduce
  a request from a log line.
- First-class OpenAPI tooling: spec-first design, code generation,
  contract testing, mock servers, and Swagger UI.
- Easy observability: every HTTP request is a span; every status
  code is a metric label. No additional protocol-aware
  instrumentation.
- Easy to cache (gateway, CDN), easy to rate-limit (gateway), easy
  to authenticate (gateway validates the JWT on every request).
- Versioning that we can run two of side-by-side at the gateway
  (`/v1/...`, `/v2/...`) with a deprecation window of ≥ 6 months.
- Compatible with the team's existing skills and the language
  ecosystems of our services.

## Considered Options

- **REST/JSON over HTTP/1.1 + HTTP/2** — uniform interface, JSON
  bodies, URI versioning, OpenAPI 3.1.
- **gRPC (HTTP/2 + Protobuf)** — strongly typed, high performance,
  bidirectional streaming.
- **GraphQL** — single endpoint, client-specified shape, strong for
  read-heavy UIs.
- **GraphQL federation** — GraphQL plus a federated schema across
  services.

## Decision Outcome

Chosen option: "**REST/JSON over HTTP**", because (a) it is the only
style that gives us universal client support (every mobile platform,
every web framework, every partner integration), (b) it is
human-readable on the wire, which materially helps incident response,
(c) OpenAPI 3.1 gives us a contract-first workflow with code
generation, contract tests, and mock servers, (d) the gateway
already terminates HTTP for JWT validation, rate limiting, and
header injection — adding gRPC or GraphQL at the edge would require
parallel infrastructure, and (e) the platform's hot paths (location
writes, dispatch reads) are not bottlenecked by the wire format; they
are bottlenecked by Postgres and Redis, and the JSON parsing
overhead is well within our P99 budget. The few services that need
very low latency over a local link (e.g. a co-located cache layer)
use in-process calls or Redis directly; the cross-service API is
REST.

### Consequences

- Good: One API style across the platform. SDKs are generated from
  OpenAPI; contract tests are generated from OpenAPI; mock servers
  are generated from OpenAPI.
- Good: Universal client support. Mobile (Flutter, native iOS,
  native Android), web (TypeScript), partner integrations, and
  internal tools all consume the same APIs.
- Good: Human-readable on the wire. On-call engineers can reproduce
  a request with `curl` and a captured log line.
- Good: Standard observability. Every HTTP request is a span;
  every status code is a metric label; OTel auto-instruments HTTP
  clients and servers.
- Good: Versioning with deprecation. `/v1/...` and `/v2/...` run
  side-by-side; old versions stay for ≥ 6 months with `Deprecation`
  and `Sunset` headers.
- Good: Gateway-side caching and rate limiting are standard.
- Bad: We do not get Protobuf's wire-efficiency or its strong
  typing at the language level. (Mitigation: type generation from
  OpenAPI; we have it for TypeScript, Kotlin, Swift, and Dart.)
- Bad: We do not get GraphQL's client-specified shape. For the few
  UIs that need it (e.g. the restaurant operator console, which
  has many optional fields and many forms), we add per-screen
  read-optimized endpoints in the owning service rather than a
  federated GraphQL layer.
- Bad: Chained synchronous calls can balloon tail latency. We
  mitigate by bounding the synchronous depth to ≤ 3 and by
  outsourcing everything else to Kafka (see
  [`ARCHITECTURE.md`](../ARCHITECTURE.md)).
- Neutral: All bodies are JSON, all errors use the same envelope,
  all money is integer minor units, all timestamps are RFC3339 UTC.
  This is documented in `API_STANDARDS.md` and is the contract for
  every service.

### Confirmation

- 100% of services publish `/openapi.json` and the spec is the
  source of truth (the implementation is contract-tested in CI).
- P99 latency for the dominant API path per Tier-1 service ≤ 500ms
  (per [`OBSERVABILITY.md`](../OBSERVABILITY.md) SLOs).
- Synchronous call depth: median ≤ 2, P99 ≤ 3 (we measure this via
  OTel span depth; anything deeper triggers a review).
- Deprecation hygiene: every endpoint scheduled for removal has a
  `Sunset` header and a replacement endpoint; we have a quarterly
  report of endpoints past their sunset date.

## Pros and Cons of the Options

### REST/JSON over HTTP

The chosen option. REST over HTTPS, JSON bodies, URI versioning,
OpenAPI 3.1 specs at `/openapi.json`.

- Good: Universal client support; standard tooling; human-readable.
- Good: Gateway-centric auth, rate limiting, caching, and header
  injection.
- Good: OpenAPI gives us spec-first, code generation, contract tests,
  and mocks.
- Good: Observability is free — OTel auto-instruments HTTP.
- Bad: Wire size is larger than Protobuf. Acceptable for our scale.
- Bad: No client-specified shape (vs. GraphQL). We mitigate with
  per-screen read endpoints.
- Bad: Verbose for some operations (e.g. nested resources). We
  mitigate with sparse fieldsets and per-screen projections.

### gRPC (HTTP/2 + Protobuf)

Strongly typed, high performance, bidirectional streaming, code
generation for many languages.

- Good: Strong typing at the language level.
- Good: Wire efficiency (Protobuf).
- Good: Streaming.
- Good: Built-in deadlines, cancellation, and metadata propagation.
- Bad: No browser-native support; partners and web clients cannot
  easily consume.
- Bad: Harder to debug (binary on the wire); no `curl`-equivalent.
- Bad: Less mature gateway ecosystem for JWT validation, rate
  limiting, and header injection at the edge (compared to HTTP).
- Bad: REST is the lingua franca of the partner ecosystem; gRPC
  would be a second style we'd have to maintain alongside it.

### GraphQL

Single endpoint, client-specified shape, strong for read-heavy UIs.

- Good: Client-specified shape; less over-fetching.
- Good: Strong tooling for client-side caching and optimistic UI.
- Bad: We would need a gateway in front of services to fan out
  GraphQL queries to multiple services — GraphQL federation.
- Bad: The N+1 query problem at the gateway is real; we have a
  bad experience with N+1 in the past.
- Bad: AuthN/AuthZ at the gateway is harder; the gateway must
  understand the GraphQL query to enforce per-field rules.
- Bad: A second API style alongside REST doubles our API surface
  to maintain.

### GraphQL federation

A federated GraphQL schema across services, with the gateway
fanning out to the right service for each field.

- Good: Client-specified shape; per-service ownership of sub-graphs.
- Good: Solves the per-field N+1 with persisted queries and DataLoader.
- Bad: Operationally complex. The gateway runs the federation
  runtime; every service must own a sub-graph schema; cross-service
  entity resolution (e.g. a Trip that spans `trip-service` and
  `driver-service`) is non-trivial.
- Bad: We would still need REST for non-UI callers (mobile native
  features, partner integrations, internal tools).
- Bad: The team's experience with GraphQL federation has been
  mixed; the cognitive load of sub-graph design is high.

## References

- [`API_STANDARDS.md`](../API_STANDARDS.md) — the full REST contract:
  style, naming, versioning, pagination, headers, auth, errors,
  idempotency, rate limiting, money, time, conventions,
  deprecation, OpenAPI, webhooks, anti-patterns.
- [`ARCHITECTURE.md`](../ARCHITECTURE.md) — communication patterns:
  REST for sync read-your-writes, Kafka for async decoupling.
- [`OBSERVABILITY.md`](../OBSERVABILITY.md) — RED metrics, OTel
  tracing, SLOs.
- Roy Fielding, *Architectural Styles and the Design of
  Network-Based Software Architectures* — the original REST
  dissertation.
- OpenAPI Initiative, *OpenAPI Specification 3.1.0*.
- RFC 8594 — `Sunset` header.
- RFC 8288 — `Link` header for pagination.
