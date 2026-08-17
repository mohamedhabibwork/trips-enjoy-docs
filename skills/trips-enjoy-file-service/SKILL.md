---
name: trips-enjoy-file-service
description: Implement and review secure, clean Go changes for the Trips Enjoy file service. Use for file-service routing, upload/download, integrations, tests, or documentation updates.
---

# File Service

Apply the [shared Code Quality Gate](../AGENTS.md) before handoff.

Before implementation, read the service [README](../../docs/services/file-service/README.md), [SRS](../../docs/services/file-service/SRS.md), [integration contract](../../docs/services/file-service/INTEGRATION.md), [workflows](../../docs/services/file-service/WORKFLOWS.md), and [technical profile](../../docs/services/file-service/TECH.md). Keep API, testing, Kubernetes, and monitoring changes aligned with those documents.

Read `../../docs/services/file-service/` and `../../apps/file-service/AGENTS.md` before changing code.

1. Preserve upload/download authorization, content validation, malware scan,
   retention, privacy, event, and resilience contracts.
2. Keep handlers thin; isolate routing, middleware, configuration, and clients.
   Validate input at the boundary and return stable, non-sensitive errors.
3. Authenticate callers, authorize every operation, avoid logging file content,
   tokens, or PII, and never commit secrets or production data.
4. Keep Go code simple, explicit, context-aware, and covered by focused tests.
   Do not edit `bin/` or generated artifacts.
5. Run `gofmt` on changed files and `go test ./...`; run configured linting
   when present and report unavailable checks.
