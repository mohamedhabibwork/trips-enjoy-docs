# geolocation-service — Business Requirements Document

## 1. Document Purpose

This document is the authoritative statement of *what* the
`geolocation-service` must do for the business. It is read by
product management, the platform architecture team, the service's
engineering team, and any auditor verifying the platform's
geospatial correctness. It informs roadmap prioritization, SLO
budgeting, vendor-relationship decisions, and cache-policy
choices.

## 2. Business Context

Every customer-facing and operations-facing flow in the platform
needs to turn an address into a coordinate, a coordinate into an
address, two coordinates into an ETA, and a sequence of coordinates
into a route. The platform's product line is a multi-vertical
ride-hailing and food-delivery marketplace operating in many cities
and many languages, on a tight map-vendor bill.

`geolocation-service` exists to:

1. **Decouple** the rest of the platform from a single map vendor
   (vendor risk).
2. **Bound vendor cost** by caching results aggressively and
   coalescing requests.
3. **Bound vendor outages** by absorbing failures behind a circuit
   breaker and a fallback provider.
4. **Stabilize the contract** so that downstream services
   (`ride-request-service`, `delivery-service`, `address-service`,
   `eta-routing-service`) do not have to change when we change
   vendors.
5. **Be a platform asset** for any team that needs geospatial
   answers: fraud velocity, dispatch proximity, support location
   lookup.

Without it, every service that needs a coordinate would either
embed the vendor SDK (coupling) or call the vendor directly
(cost amplification, no caching, no abstraction).

## 3. Objectives

| ID | Objective | Metric |
|----|-----------|--------|
| BR--001 | Reduce map-vendor spend by caching ≥ 90% of geocode lookups in steady state | cache hit ratio (geocode) ≥ 0.90 measured weekly |
| BR--002 | Keep vendor outage invisible to customers when a fallback provider is configured | fallback activation ≤ 5 s after primary circuit opens; P99 user-facing latency within 1.5× of normal |
| BR--003 | Make the platform vendor-portable so a switch takes ≤ 30 days end-to-end | vendor swap completed within 30 days; no downstream service code change required |
| BR--004 | Provide a single, stable, language- and locale-neutral geospatial API across the platform | all ride-hailing, food, and platform services consume only this service for geocode/ETA/route |
| BR--005 | Keep ETA P99 latency under 500ms for cached and 1500ms for cache-miss requests | API P99 latency measured at the edge |
| BR--006 | **Multi-provider chain**: every geospatial capability must route through an ordered provider chain so the platform is never single-vendor-dependent | for any region where the primary vendor is down, the secondary vendor is auto-promoted within 5 s |
| BR--007 | **Per-region provider routing**: support different chains per region so restricted markets (e.g. mainland China) and regulated markets (e.g. EU data-residency) can run without code forks | at least 3 production chains in different regions; restricted-region chains have no Google/Mapbox member |
| BR--008 | **Self-host option**: support running routing and ETA on self-hosted OSRM/Valhalla and geocoding on Nominatim/Pelias/Photon for cost-bounded fallback | at least 1 self-host provider in production fallback chains; self-host fallback engages within 5 s of commercial-vendor failure |
| BR--009 | **Vendor cost ceiling**: the chain resolver must enforce per-provider rate limits and a platform-wide monthly cost ceiling | `vendor_rate_limit_remaining` exposed; monthly cost report generated from `provider_usage_daily` roll-up |
| BR--010 | **Provider-agnostic API contract**: the public API of `geolocation-service` MUST NOT expose vendor-specific response shapes | all responses use the canonical `GeoAddress`/`EtaEstimate`/`Route` types; `vendor_id` returned only as metadata |

## 4. Stakeholders

