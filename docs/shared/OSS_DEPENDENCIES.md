# Open-source Dependencies & License Attribution

> **Single source of truth** for the platform's open-source footprint.
> Every service in [`docs/services/`](../services/README.md) inherits from
> this catalogue via its `TECH.md` 11 *Open-source bundle*, and per-service
> extractability is documented by the [`SKELETON.<ext>`](../services/README.md)
> file alongside each service.
>
> **Sibling docs** in this directory: [`README.md`](./README.md) ·
> [`PLATFORM_BASELINE.md`](./PLATFORM_BASELINE.md) ·
> [`MODULES.md`](./MODULES.md) · [`CONVENTIONS.md`](./CONVENTIONS.md) ·
> [`AUTO_CONFIG.md`](./AUTO_CONFIG.md) · [`INTEGRATION.md`](./INTEGRATION.md) ·
> [`TESTING.md`](./TESTING.md) · [`VERSIONING.md`](./VERSIONING.md) ·
> [`ROADMAP.md`](./ROADMAP.md).

---

## 1. Purpose

This document is the platform's **open-source bill of materials**:

- It enumerates every OSS project the platform depends on (infrastructure
  + libraries), pinned to the version listed in
  [`../services/RECOMMENDATIONS.md`](../services/RECOMMENDATIONS.md) 5.
- It annotates each dependency with its **SPDX license identifier** and
  a link to the upstream license text.
- It tells every service which dependencies are **platform-required**
  (cannot be removed without breaking platform integration) and which
  are **swappable** (e.g. PostgreSQL ↔ H2, Keycloak ↔ stub JWT, Redis
  ↔ Caffeine).
- It describes the **NOTICE / THIRD-PARTY-LICENSES** practice that
  every service bundle must follow when shipped.

