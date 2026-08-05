# zone-service — Business Requirements Document

## 1. Document Purpose

This document is the authoritative statement of *what*
`zone-service` must do for the business. It is read by product
management, the platform architecture team, the operations
team that manages cities, the service's engineering team, and
any auditor verifying the platform's geospatial correctness. It
informs the city expansion roadmap, the surge pricing
mechanism, the restricted-zones policy (e.g. no pickups at
airports during certain hours), and the data model for the
geospatial layer.

## 2. Business Context

Every customer-facing flow depends on knowing *where* a ride
or order happens. A rider cannot request a trip if their pickup
is outside a service zone. A courier cannot be assigned a
delivery if the drop-off is in a no-go area. The platform
applies surge multipliers that depend on which zone a pickup
is in. The platform's city expansion (Riyadh, Dubai, etc.) is
operationally a series of `zone-service` writes: a new city
with its first set of service zones, surge zones, and
restricted zones.

`zone-service` is the single source of truth for all of that. If
two services disagreed on whether a coordinate is in a service
zone, the platform would behave inconsistently — pricing would
diverge from dispatch, and customer trust would erode. So this
service is non-negotiable: every other service that needs a
zone answer reads from here.

## 3. Objectives

| ID | Objective | Metric |
|----|-----------|--------|
| BR--001 | Provide a single, authoritative point-in-zone query across the platform | every other service uses `POST /v1/zones/contains` for any "is X in zone?" check; no service computes zones locally |
| BR--002 | Allow operations to add, modify, and retire zones without downtime | 100% of zone edits completed via this service; zero downtime for the platform during zone changes |
| BR--003 | Make every zone change observable to downstream services within 5 seconds | P95 zone-update propagation ≤ 5 s (event publish → consumer cache invalidate) |
| BR--004 | Support surge zones with time-bound multipliers | surge.zone.multiplier can be set per zone, per time window, with sub-zone granularity |
| BR--005 | Support restricted zones (no pickups, no drops, or no idle) with reason and time windows | restricted zones apply at dispatch and at ride request; no-go areas can be made temporary (e.g. parade routes) |
| BR--006 | Support hierarchical zones (zone inside city inside region) | a zone always has a city; a city always has a region; region-level queries (e.g. "is this in MENA?") answered in one call |
| BR--007 | Keep polygon queries fast enough to be on the hot path of every ride request | P99 `POST /v1/zones/contains` ≤ 50 ms cached, ≤ 150 ms cache miss |

## 4. Stakeholders

| Stakeholder | Role | Interest |
|-------------|------|----------|
| Operations (city ops) | primary writer | add / edit / retire zones for their city |
| Product (Ride) | consumer | correct service-zone boundaries; correct surge multipliers |
| Product (Food) | consumer | correct delivery-zone boundaries; restricted zones (no-go restaurants, low-prep areas) |
| Dispatch | consumer | uses restricted zones to reject pickups |
| Pricing | consumer | uses surge multipliers |
| Fraud / Risk | consumer | cross-checks against restricted zones (e.g. account created in a high-fraud zone) |
| Finance | reviewer | surge revenue; any zone changes that affect revenue must be auditable |
| Legal / Compliance | reviewer | restricted zones for legally sensitive areas (e.g. near a government building) |
| Customer Support | consumer | "why was my ride rejected at this address?" — needs to look up the zone and reason |

## 5. Actors / Personas

- **City operations manager**: maintains zones for their city.
  Adds new service zones when the city expands, retires
  zones when a neighborhood is no longer served.
- **Pricing operations**: sets surge multipliers per zone per
  time window. Operates the surge zone as a knob, not a
  switch.
- **Safety / Compliance officer**: creates restricted zones
  (e.g. around a stadium during a match, near a school
  during pick-up hours).
- **Driver / Courier app**: every minute, fetches the current
  service zone polygons to render the "where can I go" map.
- **Rider / Diner app**: at every pickup / drop-off entry,
  checks whether the address is in a service zone.
- **Audit / Finance**: reviews every zone edit with full
  history.

## 6. Business Capabilities

- **City management** (CRUD on cities, with timezone, country,
  currency, supported verticals).
- **Service zone management** (CRUD on polygons inside a city,
  with hours, allowed verticals, max concurrency).
- **Surge zone management** (CRUD on polygons with multipliers
  and time windows).
- **Restricted zone management** (CRUD on polygons with type,
  reason, time window).
- **Point-in-zone query** (the hot path).
- **Polygon-overlap query** (used by admins to detect
  overlapping zones before saving).
