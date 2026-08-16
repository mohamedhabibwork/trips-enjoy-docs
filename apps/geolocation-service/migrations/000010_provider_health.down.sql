-- 000010_provider_health.down.sql
DROP INDEX IF EXISTS geolocation.provider_health_result_time_idx;
DROP INDEX IF EXISTS geolocation.provider_health_vendor_time_idx;
DROP TABLE IF EXISTS geolocation.provider_health_2026_07;
DROP TABLE IF EXISTS geolocation.provider_health;