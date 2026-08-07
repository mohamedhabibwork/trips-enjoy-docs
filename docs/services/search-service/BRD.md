# search-service — Business Requirements Document

## 1. Document Purpose

This document is the authoritative statement of *what*
`search-service` must do for the business. It is read by
product management (search UX), the platform architecture
team (vendor abstraction), the data team (relevance
tuning), the service's engineering team, and any auditor
verifying the platform's search practices. It informs the
relevance model, the index strategy, the reindex tooling,
and the multi-vertical expansion.

## 2. Business Context

The platform's customer-facing app needs to search
restaurants and menu items. The merchant portal needs to
search its own menu. The support console needs to search
tickets. Each of these is a different "vertical" with
different fields, different relevance signals, and
different SLAs.

`search-service` is the platform's search abstraction.
Without it, each consumer would either embed an
OpenSearch client (coupling) or implement its own
search on top of PostgreSQL (poor performance, poor
relevance). The service indexes domain events into
OpenSearch, exposes a stable search API, and supports
relevance tuning per vertical.

## 3. Objectives

| ID | Objective | Metric |
|----|-----------|--------|
| BR--001 | Provide a stable, vendor-neutral search API for at least restaurants, menu items, merchants, and support tickets | 100% of search flows through this service |
| BR--002 | P99 search latency ≤ 300ms (cache miss), ≤ 100ms (cache hit) | API P99 measured |
| BR--003 | Index latency (event → searchable) ≤ 5s P95 | measured |
| BR--004 | Support multi-locale (en, ar) with locale-aware relevance | all verticals have en + ar variants |
| BR--005 | Support reindex without downtime (zero-downtime alias swap) | reindex completes with no search outage |
| BR--006 | Provide per-vertical relevance tuning via configuration | relevance config hot-reloadable |
| BR--007 | Provide query analytics (top queries, zero-result queries) for product and ops | dashboard in `reporting-service` |

## 4. Stakeholders

| Stakeholder | Role | Interest |
|-------------|------|----------|
| Product (Ride / Food) | consumer | fast, relevant restaurant / menu search |
| Merchant / Restaurant ops | consumer | search own menu, support tickets |
| Customer Support | consumer | search tickets |
| Data team | owner | relevance tuning, query analytics |
| Platform Architecture | owner | vendor abstraction, SLO |
| Engineering (consumer services) | consumer | "index this field" |

## 5. Actors / Personas

- **Customer (rider / diner)**: searches for restaurants
  and menu items.
- **Merchant / Restaurant staff**: searches own menu
  (admin); searches support tickets.
- **Support agent**: searches support tickets.
- **Data analyst**: tunes relevance, analyzes query
  patterns.
- **Operations (admin)**: triggers reindex, manages
  relevance config.

## 6. Business Capabilities

- **Indexing** (consume events, project to index).
- **Search** (full-text, filter, sort, pagination).
- **Suggest / autocomplete**.
- **Multi-locale relevance**.
- **Reindex** (zero-downtime alias swap).
- **Query analytics**.

## 7. Business Requirements

| ID | Requirement | Priority | Source |
|----|-------------|----------|--------|
| BR--010 | The service MUST expose a stable search API per vertical (`/v1/search/{vertical}`). | MUST | product |
| BR--011 | The service MUST consume `restaurant.updated.v1`, `menu.updated.v1`, `merchant.updated.v1` and project to the index within 5s P95. | MUST | product |
| BR--012 | The service MUST support multi-locale (en, ar) with locale-aware relevance. | MUST | i18n |
| BR--013 | The service MUST support zero-downtime reindex via alias swap. | MUST | operations |
| BR--014 | The service MUST support per-vertical relevance tuning via configuration. | MUST | data team |
| BR--015 | The service MUST support filter, sort, and pagination on every search. | MUST | product |
| BR--016 | The service MUST provide autocomplete (`/v1/search/suggest/{vertical}`). | MUST | product |
| BR--017 | The service MUST emit `search.query.executed.v1` for analytics. | MUST | analytics |
| BR--018 | The service MUST support per-tenant index isolation where applicable. | SHOULD | multi-tenant |
| BR--019 | The service MUST log every query (with `query_hash`, not raw query) and retain for 30 days. | MUST | analytics, audit |
| BR--020 | The service MUST NOT index PII fields (e.g. user phone, email) unless explicitly required. | MUST | privacy |
| BR--021 | The service MUST support geo filters (lat, lon, radius_m) on the restaurant and menu item verticals. | MUST | product |

## 8. Business Rules

