# geolocation-service — Entity-Relationship Diagram

## 1. Database

- **Engine**: PostgreSQL 18 with PostGIS 3.4 extension.
- **Schema**: `geolocation` — owned exclusively by this service.
- **Migrations**: `services/geolocation-service/migrations/`
  (versioned, forward-only, golang-migrate; reviewed in PR;
  no destructive migrations without a multi-step plan).

The schema holds the **persistent cache** of vendor responses and
the **append-only audit log** of admin actions. It is not the
authoritative location for any business entity; the service is
a cache + audit layer on top of a third-party map provider.

## 2. Cross-Service References

The schema stores references to entities owned by other services.
These are UUID columns **without** database foreign keys, per
`DATA_OWNERSHIP.md` and `CONSISTENCY_STRATEGY.md`.

| Column | Type | Refers to | Source of truth |
|--------|------|-----------|------------------|
| `city_id` | UUID | `City` in ``geolocation-service` (zones)` | ``geolocation-service` (zones)` |
| `zone_id` | UUID (nullable) | `Zone` in ``geolocation-service` (zones)` | ``geolocation-service` (zones)` |
| `requested_by_sub` | UUID | Keycloak `sub` of the caller (or service account) | `identity-service` (Keycloak) |
| `requested_by_tenant_id` | UUID (nullable) | `Tenant` for multi-tenant admin paths | `identity-service` |
| `admin_actor_sub` | UUID | Keycloak `sub` of the admin who issued a purge / rotation | `identity-service` |
| `vendor_id` (in cache tables) | TEXT | internal id of the configured map provider, references `provider_config.vendor_id` (no FK, soft reference) | `provider_config` table in this service |
| `vendor_id` (in `provider_health`, `provider_circuit_state`, `provider_usage_daily`) | TEXT | same — soft reference to `provider_config.vendor_id` | `provider_config` table in this service |

## 3. Entities

### `GeocodeCache`

Persistent cache for forward and reverse geocodes. Each row is
identified by a stable `cache_key` (see BR--020..BR--021 in `BRD.md`)
and is TTL-pruned by a background job.

#### Columns

| Column | Type | Constraints | Notes |
|--------|------|-------------|-------|
| `id` | UUID | PK | UUIDv7 |
| `cache_key` | TEXT | NOT NULL UNIQUE | SHA-256 hex of (normalized address or rounded coord + locale + region) |
| `kind` | TEXT | NOT NULL | `forward` \| `reverse` |
| `locale` | TEXT | NOT NULL | `en`, `ar`, etc. |
| `region_city_id` | UUID | NULL | cross-ref: `city_id` from ``geolocation-service` (zones)` |
| `query_fingerprint` | TEXT | NOT NULL | shorter hash used for admin purge |
| `coordinate` | `geometry(Point, 4326)` | NOT NULL | PostGIS point, SRID 4326 |
| `formatted_address_encrypted` | BYTEA | NULL | `pgcrypto` ciphertext (PII); null for `forward` results that do not include a formatted address |
| `address_components` | JSONB | NULL | structured breakdown (street, city, country, etc.) |
| `vendor_id` | UUID | NOT NULL | which vendor produced this |
| `vendor_response` | JSONB | NULL | raw vendor response, opaque, never queried |
| `confidence` | NUMERIC(4,3) | NULL CHECK (confidence >= 0 AND confidence <= 1) | vendor-supplied; null if absent |
| `bbox` | `geometry(Polygon, 4326)` | NULL | for reverse geocodes: the bounding box the formatted address represents |
| `created_at` | TIMESTAMPTZ | NOT NULL DEFAULT now() | audit |
| `updated_at` | TIMESTAMPTZ | NOT NULL DEFAULT now() | audit |
| `created_by` | UUID | NOT NULL | identity |
| `updated_by` | UUID | NOT NULL | identity |
| `expires_at` | TIMESTAMPTZ | NOT NULL | created_at + ttl |
| `last_accessed_at` | TIMESTAMPTZ | NOT NULL DEFAULT now() | for LRU |
| `version` | INT | NOT NULL DEFAULT 1 | optimistic concurrency |

#### Indexes

- PK on `id`
- UNIQUE on `cache_key`
- GIST on `coordinate` (for `ST_DWithin` queries by zone
  invalidation job)
- GIST on `bbox` (for reverse geocodes whose bbox intersects a
  zone polygon)
- BTree on `expires_at` (TTL prune)
- BTree on `query_fingerprint` (admin purge filter)
- BTree on `last_accessed_at` (LRU eviction if Redis evicts)
- BTree on `region_city_id` (admin purge by city)

#### Constraints

- CHECK: `kind IN ('forward', 'reverse')`
- CHECK: `confidence IS NULL OR (confidence >= 0 AND confidence <= 1)`
- CHECK: `expires_at > created_at`

### `EtaCache`

Persistent cache for ETA estimates.

#### Columns

