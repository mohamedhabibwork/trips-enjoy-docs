# Platform Baseline

> **Single source of truth** for the platform-wide facts every service
> README, BRD, SRS, ERD, INTEGRATION, WORKFLOWS, and TECH inherits.
> When the baseline changes (e.g. PostgreSQL bumps from 18 → 19), only
> this file changes; service docs reference it instead of repeating it.

This file is referenced from every `services/<svc>/README.md`. Per-service
docs MUST NOT restate any of these facts at length — they MUST link here
and only describe what is *unique* to that service.

---

## 1. Runtime & deployment baseline

| Concern | Baseline | ADR |
|---|---|---|
| Container orchestrator | Kubernetes (managed; multi-region active-active) | [ADR-0012](../architecture/adrs/0012-kubernetes-orchestration.md) |
| Container base image (JVM) | `eclipse-temurin:25-jre-jammy` (multi-stage, JRE-only final stage, non-root user) | — |
| Container base image (Go) | `gcr.io/distroless/static-debian12:nonroot` | — |
| Container base image (Python) | `python:3.13-slim-bookworm` (slim, non-root) | — |
| Service mesh | Istio ambient mode (mTLS between pods; L7 only at the gateway) | — |
| API gateway | [ADR-0008](../architecture/adrs/0008-api-gateway.md) — Envoy-based, JWT validation, rate limiting, request transformation | — |
| Ingress | Cloud-native LB → Istio ingress gateway → `api-gateway` service | — |
| Deployment strategy | Rolling (default), canary for risk-tier-1 services | [`DEPLOYMENT_ARCHITECTURE.md`](../architecture/DEPLOYMENT_ARCHITECTURE.md) |
| Migrations | Run as a Kubernetes Job **before** the rolling deploy, using the same image with the `migrate` subcommand | [`DATABASE_ARCHITECTURE.md`](../architecture/DATABASE_ARCHITECTURE.md) |
| Pod disruption budget | `minAvailable: 50%` in production | [`DEPLOYMENT_ARCHITECTURE.md`](../architecture/DEPLOYMENT_ARCHITECTURE.md) |
| Topology spread | Anti-affinity across nodes and zones | [`DEPLOYMENT_ARCHITECTURE.md`](../architecture/DEPLOYMENT_ARCHITECTURE.md) |
| Network policy | Default-deny ingress; explicit allow from `api-gateway`, `admin-service`, or the platform's Kafka consumers | [`SECURITY_ARCHITECTURE.md`](../architecture/SECURITY_ARCHITECTURE.md) |
| Image registry | `registry.trips-enjoy.com/<service>:<sha>` (immutable, signed with cosign) | — |
| Secret delivery | Vault → Kubernetes secret CSI driver → env or mounted file (no secret in env for prod) | [`SECURITY_ARCHITECTURE.md`](../architecture/SECURITY_ARCHITECTURE.md) |
| Disaster recovery | RPO 15 min (async replica + WAL shipping); RTO 1 h; warm standby per region | [`FAILURE_HANDLING.md`](../architecture/FAILURE_HANDLING.md) 6 |

## 2. Data baseline

