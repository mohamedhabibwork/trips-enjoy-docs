-- 000001_create_file_schema.down.sql
-- Rollback for the schema created by .up.sql. CASCADE so any domain tables
-- added in later migrations (V2+) are removed too.
DROP SCHEMA IF EXISTS file CASCADE;
