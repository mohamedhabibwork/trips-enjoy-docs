# Per-Service Deployment Order

> **Purpose.** This document is the **canonical, ordered sequence** in
> which the platform's 21 active services must be deployed. Every
> service has a defined position in the sequence, and the **hard
> service-to-service dependencies** that justify that position.
>
> **Scope.** This is the **runtime deployment order** — the order
> in which the services must come up in a fresh environment
> (greenfield, region failover, or a from-scratch disaster
> recovery). It is **not** the **implementation order** (the
> order in which service teams build the services — see
> [`MASTER_PLAN.md`](MASTER_PLAN.md) and
> [`IMPLEMENTATION_PHASES.md`](IMPLEMENTATION_PHASES.md) for
> that).
>
> **Single source of truth.** Every per-service `PLAN.md`
> carries a **Hard service-to-service dependencies** callout
> that references this document and lists the service's
> specific position and its hard deps.

> **Updated:** 2026-08-14 — implementation progress reflected in §8
> (9 of 21 active services graduated from docs-only to full
> implementation; see §8 + `apps/` for the running count).

## 1. Reading the order

Each service has a **tier** (0–4) and a **position within the tier**.
Deployment proceeds tier-by-tier; within a tier, services may be
deployed **in parallel** if the environment allows it (the
greenfield install script can do this; a manual rollout usually
goes in the listed position order for safety).

For each service, the table below lists:

- **Tier** — the deployment tier (lower = earlier).
- **Position** — the position within the tier (1-indexed).
- **Hard deps** — services that **must be live and reachable**
  before this service can complete its **startup health check**
  (`/ready` returns 200). A "hard dep" means: the service
  cannot start without it.
- **Soft deps (graceful degrade)** — services that the service
  calls at runtime but can start without. The service starts;
  calls fail with circuit-breaker fallback until the soft dep
  is up.
- **Criticality** — Tier 1 (revenue-critical, 99.95% SLO) /
  Tier 2 (important but degrades gracefully, 99.9%) /
  Tier 3 (supporting, 99.5%). Inherited from
  [`architecture/MICROSERVICES_MAP.md`](architecture/MICROSERVICES_MAP.md).

## 2. The deployment order

### Tier 0 — Platform foundation (no service deps)

| # | Service | Hard deps | Soft deps | Criticality | Notes |
|---|---|---|---|---|---|
| 1 | `configuration-service` | — (PostgreSQL + Redis only) | — | T1 | Every other service reads from it at startup; the only hard dep is the **PostgreSQL schema** + **Redis cluster**, which are infra-level. |
| 2 | `identity-service` | `configuration-service` (claims, locale defaults) | — | T1 | Bridges to Keycloak. The hard dep is `configuration-service` for OIDC client config. |
| 3 | `audit-service` | `configuration-service` (Kafka topic config) | — | T2 | Every service emits `audit.*` events; the audit-service consumer must be live to drain the topic from offset 0. |
| 4 | `api-gateway` | `configuration-service` (routing rules), `identity-service` (JWKS cache) | — | T1 | The edge. Cannot route without the routing config. |
| 5 | `file-service` | `configuration-service` (S3 driver config) | — | T2 | Standalone S3-backed service. No service deps. |
| 6 | `geolocation-service` | `configuration-service` (map provider API keys) | — | T1 | Standalone PostGIS service. No service deps. |
| 7 | `notification-service` | `configuration-service` (provider API keys, templates) | `customer-service` (recipient profile for SMS/push) | T2 | Standalone; soft-deps on `customer-service` only at runtime (recipient lookup). |
| 8 | `ledger-service` | `configuration-service` (chart-of-accounts default) | — | T1 | Standalone; financial core. |

### Tier 1 — Domain foundations

