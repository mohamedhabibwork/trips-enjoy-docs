# Spring Initializr — Platform Standard Scaffolding

> **Purpose.** This document is the **canonical recipe** for
> scaffolding every Kotlin + Spring Boot 4 backend service in the
> `trips-enjoy` platform from
> [https://start.spring.io/](https://start.spring.io/) (Spring
> Initializr).
>
> **Scope.** All 14 Kotlin + Spring Boot 4 backend services
> (admin, audit, configuration, courier, customer, driver,
> food-order, identity, ledger, notification, payment, pricing,
> restaurant, search, trip) are scaffolded from this recipe, then
> immediately adopt
> `com.trips-enjoy.platform:spring-boot-starter` for the
> cross-cutting concerns (Web RFC 7807, security Keycloak, data
> JPA, money, caching, messaging, observability, audit, i18n,
> API docs, test).
>
> **Why Initializr?** Spring Initializr gives us a deterministic,
> audited Spring Boot scaffold with the right BOM versions, the
> right Kotlin + JVM toolchain, and the platform's standard
> dependencies — all reproducible from a single curl / API call
> per service. It eliminates "which Spring Boot version?" and
> "did we forget Springdoc?" drift.

---

## 1. The recipe (canonical settings)

| Setting | Value | Rationale |
|---|---|---|
| **Project** | Gradle - Kotlin DSL | The platform's build tool for Kotlin services (per [`../shared/CONVENTIONS.md`](../shared/CONVENTIONS.md)) |
| **Language** | Kotlin | The platform's business + financial core language |
| **Spring Boot** | **4.0.0** (or latest 4.x stable) | The platform's locked Spring Boot version (per `platform-spring-boot-starter:4.1.0` BOM) |
| **Java** | **21** | The JVM target for Kotlin 2.2.x; JDK 25 is the recommended runtime but JDK 21 is the build target for Gradle compatibility |
| **Group** | `com.trips-enjoy` | The platform's Maven group ID (per `docs/shared/VERSIONING.md`) |
| **Artifact** | `<service-name>` (kebab-case, matches `services/<service>/` directory) | Matches the platform's per-service convention |
| **Name** | `<service-name>` | Same |
| **Description** | copy from the per-service `BRD.md` "Purpose" paragraph | The Initializr-generated `README.md` is the bootstrap; the per-service `README.md` overwrites it once the service is initialized |
| **Package name** | `com.trips-enjoy.<service-name>` (e.g. `com.trips-enjoy.customer`) | The platform's Java package convention |
| **Packaging** | Jar | Standard Spring Boot deployment artifact |
| **Java language level** | 21 | Matches the JVM target |

### 1.1 Initializr URL with all settings pre-applied

A single `curl` (or browser visit) per service scaffolds the
project. Replace `<SERVICE>` with the kebab-case service name
(e.g. `customer-service`).

```
https://start.spring.io/starter.zip?
  type=gradle-project-kotlin
  &language=kotlin
  &bootVersion=4.0.0
  &baseDir=<SERVICE>
  &groupId=com.trips-enjoy
  &artifactId=<SERVICE>
  &name=<SERVICE>
  &description=trips-enjoy+<SERVICE>
  &packageName=com.trips-enjoy.<SERVICE_DOT>
  &packaging=jar
  &javaVersion=21
  &dependencies=web,data-jpa,validation,actuator,security,data-redis,spring-kafka,testcontainers,flyway,postgresql,kotlin-coroutines,webflux
```

The `<SERVICE_DOT>` is `<SERVICE>` with `-` replaced by `.` (e.g.
`customer.service`).

