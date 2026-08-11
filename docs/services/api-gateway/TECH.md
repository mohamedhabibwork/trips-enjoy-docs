# api-gateway — Technology Profile

> One-page technology reference for `api-gateway`. The platform-wide
> technology map lives in [`../RECOMMENDATIONS.md`](../RECOMMENDATIONS.md);
> this file is the per-service view of that map.
>
> **Sibling docs**: [`README.md`](./README.md) · [`BRD.md`](./BRD.md) ·
> [`SRS.md`](./SRS.md) · [`ERD.md`](./ERD.md) ·
> [`INTEGRATION.md`](./INTEGRATION.md) · [`WORKFLOWS.md`](./WORKFLOWS.md).

## 1. Runtime

| | |
|---|---|
| **Profile** | Edge / hot path |
| **Language** | Go 1.25.x |
| **Framework** | `net/http` + `go-chi/chi` v2 |
| **Build** | `go build` (Go 1.25.x toolchain) |
| **Container** | `gcr.io/distroless/static-debian12:nonroot` (multi-stage build, static binary) |

## 2. Key libraries

- `go-chi/chi` v2 (5.x) — router
- `coreos/go-oidc` v3 — Keycloak JWKS verification
- `go-redis/redis` v9 — rate limits, JWKS cache
- `prometheus/client_golang` v1.20+ — metrics
- `google/uuid` — UUIDv7 generation (per
  [ADR-0015](../../architecture/adrs/0015-uuidv7-for-ids.md))
- `go.opentelemetry.io/otel` v1.30+ — trace context + OTel
  attribute `platform.request_id` (per
  [ADR-0019](../../architecture/adrs/0019-request-id-at-the-edge.md))

### 2.1 Request-id filter (Go)

The shared library's `correlationIdFilter` (Kotlin/Spring) is not
applicable to the Go gateway; the gateway re-implements the
[ADR-0019](../../architecture/adrs/0019-request-id-at-the-edge.md)
contract in Go as a `http.Handler` middleware that runs **first**
in the chain, before JWT verification, before rate limiting, and
before any other filter:

1. **Read inbound headers.** Look up `X-Request-Id`; if absent,
   look up `X-Correlation-Id`; if both are absent, generate a
   UUIDv7 via `google/uuid.NewV7()`. If both are sent, the
   value of `X-Request-Id` wins.
2. **Set the response headers.** Write **both** `X-Request-Id`
   and `X-Correlation-Id` to the response (same value).
