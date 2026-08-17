---
name: trips-enjoy-restaurant-service
description: Implement and review secure, clean Kotlin/Spring Boot changes for the Trips Enjoy restaurant service. Use for restaurant-service APIs, persistence, integrations, tests, or documentation updates.
---

# Restaurant Service

Apply the [shared Code Quality Gate](../AGENTS.md) before handoff.

Before implementation, read the service [README](../../docs/services/restaurant-service/README.md), [SRS](../../docs/services/restaurant-service/SRS.md), [integration contract](../../docs/services/restaurant-service/INTEGRATION.md), [workflows](../../docs/services/restaurant-service/WORKFLOWS.md), and [technical profile](../../docs/services/restaurant-service/TECH.md). Keep API, testing, Kubernetes, and monitoring changes aligned with those documents.

Read `../../docs/services/restaurant-service/` and `../../apps/restaurant-service/AGENTS.md` before changing code.

1. Preserve merchant authorization, menu versioning, availability, event, and
   resilience contracts.
2. Keep controllers thin; place validation at the boundary and business rules in
   application/domain code. Use Flyway for schema changes.
3. Authenticate callers, authorize every operation, validate input, use
   parameterized persistence, and redact PII, tokens, and secrets from output.
4. Keep changes small, explicit, and covered by focused tests. Do not edit
   generated build artifacts.
5. Run `./gradlew test` and configured formatter/linter tasks when present;
   report unavailable checks and update service docs for contract changes.
