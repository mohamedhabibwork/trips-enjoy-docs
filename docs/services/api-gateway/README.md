# api-gateway

## 1. Purpose

The `api-gateway` is the platform's single, stateless, north-south edge for
every external client (mobile apps, web apps, partner integrations, and
admin consoles). It terminates TLS, validates JWTs issued by Keycloak,
translates claims into request headers, applies rate limits, routes
requests to the right downstream microservice, and emits an
`audit.api.request.v1` event for every authenticated call. It owns no
business data.

## 2. Bounded Context

**Edge / cross-cutting request handling.** In scope: edge security,
routing, claim propagation, request/response transformation, rate
limiting, CORS, and observability fan-out. Out of scope: any business
logic, any persistent state, any user-facing content.

## 3. Responsibilities

- Terminate TLS 1.3 for all inbound public traffic.
- Validate JWTs against Keycloak's JWKS (RS256), with cached
  revocation set in Redis.
- Translate validated JWT claims into `X-User-Id`, `X-User-Type`,
  `X-Roles`, `X-Scopes`, `X-Tenant-Id` request headers.
- Apply per-route, per-token, per-IP rate limits.
- Route `/v1/...` URIs to the right downstream service using the
  service's published route table.
- Rewrite paths and add correlation IDs / trace propagation headers.
- Issue or accept the platform's per-request id at the edge and
  propagate it (as `X-Request-Id` and alias `X-Correlation-Id`,
  to every downstream call, every emitted event, every log line,
  and the OTel root span) so a single id ties the whole request
  together. See
  [ADR-0019](../../architecture/adrs/0019-request-id-at-the-edge.md).
- Emit `audit.api.request.v1` for every authenticated request.
- Apply CORS policy per channel (web customer, web driver, etc.).
- Serve the platform's unified OpenAPI explorer at
  `https://api.example.com/docs`.
- Apply the platform error envelope to downstream 4xx/5xx responses
  (see `architecture/API_STANDARDS.md`).
- Enforce WAF-style rules (e.g. block obvious SQLi/XXE patterns in
  well-known paths).
- Serve `/health`, `/ready`, `/started` for the gateway itself.
- Token blacklist lookup: reject tokens present in the Redis
  revocation set even if their signature is valid.

## 4. Explicitly NOT Owned

- **No business data.** No PostgreSQL schema. No persistent storage
  of user data, ride data, order data, etc.
- **No business logic.** No validation of business rules; that belongs
  to the downstream service.
- **No authentication decisioning.** The gateway verifies a Keycloak
  token; it does not decide who can do what (authorization is RBAC +
  resource ownership, enforced downstream).
- **No long-running flows.** The gateway routes and forwards; it does
  not orchestrate sagas.
- **No session storage.** Sessions live in Keycloak; the gateway
  holds only a revocation set in Redis (TTL ≤ access-token lifetime).
- **No provider credentials.** No payment, SMS, map, or other
  third-party API keys. (Downstream services own those.)
- **No OpenAPI authoring.** Each downstream service owns its spec.
  The gateway aggregates and exposes the union at `/docs`.

## 5. Actors

| Actor | Type | Access |
|-------|------|--------|
| Customer (web/mobile) | human | read/write on customer-routed endpoints |
| Driver (web/mobile) | human | read/write on driver-routed endpoints |
| Courier (web/mobile) | human | read/write on courier-routed endpoints |
| Merchant / restaurant staff | human | read/write on staff endpoints |
| Support agent | human (internal) | read on most endpoints; scoped to support tickets |
| Admin / super admin | human (internal) | admin-scoped endpoints; high-value actions require signature |
| Partner (B2B) | system | partner-scoped endpoints with API key + JWT |
| Service-to-service | system (machine) | service-to-service client_credentials JWT |
| Bot / scanner | unknown | anonymous; only public endpoints; aggressively rate-limited |
| Keycloak | system (IdP) | JWKS source; introspection partner |

