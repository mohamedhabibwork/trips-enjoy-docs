-- V4: canonical outbox + idempotency tables per ADR-0028 / ADR-0027.
--
-- Phase B of the platform-DRY initiative: driver-service adopts the
-- platform canonical 11-column outbox (per ADR-0028) and the canonical
-- idempotency record (per ADR-0027).
-- See:
--   * docs/architecture/adrs/0028-outbox-event-schema.md
--   * docs/architecture/adrs/0027-idempotency-record-schema.md
--   * docs/shared/PLATFORM_DRY_AUDIT.md §B
--
-- driver.outbox_events already exists (V2) with the 13-col local shape
-- that mirrors payment-service's pre-pilot outbox. V4 ALTERs the table
-- in place — NOT destructive — to add the two canonical columns the
-- local table is missing:
--   * event_id        (UNIQUE) — consumer dedup key
--   * partition_key   (NOT NULL) — Kafka partition key
--
-- The canonical `driver.idempotency` table is created here so service
-- code that adopts `IdempotencyRecordCanonical` later in Phase D has a
-- target.

-- ----------------------------------------------------------------------------
-- Canonical outbox (ADR-0028) on top of the existing driver.outbox_events
-- ----------------------------------------------------------------------------
ALTER TABLE driver.outbox_events
    ADD COLUMN IF NOT EXISTS event_id        UUID UNIQUE DEFAULT gen_random_uuid(),
    ADD COLUMN IF NOT EXISTS partition_key   TEXT NOT NULL DEFAULT 'driver';

CREATE INDEX IF NOT EXISTS idx_driver_outbox_pending
    ON driver.outbox_events (next_attempt_at)
    WHERE published_at IS NULL;

-- ----------------------------------------------------------------------------
-- Canonical idempotency (ADR-0027)
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS driver.idempotency (
    id               UUID         PRIMARY KEY,
    actor_id         UUID         NOT NULL,
    idempotency_key  UUID         NOT NULL,
    request_hash     CHAR(64)     NOT NULL,
    response_status  INT,
    response_body    JSONB,
    state            VARCHAR(16)  NOT NULL DEFAULT 'PENDING',
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    expires_at       TIMESTAMPTZ  NOT NULL,

    CONSTRAINT ck_driver_idempotency_state CHECK (state IN ('PENDING', 'COMPLETED', 'RELEASED')),
    CONSTRAINT ck_driver_idempotency_request_hash_len CHECK (char_length(request_hash) = 64),
    CONSTRAINT uq_driver_idempotency_actor_key UNIQUE (actor_id, idempotency_key)
);

CREATE INDEX IF NOT EXISTS idx_driver_idempotency_expires_at
    ON driver.idempotency (expires_at);
