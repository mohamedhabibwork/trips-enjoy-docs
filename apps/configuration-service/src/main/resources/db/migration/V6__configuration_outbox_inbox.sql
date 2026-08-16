-- V6__configuration_outbox_inbox.sql
-- Per docs/services/configuration-service/ERD.md §3 (Outbox):
--   configuration.outbox — transactional outbox for produced events,
--   polled every second by OutboxPublisher.
--   configuration.inbox — dedup of consumed events keyed by event_id
--   (consumed Kafka messages; matches INTEGRATION.md §4 dedup contract).

-- =========================================================================
-- 1. configuration.outbox
-- =========================================================================
CREATE TABLE IF NOT EXISTS configuration.outbox (
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

-- Partial index for the publisher's working set (unpublished rows).
CREATE INDEX IF NOT EXISTS idx_outbox_unpublished
    ON configuration.outbox (claimed_at)
    WHERE published_at IS NULL;

CREATE INDEX IF NOT EXISTS idx_outbox_topic
    ON configuration.outbox (topic);

-- =========================================================================
-- 2. configuration.inbox — Kafka dedup
-- =========================================================================
CREATE TABLE IF NOT EXISTS configuration.inbox (
    event_id UUID PRIMARY KEY,
    topic TEXT NOT NULL,
    received_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    processed_at TIMESTAMPTZ,
    error TEXT
);
CREATE INDEX IF NOT EXISTS idx_inbox_topic ON configuration.inbox (topic);
CREATE INDEX IF NOT EXISTS idx_inbox_received_at ON configuration.inbox (received_at);
