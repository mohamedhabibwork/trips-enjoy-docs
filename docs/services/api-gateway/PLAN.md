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
- [ ] No database schema (stateless service)
- [ ] Define in-process config snapshot struct (routes, rate limits, CORS, JWKS settings)
- [ ] Implement atomic in-memory config swap for hot-reload

### Phase 2 — REST API
- [ ] `ANY /v1/{service}/{resource}` — forward to matched downstream service with JWT validation, rate-limit, correlation ID
- [ ] `GET /openapi.json` — serve aggregate OpenAPI 3.1 document
- [ ] `GET /docs` — serve Swagger UI
- [ ] `GET /health` — liveness probe
- [ ] `GET /ready` — readiness (checks JWKS cached, Redis reachable, at least one upstream reachable)
- [ ] `GET /started` — startup probe (initial config loaded, route table built)
- [ ] `POST /admin/reload` — hot-reload in-process config (internal, `127.0.0.1` only, mTLS)

### Phase 3 — Event Publishing
- [ ] Implement in-process Kafka producer (no outbox — stateless)
- [ ] Publish `audit.api.request.v1` → topic `audit.api.request` (every authenticated request)
- [ ] Publish `gateway.config.reloaded.v1` → topic `platform.gateway.config.reloaded` (on successful hot-reload)
- [ ] Publish `gateway.rate_limit.exceeded.v1` → topic `platform.gateway.rate_limit.exceeded` (on 429 rejection)
- [ ] Publish `gateway.circuit_breaker.opened.v1` → topic `platform.gateway.circuit_breaker` (on CB state transition)
- [ ] Producer retry: 3 attempts with exponential backoff; DLQ per topic

### Phase 4 — Event Consumption
- [ ] Implement in-process inbox (keyed by `event_id`, TTL 24h)
- [ ] Consume `identity.session.revoked.v1` → write `jti` to Redis revoked set with TTL = remaining access-token lifetime
- [ ] Consume `identity.user.suspended.v1` → write `kc_sub` to Redis suspended-sub set (TTL 30d)
- [ ] Consume `identity.user.disabled.v1` → write `kc_sub` to Redis disabled set (no expiry)
- [ ] Consume `configuration.updated.v1` → hot-reload routes, rate limits, CORS, JWKS refresh interval

### Phase 5 — Caching
- [ ] Redis rate-limit counters: per-token, per-IP, per-route (sliding window)
- [ ] Redis JWKS cache (TTL configurable, default 5 min)
- [ ] Redis revocation set: `gateway:revoked:jti:<jti>` and `gateway:revoked:sub:<kc_sub>`
- [ ] Cache invalidation on `identity.session.revoked.v1` and `identity.user.suspended/disabled.v1`

### Phase 6 — External Integrations
- [ ] Keycloak JWKS (`/realms/{realm}/protocol/openid-connect/certs`) — periodic refresh + event-driven rotation
- [ ] Keycloak OIDC discovery — `/.well-known/openid-configuration`
- [ ] Keycloak token introspection for partner B2B (cache-miss path)
- [ ] `identity-service` — internal introspection helper
- [ ] Circuit breakers on all upstreams (default: open after 5 failures in 10s, reset after 30s)

### Phase 7 — Security
- [ ] JWT bearer auth via `coreos/go-oidc v3` (RS256, `iss` + `aud` + `exp` + `nbf` + revocation set)
- [ ] Required scopes/roles: coarse role check per route at gateway; fine-grained check in downstream
- [ ] WAF-style pattern blocking (SQLi, XXE, path traversal)
- [ ] mTLS for in-cluster traffic (Istio/Linkerd sidecar)
- [ ] No request body logging in production (SHA-256 body hash only)
- [ ] Secrets via HashiCorp Vault

### Phase 8 — Observability
- [ ] Structured JSON logs with `correlation_id`, `request_id`, `trace_id`, `user_id`, `route`, `method`, `status`, `latency_ms`, `upstream`, `client_ip`
- [ ] Metrics: `gateway_requests_total{route,method,status}`, `gateway_request_duration_seconds`, `gateway_upstream_duration_seconds`, `gateway_rate_limit_rejections_total`, `gateway_jwt_verification_failures_total`, `gateway_revocation_set_size`, `gateway_circuit_breaker_state`, `gateway_audit_events_emitted_total`
- [ ] OpenTelemetry traces with child spans for JWT verify, Redis lookups, upstream call, Kafka publish
- [ ] Health endpoints: `/health`, `/ready`, `/started`

### Phase 9 — Testing
- [ ] Unit tests: JWT validation, rate-limit logic, route matching, claim-to-header translation
- [ ] Integration tests: Testcontainers (Redis, Kafka); mock Keycloak and upstreams
- [ ] E2E tests: full request flow, revocation, rate-limit rejection, circuit breaker opening

### Phase 10 — Deployment
- [ ] Kubernetes manifests: Deployment, Service, HPA (RPS, 5–100 replicas), PDB (`minAvailable: 3`)
- [ ] No database migration job (stateless)
- [ ] Resource limits per DEPLOYMENT_ARCHITECTURE.md (1 vCPU / 1 GiB per pod)
- [ ] Network policy: ingress from public LB only; egress to upstreams, Keycloak, Redis, Kafka

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
- [Master Plan](../../MASTER_SERVICE_PLAN.md)
