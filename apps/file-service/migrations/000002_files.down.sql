-- 000002_files.down.sql
DROP INDEX IF EXISTS file.files_driver_idx;
DROP INDEX IF EXISTS file.files_correlation_idx;
DROP INDEX IF EXISTS file.files_status_pending_idx;
DROP INDEX IF EXISTS file.files_retention_idx;
DROP INDEX IF EXISTS file.files_owner_idx;
DROP INDEX IF EXISTS file.files_sha256_idx;
DROP TABLE IF EXISTS file.files;