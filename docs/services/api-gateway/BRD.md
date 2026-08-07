# api-gateway — Business Requirements Document

## 1. Document Purpose

This BRD is read by the platform's edge team, the security team, and
the SRE on-call rotation. It captures *why* the `api-gateway` exists
in the platform, the business capabilities it provides, the
business rules it enforces, and the KPIs against which it is
evaluated. It is the input to the SRS, ERD, and INTEGRATION docs in
this folder.

## 2. Business Context

The platform exposes 50+ microservices. Without a single, consistent
edge, every channel (customer web, customer mobile, driver app,
courier app, restaurant console, partner API, admin console) would
have to implement its own authentication, claim propagation, rate
limiting, CORS, request/response transformation, and observability
fan-out. The `api-gateway` is the single chokepoint that:

- **Reduces time-to-market** for new channels (one edge, many
  surfaces).
- **Centralizes security** so a fix to JWT validation, a Keycloak
  key rotation, or a new fraud signal can be rolled out platform-wide
  in hours, not weeks.
- **Reduces the cost of compliance** by making the edge the
  single place where PII handling, audit emission, and rate limits
  are enforced uniformly.
- **Improves availability** by absorbing DDoS, slow clients, and
  retry storms before they reach business services.

## 3. Objectives

| ID | Objective | Metric |
|----|-----------|--------|
| BR--001 | Provide a single, secure, observable edge for all public traffic. | 100% of public traffic served by the gateway (no direct service exposure). |
| BR--002 | Enforce platform-wide authentication and claim propagation consistently. | 100% of authenticated requests carry the standard `X-User-*` headers downstream. |
| BR--003 | Protect downstream services from abusive traffic. | < 0.1% of requests to downstream services are rate-limited / WAF-blocked at the gateway. |
| BR--004 | Emit an `audit.api.request.v1` for every authenticated request so security and analytics can correlate. | 100% sampling in production; 0 events lost. |
| BR--005 | Meet the Tier-1 SLO of 99.99% availability and P99 ≤ 50ms edge overhead. | SLO burn rate < 1x over 30 days. |
| BR--006 | Support zero-downtime configuration changes (route table, rate limits, CORS). | Config reloads observed with 0 dropped requests. |
| BR--007 | Reduce time-to-recovery for edge incidents (e.g. JWKS rotation, new fraud signal). | Median MTTR < 10 minutes for an edge-only incident. |

## 4. Stakeholders

| Stakeholder | Role | Interest |
|-------------|------|----------|
| Platform team (edge) | owner | SLO, capacity, configuration, security of the edge |
| Security team | approver | AuthN/AuthZ, WAF, key rotation, audit completeness |
| Service teams (50+ services) | consumer | predictable routing, claim headers, low overhead |
| Mobile / web channel teams | consumer | CORS, per-channel rate limits, OpenAPI exposure |
| SRE on-call | operator | alerts, runbooks, MTTR |
| Compliance | reviewer | audit emission, PII handling, GDPR / PCI scoping |
| Product (rides + food) | consumer | new route introductions; no edge regression on launch days |
| Partner integrations (B2B) | consumer | partner API keys; per-partner rate limits |

## 5. Actors / Personas

- **Customer (mobile/web)** — calls the gateway with a Keycloak JWT
  from the `platform-customer` realm. Expects the gateway to be
  fast, accept the channel's CORS policy, and rate-limit abuse.
- **Driver (mobile)** — same shape, but with the
  `platform-driver` realm token and a different rate-limit budget
  (lower per-minute, longer-lived token).
- **Courier (mobile)** — `platform-courier` realm; long-lived
  sessions for active shift work.
- **Restaurant / merchant staff** — confidential-client JWT from
  `platform-staff`; web console.
- **Admin / support agent** — `platform-internal` realm; MFA-gated;
  audited.
- **Partner (B2B)** — mTLS + JWT; separate rate-limit tier.
- **Service-to-service** — `client_credentials` JWT from
  `platform-services`; high QPS allowed but monitored.
- **Anonymous public** — discovery endpoints only (restaurant
  search, marketing pages); aggressive per-IP rate limit.
- **Bot / attacker** — blocked at WAF and rate limiter.

## 6. Business Capabilities

- **Edge TLS termination** (TLS 1.3) with HSTS and modern cipher
  enforcement.
- **JWT validation** (RS256) against Keycloak JWKS, with claim
  extraction, signature, issuer, audience, and lifetime checks.