3. **Set the MDC.** Stash the value in a context key (the Go
   equivalent of SLF4J's MDC) so the structured-JSON log line
   emitted for every log call in the request scope carries the
   value under the key `requestId`.
4. **Set the OTel root span attribute.** Use
   `trace.SpanFromContext(ctx).SetAttributes(attribute.String("platform.request_id", id))`
   on the root span of the request.
5. **Propagate outbound.** Pass the value through a context key
   that the outbound HTTP client interceptor reads (adds both
   `X-Request-Id` and `X-Correlation-Id` to every upstream call)
   and the Kafka producer interceptor reads (adds both
   `X-Request-Id` and `X-Correlation-Id` to every produced
   message).
6. **Idempotency on retry.** A retried request with the same
   client-supplied id keeps the same id; the filter does not
   regenerate when an inbound value is present. The audit topic
   is partitioned by `correlation_id`, so a retried request
   lands on the same partition and is processed in order.

The implementation lives in
`internal/gateway/request_id_middleware.go`. Synthetic tests in
`services/api-gateway/PLAN.md` Phase 8a T-GW-07 assert the
chain.

## 3. Data layer

- **Database**: PostgreSQL 19, schema — (stateless)
- **Migrations**: n/a
- **ORM / DSL**: n/a (no DB)

## 4. Cache

Redis — rate-limit counters, JWKS cache

## 5. External integrations

Keycloak JWKS

## 6. Security

- **AuthN**: Keycloak resource server (Spring Security 7 for Kotlin, `coreos/go-oidc` v3 for Go, `authlib` for Python)
- **AuthZ**: RBAC (JWT scopes / roles)
- **Secrets**: external secret manager (Kubernetes External Secrets / Vault — no secret in env)
- **mTLS**: linkerd sidecar, all intra-cluster traffic

## 7. Observability

- **Tracing**: OpenTelemetry SDK 1.40+ → OTLP
- **Metrics**: `prometheus/client_golang` → Prometheus
- **Logs**: structured JSON to stdout (Loki)
- **Health**: `/healthz` (custom handler)

## 8. Scaling

- **HPA signal**: RPS, 5–100 replicas, p99 < 5ms
- **Pod resources**: requests/limits set per service (see `k8s/base/<service>/deployment.yaml`)

## 9. Local dev

- **Run**: `go run ./cmd/api-gateway`
- **Test**: `go test ./...`
- **Compose profile**: `docker compose --profile api-gateway up`

## 10. Admin endpoints & RBAC

This service exposes `/admin/v1/...` endpoints for the `admin-service`
BFF and platform operators. The platform-wide admin pattern (roles,
audit format, network policy, common endpoints) is in
[`../RECOMMENDATIONS.md` 6](../RECOMMENDATIONS.md#6-admin-endpoints--rbac);
this section documents the **per-service specifics**.

### 10.1 Keycloak admin roles accepted

This service accepts admin calls from these Keycloak roles:

- `platform.super_admin`
- `platform.admin`
- `platform.engineering`
- `api_gateway.admin`

### 10.2 Audit log

Every admin call on this service emits one event to:

- **Key**: `audit.admin.api_gateway.v1`
- **Consumer**: `audit-service` (writes to its immutable `audit` schema)
- **Fields**: `actor_id`, `actor_username`, `roles`, `endpoint`,
  `target_resource`, `action`, `reason_code` (required for PII access),
  `request_id`, `trace_id`, `result`, `duration_ms`

### 10.3 Data access policy (per-service)

The platform-wide policy table is in
[RECOMMENDATIONS.md 6.5](../RECOMMENDATIONS.md#65-data-access-by-role-platform-wide).
This service refines it as follows:

| Data class | super_admin | admin | ops | support | finance | engineering | data_eng |
|---|---|---|---|---|---|---|---|
| Rate-limit state (Redis) | ✓ | ✓ | — | — | — | ✓ | — |
| Route table (in-memory) | ✓ | ✓ | — | — | — | ✓ | — |

### 10.4 Service-specific admin endpoints

In addition to the
[common 8 endpoints in RECOMMENDATIONS.md 6.4](../RECOMMENDATIONS.md#64-common-admin-endpoints-every-service)
(inherited by every service), this service exposes:

| Method | Path | Min role | Purpose |
|---|---|---|---|
| `POST` | `/admin/v1/routes/reload` | `api_gateway.admin` | Reload the route table from the source of truth without a restart |
| `POST` | `/admin/v1/blocklists/ip/{value}` | `platform.admin` | Block an IP at the gateway (add to Redis blocklist) |
| `DELETE` | `/admin/v1/blocklists/ip/{value}` | `platform.admin` | Unblock an IP |
| `POST` | `/admin/v1/jwks/refresh` | `platform.engineering` | Force-refresh the Keycloak JWKS cache |

### 10.5 Admin enforcement

- **Pattern**: `net/http` middleware that reads `coreos/go-oidc` v3 ID-token claims; admin mux mounted on `:8081` separately from public mux on `:8080`
- **Network**: admin port (`8081`) is reachable only from the
  `admin-service`, `platform-ops`, and `platform-engineering`
  namespaces + bastion. Public ingress is not routed to it.
- **mTLS**: linkerd sidecar on every admin call.

### 10.6 Local admin (dev only)

- **Run with admin port**: `ADMIN_PORT=8081 go run ./cmd/api-gateway`
- **Test admin endpoints**: `go test -run Admin ./...`
- **Mint a dev admin JWT**: `make admin-jwt ROLE=platform.admin`

### 10.7 Super Admin preset membership

This service is included in the platform's `SUPER_ADMIN` permission
preset. An operator granted the preset receives:

- The platform-wide guard role `platform.super_admin`.
- This service's per-service admin role `api_gateway.admin`.

Grant and revoke are managed through
[`admin-service`](../admin-service/TECH.md#10-admin-endpoints--rbac)
via `POST /v1/admin/identity/grant-super-admin` and
`DELETE /v1/admin/identity/revoke-super-admin`. Both require
break-glass co-signature (per `SECURITY_ARCHITECTURE.md` 14) and
emit `audit.admin.api_gateway.v1` (per 10.2) for each touched
record.


---

## 11. Open-source bundle

This service is built on the platform's open-source stack. The full
catalogue — what each library is, what license it ships under, where
the NOTICE file comes from — is in
[`../../shared/OSS_DEPENDENCIES.md`](../../shared/OSS_DEPENDENCIES.md).
This section is the per-service view of that catalogue.

**Profile context.** Edge / hot path — Go / `net/http` + `chi`.

**External vendor SDK.** Keycloak JWKS (see the entry in [`../../shared/OSS_DEPENDENCIES.md`](../../shared/OSS_DEPENDENCIES.md) 7 for the per-service index).

**Per-service OSS libraries.** This service pulls in the full pinned set listed in [`../../shared/OSS_DEPENDENCIES.md`](../../shared/OSS_DEPENDENCIES.md) 4 *Go OSS dependencies* (the `platform-spring-boot-starter` for Kotlin, the standard Go stack for Go, or the FastAPI + Pydantic + SQLAlchemy set for Python). The most service-specific entries are: `go-chi/chi v2` · `coreos/go-oidc v3` · `go-redis/redis v9` · `prometheus/client_golang`.

**Extractability.** This service can be lifted out of the platform and run as a
standalone project without code changes. The minimum dependency manifest
that demonstrates this is [`SKELETON.go.mod`](./SKELETON.go.mod)
(doc-only stub; not a runnable build). The split between platform-required
and swappable dependencies is:

| Dependency class | Platform-required | Swappable |
|---|---|---|
| Language runtime | — | JDK 25 / Go 1.25 / Python 3.14 (use whatever your env needs) |
| Web/framework | `platform-spring-boot-starter` (Kotlin) / `net/http` + `chi` (Go) / FastAPI (Python) | Replace with your preferred framework |
| Database | PostgreSQL 19 (per-service schema) | H2 (in tests) / any PostgreSQL 14+ compatible |
| Migrations | Flyway 11 (Kotlin) / `golang-migrate` v4 (Go) / Alembic (Python) | Any tool that produces the same SQL |
| Cache | Redis 8 (cluster) | Caffeine (in-process) / no cache |
| Messaging | Apache Kafka 3.9 | In-process `BlockingQueue` for tests |
| Identity | Keycloak | Stub JWT verifier (JWKS = a static fixture) |
| Observability | OpenTelemetry SDK → OTLP | Logback / logrus / structlog direct to stdout |
| External vendor SDK | (per the "External" column of [`RECOMMENDATIONS.md`](../RECOMMENDATIONS.md) 2) | Swap or stub at the driver boundary (`PaymentGatewayDriver`, `MapProvider`, etc.) |

**Single source of truth.** The full licence catalogue (SPDX IDs,
license-text URLs, NOTICE / THIRD-PARTY-LICENSES generation tooling,
license compatibility matrix) is in
[`../../shared/OSS_DEPENDENCIES.md`](../../shared/OSS_DEPENDENCIES.md).
The version pin for every library is in [`../RECOMMENDATIONS.md` 5](../RECOMMENDATIONS.md#5-cross-cutting-tooling-language-agnostic).
Do not pin versions in this file.

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
- [`../../../main.md`](../../../main.md) — top-level platform specification (architecture, Keycloak, PostgreSQL 19, messaging, observability baseline)

---

> All pinned versions are in [`../RECOMMENDATIONS.md` 5](../RECOMMENDATIONS.md#5-cross-cutting-tooling-language-agnostic).
> Admin endpoints, roles, and audit conventions are pinned in
> [`../RECOMMENDATIONS.md` 6](../RECOMMENDATIONS.md#6-admin-endpoints--rbac).
> To bump versions or change the admin pattern, open a PR against the
> corresponding section — never pin versions directly in this file.
