-- 000009_provider_region_route.down.sql
DROP INDEX IF EXISTS geolocation.provider_region_route_chain_gin;
DROP INDEX IF EXISTS geolocation.provider_region_route_enabled_idx;
DROP TABLE IF EXISTS geolocation.provider_region_route;