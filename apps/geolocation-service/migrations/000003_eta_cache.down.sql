-- 000003_eta_cache.down.sql
DROP INDEX IF EXISTS geolocation.eta_cache_origin_dest_idx;
DROP INDEX IF EXISTS geolocation.eta_cache_expires_at_idx;
DROP TABLE IF EXISTS geolocation.eta_cache;