| Column | Type | Constraints | Notes |
|--------|------|-------------|-------|
| `id` | UUID | PK | UUIDv7 |
| `cache_key` | TEXT | NOT NULL UNIQUE | SHA-256 of (origin_grid + dest_grid + traffic_bucket + hour_of_day) |
| `origin_coordinate` | `geometry(Point, 4326)` | NOT NULL | rounded to ~10m grid |
| `destination_coordinate` | `geometry(Point, 4326)` | NOT NULL | rounded |
| `waypoint_count` | INT | NOT NULL CHECK (waypoint_count >= 0 AND waypoint_count <= 5) | |
| `departure_time_bucket` | INT | NOT NULL | hour-of-day 0..23 |
| `traffic_bucket` | TEXT | NOT NULL | `low` \| `medium` \| `high` \| `unknown` |
| `eta_seconds` | INT | NOT NULL CHECK (eta_seconds >= 0) | |
| `distance_meters` | INT | NOT NULL CHECK (distance_meters >= 0) | |
| `vendor_id` | UUID | NOT NULL | |
| `vendor_response` | JSONB | NULL | raw, opaque |
| `created_at` | TIMESTAMPTZ | NOT NULL DEFAULT now() | |
| `updated_at` | TIMESTAMPTZ | NOT NULL DEFAULT now() | |
| `created_by` | UUID | NOT NULL | |
| `updated_by` | UUID | NOT NULL | |
| `expires_at` | TIMESTAMPTZ | NOT NULL | default created_at + 60s |
| `last_accessed_at` | TIMESTAMPTZ | NOT NULL DEFAULT now() | |
| `version` | INT | NOT NULL DEFAULT 1 | |

#### Indexes

