-- 000003_eta_cache.up.sql
--
-- geolocation.eta_cache — durable cache for ETA estimates per
-- docs/services/geolocation-service/ERD.md §3.2. Idempotent.
CREATE TABLE IF NOT EXISTS geolocation.eta_cache (
    id                     UUID PRIMARY KEY,
    cache_key              TEXT NOT NULL UNIQUE,
    origin_coordinate      geometry(Point, 4326) NOT NULL,
    destination_coordinate geometry(Point, 4326) NOT NULL,
    waypoint_count         INT NOT NULL CHECK (waypoint_count >= 0 AND waypoint_count <= 5),
    departure_time_bucket  INT NOT NULL CHECK (departure_time_bucket BETWEEN 0 AND 23),
    traffic_bucket         TEXT NOT NULL CHECK (traffic_bucket IN ('low','medium','high','unknown')),
    eta_seconds            INT NOT NULL CHECK (eta_seconds >= 0),
    distance_meters        INT NOT NULL CHECK (distance_meters >= 0),
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

CREATE INDEX IF NOT EXISTS eta_cache_expires_at_idx
    ON geolocation.eta_cache (expires_at);
CREATE INDEX IF NOT EXISTS eta_cache_origin_dest_idx
    ON geolocation.eta_cache (origin_coordinate, destination_coordinate);