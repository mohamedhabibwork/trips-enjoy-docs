-- 000006_admin_audit.down.sql
DROP INDEX IF EXISTS geolocation.admin_audit_correlation_idx;
DROP INDEX IF EXISTS geolocation.admin_audit_actor_idx;
DROP TABLE IF EXISTS geolocation.admin_audit_2026_07;
DROP TABLE IF EXISTS geolocation.admin_audit;