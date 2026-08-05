# ADR-0007: Use PostGIS for Geospatial Queries

- Status: Accepted
- Date: 2026-07-29
- Authors: Platform Architecture Team
- Deciders: Architecture Review Board
- Tags: database, geospatial, postgis, location, zones

## Context and Problem Statement

The platform is fundamentally a geospatial product: customers are
matched to nearby drivers, couriers are matched to nearby ready
orders, restaurants are matched to their delivery zone, surge zones
are computed from real-time demand and supply, and trips' pickup and
dropoff are geocoded to a city and a zone. We need a geospatial
engine that can hold points and polygons, index them, and answer
"what is within X meters of Y" queries at hot-path latency. The
geospatial engine must live close to the operational data so that we
can write joins like "drivers in this surge zone" without
materializing data into a separate store. It must also be the
canonical store for zone polygons (service zones, surge zones,
restricted zones) and for the geocoding cache.

The decision is whether to use PostGIS (in Postgres), an external
map service (HERE, Google Maps, Mapbox), a search engine with
geo support (Elasticsearch, OpenSearch), or a document store with
geo support (MongoDB).

## Decision Drivers

- Sub-second "what is within X meters" queries at peak (10k+
  drivers in a city, hundreds of thousands of geocoded addresses).
- Joins with operational data: a zone polygon in the same database
  as a driver's last known location, in the same query.
- ACID transactions on geospatial data: a zone polygon update and
  the corresponding `zone.updated.v1` outbox row are committed
  atomically.
- Mature indexing (`GIST` on geometry) with documented query
  patterns (`ST_DWithin` for filtering, not `ST_Distance`).
- Multi-region, multi-country: same engine in every region; same
  query patterns; same operational runbook.
- Cost: we already run Postgres; the marginal cost of enabling
  PostGIS is the extension and the operational knowledge, not a
  new cluster.
- Co-location with the OLTP store: the trip's pickup point, the
  driver's last known location, and the zone polygon are all in
  the same database family; no ETL into a separate geo store.

## Considered Options

- **PostGIS in PostgreSQL** — the chosen option. Geometry/geography
  types, `GIST` indexes, `ST_DWithin`, `ST_Distance_Spheroid`,
  `ST_Contains`, `ST_Intersects`, etc.
- **Elasticsearch / OpenSearch with geo_shape and geo_point** —
  search engine with geo support.
- **MongoDB with 2dsphere indexes** — document store with geo
  support.
- **External map service only (HERE, Google, Mapbox)** — call out
  for every geo query.
- **Redis with geo commands (GEOADD, GEORADIUS)** — in-memory geo
  for hot-path lookups.

## Decision Outcome

Chosen option: "**PostGIS in PostgreSQL**", because (a) it lets us
store the geospatial data (zone polygons, geocoding cache,
address points, driver/courier location trail) in the same database
as the operational data, with the same transactions and the same
operational runbook, (b) it supports the joins that drive the
business: "available drivers in this surge zone" is one query
joining `zone` polygons with `driver_location.current_location`
points via `ST_Contains` + `ST_DWithin`, (c) `GIST` indexes on
geometry columns are well-understood and well-indexed, (d) the
query patterns are documented (`ST_DWithin` for filtering; never
`ST_Distance` without a bounding box), and (e) the marginal cost
of enabling PostGIS in our existing Postgres clusters is small.
The external map provider (HERE or equivalent) is used for the
authoritative geocoding and routing data; we cache the results in
`geolocation-service`'s `geocoded_addresses` table (PostGIS
geometry(Point, 4326) with a `GIST` index) and serve from there.

### Consequences

- Good: Geospatial data in the same database as operational data.
  Joins are SQL joins; transactions are Postgres transactions; the
  outbox row for `zone.updated.v1` is in the same commit.
- Good: `GIST` indexes on geometry columns give us sub-100ms
  "within X meters" queries on millions of points.
- Good: `ST_DWithin` (with the bounding-box optimization) is the
  standard filter; `ST_Distance_Spheroid` is the standard exact
  distance. The query patterns are well-documented in
  [`DATABASE_ARCHITECTURE.md`](../DATABASE_ARCHITECTURE.md).
- Good: ACID for geospatial state. A zone polygon update, a
  `zone.updated.v1` outbox row, and a `zone.surge.updated.v1` outbox
  row are committed atomically.
- Good: Multi-region, multi-country with the same engine and the
  same query patterns. SRID 4326 (WGS84) is the universal default.
- Good: Cost. We already run Postgres; the marginal cost is the
  PostGIS extension, the additional disk for the `GIST` indexes,
  and the operational knowledge.
- Bad: PostGIS has a learning curve. The team must know the
  difference between geometry and geography, between `ST_DWithin`
  and `ST_Distance`, and the SRID conventions. (Mitigation: a
  per-service README that documents the patterns; a `postgis-lint`
  CI check for `ST_Distance` in `WHERE` clauses.)
- Bad: Very high write workloads (the location stream) need
  careful partitioning. We partition `driver_location.locations`
  and `courier_tracking.locations` by day and maintain a
  `current_location` table for the last-known position; see
  [`DATABASE_ARCHITECTURE.md`](../DATABASE_ARCHITECTURE.md).
