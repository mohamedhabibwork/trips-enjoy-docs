-- 000002_geocode_cache.down.sql
DROP INDEX IF EXISTS geolocation.geocode_cache_region_city_id_idx;
DROP INDEX IF EXISTS geolocation.geocode_cache_last_accessed_at_idx;
DROP INDEX IF EXISTS geolocation.geocode_cache_query_fp_idx;
DROP INDEX IF EXISTS geolocation.geocode_cache_expires_at_idx;
DROP INDEX IF EXISTS geolocation.geocode_cache_bbox_gist;
DROP INDEX IF EXISTS geolocation.geocode_cache_coordinate_gist;
DROP TABLE IF EXISTS geolocation.geocode_cache;