| Concern | Baseline | ADR |
|---|---|---|
| Primary OLTP store | **PostgreSQL 19** — one schema per service, no cross-service FKs | [ADR-0002](../architecture/adrs/0002-postgres-per-service.md) |
| Geospatial | **PostGIS 3.5** bundled with PostgreSQL 19 | [ADR-0007](../architecture/adrs/0007-postgis-for-geospatial.md) |
| Migrations | **Flyway 11** (Spring Boot services), `golang-migrate` (Go services), `alembic` (Python) | [`DATABASE_ARCHITECTURE.md`](../architecture/DATABASE_ARCHITECTURE.md) |
| Identifier type | **UUIDv7** for new primary keys (time-ordered, k-sortable); UUIDv4 acceptable for non-time-ordered entities | [ADR-0015](../architecture/adrs/0015-uuidv7-for-ids.md) |
| Cross-service references | Stored as UUID columns **without** FKs to other services' databases | [`CONSISTENCY_STRATEGY.md`](../architecture/CONSISTENCY_STRATEGY.md) |
| Naming | Tables and columns in `snake_case`; logical entities `PascalCase` | [`CONVENTIONS.md`](./CONVENTIONS.md) |
| Money | Stored in **minor units** (integer cents/fils) as `BIGINT`; `Money` value class wraps arithmetic | [`CONVENTIONS.md`](./CONVENTIONS.md) 3 |
| Times | All persisted as `timestamptz` UTC; display in user TZ at the edge | [`CONVENTIONS.md`](./CONVENTIONS.md) 3 |
| Soft-delete | `deleted_at timestamptz NULL` column, partial index `WHERE deleted_at IS NULL` | [`shared/README.md`](./README.md) |
| Audit columns | `created_at`, `created_by`, `updated_at`, `updated_by` populated by JPA auditing | [`shared/README.md`](./README.md) |
| JSONB usage | For opaque, queryable metadata only; never as the primary shape of an entity | [`DATABASE_ARCHITECTURE.md`](../architecture/DATABASE_ARCHITECTURE.md) |
| Table partitioning for high-volume append-mostly tables | Declarative `RANGE` by UTC timestamp; child partitions created with `CREATE TABLE IF NOT EXISTS … PARTITION OF …`; pre-creation + drop is a service-owned scheduled job; canonical template (eligibility, cadence decision table, naming, mixed-retention, outbox policy, maintenance contract) in [`DATABASE_ARCHITECTURE.md`](../architecture/DATABASE_ARCHITECTURE.md) "Table Partitioning — Canonical Template" | [`DATABASE_ARCHITECTURE.md`](../architecture/DATABASE_ARCHITECTURE.md) |
| Partition maintenance engine (added 2026-08-14) | Canonical PL/pgSQL functions in the `partman` schema (`ensure_partitions`, `ensure_partitions_daily`, `drop_expired_partitions`, `partition_health`); driven by **pg_cron** at `0 2 * * *` per parent with a per-service Spring `@Scheduled` fallback; `pg_partman` is an opt-in alternative | [`PARTITION_FUNCTIONS.md`](./PARTITION_FUNCTIONS.md) |
| Cluster extensions (added 2026-08-14) | `pg_cron` installed cluster-wide via `scripts/db-init.sh` (superuser) and re-asserted by each service's `V__partition_functions.sql` (defence in depth); `pg_partman` is opt-in per service | [`scripts/db-init.sh`](../../scripts/db-init.sh), [`PARTITION_FUNCTIONS.md`](./PARTITION_FUNCTIONS.md) §8 |

## 3. Messaging baseline

| Concern | Baseline | ADR |
|---|---|---|
| Event broker | **Apache Kafka 3.9** (KRaft mode, no ZooKeeper) | [ADR-0005](../architecture/adrs/0005-kafka-as-event-broker.md) |
| Event naming | `domain.entity.event.vN` — e.g. `ride.trip.completed.v1` | [`CONVENTIONS.md`](./CONVENTIONS.md) 5 |
| Schema registry | Confluent Schema Registry, Avro with backward-compatibility checks | [`EVENT_ARCHITECTURE.md`](../architecture/EVENT_ARCHITECTURE.md) |
| Producer pattern | **Transactional outbox** — no dual-write between DB and Kafka | [ADR-0009](../architecture/adrs/0009-transactional-outbox.md) |
| Consumer pattern | At-least-once with consumer-side dedup on `event_id` | [`EVENT_ARCHITECTURE.md`](../architecture/EVENT_ARCHITECTURE.md) |
| Cross-service workflows | **Saga** with compensating actions; orchestration in the owner of the root aggregate | [ADR-0010](../architecture/adrs/0010-saga-pattern.md) |
| DLQ | Per consumer, with replay runbook | [`FAILURE_HANDLING.md`](../architecture/FAILURE_HANDLING.md) |

## 4. Identity & security baseline

| Concern | Baseline | ADR |
|---|---|---|
| Identity provider | **Keycloak** realms: `customers`, `merchants`, `drivers-couriers`, `staff`, `internal` | [ADR-0003](../architecture/adrs/0003-keycloak-for-identity.md) |
| AuthN on REST | Bearer JWT, validated by `api-gateway` (edge) and re-validated by each service (defence in depth) | [`SECURITY_ARCHITECTURE.md`](../architecture/SECURITY_ARCHITECTURE.md) |
| AuthZ | RBAC via Keycloak realm roles + service-level `@PreAuthorize` for fine-grained checks | [`KEYCLOAK_ARCHITECTURE.md`](../architecture/KEYCLOAK_ARCHITECTURE.md) |
| PCI scope | Card data **never** enters our services; `payment-service` uses provider tokenisation | [`SECURITY_ARCHITECTURE.md`](../architecture/SECURITY_ARCHITECTURE.md) 4 |
| PII | At-rest encryption (PG `pgcrypto`), in-transit TLS, redaction in logs (`platform-spring-boot-redactor`) | [`SECURITY_ARCHITECTURE.md`](../architecture/SECURITY_ARCHITECTURE.md) |
| Secret storage | HashiCorp Vault; secrets mounted at pod start, never in env for prod | [`SECURITY_ARCHITECTURE.md`](../architecture/SECURITY_ARCHITECTURE.md) |
| mTLS | Pod-to-pod mTLS via Istio ambient mesh | [`SECURITY_ARCHITECTURE.md`](../architecture/SECURITY_ARCHITECTURE.md) |

