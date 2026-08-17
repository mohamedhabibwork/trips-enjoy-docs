# API Gateway Guide

This is the Go 1.22 scaffold for the platform's `api-gateway`. The
matching documentation lives in
[`../../docs/services/api-gateway/`](../../docs/services/api-gateway/)
— read `README.md`, `BRD.md`, `SRS.md`, `INTEGRATION.md`,
`WORKFLOWS.md`, and `TECH.md` before making changes.

## Layout

```
apps/api-gateway/
├── cmd/server/main.go             # process entry; wires OTel/Redis/Kafka/JWKS, public + admin muxes
├── internal/gateway/               # all packages; no export outside this app
│   ├── config.go                  # API_GATEWAY_* env loading; per-route table; per-upstream config
│   ├── errors.go                  # canonical RFC 7807 envelope + downstream block
│   ├── request_id.go              # ADR-0019 — X-Request-Id / X-Correlation-Id alias contract
│   ├── telemetry.go               # OpenTelemetry SDK init + traceparent propagation
│   ├── logger.go                  # slog JSON with MDC (request_id, user_id, route)
│   ├── jwt.go                     # coreos/go-oidc verifier + claims struct
│   ├── headers.go                 # claim-to-X-User-* translator
│   ├── redis.go                   # revocation set + JWKS cache + rate-limit counters
│   ├── ratelimit.go               # Redis token-bucket + RateLimit-* headers
│   ├── waf.go                     # SQLi/XXE/path-traversal/shell-injection defense-in-depth
│   ├── body_hash.go               # SHA-256 of body, capped
│   ├── audit_event.go             # audit.api.request.v1 envelope builder
│   ├── kafka_producer.go          # segmentio/kafka-go writer (sync audit / best-effort others)
│   ├── kafka_consumer.go          # identity.* + configuration.updated.v1 consumers
│   ├── circuit.go                 # per-upstream gobreaker + bulkhead
│   ├── config_snapshot.go         # atomic snapshot store (hot-reload)
│   ├── proxy.go                   # the orchestrator (WAF → JWT → role → RL → proxy → audit)
│   ├── router.go                  # chi mux assembly (public + admin)
│   ├── admin.go                   # /admin/reload + RBAC + audit.admin.api_gateway.v1
│   ├── metrics.go                 # Prometheus collectors (gateway_*)
│   └── *test.go                   # unit tests per package
├── Dockerfile                      # distroless static binary
├── Makefile                        # build / test / fmt / vet / docker
├── .env.example                    # API_GATEWAY_* + per-service UPSTREAM_URLs
├── go.mod / go.sum
└── README.md
```

## Contract First

Before changing this scaffold, read the matching per-service docs
and the cross-cutting contracts they reference:
- `architecture/API_STANDARDS.md` (§6 request id, §7 user headers,
  §11 error envelope, §12 rate-limit headers)
- `architecture/DOWNSTREAM_ERROR_CATALOG.md` (canonical code →
  status mapping)
- `architecture/OBSERVABILITY.md` (log field set + metric naming)
- `architecture/SERVICE_ISOLATION.md` (5-layer isolation pattern)
- `architecture/KEYCLOAK_ARCHITECTURE.md` (realms, claims, JWKS)
- `architecture/adrs/0019-request-id-at-the-edge.md` (canonical
  request-id contract — implemented in `request_id.go`)
- `shared/CONVENTIONS.md` (error envelope + correlation rule)
- `shared/PLATFORM_BASELINE.md` (env-var naming + container base)

## Local development rules

- Format with `gofmt -l -w .` (run before committing).
- Run `go test ./... -race -count=1` before handoff; all tests must
  be green.
- Run `go vet ./...`. No findings.
- Keep secrets out of source, logs, and `.env.example`; the
  example file MUST contain only safe defaults.
- Never log JWTs, PANs, OTPs, or full request bodies in production;
  `user_agent_hash`, `body_sha256`, `client_ip /24 truncation`
  only.
- When you add a new route, update both `defaultRoutes()` in
  `config.go` and the `TestDefaultRoutesCoverEveryDownstreamService`
  test.

## Security and Quality Gate

- Never disable JWKS, Redis revocation, or Kafka audit in
  production to make tests pass; fail-closed is the contract.
- Authenticate every request, authorize every operation, validate
  every input.
- Run `gofmt`, `go vet`, and `go test ./...` and report every check
  you couldn't run.
- The gateway is the platform's only public attack surface; treat
  any change here as a Tier-0/SRE-blocking PR.

## OSS dependency review

Each new dependency must be:
- reviewed for maintenance, license (SPDX), and known
  vulnerabilities
- added to `go.mod` with an explicit version (no `^` semantics in
  Go's resolver)
- documented in `docs/shared/OSS_DEPENDENCIES.md` §4
