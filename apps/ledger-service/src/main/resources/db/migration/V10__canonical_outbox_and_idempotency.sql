-- V10: canonical outbox + idempotency tables per ADR-0028 / ADR-0027.
--
-- Phase B of the platform-DRY initiative: ledger-service adopts the
-- platform canonical 11-column outbox (per ADR-0028) and the canonical
-- idempotency record (per ADR-0027).
-- See:
--   * docs/architecture/adrs/0028-outbox-event-schema.md
--   * docs/architecture/adrs/0027-idempotency-record-schema.md
--   * docs/shared/PLATFORM_DRY_AUDIT.md §B
--
-- ledger.outbox already exists (V2) with 10 columns. V10 ALTERs the
-- table in place — NOT destructive — to add the canonical 11-column
-- shape (ADR-0028) plus correlation_id/created_by service-local
-- columns.

-- ----------------------------------------------------------------------------
-- Canonical outbox (ADR-0028) on top of the existing ledger.outbox
-- ----------------------------------------------------------------------------
ALTER TABLE ledger.outbox
    ADD COLUMN IF NOT EXISTS event_id        UUID UNIQUE DEFAULT gen_random_uuid(),
    ADD COLUMN IF NOT EXISTS partition_key   TEXT NOT NULL DEFAULT 'ledger',
    ADD COLUMN IF NOT EXISTS headers         JSONB NOT NULL DEFAULT '{}'::JSONB,
    ADD COLUMN IF NOT EXISTS next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    ADD COLUMN IF NOT EXISTS correlation_id  UUID NOT NULL DEFAULT gen_random_uuid(),
    ADD COLUMN IF NOT EXISTS created_by      UUID NOT NULL DEFAULT gen_random_uuid();

CREATE INDEX IF NOT EXISTS idx_ledger_outbox_pending
    ON ledger.outbox (next_attempt_at)
    WHERE published_at IS NULL;

-- ----------------------------------------------------------------------------
-- Canonical idempotency (ADR-0027)
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS ledger.idempotency (
    id               UUID         PRIMARY KEY,
    actor_id         UUID         NOT NULL,
    idempotency_key  UUID         NOT NULL,
    request_hash     CHAR(64)     NOT NULL,
    response_status  INT,
    response_body    JSONB,
    state            VARCHAR(16)  NOT NULL DEFAULT 'PENDING',
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    expires_at       TIMESTAMPTZ  NOT NULL,

    CONSTRAINT ck_ledger_idempotency_state CHECK (state IN ('PENDING', 'COMPLETED', 'RELEASED')),
    CONSTRAINT ck_ledger_idempotency_request_hash_len CHECK (char_length(request_hash) = 64),
    CONSTRAINT uq_ledger_idempotency_actor_key UNIQUE (actor_id, idempotency_key)
);

CREATE INDEX IF NOT EXISTS idx_ledger_idempotency_expires_at
    ON ledger.idempotency (expires_at);