- Bad: Geocoding is still an external dependency. The map
  provider is the source of truth for "this address string is at
  lat/lng"; we cache it in PostGIS, but cold-cache misses are an
  external call.
- Neutral: We use the geography type for distance queries
  (meter-accurate on a sphere) and the geometry type for zone
  polygons and joins. The convention is documented.

### Confirmation

- Sub-100ms P99 for "available drivers within X meters of a
  pickup point" queries in ``driver-service` (dispatch)` and
  ``courier-service` (dispatch)`.
- Sub-50ms P99 for "is this point in any surge zone" joins in
  `pricing-service` and ``driver-service` (dispatch)`.
- 100% of geospatial services use `GIST` indexes on geometry
  columns; no production query uses `ST_Distance` in a `WHERE`
  clause (CI lint).
- Quarterly accuracy drill: validate a sample of geocoded
  addresses against the external map provider; < 1% drift.
- Zone polygon updates land in the same transaction as the
  outbox row for `zone.updated.v1`; verified by a chaos test
  that kills the producer mid-commit and asserts no orphaned
  events.

## Pros and Cons of the Options

### PostGIS in PostgreSQL

The chosen option. Geometry and geography types, `GIST` indexes,
`ST_DWithin`, `ST_Contains`, `ST_Intersects`, `ST_Distance_Spheroid`,
projection and transformation functions.

- Good: In the same database as operational data; joins are SQL
  joins; transactions are ACID.
- Good: Mature indexing; well-documented query patterns.
- Good: Open source; we already run Postgres.
- Good: Multi-region, multi-country; same engine everywhere.
- Bad: Learning curve (geometry vs. geography, SRID, `ST_DWithin`
  vs. `ST_Distance`).
- Bad: High-volume location writes need partitioning; the default
  schema is wrong.
- Bad: Geocoding is still an external dependency (cached).

### Elasticsearch / OpenSearch with geo_shape / geo_point

A search engine with first-class geo support.

- Good: Excellent for full-text + geo combined queries (e.g.
  "Italian restaurants within 2 km of me").
- Good: Horizontal scaling; mature operational story.
- Bad: Not the source of truth for zones; we would write to
  Postgres (for ACID) and to ES (for search) and reconcile.
- Bad: A separate cluster to operate; we already have Postgres
  expertise.
- Bad: Not transactional; the zone update in Postgres and the
  document update in ES are not atomic.
- Bad: The `search-service` already uses OpenSearch for full-text
  search; adding geo there is reasonable for the
  restaurant-discovery use case, but it is not the geospatial
  source of truth for ``driver-service` (dispatch)` and `pricing-service`.

### MongoDB with 2dsphere indexes

A document store with geo support.

- Good: Flexible schema; good for document-shaped data.
- Good: 2dsphere indexes for "within X meters" queries.
- Bad: Not our engine of record; the platform is on Postgres. We
  would be the only service on Mongo.
- Bad: Not transactional with our OLTP data; cross-store joins
  are application-level.
- Bad: We have no in-house Mongo expertise at platform scale.

### External map service only (HERE, Google, Mapbox)

Call out to the provider for every geo query.

- Good: Authoritative geocoding and routing.
- Good: No geo data to store; the provider does it.
- Bad: Latency on the hot path; we cannot call HERE in the
  dispatch loop.
- Bad: Cost at our query volume.
- Bad: Per-query rate limits; one provider outage takes down our
  match path.
- Bad: No transactional consistency with our state.
- Bad: The provider does not know our zones (surge zones,
  restricted zones) — those are our data.

### Redis with geo commands (GEOADD, GEORADIUS, GEOSEARCH)

In-memory geo for hot-path lookups.

- Good: Very fast; in-memory.
- Good: `GEORADIUS` and `GEOSEARCH` are simple.
- Bad: No ACID; no transactional consistency with our state.
- Bad: In-memory; the data set (millions of addresses, hundreds
  of thousands of driver locations per region) is too large for a
  Redis cluster at acceptable cost.
- Bad: Not the source of truth; we would mirror from Postgres to
  Redis and reconcile.

## References

- [`DATABASE_ARCHITECTURE.md`](../DATABASE_ARCHITECTURE.md) —
  PostGIS section: `geometry(Point, 4326)` for points,
  `geometry(Polygon, 4326)` for zones, `GIST` indexes,
  `ST_DWithin` for filtering, partitioning for high-volume
  location tables.
- [`ARCHITECTURE.md`](../ARCHITECTURE.md) — Geospatial & Zones
  bounded context: `geolocation-service`, ``geolocation-service` (zones)`.
- [`EVENT_ARCHITECTURE.md`](../EVENT_ARCHITECTURE.md) —
  `zone.updated.v1`, `zone.surge.updated.v1`,
  `driver.location.updated.v1`, `courier.location.updated.v1`.
- PostGIS documentation — `ST_DWithin`, `ST_Contains`,
  `ST_Intersects`, `ST_Distance_Spheroid`, `GIST` indexes, SRID
  4326.
- [`MICROSERVICES_MAP.md`](../MICROSERVICES_MAP.md) — the
  geospatial services and their schemas (`geolocation`, `zone`,
  `driver_location`, `courier_tracking`, `address`).
