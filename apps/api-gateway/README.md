# api-gateway scaffold

Stateless edge service for the Trips Enjoy platform. This Go
scaffold implements the contract documented in
[`docs/services/api-gateway/`](../../docs/services/api-gateway/)
(README, BRD, SRS, INTEGRATION, WORKFLOWS, TECH, PLAN).

## Local run

```bash
# 1. Bring up the shared infra (per the platform's docker-compose)
make -C apps/api-gateway build

# 2. Configure (copy .env.example; do not commit .env)
cp apps/api-gateway/.env.example apps/api-gateway/.env
$EDITOR apps/api-gateway/.env

# 3. Run
make -C apps/api-gateway run
```

The public mux binds `:8080`; the admin mux binds `:8081` on
`0.0.0.0` (configurable via `API_GATEWAY_ADMIN_BIND`).

## Endpoints

| Method | Path | Auth | Purpose |
|--------|------|------|---------|
| `ANY`  | `/v1/...` | bearer JWT | proxies to the matched downstream service |
| `GET`  | `/openapi.json` | none | aggregate OpenAPI 3.1 |
| `GET`  | `/openapi/{service}.json` | none | per-service OpenAPI |
| `GET`  | `/docs` | none | Swagger UI |
| `GET`  | `/health` | none | liveness |
| `GET`  | `/ready` | none | readiness (Redis + Keycloak) |
| `GET`  | `/started` | none | startup |
| `GET`  | `/healthz/downstream` | none | aggregated downstream-service health (see §Public aggregated downstream health below) |
| `GET`  | `/metrics` | none | Prometheus scrape |
| `POST` | `/admin/reload` | bearer (admin token or admin JWT) | hot-reload config |
| `POST` | `/admin/v1/jwks/refresh` | bearer | force-refresh JWKS |
| `POST` | `/admin/v1/blocklists/ip/{value}` | bearer | block IP |
| `DELETE` | `/admin/v1/blocklists/ip/{value}` | bearer | unblock IP |

### Public aggregated downstream health

`GET /healthz/downstream` returns one entry per distinct
downstream service in the gateway's route table (20 services).
Each entry combines a fresh HTTP probe against `<upstream>/health`
(the per-service liveness endpoint per
`docs/architecture/OBSERVABILITY.md` §"Health, Readiness, Liveness";
liveness is intentionally side-effect-free so the aggregator does
not recursively probe dependencies) with the current
`sony/gobreaker` circuit state for that upstream.

**Auth:** none (public on port 8080). The body carries only
summary state (UP/DOWN + service names + latencies); the existing
`gateway:blocks:ip:%s` Redis blocklist still applies; linkerd mTLS
protects in-cluster callers.

**Query parameters (all optional):**

| Param | Default | Range | Purpose |
|---|---|---|---|
| `service` | (all) | one service name | restrict probe to a single service |
| `timeout` | `2s` | `100ms..5s` | per-service probe budget (env: `API_GATEWAY_DOWNSTREAM_PROBE_TIMEOUT`) |
| `parallelism` | `8` | `1..32` | concurrent probe worker pool size (env: `API_GATEWAY_DOWNSTREAM_PROBE_PARALLEL`) |

**Probe path:** `<upstream>/health` (single path; matches Spring
Actuator's `OperationalController` and the Go services' static
`/health`).

**Circuit-state policy:** probe **bypasses** `sony/gobreaker`
(uses a fresh `*http.Client` with the per-request timeout). The
breaker state is read via `CircuitRegistry.ForEach` and surfaced as
the `circuit_state` field — so the operator sees both the live
"is it healthy right now?" probe AND the gateway's "am I currently
refusing to call it?" verdict.

**Response shape** (always HTTP 200 unless the aggregator itself
breaks; per-service failures live inside the body as DOWN entries
with a `downstream` block):

```json
{
  "status": "DEGRADED",
  "checked_at": "2026-08-14T10:23:45.123Z",
  "correlation_id": "01HZX9C7T0XK2P9F0V6E4B1MZA",
  "config_version": 17,
  "probe_timeout_ms": 2000,
  "totals": { "ok": 18, "degraded": 0, "down": 2, "skipped": 0, "total": 20 },
  "services": [
    { "service": "configuration-service", "status": "UP", "latency_ms": 12,
      "circuit_state": "closed", "http_status": 200, "endpoint": "/health" },
    { "service": "trip-service", "status": "DOWN", "latency_ms": 2001,
      "circuit_state": "open", "http_status": 0,
      "downstream": { "code": "DEPENDENCY_TIMEOUT", "status": 504,
                      "latency_ms": 2001, "message": "probe timed out after 2s" } }
  ]
}
```

Top-level `status` is `UP` (all probed ok), `DEGRADED` (some up,
some not), `DOWN` (all probed failed), or `SKIPPED_ONLY` (caller
passed an unknown `?service=` name). Per-service `status` is
`UP` / `DOWN` / `DEGRADED` / `SKIPPED`. RFC 7807 envelopes only
fire for aggregator-level failures or `?timeout=` parse errors.

**Stub-service policy:** always probe. A stub service that isn't
running reports `DOWN` with
`downstream.code = DEPENDENCY_UPSTREAM_FAILURE` and
`message: "dial tcp: lookup <service>: no such host"`. The
gateway never knows or cares about graduate status.

**No audit emission:** the endpoint is public and high-volume;
it does not emit `audit.api.request.v1` events. The `requestId`
header is still propagated on each downstream probe so the
target service can correlate its own audit events.

## Env-var reference

See [`.env.example`](./.env.example). Every variable is prefixed
with `API_GATEWAY_` per the platform's `<SVC>_*` env-var convention
(docs/shared/PLATFORM_BASELINE.md §11). Per-service upstream
overrides use `<SERVICE>_UPSTREAM_URL`.

## Test

```bash
make -C apps/api-gateway test
```

`go test ./...` exercises the canonical contract paths:
- JWT verification + claim extraction + coarse role check
- claim-to-X-User-* header translation (with client-side spoof
  stripping)
- rate-limit decision + `RateLimit-*` / `Retry-After` headers
- WAF pre-check (SQLi, XXE, path-traversal, shell-injection)
- request-id alias rules (ADR-0019): prefer `X-Request-Id`,
  fall back to `X-Correlation-Id`, generate UUIDv7 if absent;
  retry-stable
- canonical error envelope (RFC 7807 + downstream block)
- circuit-breaker trip and `ErrCircuitOpen` propagation
- atomic Snapshot hot-swap (config snapshot store)
- audit-event builders and Kafka header injection
- base64url decoder

## Container image

`make docker` builds a static binary and ships it via
`gcr.io/distroless/static-debian12:nonroot` (PLATFORM_BASELINE.md §1).
The image runs as the unprivileged `nonroot` user.

## More

- [AGENTS.md](./AGENTS.md) — contributor rules
- Documentation: [`docs/services/api-gateway/`](../../docs/services/api-gateway/)
- Cross-cutting contracts: [`docs/shared/`](../../docs/shared/),
  [`docs/architecture/`](../../docs/architecture/)
