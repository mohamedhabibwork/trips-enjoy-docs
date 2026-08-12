# Versioning

The shared library follows **Semantic Versioning 2.0.0** with one
extra rule: every sub-module shares the same version number, and
versions are aligned with Spring Boot releases.

---

## 1. Version format

```
<spring-boot-major>.<spring-boot-minor>.<patch>
```

| Example | Spring Boot | Notes |
|---|---|---|
| `4.0.0` | 4.0 | First GA on Spring Boot 4 |
| `4.0.5` | 4.0 | Patch release |
| `4.1.0` | 4.1 | New features, no breaking changes |
| `5.0.0` | 5.0 | Major version (when Spring Boot 5 lands) |

The library does **not** use its own independent version number. The
major version of the library is the major version of Spring Boot it
targets. This makes upgrades trivially aligned across the platform.

---

## 2. SemVer rules

### Major (breaking)

A major version bump is reserved for changes that break
backwards-compat for at least one service. Examples:

- A default behaviour change that the override keys do not cover.
- Removal of a deprecated API.
- Spring Boot major version bump.
- A schema change to a published event (e.g. `audit.api.request.v1`
  fields removed).

**Migration support**: a major version ships with a `MIGRATION.md`
that lists every breaking change, the recommended migration path, and
a deprecation timeline for the previous major.

### Minor (additive)

A minor version bump adds new functionality without breaking
backwards-compat. Examples:

- A new auto-configuration behind a property (default off or default on,
  whichever is correct).
- A new helper method on an existing class.
- A new module (`platform-spring-boot-metrics-prometheus` is a hypothetical
  example).
- A new event field on an existing topic (consumers that ignore
  unknown fields are not affected).

### Patch (bugfix)

A patch version bump fixes a bug without changing any contract.
Examples:

- A null-safety bug in the correlation filter.
- A regression in the OTel resource attributes.
- A documentation fix.

---

## 3. Deprecation

Any breaking change is preceded by **at least one minor version**
where the new behaviour is available behind a property, and the old
behaviour is marked `@Deprecated` with a clear migration message.

Example timeline:
- `4.5.0` — new `platform.security.admin.min-role` property added;
  old hard-coded `platform.admin` is now `@Deprecated` with a message
  pointing to the new property.
- `4.5.x` — 1 minor cycle (1–3 months) for services to migrate.
- `4.6.0` — `@Deprecated` removed. Old behaviour now throws an
  `IllegalStateException` at startup if the new property is not set.

Deprecations are listed in `CHANGELOG.md` with the property name and
the version that will remove them.

---

## 4. Version catalog

All services share a single Gradle version catalog at
`gradle/libs.versions.toml`. The starter is pinned there:

```toml
[versions]
platformSpringBoot = "4.1.0"

[libraries]
platform-spring-boot-starter = { module = "com.trips-enjoy.platform:spring-boot-starter", version.ref = "platformSpringBoot" }
platform-spring-boot-test   = { module = "com.trips-enjoy.platform:platform-spring-boot-test", version.ref = "platformSpringBoot" }
```

A platform-wide version bump touches one file. Services consume the
new version on the next `gradle refresh-dependencies`.

---

## 5. Upgrade process

To upgrade from `4.0.x` to `4.1.0`:

1. Read `CHANGELOG.md` and `MIGRATION.md` (if any).
2. Bump the version in `gradle/libs.versions.toml`.
3. Run `./gradlew --write-locks` to update the lock file.
4. Run the service's full test suite.
5. If a deprecation warning fires, follow the migration hint.
6. Open a PR; CI runs the full integration test matrix.

For breaking changes (major bump), the platform team runs a "wave"
across services over a planned window, with a service-team owner per
service. The wave plan is published in `MIGRATION.md`.

---

## 6. Compatibility matrix

| Library | Spring Boot | JDK | Kotlin | Hibernate | Notes |
|---|---|---|---|---|---|
| `4.0.x` | 4.0.x | 21+ | 2.1.x | 7.0.x | GA; pinned to Spring Boot 4.0 |
| `4.1.x` | 4.1.x | 21+ | 2.2.x | 7.0.x | adds R2DBC improvements, new admin helpers |
| `5.0.x` | 5.0.x | 25+ | 3.0.x | 8.0.x | future |

The platform team maintains the matrix and announces new lines in
`CHANGELOG.md`.

---

## 7. CVE policy

- **Critical CVEs** in any direct dependency → patch release within
  24 hours.
- **High CVEs** in any direct dependency → patch release within 7 days.
- **Medium CVEs** in any direct dependency → patch release in the
  next regular sprint.
- **Low CVEs** in any direct dependency → back-log, fixed at the
  team's discretion.

The library is scanned nightly with `dependency-check` (OWASP) and
Trivy. CVEs are reported in `#platform-security`.

## Related docs

- [`../shared/PLATFORM_BASELINE.md`](../shared/PLATFORM_BASELINE.md) — single source for PostgreSQL 19, Kafka, Keycloak, etc.
- [`../shared/CONVENTIONS.md`](../shared/CONVENTIONS.md) — code conventions and naming
- [`../architecture/SYSTEM_OVERVIEW.md`](../architecture/SYSTEM_OVERVIEW.md) — plain-English platform summary
