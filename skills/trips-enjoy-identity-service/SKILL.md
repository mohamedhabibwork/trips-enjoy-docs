---
name: trips-enjoy-identity-service
description: Implement and review secure, clean Kotlin/Spring Boot changes for the Trips Enjoy identity service. Use for identity-service APIs, persistence, integrations, tests, or documentation updates.
---

# Identity Service

Apply the [shared Code Quality Gate](../AGENTS.md) before handoff.

Before implementation, read the service [README](../../docs/services/identity-service/README.md), [SRS](../../docs/services/identity-service/SRS.md), [integration contract](../../docs/services/identity-service/INTEGRATION.md), [workflows](../../docs/services/identity-service/WORKFLOWS.md), and [technical profile](../../docs/services/identity-service/TECH.md). Keep API, testing, Kubernetes, and monitoring changes aligned with those documents.

Read `../../docs/services/identity-service/` and `../../apps/identity-service/AGENTS.md` before changing code.

1. Preserve Keycloak, authentication, authorization, token, audit, event, and
   resilience contracts. Treat all identity flows as security-critical.
2. Keep controllers thin; place validation at the boundary and business rules in
   application/domain code. Use Flyway for schema changes.
3. Authenticate callers, authorize every operation, validate input, use
   parameterized persistence, and redact PII, tokens, and secrets from output.
4. Keep changes small, explicit, and covered by focused tests. Do not edit
   generated build artifacts.
5. Run `./gradlew test` and configured formatter/linter tasks when present;
   report unavailable checks and update service docs for contract changes.
