# Chat Service Guide

This is a Go 1.22 service using Chi. Read the matching documentation in
`../../docs/services/chat-service/` before changes.

- Keep routing, middleware, handlers, clients, and configuration separated
  under `cmd/` and internal packages as the codebase evolves.
- Preserve participant authorization, message privacy, idempotency,
  correlation, retention, and event semantics from the service documentation.
- Validate all request input at the boundary. Do not log message content,
  tokens, credentials, or sensitive personal data.
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
