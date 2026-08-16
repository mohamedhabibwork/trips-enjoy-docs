-- 000007_storage_drivers.down.sql
DROP INDEX IF EXISTS file.storage_drivers_is_default_uk;
DROP INDEX IF EXISTS file.storage_drivers_priority_idx;
DROP INDEX IF EXISTS file.storage_drivers_state_idx;
DROP INDEX IF EXISTS file.storage_drivers_kind_idx;
DROP TABLE IF EXISTS file.storage_drivers;