# Repository Guide

## Overview

This repository documents the Trips Enjoy platform: a production-grade
microservices system for ride-hailing, food delivery, and shared platform
capabilities. The primary source of truth is the documentation in `main.md`
and `docs/`. The `apps/` directory contains per-service implementation
scaffolds in Kotlin/Spring Boot, Go, and Python.

## Before Making Changes

1. Read `docs/README.md` for the documentation map and platform conventions.
2. For a service-specific change, read that service's `README.md`, `SRS.md`,
   `INTEGRATION.md`, `WORKFLOWS.md`, and `TECH.md` before editing.
3. Check the architecture documents referenced by the affected service. In
   particular, preserve the contracts in `API_STANDARDS.md`,
   `EVENT_ARCHITECTURE.md`, `DATA_OWNERSHIP.md`, and `PLATFORM_BASELINE.md`.
4. Treat `docs/architecture/ARCHITECTURE.md` as the primary implementation
   guardrail: preserve the 20-service bounded-context model, database-per-
   service isolation, REST/Kafka communication boundaries, security, and
   resilience decisions. Read the directly relevant companion architecture
   documents before changing a boundary or contract.

## Documentation Rules

- Keep service names in `kebab-case` with the `-service` suffix.
- Keep requirement IDs stable and use the established prefixes: `BR-`, `FR-`,
  `NFR-`, `SEC-`, and `DATA-`.
- Use URI-versioned REST APIs (`/v1/...`), version events as
  `domain.entity.event.vN`, and follow the canonical error envelope.
- Document service-owned data only. Cross-service identifiers are UUID values;
  do not introduce database foreign keys across service boundaries.
- Store monetary values in minor units and timestamps as UTC `timestamptz`.
- Update related artifacts together when a contract changes: service README,
  SRS, ERD, integration, workflows, tech profile, plan, and affected shared or
  architecture documentation.
- Keep Mermaid diagrams valid and use relative Markdown links that work from
  the containing document.

## Application Scaffolds

- Respect each service's existing language and framework choice; do not
  homogenize services without an explicit architecture decision.
- Do not edit generated or local artifacts such as `build/`, `bin/`,
  `.gradle/`, `.kotlin/`, `.venv/`, or dependency caches.
- Use the service-local manifest and test tooling when available. Avoid adding
  dependencies unless the accompanying documentation and license inventory are
  updated as needed.
- Every HTTP service MUST publish an OpenAPI 3 contract at `/openapi.json` and
  provide Swagger UI at `/docs`. Keep the generated specification aligned with
  the service SRS and integration contract: versioned paths, request/response
  schemas, canonical errors, security schemes/scopes, idempotency, and
  correlation headers must be represented and tested.
- Keep implementation folders intentional and framework-native: Kotlin services
  use `src/main` and `src/test`; Go services use `cmd` and `internal`; Python
  services use `app` and `tests`. Keep migrations, deployment assets, and
  OpenAPI contract notes in their dedicated service folders; do not create a
  cross-service shared code folder.

## Validation

- For documentation-only changes, verify links, headings, terminology, and
  consistency with the authoritative architecture documents.
- For scaffold changes, run the narrowest relevant formatter, build, or test
  command for that service and report any command not run.
- Keep changes focused; do not overwrite unrelated uncommitted work in
  `apps/`.

## Security Baseline

- Treat authentication, authorization, payment, location, identity, message,
  file, and customer data as security-sensitive by default.
- Never commit secrets, tokens, private keys, production data, or connection
  strings. Use configuration references and secret managers instead.
- Validate untrusted input at service boundaries; enforce authorization before
  data access; use parameterized queries and avoid unsafe deserialization.
- Do not log credentials, access tokens, payment data, raw location data, file
  contents, or unnecessary PII. Preserve required audit records and
  correlation IDs.
- Pin and review new dependencies, keep license information current, and run
  the relevant formatter, linter, and tests before handoff.

## Code Quality Gate

Before handoff, every code change MUST meet all applicable checks:

- Keep responsibilities small and explicit; prefer clear names, typed
  boundaries, and straightforward control flow over clever abstractions.
- Format changed source, pass the configured static analysis/linter without
  suppressing findings, and keep compiler warnings actionable.
- Add or update focused tests for behavior, validation failures, authorization,
  error paths, and regressions. Do not skip, weaken, or delete tests to pass.
- Run the narrowest relevant test suite, then broader checks when integration,
  persistence, events, security, or shared contracts change.
- Update contracts and operational documentation with externally visible code
  changes; report every validation command that could not run.
