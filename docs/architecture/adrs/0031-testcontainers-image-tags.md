# ADR-0031: Testcontainers image tags (canonical pinned versions)

- Status: Accepted
- Date: 2026-08-15
- Authors: Platform Architecture Team
- Deciders: Architecture Review Board
- Tags: testing, testcontainers, observability, ci

> **Catalog revision (2026-08-15, appended per append-not-renumber):**
> this ADR locks the platform-wide canonical Testcontainers image
> tags. Every service's `TestcontainersConfiguration` MUST use
> the canonical pinned versions declared by
> `platform-spring-boot-test`; the 12 services that ship a local
> Testcontainers triple adopt the platform helper.

## Context and Problem Statement

The `platform-spring-boot-test` module declares the canonical
Testcontainers image tags:

- **Kafka:** `confluentinc/cp-kafka:7.5.0`
- **PostgreSQL:** `postgres:18.0-alpine`
- **Redis:** `redis:7.2-alpine`
- **Keycloak:** `quay.io/keycloak/keycloak:24.0`

But 12 of 14 Kotlin services ship a local
`TestcontainersConfiguration.kt` with one of several variants:

| Image | App-local pin | Platform pin |
|---|---|---|
| Kafka | `apache/kafka-native:latest` (11 services), `confluentinc/cp-kafka:7.5.0` (1 service) | `confluentinc/cp-kafka:7.5.0` |
| PostgreSQL | `postgres:latest` (10 services), `postgres:16.2-alpine` (2 services) | `postgres:18.0-alpine` |
| Redis | `redis:latest` (10 services), `redis:7-alpine` (2 services) | `redis:7.2-alpine` |
| Keycloak | `quay.io/keycloak/keycloak:24.0` (1 service, identity) | `quay.io/keycloak/keycloak:24.0` ✓ |

The audit at [`shared/PLATFORM_DRY_AUDIT.md` §6 ADR-0027](../../shared/PLATFORM_DRY_AUDIT.md)
flagged this drift. The contract is load-bearing:

- CI runs use the locally-pulled image; if 11 services pull
  `apache/kafka-native:latest` and 1 pulls
  `confluentinc/cp-kafka:7.5.0`, the CI cache footprint is
  larger than necessary.
- The Kafka client protocol differs slightly between
  `apache/kafka-native` (KRaft mode) and `confluentinc/cp-kafka`
  (ZooKeeper mode); a service that tests against one may
  produce or fail against the other in production.
- Floating tags (`latest`) are not reproducible: a service
  test that passes today may fail tomorrow when the upstream
  image publishes a breaking change.

## Decision Drivers

- **Reproducibility.** Pinned versions (`7.5.0`, `18.0-alpine`)
  ensure CI runs use the same image byte-for-byte.
- **Production parity.** The `confluentinc/cp-kafka:7.5.0`
  image matches the production Kafka broker (per
  [`shared/PLATFORM_BASELINE.md`](../shared/PLATFORM_BASELINE.md));
  `apache/kafka-native` is a different broker.
- **CI cache footprint.** Pinned versions enable layer caching;
  `latest` forces re-pulling on every CI run.

## Considered Options

1. **`confluentinc/cp-kafka:7.5.0`** + `postgres:18.0-alpine` +
   `redis:7.2-alpine` (platform canonical; matches production)
2. **`apache/kafka-native:latest`** + `postgres:latest` +
   `redis:latest` (current 11-of-12 default; not reproducible)
3. **Per-service pinning** (rejected — defeats the purpose of a
   shared library)

## Decision Outcome

**Chosen option: option 1, canonical pinned versions.**

- **Kafka:** `confluentinc/cp-kafka:7.5.0` (production parity)
- **PostgreSQL:** `postgres:18.0-alpine` (production parity)
- **Redis:** `redis:7.2-alpine` (production parity)
- **Keycloak:** `quay.io/keycloak/keycloak:24.0` (production
  parity; only `identity-service` uses it)

The 12 redundant `TestcontainersConfiguration.kt` files are
deleted; each service's test base class
(`BaseIntegrationTest`) extends the platform
`PlatformTestcontainers` annotation.

### Consequences

**Good:**
- Single canonical pinned versions across 12 services
- CI runs are reproducible byte-for-byte
- Kafka client protocol matches production
- CI cache footprint reduced (single image per type instead of
  N variants)
- 12 redundant `TestcontainersConfiguration.kt` files deleted
  (~360 LOC)

**Bad:**
- 11 services must change `apache/kafka-native:latest` →
  `confluentinc/cp-kafka:7.5.0`. The change is a single-line
  configuration tweak; CI must re-pull the new image once.
- 10 services must change `postgres:latest` →
  `postgres:18.0-alpine`. Same — single-line change.
- 10 services must change `redis:latest` → `redis:7.2-alpine`.
  Same.

### Follow-up

- [ ] Update 11 services'
  `TestcontainersConfiguration.kt` to use
  `confluentinc/cp-kafka:7.5.0`.
- [ ] Update 10 services' `TestcontainersConfiguration.kt` to use
  `postgres:18.0-alpine`.
- [ ] Update 10 services' `TestcontainersConfiguration.kt` to use
  `redis:7.2-alpine`.
- [ ] Add `PlatformTestcontainers` annotation to
  `platform-spring-boot-test` so test classes auto-import the
  canonical image tags (Phase A).

## Pros and Cons of the Options

### Pinned canonical versions (chosen)

Reproducible, production-parity, cache-friendly.

### Floating `latest` tags

Current default. Not reproducible; CI may flake on upstream
breaking changes; rejected.

## References

- [`shared/PLATFORM_BASELINE.md`](../shared/PLATFORM_BASELINE.md)
  — production broker/DB/cache versions
- [`shared/PLATFORM_DRY_AUDIT.md` §6 ADR-0027](../../shared/PLATFORM_DRY_AUDIT.md)
  — the audit that flagged this drift
- [`shared/TESTING.md`](../shared/TESTING.md) — the canonical
  Testcontainers contract
