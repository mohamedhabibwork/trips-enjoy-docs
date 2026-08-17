-- 000012_outbox_inbox.up.sql
--
-- file.outbox — transactional outbox per docs/architecture/EVENT_ARCHITECTURE.md.
-- Rows are written in the same transaction that mutates state; a
-- separate poller drains them to Kafka. file.inbox — idempotent
-- consumer-side dedup keyed by event_id.

CREATE TABLE IF NOT EXISTS file.outbox (
    id UUID PRIMARY KEY,
    aggregate_type TEXT NOT NULL,
    aggregate_id TEXT NOT NULL,
    event_name TEXT NOT NULL,
    topic TEXT NOT NULL,
    payload JSONB NOT NULL,
    headers JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    published_at TIMESTAMPTZ,
    attempts INT NOT NULL DEFAULT 0,
    last_error TEXT
);

CREATE INDEX IF NOT EXISTS outbox_pending_idx ON file.outbox (created_at)
    WHERE published_at IS NULL;
CREATE INDEX IF NOT EXISTS outbox_event_name_idx ON file.outbox (event_name);

CREATE TABLE IF NOT EXISTS file.inbox (
    event_id UUID PRIMARY KEY,
    consumer TEXT NOT NULL,
    received_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    processed_at TIMESTAMPTZ,
    error TEXT
);

CREATE INDEX IF NOT EXISTS inbox_received_idx ON file.inbox (received_at);