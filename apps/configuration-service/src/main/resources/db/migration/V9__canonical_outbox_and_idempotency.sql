-- V9: canonical outbox + idempotency tables per ADR-0028 / ADR-0027.
--
-- Phase B of the platform-DRY initiative: configuration-service adopts
-- the platform canonical 11-column outbox (per ADR-0028) and the
-- canonical idempotency record (per ADR-0027).
-- See:
--   * docs/architecture/adrs/0028-outbox-event-schema.md
--   * docs/architecture/adrs/0027-idempotency-record-schema.md
--   * docs/shared/PLATFORM_DRY_AUDIT.md §B
--
-- configuration.outbox already exists (V6) with 10 columns
-- (id, topic, event_id, payload, headers, created_at, claimed_at,
-- published_at, attempts, last_error). V9 ALTERs the table in place
-- — NOT destructive — to add the canonical 11-column shape (ADR-0028):
--   * partition_key   (NOT NULL) — Kafka partition key
--   * next_attempt_at (NOT NULL) — FOR UPDATE SKIP LOCKED poll loop
-- The local `claimed_at` column is kept (configuration-service uses it
-- for the worker-claim contract).
--
-- The canonical `configuration.idempotency` table is created here so
-- service code that adopts `IdempotencyRecordCanonical` later in
-- Phase D has a target.

-- ----------------------------------------------------------------------------
-- Canonical outbox (ADR-0028) on top of the existing configuration.outbox
-- ----------------------------------------------------------------------------
ALTER TABLE configuration.outbox
    ADD COLUMN IF NOT EXISTS partition_key   TEXT NOT NULL DEFAULT 'configuration',
    ADD COLUMN IF NOT EXISTS next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT now();

-- Replace the existing unpublished index with the canonical pending
-- index on next_attempt_at. The existing idx_outbox_unpublished on
-- created_at is kept for the lag dashboard.
CREATE INDEX IF NOT EXISTS idx_configuration_outbox_pending
    ON configuration.outbox (next_attempt_at)
    WHERE published_at IS NULL;

-- ----------------------------------------------------------------------------
-- Canonical idempotency (ADR-0027)
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS configuration.idempotency (
    id               UUID         PRIMARY KEY,
    actor_id         UUID         NOT NULL,
    idempotency_key  UUID         NOT NULL,
    request_hash     CHAR(64)     NOT NULL,
    response_status  INT,
    response_body    JSONB,
    state            VARCHAR(16)  NOT NULL DEFAULT 'PENDING',
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    expires_at       TIMESTAMPTZ  NOT NULL,

    CONSTRAINT ck_configuration_idempotency_state CHECK (state IN ('PENDING', 'COMPLETED', 'RELEASED')),
    CONSTRAINT ck_configuration_idempotency_request_hash_len CHECK (char_length(request_hash) = 64),
    CONSTRAINT uq_configuration_idempotency_actor_key UNIQUE (actor_id, idempotency_key)
);

CREATE INDEX IF NOT EXISTS idx_configuration_idempotency_expires_at
    ON configuration.idempotency (expires_at);
