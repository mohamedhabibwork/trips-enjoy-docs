# eta-routing-service — Business Requirements Document

## 1. Document Purpose

Read by product, engineering, and operations to align on what
`eta-routing-service` does. The adapter hides a complex third party
(the map provider) behind a stable internal API; this document
defines the rules for that adapter.

## 2. Business Context

Every ride-hailing and food-delivery feature that needs an ETA, a
route, or a distance talks to the map provider through this
service. Without it, every service would have its own integration,
its own caching strategy, and its own failover — and the platform
would be at the mercy of the provider's API quirks and outages.

## 3. Objectives

| ID | Objective | Metric |
|----|-----------|--------|
| BR--001 | Be the single point of integration with the map provider | 100% of ETA / route reads go through us |
| BR--002 | Hide provider quirks from callers | stable response shape |
| BR--003 | Fail over to a secondary provider on primary failure | `eta_failover_total` rate |
| BR--004 | Be fast for repeated requests | `eta_cache_hit_ratio` ≥ 80% |
| BR--005 | Be honest about traffic | refresh traffic-sensitive caches every 60s |

## 4. Stakeholders

| Stakeholder | Role | Interest |
|-------------|------|----------|
| Product (Rides) | owner | ETA accuracy for customers |
| Product (Food) | owner | ETA accuracy for delivery |
| Engineering (Rides) | builder | latency, correctness |
| Operations | reviewer | provider health, failover |

## 5. Actors / Personas

- **`pricing-service`** — needs ETA to compute a quote.
- **`dispatch-service`** — needs ETA to rank candidates.
- **`trip-service`** — needs the actual route for the final fare.
- **`delivery-service`** — needs the route for delivery ETA.
- **`ride-request-service`** — needs ETA for the customer-facing
  estimate.

## 6. Business Capabilities

- Compute ETA (point-to-point, point-to-zone, zone-to-zone).
- Compute a route polyline.
- Return alternative routes.
- Cache recent results.
- Fail over to a secondary provider.

## 7. Business Requirements

| ID | Requirement | Priority | Source |
|----|-------------|----------|--------|
| BR--010 | The service MUST call the map provider via HTTPS, with an API key from Vault. | MUST | Security |
| BR--011 | The service MUST cache ETA results for at most 60 seconds (traffic-sensitive). | MUST | Product |
| BR--012 | The service MUST cache route shapes for at most 300 seconds (less traffic-sensitive). | MUST | Product |
| BR--013 | The service MUST fail over to the secondary provider on primary failure. | MUST | Operations |
| BR--014 | The service MUST emit `eta.computed.v1` and `route.computed.v1` for analytics. | MUST | Analytics |
| BR--015 | The service MUST NOT log precise lat/lon in plain text. | MUST | Privacy |
| BR--016 | The service MUST support surge-aware traffic multipliers (via configuration). | SHOULD | Product |
| BR--017 | The service MUST support per-city provider configuration (e.g. HERE in EU, Google in MENA). | MUST | Operations |
| BR--018 | The service MUST return a stable response shape regardless of provider. | MUST | Engineering |
| BR--019 | The service MUST record an audit event for every provider failover. | MUST | Compliance |

## 8. Business Rules

| ID | Rule | Notes |
|----|------|-------|
| BR--030 | The cache key is a hash of the request (lat/lon rounded, time bucket, mode). | Avoids PII in the cache. |
| BR--031 | The provider failover is silent (the caller does not see the difference). | Logging only. |
| BR--032 | The cache TTL is shortened during surge (more frequent traffic refresh). | Optional, configurable. |
| BR--033 | The service does not store the route polyline beyond the cache TTL. | No long-term storage. |

## 9. Assumptions

- The map provider's API is rate-limited; we cache to stay within
  the quota.
- The provider's response shape is documented; we normalise it to
  our internal shape.

## 10. Constraints

- The service is stateless (apart from the cache).
- All caching is best-effort; a cache miss falls through to the
  provider.

## 11. Dependencies

| Dependency | Type | Notes |
|------------|------|-------|
| Map provider (primary) | external | HERE or Google Maps |
| Map provider (failover) | external | alternate |
| `configuration-service` | service | per-city provider config |
| `analytics-service` | consumer | `eta.computed.v1`, `route.computed.v1` |

## 12. Business Workflows

- **ETA compute (cache hit)** — see `WORKFLOWS.md`.
- **ETA compute (cache miss → provider)** — see `WORKFLOWS.md`.
- **Provider failover** — see `WORKFLOWS.md`.
- **Route compute** — see `WORKFLOWS.md`.

## 13. Exception Workflows

- Provider timeout: retry once; on persistent failure, fail over.
- Both providers down: return 503 with `code: DEPENDENCY_TIMEOUT`;
  the caller decides what to do (e.g. `pricing-service` returns a
  cached quote).
- Invalid request: 400 `VALIDATION_FAILED`.

## 14. Success Criteria

- Provider failures do not cascade into caller failures (failover
  works).
- The cache hit ratio is ≥ 80% on hot paths.
- ETAs are accurate to within 10% of the actual arrival.

## 15. KPIs

| KPI | Target | Measurement |
|-----|--------|-------------|
| Cache hit ratio | ≥ 80% | `eta_cache_hit_ratio` |
| ETA P99 latency | ≤ 300ms | `eta_compute_seconds` |
| Provider failover rate | < 1% | `eta_failover_total` / `eta_compute_total` |

## 16. Acceptance Criteria

- A request for a known ETA / route returns from the cache within
  50ms.
- A cache miss falls through to the provider and returns within
  800ms.
- A primary-provider failure triggers a failover within 5 seconds.
- The response shape is identical regardless of provider.

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
- [`../../README.md`](../../README.md) — services overview (the catalog of all 58 services)
- [`../../../main.md`](../../../main.md) — top-level platform specification (architecture, Keycloak, PostgreSQL 18, messaging, observability baseline)

