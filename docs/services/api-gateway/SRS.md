# api-gateway — Software Requirements Specification

## 1. Introduction

This document specifies the software behavior, contracts, and
non-functional requirements of the `api-gateway` service. The
gateway is the platform's single north-south edge. It is stateless
beyond a small Redis-backed set used for token revocation and
rate-limit counters, and an in-process configuration cache. This
SRS is the input to implementation and to the test plan that ships
with each gateway release.

## 2. Scope

**In scope:**

- TLS termination, JWT validation, claim-to-header translation.
- Path-based routing to downstream services.
- Per-route, per-token, per-IP rate limiting.
- Request and response transformation (header rewrite, error
  envelope, body-size limits).
- CORS per channel.
- Audit emission (`audit.api.request.v1`).
- Hot config reload from `configuration-service`.
- OpenAPI 3.1 aggregation at `/openapi.json`.
- Health, readiness, and startup probes.
- Distributed tracing (OpenTelemetry) and structured logging.
- WAF-style pattern blocking (defense in depth).

**Out of scope:**

- Any business logic, validation of business rules, or
  orchestration of multi-service flows.
- Persistent storage of business data; the gateway owns no
  PostgreSQL schema.
- User-facing UI; the gateway is an API edge.
- Authentication decisioning; the gateway verifies Keycloak
  tokens, it does not decide who can do what.
- Provider credentials (payment, SMS, maps); downstream
  services own those.

## 3. System Context

```mermaid
flowchart LR
    subgraph Clients
        CUS[Customer app]
        DRV[Driver app]
        CRR[Courier app]
        MER[Merchant console]
        ADM[Admin console]
        SUP[Support console]
        B2B[Partner API]
    end
    GW[api-gateway]
    KC[Keycloak]
    ID[identity-service]
    CFG[configuration-service]
    REDIS[(Redis)]
    KAFKA[(Kafka)]
    DSVC[Downstream services]
    AUD[audit-service]
    ANA["`reporting-service` (data lake)]
    CUS -->|HTTPS| GW
    DRV -->|HTTPS| GW
    CRR -->|HTTPS| GW
    MER -->|HTTPS| GW
    ADM -->|HTTPS| GW
    SUP -->|HTTPS| GW
    B2B -->|HTTPS + mTLS| GW
    GW -->|JWKS| KC
    GW -->|revocation events| KAFKA
    ID -->|identity.*.v1| KAFKA
    CFG -->|configuration.updated.v1| KAFKA
    GW -->|revocation set, RL counters| REDIS
    GW -->|audit.api.request.v1| KAFKA
    KAFKA --> AUD
    KAFKA --> ANA
    GW -->|HTTP/2| DSVC
```

## 4. Actors

- **Human clients**: customer, driver, courier, merchant staff,
  admin, support agent.
- **System clients**: partner B2B callers, internal
  service-to-service callers, Keycloak (JWKS provider).
- **Internal subsystems**: Redis (revocation set, rate-limit
  counters, JWKS cache), Kafka (audit topic, identity events,
  configuration events).

## 5. Functional Requirements

