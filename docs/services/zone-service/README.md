# zone-service

## 1. Purpose

`zone-service` is the platform's **geospatial zoning authority**.
It owns the canonical cities, service zones, surge zones, and
restricted zones that the rest of the platform uses to decide
*where* a ride can start, *where* a courier can pick up, *how
much* to charge for a trip in a given area, and *whether* a given
location is allowed at all. Polygons are stored as PostGIS
geometries; every change is published as a domain event so
downstream services can react.

## 2. Bounded Context

**Bounded Context**: *Cities / service zones / surge zones /
restricted zones*.

In scope:

- City lifecycle (create, update, suspend, retire).
- Service-zone CRUD (polygon, hours, allowed verticals).
- Surge-zone CRUD (polygon, multiplier, time window).
- Restricted-zone CRUD (polygon, type, reason, time window).
- Polygon validation (no self-intersection, closure, SRID 4326).
- Point-in-zone queries (used by dispatch, pricing, search,
  fraud).
- Zone versioning (every change is versioned; old versions
  retained for audit).

Out of scope:

- Driver / courier live location — `driver-location-service`,
  `courier-tracking-service`.
- Geocode cache and map provider — `geolocation-service`.
- Pricing logic — `pricing-service` reads surge multipliers from
  here.
- Trip / order state — `trip-service`, `food-order-service`.

## 3. Responsibilities

- Maintain `zone.cities`, `zone.zones`, `zone.surge_zones`,
  `zone.restricted_zones`, `zone.zone_hours`.
- Provide `GET /v1/cities`, `GET /v1/cities/{id}`,
  `GET /v1/cities/lookup` (last-known city for a coordinate).
- Provide `GET /v1/zones`, `POST /v1/zones`, `PATCH /v1/zones/{id}`,
  `GET /v1/zones/{id}`, `GET /v1/zones/contains` (point-in-zone
  query), `GET /v1/zones/intersects` (polygon-polygon).
- Provide `GET /v1/surge-zones` and admin CRUD on surge
  multipliers.
- Provide `GET /v1/restricted-zones` (used by `dispatch-service`
  to reject pickups in no-go areas).
- Validate polygons (PostGIS `ST_IsValid`, `ST_IsSimple`,
  `ST_Within(zone, city)`).
- Emit `zone.updated.v1`, `zone.surge.updated.v1`,
  `zone.city.updated.v1`, `zone.restricted.updated.v1` for
  every change.
- Resolve a coordinate to its enclosing city / service zone /
  surge zone / restricted zone, in one REST call.
- Persist zone hours (operating windows per weekday + holiday
  calendar) and apply them on point-in-zone queries that are
  time-sensitive (e.g. airport zones are only active during
  certain hours).

## 4. Explicitly NOT Owned

- **Driver / courier live locations** — `driver-location-service`,
  `courier-tracking-service`.
- **Geocoding** — `geolocation-service`. We may call it for
  reverse geocoding a new zone's centroid during creation.
- **Pricing** — `pricing-service` reads surge multipliers; we do
  not compute fares.
- **Trip / order state** — `trip-service`, `food-order-service`,
  `food-order-service`.

## 5. Actors

| Actor | Type | Access |
|-------|------|--------|
| Admin (operations) | human | full CRUD on cities, zones, surge, restricted |
| Admin (city ops) | human | CRUD on their own city's zones |
| `pricing-service` | system | read zones, surge multipliers |
| `dispatch-service` | system | read service zones, restricted zones |
| `courier-dispatch-service` | system | read service zones, restricted zones |
| `ride-request-service` | system | read service zones (is pickup allowed?) |
| `food-order-service` | system | read service zones (is the address in a delivery zone?) |
| `address-service` | system | read cities |
| `geolocation-service` | system | read zone metadata for cache-key scoping |
| `fraud-risk-service` | system | read zones (velocity vs. zone geometry) |
| `search-service` | system | read service zones (filter restaurants by zone) |
| `analytics-service` | system | read zones for dashboards |

## 6. Dependencies

### Synchronous (REST)

- `geolocation-service` — reverse geocode zone centroids during
  creation — SLO 99.95% — circuit breaker: yes.
- `configuration-service` — read default country, default
  timezone, supported verticals — SLO 99.95% — circuit breaker:
  yes.
- `identity-service` — validate admin actor (Keycloak roles) —
  SLO 99.95% — circuit breaker: no (handled at the gateway).

### Asynchronous (events consumed)

- `customer.created.v1` from `customer-service` — onboarding
  (no zone action; logged for analytics only).
