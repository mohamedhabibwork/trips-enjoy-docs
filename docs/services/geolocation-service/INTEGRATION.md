# geolocation-service — Integration Contract

## 1. Inbound APIs

All endpoints follow `architecture/API_STANDARDS.md` (JSON, JWT,
cursor pagination on lists, error envelope, `X-Correlation-Id`,
`Idempotency-Key` on POSTs, OpenAPI 3.1 spec at `/openapi.json`).

### 1.1 `POST /v1/geocodes` (forward geocode)

- **Purpose**: Resolve a free-text address to a coordinate and
  a structured `GeoAddress`.
- **Auth**: Bearer JWT (any role); internal callers use a
  `service` role. Rate-limited per `sub` and per IP.
- **Idempotency**: not required (the operation is read-only;
  cache writes are idempotent on `cache_key`).
- **Request**:
  ```json
  {
    "address": "1600 Amphitheatre Parkway, Mountain View, CA",
    "locale": "en",
    "region_city_id": "01HZX9C5S3B1L7K0P2F8V4T6YDA",
    "components": {
      "country": "US"
    }
  }
  ```
- **Response (200)**:
  ```json
  {
    "id": "01HZX9C8W6K0G3V2Y5N1Q4R7PB",
    "kind": "forward",
    "locale": "en",
    "coordinate": { "lat": 37.4223878, "lon": -122.0841878 },
    "formatted_address": "1600 Amphitheatre Pkwy, Mountain View, CA 94043, USA",
    "address_components": {
      "street": "Amphitheatre Pkwy",
      "street_number": "1600",
      "city": "Mountain View",
      "state": "CA",
      "postal_code": "94043",
      "country": "US"
    },
    "confidence": 0.987,
    "bbox": {
      "min_lat": 37.4210, "min_lon": -122.0855,
      "max_lat": 37.4237, "max_lon": -122.0828
    },
    "cache_hit": true,
    "provider": {
      "vendor_id": "here",
      "chain_position": 0,
      "role": "primary",
      "capability": "geocode_forward",
      "region": "city:01HZX9C5S3B1L7K0P2F8V4T6YDA",
      "is_self_host": false
    },
    "occurred_at": "2026-07-29T10:42:11.183Z"
  }
  ```
- **Errors**:
  - 400 `VALIDATION_FAILED` — missing or malformed `address`.
  - 401 `UNAUTHENTICATED` — missing / invalid bearer.
  - 403 `FORBIDDEN` — role missing.
  - 422 `ADDRESS_UNSUPPORTED_REGION` — country not served by any
    configured provider.
  - 429 `RATE_LIMITED` — per-user or per-IP.
  - 503 `CIRCUIT_OPEN` — primary and fallback both unavailable.
  - 504 `DEPENDENCY_TIMEOUT` — vendor timeout after retries.
- **Validation**:
  - `address` length 3..256.
  - `locale` ∈ {`en`, `ar`, … configured}.
  - `region_city_id` if present must be a UUID; not validated
    against ``geolocation-service` (zones)` on the read path (cache key includes
    it, so a wrong one simply misses the cache).

### 1.2 `GET /v1/geocodes/reverse` (reverse geocode)

- **Purpose**: Resolve a coordinate to a structured `GeoAddress`.
- **Auth**: Bearer JWT; rate-limited.
- **Request (query)**:
  ```
  ?lat=37.4224&lon=-122.0842&locale=en
  ```
- **Response (200)**:
  ```json
  {
    "id": "01HZX9C8W6K0G3V2Y5N1Q4R7PC",
    "kind": "reverse",
    "locale": "en",
    "coordinate": { "lat": 37.4224, "lon": -122.0842 },
    "formatted_address": "Amphitheatre Pkwy, Mountain View, CA 94043, USA",
    "address_components": { ... },
    "cache_hit": true,
    "approximate": false,
    "occurred_at": "2026-07-29T10:42:11.183Z"
  }
  ```
- **Errors**: same as 1.1, plus 404 `NOT_FOUND` if the coordinate
  is in an unmapped area and `approximate=true` is not acceptable
  (callers can pass `?approximate=true` to accept the centroid of
  the enclosing zone).

### 1.3 `POST /v1/etas`

- **Purpose**: Compute an ETA between two points (with optional
  waypoints).