| ID | Requirement | Priority |
|----|-------------|----------|
| FR--001 | Validate the inbound JWT (RS256) against Keycloak's JWKS for every request to a non-public route. | MUST |
| FR--002 | Reject any request with a missing, malformed, expired, or signature-invalid JWT with `401 UNAUTHENTICATED`. | MUST |
| FR--003 | Reject any request whose token's `jti` is in the Redis revocation set, with `401 TOKEN_REVOKED`. | MUST |
| FR--004 | Reject any request whose `sub` is in the Redis suspended-sub set, with `403 USER_SUSPENDED`. | MUST |
| FR--005 | Translate the JWT claims (`sub`, `user_type`, `tenant_id`, `roles`, `scopes`, `region`, `device_id`, `email_verified`, `phone_verified`) into standardized `X-User-*` and `X-Tenant-Id` headers before forwarding. | MUST |
| FR--006 | Match the request path and method to a configured route; forward the request to the configured upstream with HTTP/2. | MUST |
| FR--007 | Apply per-route rate limits: by token when present, by IP otherwise, with a per-route `limit` and `window_seconds`. | MUST |
| FR--008 | Emit `audit.api.request.v1` for every authenticated request before returning the response. The event's `correlation_id` is the request id (per FR--012). | MUST |
| FR--009 | Serve the platform-wide OpenAPI 3.1 aggregate at `GET /openapi.json` and the Swagger UI at `GET /docs`. | MUST |
| FR--010 | Apply CORS preflight and actual responses per channel policy. | MUST |
| FR--011 | Translate upstream 4xx/5xx into the standard error envelope (`code`, `message`, `correlationId`, `details[]`). The envelope's `correlationId` is the request id (per FR--012). | MUST |
| FR--012 | Accept the request id (read `X-Request-Id`; if absent, read `X-Correlation-Id`; if both absent, generate UUIDv7). If both are sent, use the value of `X-Request-Id`. Set the same value as both `X-Request-Id` and `X-Correlation-Id` response headers, both outbound HTTP headers on every downstream call, both Kafka headers on every emitted event, the `correlation_id` field of every audit event, the MDC key `requestId`, and the OTel root-span attribute `platform.request_id`. The id is stable across retries. See [ADR-0019](../../architecture/adrs/0019-request-id-at-the-edge.md). | MUST |
| FR--013 | Hot-reload the route table, rate limits, CORS policy, error mapping, and JWKS refresh interval on `configuration.updated.v1`. | MUST |
| FR--014 | Reject request bodies larger than the configured limit (default 1 MiB) with `413 PAYLOAD_TOO_LARGE`. | MUST |
| FR--015 | Surface the gateway's own `/health`, `/ready`, `/started` endpoints. | MUST |
| FR--016 | Enforce WAF-style pattern blocks (SQLi, XXE, path traversal) on well-known attack vectors as defense in depth. | SHOULD |
| FR--017 | Support partner B2B calls with mTLS at the network layer and a per-partner API key + JWT at the application layer. | SHOULD |
| FR--018 | Support per-route request signing (HMAC-SHA256) for high-value admin flows. | SHOULD |
| FR--019 | Expose a Prometheus-format `/metrics` endpoint for scraping. | MUST |
| FR--020 | Support an internal admin port (bound to `127.0.0.1`) with a `POST /admin/reload` to force a config reload. | SHOULD |

## 6. Non-Functional Requirements

| ID | Category | Requirement | Target |
|----|----------|-------------|--------|
| NFR--001 | availability | monthly uptime | 99.99% |
| NFR--002 | performance | P99 edge overhead (JWT + headers + rate-limit + audit ack) | ≤ 50 ms |
| NFR--003 | performance | P50 edge overhead | ≤ 10 ms |
| NFR--004 | scalability | sustained throughput per region | 100,000 req/s |
| NFR--005 | scalability | burst throughput per region | 500,000 req/s |
| NFR--006 | scalability | horizontal scale | 3 → 30 replicas per region |
| NFR--007 | scalability | concurrent in-flight requests per replica | ≥ 1,000 |
| NFR--008 | maintainability | MTTR for an edge-only incident | ≤ 10 min median |
| NFR--009 | maintainability | gateway binary startup | ≤ 5 s |
| NFR--010 | observability | SLO burn-rate alerts wired | yes, multi-window |
| NFR--011 | deployability | zero-downtime config reload | yes (no in-flight drops) |
| NFR--012 | deployability | blue/green deployable | yes |
| NFR--013 | reliability | audit emission success | 100% (sync ack before response) |
| NFR--014 | reliability | revocation fan-out P99 | ≤ 5 s end-to-end |

## 7. API Requirements

The gateway is the platform's public API surface. It conforms to
`architecture/API_STANDARDS.md` and aggregates the OpenAPI specs
of every downstream service. The aggregate spec is published at
`/openapi.json` and rendered at `/docs`. The gateway itself
exposes only the small set of infrastructure endpoints listed in
the `README.md` API table (health, ready, started, openapi, docs).
Full upstream contracts are in each downstream service's
`INTEGRATION.md`; the gateway does not duplicate them.

## 8. Data Requirements

