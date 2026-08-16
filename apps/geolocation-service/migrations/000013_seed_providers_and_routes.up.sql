-- 000013_seed_providers_and_routes.up.sql
--
-- Seed the canonical 9 map providers (mock + 8 stubs) plus the
-- default chain for every (region=default, capability) pair. This is
-- the bootstrap the geolocation-service needs so the very first
-- request can resolve a chain (per docs/services/geolocation-service/
-- README.md §4.4 + ERD.md §3.4 + §3.5). Cloud vendor rows
-- (google / mapbox / here) are inserted as disabled until their
-- Vault credentials land; the dev scaffold enables mock only.
-- Idempotent: re-running against a partially-seeded DB is a no-op.

-- --- mock provider ---
INSERT INTO geolocation.provider_config (
    id, vendor_id, display_name, adapter_type, capabilities,
    is_self_host, is_static_only, enabled, priority,
    base_url, auth_type, vault_secret_path,
    qps_limit, burst_limit, timeout_ms,
    failure_threshold, cooldown_seconds, half_open_probe_count,
    cost_per_1k_usd, jurisdictions, metadata,
    created_by, updated_by
) VALUES (
    gen_random_uuid(),
    'mock', 'Mock (dev / test / CI)', 'in_process',
    ARRAY['geocode_forward','geocode_reverse','eta','route','autocomplete','place_details','static_map']::TEXT[],
    true, false, true, 1,
    NULL, 'none', '',
    10000, 10000, 50,
    1000, 5, 100,
    0, ARRAY['global']::TEXT[], '{"source":"dev-scaffold"}'::JSONB,
    gen_random_uuid(), gen_random_uuid()
)
ON CONFLICT (vendor_id) DO NOTHING;

-- --- google (commercial_rest, disabled until API key) ---
INSERT INTO geolocation.provider_config (
    id, vendor_id, display_name, adapter_type, capabilities,
    is_self_host, is_static_only, enabled, priority,
    base_url, auth_type, vault_secret_path,
    qps_limit, burst_limit, timeout_ms,
    failure_threshold, cooldown_seconds, half_open_probe_count,
    cost_per_1k_usd, jurisdictions, metadata,
    created_by, updated_by
) VALUES (
    gen_random_uuid(),
    'google', 'Google Maps Platform', 'commercial_rest',
    ARRAY['geocode_forward','geocode_reverse','eta','route','autocomplete','place_details','static_map']::TEXT[],
    false, false, false, 100,
    NULL, 'api_key', 'kv/<env>/geolocation/google',
    200, 200, 1500,
    5, 30, 3,
    5.00, ARRAY['global']::TEXT[], '{"quota_project_id":""}'::JSONB,
    gen_random_uuid(), gen_random_uuid()
)
ON CONFLICT (vendor_id) DO NOTHING;

-- --- mapbox ---
INSERT INTO geolocation.provider_config (
    id, vendor_id, display_name, adapter_type, capabilities,
    is_self_host, is_static_only, enabled, priority,
    base_url, auth_type, vault_secret_path,
    qps_limit, burst_limit, timeout_ms,
    failure_threshold, cooldown_seconds, half_open_probe_count,
    cost_per_1k_usd, jurisdictions, metadata,
    created_by, updated_by
) VALUES (
    gen_random_uuid(),
    'mapbox', 'Mapbox', 'commercial_rest',
    ARRAY['geocode_forward','geocode_reverse','eta','route','autocomplete','place_details','static_map']::TEXT[],
    false, false, false, 110,
    NULL, 'api_key', 'kv/<env>/geolocation/mapbox',
    200, 200, 1500,
    5, 30, 3,
    4.00, ARRAY['global']::TEXT[], '{"dataset":"mapbox.places"}'::JSONB,
    gen_random_uuid(), gen_random_uuid()
)
ON CONFLICT (vendor_id) DO NOTHING;

-- --- here ---
INSERT INTO geolocation.provider_config (
    id, vendor_id, display_name, adapter_type, capabilities,
    is_self_host, is_static_only, enabled, priority,
    base_url, auth_type, vault_secret_path,
    qps_limit, burst_limit, timeout_ms,
    failure_threshold, cooldown_seconds, half_open_probe_count,
    cost_per_1k_usd, jurisdictions, metadata,
    created_by, updated_by
) VALUES (
    gen_random_uuid(),
    'here', 'HERE Maps', 'commercial_rest',
    ARRAY['geocode_forward','geocode_reverse','eta','route','autocomplete','place_details','static_map']::TEXT[],
    false, false, false, 90,
    NULL, 'oauth2_client_credentials', 'kv/<env>/geolocation/here',
    200, 200, 1500,
    5, 30, 3,
    4.50, ARRAY['global']::TEXT[], '{"transport_mode":"car"}'::JSONB,
    gen_random_uuid(), gen_random_uuid()
)
ON CONFLICT (vendor_id) DO NOTHING;

-- --- osrm (self-host, eta+route only) ---
INSERT INTO geolocation.provider_config (
    id, vendor_id, display_name, adapter_type, capabilities,
    is_self_host, is_static_only, enabled, priority,
    base_url, auth_type, vault_secret_path,
    qps_limit, burst_limit, timeout_ms,
    failure_threshold, cooldown_seconds, half_open_probe_count,
    cost_per_1k_usd, jurisdictions, metadata,
    created_by, updated_by
) VALUES (
    gen_random_uuid(),
    'osrm', 'OSRM (self-host)', 'self_host_rest',
    ARRAY['eta','route']::TEXT[],
    true, false, false, 300,
    NULL, 'none', '',
    500, 500, 1500,
    5, 30, 3,
    0, ARRAY['global']::TEXT[], '{}'::JSONB,
    gen_random_uuid(), gen_random_uuid()
)
ON CONFLICT (vendor_id) DO NOTHING;