| # | Service | Hard deps | Soft deps (graceful degrade) | Criticality | Notes |
|---|---|---|---|---|---|
| 9 | `customer-service` | `identity-service` (Keycloak user lookup at signup), `configuration-service` (KYC config) | `notification-service` (welcome email) | T1 | The customer profile is consumed by every other Tier 1+ service. |
| 10 | `driver-service` | `customer-service` (KYC contract), `identity-service` (KYC verification) | `notification-service` (KYC status push) | T1 | Absorbs driver-availability, driver-location, driver-incentives, driver-vehicles per [ADR-0017](architecture/adrs/0017-20-service-architecture.md). |
| 11 | `courier-service` | `customer-service` (KYC contract), `identity-service` (KYC verification) | `notification-service` (courier assignment push) | T1 | Absorbs courier-dispatch, courier-tracking, courier-delivery. |
| 12 | `restaurant-service` | `customer-service` (merchant KYC contract), `identity-service` (merchant user) | `notification-service` (merchant onboarding email) | T1 | Absorbs restaurant-merchant, restaurant-branch, restaurant-menu, restaurant-inventory, restaurant-staff. |
| 13 | `admin-service` | `configuration-service` (admin RBAC), `identity-service` (Keycloak admin realm) | every other service (BFF aggregator) | T1 | Soft-deps on every service because it's a BFF; the service starts and serves cached responses until the upstream is up. |
| 14 | `fraud-risk-service` | `configuration-service` (risk-model thresholds) | `customer-service` (user history lookup) | T1 | Standalone risk scorer; soft-deps on `customer-service` for user history. |
| 15 | `pricing-service` | `configuration-service` (tariff overrides, tax tables, geo-config), `customer-service` (loyalty account exposure) | `trip-service` (quote requests) | T1 | The pricing engine. |
| 16 | `payment-service` | `configuration-service` (gateway registry), `ledger-service` (postings), `pricing-service` (tax line items) | `customer-service` (wallet owner lookup), `restaurant-service` (merchant settlement) | T1 | Absorbs payment-wallet, payment-saga (ride + food), payment-driver-earnings, payment-courier-earnings, payment-merchant-settlement. |

### Tier 2 — Business logic

| # | Service | Hard deps | Soft deps (graceful degrade) | Criticality | Notes |
|---|---|---|---|---|---|
| 17 | `trip-service` | `customer-service` (rider profile), `driver-service` (driver profile), `pricing-service` (quote), `payment-service` (ride saga), `geolocation-service` (ETA + routing), `notification-service` (trip push), `configuration-service` (trip rules) | `chat-service` (Phase 7.7 — rider↔driver thread bootstrap) | T1 | Absorbs trip-ride-request, trip-scheduled, trip-safety, trip-history, trip-review. |
| 18 | `food-order-service` | `customer-service` (customer profile), `restaurant-service` (menu + KYC), `pricing-service` (quote with tax), `payment-service` (food saga), `courier-service` (delivery dispatch), `notification-service` (order push), `configuration-service` (menu rules) | `chat-service` (Phase 7.7 — customer↔restaurant thread) | T1 | Absorbs food-order-cart, food-order-checkout, food-order-queue, food-review. |
| 19 | `search-service` | `configuration-service` (search index config) | `restaurant-service` (menu data), `trip-service` (history) | T2 | OpenSearch-backed; the index is bootstrapped from upstream events. |
| 20 | `reporting-service` | `configuration-service` (KPI definitions, export schedule) | `audit-service` (audit fact table), `ledger-service` (revenue fact table), every other service (domain events) | T3 | Reads Kafka from offset 0; the index backfills on startup. |

### Tier 3 — Cross-cutting additions (post-MVP)

| # | Service | Hard deps | Soft deps (graceful degrade) | Criticality | Notes |
|---|---|---|---|---|---|
| 21 | `chat-service` | `configuration-service` (WebSocket limits, rate limits), `identity-service` (Keycloak JWKS) | `trip-service`, `food-order-service`, `courier-service` (thread bootstrap events), `notification-service` (offline push), `admin-service` (moderation), `fraud-risk-service` (abuse scoring), `restaurant-service` (passive participant lookup) | T1 | Phase 7.7 cross-cutting addition per [ADR-0021](architecture/adrs/0021-21-service-architecture-with-chat.md). The chat-service **can start** without its consumers; threads bootstrap on the next matching event. |

## 3. Cross-cutting runtime dependencies (apply to every service)

Regardless of the tier, every service requires the following
**infra-level** dependencies to be live before it can deploy:

