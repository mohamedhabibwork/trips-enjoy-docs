-- 000012_provider_usage_daily.down.sql
DROP INDEX IF EXISTS geolocation.provider_usage_daily_date_vendor_idx;
DROP TABLE IF EXISTS geolocation.provider_usage_daily_2026_07;
DROP TABLE IF EXISTS geolocation.provider_usage_daily;