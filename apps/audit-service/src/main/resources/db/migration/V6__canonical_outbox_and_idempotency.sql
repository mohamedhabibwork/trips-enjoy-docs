-- V6: canonical outbox + idempotency tables per ADR-0028 / ADR-0027.
--
-- Phase B of the platform-DRY initiative: audit-service adopts the
-- platform canonical 11-column outbox (per ADR-0028) and the canonical
-- idempotency record (per ADR-0027).
-- See:
--   * docs/architecture/adrs/0028-outbox-event-schema.md
--   * docs/architecture/adrs/0027-idempotency-record-schema.md
--   * docs/shared/PLATFORM_DRY_AUDIT.md §B
--
-- audit.outbox already exists (V3) with 10 columns: id, aggregate_type,
-- aggregate_id, topic, event_name, payload, created_at, published_at,
-- attempts, last_error. V6 ALTERs that table in place — NOT destructive
-- — to add the canonical 11-column shape (ADR-0028) plus the audit-
-- service service-local extras that the pilot pattern uses
-- (correlation_id, created_by). The local `OutboxEvent` entity maps to
-- the same table; new columns are populated by `@PrePersist` and
-- existing rows get backfilled via column DEFAULTs.
--
-- The canonical `audit.idempotency` table is created here so service
-- code that adopts `IdempotencyRecordCanonical` later in Phase D has a
-- target.

-- ----------------------------------------------------------------------------
-- Canonical outbox (ADR-0028) on top of the existing audit.outbox
-- ----------------------------------------------------------------------------
-- Canonical 11 cols not yet present on the audit-service outbox:
--   * event_id        (UNIQUE) — consumer dedup key
--   * partition_key   (NOT NULL) — Kafka partition key
--   * headers         (JSONB '{}') — event envelope headers
--   * next_attempt_at (NOT NULL) — FOR UPDATE SKIP LOCKED poll loop
--
-- Service-local extras the canonical entity needs (mirrors payment-service
-- pilot):
--   * correlation_id  (NOT NULL)
--   * created_by      (NOT NULL)
ALTER TABLE audit.outbox
    ADD COLUMN IF NOT EXISTS event_id        UUID UNIQUE DEFAULT gen_random_uuid(),
    ADD COLUMN IF NOT EXISTS partition_key   TEXT NOT NULL DEFAULT 'audit',
    ADD COLUMN IF NOT EXISTS headers         JSONB NOT NULL DEFAULT '{}'::JSONB,
    ADD COLUMN IF NOT EXISTS next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    ADD COLUMN IF NOT EXISTS correlation_id  UUID NOT NULL DEFAULT gen_random_uuid(),
    ADD COLUMN IF NOT EXISTS created_by      UUID NOT NULL DEFAULT gen_random_uuid();

-- Replace the audit-service local 'event_name' (TEXT NOT NULL) with a
-- canonical mirror in headers JSONB (already populated by the entity).
-- The local column is kept for backward compat with the existing
-- OutboxEvent mapping and existing data — no rename.

-- Partial index for the FOR UPDATE SKIP LOCKED poll loop.
CREATE INDEX IF NOT EXISTS idx_audit_outbox_pending
    ON audit.outbox (next_attempt_at)
    WHERE published_at IS NULL;

-- ----------------------------------------------------------------------------
-- Canonical idempotency (ADR-0027)
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS audit.idempotency (
    id               UUID         PRIMARY KEY,
    actor_id         UUID         NOT NULL,
    idempotency_key  UUID         NOT NULL,
    request_hash     CHAR(64)     NOT NULL,
    response_status  INT,
    response_body    JSONB,
    state            VARCHAR(16)  NOT NULL DEFAULT 'PENDING',
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    expires_at       TIMESTAMPTZ  NOT NULL,

    CONSTRAINT ck_audit_idempotency_state CHECK (state IN ('PENDING', 'COMPLETED', 'RELEASED')),
    CONSTRAINT ck_audit_idempotency_request_hash_len CHECK (char_length(request_hash) = 64),
    CONSTRAINT uq_audit_idempotency_actor_key UNIQUE (actor_id, idempotency_key)
);

CREATE INDEX IF NOT EXISTS idx_audit_idempotency_expires_at
    ON audit.idempotency (expires_at);
