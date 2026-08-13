# api-gateway — Implementation Plan

**Domain:** Platform Foundation
**Tier:** 0
**Technology:** Go + chi
**Criticality:** T1 (99.95% SLO)
**DB Schema:** `—`
**Cache:** Redis — rate-limit counters, JWKS cache, revocation set
**HPA:** RPS, 5–100, p99 < 5ms

---

## Purpose

The `api-gateway` is the platform's single, stateless, north-south edge for every external client. It terminates TLS, validates JWTs against Keycloak, translates claims into request headers, applies rate limits, routes requests to the right downstream microservice, and emits an `audit.api.request.v1` event for every authenticated call.

---

## Tasks

### Phase 1 — Database & Domain Model

| ID | Task | Status | Depends-On | Required Role(s) | Approver Role | Co-Signer Role | Break-Glass? |
|---|---|---|---|---|---|---|---|
| T-GW-01 | No database schema (stateless service) | pending | — | platform.engineering | platform.engineering | — | — |
| T-GW-02 | Define in-process config snapshot struct (routes, rate limits, CORS, JWKS settings) | pending | T-GW-01 | platform.engineering | platform.engineering | — | — |
| T-GW-03 | Implement atomic in-memory config swap for hot-reload | pending | T-GW-02 | platform.engineering | platform.engineering | — | — |
### Phase 2 — REST API

| ID | Task | Status | Depends-On | Required Role(s) | Approver Role | Co-Signer Role | Break-Glass? |
|---|---|---|---|---|---|---|---|
| T-GW-01 | `ANY /v1/{service}/{resource}` — forward to matched downstream service with JWT validation, rate-limit, correlation ID | pending | — | platform.engineering | platform.engineering | — | — |
| T-GW-02 | `GET /openapi.json` — serve aggregate OpenAPI 3.1 document | pending | T-GW-01 | platform.engineering | platform.engineering | — | — |
| T-GW-03 | `GET /docs` — serve Swagger UI | pending | T-GW-02 | platform.engineering | platform.engineering | — | — |
| T-GW-04 | `GET /health` — liveness probe | pending | T-GW-03 | platform.engineering | platform.engineering | — | — |
| T-GW-05 | `GET /ready` — readiness (checks JWKS cached, Redis reachable, at least one upstream reachable) | pending | T-GW-04 | platform.engineering | platform.engineering | — | — |
| T-GW-06 | `GET /started` — startup probe (initial config loaded, route table built) | pending | T-GW-05 | platform.engineering | platform.engineering | — | — |
| T-GW-07 | `POST /admin/reload` — hot-reload in-process config (internal, `127.0.0.1` only, mTLS) | pending | T-GW-06 | platform.engineering | platform.engineering | — | — |
### Phase 3 — Event Publishing

| ID | Task | Status | Depends-On | Required Role(s) | Approver Role | Co-Signer Role | Break-Glass? |
|---|---|---|---|---|---|---|---|
| T-GW-01 | Implement in-process Kafka producer (no outbox — stateless) | pending | — | platform.engineering | platform.engineering | — | — |
| T-GW-02 | Publish `audit.api.request.v1` → topic `audit.api.request` (every authenticated request) | pending | T-GW-01 | audit.api.request | audit.api.request | — | — |
| T-GW-03 | Publish `gateway.config.reloaded.v1` → topic `platform.gateway.config.reloaded` (on successful hot-reload) | pending | T-GW-02 | platform.engineering | platform.engineering | — | — |
| T-GW-04 | Publish `gateway.rate_limit.exceeded.v1` → topic `platform.gateway.rate_limit.exceeded` (on 429 rejection) | pending | T-GW-03 | platform.engineering | platform.engineering | — | — |
| T-GW-05 | Publish `gateway.circuit_breaker.opened.v1` → topic `platform.gateway.circuit_breaker` (on CB state transition) | pending | T-GW-04 | platform.gateway.circuit_breaker | platform.gateway.circuit_breaker | — | — |
| T-GW-06 | Producer retry: 3 attempts with exponential backoff; DLQ per topic | pending | T-GW-05 | platform.engineering | platform.engineering | — | — |
### Phase 4 — Event Consumption

