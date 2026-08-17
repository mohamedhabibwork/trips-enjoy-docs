-- V3__partition_functions_and_triggers.sql
-- Per docs/shared/PARTITION_FUNCTIONS.md (canonical PL/pgSQL):
--   * partman.ensure_partitions(parent_table, period_start, period_end)
--   * partman.drop_expired_partitions(parent_table, retention_days)
--   * partman.partition_health(parent_table)
--
-- driver-service partitions the time-series tables by month:
--   * driver.driver_rating_history : rated_at (monthly)
--
-- driver_audit_log is append-only by V3 trigger; outbox/inbox are
-- short-lived aggregates that get cleaned by background jobs.
--
-- The partition maintenance job runs at 02:00 UTC daily (Spring
-- @Scheduled fallback if pg_cron is unavailable).

CREATE SCHEMA IF NOT EXISTS partman;

CREATE OR REPLACE FUNCTION partman.ensure_partitions(
    p_parent_table TEXT,
    p_period_start TIMESTAMPTZ,
    p_period_end TIMESTAMPTZ
) RETURNS VOID AS $$
DECLARE
    v_schema TEXT;
    v_table TEXT;
    v_time_col TEXT;
    v_partition_start TIMESTAMPTZ;
    v_partition_end TIMESTAMPTZ;
    v_partition_name TEXT;
BEGIN
    SELECT split_part(p_parent_table, '.', 1),
           split_part(p_parent_table, '.', 2)
      INTO v_schema, v_table;

    v_time_col := CASE v_table
        WHEN 'driver_rating_history' THEN 'rated_at'
        ELSE NULL
    END;

    IF v_time_col IS NULL THEN
        RAISE EXCEPTION 'partman.ensure_partitions: unknown parent table %', p_parent_table;
    END IF;

    v_partition_start := date_trunc('month', p_period_start);
    WHILE v_partition_start <= p_period_end LOOP
        v_partition_end := v_partition_start + INTERVAL '1 month';
        v_partition_name := format('%I.%I_%s', v_schema, v_table,
                                   to_char(v_partition_start, 'YYYY_MM'));

        EXECUTE format(
            'CREATE TABLE IF NOT EXISTS %s PARTITION OF %s FOR VALUES FROM (%L) TO (%L)',
            v_partition_name, p_parent_table, v_partition_start, v_partition_end
        );

        v_partition_start := v_partition_end;
    END LOOP;
END;
$$ LANGUAGE plpgsql;

CREATE OR REPLACE FUNCTION partman.drop_expired_partitions(
    p_parent_table TEXT,
    p_retention_days INT
) RETURNS VOID AS $$
DECLARE
    v_schema TEXT;
    v_table TEXT;
    v_cutoff TIMESTAMPTZ;
    v_rec RECORD;
BEGIN
    SELECT split_part(p_parent_table, '.', 1),
           split_part(p_parent_table, '.', 2)
      INTO v_schema, v_table;
    v_cutoff := now() - (p_retention_days || ' days')::INTERVAL;

    FOR v_rec IN
        SELECT inhrelid::regclass::text AS partition_name
          FROM pg_inherits
         WHERE inhparent = p_parent_table::regclass
    LOOP
        IF regexp_replace(v_rec.partition_name, '^.*_(\d{4}_\d{2})$', '\1')::TEXT
           < to_char(v_cutoff, 'YYYY_MM') THEN
            EXECUTE format('DROP TABLE IF EXISTS %s', v_rec.partition_name);
        END IF;
    END LOOP;
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

-- Append-only triggers for the immutable tables.
CREATE OR REPLACE FUNCTION driver.reject_update() RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION 'driver.% is append-only: UPDATE blocked', TG_TABLE_NAME
        USING ERRCODE = 'integrity_constraint_violation';
END;
$$ LANGUAGE plpgsql;

CREATE OR REPLACE FUNCTION driver.reject_delete() RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION 'driver.% is append-only: DELETE blocked', TG_TABLE_NAME
        USING ERRCODE = 'integrity_constraint_violation';
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS driver_rating_history_no_update ON driver.driver_rating_history;
CREATE TRIGGER driver_rating_history_no_update
    BEFORE UPDATE ON driver.driver_rating_history
    FOR EACH ROW EXECUTE FUNCTION driver.reject_update();

DROP TRIGGER IF EXISTS driver_rating_history_no_delete ON driver.driver_rating_history;
CREATE TRIGGER driver_rating_history_no_delete
    BEFORE DELETE ON driver.driver_rating_history
    FOR EACH ROW EXECUTE FUNCTION driver.reject_delete();

DROP TRIGGER IF EXISTS driver_audit_log_no_update ON driver.driver_audit_log;
CREATE TRIGGER driver_audit_log_no_update
    BEFORE UPDATE ON driver.driver_audit_log
    FOR EACH ROW EXECUTE FUNCTION driver.reject_update();

DROP TRIGGER IF EXISTS driver_audit_log_no_delete ON driver.driver_audit_log;
CREATE TRIGGER driver_audit_log_no_delete
    BEFORE DELETE ON driver.driver_audit_log
    FOR EACH ROW EXECUTE FUNCTION driver.reject_delete();