## 6. Dependencies

### Synchronous (REST)

- `identity-service` — token introspection (cache-miss path),
  identity lookup by `kc_sub` — SLO 99.95% — circuit breaker: yes.
  (Most token validation is local via JWKS; the call to
  `identity-service` only happens on cache-miss for `introspect` flows
  used by partners.)
- `keycloak` (admin API) — JWKS refresh, well-known discovery
  (`/realms/{realm}/.well-known/openid-configuration`) — SLO 99.95% —
  circuit breaker: yes. The gateway runs an internal client to fetch
  JWKS; it does not proxy realm admin calls.
- **Every downstream microservice** — routing target. The gateway
  opens and keeps open a pool of HTTP/2 connections per upstream.

### Asynchronous (events consumed)

- `identity.session.revoked.v1` from `identity-service` — invalidates
  the in-Redis revocation set — duplicate handling: Redis `SET`
  (idempotent) keyed by `jti`.
- `identity.user.suspended.v1` from `identity-service` — fan-out to
  the revocation set; same dedup semantics.
- `identity.user.disabled.v1` from `identity-service` — same as
  above; permanent block.
- `configuration.updated.v1` from `configuration-service` — reload
  rate-limit policies, CORS allowlist, route table, and the JWKS
  refresh interval — duplicate handling: configuration version stamp.

## 7. Technology Assumptions

- Runtime: **Envoy** with custom filters for the JWT/header
  translation. Lua or Wasm filters for the request/response
  transformation pipeline.
- Alternative acceptable: **Kong** (OSS) with `jwt`, `rate-limiting`,
  `cors`, `proxy-cache`, `http-log`, and a custom Go plugin for the
  claim-to-header translation. The platform standardizes on Envoy for
  Tier-1 services.
- **No service database.** No PostgreSQL, no Redis ownership
  (Redis is shared, with the gateway's revocation set namespaced
  under `gateway:revoked:*`).
- Cache: **Redis** (shared cluster) — revoked-token set, rate-limit
  counters, JWKS cache, route-table cache.
- Event broker: **Kafka** — emits `audit.api.request.v1` (and consumes
  the small set of identity/config events listed above).

## 8. Database Ownership

- Schema: **none**. The gateway is stateless.
- Migrations: **n/a**.
- Soft delete: **n/a**.
- Partitioning: **n/a**.

## 9. API Overview

The gateway exposes the union of every downstream service's API
(versioned). A representative slice of the platform's public surface:

| Method | URI | Auth | Purpose |
|--------|-----|------|---------|
| ANY | `/v1/customers/...` | bearer (customer) | customer-service |
| ANY | `/v1/drivers/...` | bearer (driver) | driver-service |
| ANY | `/v1/couriers/...` | bearer (courier) | courier-service |
| ANY | `/v1/rides/...` | bearer (customer/driver) | `trip-service` (ride-request) / trip-service |
| ANY | `/v1/trips/...` | bearer (customer/driver) | trip-service |
| ANY | `/v1/restaurants/...` | bearer (public + customer) | restaurant-service / `restaurant-service` (branch) / `restaurant-service` (menu) |
| ANY | `/v1/orders/...` | bearer (customer/restaurant_staff) | food-order-service |
| ANY | `/v1/deliveries/...` | bearer (courier/customer) | `courier-service` (delivery) / `courier-service` (dispatch) |
| ANY | `/v1/payments/...` | bearer (customer) | payment-service / `payment-service` (wallet) |
| ANY | `/v1/addresses/...` | bearer (customer) | `customer-service` (addresses) |
| ANY | `/v1/vehicles/...` | bearer (driver/courier) | `driver-service` (vehicles) |
| ANY | `/v1/notifications/...` | bearer (any user) | notification-service |
| ANY | `/v1/admin/...` | bearer (admin) | admin-service |
| ANY | `/v1/support/...` | bearer (support) | `admin-service` (support module) |
| GET | `/openapi.json` | none (public) | aggregate OpenAPI 3.1 |
| GET | `/docs` | none (public) | Swagger UI |
| GET | `/health` | none | liveness |
| GET | `/ready` | none | readiness (checks downstream readiness) |
| GET | `/started` | none | startup |

