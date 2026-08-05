# search-service

## 1. Purpose

`search-service` is the platform's **search index
coordination authority**. It owns the search index for
multiple verticals (restaurants, menu items, support
tickets, etc.) — a read model that is queried by the
customer app, the merchant portal, and the support
console. The service consumes domain events from the
owning services and projects them into an OpenSearch
index; it exposes a stable search API with a query DSL,
relevance tuning, and reindex tooling.

## 2. Bounded Context

**Bounded Context**: *Search index coordination*.

In scope:

- Search index per vertical (restaurants, menu items,
  support tickets, etc.).
- Indexing pipeline (consume domain events, project to
  the index).
- Query DSL (full-text, filter, sort, pagination).
- Relevance tuning (per-vertical, per-locale).
- Reindex tooling (full reindex from source of truth).
- Multi-tenant index isolation (where applicable).
- Search analytics (top queries, zero-result queries).

Out of scope:

- The data itself — owned by the respective services
  (`restaurant-service`, `menu-service`, etc.).
- The app's UI — the search results are returned to the
  app, which renders them.
- Geospatial search (handled by `geolocation-service` for
  geocode / ETA; this service supports basic geo filters
  on the index).

## 3. Responsibilities

- Maintain OpenSearch indices (one per vertical).
- Consume `restaurant.updated.v1`, `menu.updated.v1`,
  `merchant.updated.v1`, and project to the index.
- Provide `POST /v1/search/{vertical}` accepting a query
  and returning ranked results.
- Provide admin operations (`POST /v1/admin/reindex`,
  `POST /v1/admin/relevance/update`).
- Support multi-locale (en, ar) relevance tuning.
- Emit `search.query.executed.v1` for analytics.
- Handle backfill (initial bulk load from the source of
  truth).
- Handle reindex (zero-downtime index swap with a new
  alias).

## 4. Explicitly NOT Owned

- **The data** — owned by `restaurant-service`,
  `menu-service`, `merchant-service`, etc.
- **The app's UI** — the search service returns JSON
  results; the app renders them.
- **Geospatial queries** (point-in-zone, ETA) — owned by
  `geolocation-service`.
- **Full-text search of support tickets** (text body) —
  this service supports basic metadata search; full-text
  ticket search is via the support console's own search.

## 5. Actors

| Actor | Type | Access |
|-------|------|--------|
| Customer app (rider / diner) | system | search restaurants, menu items |
| Merchant / Restaurant portal | system | search own menu, search support tickets |
| Support console | system | search tickets |
| Admin console | system | admin operations (reindex, relevance) |
| `restaurant-service` | system | producer of `restaurant.updated.v1` |
| `menu-service` | system | producer of `menu.updated.v1` |
| `merchant-service` | system | producer of `merchant.updated.v1` |
| `zone-service` | system | producer of `zone.updated.v1` (for geo filter) |
| `analytics-service` | consumer | reads `search.query.executed.v1` |
| `audit-service` | consumer | reads `search.reindex.started.v1`, `search.reindex.completed.v1` |

## 6. Dependencies

### Synchronous (REST)

- **OpenSearch cluster** — index, search, delete — SLO
  99.9% — circuit breaker: yes (per index).
- `configuration-service` — read relevance config, locale
  config — SLO 99.95% — circuit breaker: yes.
- `feature-flag-service` — read A/B routing — SLO 99.9% —
  circuit breaker: yes.
- `restaurant-service` — backfill source (REST) — SLO
  99.95% — circuit breaker: yes.
- `menu-service` — backfill source (REST) — SLO 99.95% —
  circuit breaker: yes.

### Asynchronous (events consumed)

- `restaurant.updated.v1` from `restaurant-service` — index
  the restaurant.
- `menu.updated.v1` from `menu-service` — index the menu
  item.
- `merchant.updated.v1` from `merchant-service` — index
  the merchant.
- `zone.updated.v1` from `zone-service` — refresh geo
  filter.
- `configuration.updated.v1` from `configuration-service` —
  relevance config changed.
- `feature_flag.updated.v1` from `feature-flag-service` —
  A/B routing changed.

### Asynchronous (events produced)

- `search.query.executed.v1` — every search (for analytics).
- `search.reindex.started.v1` / `search.reindex.completed.v1`
  — every reindex (for audit).

## 7. Technology Assumptions

- Runtime: Java 21 (Spring Boot) — strong OpenSearch
  client, performance.
- Database: PostgreSQL 18 in schema `search` (reindex
  jobs, query log, A/B config).