-- --- valhalla ---
INSERT INTO geolocation.provider_config (
    id, vendor_id, display_name, adapter_type, capabilities,
    is_self_host, is_static_only, enabled, priority,
    base_url, auth_type, vault_secret_path,
    qps_limit, burst_limit, timeout_ms,
    failure_threshold, cooldown_seconds, half_open_probe_count,
    cost_per_1k_usd, jurisdictions, metadata,
    created_by, updated_by
) VALUES (
    gen_random_uuid(),
    'valhalla', 'Valhalla (self-host)', 'self_host_rest',
    ARRAY['eta','route']::TEXT[],
    true, false, false, 310,
    NULL, 'none', '',
    500, 500, 1500,
    5, 30, 3,
    0, ARRAY['global']::TEXT[], '{}'::JSONB,
    gen_random_uuid(), gen_random_uuid()
)
ON CONFLICT (vendor_id) DO NOTHING;

-- --- nominatim (fair-use, disabled by default) ---
INSERT INTO geolocation.provider_config (
    id, vendor_id, display_name, adapter_type, capabilities,
    is_self_host, is_static_only, enabled, priority,
    base_url, auth_type, vault_secret_path,
    qps_limit, burst_limit, timeout_ms,
    failure_threshold, cooldown_seconds, half_open_probe_count,
    cost_per_1k_usd, jurisdictions, metadata,
    created_by, updated_by
) VALUES (
    gen_random_uuid(),
    'nominatim', 'Nominatim (OSM, fair-use)', 'self_host_rest',
    ARRAY['geocode_forward','geocode_reverse']::TEXT[],
    true, false, false, 400,
    NULL, 'none', '',
    1, 1, 1500,
    5, 30, 3,
    0, ARRAY['global']::TEXT[], '{}'::JSONB,
    gen_random_uuid(), gen_random_uuid()
)
ON CONFLICT (vendor_id) DO NOTHING;

-- --- pelias ---
INSERT INTO geolocation.provider_config (
    id, vendor_id, display_name, adapter_type, capabilities,
    is_self_host, is_static_only, enabled, priority,
    base_url, auth_type, vault_secret_path,
    qps_limit, burst_limit, timeout_ms,
    failure_threshold, cooldown_seconds, half_open_probe_count,
    cost_per_1k_usd, jurisdictions, metadata,
    created_by, updated_by
) VALUES (
    gen_random_uuid(),
    'pelias', 'Pelias (self-host)', 'self_host_rest',
    ARRAY['geocode_forward','geocode_reverse','eta','route','autocomplete','place_details']::TEXT[],
    true, false, false, 320,
    NULL, 'none', '',
    100, 100, 1500,
    5, 30, 3,
    0, ARRAY['global']::TEXT[], '{}'::JSONB,
    gen_random_uuid(), gen_random_uuid()
)
ON CONFLICT (vendor_id) DO NOTHING;

-- --- photon ---
INSERT INTO geolocation.provider_config (
    id, vendor_id, display_name, adapter_type, capabilities,
    is_self_host, is_static_only, enabled, priority,
    base_url, auth_type, vault_secret_path,
    qps_limit, burst_limit, timeout_ms,
    failure_threshold, cooldown_seconds, half_open_probe_count,
    cost_per_1k_usd, jurisdictions, metadata,
    created_by, updated_by
) VALUES (
    gen_random_uuid(),
    'photon', 'Photon (self-host)', 'self_host_rest',
    ARRAY['geocode_forward','geocode_reverse']::TEXT[],
    true, false, false, 410,
    NULL, 'none', '',
    100, 100, 1500,
    5, 30, 3,
    0, ARRAY['global']::TEXT[], '{}'::JSONB,
    gen_random_uuid(), gen_random_uuid()
)
ON CONFLICT (vendor_id) DO NOTHING;

-- --- per-capability default chain = [mock] ---
-- Seven rows, one per capability, all pointing at the seeded mock
-- vendor. Production chains (google, mapbox, here, osrm, …) land via
-- PUT /v1/admin/region-chains/... or via configuration-service on
-- configuration.updated.v1. The chain length is therefore 1 in dev
-- and grows to N as operators opt in to commercial / self-host vendors.
INSERT INTO geolocation.provider_region_route (
    id, region, capability, chain, notes, enabled,
    created_by, updated_by
) VALUES
    (gen_random_uuid(), 'default', 'geocode_forward', ARRAY['mock']::TEXT[], 'dev default chain (mock)', true, gen_random_uuid(), gen_random_uuid()),
    (gen_random_uuid(), 'default', 'geocode_reverse', ARRAY['mock']::TEXT[], 'dev default chain (mock)', true, gen_random_uuid(), gen_random_uuid()),
    (gen_random_uuid(), 'default', 'eta',             ARRAY['mock']::TEXT[], 'dev default chain (mock)', true, gen_random_uuid(), gen_random_uuid()),
    (gen_random_uuid(), 'default', 'route',           ARRAY['mock']::TEXT[], 'dev default chain (mock)', true, gen_random_uuid(), gen_random_uuid()),
    (gen_random_uuid(), 'default', 'autocomplete',    ARRAY['mock']::TEXT[], 'dev default chain (mock)', true, gen_random_uuid(), gen_random_uuid()),
    (gen_random_uuid(), 'default', 'place_details',   ARRAY['mock']::TEXT[], 'dev default chain (mock)', true, gen_random_uuid(), gen_random_uuid()),
    (gen_random_uuid(), 'default', 'static_map',      ARRAY['mock']::TEXT[], 'dev default chain (mock)', true, gen_random_uuid(), gen_random_uuid())
ON CONFLICT (region, capability) DO NOTHING;