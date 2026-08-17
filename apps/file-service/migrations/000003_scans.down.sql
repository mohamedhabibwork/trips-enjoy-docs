-- 000003_scans.down.sql
DROP INDEX IF EXISTS file.scans_status_idx;
DROP INDEX IF EXISTS file.scans_file_idx;
DROP TABLE IF EXISTS file.scans;