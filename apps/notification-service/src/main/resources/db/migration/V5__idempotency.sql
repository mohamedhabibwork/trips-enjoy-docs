-- V5__idempotency.sql
-- Per docs/architecture/API_STANDARDS.md §9 + docs/services/notification-service/SRS.md:
--   - notification.idempotency_records : (actor_id, idempotency_key, request_hash, response_status,
--                                          response_body, expires_at) — 24h dedup window.
--
-- A partial index on the (actor_id, idempotency_key) lookup plus a TTL-style
-- filter index on expires_at supports fast dedup checks and cheap cleanup.

CREATE TABLE IF NOT EXISTS notification.idempotency_records (
    id                UUID PRIMARY KEY,
    actor_id          UUID NOT NULL,
    idempotency_key   UUID NOT NULL,
    request_hash      TEXT NOT NULL,
    response_status   INT  NOT NULL,
    response_body     TEXT NOT NULL,
    expires_at        TIMESTAMPTZ NOT NULL,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_idempotency_actor_key
    ON notification.idempotency_records (actor_id, idempotency_key);

CREATE INDEX IF NOT EXISTS idx_idempotency_expires
    ON notification.idempotency_records (expires_at);