# File Service Guide

This is a Go 1.22 service using Chi. Read the matching documentation in
`../../docs/services/file-service/` before changes.

- Keep routing, middleware, handlers, clients, and configuration separated
  under `cmd/` and internal packages as the codebase evolves.
- Treat upload and download paths as security-sensitive: preserve documented
  authorization, content validation, malware-scanning, retention, and privacy
  requirements.
- Validate all request input at the boundary. Do not log file content, tokens,
  credentials, or sensitive personal data.
- Format changed Go code with `gofmt` and run `go test ./...` for relevant
  changes. Use `go mod tidy` only when dependencies intentionally change.
- Do not edit `bin/` or generated artifacts.

## Security and Quality Gate

- Never commit secrets, tokens, private keys, production data, or unredacted
  PII; redact sensitive values from logs, errors, and test fixtures.
- Authenticate callers, authorize every operation, validate inputs, and use
  safe database/client APIs. Do not disable security checks to pass tests.
- Run `gofmt` on changed Go files and `go test ./...`; run configured linting
  when present, and report checks that cannot run.

## Ports

- `FILE_SERVICE_PUBLIC_PORT` (default `8084`) — public mux with
  `/v1/files/**` and `/v1/admin/**` per
  `docs/services/file-service/INTEGRATION.md`. The api-gateway route
  table points `/v1/files` at this port.
- `FILE_SERVICE_ADMIN_PORT` (default `8081`) — admin mux with
  `/admin/v1/**` per `docs/services/file-service/TECH.md` §10.4 for
  ops tooling (separate listener, not reachable from public ingress).
- Both muxes share the same downstream handlers and dependencies; the
  second port exists so platform-ops can reach the admin surface
  without sharing a listener with public traffic.