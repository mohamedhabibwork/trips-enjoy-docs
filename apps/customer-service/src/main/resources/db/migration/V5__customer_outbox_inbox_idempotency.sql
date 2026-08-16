-- V5__customer_outbox_inbox_idempotency.sql
-- Per docs/services/customer-service/ERD.md §3 + INTEGRATION.md §3 + §4:
--   customer.outbox       : transactional outbox for produced events,
--                           polled by OutboxPublisher and forwarded to Kafka.
--   customer.inbox        : dedup of consumed events keyed by event_id
--                           (TTL 24h).
--   customer.idempotency  : Idempotency-Key dedupe store; per
--                           INTEGRATION.md §1.2 the platform contract
--                           is `(actor, idempotency_key, request_hash,
--                           response_status, response_body, expires_at)`.

-- =========================================================================
-- 1. customer.outbox
-- =========================================================================
CREATE TABLE IF NOT EXISTS customer.outbox (
    id UUID PRIMARY KEY,
    topic TEXT NOT NULL,
    event_id UUID NOT NULL,
    payload JSONB NOT NULL,
    headers JSONB,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    claimed_at TIMESTAMPTZ,
    published_at TIMESTAMPTZ,
    attempts INT NOT NULL DEFAULT 0,
    last_error TEXT
);

CREATE INDEX IF NOT EXISTS customer_outbox_unpublished_idx
    ON customer.outbox (claimed_at)
    WHERE published_at IS NULL;

CREATE INDEX IF NOT EXISTS customer_outbox_topic_idx
    ON customer.outbox (topic);

-- =========================================================================
-- 2. customer.inbox — Kafka dedup
-- =========================================================================
CREATE TABLE IF NOT EXISTS customer.inbox (
    event_id UUID PRIMARY KEY,
    topic TEXT NOT NULL,
    received_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    processed_at TIMESTAMPTZ,
    error TEXT
);

CREATE INDEX IF NOT EXISTS customer_inbox_topic_idx
    ON customer.inbox (topic);

CREATE INDEX IF NOT EXISTS customer_inbox_received_at_idx
    ON customer.inbox (received_at);

-- =========================================================================
-- 3. customer.idempotency — Idempotency-Key dedupe store
-- =========================================================================
CREATE TABLE IF NOT EXISTS customer.idempotency (
    idempotency_key UUID PRIMARY KEY,
    request_hash TEXT NOT NULL,
    response_status INT NOT NULL,
    response_body JSONB NOT NULL,
    actor_id UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX IF NOT EXISTS customer_idempotency_expires_at_idx
    ON customer.idempotency (expires_at);
