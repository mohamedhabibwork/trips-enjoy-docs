-- 000008_provider_config.down.sql
DROP INDEX IF EXISTS geolocation.provider_config_jurisdictions_gin;
DROP INDEX IF EXISTS geolocation.provider_config_capabilities_gin;
DROP INDEX IF EXISTS geolocation.provider_config_enabled_idx;
DROP TABLE IF EXISTS geolocation.provider_config;