| ID | Task | Status | Depends-On | Required Role(s) | Approver Role | Co-Signer Role | Break-Glass? |
|---|---|---|---|---|---|---|---|
| T-GW-01 | Implement in-process inbox (keyed by `event_id`, TTL 24h) | pending | — | platform.engineering | platform.engineering | — | — |
| T-GW-02 | Consume `identity.session.revoked.v1` → write `jti` to Redis revoked set with TTL = remaining access-token lifetime | pending | T-GW-01 | platform.engineering | platform.engineering | — | — |
| T-GW-03 | Consume `identity.user.suspended.v1` → write `kc_sub` to Redis suspended-sub set (TTL 30d) | pending | T-GW-02 | platform.engineering | platform.engineering | — | — |
| T-GW-04 | Consume `identity.user.disabled.v1` → write `kc_sub` to Redis disabled set (no expiry) | pending | T-GW-03 | platform.engineering | platform.engineering | — | — |
| T-GW-05 | Consume `configuration.updated.v1` → hot-reload routes, rate limits, CORS, JWKS refresh interval | pending | T-GW-04 | platform.engineering | platform.engineering | — | — |
### Phase 5 — Caching

| ID | Task | Status | Depends-On | Required Role(s) | Approver Role | Co-Signer Role | Break-Glass? |
|---|---|---|---|---|---|---|---|
| T-GW-01 | Redis rate-limit counters: per-token, per-IP, per-route (sliding window) | pending | — | platform.engineering | platform.engineering | — | — |
| T-GW-02 | Redis JWKS cache (TTL configurable, default 5 min) | pending | T-GW-01 | platform.engineering | platform.engineering | — | — |
| T-GW-03 | Redis revocation set: `gateway:revoked:jti:<jti>` and `gateway:revoked:sub:<kc_sub>` | pending | T-GW-02 | platform.engineering | platform.engineering | — | — |
| T-GW-04 | Cache invalidation on `identity.session.revoked.v1` and `identity.user.suspended/disabled.v1` | pending | T-GW-03 | platform.engineering | platform.engineering | — | — |
### Phase 6 — External Integrations

| ID | Task | Status | Depends-On | Required Role(s) | Approver Role | Co-Signer Role | Break-Glass? |
|---|---|---|---|---|---|---|---|
| T-GW-01 | Keycloak JWKS (`/realms/{realm}/protocol/openid-connect/certs`) — periodic refresh + event-driven rotation | pending | — | platform.engineering | platform.engineering | — | — |
| T-GW-02 | Keycloak OIDC discovery — `/.well-known/openid-configuration` | pending | T-GW-01 | platform.engineering | platform.engineering | — | — |
| T-GW-03 | Keycloak token introspection for partner B2B (cache-miss path) | pending | T-GW-02 | platform.engineering | platform.engineering | — | — |
| T-GW-04 | `identity-service` — internal introspection helper | pending | T-GW-03 | platform.engineering | platform.engineering | — | — |
| T-GW-05 | Circuit breakers on all upstreams (default: open after 5 failures in 10s, reset after 30s) | pending | T-GW-04 | platform.engineering | platform.engineering | — | — |
### Phase 7 — Security

