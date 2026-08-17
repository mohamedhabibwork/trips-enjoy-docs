-- V6: canonical outbox + idempotency tables per ADR-0028 / ADR-0027.
--
-- Phase B of the platform-DRY initiative: identity-service adopts the
-- platform canonical 11-column outbox (per ADR-0028) and the canonical
-- idempotency record (per ADR-0027).
-- See:
--   * docs/architecture/adrs/0028-outbox-event-schema.md
--   * docs/architecture/adrs/0027-idempotency-record-schema.md
--   * docs/shared/PLATFORM_DRY_AUDIT.md §B
--
-- identity.outbox already exists (V2) with 10 columns: id,
-- aggregate_type, aggregate_id, topic, event_name, payload,
-- created_at, published_at, attempts, last_error. V6 ALTERs the table
-- in place — NOT destructive — to add the canonical 11-column shape
-- (ADR-0028) plus the identity-service service-local extras that the
-- pilot pattern uses (correlation_id, created_by).
--
-- `identity.idempotency_keys` already exists with the local 8-col
-- shape used by the existing `IdempotencyRecord` entity. V6 keeps it
-- untouched (the local IdempotencyService relies on it for backward
-- compatibility) and creates the canonical `identity.idempotency`
-- table (ADR-0027) so service code that adopts
-- `IdempotencyRecordCanonical` later in Phase D has a target.

-- ----------------------------------------------------------------------------
-- Canonical outbox (ADR-0028) on top of the existing identity.outbox
-- ----------------------------------------------------------------------------
ALTER TABLE identity.outbox
    ADD COLUMN IF NOT EXISTS event_id        UUID UNIQUE DEFAULT gen_random_uuid(),
    ADD COLUMN IF NOT EXISTS partition_key   TEXT NOT NULL DEFAULT 'identity',
    ADD COLUMN IF NOT EXISTS headers         JSONB NOT NULL DEFAULT '{}'::JSONB,
    ADD COLUMN IF NOT EXISTS next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    ADD COLUMN IF NOT EXISTS correlation_id  UUID NOT NULL DEFAULT gen_random_uuid(),
    ADD COLUMN IF NOT EXISTS created_by      UUID NOT NULL DEFAULT gen_random_uuid();

CREATE INDEX IF NOT EXISTS idx_identity_outbox_pending
    ON identity.outbox (next_attempt_at)
    WHERE published_at IS NULL;

-- ----------------------------------------------------------------------------
-- Canonical idempotency (ADR-0027) — alongside the existing
-- identity.idempotency_keys which the local IdempotencyRecord still uses.
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS identity.idempotency (
    id               UUID         PRIMARY KEY,
    actor_id         UUID         NOT NULL,
    idempotency_key  UUID         NOT NULL,
    request_hash     CHAR(64)     NOT NULL,
    response_status  INT,
    response_body    JSONB,
    state            VARCHAR(16)  NOT NULL DEFAULT 'PENDING',
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    expires_at       TIMESTAMPTZ  NOT NULL,

    CONSTRAINT ck_identity_idempotency_state CHECK (state IN ('PENDING', 'COMPLETED', 'RELEASED')),
    CONSTRAINT ck_identity_idempotency_request_hash_len CHECK (char_length(request_hash) = 64),
    CONSTRAINT uq_identity_idempotency_actor_key UNIQUE (actor_id, idempotency_key)
);

CREATE INDEX IF NOT EXISTS idx_identity_idempotency_expires_at
    ON identity.idempotency (expires_at);