(Full contracts in `INTEGRATION.md`.)

## 10. Events Produced

| Event | Trigger | Consumers |
|-------|---------|-----------|
| `audit.api.request.v1` | every authenticated request (sampled 1:1 in production; aggregated in the audit topic) | `audit-service`, ``reporting-service` (data lake)` |
| `gateway.config.reloaded.v1` | successful reload of route table / rate-limit policy / CORS policy | ``reporting-service` (data lake)` (low-volume) |
| `gateway.rate_limit.exceeded.v1` | a request is rejected with `429 RATE_LIMITED` (per-route, per-token, or per-IP) | ``reporting-service` (data lake)`, `fraud-risk-service`, `audit-service` |
| `gateway.circuit_breaker.opened.v1` | a per-upstream circuit breaker transitions to `open` | ``reporting-service` (data lake)`, `notification-service`, `audit-service` |

(Full contracts in `INTEGRATION.md`.)

## 11. Events Consumed

| Event | Producer | Reason | Handler |
|-------|----------|--------|---------|
| `identity.session.revoked.v1` | `identity-service` | tokens may still be cryptographically valid but must be rejected | write `jti` to Redis revoked set with TTL = remaining access-token lifetime |
| `identity.user.suspended.v1` | `identity-service` | deny all current and future tokens for the `kc_sub` | write `kc_sub` to Redis suspended-sub set; gateway also looks up active `jti` for the sub and revokes them |
| `identity.user.disabled.v1` | `identity-service` | permanent block | same as suspended, but no expiry |
| `configuration.updated.v1` | `configuration-service` | route table, rate limits, CORS, error mapping may have changed | hot-reload in-memory config; reject-and-reload is in-process and atomic |

## 12. External Integrations

- **Keycloak** — JWKS provider (`.well-known/openid-configuration`,
  `/protocol/openid-connect/certs`). Refreshed on a configurable
  interval (default 5 min) and on key-rotation events. Credentials
  in Vault at `vault://platform/edge/keycloak/`.
- **Redis cluster** — shared with the platform; gateway uses a
  dedicated logical DB index and key prefix `gateway:`.
- **Kafka cluster** — shared with the platform; gateway's audit
  topic is `audit.api.request`. Producer client credentials in
  Vault at `vault://platform/edge/kafka/`.

## 13. Configuration

| Key | Type | Source | Notes |
|-----|------|--------|-------|
| `gateway.routes` | object (route table) | configuration-service | path-prefix → upstream mapping; reloads on `configuration.updated.v1` |
| `gateway.rate_limits.default` | object | configuration-service | `{ limit, window_seconds, by }` per route class |
| `gateway.cors.allowed_origins` | string[] | configuration-service | per channel; web customer / web driver / web courier / web staff / web admin |
| `gateway.jwt.allowed_audiences` | string[] | configuration-service | Keycloak client ids accepted |
| `gateway.jwt.allowed_issuers` | string[] | configuration-service | realm URIs accepted |
| `gateway.jwt.jwks_refresh_seconds` | int | configuration-service | default 300 |
| `gateway.audit.sample_ratio` | float | configuration-service | default 1.0 (always audit authenticated) |
| `gateway.audit.body_max_bytes` | int | configuration-service | for request-body hashing in audit (default 8192) |
| `gateway.timeout.upstream_ms` | int | configuration-service | default 30000 |
| `gateway.bulkhead.upstream_concurrency` | int | configuration-service | per upstream; default 1024 |

## 14. Security

