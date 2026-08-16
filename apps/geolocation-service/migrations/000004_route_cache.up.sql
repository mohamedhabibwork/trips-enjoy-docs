-- 000004_route_cache.up.sql
--
-- geolocation.route_cache — durable cache for full routes per
-- docs/services/geolocation-service/ERD.md §3.3. Idempotent.
CREATE TABLE IF NOT EXISTS geolocation.route_cache (
    id                     UUID PRIMARY KEY,
    cache_key              TEXT NOT NULL UNIQUE,
    origin_coordinate      geometry(Point, 4326) NOT NULL,
    destination_coordinate geometry(Point, 4326) NOT NULL,
    waypoint_count         INT NOT NULL CHECK (waypoint_count >= 0 AND waypoint_count <= 5),
    polyline               TEXT NOT NULL,
    distance_meters        INT NOT NULL CHECK (distance_meters >= 0),
    eta_seconds            INT NOT NULL CHECK (eta_seconds >= 0),
    steps                  JSONB,
    alternatives           JSONB,
    vendor_id              UUID NOT NULL,
    vendor_response        JSONB,
    created_at             TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at             TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by             UUID NOT NULL,
    updated_by             UUID NOT NULL,
    expires_at             TIMESTAMPTZ NOT NULL CHECK (expires_at > created_at),
    last_accessed_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    version                INT NOT NULL DEFAULT 1
);

CREATE INDEX IF NOT EXISTS route_cache_expires_at_idx
    ON geolocation.route_cache (expires_at);