| ID | Requirement | Notes |
|----|-------------|-------|
| DATA--001 | The gateway MUST NOT own a PostgreSQL schema. | Stateless. |
| DATA--002 | The gateway MUST use a Redis logical DB index and a `gateway:` key prefix for all keys it writes. | Multi-tenant key namespace. |
| DATA--003 | The gateway MUST write revoked `jti` values with a TTL equal to the access-token's remaining lifetime. | Automatic expiry. |
| DATA--004 | The gateway MUST write suspended/disabled `sub` values with a long TTL (default 30 days) and refresh on repeated events. | Idempotent. |
| DATA--005 | The gateway MUST cache the JWKS in Redis with a TTL aligned to the JWKS refresh interval (default 300 s). | Multi-instance JWKS hit. |
| DATA--006 | The gateway MUST use atomic Redis operations for rate-limit counters (`INCR` + `EXPIRE`) and revocation-set membership (`SISMEMBER`). | No race conditions. |
| DATA--007 | The gateway MUST NOT log request bodies, JWTs, PAN, or any PII in production. | Allowed: SHA-256 of body for audit diff. |

## 9. Validation Rules

- The `Authorization: Bearer <jwt>` header MUST be present for any
  non-public route.
- The JWT MUST be RS256-signed, with a `kid` header that resolves
  to a Keycloak JWKS key.
- The JWT's `iss` MUST be in the gateway's allowed-issuers set.
- The JWT's `aud` MUST be in the gateway's allowed-audiences set.
- The JWT's `exp` MUST be in the future; `nbf` MUST be in the past.
- The JWT MUST contain a `sub` claim.
- The request's body, if present, MUST be a JSON document whose
  size is below the route's `body_max_bytes`.
- A request to a route that requires a specific role MUST carry
  that role in either `realm_access.roles` or the client-specific
  role claim.
- A rate-limited request MUST be rejected with `429 RATE_LIMITED`
  and `Retry-After`.

## 10. State Transitions

The gateway itself is stateless; the only state is the revocation
set, the rate-limit counters, and the JWKS cache (all in Redis),
plus the in-process configuration cache. State transitions are
documented in `WORKFLOWS.md` for the revocation fan-out flow.

## 11. Authorization Requirements

- **Coarse role check**: at the gateway, for every matched route,
  the set of required roles is read from the configuration; if
  any required role is missing from the JWT, the gateway returns
  `403 FORBIDDEN` with `code: "FORBIDDEN"`.
- **Tenant scoping**: if the JWT carries a `tenant_id` claim and
  the route requires tenant scoping, the gateway adds
  `X-Tenant-Id: <tenant_id>` to the forwarded request.
- **Service-to-service**: when the JWT's `azp` is in the
  `platform-services` realm, the gateway forwards the
  `client_credentials` token's `azp` as `X-User-Id` and
  `X-User-Type: "service"`.
- **Resource-level checks**: the gateway does not perform
  resource-level checks. They are the downstream service's
  responsibility.

## 12. Configuration Requirements

The gateway reads its configuration from `configuration-service`
under the prefix `gateway.*` and consumes `configuration.updated.v1`
to hot-reload. Keys are listed in `README.md` 13.

## 13. Error Handling

| Condition | Response |
|-----------|----------|
| Missing/invalid Authorization header | 401 `UNAUTHENTICATED` |
| Expired JWT | 401 `UNAUTHENTICATED` with `code: "TOKEN_EXPIRED"` |
| Signature invalid / unknown kid | 401 `UNAUTHENTICATED` with `code: "TOKEN_INVALID"` |
| Revoked jti | 401 `UNAUTHENTICATED` with `code: "TOKEN_REVOKED"` |
| Suspended sub | 403 `FORBIDDEN` with `code: "USER_SUSPENDED"` |
| Disabled sub | 403 `FORBIDDEN` with `code: "USER_DISABLED"` |
| Missing required role | 403 `FORBIDDEN` |
| Rate limit exceeded | 429 `RATE_LIMITED` with `Retry-After` |
| Body too large | 413 `PAYLOAD_TOO_LARGE` |
| WAF block | 403 `FORBIDDEN` with `code: "WAF_BLOCKED"` |
| Upstream 5xx | 502 `BAD_GATEWAY` with `code: "DEPENDENCY_UPSTREAM_FAILURE"` |
| Upstream timeout | 504 `GATEWAY_TIMEOUT` with `code: "DEPENDENCY_TIMEOUT"` |
| Circuit breaker open | 503 `SERVICE_UNAVAILABLE` with `code: "CIRCUIT_OPEN"` |
| Bad JSON / schema fail | 400 `VALIDATION_FAILED` with `details[]` |

All error responses use the standard envelope defined in
`architecture/API_STANDARDS.md`.

## 14. Concurrency Requirements

- The gateway MUST handle ≥ 1,000 concurrent in-flight requests
  per replica without head-of-line blocking.