| ID | Task | Status | Depends-On | Required Role(s) | Approver Role | Co-Signer Role | Break-Glass? |
|---|---|---|---|---|---|---|---|
| T-GW-01 | JWT bearer auth via `coreos/go-oidc v3` (RS256, `iss` + `aud` + `exp` + `nbf` + revocation set) | pending | — | platform.engineering | platform.engineering | — | — |
| T-GW-02 | Required scopes/roles: coarse role check per route at gateway; fine-grained check in downstream | pending | T-GW-01 | platform.engineering | platform.engineering | — | — |
| T-GW-03 | WAF-style pattern blocking (SQLi, XXE, path traversal) | pending | T-GW-02 | platform.engineering | platform.engineering | — | — |
| T-GW-04 | mTLS for in-cluster traffic (Istio/Linkerd sidecar) | pending | T-GW-03 | platform.engineering | platform.engineering | — | — |
| T-GW-05 | No request body logging in production (SHA-256 body hash only) | pending | T-GW-04 | platform.engineering | platform.engineering | — | — |
| T-GW-06 | Secrets via HashiCorp Vault | pending | T-GW-05 | platform.engineering | platform.engineering | — | — |
### Phase 8 — Observability

| ID | Task | Status | Depends-On | Required Role(s) | Approver Role | Co-Signer Role | Break-Glass? |
|---|---|---|---|---|---|---|---|
| T-GW-01 | Structured JSON logs with `correlation_id`, `request_id`, `trace_id`, `user_id`, `route`, `method`, `status`, `latency_ms`, `upstream`, `client_ip` | pending | — | platform.engineering | platform.engineering | — | — |
| T-GW-02 | Metrics: `gateway_requests_total{route,method,status}`, `gateway_request_duration_seconds`, `gateway_upstream_duration_seconds`, `gateway_rate_limit_rejections_total`, `gateway_jwt_verification_failures_total`, `gateway_revocation_set_size`, `gateway_circuit_breaker_state`, `gateway_audit_events_emitted_total` | pending | T-GW-01 | platform.engineering | platform.engineering | — | — |
| T-GW-03 | OpenTelemetry traces with child spans for JWT verify, Redis lookups, upstream call, Kafka publish | pending | T-GW-02 | platform.engineering | platform.engineering | — | — |
| T-GW-04 | Health endpoints: `/health`, `/ready`, `/started` | pending | T-GW-03 | platform.engineering | platform.engineering | — | — |
### Phase 8a — Edge request id (correlation at the edge)

> **ADR-0019** (request id at the edge). The gateway is the
> canonical root generator of the platform's per-request id;
> `X-Request-Id` and `X-Correlation-Id` are aliases. This phase
> codifies the gateway-side implementation. The shared library
> already implements the alias rule for every downstream service
> (Kotlin/Spring `correlationIdFilter` in
> `platform-spring-boot-starter`); the Go gateway re-implements
> the same contract in `internal/gateway/request_id_middleware.go`
> (see `services/api-gateway/TECH.md` 2.1).

| ID | Task | Status | Depends-On | Required Role(s) | Approver Role | Co-Signer Role | Break-Glass? |
|---|---|---|---|---|---|---|---|
| T-GW-01 | Request-id middleware: read `X-Request-Id` then `X-Correlation-Id`; if both absent, generate UUIDv7 via `google/uuid.NewV7()`; if both sent, `X-Request-Id` wins. Stash the value in a context key used by the structured-JSON logger (MDC `requestId`) and by the OTel root-span attribute `platform.request_id` | pending | — | platform.engineering | platform.engineering | — | — |
| T-GW-02 | Set **both** `X-Request-Id` and `X-Correlation-Id` as response headers, same value | pending | T-GW-01 | platform.engineering | platform.engineering | — | — |
| T-GW-03 | Outbound HTTP interceptor: add **both** `X-Request-Id` and `X-Correlation-Id` to every call to a downstream service | pending | T-GW-01 | platform.engineering | platform.engineering | — | — |
| T-GW-04 | Kafka producer interceptor: set Kafka headers `X-Request-Id` and `X-Correlation-Id` on every event the gateway produces (`audit.api.request.v1`, `gateway.config.reloaded.v1`, `gateway.rate_limit.exceeded.v1`, `gateway.circuit_breaker.opened.v1`) | pending | T-GW-01 | platform.engineering | platform.engineering | — | — |
| T-GW-05 | Bind the request id to the OTel root span as the attribute `platform.request_id`; emit the same value as the `correlation_id` field of the `audit.api.request.v1` event envelope | pending | T-GW-01 | platform.engineering | platform.engineering | — | — |
| T-GW-06 | Guarantee the id is stable across retries (idempotent on retries; do not regenerate when an inbound value is present) | pending | T-GW-01 | platform.engineering | platform.engineering | — | — |
| T-GW-07 | Synthetic tests: (a) no inbound headers → both response headers + audit `correlation_id` + OTel `platform.request_id` + Kafka headers all equal one UUIDv7; (b) `X-Request-Id: A` + `X-Correlation-Id: B` with different values → response and audit carry `A`; (c) retried request with the same `X-Request-Id` → audit topic partition key is the same on both attempts | pending | T-GW-01..06 | platform.engineering | platform.engineering | — | — |
### Phase 9 — Testing

