-- 000008_driver_assignments.down.sql
DROP INDEX IF EXISTS file.driver_assignments_source_idx;
DROP INDEX IF EXISTS file.driver_assignments_file_idx;
DROP TABLE IF EXISTS file.driver_assignments;