ALTER TABLE identity.identities
    ADD COLUMN IF NOT EXISTS customer_id UUID,
    ADD COLUMN IF NOT EXISTS driver_id UUID,
    ADD COLUMN IF NOT EXISTS courier_id UUID,
    ADD COLUMN IF NOT EXISTS merchant_id UUID,
    ADD COLUMN IF NOT EXISTS restaurant_staff_id UUID;

ALTER TABLE identity.identities
    ADD CONSTRAINT identities_suspended_reason_check
    CHECK (suspended_reason IS NULL OR suspended_reason IN ('fraud', 'payment_failure', 'manual_review', 'security', 'legal'));

CREATE TABLE identity.identity_claims (
    identity_id UUID PRIMARY KEY REFERENCES identity.identities(id),
    name TEXT, email TEXT, phone TEXT, locale TEXT,
    mfa_methods JSONB NOT NULL DEFAULT '[]', amr JSONB NOT NULL DEFAULT '[]',
    last_refreshed_at TIMESTAMPTZ NOT NULL DEFAULT now(), row_version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(), updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE identity.identity_audit_log (
    id UUID PRIMARY KEY, identity_id UUID NOT NULL REFERENCES identity.identities(id), action TEXT NOT NULL,
    actor UUID NOT NULL, actor_type TEXT NOT NULL, reason TEXT, correlation_id UUID, occurred_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE identity.outbox (
    id UUID PRIMARY KEY, aggregate_type TEXT NOT NULL, aggregate_id UUID NOT NULL, topic TEXT NOT NULL,
    event_name TEXT NOT NULL, payload JSONB NOT NULL, created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    published_at TIMESTAMPTZ, attempts INTEGER NOT NULL DEFAULT 0, last_error TEXT
);
CREATE INDEX outbox_unpublished_idx ON identity.outbox (created_at) WHERE published_at IS NULL;

CREATE TABLE identity.idempotency_keys (
    id UUID PRIMARY KEY, actor UUID NOT NULL, idempotency_key UUID NOT NULL, request_hash TEXT NOT NULL,
    response_status INTEGER NOT NULL, response_body JSONB NOT NULL, expires_at TIMESTAMPTZ NOT NULL,
    UNIQUE (actor, idempotency_key)
);

CREATE TABLE identity.identity_claim_history (
    id UUID NOT NULL, identity_id UUID NOT NULL REFERENCES identity.identities(id), field TEXT NOT NULL,
    old_value JSONB, new_value JSONB, source TEXT NOT NULL, changed_at TIMESTAMPTZ NOT NULL DEFAULT now(), changed_by UUID NOT NULL,
    PRIMARY KEY (id, changed_at)
) PARTITION BY RANGE (changed_at);
CREATE TABLE identity.identity_claim_history_default PARTITION OF identity.identity_claim_history DEFAULT;
