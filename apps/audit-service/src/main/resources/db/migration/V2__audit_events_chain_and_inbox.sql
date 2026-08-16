-- V2__audit_events_chain_and_inbox.sql
-- Per docs/services/audit-service/ERD.md:
--   - audit.events    : append-only, hash-chained, range-partitioned by month on created_at
--   - audit.inbox     : deduplication of consumed events (event_id UNIQUE)
-- Plus immutability triggers (SEC--004) and initial monthly partitions.

-- =========================================================================
-- 1. audit.events — append-only, hash-chained, monthly partitioned
-- =========================================================================
CREATE TABLE IF NOT EXISTS audit.events (
    id UUID NOT NULL,
    event_id UUID NOT NULL,
    event_name TEXT NOT NULL,
    schema_version INT NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL,
    received_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    producer TEXT NOT NULL,
    tenant_id TEXT NOT NULL,
    correlation_id UUID NOT NULL,
    causation_id UUID,
    aggregate_type TEXT NOT NULL,
    aggregate_id UUID,
    subject_type TEXT,
    subject_id UUID,
    data JSONB NOT NULL,
    headers JSONB,
    topic TEXT NOT NULL,
    partition INT NOT NULL,
    "offset" BIGINT NOT NULL,
    prev_hash TEXT,
    hash TEXT NOT NULL,
    retention_class TEXT NOT NULL,
    litigation_hold BOOLEAN NOT NULL DEFAULT FALSE,
    retention_until TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (id, created_at),
    CONSTRAINT events_retention_class_check CHECK (retention_class IN ('financial', 'default'))
) PARTITION BY RANGE (created_at);

-- Indexes from ERD §3
CREATE INDEX IF NOT EXISTS idx_events_event_id ON audit.events (event_id, created_at);
CREATE INDEX IF NOT EXISTS idx_events_topic_partition_offset ON audit.events (topic, partition, "offset");
CREATE INDEX IF NOT EXISTS idx_events_tenant_occurred ON audit.events (tenant_id, occurred_at DESC);
CREATE INDEX IF NOT EXISTS idx_events_subject ON audit.events (subject_type, subject_id, occurred_at DESC);
CREATE INDEX IF NOT EXISTS idx_events_correlation ON audit.events (correlation_id);
CREATE INDEX IF NOT EXISTS idx_events_retention ON audit.events (retention_class, retention_until);
CREATE INDEX IF NOT EXISTS idx_events_litigation ON audit.events (litigation_hold) WHERE litigation_hold = TRUE;

-- DEFAULT partition ensures inserts never fail because of a missing child
-- partition. The maintenance job (AuditPartitionJob) materializes explicit
-- monthly children for the pre-create window so most writes hit a properly
-- bounded child.
CREATE TABLE IF NOT EXISTS audit.events_default PARTITION OF audit.events DEFAULT;

-- Pre-create partitions for previous, current and next month so INSERTs work
-- out of the box on a fresh database. The AuditPartitionJob extends this to
-- the next 12 months on its daily run.
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
        part_name := 'audit.events_' || to_char(start_month, 'YYYY_MM');
        EXECUTE format(
            'CREATE TABLE IF NOT EXISTS %I PARTITION OF audit.events FOR VALUES FROM (%L) TO (%L)',
            part_name, start_month, end_month
        );
    END LOOP;
END $$;

-- =========================================================================
-- 2. audit.inbox — Kafka dedup keyed by event_id
-- =========================================================================
CREATE TABLE IF NOT EXISTS audit.inbox (
    event_id UUID PRIMARY KEY,
    topic TEXT NOT NULL,
    received_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    processed_at TIMESTAMPTZ,
    error TEXT
);
CREATE INDEX IF NOT EXISTS idx_inbox_received_at ON audit.inbox (received_at);

-- =========================================================================
-- 3. Immutability trigger — reject UPDATE/DELETE on the audit log
--    (SEC--004 + ERD §3 Constraints)
-- =========================================================================
CREATE OR REPLACE FUNCTION audit.prevent_events_mutation()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'audit.events is append-only';
END;
$$;

DROP TRIGGER IF EXISTS events_immutable ON audit.events;
CREATE TRIGGER events_immutable
    BEFORE UPDATE OR DELETE ON audit.events
    FOR EACH ROW EXECUTE FUNCTION audit.prevent_events_mutation();