- Search engine: OpenSearch 2.x (one cluster, multiple
  indices).
- Cache: Redis 7 (per-service) for hot query cache and
  A/B config.
- Event broker: Kafka.

## 8. Database Ownership

- Schema: `search`
- Migrations: `services/search-service/migrations/`
  (versioned, forward-only, golang-migrate).
- Soft delete: no (the index is the source of truth for
  search; the underlying service owns the data).
- Partitioning: no (reindex jobs and query log are
  small).

## 9. API Overview

| Method | URI | Auth | Purpose |
|--------|-----|------|---------|
| POST | /v1/search/restaurants | bearer | search restaurants |
| POST | /v1/search/menu-items | bearer | search menu items |
| POST | /v1/search/merchants | bearer | search merchants |
| POST | /v1/search/tickets | bearer (agent) | search support tickets |
| GET | /v1/search/suggest/{vertical} | bearer | autocomplete |
| POST | /v1/admin/reindex | admin | trigger reindex |
| GET | /v1/admin/reindex/{id} | admin | reindex status |
| POST | /v1/admin/relevance/update | admin | update relevance config |
| GET | /v1/admin/queries/top | admin | top queries |

(Full contracts in INTEGRATION.md.)

## 10. Events Produced

| Event | Trigger | Consumers |
|-------|---------|-----------|
| `search.query.executed.v1` | every search | `analytics-service` |
| `search.reindex.started.v1` | reindex begins | `audit-service` |
| `search.reindex.completed.v1` | reindex ends | `audit-service` |

## 11. Events Consumed

| Event | Producer | Reason | Handler |
|-------|----------|--------|---------|
| `restaurant.updated.v1` | `restaurant-service` | project to index | upsert restaurant doc |
| `menu.updated.v1` | `menu-service` | project to index | upsert menu item doc |
| `merchant.updated.v1` | `merchant-service` | project to index | upsert merchant doc |
| `zone.updated.v1` | `zone-service` | refresh geo filter | update zone metadata in index |
| `configuration.updated.v1` | `configuration-service` | relevance / locale | reload config |
| `feature_flag.updated.v1` | `feature-flag-service` | A/B routing | reload A/B config |

## 12. External Integrations

- **OpenSearch cluster** — the search index.
- **Vault** — OpenSearch credentials.

## 13. Configuration

| Key | Type | Source | Notes |
|-----|------|--------|-------|
| `search.index.restaurants.alias` | string | configuration-service | default `restaurants_v1` |
| `search.index.menu_items.alias` | string | configuration-service | default `menu_items_v1` |
| `search.index.merchants.alias` | string | configuration-service | default `merchants_v1` |
| `search.index.tickets.alias` | string | configuration-service | default `tickets_v1` |
| `search.relevance.{vertical}.{field}.boost` | number | configuration-service | e.g. `restaurants.name.boost=2.0` |
| `search.locale.{vertical}.supported` | array | configuration-service | e.g. `["en", "ar"]` |
| `search.cache.query.ttl_seconds` | int | configuration-service | default 60 |
| `search.query_log.retention_days` | int | configuration-service | default 30 |

## 14. Security

- **AuthN**: bearer JWT (validated at gateway); internal
  calls use client-credentials tokens.
- **AuthZ**: user can search public verticals
  (restaurants, menu items); agent can search support
  tickets; admin for reindex / relevance.
- **Secrets**: OpenSearch credentials in Vault; rotated
  quarterly.
- **PII**: search queries may contain PII (e.g. a user
  searching for their own name in tickets); the query log
  is purged after 30 days.

## 15. Observability

- **Logs**: JSON to stdout; fields: `correlation_id`, `trace_id`,
  `vertical`, `query_hash`, `result_count`, `latency_ms`.
- **Metrics**: RED (per route) + business:
  `search_queries_total{vertical, status}`,
  `search_query_seconds{vertical}` (histogram),
  `search_results_count{vertical}` (histogram),
  `search_zero_result_queries_total{vertical}`,
  `search_reindex_total{vertical, status}`,
  `search_index_size_bytes{vertical}` (gauge).
- **Traces**: OpenTelemetry; root span per search; OpenSearch
  call as child span.
- **Health**: `/health`, `/ready` (DB + Redis + Kafka +
  OpenSearch reachable), `/started`.

## 16. Scalability

- **Replicas**: default 6.
- **HPA**: CPU 60%, custom metric
  `search_queries_per_second > 200` per replica.
