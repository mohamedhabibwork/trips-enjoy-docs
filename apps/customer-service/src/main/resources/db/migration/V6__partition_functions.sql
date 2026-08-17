-- V6__partition_functions.sql
--
-- Canonical partition-maintenance functions for the `customer` schema.
-- Source of truth: docs/shared/PARTITION_FUNCTIONS.md
--                   docs/shared/sql/partition_functions.sql
--                   docs/architecture/DATABASE_ARCHITECTURE.md §12
--
-- After this migration:
--   * partman.ensure_partitions('customer.customer_ltv_history'::REGCLASS, 12)
--     pre-creates + verifies the next 12 monthly children.
--   * The Spring wrapper PartitionMaintenanceJob runs daily at 02:00 UTC.
--   * (Optional) pg_cron schedules ensure maintenance runs at 02:00 UTC
--     daily even when the Spring wrapper is down — the platform-level
--     decision to enable pg_cron is documented at docs/shared/CONVENTIONS.md
--     §"pg_cron rollout". pg_cron is intentionally NOT installed here so
--     the migration works on the bare postgres image used by the local
--     Testcontainers harness.

CREATE SCHEMA IF NOT EXISTS partman;

-- ----------------------------------------------------------------------------
-- ensure_partitions
-- ----------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION partman.ensure_partitions(
    p_parent  REGCLASS,
    p_horizon INT
) RETURNS JSONB
LANGUAGE plpgsql
AS $$
DECLARE
    v_parent_schema TEXT;
    v_parent_name   TEXT;
    v_child_name    TEXT;
    v_start         DATE;
    v_end           DATE;
    v_expected      TSTZRANGE;
    v_inhparent     REGCLASS;
    v_relpartbound  TSTZRANGE;
    v_verified      INT := 0;
    v_offset        INT;
    v_now           TIMESTAMPTZ := now();
    v_json          JSONB;