- PK on `id`
- UNIQUE on `cache_key`
- BTree on `expires_at` (TTL prune)
- BTree on `(origin_coordinate, destination_coordinate)` for
  audit lookups (using GIST is also possible but BTree is fine
  for the audit case; we don't query by spatial predicate here)

#### Constraints

- CHECK: `traffic_bucket IN ('low','medium','high','unknown')`

### `RouteCache`

Persistent cache for full routes.

#### Columns

| Column | Type | Constraints | Notes |
|--------|------|-------------|-------|
| `id` | UUID | PK | UUIDv7 |
| `cache_key` | TEXT | NOT NULL UNIQUE | SHA-256 of (origin_grid + dest_grid + hour_of_day) |
| `origin_coordinate` | `geometry(Point, 4326)` | NOT NULL | |
| `destination_coordinate` | `geometry(Point, 4326)` | NOT NULL | |
| `waypoint_count` | INT | NOT NULL CHECK (waypoint_count >= 0 AND waypoint_count <= 5) | |
| `polyline` | TEXT | NOT NULL | Google-encoded polyline (precision 5) |
| `distance_meters` | INT | NOT NULL | |
| `eta_seconds` | INT | NOT NULL | |
| `steps` | JSONB | NULL | ordered list of turn-by-turn steps; opaque, never queried |
| `alternatives` | JSONB | NULL | optional alternative routes |
| `vendor_id` | UUID | NOT NULL | |
| `vendor_response` | JSONB | NULL | raw, opaque |
| `created_at` | TIMESTAMPTZ | NOT NULL DEFAULT now() | |
| `updated_at` | TIMESTAMPTZ | NOT NULL DEFAULT now() | |
| `created_by` | UUID | NOT NULL | |
| `updated_by` | UUID | NOT NULL | |
| `expires_at` | TIMESTAMPTZ | NOT NULL | default created_at + 300s |
| `last_accessed_at` | TIMESTAMPTZ | NOT NULL DEFAULT now() | |
| `version` | INT | NOT NULL DEFAULT 1 | |

#### Indexes

- PK on `id`
- UNIQUE on `cache_key`
- BTree on `expires_at`

### `ZoneInvalidationState`

A denormalized snapshot of zone polygons consumed from
``geolocation-service` (zones)` for the cache-invalidation job. **Not** the source
of truth — ``geolocation-service` (zones)` owns polygons. We keep our own copy
here to avoid a synchronous call to ``geolocation-service` (zones)` on every
cache write.

#### Columns

| Column | Type | Constraints | Notes |
|--------|------|-------------|-------|
| `id` | UUID | PK | UUIDv7 |
| `zone_id` | UUID | NOT NULL UNIQUE | cross-ref |
| `city_id` | UUID | NULL | cross-ref |
| `polygon` | `geometry(Polygon, 4326)` | NOT NULL | the active polygon |
| `bbox` | `geometry(Polygon, 4326)` | NOT NULL | bbox of the polygon (for fast intersect) |
| `polygon_version` | INT | NOT NULL | the version we have |
| `updated_at_source` | TIMESTAMPTZ | NOT NULL | when the source was last updated |
| `created_at` | TIMESTAMPTZ | NOT NULL DEFAULT now() | |
| `updated_at` | TIMESTAMPTZ | NOT NULL DEFAULT now() | |
| `created_by` | UUID | NOT NULL | |
| `updated_by` | UUID | NOT NULL | |

#### Indexes

- PK on `id`
- UNIQUE on `zone_id`
- GIST on `polygon`
- GIST on `bbox`

### `AdminAudit` (append-only, partitioned)

Every admin action (cache purge, provider key rotation) is
persisted here. **No UPDATE / DELETE allowed** at the application
layer; enforced by a row-level policy.

#### Columns

| Column | Type | Constraints | Notes |
|--------|------|-------------|-------|
| `id` | UUID | NOT NULL | UUIDv7 |
| `occurred_at` | TIMESTAMPTZ | NOT NULL DEFAULT now() | partition key |
| `action` | TEXT | NOT NULL | `cache_purge` \| `provider_rotate` \| `fallback_activate` \| `fallback_deactivate` |
| `actor_sub` | UUID | NOT NULL | admin's Keycloak sub |
| `actor_role` | TEXT | NOT NULL | `admin` \| `platform_engineer` |
| `tenant_id` | UUID | NULL | for multi-tenant admin actions |
| `request_body` | JSONB | NOT NULL | the request that triggered the action |
| `request_idempotency_key` | TEXT | NULL | for purges |
| `signature` | TEXT | NOT NULL | HMAC-SHA256 hex |
| `result` | TEXT | NOT NULL | `success` \| `failure` |
| `error_code` | TEXT | NULL | if result = failure |
| `correlation_id` | UUID | NOT NULL | |
| `outbox_event_id` | UUID | NULL | the outbox event that was emitted |

#### Indexes

- BTree on `(occurred_at DESC, actor_sub)` (audit lookup)
- BTree on `correlation_id` (cross-service lookup)

#### Partitioning

- Range-partitioned by `occurred_at`, monthly.
- 12 future partitions pre-created by a maintenance job.
- Retention 1y; drop partitions older than 12 months.

### `Outbox` (per `EVENT_ARCHITECTURE.md`)

The service uses the standard outbox pattern to publish events.

#### Columns

| Column | Type | Constraints | Notes |
|--------|------|-------------|-------|
| `id` | UUID | PK | UUIDv7; this is also the `event_id` |
| `aggregate_type` | TEXT | NOT NULL | `Geocode` \| `Eta` \| `Route` \| `CachePurge` |
| `aggregate_id` | UUID | NOT NULL | the cache entry id (or audit row id) |
| `topic` | TEXT | NOT NULL | e.g. `geolocation.geocoded` |
| `event_name` | TEXT | NOT NULL | e.g. `geolocation.geocoded.v1` |
| `payload` | JSONB | NOT NULL | the event body |
| `headers` | JSONB | NOT NULL | correlation_id, tenant_id, etc. |
| `created_at` | TIMESTAMPTZ | NOT NULL DEFAULT now() | |
| `claimed_at` | TIMESTAMPTZ | NULL | when the poller picked it up |
| `published_at` | TIMESTAMPTZ | NULL | when the broker confirmed |
| `attempts` | INT | NOT NULL DEFAULT 0 | |
| `last_error` | TEXT | NULL | |

#### Indexes

- PK on `id`
- BTree on `(claimed_at, created_at)` (poller scan)
- BTree on `(topic, published_at)` (operational queries)

### `Inbox` (per `EVENT_ARCHITECTURE.md`)

Consumed events are de-duplicated here.

#### Columns

| Column | Type | Constraints | Notes |
|--------|------|-------------|-------|
| `event_id` | UUID | PK | from the event envelope |
| `consumer` | TEXT | NOT NULL | which consumer (e.g. `zone-update-invalidator`) |
| `topic` | TEXT | NOT NULL | |
| `received_at` | TIMESTAMPTZ | NOT NULL DEFAULT now() | |
| `processed_at` | TIMESTAMPTZ | NULL | |
| `error` | TEXT | NULL | |
| `attempts` | INT | NOT NULL DEFAULT 0 | |

#### Indexes

- PK on `event_id`
- BTree on `(consumer, received_at)`

### `ProviderConfig`

The canonical registry of every map provider this service can route
to. Each row is one provider implementation; chains reference these
rows by `vendor_id`. Together with `ProviderRegionRoute` this is the
data model behind the multi-provider chain model (see `README.md`
§4 and `INTEGRATION.md` §4).

#### Columns

| Column | Type | Constraints | Notes |
|--------|------|-------------|-------|
| `id` | UUID | PK | UUIDv7 |
| `vendor_id` | TEXT | NOT NULL UNIQUE | stable, kebab-case: `google`, `mapbox`, `here`, `osrm`, `valhalla`, `nominatim`, `pelias`, `photon`, `mock` |
| `display_name` | TEXT | NOT NULL | human label shown in admin UI |
| `adapter_type` | TEXT | NOT NULL | `commercial_rest` \| `self_host_rest` \| `in_process` |
| `capabilities` | TEXT[] | NOT NULL | subset of {`geocode_forward`,`geocode_reverse`,`eta`,`route`,`autocomplete`,`place_details`,`static_map`}; CHECK constraint enforces the enum |
| `is_self_host` | BOOL | NOT NULL DEFAULT false | true for OSRM/Valhalla/Nominatim/Pelias/Photon |
| `is_static_only` | BOOL | NOT NULL DEFAULT false | true for the `mock` provider and any static-only entry |
| `enabled` | BOOL | NOT NULL DEFAULT true | emergency-disable toggle; mirrored by `feature_flag.geolocation.vendor.{vendor_id}.disabled` |
| `priority` | INT | NOT NULL DEFAULT 100 | tie-break when two providers in a chain have the same `role`; lower wins |
| `base_url` | TEXT | NULL | API endpoint for REST adapters; NULL for in-process |
| `auth_type` | TEXT | NULL | `api_key` \| `oauth2_client_credentials` \| `mtls` \| `none` |
| `vault_secret_path` | TEXT | NOT NULL | Vault path: `kv/<env>/geolocation/<vendor_id>` |
| `qps_limit` | INT | NOT NULL DEFAULT 100 | token-bucket refill rate |
| `burst_limit` | INT | NOT NULL DEFAULT 100 | token-bucket size |
| `timeout_ms` | INT | NOT NULL DEFAULT 1500 | per-call timeout |
| `failure_threshold` | INT | NOT NULL DEFAULT 5 | consecutive failures to open circuit |
| `cooldown_seconds` | INT | NOT NULL DEFAULT 30 | how long circuit stays open |
| `half_open_probe_count` | INT | NOT NULL DEFAULT 3 | probes admitted in half-open |
| `cost_per_1k_usd` | NUMERIC(10,4) | NULL | per-1k-call USD cost for attribution; NULL for free providers |
| `jurisdictions` | TEXT[] | NOT NULL DEFAULT '{global}' | countries where the provider may be invoked (for data-residency) |
| `metadata` | JSONB | NULL | vendor-specific knobs (e.g. Google `region` bias, HERE `transport_mode`) |
| `created_at` | TIMESTAMPTZ | NOT NULL DEFAULT now() | |
| `updated_at` | TIMESTAMPTZ | NOT NULL DEFAULT now() | |
| `created_by` | UUID | NOT NULL | |
| `updated_by` | UUID | NOT NULL | |
| `version` | INT | NOT NULL DEFAULT 1 | optimistic concurrency |

#### Indexes

- PK on `id`
- UNIQUE on `vendor_id`
- BTree on `enabled` (filter hot path)
- GIN on `capabilities` (chain resolver lookup by capability)
- GIN on `jurisdictions` (data-residency-aware chain resolution)

#### Constraints

- CHECK: `adapter_type IN ('commercial_rest','self_host_rest','in_process')`
- CHECK: `auth_type IS NULL OR auth_type IN ('api_key','oauth2_client_credentials','mtls','none')`
- CHECK: `capabilities <> ARRAY[]::TEXT[]`
- CHECK: `qps_limit > 0 AND burst_limit > 0 AND timeout_ms > 0`

### `ProviderRegionRoute`

For each `(region, capability)` pair, the ordered list of
`provider_config.vendor_id` values that make up the chain, in the
order the resolver will try them. A region is a `city_id`, a
`country_code`, or the literal `default` (catch-all).

The resolver picks the **most specific** matching row.

#### Columns

| Column | Type | Constraints | Notes |
|--------|------|-------------|-------|
| `id` | UUID | PK | UUIDv7 |
| `region` | TEXT | NOT NULL | `city:<uuid>` \| `country:<ISO2>` \| `default` |
| `capability` | TEXT | NOT NULL | one of the 7 capabilities |
| `chain` | TEXT[] | NOT NULL | ordered `vendor_id`s; CHECK: each must reference a row in `provider_config` |
| `notes` | TEXT | NULL | e.g. `GDPR — HERE only` |
| `enabled` | BOOL | NOT NULL DEFAULT true | |
| `created_at` | TIMESTAMPTZ | NOT NULL DEFAULT now() | |
| `updated_at` | TIMESTAMPTZ | NOT NULL DEFAULT now() | |
| `created_by` | UUID | NOT NULL | |
| `updated_by` | UUID | NOT NULL | |
| `version` | INT | NOT NULL DEFAULT 1 | optimistic concurrency |

#### Indexes

- UNIQUE on `(region, capability)` — one chain per region+capability
- BTree on `enabled`
- GIN on `chain` (analytics queries: "which regions use HERE?")

#### Constraints

- CHECK: `region ~ '^(city:[0-9a-f-]{36}|country:[A-Z]{2}|default)$'`
- CHECK: `capability IN ('geocode_forward','geocode_reverse','eta','route','autocomplete','place_details','static_map')`
- CHECK: `chain <> ARRAY[]::TEXT[]`

### `ProviderHealth`

Recent health-probe results per provider. Append-only;
partitioned monthly; used by the chain resolver and admin UI.

#### Columns

| Column | Type | Constraints | Notes |
|--------|------|-------------|-------|
| `id` | UUID | PK | UUIDv7 |
| `vendor_id` | TEXT | NOT NULL | cross-ref to `provider_config.vendor_id` |
| `probed_at` | TIMESTAMPTZ | NOT NULL DEFAULT now() | partition key |
| `result` | TEXT | NOT NULL | `ok` \| `timeout` \| `http_4xx` \| `http_5xx` \| `dns` \| `tls` |
| `latency_ms` | INT | NULL | end-to-end |
| `capability` | TEXT | NOT NULL | the capability probed |
| `endpoint` | TEXT | NOT NULL | the URL hit (sanitized — no API key) |
| `error_code` | TEXT | NULL | on non-OK |
| `correlation_id` | UUID | NOT NULL | |

#### Indexes

- BTree on `(vendor_id, probed_at DESC)` — recent probes per vendor
- BTree on `(result, probed_at DESC)` — failure trend

#### Partitioning

- Range by `probed_at`, monthly.
- Retention 30 days; drop older partitions.

### `ProviderCircuitState` (in-memory + last-known-state table)

The per-provider circuit-breaker state lives **in memory** for
speed, but is mirrored to this table on every transition so a
restart can restore state. The table is small (one row per
`vendor_id`).

#### Columns

| Column | Type | Constraints | Notes |
|--------|------|-------------|-------|
| `vendor_id` | TEXT | PK | cross-ref to `provider_config.vendor_id` |
| `state` | TEXT | NOT NULL | `closed` \| `open` \| `half_open` |
| `consecutive_failures` | INT | NOT NULL DEFAULT 0 | |
| `opened_at` | TIMESTAMPTZ | NULL | when the circuit last opened |
| `half_open_probes_remaining` | INT | NOT NULL DEFAULT 0 | |
| `last_failure_at` | TIMESTAMPTZ | NULL | |
| `last_success_at` | TIMESTAMPTZ | NULL | |
| `last_transition_at` | TIMESTAMPTZ | NOT NULL DEFAULT now() | |
| `version` | INT | NOT NULL DEFAULT 1 | optimistic concurrency |

#### Constraints

- CHECK: `state IN ('closed','open','half_open')`

### `ProviderUsageDaily` (rolled-up, partitioned)

Daily roll-up used for cost attribution and trend dashboards. A
nightly job aggregates `provider_health` + analytics events into this
table.

#### Columns

| Column | Type | Constraints | Notes |
|--------|------|-------------|-------|
| `vendor_id` | TEXT | NOT NULL | |
| `usage_date` | DATE | NOT NULL | partition key |
| `capability` | TEXT | NOT NULL | |
| `region` | TEXT | NOT NULL | `city:<uuid>` \| `country:<ISO2>` \| `default` |
| `invocations` | BIGINT | NOT NULL DEFAULT 0 | |
| `cache_hits` | BIGINT | NOT NULL DEFAULT 0 | |
| `failures` | BIGINT | NOT NULL DEFAULT 0 | |
| `estimated_cost_usd` | NUMERIC(18,4) | NOT NULL DEFAULT 0 | `invocations * cost_per_1k_usd / 1000` |

#### Indexes

- UNIQUE on `(vendor_id, usage_date, capability, region)`
- BTree on `(usage_date DESC, vendor_id)` — recent trends

#### Partitioning

- Range by `usage_date`, monthly.
- Retention 3 years.

## 4. Mermaid ER Diagram

```mermaid
erDiagram
    GeocodeCache ||--|| ZoneInvalidationState : "inside (spatial, GIST)"
    ProviderConfig ||--o{ ProviderRegionRoute : "appears in chain"
    ProviderConfig ||--o{ ProviderHealth : "probed as"
    ProviderConfig ||--|| ProviderCircuitState : "current circuit"
    ProviderConfig ||--o{ ProviderUsageDaily : "aggregated into"
    GeocodeCache }o--|| ProviderConfig : "produced_by (vendor_id)"
    EtaCache }o--|| ProviderConfig : "produced_by (vendor_id)"
    RouteCache }o--|| ProviderConfig : "produced_by (vendor_id)"
    GeocodeCache {
        uuid id PK
        text cache_key UK
        text kind
        text locale
        uuid region_city_id FK_ref
        text query_fingerprint
        geometry coordinate
        bytea formatted_address_encrypted
        jsonb address_components
        text vendor_id FK_ref
        jsonb vendor_response
        numeric confidence
        timestamptz expires_at
        timestamptz last_accessed_at
        int version
    }
    EtaCache {
        uuid id PK
        text cache_key UK
        geometry origin_coordinate
        geometry destination_coordinate
        int waypoint_count
        int eta_seconds
        int distance_meters
        text traffic_bucket
        text vendor_id FK_ref
        timestamptz expires_at
    }
    RouteCache {
        uuid id PK
        text cache_key UK
        geometry origin_coordinate
        geometry destination_coordinate
        text polyline
        int eta_seconds
        int distance_meters
        jsonb steps
        timestamptz expires_at
    }
    ZoneInvalidationState {
        uuid id PK
        uuid zone_id UK
        uuid city_id FK_ref
        geometry polygon
        geometry bbox
        int polygon_version
    }
    AdminAudit {
        uuid id PK
        timestamptz occurred_at
        text action
        uuid actor_sub
        text result
        uuid correlation_id
    }
    Outbox {
        uuid id PK
        text aggregate_type
        uuid aggregate_id
        text topic
        text event_name
        jsonb payload
        timestamptz published_at
    }
    Inbox {
        uuid event_id PK
        text consumer
        text topic
        timestamptz received_at
        timestamptz processed_at
    }
    ProviderConfig {
        text vendor_id PK
        text display_name
        text adapter_type
        text_array capabilities
        bool is_self_host
        bool is_static_only
        bool enabled
        int priority
        text base_url
        text auth_type
        text vault_secret_path
        int qps_limit
        int burst_limit
        int timeout_ms
        int failure_threshold
        int cooldown_seconds
        int half_open_probe_count
        numeric cost_per_1k_usd
        text_array jurisdictions
        jsonb metadata
        int version
    }
    ProviderRegionRoute {
        uuid id PK
        text region
        text capability
        text_array chain
        bool enabled
        int version
    }
    ProviderHealth {
        uuid id PK
        text vendor_id FK_ref
        timestamptz probed_at
        text result
        int latency_ms
        text capability
        text endpoint
    }
    ProviderCircuitState {
        text vendor_id PK
        text state
        int consecutive_failures
        timestamptz opened_at
        int half_open_probes_remaining
    }
    ProviderUsageDaily {
        text vendor_id PK
        date usage_date PK
        text capability PK
        text region PK
        bigint invocations
        bigint cache_hits
        bigint failures
        numeric estimated_cost_usd
    }
```

## 5. DDL Sketch

```sql
CREATE EXTENSION IF NOT EXISTS postgis;
CREATE EXTENSION IF NOT EXISTS pgcrypto;
CREATE SCHEMA IF NOT EXISTS geolocation;
SET search_path = geolocation, public;

CREATE TABLE geolocation.geocode_cache (
    id UUID PRIMARY KEY,
    cache_key TEXT NOT NULL UNIQUE,
    kind TEXT NOT NULL CHECK (kind IN ('forward', 'reverse')),
    locale TEXT NOT NULL,
    region_city_id UUID,
    query_fingerprint TEXT NOT NULL,
    coordinate geometry(Point, 4326) NOT NULL,
    formatted_address_encrypted BYTEA,
    address_components JSONB,
    vendor_id UUID NOT NULL,
    vendor_response JSONB,
    confidence NUMERIC(4,3) CHECK (confidence IS NULL OR (confidence >= 0 AND confidence <= 1)),
    bbox geometry(Polygon, 4326),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by UUID NOT NULL,
    updated_by UUID NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL CHECK (expires_at > created_at),
    last_accessed_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    version INT NOT NULL DEFAULT 1
);

CREATE INDEX geocode_cache_coordinate_gist ON geolocation.geocode_cache USING GIST (coordinate);
CREATE INDEX geocode_cache_bbox_gist ON geolocation.geocode_cache USING GIST (bbox);
CREATE INDEX geocode_cache_expires_at_idx ON geolocation.geocode_cache (expires_at);
CREATE INDEX geocode_cache_query_fp_idx ON geolocation.geocode_cache (query_fingerprint);
CREATE INDEX geocode_cache_last_accessed_at_idx ON geolocation.geocode_cache (last_accessed_at);
CREATE INDEX geocode_cache_region_city_id_idx ON geolocation.geocode_cache (region_city_id) WHERE region_city_id IS NOT NULL;

CREATE TABLE geolocation.eta_cache (
    id UUID PRIMARY KEY,
    cache_key TEXT NOT NULL UNIQUE,
    origin_coordinate geometry(Point, 4326) NOT NULL,
    destination_coordinate geometry(Point, 4326) NOT NULL,
    waypoint_count INT NOT NULL CHECK (waypoint_count >= 0 AND waypoint_count <= 5),
    departure_time_bucket INT NOT NULL CHECK (departure_time_bucket BETWEEN 0 AND 23),
    traffic_bucket TEXT NOT NULL CHECK (traffic_bucket IN ('low','medium','high','unknown')),
    eta_seconds INT NOT NULL CHECK (eta_seconds >= 0),
    distance_meters INT NOT NULL CHECK (distance_meters >= 0),
    vendor_id UUID NOT NULL,
    vendor_response JSONB,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by UUID NOT NULL,
    updated_by UUID NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL CHECK (expires_at > created_at),
    last_accessed_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    version INT NOT NULL DEFAULT 1
);

CREATE INDEX eta_cache_expires_at_idx ON geolocation.eta_cache (expires_at);
CREATE INDEX eta_cache_origin_dest_idx ON geolocation.eta_cache (origin_coordinate, destination_coordinate);

CREATE TABLE geolocation.route_cache (
    id UUID PRIMARY KEY,
    cache_key TEXT NOT NULL UNIQUE,
    origin_coordinate geometry(Point, 4326) NOT NULL,
    destination_coordinate geometry(Point, 4326) NOT NULL,
    waypoint_count INT NOT NULL CHECK (waypoint_count >= 0 AND waypoint_count <= 5),
    polyline TEXT NOT NULL,
    distance_meters INT NOT NULL,
    eta_seconds INT NOT NULL,
    steps JSONB,
    alternatives JSONB,
    vendor_id UUID NOT NULL,
    vendor_response JSONB,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by UUID NOT NULL,
    updated_by UUID NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL CHECK (expires_at > created_at),
    last_accessed_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    version INT NOT NULL DEFAULT 1
);

CREATE INDEX route_cache_expires_at_idx ON geolocation.route_cache (expires_at);

CREATE TABLE geolocation.zone_invalidation_state (
    id UUID PRIMARY KEY,
    zone_id UUID NOT NULL UNIQUE,
    city_id UUID,
    polygon geometry(Polygon, 4326) NOT NULL,
    bbox geometry(Polygon, 4326) NOT NULL,
    polygon_version INT NOT NULL,
    updated_at_source TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by UUID NOT NULL,
    updated_by UUID NOT NULL
);

CREATE INDEX zone_inv_state_polygon_gist ON geolocation.zone_invalidation_state USING GIST (polygon);
CREATE INDEX zone_inv_state_bbox_gist ON geolocation.zone_invalidation_state USING GIST (bbox);

-- Admin audit (monthly partitioned, append-only)
CREATE TABLE geolocation.admin_audit (
    id UUID NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    action TEXT NOT NULL,
    actor_sub UUID NOT NULL,
    actor_role TEXT NOT NULL,
    tenant_id UUID,
    request_body JSONB NOT NULL,
    request_idempotency_key TEXT,
    signature TEXT NOT NULL,
    result TEXT NOT NULL,
    error_code TEXT,
    correlation_id UUID NOT NULL,
    outbox_event_id UUID,
    PRIMARY KEY (id, occurred_at)
) PARTITION BY RANGE (occurred_at);

CREATE TABLE IF NOT EXISTS geolocation.admin_audit_2026_07
    PARTITION OF geolocation.admin_audit
    FOR VALUES FROM ('2026-07-01 00:00:00+00') TO ('2026-08-01 00:00:00+00');

-- Verify IF NOT EXISTS did not hide a wrong parent or range.
DO $$
DECLARE
    v_parent   REGCLASS := 'geolocation.admin_audit'::REGCLASS;
    v_child    REGCLASS := 'geolocation.admin_audit_2026_07'::REGCLASS;
    v_expected TSTZRANGE := tstzrange('2026-07-01 00:00:00+00',
                                      '2026-08-01 00:00:00+00',
                                      '[)');
BEGIN
    IF (SELECT inhparent FROM pg_inherits WHERE inhrelid = v_child)
       IS DISTINCT FROM v_parent THEN
        RAISE EXCEPTION 'partition % is not attached to %',
            v_child::text, v_parent::text;
    END IF;
    IF NOT (SELECT relpartbound FROM pg_class WHERE oid = v_child)
              = v_expected THEN
        RAISE EXCEPTION 'partition % has unexpected bounds', v_child::text;
    END IF;
END $$;

CREATE INDEX admin_audit_actor_idx ON geolocation.admin_audit (occurred_at DESC, actor_sub);
CREATE INDEX admin_audit_correlation_idx ON geolocation.admin_audit (correlation_id);

CREATE TABLE geolocation.outbox (
    id UUID PRIMARY KEY,
    aggregate_type TEXT NOT NULL,
    aggregate_id UUID NOT NULL,
    topic TEXT NOT NULL,
    event_name TEXT NOT NULL,
    payload JSONB NOT NULL,
    headers JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    claimed_at TIMESTAMPTZ,
    published_at TIMESTAMPTZ,
    attempts INT NOT NULL DEFAULT 0,
    last_error TEXT
);
CREATE INDEX outbox_poller_idx ON geolocation.outbox (claimed_at, created_at);
CREATE INDEX outbox_topic_pub_idx ON geolocation.outbox (topic, published_at);

CREATE TABLE geolocation.inbox (
    event_id UUID PRIMARY KEY,
    consumer TEXT NOT NULL,
    topic TEXT NOT NULL,
    received_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    processed_at TIMESTAMPTZ,
    error TEXT,
    attempts INT NOT NULL DEFAULT 0
);
CREATE INDEX inbox_consumer_received_idx ON geolocation.inbox (consumer, received_at);

-- Provider registry (multi-provider chain model)
CREATE TABLE geolocation.provider_config (
    id UUID PRIMARY KEY,
    vendor_id TEXT NOT NULL UNIQUE,
    display_name TEXT NOT NULL,
    adapter_type TEXT NOT NULL CHECK (adapter_type IN ('commercial_rest','self_host_rest','in_process')),
    capabilities TEXT[] NOT NULL CHECK (cardinality(capabilities) > 0),
    is_self_host BOOL NOT NULL DEFAULT false,
    is_static_only BOOL NOT NULL DEFAULT false,
    enabled BOOL NOT NULL DEFAULT true,
    priority INT NOT NULL DEFAULT 100,
    base_url TEXT,
    auth_type TEXT CHECK (auth_type IS NULL OR auth_type IN ('api_key','oauth2_client_credentials','mtls','none')),
    vault_secret_path TEXT NOT NULL,
    qps_limit INT NOT NULL DEFAULT 100 CHECK (qps_limit > 0),
    burst_limit INT NOT NULL DEFAULT 100 CHECK (burst_limit > 0),
    timeout_ms INT NOT NULL DEFAULT 1500 CHECK (timeout_ms > 0),
    failure_threshold INT NOT NULL DEFAULT 5,
    cooldown_seconds INT NOT NULL DEFAULT 30,
    half_open_probe_count INT NOT NULL DEFAULT 3,
    cost_per_1k_usd NUMERIC(10,4),
    jurisdictions TEXT[] NOT NULL DEFAULT ARRAY['global'],
    metadata JSONB,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by UUID NOT NULL,
    updated_by UUID NOT NULL,
    version INT NOT NULL DEFAULT 1
);
CREATE INDEX provider_config_enabled_idx ON geolocation.provider_config (enabled) WHERE enabled;
CREATE INDEX provider_config_capabilities_gin ON geolocation.provider_config USING GIN (capabilities);
CREATE INDEX provider_config_jurisdictions_gin ON geolocation.provider_config USING GIN (jurisdictions);

-- Per-region chains
CREATE TABLE geolocation.provider_region_route (
    id UUID PRIMARY KEY,
    region TEXT NOT NULL CHECK (region ~ '^(city:[0-9a-f-]{36}|country:[A-Z]{2}|default)$'),
    capability TEXT NOT NULL CHECK (capability IN ('geocode_forward','geocode_reverse','eta','route','autocomplete','place_details','static_map')),
    chain TEXT[] NOT NULL CHECK (cardinality(chain) > 0),
    notes TEXT,
    enabled BOOL NOT NULL DEFAULT true,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by UUID NOT NULL,
    updated_by UUID NOT NULL,
    version INT NOT NULL DEFAULT 1,
    UNIQUE (region, capability)
);
CREATE INDEX provider_region_route_enabled_idx ON geolocation.provider_region_route (enabled) WHERE enabled;
CREATE INDEX provider_region_route_chain_gin ON geolocation.provider_region_route USING GIN (chain);

-- Health-probe history (monthly partitioned)
CREATE TABLE geolocation.provider_health (
    id UUID NOT NULL,
    vendor_id TEXT NOT NULL,
    probed_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    result TEXT NOT NULL CHECK (result IN ('ok','timeout','http_4xx','http_5xx','dns','tls')),
    latency_ms INT,
    capability TEXT NOT NULL,
    endpoint TEXT NOT NULL,
    error_code TEXT,
    correlation_id UUID NOT NULL,
    PRIMARY KEY (id, probed_at)
) PARTITION BY RANGE (probed_at);

CREATE TABLE IF NOT EXISTS geolocation.provider_health_2026_07
    PARTITION OF geolocation.provider_health
    FOR VALUES FROM ('2026-07-01 00:00:00+00') TO ('2026-08-01 00:00:00+00');

CREATE INDEX provider_health_vendor_time_idx ON geolocation.provider_health (vendor_id, probed_at DESC);
CREATE INDEX provider_health_result_time_idx ON geolocation.provider_health (result, probed_at DESC);

-- Circuit-breaker last-known state
CREATE TABLE geolocation.provider_circuit_state (
    vendor_id TEXT PRIMARY KEY,
    state TEXT NOT NULL CHECK (state IN ('closed','open','half_open')),
    consecutive_failures INT NOT NULL DEFAULT 0,
    opened_at TIMESTAMPTZ,
    half_open_probes_remaining INT NOT NULL DEFAULT 0,
    last_failure_at TIMESTAMPTZ,
    last_success_at TIMESTAMPTZ,
    last_transition_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    version INT NOT NULL DEFAULT 1
);

-- Daily cost/usage roll-up (monthly partitioned)
CREATE TABLE geolocation.provider_usage_daily (
    vendor_id TEXT NOT NULL,
    usage_date DATE NOT NULL,
    capability TEXT NOT NULL,
    region TEXT NOT NULL,
    invocations BIGINT NOT NULL DEFAULT 0,
    cache_hits BIGINT NOT NULL DEFAULT 0,
    failures BIGINT NOT NULL DEFAULT 0,
    estimated_cost_usd NUMERIC(18,4) NOT NULL DEFAULT 0,
    PRIMARY KEY (vendor_id, usage_date, capability, region)
) PARTITION BY RANGE (usage_date);

CREATE TABLE IF NOT EXISTS geolocation.provider_usage_daily_2026_07
    PARTITION OF geolocation.provider_usage_daily
    FOR VALUES FROM ('2026-07-01 00:00:00+00') TO ('2026-08-01 00:00:00+00');

CREATE INDEX provider_usage_daily_date_vendor_idx ON geolocation.provider_usage_daily (usage_date DESC, vendor_id);
```

## 6. Audit Columns

Every mutable table has `created_at`, `updated_at`, `created_by`,
`updated_by`. `admin_audit` is append-only (no `updated_*`).

## 7. Soft Delete

**No soft delete.** The service stores cache and audit rows. Cache
rows are TTL-pruned; audit rows are partitioned and dropped. There
is no business entity here that needs to be "soft-deleted".

## 8. JSONB Usage

| Table | Column | Justification |
|-------|--------|---------------|
| `geocode_cache` | `address_components` | structured but vendor-specific shape; rarely queried |
| `geocode_cache`, `eta_cache`, `route_cache` | `vendor_response` | opaque raw vendor payload, never queried |
| `route_cache` | `steps`, `alternatives` | structured turn-by-turn; only returned as-is |
| `admin_audit` | `request_body` | the admin's signed request, for audit |
| `outbox` | `payload`, `headers` | event body, opaque to the schema |
| `inbox` | (n/a) | — |
| `provider_config` | `metadata` | per-provider knobs (e.g. Google `region` bias, HERE `transport_mode`); rarely queried |

No JSONB column is used in a hot `WHERE` clause.

## 9. Partitioning

| Table | Partition strategy | Retention |
|-------|--------------------|-----------|
| `admin_audit` | RANGE by `occurred_at`, monthly | 1y, then drop |
| `provider_health` | RANGE by `probed_at`, monthly | 30d, then drop |
| `provider_usage_daily` | RANGE by `usage_date`, monthly | 3y, then drop |

Cache tables are not partitioned — they are TTL-pruned, not
appended.


See [`DATABASE_ARCHITECTURE.md` §"Table Partitioning — Canonical Template"](../../architecture/DATABASE_ARCHITECTURE.md) for the idempotent `CREATE TABLE IF NOT EXISTS … PARTITION OF …` pattern, naming convention, and the service-owned maintenance-job contract (advisory lock, verification, retention/mixed-retention handling).

## 10. Data Retention

| Table | Retention | Purged by |
|-------|-----------|-----------|
| `geocode_cache` | 24h (configurable via `geolocation.geocode.ttl_seconds`) | background job: `DELETE WHERE expires_at < now()` every 60s |
| `eta_cache` | 60s (configurable) | same job, with per-resource TTL |
| `route_cache` | 300s (configurable) | same job |
| `zone_invalidation_state` | indefinite (kept while zone exists) | removed when ``geolocation-service` (zones)` sends an "end of life" event (currently never — we drop on `zone.deleted` if added) |
| `admin_audit` | 1y | partition drop (monthly) |
| `outbox` | 24h after publish | partition drop after 24h window |
| `inbox` | 7 days | hard delete (`DELETE WHERE received_at < now() - interval '7 days'`) |
| `provider_config` | indefinite | soft-disable via `enabled = false`; row kept for audit |
| `provider_region_route` | indefinite | soft-disable via `enabled = false`; row kept for audit |
| `provider_health` | 30d | partition drop (monthly) |
| `provider_circuit_state` | indefinite | one row per `vendor_id`; updated in place |
| `provider_usage_daily` | 3y | partition drop (monthly) |

## 11. Migration Considerations

- **Cache tables are large** (~50M geocode rows). Adding an index
  is online (`CREATE INDEX CONCURRENTLY`). Removing a column is
  done in a multi-step plan: add new column, dual-write, switch
  reads, switch writes, drop old.
- **PostGIS extension** must be present before any migration
  that uses `geometry` types. The first migration in the repo
  creates the extension (`CREATE EXTENSION IF NOT EXISTS postgis`).
- **Encryption rotation** for `formatted_address_encrypted`:
  when the column key (DEK) rotates, the migration reads rows in
  batches, decrypts with the old key, re-encrypts with the new
  key, and updates in place. Done as a background job, not a
  blocking migration.
- **Adding a new cache table** is fine; no cross-service coupling.
- **Removing a cache table** is fine once all readers are gone;
  done as a multi-step plan.
- **Partition pre-creation**: a maintenance job creates the next
  12 monthly partitions of `admin_audit` on the 1st of each
  month.

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

