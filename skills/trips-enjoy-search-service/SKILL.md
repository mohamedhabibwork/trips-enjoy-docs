---
name: trips-enjoy-search-service
description: Implement and review secure, clean Kotlin/Spring Boot changes for the Trips Enjoy search service. Use for search-service APIs, persistence, integrations, tests, or documentation updates.
---

# Search Service

Apply the [shared Code Quality Gate](../AGENTS.md) before handoff.

Before implementation, read the service [README](../../docs/services/search-service/README.md), [SRS](../../docs/services/search-service/SRS.md), [integration contract](../../docs/services/search-service/INTEGRATION.md), [workflows](../../docs/services/search-service/WORKFLOWS.md), and [technical profile](../../docs/services/search-service/TECH.md). Keep API, testing, Kubernetes, and monitoring changes aligned with those documents.

Read `../../docs/services/search-service/` and `../../apps/search-service/AGENTS.md` before changing code.

1. Preserve derived-data, source-of-truth, event replay, privacy, freshness,
   and resilience contracts.
2. Keep controllers thin; place validation at the boundary and business rules in
   application/domain code. Use Flyway for schema changes.
3. Authenticate callers, authorize every operation, validate input, use
   parameterized persistence, and redact PII, tokens, and secrets from output.
4. Keep changes small, explicit, and covered by focused tests. Do not edit
   generated build artifacts.
5. Run `./gradlew test` and configured formatter/linter tasks when present;
   report unavailable checks and update service docs for contract changes.