BEGIN
    PERFORM pg_advisory_xact_lock(hashtext(p_parent::text), hashtext('ensure_partitions'));

    SELECT n.nspname, c.relname
      INTO v_parent_schema, v_parent_name
      FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
     WHERE c.oid = p_parent;
    IF v_parent_schema IS NULL THEN
        RAISE EXCEPTION 'partman.ensure_partitions: parent % does not exist', p_parent::text;
    END IF;

    FOR v_offset IN -1..p_horizon LOOP
        v_start := date_trunc('month', v_now + (v_offset || ' months')::INTERVAL)::DATE;
        v_end   := (v_start + INTERVAL '1 month')::DATE;
        v_child_name := format('%s_%s', v_parent_name, to_char(v_start, 'YYYY_MM'));
        EXECUTE format(
            'CREATE TABLE IF NOT EXISTS %I.%I PARTITION OF %I FOR VALUES FROM (%L) TO (%L)',
            v_parent_schema, v_child_name, p_parent, v_start, v_end);

        SELECT inhparent INTO v_inhparent
          FROM pg_inherits
         WHERE inhrelid = format('%I.%I', v_parent_schema, v_child_name)::REGCLASS;
        IF v_inhparent IS DISTINCT FROM p_parent THEN
            RAISE EXCEPTION 'partition %.% not attached to %',
                v_parent_schema, v_child_name, p_parent::text;
        END IF;

        SELECT relpartbound INTO v_relpartbound
          FROM pg_class
         WHERE oid = format('%I.%I', v_parent_schema, v_child_name)::REGCLASS;
        v_expected := tstzrange(v_start::TIMESTAMPTZ, v_end::TIMESTAMPTZ, '[)');
        IF v_relpartbound IS DISTINCT FROM v_expected THEN
            RAISE EXCEPTION 'partition %.% bounds mismatch', v_parent_schema, v_child_name;
        END IF;

        v_verified := v_verified + 1;
    END LOOP;

    v_json := jsonb_build_object(
        'parent',        p_parent::text,
        'created',       p_horizon + 2,
        'skipped',       0,
        'verified',      v_verified,
        'future_count',  p_horizon,
        'past_count',    1,
        'current_count', 1,
        'ran_at',        to_char(v_now AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"')
    );
    RETURN v_json;
END;
$$;

-- ----------------------------------------------------------------------------
-- drop_expired_partitions
-- ----------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION partman.drop_expired_partitions(
    p_parent                REGCLASS,
    p_retention             INTERVAL,
    p_retention_class_filter TEXT DEFAULT NULL
) RETURNS JSONB
LANGUAGE plpgsql
AS $$
DECLARE
    v_parent_schema TEXT;
    v_parent_name   TEXT;
    v_child         RECORD;
    v_dropped       INT := 0;
    v_skipped_hold  INT := 0;
    v_upper         TIMESTAMPTZ;
    v_cutoff        TIMESTAMPTZ := now() - p_retention;
    v_held          BOOLEAN;
    v_json          JSONB;
BEGIN
    PERFORM pg_advisory_xact_lock(hashtext(p_parent::text), hashtext('drop_expired_partitions'));

    SELECT n.nspname, c.relname
      INTO v_parent_schema, v_parent_name
      FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
     WHERE c.oid = p_parent;
    IF v_parent_schema IS NULL THEN
        RAISE EXCEPTION 'partman.drop_expired_partitions: parent % does not exist', p_parent::text;
    END IF;

    FOR v_child IN
        SELECT c.relname AS child_name,
               pg_get_expr(c.relpartbound, c.oid) AS bounds_expr
          FROM pg_inherits i
          JOIN pg_class c ON c.oid = i.inhrelid
          JOIN pg_class p ON p.oid = i.inhparent
         WHERE p.oid = p_parent
    LOOP
        v_upper := (regexp_matches(v_child.bounds_expr, $r$TO \(''([^'']+)''\)$r$))[1]::TIMESTAMPTZ;
        IF v_upper IS NULL OR v_upper > v_cutoff THEN
            CONTINUE;
        END IF;

        IF EXISTS (
            SELECT 1 FROM information_schema.columns
             WHERE table_schema = v_parent_schema
               AND table_name   = v_parent_name
               AND column_name  = 'litigation_hold'
        ) THEN
            IF p_retention_class_filter IS NOT NULL THEN
                EXECUTE format(
                    'SELECT EXISTS (SELECT 1 FROM %I.%I WHERE litigation_hold = TRUE AND retention_class = %L)',
                    v_parent_schema, v_child.child_name, p_retention_class_filter)
                  INTO v_held;
            ELSE
                EXECUTE format(
                    'SELECT EXISTS (SELECT 1 FROM %I.%I WHERE litigation_hold = TRUE)',
                    v_parent_schema, v_child.child_name)
                  INTO v_held;
            END IF;
            IF v_held THEN
                v_skipped_hold := v_skipped_hold + 1;
                CONTINUE;
            END IF;
        END IF;

        EXECUTE format('ALTER TABLE %s DETACH PARTITION %I.%I CONCURRENTLY',
            p_parent::text, v_parent_schema, v_child.child_name);
        EXECUTE format('DROP TABLE %I.%I', v_parent_schema, v_child.child_name);
        v_dropped := v_dropped + 1;
    END LOOP;

    v_json := jsonb_build_object(
        'parent',         p_parent::text,
        'dropped',        v_dropped,
        'skipped_hold',   v_skipped_hold,
        'remaining_past_count', (
            SELECT COUNT(*) FROM pg_inherits i
              JOIN pg_class c ON c.oid = i.inhrelid
              JOIN pg_class p ON p.oid = i.inhparent
             WHERE p.oid = p_parent),
        'ran_at', to_char(now() AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"')
    );
    RETURN v_json;
END;
$$;

-- ----------------------------------------------------------------------------
-- partition_health
-- ----------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION partman.partition_health(p_parent REGCLASS)
RETURNS TABLE (
    parent TEXT, current_count INT, future_count INT, past_count INT,
    today_missing BOOLEAN, oldest_past_lower TIMESTAMPTZ)
LANGUAGE plpgsql STABLE AS $$
DECLARE v_now TIMESTAMPTZ := now();
BEGIN
    RETURN QUERY
    WITH kids AS (
        SELECT c.relname,
               lower(pg_get_expr(c.relpartbound, c.oid)) AS lo,
               upper(pg_get_expr(c.relpartbound, c.oid)) AS hi
          FROM pg_inherits i
          JOIN pg_class c ON c.oid = i.inhrelid
          JOIN pg_class p ON p.oid = i.inhparent
         WHERE p.oid = p_parent)
    SELECT p_parent::TEXT,
           (SELECT COUNT(*) FROM kids WHERE kids.lo <= v_now AND kids.hi >  v_now)::INT,
           (SELECT COUNT(*) FROM kids WHERE kids.lo >  v_now)::INT,
           (SELECT COUNT(*) FROM kids WHERE kids.hi <= v_now)::INT,
           ((SELECT COUNT(*) FROM kids WHERE kids.lo <= v_now AND kids.hi > v_now) = 0) AS today_missing,
           (SELECT MIN(lo) FROM kids WHERE kids.hi <= v_now) AS oldest_past_lower;
END;
$$;

-- ----------------------------------------------------------------------------
-- pg_cron schedules (wrapped in DO so the migration is portable).
-- ----------------------------------------------------------------------------
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_extension WHERE extname = 'pg_cron') THEN
        PERFORM cron.unschedule('customer.partition.ltv_history.ensure');
        PERFORM cron.schedule(
            'customer.partition.ltv_history.ensure',
            '0 2 * * *',
            $cron$ SELECT partman.ensure_partitions('customer.customer_ltv_history'::REGCLASS, 12) $cron$);
        PERFORM cron.unschedule('customer.partition.ltv_history.drop_expired');
        PERFORM cron.schedule(
            'customer.partition.ltv_history.drop_expired',
            '0 3 * * 0',
            $cron$ SELECT partman.drop_expired_partitions('customer.customer_ltv_history'::REGCLASS, INTERVAL '1 year') $cron$);
    ELSE
        RAISE NOTICE 'pg_cron extension not installed; skipping schedule registration. The Spring PartitionMaintenanceJob is the authoritative maintenance path.';
    END IF;
END $$;

DO $$
DECLARE v_role TEXT := current_user;
BEGIN
    EXECUTE format('GRANT USAGE ON SCHEMA partman TO %I', v_role);
    EXECUTE format('GRANT EXECUTE ON ALL FUNCTIONS IN SCHEMA partman TO %I', v_role);
END $$;