| ID | Rule | Notes |
|----|------|-------|
| BR--020 | A query log entry stores the query hash, not the raw query, for privacy. | raw query is not retained beyond the request scope |
| BR--021 | Relevance config changes are hot-reloadable on `configuration.updated.v1`. | |
| BR--022 | Reindex is atomic from the caller's perspective: the alias is swapped; the old index is deleted after a grace period. | |
| BR--023 | A reindex that fails mid-way is rolled back: the old alias is restored. | |
| BR--024 | The service MUST NOT serve a search if the index is unhealthy (return 503). | |

## 9. Assumptions

- The source services (`restaurant-service`,
  ``restaurant-service` (menu)`, ``restaurant-service` (merchant)`) emit reliable
  events.
- OpenSearch is reliable; we maintain replicas and
  snapshots.
- The volume of searches is bursty (e.g. during peak
  hours) but bounded; we can scale horizontally.
- i18n is a hard requirement (en + ar from day one).

## 10. Constraints

- **Latency**: P99 ≤ 300ms (cache miss).
- **Index freshness**: ≤ 5s P95 from event to searchable.
- **Availability**: 99.9% (T2); reindex is zero-downtime.
- **Cost**: OpenSearch is a significant cost line; we
  optimize index size and retention.

## 11. Dependencies

| Dependency | Type | Notes |
|------------|------|-------|
| OpenSearch | external | search engine |
| `restaurant-service` | producer | `restaurant.updated.v1` |
| ``restaurant-service` (menu)` | producer | `menu.updated.v1` |
| ``restaurant-service` (merchant)` | producer | `merchant.updated.v1` |
| ``geolocation-service` (zones)` | producer | `zone.updated.v1` (for geo filter) |
| `configuration-service` | service | relevance config |
| ``configuration-service` (flags)` | service | A/B routing |
| `audit-service` | consumer | reads reindex events |
| ``reporting-service` (data lake)` | consumer | reads query events |
| PostgreSQL 18 | infra | core storage |
| Redis 7 | infra | query cache |
| Kafka | infra | events |
| Vault | infra | OpenSearch credentials |

## 12. Business Workflows

- **Index a restaurant update** — see `WORKFLOWS.md` 1.
- **Search a restaurant** — see `WORKFLOWS.md` 2.
- **Reindex a vertical** — see `WORKFLOWS.md` 3.
- **Update relevance config** — see `WORKFLOWS.md` 4.
- **Suggest / autocomplete** — see `WORKFLOWS.md` 5.

## 13. Exception Workflows

- **OpenSearch down**: the service returns 503
  `CIRCUIT_OPEN`; the caller is expected to retry.
- **Reindex fails mid-way**: the old alias is restored;
  an alert fires.
- **Index out of sync with source**: a daily
  reconciliation job compares the index to the source
  and flags drift.

## 14. Success Criteria

- 100% of search flows through this service.
- P99 search ≤ 300ms (cache miss) in production.
- Index freshness ≤ 5s P95.
- 100% of verticals have en + ar relevance.
- Zero-downtime reindex in staging.
- Zero-result rate trending down month over month
  (relevance tuning).

## 15. KPIs

| KPI | Target | Measurement |
|-----|--------|-------------|
| Search P99 (cache miss) | ≤ 300ms | `search_query_seconds` P99 |
| Search P99 (cache hit) | ≤ 100ms | same |
| Index freshness P95 | ≤ 5s | event → searchable |
| Zero-result rate | < 5% | `search_zero_result_queries_total / search_queries_total` |
| Reindex duration | < 1h for 1M docs | `search_reindex_seconds` |
| Query cache hit ratio | ≥ 0.30 | `search_cache_hits / search_queries_total` |

## 16. Acceptance Criteria

- All 12 business requirements implemented and verified by
  automated tests.
- A `restaurant.updated.v1` event in staging results in
  the restaurant being searchable within 5s.
- A search for a known restaurant in staging returns
  within 300ms (cache miss) or 100ms (cache hit).
- A reindex of 10k restaurants in staging completes with
  no search outage (alias swap).
- A relevance config change in staging is hot-reloaded
  within 1 minute.
- An autocomplete query in staging returns within
  100ms.

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

### Platform-wide

- [`../../shared/README.md`](../../shared/README.md) — `platform-spring-boot-starter` shared library (the single source of cross-cutting code for all Spring Boot services in the platform)
- [`../RECOMMENDATIONS.md`](../RECOMMENDATIONS.md) — platform-wide technology map (language, framework, version baseline, admin/RBAC pattern)
- [`../../README.md`](../../README.md) — services overview (the catalog of all 20 services)
- [`../../../main.md`](../../../main.md) — top-level platform specification (architecture, Keycloak, PostgreSQL 18, messaging, observability baseline)