- **Auth**: Bearer JWT; rate-limited.
- **Request**:
  ```json
  {
    "origin":      { "lat": 37.4224, "lon": -122.0842 },
    "destination": { "lat": 37.7749, "lon": -122.4194 },
    "waypoints":   [],
    "departure_time": "2026-07-29T11:00:00Z",
    "traffic_bucket": "high"
  }
  ```
- **Response (200)**:
  ```json
  {
    "id": "01HZX9C8W6K0G3V2Y5N1Q4R7PD",
    "eta_seconds": 2640,
    "distance_meters": 52300,
    "traffic_bucket": "high",
    "cache_hit": false,
    "provider": {
      "vendor_id": "osrm",
      "chain_position": 2,
      "role": "fallback",
      "capability": "eta",
      "region": "city:01HZX9C5S3B1L7K0P2F8V4T6YDA",
      "is_self_host": true
    },
    "occurred_at": "2026-07-29T10:42:11.183Z"
  }
  ```
- **Errors**: same as 1.1, plus 400 if `waypoints` > 5.

### 1.4 `POST /v1/routes`

- **Purpose**: Compute a full route between two points.
- **Auth**: Bearer JWT; rate-limited.
- **Request**:
  ```json
  {
    "origin":      { "lat": 37.4224, "lon": -122.0842 },
    "destination": { "lat": 37.7749, "lon": -122.4194 },
    "waypoints":   [],
    "alternatives": false,
    "geometry": "polyline"
  }
  ```
- **Response (200)**:
  ```json
  {
    "id": "01HZX9C8W6K0G3V2Y5N1Q4R7PE",
    "polyline": "gfo}EvrgsVjE_...",
    "distance_meters": 52300,
    "eta_seconds": 2640,
    "steps": [
      { "instruction": "Head north on Amphitheatre Pkwy", "distance_meters": 80, "duration_seconds": 12 }
    ],
    "alternatives": null,
    "cache_hit": false,
    "vendor_id": "01HZX9C5G3V1L7K0P2F8V4T6YDB",
    "occurred_at": "2026-07-29T10:42:11.183Z"
  }
  ```
- **Errors**: same as 1.1, plus 400 if `waypoints` > 5 or
  `geometry` not in {`polyline`,`geojson`}.

### 1.5 `GET /v1/cities/lookup`

- **Purpose**: Resolve a coordinate to the platform's `city_id`
  and the city name.
- **Auth**: Bearer JWT.
- **Request (query)**: `?lat=37.4224&lon=-122.0842`
- **Response (200)**:
  ```json
  {
    "city_id": "01HZX9C5S3B1L7K0P2F8V4T6YDA",
    "name": "San Francisco Bay Area",
    "country_code": "US",
    "timezone": "America/Los_Angeles",
    "occurred_at": "2026-07-29T10:42:11.183Z"
  }
  ```
- **Errors**:
  - 404 `CITY_NOT_FOUND` if the coordinate is outside any
    defined service zone.

### 1.6 `POST /v1/admin/cache/purge`

- **Purpose**: Force a cache purge by filter.
- **Auth**: Bearer JWT + role `admin` or `platform_engineer`;
  body MUST be HMAC-SHA256 signed.
- **Idempotency**: `Idempotency-Key` required.
- **Request**:
  ```json
  {
    "filter": {
      "city_id": "01HZX9C5S3B1L7K0P2F8V4T6YDA"
    },
    "resources": ["geocode", "eta", "route"],
    "reason": "vendor regression — forced revalidation"
  }
  ```
  `filter` is one of: `{ "city_id": "…" }`, `{ "bbox": { … } }`,
  `{ "query_fingerprint": "…" }`.
- **Response (202)**:
  ```json
  {
    "purge_id": "01HZX9C8W6K0G3V2Y5N1Q4R7PF",
    "affected_geocode_rows": 12340,
    "affected_eta_rows": 5000,
    "affected_route_rows": 2100,
    "occurred_at": "2026-07-29T10:42:11.183Z"
  }
  ```
- **Errors**:
  - 400 `VALIDATION_FAILED` if no `filter`, no `resources`, or
    `reason` empty.
  - 401 `UNAUTHENTICATED` / 403 `FORBIDDEN`.
  - 409 `SIGNATURE_INVALID` (HMAC mismatch).
  - 422 `IDEMPOTENCY_KEY_REUSED` (key with different body).
  - 503 `CIRCUIT_OPEN` (database unreachable).
