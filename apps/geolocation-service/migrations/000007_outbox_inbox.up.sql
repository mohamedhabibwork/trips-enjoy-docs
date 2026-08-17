-- 000007_outbox_inbox.up.sql
--
-- geolocation.outbox + geolocation.inbox — the standard transactional
-- outbox + consumer dedup tables per
-- docs/services/geolocation-service/ERD.md §3.6 + EVENT_ARCHITECTURE.md.
-- Idempotent.
CREATE TABLE IF NOT EXISTS geolocation.outbox (
    id             UUID PRIMARY KEY,
    aggregate_type TEXT NOT NULL,
    aggregate_id   UUID NOT NULL,
    topic          TEXT NOT NULL,
    event_name     TEXT NOT NULL,
    payload        JSONB NOT NULL,
    headers        JSONB NOT NULL,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    claimed_at     TIMESTAMPTZ,
    published_at   TIMESTAMPTZ,
    attempts       INT NOT NULL DEFAULT 0,
    last_error     TEXT
);
CREATE INDEX IF NOT EXISTS outbox_poller_idx
    ON geolocation.outbox (claimed_at, created_at);
CREATE INDEX IF NOT EXISTS outbox_topic_pub_idx
    ON geolocation.outbox (topic, published_at);

CREATE TABLE IF NOT EXISTS geolocation.inbox (
    event_id     UUID PRIMARY KEY,
    consumer     TEXT NOT NULL,
    topic        TEXT NOT NULL,
    received_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    processed_at TIMESTAMPTZ,
    error        TEXT,
    attempts     INT NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS inbox_consumer_received_idx
    ON geolocation.inbox (consumer, received_at);