| ID | Task | Status | Depends-On | Required Role(s) | Approver Role | Co-Signer Role | Break-Glass? |
|---|---|---|---|---|---|---|---|
| T-GW-01 | Unit tests: JWT validation, rate-limit logic, route matching, claim-to-header translation | pending | — | platform.engineering | platform.engineering | — | — |
| T-GW-02 | Integration tests: Testcontainers (Redis, Kafka); mock Keycloak and upstreams | pending | T-GW-01 | platform.engineering | platform.engineering | — | — |
| T-GW-03 | E2E tests: full request flow, revocation, rate-limit rejection, circuit breaker opening | pending | T-GW-02 | platform.engineering | platform.engineering | — | — |
### Phase 10 — Deployment

| ID | Task | Status | Depends-On | Required Role(s) | Approver Role | Co-Signer Role | Break-Glass? |
|---|---|---|---|---|---|---|---|
| T-GW-01 | Kubernetes manifests: Deployment, Service, HPA (RPS, 5–100 replicas), PDB (`minAvailable: 3`) | pending | — | platform.engineering | platform.engineering | — | — |
| T-GW-02 | No database migration job (stateless) | pending | T-GW-01 | platform.engineering | platform.engineering | — | — |
| T-GW-03 | Resource limits per DEPLOYMENT_ARCHITECTURE.md (1 vCPU / 1 GiB per pod) | pending | T-GW-02 | platform.engineering | platform.engineering | — | — |
| T-GW-04 | Network policy: ingress from public LB only; egress to upstreams, Keycloak, Redis, Kafka | pending | T-GW-03 | platform.engineering | platform.engineering | — | — |
---

## Integration Map

### Sync Dependencies (outbound calls)
| Target | Endpoint | Purpose | Circuit Breaker |
|--------|----------|---------|----------------|
| Keycloak | `GET /.well-known/openid-configuration` | OIDC discovery | Yes |
| Keycloak | `GET /protocol/openid-connect/certs` | JWKS refresh | Yes |
| Keycloak | `POST /protocol/openid-connect/token/introspect` | Partner B2B introspection | Yes |
| `identity-service` | `GET /v1/identities/introspect` | Internal introspection helper | Yes |
| All downstream services (×50+) | `ANY /v1/...` | Request forwarding | Yes (per upstream) |
| Redis | Redis protocol | Revocation set, rate-limit counters, JWKS cache | Yes |
| Kafka | Producer | `audit.api.request.v1` publish | Yes |

### Events Published
| Event | Topic | Trigger | Key Consumers |
|-------|-------|---------|--------------|
| `audit.api.request.v1` | `audit.api.request` | Every authenticated request | `audit-service`, ``reporting-service` (data lake)` |
| `gateway.config.reloaded.v1` | `platform.gateway.config.reloaded` | Successful hot-reload | ``reporting-service` (data lake)` |
| `gateway.rate_limit.exceeded.v1` | `platform.gateway.rate_limit.exceeded` | 429 rejection | ``reporting-service` (data lake)`, `fraud-risk-service`, `audit-service` |
| `gateway.circuit_breaker.opened.v1` | `platform.gateway.circuit_breaker` | CB transitions to open | ``reporting-service` (data lake)`, `notification-service`, `audit-service` |