- **Validation**:
  - `filter` non-null; at least one of `city_id`, `bbox`,
    `query_fingerprint` set.
  - `resources` non-empty subset of {`geocode`,`eta`,`route`}.
  - `reason` non-empty, ≤ 256 chars.

## 2. Outbound APIs

The service has **no fixed outbound URLs** — every call goes through
the resolved provider chain (4). The table below lists the **logical
outbound categories**, not specific URLs. Per-provider base URLs and
auth live in `provider_config`.

| Target category | Purpose | Timeout | Retry | Circuit |
|-----------------|---------|---------|-------|---------|
| Map provider (commercial REST) | geocode / reverse-geocode / route / ETA / autocomplete / place details / static map | per `provider_config.timeout_ms` | 2 (exponential; advances chain on retryable failure) | yes (per `vendor_id`) |
| Map provider (self-host REST) | routing + ETA (OSRM, Valhalla) or geocode (Nominatim, Pelias, Photon) | per `provider_config.timeout_ms` | 2 | yes |
| Map provider (in-process) | mock for dev/test/CI | n/a | n/a | n/a |
| ``geolocation-service` (zones)` | GET `/v1/zones/{zone_id}` — resolve zone metadata for cache-key scoping | 500ms | 2 | yes |
| `configuration-service` | GET `/v1/config/geolocation` — read TTLs, default chain, circuit-breaker parameters | 500ms | 3 | yes |
| ``configuration-service` (flags)` | GET `/v1/flags/...` — mock-provider toggle, force-static-mode, vendor-disable flags | 300ms | 1 | yes |

All outbound calls carry `X-Correlation-Id` and `traceparent` from
the inbound request. All timeouts are absolute (per
`FAILURE_HANDLING.md`).

## 3. Produced Events

### 3.1 `geolocation.geocoded.v1`

- **Producer**: `geolocation-service`.
- **Topic**: `geolocation.geocoded`.
- **Trigger**: every forward or reverse geocode request, whether
  cache hit or cache miss.
- **Schema version**: 1.
- **Partition key**: `request_id` (so a burst from one user stays
  ordered; we don't have a stable `aggregate_id` for ephemeral
  cache reads).
- **Consumers**: ``reporting-service` (data lake)`, `reporting-service`.
- **Schema** (envelope per `EVENT_ARCHITECTURE.md`):
  ```json
  {
    "event_id": "01HZX9C8W6K0G3V2Y5N1Q4R7PB",
    "event_name": "geolocation.geocoded.v1",
    "occurred_at": "2026-07-29T10:42:11.183Z",
    "schema_version": 1,
    "producer": "geolocation-service",
    "tenant_id": "global",
    "correlation_id": "01HZX9C7T0XK2P9F0V6E4B1MZA",
    "causation_id": null,
    "aggregate_type": "Geocode",
    "aggregate_id": "01HZX9C8W6K0G3V2Y5N1Q4R7PB",
    "data": {
      "kind": "forward",
      "locale": "en",
      "cache_hit": true,
      "vendor_id": "here",
      "chain_position": 0,
      "role": "primary",
      "region": "city:01HZX9C5S3B1L7K0P2F8V4T6YDA",
      "capability": "geocode_forward",
      "is_self_host": false,
      "coordinate": { "lat": 37.4223878, "lon": -122.0841878 },
      "city_id": "01HZX9C5S3B1L7K0P2F8V4T6YDA",
      "confidence": 0.987,
      "latency_ms": 42
    }
  }
  ```
- **Retry**: outbox pattern, 3 attempts; backoff 1s, 4s, 16s.
- **DLQ**: `geolocation.geocoded.dlq`.

### 3.2 `geolocation.eta.computed.v1`

- **Producer**: `geolocation-service`.
- **Topic**: `geolocation.eta.computed`.
- **Trigger**: every ETA request.
- **Partition key**: `request_id`.
- **Schema (data)**:
  ```json
  {
    "eta_seconds": 2640,
    "distance_meters": 52300,
    "traffic_bucket": "high",
    "cache_hit": false,
    "vendor_id": "here",
    "chain_position": 0,
    "role": "primary",
    "region": "city:01HZX9C5S3B1L7K0P2F8V4T6YDA",
    "capability": "eta",
    "city_id_origin": "01HZX9C5S3B1L7K0P2F8V4T6YDA",
    "city_id_destination": "01HZX9C5S3B1L7K0P2F8V4T6DB",
    "is_self_host": false,
    "latency_ms": 380
  }
  ```
