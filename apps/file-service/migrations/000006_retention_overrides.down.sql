-- 000006_retention_overrides.down.sql
DROP INDEX IF EXISTS file.retention_overrides_file_idx;
DROP TABLE IF EXISTS file.retention_overrides;