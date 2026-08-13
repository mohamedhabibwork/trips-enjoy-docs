# ADR-0023: Spring Initializr as the canonical Spring Boot service scaffolder

- Status: Accepted
- Date: 2026-08-14
- Authors: Platform Architecture + Backend Platform team
- Deciders: Architecture Review Board
- Tags: spring-boot, initializr, scaffolding, build-tooling, ci, codegen

## Context and Problem Statement

The platform's 14 Kotlin + Spring Boot 4 backend services (admin,
audit, configuration, courier, customer, driver, food-order,
identity, ledger, notification, payment, pricing, restaurant,
search, trip) need to be created with a consistent, auditable
Spring Boot scaffold. Today, every service is hand-written:

- **`build.gradle.kts`** — written from scratch, with each
  service team picking their own Spring Boot / Kotlin /
  Hibernate / Lettuce / Kafka / Flyway versions. Result: drift
  between services; some services pin to 4.0.0, some to 4.0.1,
  some to 4.0.2, some pull in 4.1.0 snapshots by accident.
- **Dependencies** — the "common" set (web + data-jpa +
  validation + actuator + security + data-redis + spring-kafka +
  testcontainers + flyway + postgresql + kotlin-coroutines) is
  hand-rolled per service. Some services forget Springdoc.
  Some forget Flyway. Some forget testcontainers.
- **Java + Kotlin versions** — the build target Java drifts
  between 17 and 21. The Kotlin version drifts between 1.9 and
  2.2.
- **`Application.kt`** — the `@SpringBootApplication` class is
  hand-written; some services forget the `scanBasePackages`
  argument; some services miss the platform's auto-config
  imports.