- **Retry / DLQ**: same as 3.1.

### 3.3 `geolocation.cache.invalidated.v1`

- **Producer**: `geolocation-service`.
- **Topic**: `geolocation.cache.invalidated`.
- **Trigger**: every cache purge — zone-driven or admin-driven.
- **Partition key**: `purge_id` (or `zone_id` for zone-driven).
- **Schema (data)**:
  ```json
  {
    "purge_id": "01HZX9C8W6K0G3V2Y5N1Q4R7PF",
    "trigger": "zone_updated",
    "zone_id": "01HZX9C5S3B1L7K0P2F8V4T6YDC",
    "filter": { "city_id": "01HZX9C5S3B1L7K0P2F8V4T6YDA" },
    "resources": ["geocode", "eta", "route"],
    "affected_rows": 19440,
    "actor_sub": null,
    "reason": "zone_updated",
    "correlation_id": "01HZX9C7T0XK2P9F0V6E4B1MZA"
  }
  ```
- **Retry / DLQ**: same as 3.1.
- **Consumers**: `audit-service` (high-severity purges),
  ``reporting-service` (data lake)` (cache-effectiveness dashboard).

### 3.4 `geolocation.provider_chain.changed.v1`

- **Producer**: `geolocation-service`.
- **Topic**: `geolocation.provider_chain.changed`.
- **Trigger**: an admin edits `provider_config` or `provider_region_route`
  (via `PUT /v1/admin/region-chains/...` or the `provider_config`
  admin endpoints).
- **Partition key**: `vendor_id` (or `region` for region-chain edits).
- **Schema (data)**:
  ```json
  {
    "change_kind": "region_chain_updated",
    "region": "city:01HZX9C5S3B1L7K0P2F8V4T6YDA",
    "capability": "geocode_forward",
    "previous_chain": ["google", "mapbox"],
    "new_chain": ["here", "google", "osrm"],
    "actor_sub": "01HZX9C7T0XK2P9F0V6E4B1MZA",
    "reason": "GDPR routing — HERE first",
    "correlation_id": "01HZX9C8X1N4M5K7B8V3R0Q9D2H"
  }
  ```
- **Retry / DLQ**: same as 3.1.
- **Consumers**: `audit-service` (always), ``reporting-service` (data lake)`
  (chain-effectiveness dashboards), all other `geolocation-service`
  replicas (to invalidate their in-memory chain cache within 60 s).

### 3.5 `geolocation.provider_health.v1`

- **Producer**: `geolocation-service`.
- **Topic**: `geolocation.provider_health`.
- **Trigger**: a background health probe completes (success or
  failure).
- **Partition key**: `vendor_id`.
- **Schema (data)**:
  ```json
  {
    "vendor_id": "here",
    "probed_at": "2026-07-29T10:42:11.183Z",
    "result": "ok",
    "latency_ms": 142,
    "capability": "geocode_forward",
    "circuit_state_after": "closed",
    "correlation_id": "01HZX9C8X1N4M5K7B8V3R0Q9D2H"
  }
  ```
- **Retry / DLQ**: same as 3.1.
- **Consumers**: ``reporting-service` (data lake)` (probe dashboards), `audit-service`
  (circuit transitions).

## 4. Provider Adapter Contract

Every map provider — commercial (Google, Mapbox, HERE) or self-host
(OSRM, Valhalla, Nominatim, Pelias, Photon) — implements the same Go
interface and is registered with `provider_config.vendor_id`. This is
the **single integration point** between geocoding logic and the
network.

### 4.1 Core interface

```go
// MapProvider is implemented by every adapter (commercial or self-host).
type MapProvider interface {
    // Metadata
    VendorID() string                       // e.g. "here", "osrm"
    DisplayName() string
    Capabilities() []Capability              // subset of the 7
    AdapterType() AdapterType                // commercial_rest / self_host_rest / in_process
    IsSelfHost() bool
    IsStaticOnly() bool
    Jurisdictions() []string                // ISO2 codes

    // Capability calls
    GeocodeForward(ctx context.Context, req GeocodeRequest) (GeocodeResult, error)
    GeocodeReverse(ctx context.Context, req ReverseRequest) (GeocodeResult, error)
    Eta(ctx context.Context, req EtaRequest) (EtaResult, error)
    Route(ctx context.Context, req RouteRequest) (RouteResult, error)
    Autocomplete(ctx context.Context, req AutocompleteRequest) ([]PlaceCandidate, error)
    PlaceDetails(ctx context.Context, req PlaceDetailsRequest) (PlaceDetails, error)
    StaticMap(ctx context.Context, req StaticMapRequest) (string, error) // returns URL

    // Lifecycle
    HealthCheck(ctx context.Context) error  // cheap probe; called by the background prober
    Close() error
}
```