- **AuthN**: JWT bearer (RS256) validated against Keycloak's JWKS.
  `iss` and `aud` enforced. `exp` and `nbf` enforced. `sub` required.
  Revocation set consulted in Redis on every request.
- **AuthZ**: coarse role check at the gateway (reject 403 if a
  required role is missing for the matched route). Fine-grained
  scope and resource-ownership checks live in the downstream
  service — the gateway does not hold business resources.
- **Secrets**: Vault. JWKS, Redis password, Kafka SASL credentials,
  Keycloak admin client secret.
- **PII**: the gateway does not log request bodies in production.
  It logs `correlation_id`, `user_id`, `route`, `status`,
  `latency_ms`, and a SHA-256 of the body (for diff-based audit
  search). No JWT, no password, no PAN ever logged.
- **mTLS**: in-cluster sidecar (Istio/Linkerd) terminates the
  outer TLS hop; the gateway is the only public-facing endpoint.
- **WAF**: layered in front of the gateway in production; the
  gateway itself enforces a small set of pattern blocks (SQLi,
  XXE, path traversal) as defense in depth.
- **Rate limiting**: per-token, per-IP, per-route; documented in
  `INTEGRATION.md` and configurable per route.

## 15. Observability

- **Logs**: structured JSON to stdout. Fields: `ts`, `level`,
  `service=api-gateway`, `version`, `env`, `region`,
  `correlation_id`, `request_id`, `trace_id`, `user_id`,
  `user_type`, `route`, `method`, `status`, `latency_ms`,
  `upstream`, `upstream_status`, `upstream_latency_ms`,
  `client_ip`, `user_agent`, `msg`.
- **Metrics**: RED per upstream and per route. Plus:
  - `gateway_requests_total{route, method, status}`
  - `gateway_request_duration_seconds{route, method, status}`
  - `gateway_upstream_duration_seconds{upstream, route}`
  - `gateway_rate_limit_rejections_total{route, reason}`
  - `gateway_jwt_verification_failures_total{reason}`
  - `gateway_revocation_set_size{realm}`
  - `gateway_circuit_breaker_state{upstream}`
  - `gateway_audit_events_emitted_total`
- **Traces**: OpenTelemetry. One root span per request named
  `{METHOD} {route}`. Spans for upstream calls, Redis lookups, JWKS
  fetch. `traceparent` propagated to upstream. Sample rate: 100%
  on errors, 10% on success in production; 100% in staging.
- **Health**: `/health` (process up), `/ready` (Redis reachable,
  JWKS cached, at least one upstream reachable), `/started`
  (initial config loaded; route table built).

## 16. Scalability

- **Replicas**: default 6 per region; minimum 3 for HA.
- **HPA**: CPU 60% target; custom metric on `gateway_requests_in_flight`
  (target 1000/instance) and `gateway_request_duration_seconds:p99`
  (target ≤ 500ms).
- **Hot path**: the JWT signature verification (cached JWKS) and
  the Redis revocation-set `SISMEMBER`. The gateway is
  intentionally stateless beyond Redis, so horizontal scale is
  linear up to the cluster's connection limit.

## 17. Local Development

- Run locally with `make up` (the platform's docker-compose
  v2 file at `infra/dev/docker-compose.yml` starts the gateway
  alongside Keycloak, a mock upstream, Redis, and Kafka).
- The gateway in dev uses a permissive CORS policy and a
  pre-issued dev JWT for local testing (`vault://local/gateway/dev-jwt`).
- Hot-reload of routes is supported via a SIGHUP or a
  `POST /admin/reload` to the internal admin port (bound to
  `127.0.0.1` only).
- Seed data: a fixture dev route table and a 60s-window rate
  limit are loaded from `infra/dev/gateway/fixtures/`.

## 18. Deployment

