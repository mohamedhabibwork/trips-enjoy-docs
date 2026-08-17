-- 000010_driver_object_registry.down.sql
DROP INDEX IF EXISTS file.driver_object_registry_file_idx;
DROP INDEX IF EXISTS file.driver_object_registry_uk;
DROP TABLE IF EXISTS file.driver_object_registry;