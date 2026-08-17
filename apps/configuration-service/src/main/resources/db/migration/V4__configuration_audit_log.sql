-- V4__configuration_audit_log.sql
-- Per docs/services/configuration-service/ERD.md §3 (AuditLog):
--   configuration.audit_log — append-only local audit cache, monthly
--   partitioned on created_at.
--
-- UPDATE/DELETE are revoked via a trigger (SEC-007 / ERD §3 Constraints)
-- so the audit chain is preserved even if a role misconfiguration happens.

-- =========================================================================
-- 1. Parent table
-- =========================================================================
CREATE TABLE IF NOT EXISTS configuration.audit_log (
    id UUID NOT NULL,
    document_id UUID NOT NULL,
    version BIGINT NOT NULL,
    action TEXT NOT NULL
        CHECK (action IN ('create','update','rollback',
                          'deactivate','reactivate','deprecate')),
    old_value JSONB,
    new_value JSONB,
    actor_id UUID NOT NULL,
    reason TEXT NOT NULL,
    correlation_id UUID NOT NULL,
    client_ip TEXT,
    request_signature TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (id, created_at)
) PARTITION BY RANGE (created_at);

-- Secondary indexes.
CREATE INDEX IF NOT EXISTS idx_audit_log_doc_created
    ON configuration.audit_log (document_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_audit_log_actor
    ON configuration.audit_log (actor_id);
CREATE INDEX IF NOT EXISTS idx_audit_log_correlation
    ON configuration.audit_log (correlation_id);
CREATE INDEX IF NOT EXISTS idx_audit_log_created_at
    ON configuration.audit_log (created_at);

-- =========================================================================
-- 2. Default partition + pre-create monthly children
-- =========================================================================
CREATE TABLE IF NOT EXISTS configuration.audit_log_default
    PARTITION OF configuration.audit_log DEFAULT;

DO $$
DECLARE
    i INT;
    start_month DATE;
    end_month DATE;
    part_name TEXT;
BEGIN
    FOR i IN -1..12 LOOP
        start_month := date_trunc('month', now() + (i || ' month')::interval)::date;
        end_month := (date_trunc('month', now() + ((i + 1) || ' month')::interval))::date;
        part_name := 'configuration.audit_log_' || to_char(start_month, 'YYYY_MM');
        EXECUTE format(
            'CREATE TABLE IF NOT EXISTS %I PARTITION OF configuration.audit_log
             FOR VALUES FROM (%L) TO (%L)',
            part_name, start_month, end_month
        );
    END LOOP;
END $$;

-- =========================================================================
-- 3. Immutability trigger — reject UPDATE/DELETE
-- =========================================================================
CREATE OR REPLACE FUNCTION configuration.prevent_audit_log_mutation()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'configuration.audit_log is append-only (SEC-007)';
END;
$$;

DROP TRIGGER IF EXISTS audit_log_immutable ON configuration.audit_log;
CREATE TRIGGER audit_log_immutable
    BEFORE UPDATE OR DELETE ON configuration.audit_log
    FOR EACH ROW EXECUTE FUNCTION configuration.prevent_audit_log_mutation();
