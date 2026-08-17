---
name: trips-enjoy-chat-service
description: Implement and review secure, clean Go changes for the Trips Enjoy chat service. Use for chat-service routing, handlers, integrations, tests, or documentation updates.
---

# Chat Service

Apply the [shared Code Quality Gate](../AGENTS.md) before handoff.

Before implementation, read the service [README](../../docs/services/chat-service/README.md), [SRS](../../docs/services/chat-service/SRS.md), [integration contract](../../docs/services/chat-service/INTEGRATION.md), [workflows](../../docs/services/chat-service/WORKFLOWS.md), and [technical profile](../../docs/services/chat-service/TECH.md). Keep API, testing, Kubernetes, and monitoring changes aligned with those documents.

Read `../../docs/services/chat-service/` and `../../apps/chat-service/AGENTS.md` before changing code.

1. Preserve participant authorization, message privacy, idempotency,
   correlation, retention, event, and resilience contracts.
2. Keep handlers thin; isolate routing, middleware, configuration, and clients.
   Validate input at the boundary and return stable, non-sensitive errors.
3. Authenticate callers, authorize every operation, avoid logging message
   contents, tokens, or PII, and never commit secrets or production data.
4. Keep Go code simple, explicit, context-aware, and covered by focused tests.
   Do not edit `bin/` or generated artifacts.
5. Run `gofmt` on changed files and `go test ./...`; run configured linting
   when present and report unavailable checks.
