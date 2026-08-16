CREATE TABLE identity.inbox (
    id UUID PRIMARY KEY,
    event_id UUID NOT NULL UNIQUE,
    topic TEXT NOT NULL,
    received_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX inbox_received_at_idx ON identity.inbox (received_at);

CREATE OR REPLACE FUNCTION identity.prevent_audit_mutation()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'identity_audit_log is append-only';
END;
$$;

CREATE TRIGGER identity_audit_log_immutable
    BEFORE UPDATE OR DELETE ON identity.identity_audit_log
    FOR EACH ROW EXECUTE FUNCTION identity.prevent_audit_mutation();
