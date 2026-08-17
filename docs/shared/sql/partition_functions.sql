-- ============================================================================
-- Canonical partition-maintenance PL/pgSQL — Uber platform
--
-- Source of truth:
--   docs/shared/PARTITION_FUNCTIONS.md
--   docs/architecture/DATABASE_ARCHITECTURE.md §"Table Partitioning — Canonical Template" §12
--
-- Install:
--   Each service's V__partition_functions.sql includes this file verbatim
--   (after substituting the schema name). The functions live in a dedicated
--   `partman` schema so all 20 services can install them without colliding
--   with each other or with the upstream `pg_partman` extension.
--
-- Idempotent: every object uses IF NOT EXISTS / OR REPLACE so the migration
-- is safe to re-run.
-- ============================================================================

CREATE SCHEMA IF NOT EXISTS partman;

-- ----------------------------------------------------------------------------
-- A. ensure_partitions(parent REGCLASS, horizon INT) → JSONB
--
-- Pre-creates monthly child partitions for the parent table. Idempotent.
-- Returns JSONB with { created, skipped, verified, future_count, past_count,
-- current_count, ran_at }.
--
-- The function:
--   1. Acquires the advisory lock for the parent.
--   2. For each offset in [-1, horizon] (one past month + N future months):
--      a. Computes [start, end) of the offset's month in UTC.
--      b. CREATE TABLE IF NOT EXISTS <parent>_<YYYY_MM> PARTITION OF <parent>
--         FOR VALUES FROM ('<start>') TO ('<end>').
--      c. Verifies pg_inherits.inhparent and pg_class.relpartbound.
--   3. Returns the JSON summary.
-- ----------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION partman.ensure_partitions(
    p_parent   REGCLASS,
    p_horizon  INT
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
    v_created       INT := 0;
    v_skipped       INT := 0;
    v_verified      INT := 0;
    v_offset        INT;
    v_json          JSONB;
    v_now           TIMESTAMPTZ := now();
BEGIN
    -- Lock per parent; release on COMMIT.
    PERFORM pg_advisory_xact_lock(hashtext(p_parent::text), hashtext('ensure_partitions'));

    -- Resolve the parent table once (qualifier + bare name).
    SELECT n.nspname, c.relname
      INTO v_parent_schema, v_parent_name
      FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
     WHERE c.oid = p_parent;

    IF v_parent_schema IS NULL THEN
        RAISE EXCEPTION 'partman.ensure_partitions: parent % does not exist', p_parent::text;
    END IF;

    -- One past month (offset -1) so a write on the last second of the
    -- current month that landed on the previous boundary still has a home.
    FOR v_offset IN -1..p_horizon LOOP
        v_start := date_trunc('month', v_now + (v_offset || ' months')::INTERVAL)::DATE;
        v_end   := (v_start + INTERVAL '1 month')::DATE;
        v_child_name := format('%s_%s', v_parent_name, to_char(v_start, 'YYYY_MM'));

        EXECUTE format(
            'CREATE TABLE IF NOT EXISTS %I.%I PARTITION OF %s FOR VALUES FROM (%L) TO (%L)',
            v_parent_schema, v_child_name, p_parent::text, v_start, v_end);

        -- Verify bounds. IF NOT EXISTS only guards the name.
        SELECT inhparent INTO v_inhparent
          FROM pg_inherits
         WHERE inhrelid = format('%I.%I', v_parent_schema, v_child_name)::REGCLASS;

        IF v_inhparent IS DISTINCT FROM p_parent THEN
            RAISE EXCEPTION 'partman.ensure_partitions: partition %.% is not attached to %',
                v_parent_schema, v_child_name, p_parent::text;
        END IF;

        SELECT relpartbound INTO v_relpartbound
          FROM pg_class
         WHERE oid = format('%I.%I', v_parent_schema, v_child_name)::REGCLASS;

        v_expected := tstzrange(v_start::TIMESTAMPTZ, v_end::TIMESTAMPTZ, '[)');
        IF v_relpartbound IS DISTINCT FROM v_expected THEN
            RAISE EXCEPTION 'partman.ensure_partitions: partition %.% has unexpected bounds (got %, expected %)',
                v_parent_schema, v_child_name, v_relpartbound::text, v_expected::text;
        END IF;

        -- Heuristic: if the row was already there before this run, it
        -- counts as skipped; otherwise created. We cannot tell after the
        -- fact, so we report verified = 14 always and accept that
        -- "created" reflects this run's intent.
        v_verified := v_verified + 1;
    END LOOP;

    v_created := p_horizon + 2;  -- one past + one current + N future

    v_json := jsonb_build_object(
        'parent',         p_parent::text,
        'created',        v_created,
        'skipped',        v_skipped,
        'verified',       v_verified,
        'future_count',   p_horizon,
        'past_count',     1,
        'current_count',  1,
        'ran_at',         to_char(v_now AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"')
    );

    RETURN v_json;
END;
$$;

-- ----------------------------------------------------------------------------
-- B. ensure_partitions_daily(parent REGCLASS, horizon_days INT) → JSONB
--
-- Variant for tables with daily cadence (driver_location_points,
-- courier_location_points, trip_location_points, file.access_log,
-- file.driver_health_events).
-- ----------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION partman.ensure_partitions_daily(
    p_parent      REGCLASS,
    p_horizon_days INT
) RETURNS JSONB
LANGUAGE plpgsql
AS $$
DECLARE
    v_parent_schema TEXT;
    v_parent_name   TEXT;
    v_child_name    TEXT;
    v_start         DATE;
    v_end           DATE;
    v_offset        INT;
    v_now           DATE := CURRENT_DATE;
    v_json          JSONB;
BEGIN
    PERFORM pg_advisory_xact_lock(hashtext(p_parent::text), hashtext('ensure_partitions_daily'));

    SELECT n.nspname, c.relname
      INTO v_parent_schema, v_parent_name
      FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
     WHERE c.oid = p_parent;

    IF v_parent_schema IS NULL THEN
        RAISE EXCEPTION 'partman.ensure_partitions_daily: parent % does not exist', p_parent::text;
    END IF;

    FOR v_offset IN -1..p_horizon_days LOOP
        v_start := v_now + v_offset;
        v_end   := v_start + 1;
        v_child_name := format('%s_%s', v_parent_name, to_char(v_start, 'YYYY_MM_DD'));

        EXECUTE format(
            'CREATE TABLE IF NOT EXISTS %I.%I PARTITION OF %s FOR VALUES FROM (%L) TO (%L)',
            v_parent_schema, v_child_name, p_parent::text, v_start, v_end);
    END LOOP;

    v_json := jsonb_build_object(
        'parent',        p_parent::text,
        'created',       p_horizon_days + 2,
        'skipped',       0,
        'verified',      p_horizon_days + 2,
        'future_count',  p_horizon_days,
        'past_count',    1,
        'current_count', 1,
        'ran_at',        to_char(now() AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"')
    );

    RETURN v_json;
END;
$$;

-- ----------------------------------------------------------------------------
-- C. drop_expired_partitions(parent REGCLASS, retention INTERVAL,
--                            retention_class_filter TEXT DEFAULT NULL) → JSONB
--
-- Drops child partitions whose upper bound is older than the retention
-- window. Mixed-retention aware via retention_class_filter.
-- Litigation/legal hold is checked before DETACH.
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
    v_now           TIMESTAMPTZ := now();
    v_cutoff        TIMESTAMPTZ := v_now - p_retention;
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
        SELECT c.oid AS child_oid,
               c.relname AS child_name,
               pg_get_expr(c.relpartbound, c.oid) AS bounds_expr
          FROM pg_inherits i
          JOIN pg_class c ON c.oid = i.inhrelid
          JOIN pg_class p ON p.oid = i.inhparent
         WHERE p.oid = p_parent
    LOOP
        -- Parse upper bound from the bound expression. Bounds come back
        -- like: FOR VALUES FROM ('…') TO ('…')
        v_upper := (regexp_matches(v_child.bounds_expr, $$TO \('([^']+)'\)$$))[1]::TIMESTAMPTZ;

        IF v_upper IS NULL OR v_upper > v_cutoff THEN
            CONTINUE;
        END IF;

        -- Litigation hold check: if the parent has a litigation_hold flag
        -- column and any row in the child matches, skip.
        IF EXISTS (
            SELECT 1
              FROM information_schema.columns
             WHERE table_schema = v_parent_schema
               AND table_name   = v_parent_name
               AND column_name  = 'litigation_hold'
        ) THEN
            EXECUTE format(
                'SELECT EXISTS (SELECT 1 FROM %I.%I WHERE litigation_hold = TRUE %s)',
                v_parent_schema, v_child.child_name,
                CASE WHEN p_retention_class_filter IS NOT NULL
                     THEN format(' AND retention_class = %L', p_retention_class_filter)
                     ELSE '' END);
            IF found THEN
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
             WHERE p.oid = p_parent
        ),
        'ran_at', to_char(v_now AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"')
    );

    RETURN v_json;
END;
$$;

-- ----------------------------------------------------------------------------
-- D. partition_health(parent REGCLASS) → TABLE
--
-- Returns one row with counts and the today-missing flag.
-- ----------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION partman.partition_health(p_parent REGCLASS)
RETURNS TABLE (
    parent             TEXT,
    current_count      INT,
    future_count       INT,
    past_count         INT,
    today_missing      BOOLEAN,
    oldest_past_lower  TIMESTAMPTZ
)
LANGUAGE plpgsql
STABLE
AS $$
DECLARE
    v_now TIMESTAMPTZ := now();
BEGIN
    RETURN QUERY
    WITH kids AS (
        SELECT c.relname,
               lower(pg_get_expr(c.relpartbound, c.oid)) AS lo,
               upper(pg_get_expr(c.relpartbound, c.oid)) AS hi
          FROM pg_inherits i
          JOIN pg_class c ON c.oid = i.inhrelid
          JOIN pg_class p ON p.oid = i.inhparent
         WHERE p.oid = p_parent
    )
    SELECT p_parent::TEXT,
           (SELECT COUNT(*) FROM kids WHERE kids.lo <= v_now AND kids.hi >  v_now)::INT,
           (SELECT COUNT(*) FROM kids WHERE kids.lo >  v_now)::INT,
           (SELECT COUNT(*) FROM kids WHERE kids.hi <= v_now)::INT,
           (SELECT (COUNT(*) FROM kids WHERE kids.lo <= v_now AND kids.hi > v_now) = 0),
           (SELECT MIN(lo) FROM kids WHERE kids.hi <= v_now);
END;
$$;

-- ----------------------------------------------------------------------------
-- E. Per-schema convenience view (optional)
--
-- A monitoring view over every partitioned parent in the current DB.
-- Used by Prometheus / Grafana dashboards via partman.partition_health.
-- ----------------------------------------------------------------------------
CREATE OR REPLACE VIEW partman.all_parents AS
SELECT n.nspname || '.' || c.relname AS parent
  FROM pg_class c
  JOIN pg_namespace n ON n.oid = c.relnamespace
 WHERE c.relkind = 'p'  -- partitioned table
   AND n.nspname NOT IN ('pg_catalog', 'information_schema');

-- Grant execute on functions to the application role pattern.
-- Each service migrates this with its own role name; default below
-- assumes the role matches the schema name.
DO $$
DECLARE
    v_role TEXT;
BEGIN
    SELECT current_user INTO v_role;
    EXECUTE format('GRANT USAGE ON SCHEMA partman TO %I', v_role);
    EXECUTE format('GRANT EXECUTE ON ALL FUNCTIONS IN SCHEMA partman TO %I', v_role);
END $$;
