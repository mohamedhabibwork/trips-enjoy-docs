-- V4__customer_audit_log.sql
-- Per docs/services/customer-service/ERD.md §3:
--   customer.customer_audit_log : append-only audit of every state
--   change. Immutable; UPDATE / DELETE are rejected at the DB level
--   (DATA--005 / SEC--007).
--
-- Stored as JSONB before/after snapshots so the audit log is
-- self-describing (per platform-wide audit-log shape).

CREATE TABLE IF NOT EXISTS customer.customer_audit_log (
    id UUID PRIMARY KEY,
    customer_id UUID NOT NULL,
    action TEXT NOT NULL,
    actor UUID,
    actor_type TEXT NOT NULL,
    before JSONB,
    after JSONB,
    reason TEXT,
    correlation_id UUID,
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT customer_audit_log_action_check
        CHECK (action IN ('create', 'update', 'kyc_change', 'suspend', 'reinstate',
                          'disable', 'erase', 'default_method_change',
                          'default_address_change', 'ltv_change', 'segment_change')),
    CONSTRAINT customer_audit_log_actor_type_check
        CHECK (actor_type IN ('user', 'admin', 'service', 'system'))
);

CREATE INDEX IF NOT EXISTS customer_audit_log_customer_id_idx
    ON customer.customer_audit_log (customer_id, occurred_at DESC);

CREATE INDEX IF NOT EXISTS customer_audit_log_correlation_id_idx
    ON customer.customer_audit_log (correlation_id);

-- Reject UPDATE / DELETE — audit log is append-only.
DROP TRIGGER IF EXISTS customer_audit_log_append_only ON customer.customer_audit_log;
CREATE TRIGGER customer_audit_log_append_only
    BEFORE UPDATE OR DELETE ON customer.customer_audit_log
    FOR EACH STATEMENT EXECUTE FUNCTION customer.raise_exception();