### 4.2 Built-in adapter implementations

| Vendor | Adapter file | Auth | Capabilities | Notes |
|---|---|---|---|---|
| Google | `internal/provider/google/` | `api_key` (Vault) | all 7 | Quota project via `metadata.quota_project_id` |
| Mapbox | `internal/provider/mapbox/` | `api_key` (Vault) | all 7 | `metadata.dataset` for tile-accurate geocode |
| HERE | `internal/provider/here/` | `oauth2_client_credentials` (Vault) + optional `mtls` | all 7 | `metadata.transport_mode` for traffic-aware ETA |
| OSRM | `internal/provider/osrm/` | `mtls` or `none` (LAN) | `eta`, `route` | Reads `.osrm` extracts from a shared volume; no geocode |
| Valhalla | `internal/provider/valhalla/` | `mtls` or `none` (LAN) | `eta`, `route` | Tile-server backed |
| Nominatim | `internal/provider/nominatim/` | `none` (LAN) | `geocode_forward`, `geocode_reverse` | OSM-based; rate-limited 1 req/s — fallback only |
| Pelias | `internal/provider/pelias/` | `api_key` (Vault) or `none` | all except `static_map` | Modular OSM geocoder |
| Photon | `internal/provider/photon/` | `none` (LAN) | `geocode_forward`, `geocode_reverse` | Lighter than Pelias |
| Mock | `internal/provider/mock/` | `none` | all 7 | Deterministic; used in dev/test/CI |

### 4.3 Adapter registration

Every adapter registers itself at startup:

```go
provider.MustRegister(google.New(vault))
provider.MustRegister(osrm.New(cfg.OSRM.BaseURL, cfg.OSRM.MTLSCert))
provider.MustRegister(mock.New(fixturesFS))
```

The chain resolver looks up providers by `vendor_id` from
`provider_config`. If a `vendor_id` is referenced in
`provider_region_route.chain` but no adapter is registered, the
chain resolver logs a warning and skips that member (treated as if
its circuit is open).

### 4.4 Canonical translation

Each adapter MUST translate the vendor's response into the canonical
types (`GeoAddress`, `EtaEstimate`, `Route`, `PlaceCandidate`,
`PlaceDetails`) defined in `internal/provider/types.go`. Adapters
NEVER return vendor-specific shapes from capability calls; the
`provider` field in the public response is metadata only.

## 5. Admin API (Provider Management)

In addition to `POST /v1/admin/cache/purge` (1.6) and
`POST /v1/admin/providers/rotate` (existing), the service exposes a
**provider admin API** for runtime chain management without redeploy
(FR--026).

### 5.1 `GET /v1/admin/providers`

- **Purpose**: List every configured provider and its current
  health / circuit state.
- **Auth**: Bearer JWT + role `admin` or `platform_engineer`.
- **Response (200)**:
  ```json
  {
    "providers": [
      {
        "vendor_id": "here",
        "display_name": "HERE Maps",
        "adapter_type": "commercial_rest",
        "is_self_host": false,
        "enabled": true,
        "capabilities": ["geocode_forward","geocode_reverse","eta","route","autocomplete","place_details","static_map"],
        "circuit_state": "closed",
        "consecutive_failures": 0,
        "last_success_at": "2026-07-29T10:42:11.183Z",
        "qps_limit": 200,
        "jurisdictions": ["global"]
      }
    ],
    "occurred_at": "2026-07-29T10:42:11.183Z"
  }
  ```

### 5.2 `GET /v1/admin/providers/{vendor_id}`

- **Purpose**: Get full detail for one provider, including recent
  health-probe history (last 50).
- **Auth**: Bearer JWT + role `admin` or `platform_engineer`.
- **Response (200)**: the provider record + `recent_probes`
  array.

