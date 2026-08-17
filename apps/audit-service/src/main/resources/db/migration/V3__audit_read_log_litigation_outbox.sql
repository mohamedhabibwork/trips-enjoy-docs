-- V3__audit_read_log_litigation_outbox.sql
-- Per docs/services/audit-service/ERD.md:
--   - audit.read_log       : append-only access log, range-partitioned by month
--   - audit.litigation_hold: append-only registry of litigation holds
--   - audit.outbox         : transactional outbox for operational events
--
-- Immutability triggers mirror V2 (SEC--004).

-- =========================================================================
-- 1. audit.read_log
-- =========================================================================
CREATE TABLE IF NOT EXISTS audit.read_log (
    id UUID NOT NULL,
    actor_id UUID NOT NULL,
    actor_ip INET,
    query JSONB NOT NULL,
    result_count INT NOT NULL,
    reason TEXT NOT NULL,
    correlation_id UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (id, created_at)
) PARTITION BY RANGE (created_at);

CREATE INDEX IF NOT EXISTS idx_read_log_actor ON audit.read_log (actor_id, created_at DESC);

CREATE TABLE IF NOT EXISTS audit.read_log_default PARTITION OF audit.read_log DEFAULT;

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
        part_name := 'audit.read_log_' || to_char(start_month, 'YYYY_MM');
        EXECUTE format(
            'CREATE TABLE IF NOT EXISTS %I PARTITION OF audit.read_log FOR VALUES FROM (%L) TO (%L)',
            part_name, start_month, end_month
        );
    END LOOP;
END $$;

-- =========================================================================
-- 2. audit.litigation_hold
-- =========================================================================
CREATE TABLE IF NOT EXISTS audit.litigation_hold (
    id UUID PRIMARY KEY,
    tenant_id TEXT,
    subject_type TEXT,
    subject_id UUID,
    topic TEXT,
    reason TEXT NOT NULL,
    effective_from TIMESTAMPTZ NOT NULL,
    effective_to TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by UUID NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_litigation_tenant ON audit.litigation_hold (tenant_id, effective_from);
CREATE INDEX IF NOT EXISTS idx_litigation_subject ON audit.litigation_hold (subject_type, subject_id, effective_from);
CREATE INDEX IF NOT EXISTS idx_litigation_topic ON audit.litigation_hold (topic, effective_from);

-- =========================================================================
-- 3. audit.outbox — transactional outbox for operational events
-- =========================================================================
CREATE TABLE IF NOT EXISTS audit.outbox (
    id UUID PRIMARY KEY,
    aggregate_type TEXT NOT NULL,
    aggregate_id UUID,
    topic TEXT NOT NULL,
    event_name TEXT NOT NULL,
    payload JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    published_at TIMESTAMPTZ,
    attempts INT NOT NULL DEFAULT 0,
    last_error TEXT
);
CREATE INDEX IF NOT EXISTS idx_outbox_unpublished ON audit.outbox (created_at) WHERE published_at IS NULL;

-- =========================================================================
-- 4. Immutability triggers
-- =========================================================================
CREATE OR REPLACE FUNCTION audit.prevent_read_log_mutation()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'audit.read_log is append-only';
END;
$$;

DROP TRIGGER IF EXISTS read_log_immutable ON audit.read_log;
CREATE TRIGGER read_log_immutable
    BEFORE UPDATE OR DELETE ON audit.read_log
    FOR EACH ROW EXECUTE FUNCTION audit.prevent_read_log_mutation();

CREATE OR REPLACE FUNCTION audit.prevent_litigation_hold_mutation()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'audit.litigation_hold is append-only (extend with a new row)';
END;
$$;

DROP TRIGGER IF EXISTS litigation_hold_immutable ON audit.litigation_hold;
CREATE TRIGGER litigation_hold_immutable
    BEFORE UPDATE OR DELETE ON audit.litigation_hold
    FOR EACH ROW EXECUTE FUNCTION audit.prevent_litigation_hold_mutation();