| Dep | Provided by | Hard? |
|---|---|---|
| **PostgreSQL 19 cluster** (per-service schema) | Platform DBA + Terraform | Hard — the migration Job fails if the cluster is unreachable |
| **Kafka cluster** (per-topic partitioning) | Platform infra | Hard for event producers and consumers |
| **Redis cluster** (per-service cache) | Platform infra | Hard for services with Redis cache; soft for stateless services |
| **Keycloak** (OIDC issuer) | `identity-service` is the bridge, but the actual Keycloak server is infra | Hard for human-auth services (`api-gateway`, every web + mobile BFF) |
| **Vault** (secrets) | Platform infra | Hard — the secret CSI driver fails the pod if Vault is unreachable |
| **Istio ambient mesh** (mTLS) | Platform infra | Soft — pods can start without mesh but won't have mTLS; degraded security posture |
| **OpenTelemetry collector** | Platform infra | Soft — emits drop telemetry if collector is down; not a startup blocker |
| **Object storage (S3 / equivalent)** | Platform infra | Hard for `file-service`; soft for services that delegate to `file-service` |
| **PostGIS extension** | Per-service database | Hard for `geolocation-service` and the zones sub-aggregate of `geolocation-service` |

These are documented in
[`architecture/DEPLOYMENT_ARCHITECTURE.md`](architecture/DEPLOYMENT_ARCHITECTURE.md)
and the per-service `TECH.md` for the specific service.

## 4. Deployment scenarios

### 4.1 Greenfield (new environment, no services)

Execute the tiers in order, with intra-tier parallelism if
the environment allows. The 8 services in Tier 0 are the
hardest because they have no service-to-service deps — they
all dep on the same infra (PostgreSQL + Redis + Kafka +
Keycloak + Vault + S3). Once Tier 0 is up, Tier 1 services
can come up in any order, but **the listed position order
is safest** because it surfaces config / RBAC issues one
service at a time.

### 4.2 Single-service rollout (most common)

When rolling out a new version of a single service, the
service's tier determines the **blast radius**:

- **Tier 0 (foundation)** — rolling deploy with canary
  10% → 50% → 100%. A bad deploy can break the platform
  (configuration-service is depended on by all 20 others;
  identity-service blocks every authenticated call).
  Per [`architecture/DEPLOYMENT_ARCHITECTURE.md`](architecture/DEPLOYMENT_ARCHITECTURE.md)
  rolling deploy, the platform requires a canary of at
  least 10% for 5 minutes.
- **Tier 1 / 2** — rolling deploy with no canary required
  by default; canary is configurable per service.
- **Tier 3 (cross-cutting additions like `chat-service`)** —
  canary required because the service has the largest
  blast radius (many consumers).

### 4.3 Region failover

In a region failover, the order above is replayed in the
failover region. The Tier 0 services are the most
critical — they must be live before the Tier 1+ services
can take traffic.

### 4.4 Disaster recovery (full platform rebuild)

The full Tier 0 → Tier 1 → Tier 2 → Tier 3 sequence is
replayed. The Tier 0 cluster is restored from backup
(PostgreSQL PITR, Kafka MirrorMaker2, Redis RDB). Tier 1+
services redeploy from images.

## 5. Per-service hard deps — the authoritative table

This is the **per-service** view. Each service has a
**Hard service-to-service dependencies** callout in its
`PLAN.md` that references this table.