- **Zone hierarchy** (city → zone; region aggregation).
- **Zone version history** (every change is versioned; old
  versions retained for audit).

## 7. Business Requirements

| ID | Requirement | Priority | Source |
|----|-------------|----------|--------|
| BR--010 | The service MUST be the only writer of cities, service zones, surge zones, and restricted zones. | MUST | data ownership, platform architecture |
| BR--011 | The service MUST validate every polygon before persisting (no self-intersection, valid SRID, inside the parent city, area ≤ `zone.polygon.max_area_km2`). | MUST | data correctness |
| BR--012 | The service MUST emit a `zone.*.updated.v1` event for every change within 5 seconds. | MUST | downstream propagation SLO |
| BR--013 | The service MUST support nested / overlapping zones (a point can be in multiple service zones of the same vertical; the first match in deterministic order is the "active" one). | MUST | product requirement (overlapping service areas during expansion) |
| BR--014 | The service MUST support time-bound zones: a zone can have hours of operation (per weekday + holiday calendar). | MUST | airport / stadium zones |
| BR--015 | The service MUST support restricted zones with a `reason` and a `type` (`no_pickup`, `no_dropoff`, `no_idle`, `surge_only`). | MUST | operations policy |
| BR--016 | The service MUST support surge zones with a `multiplier` between 1.0 and 10.0 (configurable upper bound) and a per-zone override. | MUST | surge pricing |
| BR--017 | The service MUST support a hierarchical "region" concept (e.g. MENA, EU, US) for cross-city queries. | SHOULD | regional reporting |
| BR--018 | The service MUST return the resolved zone and the time-bounded metadata in a single point-in-zone response (e.g. "is the point in zone X right now, given the time window?"). | MUST | time-bound zones |
| BR--019 | The service MUST reject zone edits from actors who are not authorized for the city (or region), with 403. | MUST | RBAC |
| BR--020 | The service MUST log every zone edit in an append-only audit table with `actor_sub`, `before`, `after`, and `correlation_id`. | MUST | audit / finance |
| BR--021 | The service MUST support admin "draft" zones that are not yet active, with an `activate_at` timestamp that promotes them to active automatically. | SHOULD | operations planning |
| BR--022 | The service MUST detect and warn on overlapping service zones in the same city at write time (but allow the write — the warning is operational). | SHOULD | data quality |
| BR--023 | The service MUST provide a "soft delete" path for zones (mark inactive but retain for audit). | MUST | data retention policy |
| BR--024 | The service MUST support read-only access for the public (e.g. mobile app's "where do you operate?") without exposing admin endpoints. | MUST | product |

## 8. Business Rules

| ID | Rule | Notes |
|----|------|-------|
| BR--020 | A service zone MUST be inside exactly one city polygon. | enforced via `ST_Within(zone.polygon, city.polygon)` |
| BR--021 | A surge zone MUST be inside exactly one city polygon. | same |
| BR--022 | A restricted zone MUST be inside exactly one city polygon. | same |
| BR--023 | A surge zone MUST overlap at least one service zone (otherwise it has no effect). | enforced as a warning, not a hard error |
| BR--024 | Polygon area MUST NOT exceed `zone.polygon.max_area_km2` (default 500 km²). | |
| BR--025 | Polygon SRID MUST be 4326. | enforced by the migration; enforced at the API |
| BR--026 | Two service zones in the same city MAY overlap; the deterministic tiebreaker is the lowest `zone_id` (UUIDv7) wins. | |
| BR--027 | A restricted zone's `type` MUST be one of `no_pickup`, `no_dropoff`, `no_idle`, `surge_only`. | |
| BR--028 | A surge multiplier MUST be in `[1.0, max_multiplier]` where `max_multiplier` is configurable (default 10.0). | |
| BR--029 | Zone hours are stored in the city's timezone. | |
| BR--030 | Holiday calendar is locale-specific. | |

## 9. Assumptions

- The platform operates in cities whose boundaries are
  well-defined and stable enough to be stored as polygons.
- Cities rarely need to be deleted; soft delete is enough.
- Zone polygon changes are infrequent (a few per city per
  day), so event emission is low-volume.
- Point-in-zone queries are high-volume (every ride request
  does at least two: pickup and dropoff).

## 10. Constraints

- **Correctness**: a wrong zone answer is a customer-trust
  incident (rider charged for a surge they shouldn't have
  been, or pickup rejected in a valid area). Polygon
  validation is non-negotiable.
- **Latency**: point-in-zone is on the hot path; P99 ≤ 50
  ms cached.
- **Auditability**: every zone change must be attributable to
  an actor and reversible only by another authorized actor.
- **Compliance**: restricted zones may reflect legal
  restrictions (e.g. no pickups near a courthouse); the
  service must support a "legal hold" flag on restricted
  zones that prevents deletion.

## 11. Dependencies

| Dependency | Type | Notes |
|------------|------|-------|
| `geolocation-service` | service | reverse-geocode zone centroids on creation |
| `configuration-service` | service | default country, supported verticals, holiday calendar |
| `pricing-service` | consumer | reads surge multipliers |
| `dispatch-service` | consumer | reads service + restricted zones |
| `courier-dispatch-service` | consumer | reads service + restricted zones |
| `ride-request-service` | consumer | reads service zones |
| `food-order-service` | consumer | reads delivery zones |
| `search-service` | consumer | reads service zones for filtering |
| `fraud-risk-service` | consumer | reads restricted zones for fraud scoring |
| `ride-safety-service` | consumer | reads restricted zones (e.g. school zones for safety) |
| `address-service` | consumer | reads cities |
| `audit-service` | consumer | reads zone edit events for finance / legal audit |
| PostgreSQL 18 + PostGIS 3.4 | infra | core storage |
| Redis 7 | infra | hot cache for point-in-zone |
| Kafka | infra | event propagation |
| Vault | infra | admin signing keys |

## 12. Business Workflows

- **Add a new service zone** — see `WORKFLOWS.md` §1.
- **Update surge multiplier** — see `WORKFLOWS.md` §2.
- **Create a temporary restricted zone (e.g. parade)** — see
  `WORKFLOWS.md` §3.
- **Point-in-zone query (hot path)** — see `WORKFLOWS.md` §4.
- **Onboard a new city** — see `WORKFLOWS.md` §5.

## 13. Exception Workflows

- **Overlapping service zones**: at write time, the admin is
  warned; the write is allowed; the deterministic tiebreaker
  applies at query time.
- **Polygon validation failure**: the write is rejected with
  422 and a specific reason (e.g. `POLYGON_SELF_INTERSECTS`,
  `POLYGON_OUTSIDE_CITY`, `POLYGON_TOO_LARGE`).
- **Zone edit by an unauthorized actor**: 403.
- **Cache vs. truth drift**: Redis is invalidated on every
  `zone.updated.v1`; the consumer (this service) is the
  source of truth.

## 14. Success Criteria

- 100% of zone reads across the platform come from
  `zone-service` (no service computes its own
  point-in-zone).
- P95 zone-update propagation ≤ 5 s (measured from admin save
  to consumer cache invalidate).
- P99 point-in-zone ≤ 50 ms cached, ≤ 150 ms cache miss.
- Zero "phantom" zones (polygons that don't validate) ever
  reach production.
- 100% of zone edits audited.
- The platform can onboard a new city (Riyadh, then Dubai)
  in ≤ 14 days.

## 15. KPIs

| KPI | Target | Measurement |
|-----|--------|-------------|
| P95 zone-update propagation | ≤ 5 s | `zone_update_lag_seconds` (P95) over 7-day window |
| P99 point-in-zone (cache hit) | ≤ 50 ms | Prometheus histogram |
| P99 point-in-zone (cache miss) | ≤ 150 ms | Prometheus histogram |
| Polygon validation failure rate | 0% in production (writes) | `polygon_validation_failures_total{reason}` |
| Zone-edit audit coverage | 100% | `admin_audit_zone_edit_total / zone_edit_total` |
| Cache hit ratio (point-in-zone) | ≥ 0.85 | `zone_cache_hit_ratio{resource=contains}` |
| Service uptime | 99.95% (T1) | uptime from synthetic monitoring |

## 16. Acceptance Criteria

- All five entities (City, ServiceZone, SurgeZone,
  RestrictedZone, ZoneHours) implemented, versioned, and
  indexed.
- All five events (`zone.city.updated.v1`, `zone.updated.v1`,
  `zone.surge.updated.v1`, `zone.restricted.updated.v1`,
  plus zone.deleted.v1) emitted on the right trigger.
- Polygon validation rejects all four common failure modes
  (self-intersection, outside city, too large, invalid SRID).
- Point-in-zone P99 ≤ 50 ms cached in the staging load test
  (10k QPS sustained).
- A new city (fixtures) can be created end-to-end in staging
  in ≤ 30 minutes.
- A zone edit by a non-owner actor is rejected with 403 in
  the integration test.
- A zone edit by an authorized actor results in the event
  being consumed by at least three downstream services in
  staging within 5 s.

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

