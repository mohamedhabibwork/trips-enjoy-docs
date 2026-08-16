-- 000004_route_cache.down.sql
DROP INDEX IF EXISTS geolocation.route_cache_expires_at_idx;
DROP TABLE IF EXISTS geolocation.route_cache;