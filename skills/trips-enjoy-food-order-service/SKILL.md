---
name: trips-enjoy-food-order-service
description: Implement and review secure, clean Kotlin/Spring Boot changes for the Trips Enjoy food order service. Use for food-order-service APIs, persistence, integrations, tests, or documentation updates.
---

# Food Order Service

Apply the [shared Code Quality Gate](../AGENTS.md) before handoff.

Before implementation, read the service [README](../../docs/services/food-order-service/README.md), [SRS](../../docs/services/food-order-service/SRS.md), [integration contract](../../docs/services/food-order-service/INTEGRATION.md), [workflows](../../docs/services/food-order-service/WORKFLOWS.md), and [technical profile](../../docs/services/food-order-service/TECH.md). Keep API, testing, Kubernetes, and monitoring changes aligned with those documents.

Read `../../docs/services/food-order-service/` and `../../apps/food-order-service/AGENTS.md` before changing code.

1. Preserve documented order state transitions, payment and dispatch
   compensations, idempotency, event, and resilience contracts.
2. Keep controllers thin; place validation at the boundary and business rules in
   application/domain code. Use Flyway for schema changes.
3. Authenticate callers, authorize every operation, validate input, use
   parameterized persistence, and redact PII, tokens, and secrets from output.
4. Keep changes small, explicit, and covered by focused tests. Do not edit
   generated build artifacts.
5. Run `./gradlew test` and configured formatter/linter tasks when present;
   report unavailable checks and update service docs for contract changes.
