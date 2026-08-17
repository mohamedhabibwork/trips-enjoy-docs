-- 000009_driver_history.down.sql
DROP INDEX IF EXISTS file.driver_history_change_type_idx;
DROP INDEX IF EXISTS file.driver_history_to_driver_idx;
DROP INDEX IF EXISTS file.driver_history_migration_idx;
DROP INDEX IF EXISTS file.driver_history_file_idx;
DROP TABLE IF EXISTS file.driver_history;