| Stakeholder | Role | Interest |
|-------------|------|----------|
| Product (Ride) | requester | low-latency ETA, accurate geocode |
| Product (Food) | requester | same, but at food scale (many short deliveries) |
| Platform Architecture | owner | vendor abstraction, cost control, SLO compliance |
| Finance | reviewer | map-vendor bill is a significant platform cost line |
| Procurement | owner | vendor contract, key rotation, multi-vendor strategy |
| Security | reviewer | PII handling for reverse geocoded addresses |
| Customer Support | consumer | "what address did the customer use?" — needs audit trail |
| Fraud / Risk | consumer | last-known city for velocity checks |
| Driver / Courier ops | consumer | accurate pickup / drop ETA |

## 5. Actors / Personas

- **Customer (rider / diner)**: enters an address, sees an ETA. Their
  app calls this service for forward geocode, ETA, and route.
- **Driver / Courier**: opens their app, sees pickup route. Calls
  this service for route + ETA.
- **Merchant staff**: enters a restaurant address during onboarding.
  Calls this service for forward geocode and last-known-city
  validation.
- **Operations agent**: investigates a fraud or safety incident.
  Calls this service for reverse geocode of a coordinate. Access
  is audit-logged.
- **Admin (platform engineer)**: rotates a provider key, forces a
  cache purge after a vendor regression.

## 6. Business Capabilities

- **Forward geocoding** (address → coordinate) with persistence.
- **Reverse geocoding** (coordinate → address) with persistence.
- **ETA estimation** (origin, destination, optional waypoints,
  optional departure time) with persistence.
- **Route computation** (origin, destination, waypoints,
  alternatives) with persistence.
- **Last-known city lookup** for any coordinate.
- **Cache management** (purge, warm, TTL).
- **Provider abstraction** — ordered, per-region, per-capability
  provider chains (commercial + self-host).
- **Per-provider circuit breaker** with rate-limit-aware fallback.
- **Provider health probing** and adaptive circuit-open cooldown.
- **Surge-zone-aware cache invalidation**.

## 7. Business Requirements

| ID | Requirement | Priority | Source |
|----|-------------|----------|--------|
| BR--010 | The service MUST expose a vendor-neutral REST API for geocode, reverse-geocode, ETA, and route. | MUST | platform architecture |
| BR--011 | The service MUST cache every successful vendor response with a configurable per-resource TTL. | MUST | finance, platform architecture |
| BR--012 | The service MUST route each geospatial call through an ordered **provider chain** that may include commercial vendors and self-host adapters. | MUST | vendor-risk, ADR-0011, BR--006 |
| BR--013 | The service MUST support **per-region chains** so different markets can use different providers (incl. omitting Google/Mapbox where restricted). | MUST | BR--007 |
| BR--014 | The service MUST support **self-host adapters** (OSRM/Valhalla for routing, Nominatim/Pelias/Photon for geocoding) as members of a chain. | MUST | BR--008, cost ceiling |
| BR--015 | The service MUST enforce a **per-provider circuit breaker** with configurable failure threshold, cooldown, and half-open probe count. | MUST | resilience |
| BR--016 | The service MUST consume `zone.updated.v1` and invalidate cache entries whose key's bounding box intersects the updated polygon. | MUST | surge-zone correctness |
| BR--017 | The service MUST emit a `geolocation.*.v1` event for every vendor call (cache hit OR miss) for analytics. | SHOULD | analytics, reporting |
| BR--018 | The service MUST enforce per-provider QPS rate limits with a token bucket, and a per-provider monthly cost ceiling. | MUST | vendor contract, BR--009 |
| BR--019 | The service MUST NOT persist addresses longer than the configured TTL (≤ 24h for geocodes; ≤ 5 min for routes; ≤ 1 min for ETAs). | MUST | GDPR / PII minimization |
| BR--020 | The service MUST return cached results when present and valid, even if every provider in the chain is currently unavailable. | MUST | availability |
| BR--021 | The service MUST return a 200 with a structured "approximate" result (centroid of zone) when reverse geocode falls in a remote area no chain member covers, rather than failing. | SHOULD | resilience |
| BR--022 | The service MUST honor a per-user and per-IP rate limit in addition to provider rate limits. | MUST | abuse prevention |
| BR--023 | The service MUST allow admin to force-purge cache entries by city, bounding box, or query fingerprint. | MUST | ops, vendor-regression recovery |
| BR--024 | The service MUST allow admin to view, test, and edit the provider chain for any region at runtime (no redeploy). | MUST | BR--006, vendor incidents |
| BR--025 | The service MUST background-probe every enabled provider at a configurable interval and expose probe results as a metric. | MUST | BR--008, observability |
| BR--026 | The service MUST include `vendor_id`, `chain_position`, and `region` in every analytics event for cost attribution and chain-effectiveness analysis. | MUST | BR--009 |

