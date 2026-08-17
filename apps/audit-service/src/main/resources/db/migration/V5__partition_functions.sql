-- V5__partition_functions.sql
--
-- Canonical partition-maintenance functions for the `audit` schema.
-- Source of truth: docs/shared/PARTITION_FUNCTIONS.md
--                   docs/shared/sql/partition_functions.sql
--                   docs/architecture/DATABASE_ARCHITECTURE.md §12
--
-- After this migration:
--   * partman.ensure_partitions('audit.events'::REGCLASS, 12) pre-creates
--     and verifies the next 12 monthly children (plus one past + one
--     current = 14 total). Same for 'audit.read_log'.
--   * partman.drop_expired_partitions(..., INTERVAL '7 years',
--     retention_class_filter := 'financial') sweeps the 7-year financial
--     retention; a second call with retention_class_filter := 'default'
--     sweeps the 1-year default retention (mixed-retention aware).
--   * pg_cron schedules ensure maintenance runs at 02:00 UTC every day
--     even when the Spring @Scheduled wrapper is down.
--
-- This migration is idempotent — re-running is a no-op. The earlier V2/V3
-- inline DO-loop blocks remain (also idempotent) so a fresh DB still gets
-- the first children without depending on the cron firing.

-- pg_cron is only available on managed Postgres (RDS, Cloud SQL, etc.);
-- guard the CREATE EXTENSION so a fresh local dev DB without pg_cron can
-- still apply this migration. The application's @Scheduled wrapper
-- (PartitionMaintenanceJob) covers the maintenance work when pg_cron
-- is absent.
DO $$
BEGIN
    BEGIN
        CREATE EXTENSION IF NOT EXISTS pg_cron;
    EXCEPTION
        WHEN feature_not_supported OR insufficient_privilege THEN
            RAISE NOTICE 'pg_cron extension is not available; partition-maintenance jobs will run via the Spring @Scheduled wrapper only.';
    END;
END $$;

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
            'CREATE TABLE IF NOT EXISTS %I.%I PARTITION OF %s FOR VALUES FROM (%L) TO (%L)',
            v_parent_schema, v_child_name, p_parent::text, v_start, v_end);

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
        'ran_at',        to_char(v_now AT TIME ZONE 'UTC', 'YYYY-MM-DDTHH24:MI:SSZ')
    );
    RETURN v_json;
END;
$$;

-- ----------------------------------------------------------------------------
-- drop_expired_partitions (mixed-retention aware)
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
        SELECT
            c.relname AS child_name,
            pg_get_expr(c.relpartbound, c.oid) AS bounds_expr
        FROM pg_inherits i
        JOIN pg_class c ON c.oid = i.inhrelid
        JOIN pg_class p ON p.oid = i.inhparent
        WHERE p.oid = p_parent
    LOOP
        v_upper := (regexp_matches(v_child.bounds_expr, 'TO \(''([^'']+)''\)'))[1]::TIMESTAMPTZ;
        IF v_upper IS NULL OR v_upper > v_cutoff THEN
            CONTINUE;
        END IF;

        -- Litigation-hold check. Skip children that still have any held row
        -- matching the optional retention_class filter.
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
        'ran_at', to_char(now() AT TIME ZONE 'UTC', 'YYYY-MM-DDTHH24:MI:SSZ')
    );
    RETURN v_json;
END;
$$;

-- ----------------------------------------------------------------------------
-- partition_health
-- ----------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION partman.partition_health(p_parent REGCLASS)
RETURNS TABLE (
    parent TEXT,
    current_count INT,
    future_count INT,
    past_count INT,
    today_missing BOOLEAN,
    oldest_past_lower TIMESTAMPTZ)
LANGUAGE plpgsql STABLE
AS $fn$
DECLARE v_now TIMESTAMPTZ := now();
BEGIN
    RETURN QUERY
    WITH kids AS (
        SELECT
            lower(pg_get_expr(c.relpartbound, c.oid)) AS lo,
            upper(pg_get_expr(c.relpartbound, c.oid)) AS hi
        FROM pg_inherits i
        JOIN pg_class c ON c.oid = i.inhrelid
        JOIN pg_class p ON p.oid = i.inhparent
        WHERE p.oid = p_parent
    )
    SELECT
        p_parent::TEXT,
        (SELECT COUNT(*)::INT FROM kids WHERE kids.lo <= v_now AND kids.hi > v_now),
        (SELECT COUNT(*)::INT FROM kids WHERE kids.lo > v_now),
        (SELECT COUNT(*)::INT FROM kids WHERE kids.hi <= v_now),
        (SELECT COUNT(*)::INT FROM kids WHERE kids.lo <= v_now AND kids.hi > v_now) = 0,
        (SELECT MIN(lo) FROM kids WHERE kids.hi <= v_now);
END;
$fn$;

-- ----------------------------------------------------------------------------
-- pg_cron schedules (per docs/shared/PARTITION_FUNCTIONS.md §8).
-- Guarded by a DO block so a fresh local dev DB without pg_cron can
-- still apply the migration; the application's @Scheduled wrapper
-- (PartitionMaintenanceJob) covers the maintenance work in that case.
-- ----------------------------------------------------------------------------
DO $cron$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_extension WHERE extname = 'pg_cron') THEN
        PERFORM cron.unschedule('audit.partition.events.ensure');
        PERFORM cron.schedule('audit.partition.events.ensure', '0 2 * * *', $sql$ SELECT partman.ensure_partitions('audit.events'::REGCLASS, 12) $sql$);

        PERFORM cron.unschedule('audit.partition.read_log.ensure');
        PERFORM cron.schedule('audit.partition.read_log.ensure', '0 2 * * *', $sql$ SELECT partman.ensure_partitions('audit.read_log'::REGCLASS, 12) $sql$);

        PERFORM cron.unschedule('audit.partition.events.drop_expired.financial');
        PERFORM cron.schedule('audit.partition.events.drop_expired.financial', '0 3 * * 0', $sql$ SELECT partman.drop_expired_partitions('audit.events'::REGCLASS, INTERVAL '7 years', retention_class_filter := 'financial') $sql$);

        PERFORM cron.unschedule('audit.partition.events.drop_expired.default');
        PERFORM cron.schedule('audit.partition.events.drop_expired.default', '0 3 * * 0', $sql$ SELECT partman.drop_expired_partitions('audit.events'::REGCLASS, INTERVAL '1 year', retention_class_filter := 'default') $sql$);

        PERFORM cron.unschedule('audit.partition.read_log.drop_expired');
        PERFORM cron.schedule('audit.partition.read_log.drop_expired', '0 3 * * 0', $sql$ SELECT partman.drop_expired_partitions('audit.read_log'::REGCLASS, INTERVAL '1 year') $sql$);
    ELSE
        RAISE NOTICE 'pg_cron is not installed; the Spring @Scheduled wrapper (PartitionMaintenanceJob) handles partition maintenance.';
    END IF;
END $cron$;

-- ----------------------------------------------------------------------------
-- Grant execute to the audit service role.
-- ----------------------------------------------------------------------------
DO $$
DECLARE v_role TEXT := current_user;
BEGIN
    EXECUTE format('GRANT USAGE ON SCHEMA partman TO %I', v_role);
    EXECUTE format('GRANT EXECUTE ON ALL FUNCTIONS IN SCHEMA partman TO %I', v_role);
END $$;
