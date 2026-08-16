-- 000011_driver_health_events.down.sql
DROP INDEX IF EXISTS file.driver_health_events_correlation_idx;
DROP INDEX IF EXISTS file.driver_health_events_driver_idx;
DROP TABLE IF EXISTS file.driver_health_events_default;
DROP TABLE IF EXISTS file.driver_health_events;