## 5. Observability baseline

| Concern | Baseline | ADR |
|---|---|---|
| Tracing | **OpenTelemetry** SDK → OTLP → collector → Tempo/Jaeger | [ADR-0011](../architecture/adrs/0011-opentelemetry-observability.md) |
| Metrics | Micrometer → Prometheus → Grafana; standard tags `service`, `env`, `region`, `tenant` | [`OBSERVABILITY.md`](../architecture/OBSERVABILITY.md) |
| Logs | Structured JSON to stdout → Fluent Bit → Loki; correlation id on every record | [`OBSERVABILITY.md`](../architecture/OBSERVABILITY.md) |
| Health endpoints | `/health` (liveness), `/ready` (readiness — DB + Kafka + Redis checks), `/started` (after warm caches) | [`OBSERVABILITY.md`](../architecture/OBSERVABILITY.md) |
| Audit | Auto-emit `audit.api.request.v1` (who/what/when) and `audit.admin.<service>.v1` (admin actions) | [`shared/README.md`](./README.md) |

## 6. API baseline

| Concern | Baseline | ADR |
|---|---|---|
| Synchronous API style | **REST** with OpenAPI 3.x specs | [ADR-0004](../architecture/adrs/0004-rest-as-primary-api.md) |
| URI versioning | `/v1/<resource>`. Major breaking → `/v2`. Minor additive stays in `/v1`. | [`API_STANDARDS.md`](../architecture/API_STANDARDS.md) |
| Idempotency | `Idempotency-Key` header on every mutating POST; server caches key → response for 24 h | [`API_STANDARDS.md`](../architecture/API_STANDARDS.md) |
| Error envelope | RFC 7807 `application/problem+json` (see [`CONVENTIONS.md`](./CONVENTIONS.md) 1) | [`CONVENTIONS.md`](./CONVENTIONS.md) |
| Pagination | Cursor-based (`?cursor=<opaque>&limit=<n>`); default 50, max 200 | [`API_STANDARDS.md`](../architecture/API_STANDARDS.md) |
| Filtering | Field-named query params (`?status=active&city=dubai`); no DSL | [`API_STANDARDS.md`](../architecture/API_STANDARDS.md) |
| Correlation | `X-Request-Id` header (alias `X-Correlation-Id`) propagated through every call, event, log, and OTel span; API gateway is the canonical root generator | [`CONVENTIONS.md`](./CONVENTIONS.md) · [ADR-0019](../architecture/adrs/0019-request-id-at-the-edge.md) |
| Rate limiting | Redis-backed token bucket; defaults per service in [`CONFIGURATION_ARCHITECTURE.md`](../architecture/CONFIGURATION_ARCHITECTURE.md) | [ADR-0006](../architecture/adrs/0006-redis-for-cache-and-rate.md) |

## 7. Caching & rate limiting baseline

| Concern | Baseline | ADR |
|---|---|---|
| Cache store | **Redis 8.x**, Lettuce client, `CacheManager` with consistent JSON serializer | [ADR-0006](../architecture/adrs/0006-redis-for-cache-and-rate.md) |
| Cache key format | `<service>:<entity>:<id[:version]>` (e.g. `pricing:quote:01HZX…:v1`) | [`CONVENTIONS.md`](./CONVENTIONS.md) 6 |
| TTL convention | Short (≤ 5 min) for hot derived data; long (≥ 1 h) for static config; explicit `null` TTL is forbidden | [`CONVENTIONS.md`](./CONVENTIONS.md) 6 |
| Rate limiter | Redis token bucket; per principal id or per IP, per service | [`API_STANDARDS.md`](../architecture/API_STANDARDS.md) |

## 8. Configuration baseline

| Concern | Baseline | ADR |
|---|---|---|
| Source of truth | `configuration-service` (git-backed, env-scoped) | [ADR-0014](../architecture/adrs/0014-externalize-configuration.md) |
| Local override | `application.yml` + `application-local.yml` in the service repo (gitignored for secrets) | [`CONFIGURATION_ARCHITECTURE.md`](../architecture/CONFIGURATION_ARCHITECTURE.md) |
| Hierarchy | env-specific override > `configuration-service` > built-in default | [`CONFIGURATION_ARCHITECTURE.md`](../architecture/CONFIGURATION_ARCHITECTURE.md) |
| Hot reload | Spring Cloud Bus event from `configuration-service` on commit | [`CONFIGURATION_ARCHITECTURE.md`](../architecture/CONFIGURATION_ARCHITECTURE.md) |
| Feature flags | ``configuration-service` (flags)` (separate concern from configuration) | [`configuration-service` README](../services/configuration-service/README.md) |

## 9. Local-development baseline

