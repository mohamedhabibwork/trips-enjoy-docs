# Application Scaffolds Guide

Each subdirectory is an independently buildable service scaffold. Its local
`AGENTS.md` is authoritative for language-specific work; this file provides
rules that apply across all apps.

## Contract First

Before changing an app, read the matching documentation in
`../docs/services/<service-name>/`, especially `SRS.md`, `INTEGRATION.md`,
`ERD.md`, `WORKFLOWS.md`, `TECH.md`, `PLAN.md`, and `STATUS.md`. Keep
implementation consistent with the main architecture in
`../docs/architecture/ARCHITECTURE.md` and the API, event, data-ownership,
security, and platform-baseline documents under `../docs/`.
`ARCHITECTURE.md` is the primary guardrail for service boundaries,
database-per-service isolation, REST/Kafka communication, and resilience.

When a service graduates, changes tech profile, updates its plan phase
blocks, or completes a Phase 7.0 / 7.5 / 7.6 / 7.7 cross-cutting block,
regenerate the service's `docs/services/<service>/STATUS.md` per the
contract in `docs/PLAN_INDEX.md` "STATUS.md composition contract".
`STATUS.md` is a composition-only page — every field is a pointer to
the canonical source (e.g. lifecycle → `DEPLOYMENT_ORDER.md` §8.2;
contract counts → `INTEGRATION.md` §1–4); never invent values.

## Cross-Service Constraints

- Every service owns its database. Never add cross-service database foreign
  keys or direct table access.
- Preserve URI-versioned APIs, canonical errors, idempotency, correlation IDs,
  Kafka event versioning, and outbox/retry requirements from the service docs.
- Use UUIDs for identifiers, minor units for money, and UTC timestamps.
- Keep secrets out of source, logs, fixtures, and committed configuration.
- Update the service documentation when an externally visible behavior,
  persistence model, integration, dependency, or operational requirement
  changes.

## Repository Hygiene

- Work inside one service at a time and use its local wrapper or toolchain.
- Do not edit generated artifacts or local environments (`build/`, `bin/`,
  `.gradle/`, `.kotlin/`, `.venv/`, or caches).
- Do not change another service's contract as an incidental implementation
  detail; coordinate the documented contract change instead.

## Mandatory Security Rules

- Never commit secrets, credentials, private keys, tokens, production data, or
  unredacted PII. Read secrets only from approved configuration at runtime.
- Authenticate callers, authorize every data operation, validate untrusted
  input, and use parameterized persistence APIs.
- Prevent sensitive-data exposure in logs, errors, traces, metrics, fixtures,
  and Kafka events. Retain audit evidence and correlation IDs as specified.
- Review every new dependency for maintenance, licensing, and known-vulnerability
  risk; do not disable security controls or verification to make tests pass.
- Run the local formatter, linter, and targeted tests before handoff; report
  checks that cannot run and why.

## Code Quality Gate

All service changes inherit the root quality gate and MUST also satisfy these
application rules:

- Keep transport, application/domain logic, persistence, integrations, and
  configuration separated; avoid leaking framework concerns across layers.
- Add tests for normal behavior, validation, authorization, failure handling,
  idempotency, and state transitions affected by the change.
- Run the service’s formatter, linter/static analysis, and test command. Never
  bypass, downgrade, or suppress a quality or security failure without an
  explicit documented exception.
- Use `make quality-service SERVICE=<service-name>` as the mandatory local
  quality gate. It runs Go (`gofmt`, `go vet`, tests), Python (Ruff format and
  lint checks plus pytest), or Kotlin/Spring Boot (`./gradlew check`) as
  appropriate. Use `make quality` before cross-service handoff when the change
  affects shared contracts or multiple services.
- Keep public APIs, events, migrations, and configuration backward compatible
  unless the corresponding versioned contract and documentation are changed.

## OpenAPI / Swagger Contract

- Every HTTP service MUST expose its generated OpenAPI 3 document at
  `/openapi.json` and Swagger UI at `/docs`.
- Treat the specification as a release artifact: document every `/v1/...`
  operation, request and response schema, canonical error, security
  requirement, idempotency behavior, and correlation header from the service
  SRS and `INTEGRATION.md`.
- Keep the implementation framework-native (springdoc for Kotlin/Spring Boot,
  the service HTTP router for Go, and FastAPI for Python). Do not hand-maintain
  a divergent duplicate specification.
- Keep service-specific OpenAPI guidance in `openapi/README.md`; update it with
  every externally visible API contract change and test both documentation
  endpoints when routes or security change.

## Standard Service Folders

Every service retains its language-native source and test layout plus these
contract and operational folders where applicable:

- `openapi/` — API-documentation ownership and verification notes.
- `migrations/` or the framework-native migration location — service-owned
  schema changes only.
- `k8s/` and `monitoring/` — deployment and observability assets when the
  service has production manifests.

Do not add empty placeholder source folders or force one language's layout
onto another.

## MCP Servers

There is currently no MCP server project or MCP configuration under `apps/`.
If one is added, isolate it in its own app directory and document its exposed
tools, input validation, authorization boundaries, data access, timeouts,
audit logging, and test command in that app's local `AGENTS.md`.
