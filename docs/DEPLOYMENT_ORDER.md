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
