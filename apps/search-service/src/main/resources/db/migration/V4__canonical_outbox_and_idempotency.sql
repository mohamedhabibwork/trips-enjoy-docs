-- V4: canonical outbox + idempotency tables per ADR-0028 / ADR-0027.
--
-- Phase B of the platform-DRY initiative: search-service adopts the
-- platform canonical 11-column outbox (per ADR-0028) and the canonical
-- idempotency record (per ADR-0027).
-- See:
--   * docs/architecture/adrs/0028-outbox-event-schema.md
--   * docs/architecture/adrs/0027-idempotency-record-schema.md
--   * docs/shared/PLATFORM_DRY_AUDIT.md §B
--
-- search.outbox already exists (V2) with the 14-col local shape (the
-- extra `updated_by` is a service-local addition). V4 ALTERs the table
-- in place — NOT destructive — to add the two canonical columns the
-- local table is missing:
--   * event_id        (UNIQUE) — consumer dedup key
--   * partition_key   (NOT NULL) — Kafka partition key
--
-- The canonical `search.idempotency` table is created here so service
-- code that adopts `IdempotencyRecordCanonical` later in Phase D has a
-- target.

-- ----------------------------------------------------------------------------
-- Canonical outbox (ADR-0028) on top of the existing search.outbox
-- ----------------------------------------------------------------------------
ALTER TABLE search.outbox
    ADD COLUMN IF NOT EXISTS event_id        UUID UNIQUE DEFAULT gen_random_uuid(),
    ADD COLUMN IF NOT EXISTS partition_key   TEXT NOT NULL DEFAULT 'search';

CREATE INDEX IF NOT EXISTS idx_search_outbox_pending
    ON search.outbox (next_attempt_at)
    WHERE published_at IS NULL;

-- ----------------------------------------------------------------------------
-- Canonical idempotency (ADR-0027)
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS search.idempotency (
    id               UUID         PRIMARY KEY,
    actor_id         UUID         NOT NULL,
    idempotency_key  UUID         NOT NULL,
    request_hash     CHAR(64)     NOT NULL,
    response_status  INT,
    response_body    JSONB,
    state            VARCHAR(16)  NOT NULL DEFAULT 'PENDING',
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    expires_at       TIMESTAMPTZ  NOT NULL,

    CONSTRAINT ck_search_idempotency_state CHECK (state IN ('PENDING', 'COMPLETED', 'RELEASED')),
    CONSTRAINT ck_search_idempotency_request_hash_len CHECK (char_length(request_hash) = 64),
    CONSTRAINT uq_search_idempotency_actor_key UNIQUE (actor_id, idempotency_key)
);

CREATE INDEX IF NOT EXISTS idx_search_idempotency_expires_at
    ON search.idempotency (expires_at);