- **CI** — the build is reproducible on the dev's laptop
  but the lockfile drift causes CI failures ("works on my
  machine").

The platform's [`shared/INTEGRATION.md`](../../shared/INTEGRATION.md)
already standardises the **one-line install** of
`com.trips-enjoy.platform:spring-boot-starter:4.1.0`. The
**scaffold** is the missing piece.

## Decision Drivers

- **Reproducibility** — the scaffold must be byte-deterministic
  given the same recipe. Every PR that adds a service must
  start from the same baseline.
- **Auditability** — every dependency in the scaffold must be
  in [`shared/OSS_DEPENDENCIES.md`](../../shared/OSS_DEPENDENCIES.md)
  § 6 (the platform's OSS bundle matrix) and have a known
  SPDX ID + license compatibility.
- **Drift prevention** — Spring Boot / Kotlin / Hibernate /
  Lettuce / Kafka / Flyway / Springdoc versions must be
  pinned to the BOM; a single version-pinning event covers
  every service.
- **Onboarding** — a new backend engineer must be able to
  scaffold a new service in a single `curl` call, not read
  50 pages of "how to set up a Spring Boot project".
- **Reviewability** — the first commit of a new service
  should be the Initializr scaffold + the per-service docs
  (so the service is reviewable before the implementation
  begins). No business logic in the first commit.

## Considered Options

- **Option A — Hand-written scaffolds per service.** Status
  quo. *Rejected*: drift, audit failures, slow onboarding.

- **Option B — Internal scaffolder** (a repo template
  `trips-enjoy/spring-boot-service-template` that every
  service is forked from). *Rejected*: still per-service
  copy-paste; the template itself drifts over time; the
  template is **not** the official Spring Boot scaffold
  (the Spring Boot project is).

- **Option C — Spring Initializr ([start.spring.io](https://start.spring.io/))**
  as the canonical scaffolder. *Chosen* — it IS the official
  Spring Boot scaffolder, maintained by the Spring Boot
  project; the dependency set is curated by the Spring team;
  the URL-based recipe is reproducible; the output is
  byte-deterministic for the same recipe.

- **Option D — Both B + C** (internal template derived from
  Initializr). *Considered*: useful for "first commit is the
  scaffold + the docs" pattern, but adds a maintenance
  burden (the template must be regenerated when Initializr
  changes). *Rejected* — the per-service docs
  ([`README.md` §"Per-Service Documentation Contract"](../../README.md#per-service-documentation-contract))
  are the source of truth for the per-service override;
  the Initializr output is the source of truth for the
  generic scaffold. Splitting the responsibilities is
  simpler than a single internal template.

## Decision Outcome

Chosen option: **C — Spring Initializr as the canonical
scaffolder.**

The full recipe (URL + per-service override) is in
[`docs/services/SPRING_INITIALIZR.md`](../../services/SPRING_INITIALIZR.md).
Summary:

- **Standard settings** — Gradle - Kotlin DSL, Kotlin
  2.2.x, Spring Boot 4.0.0, Java 21, Group `com.trips-enjoy`,
  Artifact `<service-name>`, Package `com.trips-enjoy.<service-name>`.
- **Standard dependencies** — `web, data-jpa, validation,
  actuator, security, data-redis, spring-kafka, testcontainers,
  flyway, postgresql, kotlin-coroutines, webflux` (the
  union of the 12 starters every Spring Boot 4 service
  needs; per-service override table in `SPRING_INITIALIZR.md`
  § 3).
- **Standard post-scaffold step** — add the platform's
  [`platform-spring-boot-starter`](../../shared/INTEGRATION.md)
  (one line in `build.gradle.kts`) for the cross-cutting
  concerns. The starter's `platform-bom` resolves every
  transitive version to a single pinned value.
- **Standard first commit** — the Initializr scaffold + the
  per-service docs (`README.md`, `BRD.md`, `SRS.md`, `ERD.md`,
  `INTEGRATION.md`, `WORKFLOWS.md`, `TECH.md`, `PLAN.md`)
  + the `SKELETON.gradle.kts` (per
  [`RECOMMENDATIONS.md` § 5](../../services/RECOMMENDATIONS.md#5-extractability-skeleton-files))
  + the `Hard service-to-service dependencies` callout
  (per [`DEPLOYMENT_ORDER.md`](../../DEPLOYMENT_ORDER.md)).
  **No Java code** in the first commit.

### Consequences

- Good: the scaffold is **byte-deterministic** for the same
  recipe. The CI gate (`enforceSpringBootBom`,
  `enforceInitializrCoherence`) rejects drift.
- Good: every new service starts with the same baseline as
  every other service; the "what's in `build.gradle.kts`?"
  question is answered by the recipe in
  `SPRING_INITIALIZR.md`.
- Good: the OSS licence matrix is checked at scaffold time
  (every Initializr dependency is in
  `OSS_DEPENDENCIES.md` § 6).
- Good: the "first commit is docs" pattern is enforceable —
  the CI gate rejects any first commit that contains Java
  code in `src/main/kotlin/` (the Initializr scaffold's
  `Application.kt` is allowed; service-specific code is
  not).
- Good: onboarding a new engineer is a single `curl` call
  to `https://start.spring.io/starter.zip?...&dependencies=...`
  followed by one `gradle build` and one
  `git init && git add . && git commit -m "scaffold: <service>"`.
- Neutral: a new platform-team member must understand
  Initializr's URL schema. The recipe in
  `SPRING_INITIALIZR.md` is the single source of truth.
- Neutral: the platform still maintains the
  `platform-spring-boot-starter` (a separate repo / artifact);
  the Initializr scaffold is generic, the starter is
  platform-specific. The split is intentional: Initializr
  changes only when Spring Boot changes; the starter changes
  when the platform changes.
- Bad: when Spring Initializr ships a new Spring Boot
  version (e.g. 4.1.0 → 4.2.0), the platform's
  `platform-spring-boot-starter` must be re-pinned and every
  service must regenerate its scaffold. This is intentional
  (a single version-pinning event covers every service) but
  it is a maintenance cost.

### Confirmation

- [`docs/services/SPRING_INITIALIZR.md`](../../services/SPRING_INITIALIZR.md)
  is the single source of truth for the recipe; it is
  cross-referenced from `services/README.md`,
  `shared/INTEGRATION.md`, `shared/README.md`, and
  `architecture/HLD.md`.
- Every per-service `PLAN.md` has the `Hard
  service-to-service dependencies` callout (per
  `DEPLOYMENT_ORDER.md`); the callout is added after the
  Initializr scaffold lands.
- The CI gate (`enforceSpringBootBom`,
  `enforceInitializrCoherence`, `enforceOSSAllowlist`)
  rejects PRs that drift from the recipe.

## References

- [https://start.spring.io/](https://start.spring.io/) —
  Spring Initializr (the upstream tool).
- [https://docs.spring.io/initializr/docs/current/reference/html/](https://docs.spring.io/initializr/docs/current/reference/html/) —
  Spring Initializr reference.
- [`../../services/SPRING_INITIALIZR.md`](../../services/SPRING_INITIALIZR.md) —
  the full recipe (canonical settings + canonical dependencies
  + per-service override table + end-to-end workflow).
- [`../../shared/INTEGRATION.md`](../../shared/INTEGRATION.md) —
  the one-line install of `platform-spring-boot-starter`
  (the second step after the Initializr scaffold).
- [`../../shared/VERSIONING.md`](../../shared/VERSIONING.md) —
  the version-pin policy + the platform's BOM.
- [`../../shared/OSS_DEPENDENCIES.md`](../../shared/OSS_DEPENDENCIES.md) —
  the SPDX + licence matrix; every Initializr dependency is
  in § 6.
- [`../../services/RECOMMENDATIONS.md` § 5](../../services/RECOMMENDATIONS.md#5-extractability-skeleton-files) —
  the `SKELETON.gradle.kts` extractability contract.
- [`../../DEPLOYMENT_ORDER.md`](../../DEPLOYMENT_ORDER.md) —
  the per-service `Hard service-to-service dependencies`
  callout that every service `PLAN.md` carries.
- [`../../README.md` §"Per-Service Documentation Contract"](../../README.md#per-service-documentation-contract) —
  the per-service docs that are added in the first commit.
