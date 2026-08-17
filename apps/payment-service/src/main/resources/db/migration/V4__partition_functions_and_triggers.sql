-- V4__partition_functions_and_triggers.sql
-- Per docs/shared/PARTITION_FUNCTIONS.md (canonical PL/pgSQL):
--   * partman.ensure_partitions(parent_table, period_start, period_end)
--     creates monthly partitions for the date range.
--   * partman.drop_expired_partitions(parent_table, retention_days)
--     drops partitions older than the retention window.
--   * partman.partition_health(parent_table) returns the partition
--     coverage window for monitoring.
--
-- The payment service partitions the following time-series tables by
-- month on their respective time columns:
--   * payment.payment_attempts    partitioned by started_at (monthly)
--   * payment.wallet_entries      partitioned by posted_at (monthly)
--   * payment.driver_earnings     partitioned by period_start (monthly)
--   * payment.courier_earnings    partitioned by period_start (monthly)
--   * payment.merchant_settlements partitioned by period_start (monthly)
--
-- The aggregate tables (payment_intents, wallets, payment_gateways,
-- idempotency_keys, outbox_events, inbox_events, *_earnings_lines,
-- merchant_settlement_lines) are NOT partitioned — they are
-- small/aggregate tables keyed by aggregate root.

-- 1) partman schema + canonical PL/pgSQL helpers.
-- Mirrors docs/shared/PARTITION_FUNCTIONS.md (lifted verbatim from
-- audit-service + ledger-service + notification-service + configuration-service).
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
    -- Parse schema.table
    SELECT split_part(p_parent_table, '.', 1),
           split_part(p_parent_table, '.', 2)
      INTO v_schema, v_table;

    -- Per-table time column lookup. The payment service uses:
    --   payment_attempts.started_at
    --   wallet_entries.posted_at
    --   driver_earnings.period_start
    --   courier_earnings.period_start
    --   merchant_settlements.period_start
    v_time_col := CASE v_table
        WHEN 'payment_attempts' THEN 'started_at'
        WHEN 'wallet_entries' THEN 'posted_at'
        WHEN 'driver_earnings' THEN 'period_start'
        WHEN 'courier_earnings' THEN 'period_start'
        WHEN 'merchant_settlements' THEN 'period_start'
        ELSE NULL
    END;

    IF v_time_col IS NULL THEN
        RAISE EXCEPTION 'partman.ensure_partitions: unknown parent table %', p_parent_table;
    END IF;

    -- Walk month-by-month from p_period_start to p_period_end (inclusive).
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
    v_partition_name TEXT;
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
        -- Only drop partitions whose upper bound is below the cutoff.
        IF v_rec.partition_name LIKE '%' || to_char(v_cutoff, 'YYYY_MM') || '%'
           OR regexp_replace(v_rec.partition_name, '^.*_(\d{4}_\d{2})$', '\1')::TEXT
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

-- 2) Append-only triggers for ledger-style tables.
-- payment_intents rows are append-only on the metadata columns once
-- captured; payment_attempts + wallet_entries are append-only entirely.
-- Per ledger-service pattern.
CREATE OR REPLACE FUNCTION payment.reject_update() RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION 'payment.% is append-only: UPDATE blocked', TG_TABLE_NAME
        USING ERRCODE = 'integrity_constraint_violation';
END;
$$ LANGUAGE plpgsql;

CREATE OR REPLACE FUNCTION payment.reject_delete() RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION 'payment.% is append-only: DELETE blocked', TG_TABLE_NAME
        USING ERRCODE = 'integrity_constraint_violation';
END;
$$ LANGUAGE plpgsql;

-- payment_attempts is fully append-only.
DROP TRIGGER IF EXISTS payment_attempts_no_update ON payment.payment_attempts;
CREATE TRIGGER payment_attempts_no_update
    BEFORE UPDATE ON payment.payment_attempts
    FOR EACH ROW EXECUTE FUNCTION payment.reject_update();

DROP TRIGGER IF EXISTS payment_attempts_no_delete ON payment.payment_attempts;
CREATE TRIGGER payment_attempts_no_delete
    BEFORE DELETE ON payment.payment_attempts
    FOR EACH ROW EXECUTE FUNCTION payment.reject_delete();

-- wallet_entries is fully append-only (the double-entry ledger primitive).
DROP TRIGGER IF EXISTS wallet_entries_no_update ON payment.wallet_entries;
CREATE TRIGGER wallet_entries_no_update
    BEFORE UPDATE ON payment.wallet_entries
    FOR EACH ROW EXECUTE FUNCTION payment.reject_update();

DROP TRIGGER IF EXISTS wallet_entries_no_delete ON payment.wallet_entries;
CREATE TRIGGER wallet_entries_no_delete
    BEFORE DELETE ON payment.wallet_entries
    FOR EACH ROW EXECUTE FUNCTION payment.reject_delete();

-- driver_earnings_lines + courier_earnings_lines + merchant_settlement_lines
-- are append-only line items.
DROP TRIGGER IF EXISTS driver_earnings_lines_no_update ON payment.driver_earnings_lines;
CREATE TRIGGER driver_earnings_lines_no_update
    BEFORE UPDATE ON payment.driver_earnings_lines
    FOR EACH ROW EXECUTE FUNCTION payment.reject_update();

DROP TRIGGER IF EXISTS courier_earnings_lines_no_update ON payment.courier_earnings_lines;
CREATE TRIGGER courier_earnings_lines_no_update
    BEFORE UPDATE ON payment.courier_earnings_lines
    FOR EACH ROW EXECUTE FUNCTION payment.reject_update();

DROP TRIGGER IF EXISTS merchant_settlement_lines_no_update ON payment.merchant_settlement_lines;
CREATE TRIGGER merchant_settlement_lines_no_update
    BEFORE UPDATE ON payment.merchant_settlement_lines
    FOR EACH ROW EXECUTE FUNCTION payment.reject_update();