## 8. Business Rules

| ID | Rule | Notes |
|----|------|-------|
| BR--020 | Cache key for forward geocode = SHA-256(normalized(address) + locale + region) | locale affects translation; region is the city code |
| BR--021 | Cache key for reverse geocode = rounded coordinate to ~10m grid + locale | rounding reduces cache misses by ~30% in dense areas |
| BR--022 | Cache key for ETA = (origin_grid, destination_grid, traffic_bucket, hour_of_day) | hour-of-day is a quantization; TTL 60s default |
| BR--023 | Cache key for route = (origin_grid, destination_grid, hour_of_day) | TTL 300s default |
| BR--024 | Surge-zone update from `zone-service` invalidates all geocode/ETA/route cache entries whose key's bounding box intersects the updated polygon | runs as a background job, max lag 60s |
| BR--025 | A provider circuit opens after ≥ N consecutive 5xx or timeout within 30s (configurable per provider); after cooldown it goes half-open and admits a probe count of `M` requests | per-provider circuit breaker |
| BR--026 | Forward geocoding for an address in a country not served by any member of the resolved chain returns 422 `ADDRESS_UNSUPPORTED_REGION` | does not call any provider |
| BR--027 | Last-known city lookup uses the geocoded admin area levels, not the country | city = admin level 2 typically |
| BR--028 | Provider chain resolution per request: pick the most specific chain for the request's region + capability; chain members are tried in `role` order (`primary` → `secondary` → `fallback` → `static`) | fallback to the `default` chain if no region-specific chain exists |
| BR--029 | A chain member is skipped (without invoking) when its circuit is open OR it does not advertise the requested capability OR its rate-limit bucket is empty | the resolver does not block on these skips; latency is dominated by the first viable member |
| BR--030 | Provider credentials are stored in Vault at `kv/<env>/geolocation/<vendor_id>`; rotated quarterly with a runbook for emergency rotation | applies to commercial vendors; self-host providers use mTLS or a shared key in Vault |

## 9. Assumptions

- **At least two commercial map providers** are commercially available
  per region (Google, Mapbox, HERE), each with REST/HTTPS APIs for
  geocode, route, ETA.
- A **self-host option** (OSRM/Valhalla for routing, Nominatim/Pelias
  for geocoding) is available as a fallback where commercial providers
  are blocked, restricted, or too expensive.
- The vendor's typical response latency is ≤ 400ms at P99.
- Cache storage (PostgreSQL + Redis) is sized for 50M geocode
  entries and 10M ETA entries.
- Map vendor contracts allow a 30-day notice for any pricing or
  schema change.
- Per-region data-residency rules (EU, China, Saudi Arabia, India) are
  satisfied by selecting chains whose members store data only in the
  allowed jurisdictions.

## 10. Constraints

- **Cost**: map-vendor bill is one of the top 5 platform
  operating costs; aggressive caching is non-negotiable.
- **Compliance**: GDPR / PDPL: PII in cached geocoded addresses
  must not be retained longer than 24h.
- **Vendor lock-in**: the service exists to prevent it; switching
  vendors is a 30-day project, not a 6-month one.
- **Performance**: dominant path (forward geocode) P99 ≤ 500ms
  cache hit, ≤ 1500ms cache miss.

