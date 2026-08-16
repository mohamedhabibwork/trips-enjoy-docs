-- 000004_access_log.down.sql
DROP INDEX IF EXISTS file.access_log_driver_idx;
DROP INDEX IF EXISTS file.access_log_correlation_idx;
DROP INDEX IF EXISTS file.access_log_actor_idx;
DROP INDEX IF EXISTS file.access_log_file_idx;
DROP TABLE IF EXISTS file.access_log_default;
DROP TABLE IF EXISTS file.access_log;