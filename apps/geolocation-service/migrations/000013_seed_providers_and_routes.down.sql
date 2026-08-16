-- 000013_seed_providers_and_routes.down.sql
-- Reverse the seed: drop every per-capability default chain + every
-- provider_config row inserted by 000013_seed_providers_and_routes.up.sql.
DELETE FROM geolocation.provider_region_route
    WHERE region = 'default' AND notes = 'dev default chain (mock)';
DELETE FROM geolocation.provider_config
    WHERE vendor_id IN ('mock','google','mapbox','here','osrm','valhalla','nominatim','pelias','photon');