### Events Consumed
| Event | Producer | Handler |
|-------|----------|---------|
| `identity.session.revoked.v1` | `identity-service` | Write `jti` to Redis revoked set with TTL |
| `identity.user.suspended.v1` | `identity-service` | Write `kc_sub` to Redis suspended set (TTL 30d) |
| `identity.user.disabled.v1` | `identity-service` | Write `kc_sub` to Redis disabled set (no expiry) |
| `configuration.updated.v1` | `configuration-service` | Hot-reload routes, rate limits, CORS, JWKS interval |

---

## Acceptance Criteria
- [ ] All REST endpoints return correct status codes
- [ ] p99 latency within SLO targets (< 5ms on cache-hit path)
- [ ] All events published reliably via outbox pattern
- [ ] Zero data leakage across service boundaries
- [ ] 80%+ unit test coverage

---

## Related Docs
- [README](README.md) · [BRD](BRD.md) · [SRS](SRS.md) · [ERD](ERD.md) · [INTEGRATION](INTEGRATION.md) · [WORKFLOWS](WORKFLOWS.md) · [TECH](TECH.md)
- [Master Plan](../../MASTER_PLAN.md)


---



## Hard service-to-service dependencies

This service's position in the canonical per-service deployment
order is **Tier 0, Position 4** per
[`../../DEPLOYMENT_ORDER.md`](../../DEPLOYMENT_ORDER.md).

| Class | Services |
|---|---|
| **Hard deps** (must be live and reachable before this service can complete its `/ready` health check) | [`configuration-service`](../configuration-service/README.md) (routing rules), [`identity-service`](../identity-service/README.md) (JWKS cache, OIDC discovery) |
| **Soft deps** (this service can start without them; runtime calls fail gracefully with circuit-breaker fallback until the dep is up) | — |

**Deployment scenarios** (per [`../../DEPLOYMENT_ORDER.md` §4](../../DEPLOYMENT_ORDER.md)):

- **Greenfield** — tiers are deployed in order; intra-tier parallelism is allowed.
- **Single-service rollout** — rolling deploy with canary required for Tier 0 (`configuration-service`, `identity-service`, `api-gateway`); optional for Tier 1+; canary required for `chat-service` (Phase 7.7 cross-cutting).
- **Region failover / DR** — full Tier 0 → Tier 1 → Tier 2 → Tier 3 sequence is replayed.

For cross-cutting infra deps (PostgreSQL, Kafka, Redis, Keycloak, Vault, mTLS, OTel, S3) see [`../../DEPLOYMENT_ORDER.md` §3](../../DEPLOYMENT_ORDER.md).

## Role Mapping (back-reference)

This service's tasks map to platform roles per [`MASTER_TASK.md`](../../MASTER_TASK.md) 11 "Role Mapping (back-reference)". The columns `Required Role(s) | Approver Role | Co-Signer Role | Break-Glass?` added to every task table above come from that appendix.

| ID prefix | Required Role(s) | Approver Role | Co-Signer Role | Break-Glass? |
|---|---|---|---|---|
| T-GW-NN (Phase 1-10) | per task | per task | per task | per task |
| T-GW-P70-NN | platform.admin / pricing.admin / customer.admin | platform.admin | platform.super_admin | no (Phase 7.0 cross-cutting) |
| T-GW-P75-NN | platform.admin / pricing.admin | platform.admin | platform.super_admin | no (Phase 7.5 Make-a-Deal) |
| T-GW-P76-NN | platform.admin + Conductor UI role | platform.admin | platform.super_admin | yes (workflow worker registration) |

For the canonical SUPER_ADMIN preset (1 × `platform.super_admin` + 20 × `<service>.admin`), see [`shared/TIME_BOUNDED_ALIASES.md`](../../shared/TIME_BOUNDED_ALIASES.md) for time-bounded aliases and `admin-service/INTEGRATION.md` 1.13 for the canonical role list.
