-- V4__audit_history_partition_admin.sql
-- Conformance additions for identity-service docs:
--   - ERD §3 audit_log: extra columns + CHECK constraints
--   - ERD §3 role_assignment_history: new partitioned table with immutability
--   - ERD §3.3 partitioning: pre-create next 30 days of monthly partitions on
--     identity.identity_claim_history (DATA--007)
--   - ERD §3.1 indexes: partial indexes on cross-service ID columns

-- =========================================================================
-- 1. identity.identity_audit_log: extra columns + CHECK constraints
-- =========================================================================
ALTER TABLE identity.identity_audit_log
    ADD COLUMN IF NOT EXISTS role TEXT,
    ADD COLUMN IF NOT EXISTS preset TEXT,
    ADD COLUMN IF NOT EXISTS cosigner UUID,
    ADD COLUMN IF NOT EXISTS break_glass BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS signature TEXT,
    ADD COLUMN IF NOT EXISTS before JSONB,
    ADD COLUMN IF NOT EXISTS after JSONB,
    ADD COLUMN IF NOT EXISTS occurred_by_role TEXT;

-- Cosigner must be a different actor than the granting actor
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'identity_audit_log_cosigner_differs_actor'
    ) THEN
        ALTER TABLE identity.identity_audit_log
            ADD CONSTRAINT identity_audit_log_cosigner_differs_actor
            CHECK (cosigner IS NULL OR cosigner <> actor);
    END IF;
END $$;

-- For super-admin actions, either break-glass is set or the action is not allowed
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'identity_audit_log_super_admin_break_glass'
    ) THEN
        ALTER TABLE identity.identity_audit_log
            ADD CONSTRAINT identity_audit_log_super_admin_break_glass
            CHECK (occurred_by_role IS DISTINCT FROM 'platform.super_admin' OR break_glass = TRUE);
    END IF;
END $$;

-- =========================================================================
-- 2. identity.role_assignment_history: partitioned by month, immutable
-- =========================================================================
CREATE TABLE IF NOT EXISTS identity.role_assignment_history (
    id UUID NOT NULL,
    identity_id UUID NOT NULL REFERENCES identity.identities(id),
    kc_sub TEXT NOT NULL,
    realm TEXT NOT NULL,
    role TEXT NOT NULL,
    action TEXT NOT NULL CHECK (action IN ('grant', 'revoke')),
    preset TEXT,
    actor UUID NOT NULL,
    cosigner UUID,
    break_glass BOOLEAN NOT NULL DEFAULT FALSE,
    signature TEXT,
    reason_code TEXT,
    correlation_id UUID,
    endpoint TEXT NOT NULL,
    target_resource TEXT NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (id, occurred_at)
) PARTITION BY RANGE (occurred_at);

CREATE TABLE IF NOT EXISTS identity.role_assignment_history_default
    PARTITION OF identity.role_assignment_history DEFAULT;

-- Generate monthly partitions for the previous, current and next month.
DO $$
DECLARE
    i INT;
    start_month DATE;
    end_month DATE;
    part_name TEXT;
BEGIN
    FOR i IN -1..1 LOOP
        start_month := date_trunc('month', now() + (i || ' month')::interval)::date;
        end_month := (date_trunc('month', now() + ((i + 1) || ' month')::interval))::date;
        part_name := 'identity.role_assignment_history_' || to_char(start_month, 'YYYY_MM');
        EXECUTE format(
            'CREATE TABLE IF NOT EXISTS %I PARTITION OF identity.role_assignment_history FOR VALUES FROM (%L) TO (%L)',
            part_name, start_month, end_month
        );
    END LOOP;
END $$;

-- immutability trigger on role_assignment_history
CREATE OR REPLACE FUNCTION identity.prevent_role_assignment_history_mutation()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'identity.role_assignment_history is append-only';
END;
$$;

DROP TRIGGER IF EXISTS role_assignment_history_immutable ON identity.role_assignment_history;
CREATE TRIGGER role_assignment_history_immutable
    BEFORE UPDATE OR DELETE ON identity.role_assignment_history
    FOR EACH ROW EXECUTE FUNCTION identity.prevent_role_assignment_history_mutation();

-- =========================================================================
-- 3. identity.identity_claim_history: pre-create next 30 days of monthly partitions
--    (the parent was created in V2 with a DEFAULT partition; here we add the
--    explicit monthly partitions and the immutability trigger.)
-- =========================================================================

CREATE OR REPLACE FUNCTION identity.prevent_claim_history_mutation()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'identity.identity_claim_history is append-only';
END;
$$;

DROP TRIGGER IF EXISTS identity_claim_history_immutable ON identity.identity_claim_history;
CREATE TRIGGER identity_claim_history_immutable
    BEFORE UPDATE OR DELETE ON identity.identity_claim_history
    FOR EACH ROW EXECUTE FUNCTION identity.prevent_claim_history_mutation();

-- Generate monthly partitions: previous, current and next month.
DO $$
DECLARE
    i INT;
    start_month DATE;
    end_month DATE;
    part_name TEXT;
BEGIN
    FOR i IN -1..2 LOOP
        start_month := date_trunc('month', now() + (i || ' month')::interval)::date;
        end_month := (date_trunc('month', now() + ((i + 1) || ' month')::interval))::date;
        part_name := 'identity.identity_claim_history_' || to_char(start_month, 'YYYY_MM');
        EXECUTE format(
            'CREATE TABLE IF NOT EXISTS %I PARTITION OF identity.identity_claim_history FOR VALUES FROM (%L) TO (%L)',
            part_name, start_month, end_month
        );
    END LOOP;
END $$;

-- =========================================================================
-- 4. identity.identities: partial indexes on cross-service ID columns
-- =========================================================================
CREATE INDEX IF NOT EXISTS identities_customer_id_idx
    ON identity.identities (customer_id) WHERE customer_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS identities_driver_id_idx
    ON identity.identities (driver_id) WHERE driver_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS identities_courier_id_idx
    ON identity.identities (courier_id) WHERE courier_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS identities_merchant_id_idx
    ON identity.identities (merchant_id) WHERE merchant_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS identities_restaurant_staff_id_idx
    ON identity.identities (restaurant_staff_id) WHERE restaurant_staff_id IS NOT NULL;

-- =========================================================================
-- 5. identity.identity_claims upsert helper used by application code
-- =========================================================================
CREATE INDEX IF NOT EXISTS identity_claims_refreshed_at_idx
    ON identity.identity_claims (last_refreshed_at);
