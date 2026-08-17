-- V3__partition_functions_and_triggers.sql
-- Per docs/shared/PARTITION_FUNCTIONS.md (canonical PL/pgSQL).
--
-- food-order-service does not currently partition any table (the
-- order_state_history is small, audit log style). Stubs for parity
-- with the prior graduates — they always raise "unknown parent table".

CREATE SCHEMA IF NOT EXISTS partman;

CREATE OR REPLACE FUNCTION partman.raise_unknown_parent(p_parent_table TEXT)
RETURNS VOID AS $$
BEGIN
    RAISE EXCEPTION 'partman: unknown parent table % (food-order-service has no partitioned tables)', p_parent_table;
END;
$$ LANGUAGE plpgsql;

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

-- Append-only triggers for order_state_history.
CREATE OR REPLACE FUNCTION food_order.reject_update() RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION 'food_order.% is append-only: UPDATE blocked', TG_TABLE_NAME
        USING ERRCODE = 'integrity_constraint_violation';
END;
$$ LANGUAGE plpgsql;

CREATE OR REPLACE FUNCTION food_order.reject_delete() RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION 'food_order.% is append-only: DELETE blocked', TG_TABLE_NAME
        USING ERRCODE = 'integrity_constraint_violation';
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS order_state_history_no_update ON food_order.order_state_history;
CREATE TRIGGER order_state_history_no_update
    BEFORE UPDATE ON food_order.order_state_history
    FOR EACH ROW EXECUTE FUNCTION food_order.reject_update();

DROP TRIGGER IF EXISTS order_state_history_no_delete ON food_order.order_state_history;
CREATE TRIGGER order_state_history_no_delete
    BEFORE DELETE ON food_order.order_state_history
    FOR EACH ROW EXECUTE FUNCTION food_order.reject_delete();