- **Image**: `registry.example.com/edge/api-gateway:{semver}`.
- **Replicas**: 6 (prod, per region), 3 (staging), 1 (dev).
- **Resource limits**: 1 vCPU / 1 GiB RAM per pod (Envoy is
  memory-efficient); HPA 3-30 per region.
- **Migrations**: none. Configuration is hot-reloaded; no
  in-process state to migrate.
- **Pod disruption budget**: `minAvailable: 3` in production.
- **Topology spread**: anti-affinity across nodes.
- **Network policy**: ingress allowed from public LB only;
  egress allowed to upstream services, Keycloak, Redis, Kafka.


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

### Related services

- **Depends on**: [``customer-service` (addresses)`](../customer-service/README.md), [`admin-service`](../admin-service/README.md), [``reporting-service` (data lake)`](../reporting-service/README.md), [`audit-service`](../audit-service/README.md), [``restaurant-service` (branch)`](../restaurant-service/README.md), [`configuration-service`](../configuration-service/README.md), [``courier-service` (dispatch)`](../courier-service/README.md), [`courier-service`](../courier-service/README.md), [`customer-service`](../customer-service/README.md), [``courier-service` (delivery)`](../courier-service/README.md), [``driver-service` (dispatch)`](../driver-service/README.md), [`driver-service`](../driver-service/README.md), [`food-order-service`](../food-order-service/README.md), [`fraud-risk-service`](../fraud-risk-service/README.md), [`identity-service`](../identity-service/README.md), [``restaurant-service` (menu)`](../restaurant-service/README.md), [`notification-service`](../notification-service/README.md), [`payment-service`](../payment-service/README.md), [`restaurant-service`](../restaurant-service/README.md), [``trip-service` (ride-request)`](../trip-service/README.md)
- **Depended on by**: [``customer-service` (addresses)`](../customer-service/README.md), [`courier-service`](../courier-service/README.md), [`customer-service`](../customer-service/README.md), [`driver-service`](../driver-service/README.md), [`identity-service`](../identity-service/README.md), [``customer-service` (cross-persona profile)`](../customer-service/README.md), [``driver-service` (vehicles)`](../driver-service/README.md)

> Full dependency map in [`../README.md`](../README.md) and [`../../architecture/MICROSERVICES_MAP.md`](../../architecture/MICROSERVICES_MAP.md).

### Platform-wide

- [`../../shared/README.md`](../../shared/README.md) — `platform-spring-boot-starter` shared library (the single source of cross-cutting code for all Spring Boot services in the platform)
- [`../../shared/PLATFORM_BASELINE.md`](../../shared/PLATFORM_BASELINE.md) — single source for PostgreSQL 19, Kafka, Keycloak, Redis, OpenTelemetry, Vault, deployment, DR (do not restate these in this README)
- [`../../architecture/SERVICE_ISOLATION.md`](../../architecture/SERVICE_ISOLATION.md) — **how this service behaves when a downstream is down** (timeout / bulkhead / circuit / retry / fallback, by class: CRITICAL / DEGRADABLE / BEST-EFFORT)
- [`../../architecture/DOWNSTREAM_ERROR_CATALOG.md`](../../architecture/DOWNSTREAM_ERROR_CATALOG.md) — **canonical error-code catalog + propagation rules** (the `downstream` block, forward/translate/degrade/reject)
- [`../RECOMMENDATIONS.md`](../RECOMMENDATIONS.md) — platform-wide technology map (language, framework, version baseline, admin/RBAC pattern)
- [`../../README.md`](../../README.md) — services overview (the catalog of all 20 services)
- [`../../../main.md`](../../../main.md) — top-level platform specification (architecture, Keycloak, PostgreSQL 19, messaging, observability baseline)
- [`../../shared/OSS_DEPENDENCIES.md`](../../shared/OSS_DEPENDENCIES.md) — **open-source dependencies & license attribution** (platform-wide OSS projects + per-language OSS libraries with SPDX IDs; per-service bundle index; license compatibility matrix)