The per-service `TECH.md` 11 *Open-source bundle* references this
catalogue and adds only the **service-specific** items (the
service-specific external vendor SDK and the 2–4 most important
runtime OSS libraries the service actually exercises). The version
pin for every library is **never** duplicated in this file — it is
in [`RECOMMENDATIONS.md` 5](../services/RECOMMENDATIONS.md#5-cross-cutting-tooling-language-agnostic).

To bump a version, open a PR against `RECOMMENDATIONS.md` 5. This
catalogue is regenerated from there.

---

## 2. Platform-wide OSS projects (infrastructure)

Every service in the platform transitively depends on these projects
at runtime. They are deployed as part of the platform, not bundled
into any service binary.

| Project | Version | License (SPDX) | Where used | License URL |
|---|---|---|---|---|
| Kubernetes | multi-region active-active (managed) | Apache-2.0 | The cluster every service runs in | https://github.com/kubernetes/kubernetes/blob/master/LICENSE |
| Helm | 3.x (chart packaging) | Apache-2.0 | Service deployment charts | https://github.com/helm/helm/blob/main/LICENSE |
| Istio (service mesh) | ambient mode | Apache-2.0 | mTLS between every pair of service pods | https://github.com/istio/istio/blob/master/LICENSE |
| Envoy | per Istio ambient | Apache-2.0 | Ingress + L7 gateway for the platform edge | https://github.com/envoyproxy/envoy/blob/main/LICENSE |
| PostgreSQL | **18** | PostgreSQL (BSD-style) | Per-service database; persistent state for all 20 services | https://www.postgresql.org/about/licence/ |
| PostGIS | **3.5** (bundled with PostgreSQL 19) | GPL-2.0 | Geospatial types for `geolocation-service`, ``geolocation-service` (zones)`, ``driver-service` (dispatch)` | https://github.com/postgis/postgis/blob/master/LICENSE.TXT |
| Apache Kafka | **3.9** (KRaft mode, no ZooKeeper) | Apache-2.0 | Async event bus for every service that publishes or consumes | https://github.com/apache/kafka/blob/trunk/LICENSE |
| Confluent Schema Registry | per Kafka 3.9 | Apache-2.0 (Community) | Avro schema lifecycle for ``reporting-service` (data lake)`, `audit-service` | https://github.com/confluentinc/schema-registry/blob/master/LICENSE.txt |
| Apicurio Registry | 2.x | Apache-2.0 | Avro schema lifecycle (alternative / additional registry) | https://github.com/Apicurio/apicurio-registry/blob/master/LICENSE |
| Redis | **7.x** (core engine) | BSD-3-Clause | Cache for every service + GEO index for ``driver-service` (location)`, ``courier-service` (tracking)` | https://github.com/redis/redis/blob/7.4/LICENSE.txt |
| Keycloak | current LTS | Apache-2.0 | OIDC / SAML identity provider for every authenticated service | https://github.com/keycloak/keycloak/blob/main/LICENSE.txt |
| OpenSearch | 2.x | Apache-2.0 | Search engine for `search-service`; index for restaurants, menu items, support tickets | https://github.com/opensearch-project/OpenSearch/blob/main/LICENSE.txt |
| OpenTelemetry SDK | **1.40+** | Apache-2.0 | Tracing / metrics / logs SDK used by every service | https://github.com/open-telemetry/opentelemetry-go/blob/main/LICENSE |
| OpenTelemetry Collector | 0.110+ | Apache-2.0 | Vendor-neutral collector in the observability tier | https://github.com/open-telemetry/opentelemetry-collector/blob/main/LICENSE |
| Prometheus | 2.x | Apache-2.0 | Metrics backend; scraped by every service | https://github.com/prometheus/prometheus/blob/main/LICENSE |
| Grafana | 11.x | AGPL-3.0-only | Dashboards (UI tier only; not linked into any service binary) | https://github.com/grafana/grafana/blob/main/LICENSE |
| Loki | 3.x | AGPL-3.0-only | Log aggregation (UI tier only; not linked into any service binary) | https://github.com/grafana/loki/blob/main/LICENSE |
| Tempo | 2.x | AGPL-3.0-only | Distributed tracing backend (UI tier only; not linked into any service binary) | https://github.com/grafana/tempo/blob/main/LICENSE |
| Jaeger | 1.x (legacy fallback) | Apache-2.0 | Distributed tracing backend (compatibility) | https://github.com/jaegertracing/jaeger/blob/main/LICENSE |
| Fluent Bit | 3.x | Apache-2.0 | Log shipper; node-level agent | https://github.com/fluent/fluent-bit/blob/master/LICENSE |
| HashiCorp Vault | latest (community) | BUSL-1.1 (source-available) | Secret manager for every service's secret references | https://github.com/hashicorp/vault/blob/main/LICENSE |
| ClamAV | 1.x | GPL-2.0 | Malware scan in `file-service` upload pipeline | https://github.com/Cisco-Talos/clamav/blob/main/LICENSE |
| `cosign` (Sigstore) | 2.x | Apache-2.0 | Container image signing in CI | https://github.com/sigstore/cosign/blob/main/LICENSE |
| Container base: Go | `gcr.io/distroless/static-debian12:nonroot` | Apache-2.0 (distroless) + Debian components | All 8 Go services | https://github.com/GoogleContainerTools/distroless/blob/main/LICENSE |
| Container base: Kotlin | `eclipse-temurin:25-jre-jammy` | GPL-2.0-with-classpath-exception | All 46 Kotlin / Spring Boot 4 services | https://github.com/adoptium/temurin-build/blob/master/LICENSE |
| Container base: Python | `python:3.14-slim` | PSF-2.0 + Debian components | All 4 Python services | https://github.com/python/cpython/blob/main/LICENSE |

> **Notes on the license classes:**
> - **AGPL-3.0-only** (Grafana, Loki, Tempo) — the platform uses these
>   as **observability backends only**, not as libraries linked into
>   any service binary. The AGPL "network use is distribution"
>   clause applies to anyone who accesses the platform's dashboards
>   from the public internet. This is acceptable for an internal SaaS
>   offering. **It would change** if the platform were distributed as
>   an on-prem appliance — at that point Grafana/Loki would need to
>   be swapped for a non-AGPL alternative (e.g. Splunk, Elastic
>   under a custom license, or a self-hosted fork).
> - **BUSL-1.1** (HashiCorp Vault) — source-available, not OSI-approved
>   open source. Acceptable for the platform's internal use; the
>   Vault server is not linked into any service binary (services
>   talk to it via the standard Vault REST API).
> - **GPL-2.0** (PostGIS, ClamAV) — both are loaded as a PostgreSQL
>   extension / a sidecar process respectively; neither is statically
>   linked into any service binary. The GPL's "if you distribute a
>   derivative work" clause applies to PostgreSQL-with-PostGIS builds
>   (which is governed by the PostgreSQL license, not GPL).

---

## 3. Kotlin / Spring Boot OSS dependencies

The 46 Kotlin / Spring Boot 4 services in this platform depend on
the following **shared library** plus a small set of per-service
additions. The full version table is in
[`../services/RECOMMENDATIONS.md` 5.1](../services/RECOMMENDATIONS.md#51-version-baseline-pinned-latest-stable);
this catalogue adds the SPDX license attribution.

### 3.1 Shared library (the only dependency every Kotlin service pulls in)

| Library | Maven coordinate | License (SPDX) | Purpose |
|---|---|---|---|
| `platform-spring-boot-starter` | `com.trips-enjoy.platform:spring-boot-starter` | Apache-2.0 (this platform's own library) | Cross-cutting web, security, data, money, caching, messaging, observability, audit, error, api-docs, test |

Source: [`packages/platform-spring-boot/`](../README.md); see
[`README.md`](./README.md) for the module list.

### 3.2 Pinned OSS libraries (Kotlin / Spring Boot 4 line)

| Library | Pinned version | License (SPDX) | Why we use it |
|---|---|---|---|
| JDK | **25 (LTS)** | GPL-2.0-with-classpath-exception | Runtime; virtual threads + structured concurrency |
| Kotlin | **2.2.x** | Apache-2.0 | Source language |
| Spring Boot | **4.x (latest 4.1.x)** | Apache-2.0 | Application framework |
| Spring Framework | **7.x** | Apache-2.0 | Pulled in by Spring Boot 4 |
| Spring Security | **7.x** | Apache-2.0 | AuthN / AuthZ (Keycloak resource server) |
| Spring Kafka | **4.x** | Apache-2.0 | Kafka producer / consumer; transactional outbox |
| Spring Statemachine | **5.x** | Apache-2.0 | State machines in ``food-order-service` (checkout)`, ``courier-service` (delivery)`, `food-order-service`, ``payment-service` (food saga)`, ``pricing-service` (loyalty rules) / `customer-service` (loyalty account)`, `payment-service`, ``food-order-service` (queue)`, ``payment-service` (ride saga)`, ``trip-service` (ride-request)`, ``payment-service` (wallet slice)` |
| Spring Data OpenSearch | **6.x** | Apache-2.0 | `search-service` only |
| Hibernate ORM | **7.x** | LGPL-2.1-only | JPA provider (default in Spring Boot 4) |
| Hibernate Spatial | **7.x** | LGPL-2.1-only | ``geolocation-service` (zones)` PostGIS integration |
| `jOOQ` | **3.20.x** (or 4.x) | Apache-2.0 | Type-safe SQL for the 7 financial services (`payment-service`, ``payment-service` (wallet slice)`, `ledger-service`, ``food-order-service` (checkout)`, ``payment-service` (food saga)`, ``payment-service` (ride saga)`, ``payment-service` (merchant-settlement slice)`) |
| Exposed | **1.0.x** (or 0.5x) | Apache-2.0 | Lightweight DSL alternative to JPA |
| Flyway | **11.x** | Apache-2.0 (Community Edition) | Versioned migrations targeting PostgreSQL 19 |
| Testcontainers | **1.21.x** | MIT | JUnit 5 integration for ephemeral PostgreSQL 19 / Kafka / Redis / Keycloak |
| JUnit 5 | **5.11.x** | EPL-2.0 | Test runner (Jupiter + Vintage) |
| MockK | **1.13.x** | Apache-2.0 | Kotlin-first mocking |
| Gradle | **9.x** | Apache-2.0 | Build tool (Kotlin DSL) |
| `ktlint` | **1.5.x** | Apache-2.0 | Kotlin formatter |
| `detekt` | **1.23.x** | Apache-2.0 | Kotlin static analysis |
| MapStruct | **1.6.x** | Apache-2.0 | DTO ↔ entity mapping |
| Spring Boot Actuator | **4.x** | Apache-2.0 | Health, metrics, info endpoints |
| Micrometer | **1.14.x** | Apache-2.0 | Metrics facade |
| Lettuce | **6.x** | Apache-2.0 | Redis client (default in Spring Boot 4) |
| Spring Cache | **4.x** | Apache-2.0 | Cache abstraction (Caffeine + Redis backends) |
| Spring Validation | **4.x** | Apache-2.0 | Bean Validation (Jakarta) |
| Caffeine | **3.x** | Apache-2.0 | In-process cache for Spring Cache |
| Resilience4j | **2.x** | Apache-2.0 | Circuit breaker, retry, bulkhead, rate limiter |
| AWS SDK v2 (S3) | **2.x** | Apache-2.0 | ``reporting-service` (data lake)` (Parquet landing), `notification-service` (attachments), `file-service` (S3-compatible backend) |
| Snowflake JDBC | **3.x** | Apache-2.0 (Snowflake provided) | ``reporting-service` (data lake)` warehouse export |
| Apache Avro | **1.12.x** | Apache-2.0 | Event schema for ``reporting-service` (data lake)`, `audit-service` |
| OpenTelemetry Java agent / SDK | **1.40+** | Apache-2.0 | Tracing / metrics |
| Logback | **1.5.x** | EPL-1.0 + LGPL-2.1 (dual) | Default logger in Spring Boot 4 |
| SLF4J | **2.x** | MIT | Logging façade |
| Jackson | **2.18.x** | Apache-2.0 | JSON serialization |
| Logstash Logback Encoder | **8.x** | Apache-2.0 | Structured JSON to stdout |
| Testcontainers Postgres module | **1.21.x** | MIT | Testcontainers PostgreSQL module |
| Testcontainers Kafka module | **1.21.x** | MIT | Testcontainers Kafka module |
| Apicurio Avro client | **2.x** | Apache-2.0 | Avro schema runtime for ``reporting-service` (data lake)` |

> **Hibernate Spatial** is part of the Hibernate ORM project; the
> license attribution for the spatial extension is the same as
> Hibernate ORM (LGPL-2.1-only). The spatial code is dynamically
> dispatched at runtime; no service statically links it.
>
> **JUnit 5 (EPL-2.0)** is the runtime engine; its `junit-platform-launcher`
> uses SPI rather than code linking, so EPL-2.0 distribution
> obligations are not triggered by use from service binaries.
>
> **Logback** carries a dual EPL-1.0 / LGPL-2.1 license. Spring Boot's
> distribution already ships Logback; the platform inherits it.

---

## 4. Go OSS dependencies

The 8 Go services in this platform use a deliberately minimal stack
(`net/http` + `chi` + a handful of well-scoped libraries). The full
version table is in
[`RECOMMENDATIONS.md` 5.1](../services/RECOMMENDATIONS.md#51-version-baseline-pinned-latest-stable);
this section adds the SPDX attribution.

| Library | Pinned version | License (SPDX) | Why we use it | Import path |
|---|---|---|---|---|
| Go toolchain | **1.25.x** | BSD-3-Clause | Source language + stdlib | `go` |
| `go-chi/chi` | v2 (5.x) | MIT | HTTP router on top of `net/http` | `github.com/go-chi/chi/v5` |
| `pgx` | v5 | MIT | PostgreSQL 19 driver (preferred over `database/sql` + `lib/pq`) | `github.com/jackc/pgx/v5` |
| `segmentio/kafka-go` | latest | MIT | Kafka producer / consumer for hot-path services | `github.com/segmentio/kafka-go` |
| `go-redis/redis` | v9 | BSD-2-Clause | Redis 8+ client | `github.com/redis/go-redis/v9` |
| `coreos/go-oidc` | v3 | Apache-2.0 | Keycloak OIDC verification (used by `api-gateway`) | `github.com/coreos/go-oidc/v3` |
| `prometheus/client_golang` | v1.20+ | Apache-2.0 | Prometheus metrics | `github.com/prometheus/client_golang` |
| `golang-migrate` | v4 | MIT | SQL migrations (mirrors Flyway semantics) | `github.com/golang-migrate/migrate/v4` |
| `golangci-lint` | v1.62+ | GPL-3.0-only (aggregator) | Meta-linter; invoked as an executable in CI, not linked into service binaries | `github.com/golangci/golangci-lint` |
| `coder/websocket` | latest | ISC | WebSocket for ``courier-service` (tracking)` | `github.com/coder/websocket` |
| `aws-sdk-go-v2` | latest | Apache-2.0 | S3 in `file-service` | `github.com/aws/aws-sdk-go-v2` |
| `resty` | latest | MIT | HTTP client for ``geolocation-service` (ETA/routing)`, `geolocation-service` | `github.com/go-resty/resty/v2` |
| `coreos/go-oidc` (deps) | v3 | Apache-2.0 | JWT verification | `github.com/coreos/go-oidc/v3` |
| `golang.org/x/oauth2` | latest | BSD-3-Clause | OAuth2 client (used by `coreos/go-oidc`) | `golang.org/x/oauth2` |
| `golang.org/x/crypto` | latest | BSD-3-Clause | TLS / hashing | `golang.org/x/crypto` |
| `golang.org/x/sys` | latest | BSD-3-Clause | OS syscall bindings | `golang.org/x/sys` |
| `golang.org/x/net` | latest | BSD-3-Clause | Linux netlink / DNS | `golang.org/x/net` |
| `google/uuid` | latest | BSD-3-Clause | UUIDv7 generation (services migrating from UUIDv4) | `github.com/google/uuid` |

> **`golangci-lint`** is invoked as an executable in CI; it is **not**
> linked into any service binary. The GPL-3.0 license of the
> aggregator is irrelevant to the license of the services whose code
> it lints.

---

## 5. Python OSS dependencies

The 4 Python services (`fraud-risk-service`, ``courier-service` (dispatch)`,
``driver-service` (incentives)`, `reporting-service`) use FastAPI + Pydantic
as the core, plus a small set of ML / data libraries. The full
version table is in
[`RECOMMENDATIONS.md` 5.1](../services/RECOMMENDATIONS.md#51-version-baseline-pinned-latest-stable);
this section adds the SPDX attribution.

| Library | Pinned version | License (SPDX) | Why we use it | Used by |
|---|---|---|---|---|
| Python | **3.14** | PSF-2.0 | Runtime | All 4 |
| FastAPI | **0.115+** (or 1.0) | MIT | Async REST framework | All 4 |
| Pydantic | **2.x** | MIT | Validation + serialization | All 4 |
| SQLAlchemy | **2.0.x** | MIT | Async ORM for read-side services | `reporting-service` |
| Alembic | **1.13+** | MIT | SQLAlchemy migrations | `reporting-service` |
| `confluent-kafka-python` | **2.6+** | Apache-2.0 | librdkafka-backed Kafka client | All 4 |
| `aiokafka` | **0.12+** | Apache-2.0 | Pure-async Kafka consumer (matches FastAPI event loops) | `fraud-risk-service`, ``courier-service` (dispatch)`, ``driver-service` (incentives)` |
| `authlib` | latest | BSD-3-Clause | Keycloak OAuth2 / OIDC client | All 4 |
| `scikit-learn` | **1.6+** | BSD-3-Clause | Risk scoring, incentive curves | `fraud-risk-service`, ``courier-service` (dispatch)`, ``driver-service` (incentives)` |
| `pandas` | **2.2+** (or 3.0) | BSD-3-Clause | Reporting rollups, BI exports | `reporting-service`, ``driver-service` (incentives)` |
| `numpy` | **2.x** | BSD-3-Clause | Vectorised math | `fraud-risk-service`, ``courier-service` (dispatch)`, ``driver-service` (incentives)`, `reporting-service` |
| `pytest` | **8.x** | MIT | Test runner | All 4 |
| `pytest-asyncio` | latest | Apache-2.0 | Async test support | All 4 |
| `ruff` | **0.7+** | MIT | Linter + formatter (replaces black + flake8 + isort) | All 4 |
| `mypy` | **1.13+** | MIT | Strict static type-check | All 4 |
| `uv` | latest | Apache-2.0 or MIT | Python package manager (the build tool) | All 4 |
| `httpx` | latest | BSD-3-Clause | Async HTTP client | All 4 |
| `starlette-prometheus` | latest | MIT | FastAPI metrics middleware | All 4 |
| `mlflow` | latest | Apache-2.0 | Model registry | `fraud-risk-service` |
| `xgboost` | latest | Apache-2.0 | Gradient boosting (optional inner loop) | `fraud-risk-service` |
| `asyncpg` | latest | Apache-2.0 | PostgreSQL 19 async driver | `fraud-risk-service`, ``courier-service` (dispatch)` |

---

## 6. Optional Rust inner loop

Per [`RECOMMENDATIONS.md` 5.1](../services/RECOMMENDATIONS.md#51-version-baseline-pinned-latest-stable),
Rust is **not** a default. It is reserved for a single hot inner loop
(e.g. the `fraud-risk-service` scoring kernel) only if profiling
shows the JVM/Python implementation is the bottleneck. When adopted,
the libraries are:

| Library | Pinned version | License (SPDX) | Why we use it |
|---|---|---|---|
| Rust toolchain | **1.83+** (stable) | MIT (compiler) / Apache-2.0 (std) | Compiler |
| `axum` | **0.7+** | MIT | Tiny standalone microservice for the scoring kernel |
| `pyo3` | **0.22+** | Apache-2.0 OR MIT | Python extension for in-process Rust kernels |

> **No Rust service is in production today.** The stack is listed
> here for completeness only.

---

## 7. Per-service OSS bundle index

This is the canonical mapping the per-service `TECH.md` 11 *Open-source
bundle* references. The 20 services are grouped by their language
profile (per [`RECOMMENDATIONS.md` 3](../services/RECOMMENDATIONS.md#3-per-cluster-rationale)).

### 7.1 Kotlin / Spring Boot 4 — business core (44 services)

Every service in this group pulls in the libraries listed in
3.1 (the shared `platform-spring-boot-starter`) and 3.2 (the
Kotlin pinned set). The "External" column lists the **service-specific
external vendor SDK** the service depends on (if any). The "Platform
required" column lists the platform-internal services the service
talks to at runtime.

| Service | Profile | External vendor SDK | Platform-required runtime services | Skeleton |
|---|---|---|---|---|
| ``customer-service` (addresses)` | business core | — | `identity-service` | `SKELETON.gradle.kts` |
| `admin-service` | business core | aggregates internal services | every service it aggregates | `SKELETON.gradle.kts` |
| `audit-service` | streaming | S3 (cold archive) | ``reporting-service` (data lake)` (downstream) | `SKELETON.gradle.kts` |
| ``restaurant-service` (branch)` | business core | — | `identity-service` | `SKELETON.gradle.kts` |
| ``food-order-service` (cart)` | business core | — | `pricing-service`, ``restaurant-service` (menu)` | `SKELETON.gradle.kts` |
| ``food-order-service` (checkout)` | financial | payment · pricing · ledger · tax | `pricing-service`, `payment-service`, ``pricing-service` (tax slice)` | `SKELETON.gradle.kts` |
| `configuration-service` | business core | — | (no upstream) | `SKELETON.gradle.kts` |
| ``payment-service` (courier-earnings slice)` | business core | ledger (read) | `ledger-service` | `SKELETON.gradle.kts` |
| `courier-service` | business core | identity (Keycloak) | `identity-service` | `SKELETON.gradle.kts` |
| `customer-service` | business core | identity | `identity-service` | `SKELETON.gradle.kts` |
| ``courier-service` (delivery)` | business core | — | ``courier-service` (dispatch)` | `SKELETON.gradle.kts` |
| ``payment-service` (driver-earnings slice)` | business core | ledger (read) | `ledger-service`, `trip-service` (events) | `SKELETON.gradle.kts` |
| `driver-service` | business core | identity | `identity-service` | `SKELETON.gradle.kts` |
| ``configuration-service` (flags)` | business core | — | (no upstream) | `SKELETON.gradle.kts` |
| `food-order-service` | business core | — | `restaurant-service`, ``restaurant-service` (menu)` | `SKELETON.gradle.kts` |
| ``payment-service` (food saga)` | financial | payment · ledger | `payment-service`, `ledger-service` | `SKELETON.gradle.kts` |
| `identity-service` | business core | Keycloak | (no upstream) | `SKELETON.gradle.kts` |
| ``restaurant-service` (inventory)` | business core | menu | ``restaurant-service` (menu)` | `SKELETON.gradle.kts` |
| ``pricing-service` (loyalty rules) / `customer-service` (loyalty account)` | business core | — | `customer-service` | `SKELETON.gradle.kts` |
| ``restaurant-service` (menu)` | business core | file (photos) | `file-service` | `SKELETON.gradle.kts` |
| ``restaurant-service` (merchant)` | business core | identity | `identity-service` | `SKELETON.gradle.kts` |
| `notification-service` | business core | communication-gateway | ``notification-service` (provider ACL)` | `SKELETON.gradle.kts` |
| ``pricing-service` (promotion slice)` | business core | — | (no upstream) | `SKELETON.gradle.kts` |
| ``food-order-service` (queue)` | business core | — | `food-order-service` | `SKELETON.gradle.kts` |
| `restaurant-service` | business core | — | ``restaurant-service` (merchant)` | `SKELETON.gradle.kts` |
| ``payment-service` (merchant-settlement slice)` | financial | ledger (read) | `ledger-service` | `SKELETON.gradle.kts` |
| ``restaurant-service` (staff)` | business core | identity | `identity-service` | `SKELETON.gradle.kts` |
| ``trip-service` / `food-order-service` / `search-service` (review projections)` | business core | — | `trip-service`, `food-order-service` (events) | `SKELETON.gradle.kts` |
| ``trip-service` (history)` | business core | — | `trip-service` (events) | `SKELETON.gradle.kts` |
| ``trip-service` (ride-request)` | business core | pricing · dispatch | `pricing-service`, ``driver-service` (dispatch)` | `SKELETON.gradle.kts` |
| ``trip-service` (safety)` | business core | communication-gateway · file | ``notification-service` (provider ACL)`, `file-service` | `SKELETON.gradle.kts` |
| ``trip-service` (scheduled)` | business core | — | ``trip-service` (ride-request)` | `SKELETON.gradle.kts` |
| ``admin-service` (support module)` | business core | file · communication-gateway | `file-service`, ``notification-service` (provider ACL)` | `SKELETON.gradle.kts` |
| `trip-service` | business core | — | ``trip-service` (ride-request)` | `SKELETON.gradle.kts` |
| ``customer-service` (cross-persona profile)` | business core | identity · file | `identity-service`, `file-service` | `SKELETON.gradle.kts` |
| ``driver-service` (vehicles)` | business core | file | `file-service` | `SKELETON.gradle.kts` |

### 7.2 Kotlin / Spring Boot 4 — financial / correctness (additional financial services)

These services sit on the same stack as 7.1 but additionally use
`jOOQ` 3.20 (for type-safe SQL on financial aggregates) and
`BigDecimal` discipline. See 3.2 for the `jOOQ` license.

| Service | Profile | External vendor SDK | Platform-required runtime services | Skeleton |
|---|---|---|---|---|
| `payment-service` | financial | payment provider (Stripe / Adyen / Hyperpay) | `payment-service`'s own provider | `SKELETON.gradle.kts` |
| ``payment-service` (wallet slice)` | financial | ledger (read) | `ledger-service` | `SKELETON.gradle.kts` |
| `ledger-service` | financial | — | (no upstream) | `SKELETON.gradle.kts` |
| ``payment-service` (ride saga)` | financial | payment · wallet · ledger | `payment-service`, ``payment-service` (wallet slice)`, `ledger-service` | `SKELETON.gradle.kts` |

### 7.3 Kotlin / Spring Boot 4 — streaming / event ingest (2 services)

``reporting-service` (data lake)` and `audit-service` add `Spring Kafka 4`,
`Apicurio Avro`, and (for analytics) `AWS SDK v2` and `Snowflake JDBC`.
See 3.2 for the license entries.

| Service | Profile | External vendor SDK | Platform-required runtime services | Skeleton |
|---|---|---|---|---|
| ``reporting-service` (data lake)` | streaming | S3 · Snowflake / BigQuery / Redshift | ``reporting-service` (data lake)`'s own warehouse | `SKELETON.gradle.kts` |
| `audit-service` | streaming | S3 (cold archive) | (no upstream) | `SKELETON.gradle.kts` |

### 7.4 Kotlin / Spring Boot 4 — search & coordination (1 service)

| Service | Profile | External vendor SDK | Platform-required runtime services | Skeleton |
|---|---|---|---|---|
| `search-service` | business core | OpenSearch (in-cluster) | (no upstream) | `SKELETON.gradle.kts` |

### 7.5 Go — edge / hot path (8 services)

Every service in this group pulls in the libraries listed in 4.
"External" lists the **service-specific external vendor SDK** (if any).

| Service | Profile | External vendor SDK | Platform-required runtime services | Skeleton |
|---|---|---|---|---|
| `api-gateway` | edge / hot path | Keycloak JWKS | every downstream service | `SKELETON.go.mod` |
| ``driver-service` (location)` | edge / hot path | — | `driver-service` | `SKELETON.go.mod` |
| ``courier-service` (tracking)` | edge / hot path | — | ``courier-service` (delivery)` | `SKELETON.go.mod` |
| ``driver-service` (availability)` | edge / hot path | — | `driver-service` | `SKELETON.go.mod` |
| ``geolocation-service` (ETA/routing)` | edge / hot path | map provider (Google / Mapbox / HERE) | ``geolocation-service` (ETA/routing)`'s own provider | `SKELETON.go.mod` |
| `configuration-service` | edge / hot path¹ | — | (no upstream) | `SKELETON.go.mod` |
| ``notification-service` (provider ACL)` | edge / hot path | FCM · APNs · Twilio · AWS SES | `notification-service` | `SKELETON.go.mod` |
| ``reporting-service` (data lake)` | streaming | S3 · Snowflake / BigQuery / Redshift | ``reporting-service` (data lake)`'s own warehouse | `SKELETON.go.mod` |
| `file-service` | edge / hot path | S3 · ClamAV | `identity-service` | `SKELETON.go.mod` |
| `geolocation-service` | edge / hot path | map provider (Google / Mapbox / HERE) | `geolocation-service`'s own provider | `SKELETON.go.mod` |

> ¹ `configuration-service` is listed in the Go column in
> `RECOMMENDATIONS.md` 2; the per-service TECH.md ships in Go
> for the same hot-path rationale.

### 7.6 Python — math / scoring / ML (4 services)

Every service in this group pulls in the libraries listed in 5.
"Specific" lists the **service-specific subspecialization**.

| Service | Profile | External vendor SDK | Platform-required runtime services | Skeleton |
|---|---|---|---|---|
| `fraud-risk-service` | math / scoring / ML | device fingerprint · threat intel | `device-fingerprint-provider` | `SKELETON.pyproject.toml` |
| ``courier-service` (dispatch)` | math / scoring | — | `courier-service` (events) | `SKELETON.pyproject.toml` |
| ``driver-service` (incentives)` | math / scoring | — | `trip-service` (events) | `SKELETON.pyproject.toml` |
| `reporting-service` | streaming / read | S3 (export) | ``reporting-service` (data lake)` (events) | `SKELETON.pyproject.toml` |

---

## 8. License attribution & NOTICE file guidance

Every service bundle — when shipped as a container image, a tarball,
or via any distribution channel — must carry:

1. **A `NOTICE` file** at the root of the container image, listing:
   - The platform's own copyright (the `trips-enjoy` platform).
   - The OSS projects listed in 2 with their licenses.
   - The OSS libraries listed in 3/4/5 that the service actually
     includes (as opposed to those that are provided by the shared
     `platform-spring-boot-starter` and listed in the starter's
     own NOTICE).
2. **A `THIRD-PARTY-LICENSES` file** containing the full upstream
   license text of every dependency, concatenated. This is the
   matching text-only counterpart to the NOTICE file.
3. **Stack-specific generation tooling** that produces (1) and (2)
   automatically on every release build:
   - **Kotlin (Gradle 9)**: `gradle-license-report` plugin. Emits
     `build/reports/dependency-license-report/index.html` plus
     `build/reports/dependency-license-report/THIRD-PARTY-NOTICES.txt`.
   - **Go (`go build`)**: `github.com/google/go-licenses` (`go-licenses
     save ./... --save_path third-party-licenses`). Emits a directory
     of license texts referenced by the NOTICE.
   - **Python (`uv` + `pyproject.toml`)**: `pip-licenses` (CLI) or
     `pip-licenses-lib` (programmatic). Emits a flat dependency →
     license table.

The generation is wired into the `release` CI job; the
artifact is published alongside the binary. The build-time
mechanism is the single source of truth for what actually ships.

> **Why do we automate this?** The catalogue in 2–7 is
> *documentation*. The NOTICE + THIRD-PARTY-LICENSES that ships
> in a service bundle is *enforced* by the build. If a dependency
> is added without updating this catalogue, the catalogue is
> stale; if it is added without updating the build, the bundle
> is non-compliant. Both are considered bugs.

---

## 9. License compatibility matrix

The platform is built entirely from OSS with permissive licenses
(Apache-2.0, BSD-2-Clause, BSD-3-Clause, MIT, EPL-2.0, ISC, PSF-2.0)
plus a small set of weak-copyleft libraries (LGPL-2.1-only, EPL-1.0)
and a few runtime-only projects with stronger copyleft (GPL-2.0,
GPL-2.0-with-classpath-exception, AGPL-3.0-only). The distribution
modes are:

| Distribution mode | What the user gets | Compatible licenses | Notes |
|---|---|---|---|
| **Internal SaaS** (the platform's primary mode) | Network access to the platform; no binaries redistributed | All licenses listed in 2 are compatible | The AGPL network clause applies to anyone who accesses the dashboards from the public internet; this is acceptable for the platform's customers. |
| **On-prem appliance** (hypothetical future mode) | Docker images + Helm charts delivered to a customer | All licenses **except** the AGPL-3.0-only ones (Grafana, Loki, Tempo) — these would need to be swapped for non-AGPL equivalents (e.g. Splunk, Elastic under a custom license, or a self-hosted fork). | Would also require substituting the BUSL-1.1 Vault for a non-source-available secret manager if the customer policy requires OSI-approved OSS only. |
| **Embedded library** (not a current mode) | The platform's own code as a library | All permissive + LGPL works; the `platform-spring-boot-starter` is Apache-2.0 | The platform is not designed for embedded-library distribution today. |

### 9.1 Why weak-copyleft is fine

- **Hibernate ORM (LGPL-2.1-only)** — the obligation under LGPL-2.1
  is to allow the user to replace the LGPL'd library with a
  modified version. Spring Boot's classloader + module system
  already satisfies this (the Hibernate JAR is on the classpath,
  not statically linked). No service statically links Hibernate.
- **Logback (EPL-1.0 / LGPL-2.1 dual)** — same reasoning as
  Hibernate. EPL-1.0 is similar in spirit to Apache-2.0 for the
  use case of being a runtime dependency.
- **JUnit 5 (EPL-2.0)** — invoked via the test classpath, never
  shipped inside a service binary.

### 9.2 Why GPL-2.0 is fine

- **PostGIS (GPL-2.0)** — distributed as a PostgreSQL extension;
  the PostgreSQL license (BSD-style) governs the database server,
  and the PostGIS extension is loaded by the PostgreSQL process.
  When distributing a PostgreSQL-with-PostGIS container image,
  the PostgreSQL+PostGIS combination is governed by the
  PostgreSQL license (which the PostgreSQL project considers
  GPL-compatible).
- **ClamAV (GPL-2.0)** — runs as a separate process; the
  `file-service` talks to it via the standard `clamd` protocol
  over a Unix socket. No service binary links libclamav.

### 9.3 Why AGPL-3.0 is fine in SaaS, not in on-prem

- **Grafana / Loki / Tempo (AGPL-3.0-only)** — the AGPL
  "network use is distribution" clause applies only when the
  service is used by external users over a network. In an internal
  SaaS, the platform operator is the licensee; the end user
  accesses the platform via the standard terms of service. In an
  on-prem appliance delivered to a customer, the customer is the
  end user and the AGPL clause applies — which would require
  either relicensing under a commercial agreement or swapping
  for a non-AGPL alternative.

### 9.4 BUSL-1.1 (HashiCorp Vault)

- **Vault (BUSL-1.1)** — source-available, not OSI-approved OSS.
  Used as a service (the Vault server runs in the cluster);
  service binaries talk to it via the standard Vault REST API.
  There is no static linking. The platform's internal use is
  unaffected by the BUSL restriction. If the platform were
  distributed as an on-prem appliance, the BUSL would not
  prevent the redistribution (BUSL prohibits competing Vault
  offerings, not redistribution), but the platform team's
  preference is to swap Vault for an OSS secret manager if the
  on-prem distribution mode is ever adopted.

---

## 10. See also

### Sibling docs in this directory

- [`README.md`](./README.md) — `platform-spring-boot-starter` overview
- [`PLATFORM_BASELINE.md`](./PLATFORM_BASELINE.md) — Platform-wide runtime / data / messaging / security / observability baseline
- [`MODULES.md`](./MODULES.md) — `platform-spring-boot-starter` sub-module breakdown
- [`CONVENTIONS.md`](./CONVENTIONS.md) — Error model, correlation IDs, audit, PII, money, logging, naming
- [`AUTO_CONFIG.md`](./AUTO_CONFIG.md) — Every auto-configuration, defaults, override keys
- [`INTEGRATION.md`](./INTEGRATION.md) — How a service adopts the starter
- [`TESTING.md`](./TESTING.md) — `BaseIntegrationTest`, JWT minting, outbox assertions, contract tests
- [`VERSIONING.md`](./VERSIONING.md) — SemVer policy, deprecation, upgrade process, CVE response
- [`ROADMAP.md`](./ROADMAP.md) — What's in / next / out of scope

### Platform-wide

- [`../services/README.md`](../services/README.md) — Service catalog (all 20 services)
- [`../services/RECOMMENDATIONS.md`](../services/RECOMMENDATIONS.md) — Language + framework recommendation per service (the tech map); version pins
- [`../README.md`](../README.md) — Top-level platform documentation reading order
- [`../../main.md`](../../main.md) — Top-level platform specification
- [`../architecture/SERVICE_DOC_TEMPLATE.md`](../architecture/SERVICE_DOC_TEMPLATE.md) — The contract every service follows
- [`../architecture/SERVICE_ISOLATION.md`](../architecture/SERVICE_ISOLATION.md) — How every service behaves when a downstream is down

### Per-service

- [`../services/<service>/TECH.md`](../services/README.md) 11 *Open-source bundle* — per-service view of this catalogue
- [`../services/<service>/SKELETON.<ext>`](../services/README.md) — per-service extractability manifest
- [`../services/<service>/README.md`](../services/README.md) — service purpose, bounded context, dependencies

---

> **Version pin discipline.** This catalogue never pins versions
> directly. The pinned versions are in
> [`RECOMMENDATIONS.md` 5.1](../services/RECOMMENDATIONS.md#51-version-baseline-pinned-latest-stable);
> bump versions there, not here.
>
> **License accuracy.** The SPDX identifiers and license URLs are
> the platform team's best understanding of the upstream license
> at the time of writing. The build-time `gradle-license-report` /
> `go-licenses` / `pip-licenses` job is the **enforcement** point
> for what actually ships in a bundle.

## Related docs

- [`../shared/PLATFORM_BASELINE.md`](../shared/PLATFORM_BASELINE.md) — single source for PostgreSQL 19, Kafka, Keycloak, etc.
- [`../shared/CONVENTIONS.md`](../shared/CONVENTIONS.md) — code conventions and naming
- [`../architecture/SYSTEM_OVERVIEW.md`](../architecture/SYSTEM_OVERVIEW.md) — plain-English platform summary