## 11. Dependencies

| Dependency | Type | Notes |
|------------|------|-------|
| Commercial map providers (Google, Mapbox, HERE) | provider | one or more primary, plus secondary per region |
| Self-host map providers (OSRM, Valhalla, Nominatim, Pelias, Photon) | provider | fallback / restricted-region option |
| `zone-service` | service | read service-zone metadata; consumed events drive cache invalidation |
| `configuration-service` | service | TTLs, default chain, circuit-breaker parameters, rate limits |
| `feature-flag-service` | service | mock-provider toggle, debug logging, force-static-mode flag |
| `analytics-service` | service | consumes `geolocation.*.v1` events for cost attribution |
| `audit-service` | service | consumes `geolocation.cache.invalidated.v1` and `geolocation.provider_chain.changed.v1` |
| Vault | infra | provider credentials at `kv/<env>/geolocation/<vendor_id>` |
| Redis | infra | hot cache |
| PostgreSQL 18 + PostGIS | infra | persistent cache + audit |

## 12. Business Workflows

- **Forward geocode request flow** — see `WORKFLOWS.md` §1.
- **Surge-zone-driven cache invalidation** — see `WORKFLOWS.md` §2.
- **Vendor fallback activation** — see `WORKFLOWS.md` §3.
- **Admin force-purge** — see `WORKFLOWS.md` §4.

## 13. Exception Workflows

- Vendor 5xx storm → circuit opens → fallback activated.
- Vendor returns no result for a known valid address → cache a
  negative result for 1h to prevent repeated calls.
- `zone.updated.v1` arrives but the cache invalidation job is
  lagging → TTL eventually wins; in the meantime, results are
  best-effort and the surge-zone UI is the source of truth.
- Cache layer is full → LRU eviction; the most recent geocodes
  for the active city are preserved.

## 14. Success Criteria

- Geocode cache hit ratio ≥ 0.90 in steady state across all
  production traffic.
- ETA cache hit ratio ≥ 0.70 in steady state (lower because
  traffic varies hour to hour).
- Map-vendor bill reduces by ≥ 50% within 6 months of the
  service going live.
- Vendor-portability exercise: switch from Google to Mapbox in
  ≤ 30 days without any downstream service change.
- 100% of vendor calls are tracked in `analytics-service` via
  `geolocation.*.v1` events.

## 15. KPIs

| KPI | Target | Measurement |
|-----|--------|-------------|
| Geocode cache hit ratio | ≥ 0.90 | `geolocation_cache_hit_ratio{resource=geocode}` 7-day rolling average |
| ETA cache hit ratio | ≥ 0.70 | `geolocation_cache_hit_ratio{resource=eta}` 7-day rolling average |
| Forward geocode P99 latency (cache hit) | ≤ 500 ms | Prometheus histogram |
| Forward geocode P99 latency (cache miss) | ≤ 1500 ms | Prometheus histogram |
| Vendor circuit-open time | ≤ 1% per month | uptime of primary provider minus platform uptime |
| Cost per 1k geocodes | trending down monthly | finance dashboard |
| Cache invalidation lag after zone update | ≤ 60 s | P95 from event arrival to cache eviction |

## 16. Acceptance Criteria

- All six canonical endpoints (forward geocode, reverse geocode,
  ETA, route, last-known city, admin purge) are implemented,
  documented in `INTEGRATION.md`, and reachable from staging.
- A vendor-swap dry run (config-only switch with a mock provider
  in staging) succeeds without any downstream service change.
- The service survives a 5-minute simulated primary-provider
  outage without returning errors to customers (fallback
  provider serves the load).
- Zone-update-driven cache invalidation completes within 60s
  for any single zone update event.
- All five `geolocation.*.v1` events have at least one consumer
  in the test environment.
- All PII fields (formatted address) are encrypted at rest
  (`pgcrypto`) and have a documented retention ≤ 24h.

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

