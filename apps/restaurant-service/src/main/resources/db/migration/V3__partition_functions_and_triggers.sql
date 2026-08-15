-- V3__partition_functions_and_triggers.sql
-- Per docs/shared/PARTITION_FUNCTIONS.md (canonical PL/pgSQL):
--   * partman.ensure_partitions(parent_table, period_start, period_end)
--   * partman.drop_expired_partitions(parent_table, retention_days)
--   * partman.partition_health(parent_table)
--
-- restaurant-service has no high-volume time-series tables (the audit log
-- is append-only but low-volume), so the partition functions are loaded
-- as stubs for parity with the other graduates but always raise
-- "unknown parent table" when called. A future graduate can wire the
-- full producer flow if restaurant-service ever starts ingesting
-- high-volume time-series data.

CREATE SCHEMA IF NOT EXISTS partman;

CREATE OR REPLACE FUNCTION partman.raise_unknown_parent(p_parent_table TEXT)
RETURNS VOID AS $$
BEGIN
    RAISE EXCEPTION 'partman: unknown parent table % (restaurant-service has no partitioned tables)', p_parent_table;
END;
$$ LANGUAGE plpgsql;

-- Stubs that always raise — restaurant-service has no partitioned tables.
CREATE OR REPLACE FUNCTION partman.ensure_partitions(
    p_parent_table TEXT,
    p_period_start TIMESTAMPTZ,
    p_period_end TIMESTAMPTZ
) RETURNS VOID AS $$
BEGIN
    PERFORM partman.raise_unknown_parent(p_parent_table);
END;
$$ LANGUAGE plpgsql;

CREATE OR REPLACE FUNCTION partman.drop_expired_partitions(
    p_parent_table TEXT,
    p_retention_days INT
) RETURNS VOID AS $$
BEGIN
    PERFORM partman.raise_unknown_parent(p_parent_table);
END;
$$ LANGUAGE plpgsql;

CREATE OR REPLACE FUNCTION partman.partition_health(p_parent_table TEXT)
RETURNS TABLE (partition_name TEXT, bound TEXT) AS $$
    SELECT inhrelid::regclass::text AS partition_name,
           pg_get_expr(c.relpartbound, c.oid, true) AS bound
      FROM pg_inherits i
      JOIN pg_class c ON c.oid = i.inhrelid
     WHERE inhparent = p_parent_table::regclass;
$$ LANGUAGE sql;

-- Append-only triggers for restaurant_audit_log.
CREATE OR REPLACE FUNCTION restaurant.reject_update() RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION 'restaurant.% is append-only: UPDATE blocked', TG_TABLE_NAME
        USING ERRCODE = 'integrity_constraint_violation';
END;
$$ LANGUAGE plpgsql;

CREATE OR REPLACE FUNCTION restaurant.reject_delete() RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION 'restaurant.% is append-only: DELETE blocked', TG_TABLE_NAME
        USING ERRCODE = 'integrity_constraint_violation';
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS restaurant_audit_log_no_update ON restaurant.restaurant_audit_log;
CREATE TRIGGER restaurant_audit_log_no_update
    BEFORE UPDATE ON restaurant.restaurant_audit_log
    FOR EACH ROW EXECUTE FUNCTION restaurant.reject_update();

DROP TRIGGER IF EXISTS restaurant_audit_log_no_delete ON restaurant.restaurant_audit_log;
CREATE TRIGGER restaurant_audit_log_no_delete
    BEFORE DELETE ON restaurant.restaurant_audit_log
    FOR EACH ROW EXECUTE FUNCTION restaurant.reject_delete();