-- V3__customer_history_tables.sql
-- Per docs/services/customer-service/ERD.md §3:
--   customer.customer_kyc_history      : append-only KYC tier change log
--   customer.customer_segment_history  : append-only segment change log
--   customer.customer_ltv_history      : append-only LTV delta log,
--                                         RANGE partitioned by occurred_at
--                                         (monthly child partitions).
--
-- KYC and segment history are small per-customer volumes, so they are
-- NOT partitioned (ERD §3); LTV history is partitioned by month because
-- payment event volume is the dominant driver.

CREATE TABLE IF NOT EXISTS customer.customer_kyc_history (
    id UUID PRIMARY KEY,
    customer_id UUID NOT NULL,
    from_tier TEXT,
    to_tier TEXT NOT NULL,
    verification_id UUID,
    actor UUID,
    reason TEXT,
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT customer_kyc_history_to_tier_check
        CHECK (to_tier IN ('tier_0', 'tier_1', 'tier_2', 'tier_3')),
    CONSTRAINT customer_kyc_history_from_tier_check
        CHECK (from_tier IS NULL OR from_tier IN ('tier_0', 'tier_1', 'tier_2', 'tier_3'))
);

CREATE INDEX IF NOT EXISTS customer_kyc_history_customer_id_idx
    ON customer.customer_kyc_history (customer_id, occurred_at DESC);

-- Append-only: UPDATE and DELETE are rejected at the database level.
-- The trigger reuses the partman.raise_exception function and is created
-- here so the constraint is in place before any rows land. The function
-- is created in V5 (outbox/inbox) as part of the platform-wide trigger
-- helper; we tolerate the absence here by trying to create it
-- idempotently.
CREATE OR REPLACE FUNCTION customer.raise_exception() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'append-only table: % operation is forbidden', TG_OP;
END;
$$;

DROP TRIGGER IF EXISTS customer_kyc_history_append_only ON customer.customer_kyc_history;
CREATE TRIGGER customer_kyc_history_append_only
    BEFORE UPDATE OR DELETE ON customer.customer_kyc_history
    FOR EACH STATEMENT EXECUTE FUNCTION customer.raise_exception();

-- Segment history (also append-only; no partitioning).
CREATE TABLE IF NOT EXISTS customer.customer_segment_history (
    id UUID PRIMARY KEY,
    customer_id UUID NOT NULL,
    from_segment TEXT,
    to_segment TEXT NOT NULL,
    trigger TEXT NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT customer_segment_history_to_segment_check
        CHECK (to_segment IN ('standard', 'frequent', 'vip', 'churned')),
    CONSTRAINT customer_segment_history_from_segment_check
        CHECK (from_segment IS NULL OR from_segment IN ('standard', 'frequent', 'vip', 'churned')),
    CONSTRAINT customer_segment_history_trigger_check
        CHECK (trigger IN ('nightly_job', 'ltv_change', 'rides_count_change', 'idle_threshold'))
);

CREATE INDEX IF NOT EXISTS customer_segment_history_customer_id_idx
    ON customer.customer_segment_history (customer_id, occurred_at DESC);

DROP TRIGGER IF EXISTS customer_segment_history_append_only ON customer.customer_segment_history;
CREATE TRIGGER customer_segment_history_append_only
    BEFORE UPDATE OR DELETE ON customer.customer_segment_history
    FOR EACH STATEMENT EXECUTE FUNCTION customer.raise_exception();

-- LTV history (partitioned parent). Composite PK including the partition
-- key column is the platform-wide rule for time-partitioned parents
-- (DATABASE_ARCHITECTURE §12 / uber-partitioning-canonical-template).
CREATE TABLE IF NOT EXISTS customer.customer_ltv_history (
    id UUID NOT NULL,
    customer_id UUID NOT NULL,
    delta_minor BIGINT NOT NULL,
    currency CHAR(3) NOT NULL,
    service TEXT NOT NULL,
    request_id UUID,
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (id, occurred_at),
    CONSTRAINT customer_ltv_history_service_check
        CHECK (service IN ('ride', 'food', 'adjustment', 'refund'))
) PARTITION BY RANGE (occurred_at);

-- Seed the current month + the previous month so a fresh service can
-- immediately write LTV rows without first running the partition job.
-- Subsequent partitions are pre-created by the Spring
-- PartitionMaintenanceJob (and the canonical pg_cron schedule in V6).
DO $$
DECLARE
    v_now TIMESTAMPTZ := date_trunc('month', now());
    v_prev TIMESTAMPTZ := v_now - INTERVAL '1 month';
    v_next TIMESTAMPTZ := v_now + INTERVAL '1 month';
BEGIN
    EXECUTE format(
        'CREATE TABLE IF NOT EXISTS customer.customer_ltv_history_%s PARTITION OF customer.customer_ltv_history FOR VALUES FROM (%L) TO (%L)',
        to_char(v_prev, 'YYYY_MM'), v_prev, v_now);
    EXECUTE format(
        'CREATE TABLE IF NOT EXISTS customer.customer_ltv_history_%s PARTITION OF customer.customer_ltv_history FOR VALUES FROM (%L) TO (%L)',
        to_char(v_now, 'YYYY_MM'), v_now, v_next);
    EXECUTE format(
        'CREATE TABLE IF NOT EXISTS customer.customer_ltv_history_%s PARTITION OF customer.customer_ltv_history FOR VALUES FROM (%L) TO (%L)',
        to_char(v_next, 'YYYY_MM'), v_next, v_next + INTERVAL '1 month');
END $$;

CREATE INDEX IF NOT EXISTS customer_ltv_history_customer_id_idx
    ON customer.customer_ltv_history (customer_id, occurred_at DESC);