### 5.3 `POST /v1/admin/providers/{vendor_id}/test`

- **Purpose**: Invoke the named provider directly (bypasses the
  chain), to verify a vendor is healthy after an incident.
- **Auth**: Bearer JWT + role `admin` or `platform_engineer`; mTLS.
- **Idempotency**: `Idempotency-Key` required.
- **Request**:
  ```json
  {
    "capability": "geocode_forward",
    "query": { "address": "Dubai Mall", "locale": "en" }
  }
  ```
- **Response (200)**: the canonical result with `provider.role`
  set to `"direct"`.

### 5.4 `PUT /v1/admin/region-chains/{region}/{capability}`

- **Purpose**: Set the chain for `(region, capability)` at runtime
  — no redeploy.
- **Auth**: Bearer JWT + role `platform_engineer`; mTLS; co-signature
  by a second `platform_engineer` required.
- **Idempotency**: `Idempotency-Key` required.
- **Request**:
  ```json
  {
    "chain": ["here", "google", "osrm"],
    "enabled": true,
    "notes": "GDPR routing — HERE first, self-host fallback",
    "reason": "vendor incident — switch primary to HERE"
  }
  ```
  `region` is one of `default`, `country:<ISO2>`, `city:<uuid>`.
- **Response (200)**:
  ```json
  {
    "region": "city:01HZX9C5S3B1L7K0P2F8V4T6YDA",
    "capability": "geocode_forward",
    "chain": ["here", "google", "osrm"],
    "version": 4,
    "applied_at": "2026-07-29T10:42:11.183Z"
  }
  ```
- **Errors**:
  - 400 `VALIDATION_FAILED` — `chain` empty, contains unknown
    `vendor_id`, or contains a disabled provider.
  - 403 `FORBIDDEN` / 409 `CO_SIGNATURE_MISSING`.
  - 503 `CHAIN_PROVIDER_DOWN` — every member of the new chain is
    circuit-open (rejected to prevent silent unavailability).
- **Side effects**:
  - Writes the row to `provider_region_route` (with optimistic
    concurrency on `version`).
  - Invalidates the in-memory chain cache on every replica via
    the `geolocation.provider_chain.changed.v1` event.
  - Emits `audit.admin.geolocation.v1` with `action =
    "region_chain_updated"`.

### 5.5 `PATCH /v1/admin/providers/{vendor_id}`

- **Purpose**: Toggle `enabled`, update `qps_limit`, `timeout_ms`,
  `cost_per_1k_usd`, `jurisdictions`, or `metadata`.
- **Auth**: Bearer JWT + role `platform_engineer`.
- **Idempotency**: `Idempotency-Key` required.
- **Request** (any subset):
  ```json
  {
    "enabled": false,
    "qps_limit": 50,
    "metadata": { "transport_mode": "car" }
  }
  ```
- **Side effects**: emits `geolocation.provider_chain.changed.v1`
  with `change_kind = "provider_config_updated"`.

## 6. Consumed Events

### 6.1 `zone.updated.v1`

- **Producer**: ``geolocation-service` (zones)`.
- **Topic**: `zone.zone.updated`.
- **Reason**: polygon changed; cached geocodes, ETAs, and routes
  whose key intersects the updated polygon may be stale.
- **Handler**:
  1. Inbox insert (`event_id`, `zone-update-invalidator`).
  2. Update `zone_invalidation_state` row.
  3. Enqueue a background job that, for each resource type
     (`geocode`, `eta`, `route`), deletes cache rows whose
     coordinate or bbox intersects the polygon (`ST_Intersects`).
  4. Emit `geolocation.cache.invalidated.v1` with the row counts.
- **Deduplication**: inbox on `event_id`.
- **Retry**: 3 with backoff (1s, 4s, 16s).
- **Failure**: DLQ `zone.zone.updated.dlq` after 3 failures.

### 6.2 `configuration.updated.v1`

- **Producer**: `configuration-service`.
- **Topic**: `configuration.configuration.updated`.
- **Reason**: TTL, vendor selection, rate limits, surge rules
  changed.
- **Handler**:
  1. Inbox insert.
  2. Compute new config hash; if different from current, atomically
     swap the in-memory config object.
  3. If `geolocation.provider` changed, drain in-flight vendor
     calls and switch the active vendor on next request.