- The gateway MUST use lock-free data structures for the
  in-process route table and the in-process rate-limit config
  (atomic snapshot swap).
- Each upstream has a configurable concurrency cap (bulkhead)
  enforced by an in-process semaphore.
- The Redis revocation-set lookup is `O(1)` (`SISMEMBER`); the
  gateway's per-request Redis work is bounded to ≤ 3 commands
  (revocation set, suspended sub, rate-limit counter `INCR`).

## 15. Idempotency Requirements

- The gateway does not enforce `Idempotency-Key` itself; it
  passes the header through to the downstream service.
- The gateway's `audit.api.request.v1` emission is keyed by
  `correlation_id`; duplicate events with the same `correlation_id`
  are tolerated by the audit consumer (the audit topic's
  partition key is `correlation_id` for a consistent ordering of
  an end-to-end flow).
- A retried `configuration.updated.v1` is a no-op if the
  configuration version stamp matches the in-memory one.
- The request id (per FR--012) is **stable across retries** — a
  retried request with the same client-supplied id (or the same
  `Idempotency-Key`) keeps the same id; the audit topic is
  partitioned by `correlation_id` so the same request id lands on
  the same partition and is processed in order.

## 16. Performance

- **Dominant path**: JWT signature verify (cached JWKS) → header
  set → Redis `SISMEMBER` (revocation) → Redis `INCR` (rate
  limit) → upstream HTTP/2 round-trip → Kafka audit emit → response.
- **P50 / P95 / P99 edge overhead** (signature verify + headers
  + RL + audit): 5 / 20 / 50 ms.

## 17. Scalability

- **Horizontal**: stateless; the only shared state is Redis
  (revocation, rate limit, JWKS) and Kafka (audit, consumed
  events). Linear horizontal scale up to the cluster's connection
  limit.
- **Vertical**: Envoy is memory-efficient; CPU-bound at very high
  QPS. 2 vCPU / 2 GiB is the production default; can scale to
  4 vCPU / 4 GiB at the burst target.
- **HPA**: CPU 60% target; custom metric
  `gateway_requests_in_flight` (target 1000/instance); custom
  metric `gateway_request_duration_seconds:p99` (target ≤ 500 ms).

## 18. Availability

- **SLO**: 99.99% per month, per region.
- **Error budget**: ~4 min / 30d.
- **Maintenance window**: none planned; the gateway supports
  in-place config reload and rolling deploys.
- **Multi-region**: deployed to each region; clients pin to their
  nearest region via the global load balancer.

## 19. Security

| ID | Requirement | Notes |
|----|-------------|-------|
| SEC--001 | Validate JWT signature with RS256 against Keycloak JWKS. | Public-key verification. |
| SEC--002 | Enforce `iss`, `aud`, `exp`, `nbf` checks on every JWT. | Reject mismatches. |
| SEC--003 | Maintain a Redis revocation set updated from `identity.*.v1` events. | TTL = remaining access-token lifetime. |
| SEC--004 | Reject all WAF-style pattern matches (SQLi, XXE, path traversal) at the gateway. | Defense in depth. |
| SEC--005 | Enforce per-route, per-token, per-IP rate limits. | DDoS mitigation. |
| SEC--006 | TLS 1.3 only at the public edge; HSTS with `max-age=31536000; includeSubDomains; preload`. | Modern cipher suite only. |
| SEC--007 | No PII, JWT, PAN, OTP, or full body logged in production. | Allowlist of fields only. |
| SEC--008 | Secrets in Vault only; rotated quarterly. | No env-file secrets. |
| SEC--009 | Internal admin port bound to `127.0.0.1`. | Defense against external admin access. |
| SEC--010 | mTLS to upstream services via sidecar (Istio/Linkerd). | Network-layer identity. |

## 20. Privacy

- The gateway stores no personal data in a database. Its only
  state is revocation-set entries (`jti`, `sub`) and rate-limit
  counters.
- Revocation entries expire automatically when the underlying
  token would have expired; suspended-sub entries expire after
  30 days and are refreshed on repeated events.
- A right-to-erasure (GDPR) request causes the
  `identity.user.disabled.v1` event; the gateway's revocation
  lookup is by `sub`, so the entry covers the request without
  any data being retained beyond the TTL.
- No personal data is logged in production.

## 21. Auditability