- **Token revocation** via Redis-backed revocation set fed by
  `identity.*.v1` events.
- **Claim-to-header translation** so downstream services don't
  re-parse JWTs.
- **Per-route / per-token / per-IP rate limiting** with
  `RateLimit-*` response headers and `429 RATE_LIMITED`.
- **Path-based routing** with regex, prefix, and exact match,
  plus a version-aware selector.
- **CORS** per channel, configurable.
- **Request transformation** (header rewrite, body buffering
  limits, path re-write).
- **Response transformation** (error envelope normalization,
  stripping upstream internal headers).
- **Audit emission** (`audit.api.request.v1`) including
  `correlation_id`, `user_id`, `route`, `status`, `latency_ms`,
  body hash.
- **Distributed tracing propagation** (`traceparent`,
  `X-Request-Id`, alias `X-Correlation-Id` — see
  [ADR-0019](../../architecture/adrs/0019-request-id-at-the-edge.md)).
- **OpenAPI aggregation** (union of downstream specs, served at
  `/openapi.json` and rendered at `/docs`).
- **WAF / pattern blocking** (defense in depth).
- **Hot config reload** (route table, rate limits, CORS).

## 7. Business Requirements

| ID | Requirement | Priority | Source |
|----|-------------|----------|--------|
| BR--010 | The gateway MUST validate every inbound JWT against Keycloak's JWKS before forwarding. | MUST | platform security policy |
| BR--011 | The gateway MUST emit `audit.api.request.v1` for every authenticated request. | MUST | compliance |
| BR--012 | The gateway MUST apply a per-route, per-token rate limit and return 429 `RATE_LIMITED` when exceeded. | MUST | platform SRE / DDoS policy |
| BR--013 | The gateway MUST reject tokens in the Redis revocation set even if their signature is valid. | MUST | security policy |
| BR--014 | The gateway MUST translate the JWT `sub`, `user_type`, `tenant_id`, and roles into `X-User-Id`, `X-User-Type`, `X-Tenant-Id`, `X-Roles` request headers. | MUST | architecture/API_STANDARDS.md |
| BR--015 | The gateway MUST support zero-downtime reload of the route table, rate limits, and CORS policy. | MUST | SRE |
| BR--016 | The gateway MUST serve the platform's unified OpenAPI 3.1 spec at `/openapi.json` and Swagger UI at `/docs`. | MUST | developer experience |
| BR--017 | The gateway MUST accept the request id (header `X-Request-Id`; alias `X-Correlation-Id` accepted) or generate one (UUIDv7) if neither is sent, and propagate it as both `X-Request-Id` and `X-Correlation-Id` response headers, both outbound HTTP headers, both Kafka headers on every emitted event, the `correlation_id` field of every audit event, the MDC key `requestId`, and the OTel root-span attribute `platform.request_id`. The id MUST be stable across retries. See [ADR-0019](../../architecture/adrs/0019-request-id-at-the-edge.md). | MUST | observability |
| BR--018 | The gateway MUST return the standard error envelope (see `architecture/API_STANDARDS.md`) for upstream 4xx/5xx responses. | MUST | platform contract |
| BR--019 | The gateway MUST NOT log request bodies, JWTs, PAN, or other PII in production. | MUST | security / PII |
| BR--020 | The gateway SHOULD support per-channel CORS policies (web customer, web driver, etc.). | SHOULD | channel teams |
| BR--021 | The gateway SHOULD support partner API-key + JWT for B2B integrations. | SHOULD | partner program |
| BR--022 | The gateway MAY support request body signing (HMAC-SHA256) for high-value admin flows. | MAY | admin team |

## 8. Business Rules

| ID | Rule | Notes |
|----|------|-------|
| BR--030 | A request to any non-public endpoint without a valid JWT MUST be rejected with `401 UNAUTHENTICATED`. | No exceptions for first-party clients. |
| BR--031 | A request with a valid JWT but a missing required role for the matched route MUST be rejected with `403 FORBIDDEN`. | Coarse check at the edge; fine-grained checks downstream. |
| BR--032 | An `identity.user.suspended.v1` event MUST cause all of the user's current and future tokens to be rejected at the edge within 5 seconds (p99). | Bounded by Kafka consumer lag. |
| BR--033 | A rate-limited response MUST include `Retry-After` and the standard `RateLimit-*` headers. | See `architecture/API_STANDARDS.md`. |
| BR--034 | An audit event MUST be emitted before the response is returned to the client. | Outbox is not used here (no DB). The audit topic is on the critical path; producers are configured for synchronous acks. |
| BR--035 | A 5xx from an upstream MUST be translated to a 502/504 and wrapped in the standard error envelope. | `CIRCUIT_OPEN` if the breaker is open. |
| BR--036 | A request body larger than the configured limit (default 1 MiB) MUST be rejected with `413 PAYLOAD_TOO_LARGE`. | Per-route overrides allowed. |