- **Deduplication**: inbox on `event_id`.
- **Retry**: 3 with backoff.
- **Failure**: DLQ.

### 6.3 `feature_flag.updated.v1`

- **Producer**: ``configuration-service` (flags)`.
- **Topic**: `feature_flag.feature_flag.updated`.
- **Reason**: `geolocation.mock_provider` (or other) flag
  changed; we may need to swap to the mock provider in dev /
  staging.
- **Handler**: re-evaluate the flag; swap the active provider
  adapter if needed.
- **Deduplication**: inbox on `event_id`.
- **Retry**: 3 with backoff.
- **Failure**: DLQ.

## 7. Reliability

- **Timeouts** (defaults; per-provider override via
  `provider_config.timeout_ms`):
  - Map provider: 1.5s.
  - ``geolocation-service` (zones)`: 500ms.
  - `configuration-service`: 500ms.
  - ``configuration-service` (flags)`: 300ms.
- **Retries** (exponential backoff with jitter, max 3 attempts):
  - All outbound calls retry on 5xx and timeout. Never retry on
    4xx (except 429 with `Retry-After`).
  - The chain resolver does **not** retry the same vendor; on
    retryable failure it advances to the next chain member.
- **Circuit breakers** (per-provider, per-downstream):
  - Open on ≥ `provider_config.failure_threshold` consecutive
    failures within the cooldown window.
  - Half-open after `provider_config.cooldown_seconds`; admit
    `half_open_probe_count` probe requests.
  - Close on the first success in half-open state.
  - State mirrored to `provider_circuit_state` on every transition
    (see ERD 3.4) so restarts restore.
- **Bulkheads**: each vendor has a separate connection pool
  (Node fetch agent with its own keep-alive agent and pool).
- **Outbox**: `geolocation.outbox` table; a poller publishes to
  Kafka at-least-once; rows are purged 24h after `published_at`.
- **Inbox**: `geolocation.inbox` table; consumers dedupe on
  `event_id`.
- **DLQ**: every topic has a paired `<topic>.dlq`; messages
  routed after 3 failed attempts.
- **Reconciliation**: a daily job re-publishes any
  `admin_audit` row whose `outbox_event_id` is null and whose
  age > 5 minutes (catches missed emissions).

## 8. Correlation IDs

- The inbound `X-Correlation-Id` (or one generated by the gateway)
  is propagated to:
  - All outbound HTTP calls (header `X-Correlation-Id`).
  - All log lines in the request scope.
  - The `correlation_id` field of every emitted event envelope.
  - The `headers.correlation_id` of every outbox row.
- The inbound `traceparent` is propagated to all outbound HTTP
  calls and to Kafka header `traceparent`.
- The `causation_id` of an emitted event is the `event_id` of
  the consumed event that caused it (e.g. for
  `geolocation.cache.invalidated.v1` caused by `zone.updated.v1`).

## 9. Distributed Tracing

- OpenTelemetry SDK, auto-instruments:
  - HTTP server (inbound): root span `POST /v1/geocodes` etc.
  - HTTP client (outbound): child span `vendor.geocode`,
    ``geolocation-service` (zones).GET /v1/zones/{id}`, etc.
  - Kafka producer: child span `kafka.publish geolocation.geocoded`.
  - Kafka consumer: child span `kafka.consume zone.zone.updated`.
  - PostgreSQL queries: child span `db.query`.
  - Redis calls: child span `redis.get` etc.
- Sample 100% of errors, 10% of successes in production; 100% in
  staging.
- One trace per request; the inbound `traceparent` is honored if
  present.


## Downstream isolation

This section describes how this service handles failures in
its upstream and downstream services. The platform-wide
isolation playbook — including the per-class (CRITICAL /
DEGRADABLE / BEST-EFFORT) behavior, the dependency matrix,
and the configuration knobs — is in
[`../../architecture/SERVICE_ISOLATION.md`](../../architecture/SERVICE_ISOLATION.md).
The canonical error-code catalog and propagation rules are in
[`../../architecture/DOWNSTREAM_ERROR_CATALOG.md`](../../architecture/DOWNSTREAM_ERROR_CATALOG.md).

When this service's own code fails unexpectedly, it returns
`500 INTERNAL_ERROR`. When an error originates from another
service, this service follows the propagation rules in
[`DOWNSTREAM_ERROR_CATALOG.md` 5](../../architecture/DOWNSTREAM_ERROR_CATALOG.md)
(forward verbatim, translate, degrade, or reject) and includes
a `downstream` block identifying the original source.

