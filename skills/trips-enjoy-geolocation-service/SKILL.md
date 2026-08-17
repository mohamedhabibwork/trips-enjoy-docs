---
name: trips-enjoy-geolocation-service
description: Implement and review secure, clean Go changes for the Trips Enjoy geolocation service. Use for geolocation-service routing, handlers, integrations, tests, or documentation updates.
---

# Geolocation Service

Apply the [shared Code Quality Gate](../AGENTS.md) before handoff.

Before implementation, read the service [README](../../docs/services/geolocation-service/README.md), [SRS](../../docs/services/geolocation-service/SRS.md), [integration contract](../../docs/services/geolocation-service/INTEGRATION.md), [workflows](../../docs/services/geolocation-service/WORKFLOWS.md), and [technical profile](../../docs/services/geolocation-service/TECH.md). Keep API, testing, Kubernetes, and monitoring changes aligned with those documents.

Read `../../docs/services/geolocation-service/` and `../../apps/geolocation-service/AGENTS.md` before changing code.

1. Preserve location authorization, precision, retention, freshness, event,
   and resilience contracts.
2. Keep handlers thin; isolate routing, middleware, configuration, and clients.
   Validate input at the boundary and return stable, non-sensitive errors.
3. Authenticate callers, authorize every operation, avoid logging raw locations,
   tokens, or PII, and never commit secrets or production data.
4. Keep Go code simple, explicit, context-aware, and covered by focused tests.
   Do not edit `bin/` or generated artifacts.
5. Run `gofmt` on changed files and `go test ./...`; run configured linting
   when present and report unavailable checks.