The exact `docker compose up`, `make up-…`, or `bun run …` commands
**are** per-service (each service has its own compose profile) and live
in each service's README 17. What is shared and lives here:

- `docker compose v2` is the universal local-orchestration tool.
- Each service's compose file includes: the service itself, PostgreSQL
  18, Kafka, Redis, Keycloak (for protected endpoints), and any
  provider mocks (Stripe/Adyen/etc.) the service depends on.
- Dev realms in Keycloak come pre-seeded with 5 test users per realm.
- Tests use Testcontainers for PostgreSQL/Kafka/Redis (no shared dev
  cluster).
- The platform-wide test harness lives in [`TESTING.md`](./TESTING.md).

## 10. How a service README should reference this file

Every `services/<svc>/README.md` should follow this pattern in the
"Scalability", "Observability", "Security", and "Deployment" sections:

```markdown
## 16. Scalability

- **Replicas**: N (HPA to M).
- **Hot path**: <what to cache, what to pre-fetch>.
- **Read replicas**: <yes/no, why>.

For platform-wide baselines (PostgreSQL, Kafka, Redis, OpenTelemetry,
Vault, etc.) see [`./PLATFORM_BASELINE.md`](./PLATFORM_BASELINE.md).
```

Do **not** restate "uses PostgreSQL 19 with Flyway migrations" or
"deploys as a Kubernetes pod with mTLS" in the per-service file — those
facts belong here.

---

## Related

- [`CONVENTIONS.md`](./CONVENTIONS.md) — naming, error model, idempotency, time/money conventions
- [`INTEGRATION.md`](./INTEGRATION.md) — how the shared library bridges services
- [`MODULES.md`](./MODULES.md) — Maven/Gradle module map of the starter
- [`TESTING.md`](./TESTING.md) — Testcontainers, base test class, contract tests
- [`VERSIONING.md`](./VERSIONING.md) — semver policy for the starter
- [`../architecture/SYSTEM_OVERVIEW.md`](../architecture/SYSTEM_OVERVIEW.md) — the architectural context this baseline implements
- [`../architecture/ADR_INDEX.md`](../architecture/ADR_INDEX.md) — every decision that lands here has an ADR
- [`../architecture/SERVICE_ISOLATION.md`](../architecture/SERVICE_ISOLATION.md) — **how every service behaves when a downstream is down** (timeout / bulkhead / circuit / retry / fallback, by class: CRITICAL / DEGRADABLE / BEST-EFFORT)
- [`../architecture/DOWNSTREAM_ERROR_CATALOG.md`](../architecture/DOWNSTREAM_ERROR_CATALOG.md) — **canonical error-code catalog and propagation rules** (the `downstream` block, forward/translate/degrade/reject)

---

## 11. Per-service env-var deliverable shape (added 2026-08-14)

Every active service scaffold ships the following files. Spring shapes are
files under `src/main/resources/`; Go and Python shapes are at the project
root.

| File (Spring) / path (Go, Python) | Purpose |
|---|---|
| `application.properties` | Build-time defaults; `<SVC>_*` env-var placeholders that resolve at runtime |
| `application.yml` | Spring Boot 4 baseline; profile selection (`SPRING_PROFILES_ACTIVE`) + Flyway schemas + datasource/redis/kafka defaults |
| `application-dev.yml` | Local: `jdbc:postgresql://0.0.0.0:5432/trips_enjoy?currentSchema=<schema>` |
| `application-stg.yml` | Staging: every value is `${VAR}` — supplied at deploy time via Vault |
| `application-prod.yml` | Production: every value is `${VAR}` — supplied at deploy time via Vault |
| `.env.example` | Checked-in template; copy to `.env` (gitignored) for local dev |
| `src/main/resources/db/migration/V{n}__<desc>.sql` | Flyway migrations |
| `migrations/000NNN_<desc>.up.sql` (Go only) | golang-migrate migrations |
| `migrations/versions/0001_<desc>.py` (Python only) | alembic migrations |

Precedence (matches CONFIGURATION_ARCHITECTURE.md §"Hierarchy"):

```
SPRING_PROFILES_ACTIVE=stg|prod      ──► application-{profile}.yml
                  │
                  ▼
.env (local) ──► <SVC>_DB_URL, <SVC>_REDIS_HOST, ...
                  │
                  ▼
Vault ◄── external-secrets-operator mounts at pod start (stg/prod only)
                  │
                  ▼
application.properties  ──► built-in defaults (jdbc URL, ports)
```

Hard rule: a missing `<SVC>_DB_URL` or `<SVC>_DB_PASSWORD` in stg/prod
**must fail the service at startup** so misconfiguration is loud, not silent.
This matches the
[`docs/shared/CONVENTIONS.md`](./CONVENTIONS.md) "fail fast on missing config"
rule.