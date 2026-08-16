---
name: trips-enjoy-customer-service
description: Implement and review secure, clean Kotlin/Spring Boot changes for the Trips Enjoy customer service. Use for customer-service APIs, persistence, integrations, tests, or documentation updates.
---

# Customer Service

Apply the [shared Code Quality Gate](../AGENTS.md) before handoff.

Before implementation, read the service [README](../../docs/services/customer-service/README.md), [SRS](../../docs/services/customer-service/SRS.md), [integration contract](../../docs/services/customer-service/INTEGRATION.md), [workflows](../../docs/services/customer-service/WORKFLOWS.md), and [technical profile](../../docs/services/customer-service/TECH.md). Keep API, testing, Kubernetes, and monitoring changes aligned with those documents.

Read `../../docs/services/customer-service/` and `../../apps/customer-service/AGENTS.md` before changing code.

1. Preserve documented customer lifecycle, PII, authorization, event, and
   resilience contracts.
2. Keep controllers thin; place validation at the boundary and business rules in
   application/domain code. Use Flyway for schema changes.
3. Authenticate callers, authorize every operation, validate input, use
   parameterized persistence, and redact PII, tokens, and secrets from output.
4. Keep changes small, explicit, and covered by focused tests. Do not edit
   generated build artifacts.
5. Run `./gradlew test` and configured formatter/linter tasks when present;
   report unavailable checks and update service docs for contract changes.
