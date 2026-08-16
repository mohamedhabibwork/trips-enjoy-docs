---
name: trips-enjoy-api-gateway
description: Implement and review secure, clean Go changes for the Trips Enjoy API gateway. Use for gateway routing, middleware, handlers, integrations, tests, or documentation updates.
---

# API Gateway

Apply the [shared Code Quality Gate](../AGENTS.md) before handoff.

Before implementation, read the service [README](../../docs/services/api-gateway/README.md), [SRS](../../docs/services/api-gateway/SRS.md), [integration contract](../../docs/services/api-gateway/INTEGRATION.md), [workflows](../../docs/services/api-gateway/WORKFLOWS.md), and [technical profile](../../docs/services/api-gateway/TECH.md). Keep API, testing, Kubernetes, and monitoring changes aligned with those documents.

Read `../../docs/services/api-gateway/` and `../../apps/api-gateway/AGENTS.md` before changing code.

1. Preserve API versioning, authentication propagation, canonical errors,
   correlation IDs, rate limits, downstream resilience, and audit contracts.
2. Keep handlers thin; isolate routing, middleware, configuration, and clients.
   Validate input at the boundary and return stable, non-sensitive errors.
3. Authenticate callers, authorize every operation, avoid logging tokens or
   payload PII, and never commit secrets or production data.
4. Keep Go code simple, explicit, context-aware, and covered by focused tests.
   Do not edit `bin/` or generated artifacts.
5. Run `gofmt` on changed files and `go test ./...`; run configured linting
   when present and report unavailable checks.