## 9. Assumptions

- Keycloak is reachable and JWKS is cacheable for at least 5
  minutes per refresh interval.
- Redis is available with < 5ms p99 latency for the
  revocation set and rate-limit counters.
- Kafka is available; the gateway's audit topic has
  replication factor ≥ 3 and acks=all.
- All downstream services expose `/ready` and honor the
  `/health` contract.
- The platform's per-service OpenAPI specs are versioned in
  the `/openapi.json` aggregate by their service's own
  semver.
- Mobile and web clients send `X-Request-Id` (preferred) or
  `X-Correlation-Id` (alias) when available; if neither is sent,
  the gateway generates a UUIDv7. The gateway always returns the
  value in the response as both `X-Request-Id` and
  `X-Correlation-Id` headers. See
  [ADR-0019](../../architecture/adrs/0019-request-id-at-the-edge.md).
- The platform deploys Envoy with WASM/Lua filters; the
  legacy Kong deployment is supported for staged migration.

## 10. Constraints

- **No business state**: no PostgreSQL, no service-owned schema.
- **Latency budget**: edge overhead P99 ≤ 50ms (signature
  verify, header translation, rate-limit lookup, audit emit).
- **Throughput target**: 100k req/s/region sustained; 500k
  req/s/region burst.
- **TLS only**: TLS 1.3; no HTTP plaintext accepted in any
  environment, including dev.
- **MUST run on the platform's standard CI/CD**; no bespoke
  deployment pipeline.
- **No upstream database reads**; all state needed at the
  edge comes from Redis, JWKS, or in-process config.

## 11. Dependencies

| Dependency | Type | Notes |
|------------|------|-------|
| Keycloak | service | JWKS provider; admin client for key-rotation events |
| `identity-service` | service | emits `identity.*.v1` events that drive the revocation set |
| `configuration-service` | service | emits `configuration.updated.v1`; route table, rate limits, CORS |
| `audit-service` | consumer | consumes `audit.api.request.v1` |
| ``reporting-service` (data lake)` | consumer | consumes `audit.api.request.v1` for traffic analytics |
| Redis (shared) | infra | revocation set, rate-limit counters, JWKS cache |
| Kafka (shared) | infra | audit topic + consumed identity/config events |
| Vault | infra | secrets (Keycloak admin, Redis, Kafka SASL) |

## 12. Business Workflows

- **Authenticated request flow** — JWT validation → claim
  translation → rate-limit → route → audit emit → upstream call →
  response. (Detailed in `WORKFLOWS.md`.)
- **Token revocation fan-out** — `identity.*.v1` event → Redis
  write. (Detailed in `WORKFLOWS.md`.)
- **Configuration hot reload** — `configuration.updated.v1` →
  in-memory config swap. (Detailed in `WORKFLOWS.md`.)
- **Partner B2B request** — mTLS terminate → API key check → JWT
  validate (with partner realm) → rate limit (per-partner tier) →
  route. (Detailed in `WORKFLOWS.md`.)

## 13. Exception Workflows

- **Invalid JWT signature** → 401 `UNAUTHENTICATED`.
- **Expired JWT** → 401 `UNAUTHENTICATED`, body
  `code: "TOKEN_EXPIRED"`.
- **Revoked JWT** (jti in Redis) → 401
  `code: "TOKEN_REVOKED"`.
- **Suspended user** → 403 `code: "USER_SUSPENDED"`.
- **Missing required role** → 403 `FORBIDDEN`.
- **Rate-limited** → 429 `RATE_LIMITED` with `Retry-After`.
- **Upstream 5xx** → 502/504 `DEPENDENCY_UPSTREAM_FAILURE` or
  `DEPENDENCY_TIMEOUT`.