| Service | Tier | Position | Hard deps | Soft deps (graceful degrade) |
|---|---|---|---|---|
| `configuration-service` | 0 | 1 | — (PostgreSQL + Redis) | — |
| `identity-service` | 0 | 2 | `configuration-service` | — |
| `audit-service` | 0 | 3 | `configuration-service` | — |
| `api-gateway` | 0 | 4 | `configuration-service`, `identity-service` | — |
| `file-service` | 0 | 5 | `configuration-service` | — |
| `geolocation-service` | 0 | 6 | `configuration-service` | — |
| `notification-service` | 0 | 7 | `configuration-service` | `customer-service` |
| `ledger-service` | 0 | 8 | `configuration-service` | — |
| `customer-service` | 1 | 9 | `identity-service`, `configuration-service` | `notification-service` |
| `driver-service` | 1 | 10 | `customer-service`, `identity-service` | `notification-service` |
| `courier-service` | 1 | 11 | `customer-service`, `identity-service` | `notification-service` |
| `restaurant-service` | 1 | 12 | `customer-service`, `identity-service` | `notification-service` |
| `admin-service` | 1 | 13 | `configuration-service`, `identity-service` | every other service (BFF) |
| `fraud-risk-service` | 1 | 14 | `configuration-service` | `customer-service` |
| `pricing-service` | 1 | 15 | `configuration-service`, `customer-service` | `trip-service` |
| `payment-service` | 1 | 16 | `configuration-service`, `ledger-service`, `pricing-service` | `customer-service`, `restaurant-service` |
| `trip-service` | 2 | 17 | `customer-service`, `driver-service`, `pricing-service`, `payment-service`, `geolocation-service`, `notification-service`, `configuration-service` | `chat-service` |
| `food-order-service` | 2 | 18 | `customer-service`, `restaurant-service`, `pricing-service`, `payment-service`, `courier-service`, `notification-service`, `configuration-service` | `chat-service` |
| `search-service` | 2 | 19 | `configuration-service` | `restaurant-service`, `trip-service` |
| `reporting-service` | 2 | 20 | `configuration-service` | `audit-service`, `ledger-service`, every other service (event consumers) |
| `chat-service` | 3 | 21 | `configuration-service`, `identity-service` | `trip-service`, `food-order-service`, `courier-service`, `notification-service`, `admin-service`, `fraud-risk-service`, `restaurant-service` |

## 6. Change-management rules

When adding a new service or a new hard dep to an existing
service:

1. **Open an RFC** — a GitHub / GitLab issue with the
   `platform/architecture` label, describing the new service
   or the new hard dep, and the rationale.
2. **Update this document** — add the new service to the
   tier table, or update the existing service's hard-deps
   row, in the same PR as the implementation.
3. **Update the per-service `PLAN.md`** — every affected
   service's `Hard service-to-service dependencies` callout
   must be updated to reference this document.
4. **Update [`MASTER_PLAN.md`](MASTER_PLAN.md)** and
   [`IMPLEMENTATION_PHASES.md`](IMPLEMENTATION_PHASES.md)**
   — the implementation order in those docs must remain
   consistent with this runtime deployment order.
5. **Update [`architecture/MICROSERVICES_MAP.md`](architecture/MICROSERVICES_MAP.md)**
   — the per-row tier + criticality must match.