> **Token `<SERVICE>`** is the canonical service slug (matches
> the per-service `README.md` and the `services/<SERVICE>/`
> directory name). The 14 Kotlin + Spring Boot services that
> use this recipe are listed in
> [§3 Service-by-service override table](#3-service-by-service-override-table).

---

## 2. The recipe (canonical dependencies)

The platform's standard dependency set is the union of the
Initializr IDs that are the **minimum viable Spring Boot 4
service**. Each dependency is also justified in
[`../shared/OSS_DEPENDENCIES.md`](../shared/OSS_DEPENDENCIES.md)
(SPDX + license) and consumed by the per-service `TECH.md`
*Open-source bundle* section.

| Initializr ID | Group / Artifact | Used for |
|---|---|---|
| `web` | `org.springframework.boot:spring-boot-starter-web` | REST API + servlet stack (MVC) |
| `data-jpa` | `org.springframework.boot:spring-boot-starter-data-jpa` | PostgreSQL 19 + Hibernate 7 + HikariCP |
| `validation` | `org.springframework.boot:spring-boot-starter-validation` | Jakarta Bean Validation (request body validation) |
| `actuator` | `org.springframework.boot:spring-boot-starter-actuator` | `/health`, `/ready`, `/started`, `/metrics`, `/info` |
| `security` | `org.springframework.boot:spring-boot-starter-security` | Keycloak resource server, RBAC, `/admin/v1/**` contract |
| `data-redis` | `org.springframework.boot:spring-boot-starter-data-redis` | Redis 8 cache + Pub/Sub + idempotency keys |
| `spring-kafka` | `org.springframework.kafka:spring-kafka` | Event producer (transactional outbox) + event consumer (inbox + dedup) |
| `testcontainers` | `org.testcontainers:testcontainers-bom` | PostgreSQL + Kafka + Redis containers for integration tests |
| `flyway` | `org.flywaydb:flyway-core` + `flyway-database-postgresql` | Versioned, forward-only PostgreSQL migrations |
| `postgresql` | `org.postgresql:postgresql` | The PostgreSQL JDBC driver |
| `kotlin-coroutines` | `org.jetbrains.kotlinx:kotlinx-coroutines-core` | `suspend` functions + structured concurrency |
| `webflux` | `org.springframework.boot:spring-boot-starter-webflux` | Reactive stack for services that need non-blocking I/O (e.g. `pricing-service` WebFlux, `chat-service` WebSocket) |

### 2.1 The platform starter is added **after** Initializr scaffolding

The Initializr URL above scaffolds a **vanilla** Spring Boot 4
service. The platform's cross-cutting concerns are added
**separately** by pulling in the
`com.trips-enjoy.platform:spring-boot-starter` artifact (per
[`../shared/INTEGRATION.md`](../shared/INTEGRATION.md)):

```kotlin
// services/<your-service>/build.gradle.kts
dependencies {
    // 1. The Initializr scaffold (above)
    // 2. The platform starter (one line — supplies RFC 7807, security,
    //    money, caching, messaging, observability, audit, i18n, etc.)
    implementation("com.trips-enjoy.platform:spring-boot-starter:4.1.0")

    // 3. Per-service domain deps (payment provider SDK, map SDK, etc.)
    // implementation("<per-service>")
}
```

This is the **one-line install** model. See
[`../shared/INTEGRATION.md` §1](../shared/INTEGRATION.md#1-the-one-line-install)
for the full pull-in set (the starter transitively includes
Hibernate 7, jOOQ 3.20, HikariCP, Spring Kafka 4, Lettuce,
Micrometer, OpenTelemetry, Apicurio, Flyway 11, all Kotlin
coroutine + reactor starters).

---

## 3. Service-by-service override table

The 14 Kotlin + Spring Boot 4 services use the **same recipe**
from §1 with **one service-specific override** per row.

| Service | Initializr URL slug | Per-service override |
|---|---|---|
| `admin-service` | `admin-service` | none (pure BFF + admin API) |
| `audit-service` | `audit-service` | none (consumer only; large `audit.events` partition by month) |
| `configuration-service` | `configuration-service` | none; add the long-poll streaming worker code per `TECH.md` (the Initializr scaffold gives you the REST surface; the SSE / long-poll worker is hand-written using `spring-webflux` for `Flux<ConfigurationEvent>`) |
| `courier-service` | `courier-service` | none |
| `customer-service` | `customer-service` | none |
| `driver-service` | `driver-service` | none; the absorbed `driver-service (vehicles)` sub-aggregate is in the same service, same schema |
| `food-order-service` | `food-order-service` | none |
| `identity-service` | `identity-service` | none; the Keycloak admin client is configured via `configuration-service`, not hard-coded |
| `ledger-service` | `ledger-service` | add `jOOQ` (`org.jooq:jooq`) for type-safe SQL in financial postings (the starter's optional `jOOQ` opt-in pulls this in; see `INTEGRATION.md` §3) |
| `notification-service` | `notification-service` | none |
| `payment-service` | `payment-service` | add the per-gateway SDK dependencies per `GATEWAYS.md` §3 (the 46 payment gateways are in the gateway registry; services depend on the per-gateway SDK only for the gateways they actually call) |
| `pricing-service` | `pricing-service` | use `webflux` for the reactive stack (Spring WebFlux coroutines + Spring Data R2DBC); the `web` starter is excluded |
| `restaurant-service` | `restaurant-service` | add `org.hibernate.orm:hibernate-spatial` (PostGIS) per `TECH.md` |
| `search-service` | `search-service` | add `org.springframework.boot:spring-boot-starter-data-opensearch` for the OpenSearch client (per `TECH.md`); the standard `web` + `data-jpa` are still present |
| `trip-service` | `trip-service` | none |

The recipe is **identical for all 14**; the override is the
single `dependencies { ... }` row that adds or replaces one
artifact (e.g. `pricing-service` swaps `spring-boot-starter-web`
for `spring-boot-starter-webflux`; `search-service` adds the
OpenSearch starter; `restaurant-service` adds Hibernate Spatial).

---

## 4. End-to-end workflow (per service)

1. **Scaffold the service** with the URL from §1.1 (browser
   or `curl`). Unzip the result into `services/<SERVICE>/` (the
   per-service directory).
2. **Add the platform starter** by editing `build.gradle.kts`
   (one line per `INTEGRATION.md` §1) and applying the
   per-service override from §3.
3. **Pin the BOM** to `platform-spring-boot-starter:4.1.0`
   (in `gradle.properties` or `settings.gradle.kts`). The
   starter's `platform-bom` resolves every transitive version
   to a single pinned value; services **must not** override
   Spring Boot / Spring Framework / Hibernate / Lettuce / Kafka
   versions directly.
4. **Generate the package skeleton** (the Initializr scaffold
   creates the `com.trips-enjoy.<service>/Application.kt` class
   with `@SpringBootApplication`; the starter's
   `@AutoConfiguration` imports wire the cross-cutting
   concerns).
5. **Create the per-service docs** (`README.md`, `BRD.md`,
   `SRS.md`, `ERD.md`, `INTEGRATION.md`, `WORKFLOWS.md`,
   `TECH.md`, `PLAN.md`) per the contract in
   [`../README.md` §"Per-Service Documentation Contract"](../README.md#per-service-documentation-contract).
6. **Generate the SKELETON.build.gradle.kts** for the
   service's package extractability contract (per
   [`../shared/RECOMMENDATIONS.md` §5](../services/RECOMMENDATIONS.md#5-extractability-skeleton-files)):
   ```
   cp docs/services/<other-kotlin-service>/SKELETON.gradle.kts \
      docs/services/<SERVICE>/SKELETON.gradle.kts
   ```
   Then update `group` / `version` / dependency coordinates as
   needed.
7. **Add the `Hard service-to-service dependencies` callout**
   to `PLAN.md` (Tier + Position) per
   [`../DEPLOYMENT_ORDER.md`](../DEPLOYMENT_ORDER.md).
8. **First commit** is the Initializr scaffold + the
   `build.gradle.kts` diff + the per-service docs + the
   `SKELETON.gradle.kts` + the `PLAN.md` callout. **No Java
   code** — the first commit is the scaffold + the docs, so
   that the service is reviewable before the implementation
   begins.

---

## 5. CI integration

The CI gate (per the platform's
`platform-ci.yml` template) runs the standard
**Initializr-coherence check** on every PR that touches a
service's `build.gradle.kts`:

- **Version pin** — `platform-spring-boot-starter` version
  is exactly `4.1.0` (or the version locked in
  `docs/shared/VERSIONING.md`); an `enforceSpringBootBom`
  Gradle rule rejects drift.
- **Dependency allowlist** — every direct dependency is
  matched against [`../shared/OSS_DEPENDENCIES.md`](../shared/OSS_DEPENDENCIES.md)
  § 6 (the platform's OSS bundle matrix). Unknown
  dependencies fail the PR with "not in OSS catalogue".
- **License compatibility** — every new dependency's SPDX
  ID is checked against the platform's license compatibility
  matrix in `OSS_DEPENDENCIES.md` § 11.
- **Reproducibility** — the lockfile (`gradle.lockfile`) is
  checked in; a `gradle --write-verification-metadata sha256`
  policy rejects unverified artifacts.

---

## 6. Anti-patterns (forbidden)

- **Manually writing a Spring Boot scaffold.** The
  Initializr-generated scaffold is the source of truth.
  Editing `build.gradle.kts` is allowed; editing the
  Initializr-generated Kotlin code in `Application.kt` to
  remove the `@SpringBootApplication` annotation is
  forbidden.
- **Bumping `platform-spring-boot-starter` outside a
  documented version-pinning event.** Per
  `docs/shared/VERSIONING.md` § 1, every version bump goes
  through the platform team's RFC + a 2-week deprecation
  window for the previous version.
- **Adding a dependency that is not in
  `docs/shared/OSS_DEPENDENCIES.md` § 6.** The CI gate
  rejects unknown deps; the platform's license compatibility
  matrix is the single source of truth.
- **Skipping the `SKELETON.gradle.kts` step.** The
  per-service SKELETON is the platform's extractability
  contract (per `RECOMMENDATIONS.md` § 5) — a service that
  is not extractable is not a service.
- **Adding business logic to the Initializr scaffold
  before the docs are in place.** The first commit is the
  scaffold + the docs; business logic starts in the second
  commit. This is a review-quality rule.

---

## 7. Related docs

- [`../shared/INTEGRATION.md`](../shared/INTEGRATION.md) —
  the one-line install (`com.trips-enjoy.platform:spring-boot-starter`).
- [`../shared/VERSIONING.md`](../shared/VERSIONING.md) —
  the version pin policy + the platform's BOM.
- [`../shared/CONVENTIONS.md`](../shared/CONVENTIONS.md) —
  the Gradle / Kotlin / Spring conventions; the SKELETON
  follows § 3.
- [`../shared/OSS_DEPENDENCIES.md`](../shared/OSS_DEPENDENCIES.md) —
  the SPDX + license compatibility matrix; every dependency
  listed in the Initializr URL above is in the catalogue.
- [`../shared/README.md`](../shared/README.md) —
  `platform-spring-boot-starter` overview.
- [`../services/RECOMMENDATIONS.md`](../services/RECOMMENDATIONS.md) —
  the per-service tech map; the § 5 "Extractability
  skeleton files" section.
- [`../README.md`](../README.md) —
  Per-Service Documentation Contract.
- [`../DEPLOYMENT_ORDER.md`](../DEPLOYMENT_ORDER.md) —
  the per-service deployment order (Tier + Position);
  the `Hard service-to-service dependencies` callout in
  `PLAN.md` references this doc.
- [https://start.spring.io/](https://start.spring.io/) —
  Spring Initializr (the upstream tool).
- [https://docs.spring.io/initializr/docs/current/reference/html/](https://docs.spring.io/initializr/docs/current/reference/html/) —
  Spring Initializr reference (the Spring Boot project).
