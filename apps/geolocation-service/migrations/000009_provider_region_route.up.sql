-- 000009_provider_region_route.up.sql
--
-- geolocation.provider_region_route — per-(region, capability) chain
-- per docs/services/geolocation-service/ERD.md §3.8. The region regex
-- enforces the `default | country:<ISO2> | city:<uuid>` shape. The
-- chain array references provider_config.vendor_id (CHECK enforces
-- non-emptiness; the application layer validates membership at
-- write-time).
-- Idempotent.
CREATE TABLE IF NOT EXISTS geolocation.provider_region_route (
    id            UUID PRIMARY KEY,
    region        TEXT NOT NULL CHECK (region ~ '^(city:[0-9a-f-]{36}|country:[A-Z]{2}|default)$'),
    capability    TEXT NOT NULL CHECK (capability IN ('geocode_forward','geocode_reverse','eta','route','autocomplete','place_details','static_map')),
    chain         TEXT[] NOT NULL CHECK (cardinality(chain) > 0),
    notes         TEXT,
    enabled       BOOL NOT NULL DEFAULT true,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by    UUID NOT NULL,
    updated_by    UUID NOT NULL,
    version       INT NOT NULL DEFAULT 1,
    UNIQUE (region, capability)
);
CREATE INDEX IF NOT EXISTS provider_region_route_enabled_idx
    ON geolocation.provider_region_route (enabled) WHERE enabled;
CREATE INDEX IF NOT EXISTS provider_region_route_chain_gin
    ON geolocation.provider_region_route USING GIN (chain);