- `configuration.updated.v1` from `configuration-service` —
  default country, supported verticals, holiday calendar —
  duplicate handling: reload is idempotent (config hash
  compared).

## 7. Technology Assumptions

- Runtime: Go 1.22 — strong PostGIS bindings, performance for
  polygon queries.
- Database: PostgreSQL 18 with PostGIS 3.4 in schema `zone`.
- Cache: Redis 7 (per-service) for hot zone lookups
  (e.g. "is this coordinate in zone X?"); TTL 5 min, invalidated
  on every `zone.updated.v1`.
- Event broker: Kafka.

## 8. Database Ownership

- Schema: `zone`
- Migrations: `services/zone-service/migrations/` (versioned,
  forward-only, golang-migrate).
- Soft delete: yes (cities, zones, surge, restricted) — for
  audit and for serving historical "where was the zone at time
  T" questions.
- Partitioning: no (zone tables are not high-volume; each city
  has O(10) zones, O(100) restricted zones).

## 9. API Overview

| Method | URI | Auth | Purpose |
|--------|-----|------|---------|
| GET | /v1/cities | bearer | list cities (cursor-paginated) |
| GET | /v1/cities/{id} | bearer | get city by id |
| POST | /v1/cities | admin | create city |
| PATCH | /v1/cities/{id} | admin | update city |
| GET | /v1/cities/lookup | bearer | resolve coordinate to city |
| GET | /v1/zones | bearer | list service zones (filter by city) |
| GET | /v1/zones/{id} | bearer | get service zone |
| POST | /v1/zones | admin | create service zone |
| PATCH | /v1/zones/{id} | admin | update service zone |
| POST | /v1/zones/contains | bearer | point-in-zone query |
| POST | /v1/zones/intersects | admin | polygon-polygon overlap |
| GET | /v1/surge-zones | bearer | list active surge zones |
| POST | /v1/surge-zones | admin | create surge zone |
| PATCH | /v1/surge-zones/{id} | admin | update surge multiplier |
| GET | /v1/restricted-zones | bearer | list active restricted zones |
| POST | /v1/restricted-zones | admin | create restricted zone |

(Full contracts in INTEGRATION.md.)

## 10. Events Produced

| Event | Trigger | Consumers |
|-------|---------|-----------|
| `zone.city.updated.v1` | city created / updated / suspended | `pricing-service`, `dispatch-service`, `courier-dispatch-service`, `search-service`, `geolocation-service` |
| `zone.updated.v1` | service zone created / updated | `pricing-service`, `dispatch-service`, `courier-dispatch-service`, `ride-request-service`, `search-service`, `geolocation-service` |
| `zone.surge.updated.v1` | surge multiplier changed | `pricing-service`, `dispatch-service` |
| `zone.restricted.updated.v1` | restricted zone created / updated | `dispatch-service`, `courier-dispatch-service`, `ride-safety-service` |

(Full contracts in INTEGRATION.md.)

## 11. Events Consumed

| Event | Producer | Reason | Handler |
|-------|----------|--------|---------|
| `configuration.updated.v1` | `configuration-service` | default country, supported verticals, holiday calendar changed | reload config (idempotent) |
| `customer.created.v1` | `customer-service` | new customer in a city (analytics only) | no-op in zone state; emitted to `analytics-service` |
| `merchant.approved.v1` | `merchant-service` | new merchant (we re-validate any auto-generated zone) | re-validate affected zones; no-op in most cases |

(Full contracts in INTEGRATION.md.)

## 12. External Integrations

- **Vault** — admin token signing keys, default configuration.
- **Geocoding provider (via `geolocation-service`)** — reverse
  geocoding new zone centroids.

## 13. Configuration

| Key | Type | Source | Notes |
|-----|------|--------|-------|
| `zone.default_country` | string | configuration-service | ISO 3166-1 alpha-2; default `US` |
| `zone.supported_verticals` | array of strings | configuration-service | e.g. `["ride", "food"]` |
| `zone.holiday_calendar_locale` | string | configuration-service | `en`, `ar`, etc. |
| `zone.cache.contains.ttl_seconds` | int | configuration-service | default 300 |
| `zone.cache.surge.ttl_seconds` | int | configuration-service | default 30 (more volatile) |
| `zone.polygon.max_area_km2` | number | configuration-service | rejects too-large zones |

## 14. Security

- **AuthN**: bearer JWT (validated at gateway) for read endpoints;
  admin role for create / update / delete; mTLS for the admin
  listener.
