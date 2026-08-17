# Geolocation Service Guide

This is a Go service (chi router + net/http) backed by PostgreSQL 19 +
PostGIS 3.4 and Redis 8. Read the matching documentation in
`../../docs/services/geolocation-service/` before changes.

## Layout

```
apps/geolocation-service/
├── cmd/server/main.go               # entrypoint — wires every dep, two http.Server
├── internal/
│   ├── admin/                       # /v1/admin/* handlers + HMAC verify
│   ├── auth/                        # JWT stub + role middleware
│   ├── chain/                       # provider chain resolver + gobreaker + rate limit
│   ├── config/                      # env loading (GEOLOCATION_SERVICE_*)
│   ├── db/                          # pgxpool + UUIDv7 helper
│   ├── events/                      # event envelope + stdout/Kafka publisher + outbox
│   ├── geocoding/                   # geocode / reverse / ETA / route + cache + validation
│   ├── httpapi/                     # chi router + middleware + handlers + OpenAPI
│   ├── observability/               # slog JSON logger + best-effort OTel init
│   ├── provider/                    # MapProvider interface + Registry
│   │   ├── google/   mapbox/   here/      # commercial stubs
│   │   ├── osrm/  valhalla/  nominatim/  # self-host stubs
│   │   ├── pelias/  photon/             # geocoder stubs
│   │   └── mock/                       # deterministic in-process adapter
│   └── zones/                       # last-known city + zone-update invalidator
└── migrations/                      # golang-migrate, 12 numbered pairs
```

## Conventions

- Keep routing, middleware, handlers, clients, and configuration
  separated under `cmd/` and internal packages as the codebase evolves.
- Treat location data as sensitive: preserve authorization,
  precision, retention, freshness, and event semantics from the
  service documentation (per SEC--003, SEC--004, SEC--008).
- The geocoding service layer never branches on `vendor_id` — every
  adapter returns canonical `GeoAddress` / `EtaEstimate` / `Route`
  shapes (per INTEGRATION.md §4.4).
- Chain resolver semantics are in `internal/chain/resolver.go`
  (README.md §4.5): skip on circuit-open / capability-mismatch /
  rate-limit-empty; advance on retryable failure; return immediately
  on non-retryable (4xx).
- Admin POSTs require `X-Signature: t=<unix>,v1=<hex>` HMAC
  (INTEGRATION.md §5.4); the dev scaffold accepts unsigned requests
  when `GEOLOCATION_SERVICE_HMAC_SECRET` is empty.
- All state-changing POSTs require `Idempotency-Key` per SRS.md §15.
- Cache `formatted_address` encrypted at rest with `pgcrypto` (PII,
  Confidential class); cache TTL ≤ 24h (per SEC--003).
- Validate all request input at the boundary. Do not log raw
  coordinates beyond the 6-decimal rounded form, tokens, credentials,
  or sensitive personal data.

## Build / Test

- `go build ./...` from `apps/geolocation-service/` must succeed.
- `gofmt -l .` must produce no diffs.
- `go vet ./...` clean.
- `go test ./...` — package-internal smoke tests.
- The canonical build command from the repo root is
  `make build-service SERVICE=geolocation-service`.

## Security and Quality Gate

- Never commit secrets, tokens, private keys, production data, or
  unredacted PII; redact sensitive values from logs, errors, and
  test fixtures.
- Authenticate callers, authorize every operation, validate inputs,
  and use safe database/client APIs. Do not disable security checks
  to pass tests.
- Run `gofmt` on changed Go files and `go test ./...`; run
  configured linting when present, and report checks that cannot
  run.

## Out of Scope (Follow-ups)

The dev scaffold intentionally stops short of production wiring:

- pgx-backed repos for cache / audit / outbox / inbox / provider_*
  (today: in-memory stores).
- Real vendor REST calls (today: stubs return `ErrNotConfigured`;
  the mock returns deterministic canned responses).
- Vault loader + Vault-rotated provider credentials.
- Real Kafka publisher (today: stdout publisher; Kafka writer wired
  but unused when bootstrap-servers empty).
- Real Keycloak JWKS verifier (today: header-driven stub).
- Real PostGIS `ST_Intersects` invalidation job (today: counter
  increment, no SQL DELETE).
- Co-signature enforcement on `PUT /v1/admin/region-chains/...`
  (placeholder).
- Real OTel exporter (today: no-op when endpoint empty; wiring
  pattern present).

Each of these is a follow-up PR; the scaffold is intentionally
runnable as-is so the contract is checkable end-to-end.