### Upstream services this service depends on

| Upstream | Class | Behavior on failure |
|---|---|---|
| [``customer-service` (addresses)`](../customer-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [`admin-service`](../admin-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [``reporting-service` (data lake)`](../reporting-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [`audit-service`](../audit-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [`configuration-service`](../configuration-service/README.md) | DEGRADABLE | degrade (cache / default / flag) |
| [``courier-service` (tracking)`](../courier-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [``courier-service` (delivery)`](../courier-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [``driver-service` (location)`](../driver-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [``geolocation-service` (ETA/routing)`](../geolocation-service/README.md) | DEGRADABLE | degrade (cache / default / flag) |
| [``configuration-service` (flags)`](../configuration-service/README.md) | DEGRADABLE | degrade (cache / default / flag) |
| [`reporting-service`](../reporting-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [``trip-service` (ride-request)`](../trip-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [`trip-service`](../trip-service/README.md) | BEST-EFFORT | log WARN; outbox for durable side-effects |
| [``geolocation-service` (zones)`](../geolocation-service/README.md) | DEGRADABLE | degrade (cache / default / flag) |

### Downstream services that depend on this service

| Downstream | Class (from its perspective) |
|---|---|
| [``customer-service` (addresses)`](../customer-service/README.md) | _see [`SERVICE_ISOLATION.md` 2](../../architecture/SERVICE_ISOLATION.md)_ |
| [``restaurant-service` (branch)`](../restaurant-service/README.md) | _see [`SERVICE_ISOLATION.md` 2](../../architecture/SERVICE_ISOLATION.md)_ |
| [``courier-service` (dispatch)`](../courier-service/README.md) | _see [`SERVICE_ISOLATION.md` 2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`courier-service`](../courier-service/README.md) | _see [`SERVICE_ISOLATION.md` 2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`customer-service`](../customer-service/README.md) | _see [`SERVICE_ISOLATION.md` 2](../../architecture/SERVICE_ISOLATION.md)_ |
| [``courier-service` (delivery)`](../courier-service/README.md) | _see [`SERVICE_ISOLATION.md` 2](../../architecture/SERVICE_ISOLATION.md)_ |
| [``driver-service` (location)`](../driver-service/README.md) | _see [`SERVICE_ISOLATION.md` 2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`driver-service`](../driver-service/README.md) | _see [`SERVICE_ISOLATION.md` 2](../../architecture/SERVICE_ISOLATION.md)_ |
| [``geolocation-service` (ETA/routing)`](../geolocation-service/README.md) | _see [`SERVICE_ISOLATION.md` 2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`pricing-service`](../pricing-service/README.md) | _see [`SERVICE_ISOLATION.md` 2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`restaurant-service`](../restaurant-service/README.md) | _see [`SERVICE_ISOLATION.md` 2](../../architecture/SERVICE_ISOLATION.md)_ |
| [``trip-service` (ride-request)`](../trip-service/README.md) | _see [`SERVICE_ISOLATION.md` 2](../../architecture/SERVICE_ISOLATION.md)_ |
| [`search-service`](../search-service/README.md) | _see [`SERVICE_ISOLATION.md` 2](../../architecture/SERVICE_ISOLATION.md)_ |
| [``geolocation-service` (zones)`](../geolocation-service/README.md) | _see [`SERVICE_ISOLATION.md` 2](../../architecture/SERVICE_ISOLATION.md)_ |

### Per-downstream configuration

Per-downstream timeout / bulkhead / circuit / retry / fallback
configuration lives in the service's application config
(Kotlin: `application.yml` under `platform.outbounds.*`;
Go: `internal/outbounds/manifest.yaml`). The shared library
(`platform-spring-boot-bulkhead` for Kotlin, `internal/bulkhead`
for Go) reads the manifest and wires up the isolation pattern.

### Error envelope

Every error response uses the platform envelope defined in
[`../../shared/CONVENTIONS.md` 1](../../shared/CONVENTIONS.md)
(RFC 7807 + `downstream` block). The codes this service emits
are in 1 of this document; the canonical catalog is in
[`DOWNSTREAM_ERROR_CATALOG.md`](../../architecture/DOWNSTREAM_ERROR_CATALOG.md).


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