- **Upstream timeout** → 504 `DEPENDENCY_TIMEOUT`.
- **Circuit breaker open** → 503 `CIRCUIT_OPEN`.
- **Body too large** → 413 `PAYLOAD_TOO_LARGE`.
- **WAF block** → 403 `code: "WAF_BLOCKED"`.
- **Body fails JSON Schema validation at upstream** → 400
  `VALIDATION_FAILED` (envelope from upstream; gateway does not
  reformat it).

## 14. Success Criteria

- All 50+ downstream services route through the gateway; no
  direct public exposure.
- 100% of authenticated requests produce an
  `audit.api.request.v1` event.
- P99 edge overhead ≤ 50ms at the 100k req/s/region target.
- 99.99% monthly availability.
- Zero JWT secret / signing key leaks in 12 months.
- Zero customer-visible incidents caused by gateway config
  reloads in 12 months.

## 15. KPIs

| KPI | Target | Measurement |
|-----|--------|-------------|
| Edge availability | ≥ 99.99% per 30d | uptime / total time per region |
| Edge overhead P99 | ≤ 50ms | gateway_request_duration_seconds - upstream_duration_seconds |
| Audit emission completeness | 100% | (audit_events_emitted / authenticated_requests) |
| Request-id propagation completeness | 100% | (audit_events_with_correlation_id_eq_response_request_id) / (audit_events) |
| Rate-limit accuracy | ≥ 99% (false positives < 0.1%) | (false_positives / total_rate_limited) |
| Token revocation lag | P99 ≤ 5s | (revocation_event_arrival → Redis SET → next request rejected) |
| Config reload success rate | ≥ 99.99% | (successful_reloads / total_reload_attempts) |
| MTTR (edge-only incident) | ≤ 10 min median | incident review board |

## 16. Acceptance Criteria

- The gateway validates JWTs against Keycloak's JWKS for all
  matched routes; failures return 401 with the standard error
  envelope.
- The gateway emits `audit.api.request.v1` for every
  authenticated request; the topic is consumed by `audit-service`
  with no losses.
- The gateway applies per-route rate limits; exceeding the
  limit returns 429 with `Retry-After` and the standard
  `RateLimit-*` headers.
- A new service can be added to the platform with a config
  change only (no gateway code change).
- The gateway serves `/openapi.json` as a valid OpenAPI 3.1
  document that includes every downstream service's endpoints
  with the correct auth annotations.
- A revocation event causes a follow-up request with the
  corresponding token to be rejected within 5 seconds (P99).
- A `configuration.updated.v1` event causes the gateway to
  reload affected configuration without dropping any in-flight
  requests.
- The gateway logs never contain JWTs, passwords, PAN, OTPs, or
  full request bodies in production.
- Every response carries both `X-Request-Id` and `X-Correlation-Id`
  set to the same value; the same value is the
  `correlation_id` of the `audit.api.request.v1` event for the
  request, the `platform.request_id` attribute on the OTel root
  span, the MDC `requestId` of every log line in the request
  scope, and the `X-Request-Id` and `X-Correlation-Id` Kafka
  headers on the produced event. The id is stable across
  retries. See
  [ADR-0019](../../architecture/adrs/0019-request-id-at-the-edge.md).

---

## See also

### Sibling docs for this service

- [`README.md`](./README.md) — purpose, bounded context, responsibilities
- [`BRD.md`](./BRD.md) — business requirements
- [`SRS.md`](./SRS.md) — functional + non-functional requirements
- [`ERD.md`](./ERD.md) — data model (entities, relationships)
- [`INTEGRATION.md`](./INTEGRATION.md) — inter-service contracts (APIs, events, sagas)
- [`WORKFLOWS.md`](./WORKFLOWS.md) — operational workflows (happy paths, failure modes)
- [`TECH.md`](./TECH.md) — technology profile (runtime, libraries, data layer, admin endpoints, RBAC)

### Platform-wide

- [`../../shared/README.md`](../../shared/README.md) — `platform-spring-boot-starter` shared library (the single source of cross-cutting code for all Spring Boot services in the platform)
- [`../RECOMMENDATIONS.md`](../RECOMMENDATIONS.md) — platform-wide technology map (language, framework, version baseline, admin/RBAC pattern)
- [`../../README.md`](../../README.md) — services overview (the catalog of all 20 services)
- [`../../../main.md`](../../../main.md) — top-level platform specification (architecture, Keycloak, PostgreSQL 18, messaging, observability baseline)

