-- 000005_zone_invalidation_state.up.sql
--
-- geolocation.zone_invalidation_state — denormalized copy of zone
-- polygons consumed from geolocation-service (zones) per
-- docs/services/geolocation-service/ERD.md §3.4. Used by the cache
-- invalidation job (ST_Intersects). Idempotent.
CREATE TABLE IF NOT EXISTS geolocation.zone_invalidation_state (
    id                UUID PRIMARY KEY,
    zone_id           UUID NOT NULL UNIQUE,
    city_id           UUID,
    polygon           geometry(Polygon, 4326) NOT NULL,
    bbox              geometry(Polygon, 4326) NOT NULL,
    polygon_version   INT NOT NULL,
    updated_at_source TIMESTAMPTZ NOT NULL,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by        UUID NOT NULL,
    updated_by        UUID NOT NULL
);

CREATE INDEX IF NOT EXISTS zone_inv_state_polygon_gist
    ON geolocation.zone_invalidation_state USING GIST (polygon);
CREATE INDEX IF NOT EXISTS zone_inv_state_bbox_gist
    ON geolocation.zone_invalidation_state USING GIST (bbox);