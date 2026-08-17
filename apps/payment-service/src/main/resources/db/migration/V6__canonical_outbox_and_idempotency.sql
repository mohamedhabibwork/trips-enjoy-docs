-- V6: canonical outbox + idempotency tables per ADR-0028 / ADR-0027.
--
-- Phase B of the platform-DRY initiative: payment-service adopts the
-- platform canonical 11-column outbox (per ADR-0028) and the canonical
-- idempotency record (per ADR-0027).
-- See:
--   * docs/architecture/adrs/0028-outbox-event-schema.md
--   * docs/architecture/adrs/0027-idempotency-record-schema.md
--   * docs/shared/PLATFORM_DRY_AUDIT.md §B
--
-- This migration creates the tables fresh (no prior outbox_events /
-- idempotency_keys tables exist on payment-service per V1-V5 — those
-- V__ migrations only created payment_intents / wallets / earnings /
-- partition functions). V6 establishes:
--
--   * payment.outbox        — canonical 11-col outbox (id, event_id,
--                              topic, partition_key, payload, headers,
--                              created_at, published_at, attempts,
--                              last_error, next_attempt_at) PLUS four
--                              service-local columns (aggregate_type,
--                              aggregate_id, event_type,
--                              correlation_id, created_by) so the
--                              existing local OutboxEvent entity can
--                              keep its constructor contract. The
--                              canonical columns are NOT NULL / UNIQUE
--                              / have checks per ADR-0028.
--   * payment.idempotency   — canonical (actor_id, idempotency_key)
--                             idempotency record per ADR-0027
--
-- The local `payment.domain.IdempotencyKey` entity (used by the
-- `IdempotencyService` for the SCOPE_* constants) remains in place —
-- its test contract depends on it. The canonical payment.idempotency
-- table is created here so service code that adopts
-- `IdempotencyRecordCanonical` later in Phase D has a target.

-- ----------------------------------------------------------------------------
-- Canonical outbox (ADR-0028) + payment-service local columns
-- ----------------------------------------------------------------------------
CREATE TABLE payment.outbox (
    -- canonical 11 columns
    id               UUID         PRIMARY KEY,
    event_id         UUID         NOT NULL UNIQUE,
    topic            TEXT         NOT NULL,
    partition_key    TEXT         NOT NULL,
    payload          JSONB        NOT NULL,
    headers          JSONB        NOT NULL DEFAULT '{}'::JSONB,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    published_at     TIMESTAMPTZ,
    attempts         INT          NOT NULL DEFAULT 0,
    last_error       TEXT,
    next_attempt_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),

    -- payment-service local columns (Phase B keeps the existing entity
    -- contract; columns are NOT NULL where the existing entity requires
    -- them so ddl-auto: validate stays happy).
    aggregate_type   TEXT         NOT NULL,
    aggregate_id     UUID         NOT NULL,
    event_type       TEXT         NOT NULL,
    correlation_id   UUID         NOT NULL,
    created_by       UUID         NOT NULL,

    CONSTRAINT ck_outbox_partition_key_nonnull CHECK (partition_key IS NOT NULL),
    CONSTRAINT ck_outbox_state CHECK (
        (published_at IS NULL AND attempts >= 0) OR
        (published_at IS NOT NULL)
    )
);

-- Partial index for the FOR UPDATE SKIP LOCKED poll loop. Only
-- unpublished rows are scanned, so this stays small.
CREATE INDEX idx_outbox_pending ON payment.outbox (next_attempt_at)
    WHERE published_at IS NULL;

-- ----------------------------------------------------------------------------
-- Canonical idempotency (ADR-0027)
-- ----------------------------------------------------------------------------
CREATE TABLE payment.idempotency (
    id               UUID         PRIMARY KEY,
    actor_id         UUID         NOT NULL,
    idempotency_key  UUID         NOT NULL,
    request_hash     CHAR(64)     NOT NULL,
    response_status  INT,
    response_body    JSONB,
    state            VARCHAR(16)  NOT NULL DEFAULT 'PENDING',
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    expires_at       TIMESTAMPTZ  NOT NULL,

    CONSTRAINT ck_idempotency_state CHECK (state IN ('PENDING', 'COMPLETED', 'RELEASED')),
    CONSTRAINT ck_idempotency_request_hash_len CHECK (char_length(request_hash) = 64),
    CONSTRAINT uq_idempotency_actor_key UNIQUE (actor_id, idempotency_key)
);

CREATE INDEX idx_idempotency_expires_at ON payment.idempotency (expires_at);