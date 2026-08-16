-- 000002_geocode_cache.up.sql
--
-- geolocation.geocode_cache — durable cache for forward + reverse geocodes
-- per docs/services/geolocation-service/ERD.md §3.1.
--
-- Requires the postgis + pgcrypto extensions (created at the DB level
-- by the DBA — see ERD §1). Idempotent via IF NOT EXISTS.
CREATE TABLE IF NOT EXISTS geolocation.geocode_cache (
    id                          UUID PRIMARY KEY,
    cache_key                   TEXT NOT NULL UNIQUE,
    kind                        TEXT NOT NULL CHECK (kind IN ('forward', 'reverse')),
    locale                      TEXT NOT NULL,
    region_city_id              UUID,
    query_fingerprint           TEXT NOT NULL,
    coordinate                  geometry(Point, 4326) NOT NULL,
    formatted_address_encrypted BYTEA,
    address_components          JSONB,
    vendor_id                   UUID NOT NULL,
    vendor_response             JSONB,
    confidence                  NUMERIC(4,3) CHECK (confidence IS NULL OR (confidence >= 0 AND confidence <= 1)),
    bbox                        geometry(Polygon, 4326),
    created_at                  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at                  TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by                  UUID NOT NULL,
    updated_by                  UUID NOT NULL,
    expires_at                  TIMESTAMPTZ NOT NULL CHECK (expires_at > created_at),
    last_accessed_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    version                     INT NOT NULL DEFAULT 1
);

CREATE INDEX IF NOT EXISTS geocode_cache_coordinate_gist
    ON geolocation.geocode_cache USING GIST (coordinate);
CREATE INDEX IF NOT EXISTS geocode_cache_bbox_gist
    ON geolocation.geocode_cache USING GIST (bbox);
CREATE INDEX IF NOT EXISTS geocode_cache_expires_at_idx
    ON geolocation.geocode_cache (expires_at);
CREATE INDEX IF NOT EXISTS geocode_cache_query_fp_idx
    ON geolocation.geocode_cache (query_fingerprint);
CREATE INDEX IF NOT EXISTS geocode_cache_last_accessed_at_idx
    ON geolocation.geocode_cache (last_accessed_at);
CREATE INDEX IF NOT EXISTS geocode_cache_region_city_id_idx
    ON geolocation.geocode_cache (region_city_id) WHERE region_city_id IS NOT NULL;