- **Hot path**: `POST /v1/search/{vertical}`. P99 ≤ 100ms
  (cache hit), ≤ 300ms (cache miss).

## 17. Local Development

- `docker compose up search-service` brings up the
  service, its DB, Redis, Kafka, and a local OpenSearch
  container.
- Seed: 1000 restaurants, 5000 menu items, 200 merchants
  from a fixture file.
- Tests: unit, integration (with real OpenSearch), query
  DSL fuzz tests.

## 18. Deployment

- **Image**: `ghcr.io/uber/search-service:<git-sha>`.
- **Replicas**: 6 in production.
- **Resource limits**: see deployment-arch (`cpu: 1`,
  `memory: 1Gi` requests; 2 CPU, 2Gi limits — JVM).
- **Migrations**: run as a Kubernetes Job on deploy.
- **OpenSearch cluster**: managed (Elastic Cloud or
  self-hosted on K8s with 3 masters + 3 data nodes).


---

## See also

### Sibling docs for this service

- [`README.md`](./README.md) — purpose, bounded context, responsibilities
- [`BRD.md`](./BRD.md) — business requirements
- [`SRS.md`](./SRS.md) — functional + non-functional requirements
- [`ERD.md`](./ERD.md) — data model (entities, relationships)
- [`INTEGRATION.md`](./INTEGRATION.md) — inter-service contracts (APIs, events, sagas)
- [`WORKFLOWS.md`](./WORKFLOWS.md) — operational workflows (happy paths, failure modes)
- [`TECH.md`](./TECH.md) — technology profile (runtime, libraries, data layer, admin endpoints, RBAC)

### Related services

- **Depends on**: [`analytics-service`](../analytics-service/README.md), [`audit-service`](../audit-service/README.md), [`configuration-service`](../configuration-service/README.md), [`feature-flag-service`](../feature-flag-service/README.md), [`geolocation-service`](../geolocation-service/README.md), [`menu-service`](../menu-service/README.md), [`merchant-service`](../merchant-service/README.md), [`restaurant-service`](../restaurant-service/README.md), [`zone-service`](../zone-service/README.md)
- **Depended on by**: [`audit-service`](../audit-service/README.md), [`branch-service`](../branch-service/README.md), [`inventory-service`](../inventory-service/README.md), [`menu-service`](../menu-service/README.md), [`restaurant-service`](../restaurant-service/README.md), [`support-service`](../support-service/README.md), [`zone-service`](../zone-service/README.md)

> Full dependency map in [`../README.md`](../README.md) and [`../../architecture/MICROSERVICES_MAP.md`](../../architecture/MICROSERVICES_MAP.md).

### Platform-wide

- [`../../shared/README.md`](../../shared/README.md) — `platform-spring-boot-starter` shared library (the single source of cross-cutting code for all Spring Boot services in the platform)
- [`../../shared/PLATFORM_BASELINE.md`](../../shared/PLATFORM_BASELINE.md) — single source for PostgreSQL 18, Kafka, Keycloak, Redis, OpenTelemetry, Vault, deployment, DR (do not restate these in this README)
- [`../../architecture/SERVICE_ISOLATION.md`](../../architecture/SERVICE_ISOLATION.md) — **how this service behaves when a downstream is down** (timeout / bulkhead / circuit / retry / fallback, by class: CRITICAL / DEGRADABLE / BEST-EFFORT)
- [`../../architecture/DOWNSTREAM_ERROR_CATALOG.md`](../../architecture/DOWNSTREAM_ERROR_CATALOG.md) — **canonical error-code catalog + propagation rules** (the `downstream` block, forward/translate/degrade/reject)
- [`../RECOMMENDATIONS.md`](../RECOMMENDATIONS.md) — platform-wide technology map (language, framework, version baseline, admin/RBAC pattern)
- [`../../README.md`](../../README.md) — services overview (the catalog of all 58 services)
- [`../../../main.md`](../../../main.md) — top-level platform specification (architecture, Keycloak, PostgreSQL 18, messaging, observability baseline)
- [`../../shared/OSS_DEPENDENCIES.md`](../../shared/OSS_DEPENDENCIES.md) — **open-source dependencies & license attribution** (platform-wide OSS projects + per-language OSS libraries with SPDX IDs; per-service bundle index; license compatibility matrix)

### Workflows this service participates in

- [`../../workflows/FOOD_ORDER_WORKFLOWS.md`](../../workflows/FOOD_ORDER_WORKFLOWS.md) — end-to-end order/delivery flows