- The gateway emits `audit.api.request.v1` for every
  authenticated request with `correlation_id`, `user_id`,
  `user_type`, `route`, `method`, `status`, `latency_ms`,
  `client_ip` (truncated /24), `user_agent` (truncated), and
  `body_sha256`.
- Rate-limit rejections are emitted with
  `code: "RATE_LIMITED"` and a `correlation_id`.
- Token revocations are recorded as `identity.session.revoked.v1`
  consumed from `identity-service` (the gateway does not
  duplicate this event).
- Admin actions against the internal admin port are logged to
  stdout with `actor_id`, `action`, `target`, and `result`.

## 22. Observability

- **Logs**: JSON to stdout; fields listed in `README.md` 15.
- **Metrics**: RED per route and per upstream. Plus:
  `gateway_rate_limit_rejections_total`,
  `gateway_jwt_verification_failures_total`,
  `gateway_revocation_set_size`,
  `gateway_circuit_breaker_state`,
  `gateway_audit_events_emitted_total`.
- **Traces**: OpenTelemetry. Root span per request, named
  `{METHOD} {route}`. Sample 100% on errors, 10% on success in
  production; 100% in staging.
- **Alerts**: SLO burn-rate alerts (multi-window) for
  availability and edge overhead; anomaly alerts on rate-limit
  rejection rate and on JWKS failure rate.
- **Dashboards**: gateway overview, per-upstream latency,
  per-route errors, audit emission rate.

## 23. Maintainability

- **Code style**: platform-standard Go (Envoy/WASM) or Lua.
  `gofmt`, `golangci-lint`, `gosec`, `staticcheck`.
- **Test coverage**: ≥ 85% for the JWT, header-translation, and
  rate-limit paths; 100% for the WAF pattern matches.
- **Documentation**: this folder, plus inline package docs and
  the platform's edge runbook in `IR/runbooks/edge.md`.
- **Config as code**: the route table, rate limits, and CORS
  policy live in `configuration-service` and are reviewed in PRs
  there.

## 24. Disaster Recovery

- **RPO**: zero for in-process config (loaded from
  `configuration-service` on every replica); revocation set is
  reconstructible from `identity.*.v1` Kafka history.
- **RTO**: ≤ 5 min per region (a new replica boots in seconds;
  the global load balancer re-routes in seconds).
- **Redis loss**: the gateway degrades to fail-closed on the
  revocation set (reject all requests with a security alert).
  Rate-limit counters reset (acceptable: short over-limit window
  for clients).

## 25. Acceptance Criteria

- A request to any non-public route without a JWT returns
  `401 UNAUTHENTICATED`.
- A request with a valid JWT and required role is routed to the
  right upstream, and the upstream receives the
  `X-User-Id`, `X-User-Type`, `X-Tenant-Id`, and `X-Roles`
  headers.
- An `audit.api.request.v1` event is observed on the audit topic
  for the request, with the same `correlation_id` as the
  response's `X-Correlation-Id` header.
- An `identity.session.revoked.v1` event causes the next
  request with the corresponding `jti` to be rejected within
  5 seconds (P99).
- A `configuration.updated.v1` event with a new route table
  causes a follow-up request to the new path to be routed
  correctly without any in-flight requests being dropped.
- The `/openapi.json` aggregate is a valid OpenAPI 3.1 document
  that includes every downstream service's endpoints with the
  correct auth annotations and the standard error envelope.
- The P99 edge overhead at 100k req/s/region sustained is
  ≤ 50 ms.
- A rate-limited request returns `429 RATE_LIMITED` with
  `Retry-After` and the standard `RateLimit-*` headers.
- An upstream 5xx response is translated to
  `502 DEPENDENCY_UPSTREAM_FAILURE` with the standard error
  envelope and a `correlation_id` that matches the audit event.
- A client request with no `X-Request-Id` and no `X-Correlation-Id`
  receives both response headers set to a UUIDv7; the same UUIDv7
  is observable as the `correlation_id` of the audit topic event,
  the `requestId` MDC of every log line, the `platform.request_id`
  attribute on the OTel root span, and the `X-Request-Id` /
  `X-Correlation-Id` Kafka headers on the produced event. A
  client request with `X-Request-Id: A` and `X-Correlation-Id: B`
  (different values) receives both response headers set to `A`.
  See [ADR-0019](../../architecture/adrs/0019-request-id-at-the-edge.md).

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

