# Identity Service Guide

This is a Kotlin 2.4 / Spring Boot 4.1 service running on Java 25. Read the
matching documentation in `../../docs/services/identity-service/` before
changes.

- Keep controllers, application services, persistence, integrations, and
  configuration separated under `src/`.
- Treat identity flows, tokens, credentials, and authorization as
  security-critical; preserve the Keycloak and security architecture contract.
- Use Flyway migrations for schema changes. The service owns its PostgreSQL
  schema and must not use cross-service foreign keys.
- Preserve correlation IDs, idempotency, Kafka event versioning, and resilient
  downstream behavior required by the platform documentation.
- Run `./gradlew test` for relevant changes; use `./gradlew bootRun` only when
  local dependencies and configuration are available.
- Do not edit `build/`, `.gradle/`, or `.kotlin/`.

## Security and Quality Gate

- Never commit secrets, tokens, private keys, production data, or unredacted
  PII; redact sensitive values from logs, errors, and test fixtures.
- Authenticate callers, authorize every operation, validate inputs, and keep
  data access parameterized. Do not disable security checks to pass tests.
- Run `./gradlew test`; run configured formatting or lint tasks when present,
  and report checks that cannot run.
