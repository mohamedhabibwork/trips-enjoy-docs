-- V5__configuration_idempotency.sql
-- Per docs/services/configuration-service/ERD.md §3 (Idempotency):
--   configuration.idempotency — Idempotency-Key dedupe per the platform
--   standard, 24h retention (purged by a daily job).

CREATE TABLE IF NOT EXISTS configuration.idempotency (
    idempotency_key UUID PRIMARY KEY,
    request_hash TEXT NOT NULL,
    response_status INT NOT NULL,
    response_body JSONB NOT NULL,
    actor_id UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at TIMESTAMPTZ NOT NULL
);

-- Index on expires_at supports the daily purge job
-- (DELETE FROM configuration.idempotency WHERE expires_at < now()).
CREATE INDEX IF NOT EXISTS idx_idempotency_expires
    ON configuration.idempotency (expires_at);

-- Index for actor lookups (used by the daily partition maintenance
-- routine to verify cleanup).
CREATE INDEX IF NOT EXISTS idx_idempotency_actor
    ON configuration.idempotency (actor_id);