This is enforced by the **Per-service Documentation
Contract** in
[`README.md` §"Per-Service Documentation Contract"`](README.md#per-service-documentation-contract).

## 7. Related docs

- [`MASTER_PLAN.md`](MASTER_PLAN.md) — the **implementation
  order** (when each service is built). Distinct from this
  doc's **runtime deployment order** (when each service must
  be live).
- [`IMPLEMENTATION_PHASES.md`](IMPLEMENTATION_PHASES.md) — the
  week-by-week implementation roadmap.
- [`PLAN_INDEX.md`](PLAN_INDEX.md) — the index of per-service
  `PLAN.md` files.
- [`architecture/DEPLOYMENT_ARCHITECTURE.md`](architecture/DEPLOYMENT_ARCHITECTURE.md) —
  Docker, Kubernetes, environments, image promotion, rolling
  deploy, canary, rollback.
- [`architecture/MICROSERVICES_MAP.md`](architecture/MICROSERVICES_MAP.md) —
  the service catalog (per-row criticality, DB schema,
  SLO).
- [`architecture/SERVICE_ISOLATION.md`](architecture/SERVICE_ISOLATION.md) —
  how every service behaves when a downstream is down
  (timeout, bulkhead, circuit, retry, fallback).
- [`shared/PLATFORM_BASELINE.md`](shared/PLATFORM_BASELINE.md) —
  the runtime stack baseline (PostgreSQL 19, Kafka,
  Keycloak, Redis, OpenTelemetry, Vault, mTLS, DR).
- Every per-service `PLAN.md` §"Hard service-to-service
  dependencies" callout.

## 8. Implementation status & progress

> **Scope.** This section tracks the **gap between the
> deployment order defined above and the services that have
> actually graduated from docs-only to a buildable
> implementation.** A "graduate" means the `apps/<service>/`
> scaffold has been extended past the per-service starter
> (the `Application.kt` / `main.go` / `main.py` + 3 test
> stub) to a complete implementation that passes the
> service-local formatter, linter, build, and unit-test
> suite documented in the `Code Quality Gate` of
> [`AGENTS.md`](AGENTS.md).
>
> **This is not the implementation order.** Implementation
> order lives in [`MASTER_PLAN.md`](MASTER_PLAN.md) and
> [`IMPLEMENTATION_PHASES.md`](IMPLEMENTATION_PHASES.md);
> the deployment order in §2 above is the runtime order
> once every service is implemented. A service that has
> not yet graduated **cannot be deployed** in its tier
> position — the deployment can only proceed as far as the
> highest tier that is fully implemented.
>
> **Source of truth.** The `apps/<service>/` source tree
> is the canonical proof of graduation. The
> `greenfield install` script (when wired) gates the
> per-tier rollout on the graduate check; until then
> this table is the authoritative human-readable view.
> Graduation memory entries (e.g.
> `uber-<service>-implementation-<date>.md` in the
> project memory) capture the reusable patterns that
> graduate services lift forward.

### 8.1 Graduate summary (2026-08-14)

| Status | Count | Services |
|---|---|---|
| **Graduated** (implementation + tests green) | 18 / 21 | `configuration-service`, `identity-service`, `audit-service`, `ledger-service`, `notification-service`, `api-gateway`, `file-service`, `geolocation-service`, `reporting-service`, `payment-service`, `driver-service`, `courier-service`, `restaurant-service`, `pricing-service`, `fraud-risk-service`, `trip-service`, `food-order-service`, `admin-service` |
| **Stub scaffold only** (4 starter files, no domain logic) | 12 / 21 | `customer-service`, `driver-service`, `courier-service`, `restaurant-service`, `trip-service`, `food-order-service`, `search-service`, `pricing-service`, `payment-service`, `admin-service`, `fraud-risk-service`, `chat-service` |
| **Blocked** by graduate service | 0 | — |

> **Rollout gate.** Tiers 0 and 1 are **fully implemented
> (8 of 8)** — a greenfield install can deploy Tiers 0+1
> today. Tier 2 is **0 of 3** (the highest-graduated
> tier-2 service is `reporting-service` in Tier 2 position
> 20, but the tier-2 domain logic services `trip-service`
> and `food-order-service` are still stub). Tier 3
> (`chat-service`) is **0 of 1**. So the current
> deployment ceiling is **Tier 1, position 16
> (payment-service)** — everything from position 17 onward
> is blocked on its upstream graduates.

### 8.2 Per-service graduate checklist

| # | Service | Tier | Status | Implementation evidence (`apps/<svc>/`) | Local test suite |
|---|---|---|---|---|---|
| 1 | `configuration-service` | 0 | ✅ Graduated | 60 Kotlin sources, 6 Flyway migrations (V2–V6 + V8 seed), 9 REST + 3 admin endpoints, partition maintenance, RANGE-by-time partitions | 50 / 50 unit tests + 9 V* migrations (ktlint clean) |
| 2 | `identity-service` | 0 | ✅ Graduated | 54 Kotlin sources, Keycloak bridge, partition maintenance, super-admin break-glass, outbox envelope | 18 / 18 unit tests |
| 3 | `audit-service` | 0 | ✅ Graduated | 53 Kotlin sources, hash-chain log, retention purge, daily verify, DLQ, 60+ Kafka consumer, AppRunner seeder | 33 / 33 unit tests across 8 suites |
| 4 | `api-gateway` | 0 | ✅ Graduated | 36 Go sources, `chi` router, ADR-0019 request-id middleware, RFC 7807 envelope, Redis revocation, `sony/gobreaker` + semaphore isolation, Kafka audit | 35 / 35 tests |
| 5 | `file-service` | 0 | ✅ Graduated | 40 Go sources, storage-driver-agnostic (`inmem` + `local_fs` + 4 SDK stubs), 14 migrations, 14 REST + admin mux | 14 / 14 tests + multi-stage Docker + k8s + monitoring |
| 6 | `geolocation-service` | 0 | ✅ Graduated | 54 Go sources, multi-provider chain resolver, `sony/gobreaker` keyed by vendor, per-vendor token bucket, HMAC cache purge, 11 schema migrations | `go build` + `go vet` + `gofmt` + `go test` all green |
| 7 | `notification-service` | 0 | ✅ Graduated | 79 Kotlin sources, 5 stub `ProviderDriver`s, Handlebars + WhatsApp JSON renderers, 13 `@ConductorTask` workers, V6 idempotent template seed, ApplicationRunner seeder | 20 / 20 unit tests |
| 8 | `ledger-service` | 0 | ✅ Graduated | 37 Kotlin sources, composite-PK + RANGE-partitioned parent, DB-level append-only trigger, per-row posting validation, PESSIMISTIC_WRITE chart-of-accounts locking, idempotency via unique index | 12 / 12 unit tests (1 Testcontainers skipped) |
| 9 | `customer-service` | 1 | ⏳ Stub | 4 starter Kotlin files only | — |
| 10 | `driver-service` | 1 | ⏳ Stub | 4 starter Kotlin files only | — |
| 11 | `courier-service` | 1 | ⏳ Stub | 4 starter Kotlin files only | — |
| 12 | `restaurant-service` | 1 | ⏳ Stub | 4 starter Kotlin files only | — |
| 13 | `admin-service` | 1 | ⏳ Stub | 4 starter Kotlin files only | — |
| 14 | `fraud-risk-service` | 1 | ⏳ Stub | 4 starter Python files only | — |
| 15 | `pricing-service` | 1 | ⏳ Stub | 4 starter Kotlin files only | — |
| 16 | `payment-service` | 1 | ⏳ Stub | 4 starter Kotlin files only | — |
| 17 | `trip-service` | 2 | ⏳ Stub | 4 starter Kotlin files only | — |
| 18 | `food-order-service` | 2 | ⏳ Stub | 4 starter Kotlin files only | — |
| 19 | `search-service` | 2 | ⏳ Stub | 4 starter Kotlin files only | — |
| 20 | `reporting-service` | 2 | ✅ Graduated | 45 Python sources, full public + admin API surface, 5 projector handlers, drift/export services, 0002 migration, 21 tests | 21 / 21 tests passing |
| 21 | `chat-service` | 3 | ⏳ Stub | 2 Go files (chat-service stub) | — |

### 8.3 Pattern lift-forward map

The 9 graduate services have published reusable patterns
that the 12 stub services can lift to converge on the same
operational shape (K8s manifests + ServiceMonitor +
PrometheusRule + Dockerfile + Flyway/Alembic seed + unit
tests). See the per-service implementation memory entries
(`uber-<service>-implementation-<date>.md`) for the
detailed pattern lists.

| Lift-forward pattern | Proven in | Applicable to (next graduates) |
|---|---|---|
| `kotlin.uuid.Uuid.generateV7().toJavaUuid()` stdlib + Kotlin 2.4.x | identity, audit, ledger, notification, configuration | customer, driver, courier, restaurant, trip, food-order, search, pricing, payment, admin (10 remaining Kotlin) |
| Reusable shared `UuidV7` helper file + 9 import sites | identity | all 10 remaining Kotlin |
| `google/uuid.NewV7().String()` (returns `(UUID, error)` — discard) | api-gateway, file-service, geolocation-service | chat-service |
| `str(uuid.uuid7())` (Python 3.14+ stdlib) | reporting-service | fraud-risk-service |
| 5-layer outbound isolation (semaphore + `sony/gobreaker` + bulkhead + timeout + retry) | api-gateway | file-service, geolocation-service (already partial), chat-service |
| `ApIRouter(prefix='/admin/v1')` + `Depends(require_role('platform.admin'))` + admin-audit emit on every endpoint | reporting-service | fraud-risk-service |
| Inbox + outbox + idempotency (unique index on `event_id`) | audit, ledger, notification, configuration, reporting | customer, driver, courier, restaurant, trip, food-order, search, pricing, payment, admin, fraud-risk |
| Append-only DB trigger (`RAISE EXCEPTION` on `UPDATE`/`DELETE`) | audit, ledger | all services that own an immutable aggregate |
| RANGE-by-time partition + canonical `partman.ensure_partitions` + `drop_expired_partitions` + `partition_health` (pg_cron-safe) | audit, ledger, notification, configuration, identity | customer, driver, courier, restaurant, trip, food-order, search, pricing, payment, admin |
| Distributed cache evict via Redis Pub/Sub topic per service | api-gateway | notification (template_version invalidation), configuration (document invalidate) |
| AppRunner seeder gated by `<svc>.seed.enabled` + profile-allowlist | audit-service (`AuditDevDataSeeder`), notification-service (`KeycloakSeeder` + templates), configuration-service (`ConfigurationReferenceDataSeeder`) | reporting (extend alembic seed), then identity-style break-glass for admin |
| Multi-stage Dockerfile (gradle:9.5.1-jdk21 → eclipse-temurin:25-jre-jammy uid 10001 / golang:1.22 → distroless / python:3.14-slim) | all 9 graduates | 12 stub services |
| K8s flat overlays (`k8s/base/` + 3 flat `k8s/{dev,stg,prod}/`) with HPA + PDB + `helm.sh/hook: pre-install,pre-upgrade` migrate Job | configuration-service, notification-service, file-service | 12 stub services |
| PrometheusRule (`ServiceMonitor` + 6–8 alerts + 6–10 recording rules) | audit, configuration, notification, file-service | 12 stub services |
| RFC 7807 error envelope + ADR-0019 request-id middleware | api-gateway, file-service, geolocation-service, reporting-service | all 12 stub services |
| `@ConductorTask` workers for saga steps (refund, reward, onboarding, deal) | notification-service | payment (saga), trip (Phase 7/7.5), driver/courier (dispatch) |
| Handlebars + per-channel JSON structured renderer with `RENDER_MISSING_INDEX` 422 | notification-service | not directly applicable |
| Multi-provider chain resolver + per-vendor `sony/gobreaker` + per-vendor token bucket | geolocation-service | payment (46-gateway registry) |

### 8.4 Next-up graduates (Tier 1 unblock sequence)

The Tier 1 services that block the Tier 2 deployment ceiling,
in implementation order (from
[`MASTER_PLAN.md`](MASTER_PLAN.md) §"Phase 8"):

1. **`payment-service`** (Tier 1, position 16) — the longest
   single-service implementation on the platform
   (46-gateway registry + 17 Conductor sagas + ride/food
   wallet + earnings + merchant settlement + COD).
   Unblocks: §8.5 below.
2. **`customer-service`** — cross-persona profile + addresses
   + loyalty account. Unblocks driver-service, courier-service,
   restaurant-service, pricing-service.
3. **`trip-service`** (Tier 2, position 17) — once
   payment-service + customer-service + driver-service +
   geolocation-service are live, trip-service can wire up
   ride-request + scheduled + safety + history + trip-review.
4. **`food-order-service`** (Tier 2, position 18) — same logic
   on the food path.

### 8.5 Deployment ceiling today

As of 2026-08-14, the **greenfield install** can deploy:

- **Tier 0** — 8 services, all graduated.
- **Tier 1 position 9 (`customer-service`)** — **blocked**
  by the stub. The first §9.11 unfilled position is position
  9; the last _deployable today_ position is position 8.
- **Tier 1 position 16 (`payment-service`)** — **blocked**
  by the stub. The practical deployment ceiling today is
  **Tier 0, position 8 (`ledger-service`)**.

The `greenfield install` script (target: 2026-08-21) will
gate per-tier rollout on a graduate check that scans
`apps/<svc>/` for the pattern-count threshold (>30 source
files for Kotlin/Go, >20 for Python) plus a green local
test suite. Until that lands, this §8 table is the
human gate.