- **AuthZ**: RBAC roles (`admin`, `city_ops`,
  `platform_engineer`). City ops can only edit their own city
  (`X-Tenant-Id` claim matches `city.tenant_id`). Resource-level
  ownership: city ops can read any city (for cross-city fraud
  investigations) but can write only to their own.
- **Secrets**: admin signing keys in Vault; rotated quarterly.
- **PII**: zone polygons can overlap with residential areas
  (Sensitive class for the residential context, but the polygon
  itself is not PII — it is operational metadata). Centroid
  addresses (if reverse-geocoded) are stored encrypted.

## 15. Observability

- **Logs**: JSON to stdout; fields: `correlation_id`, `trace_id`,
  `service=zone-service`, `route`, `latency_ms`, `status`,
  `city_id`, `zone_id`, `actor_sub`.
- **Metrics**: RED (per route) + business:
  `zone_contains_queries_total{cache_hit, status}`,
  `zones_per_city`, `surge_zone_multiplier`,
  `zone_update_lag_seconds` (time from admin save to event
  publish), `polygon_validation_failures_total{reason}`.
- **Traces**: OpenTelemetry; root span per request; PostGIS
  queries as child spans.
- **Health**: `/health`, `/ready` (DB + Redis + Kafka reachable;
  migrations done), `/started` (warm).

## 16. Scalability

- **Replicas**: default 4. Reads dominate; point-in-zone queries
  are hot.
- **HPA**: CPU 60%, custom metric
  `zone_contains_queries_per_second > 200` per replica.
- **Hot path**: `POST /v1/zones/contains` (point-in-zone
  query). P99 ≤ 50ms with cache, ≤ 150ms without.
- **PostGIS GIST indexes** on every polygon column are critical.

## 17. Local Development

- `docker compose up zone-service` brings up the service, its
  DB, and Redis, with a PostGIS-enabled image.
- Seed: `make seed` loads 5 cities (San Francisco, New York,
  London, Riyadh, Dubai) with realistic zone polygons from a
  fixtures file.
- Tests: unit (Go, table-driven), integration (testcontainers
  with PostGIS), contract (pact or equivalent).

## 18. Deployment

- **Image**: `ghcr.io/uber/zone-service:<git-sha>`.
- **Replicas**: 4 in production.
- **Resource limits**: see deployment-arch (`cpu: 500m`,
  `memory: 768Mi` requests; 1 CPU, 1.5Gi limits).
- **Migrations**: run as a Kubernetes Job on deploy (init
  container), before the new pod is marked ready.
- **PostGIS extension**: created by the first migration; the
  migration job uses a role that has `CREATE EXTENSION`
  privilege on the database.


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

- **Depends on**: [`address-service`](../address-service/README.md), [`analytics-service`](../analytics-service/README.md), [`configuration-service`](../configuration-service/README.md), [`courier-dispatch-service`](../courier-dispatch-service/README.md), [`courier-tracking-service`](../courier-tracking-service/README.md), [`customer-service`](../customer-service/README.md), [`dispatch-service`](../dispatch-service/README.md), [`driver-location-service`](../driver-location-service/README.md), [`food-order-service`](../food-order-service/README.md), [`fraud-risk-service`](../fraud-risk-service/README.md), [`geolocation-service`](../geolocation-service/README.md), [`identity-service`](../identity-service/README.md), [`merchant-service`](../merchant-service/README.md), [`pricing-service`](../pricing-service/README.md), [`ride-request-service`](../ride-request-service/README.md), [`ride-safety-service`](../ride-safety-service/README.md), [`search-service`](../search-service/README.md), [`trip-service`](../trip-service/README.md)
- **Depended on by**: [`branch-service`](../branch-service/README.md), [`configuration-service`](../configuration-service/README.md), [`courier-dispatch-service`](../courier-dispatch-service/README.md), [`courier-service`](../courier-service/README.md), [`driver-availability-service`](../driver-availability-service/README.md), [`driver-service`](../driver-service/README.md), [`geolocation-service`](../geolocation-service/README.md), [`pricing-service`](../pricing-service/README.md), [`restaurant-service`](../restaurant-service/README.md), [`ride-request-service`](../ride-request-service/README.md), [`scheduled-ride-service`](../scheduled-ride-service/README.md), [`search-service`](../search-service/README.md)

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

- [`../../workflows/RIDE_WORKFLOWS.md`](../../workflows/RIDE_WORKFLOWS.md) — end-to-end ride flows
- [`../../workflows/FOOD_ORDER_WORKFLOWS.md`](../../workflows/FOOD_ORDER_WORKFLOWS.md) — end-to-end order/delivery flows
