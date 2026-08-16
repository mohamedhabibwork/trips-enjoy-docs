-- V4__deliveries_outbox_inbox.sql
-- Per docs/services/notification-service/ERD.md:
--   - notification.deliveries : monthly RANGE-partitioned on created_at;
--     append-mostly (state transitions only); binds to template_history
--     via template_version_snapshot_id.
--   - notification.outbox      : transactional outbox for produced events.
--   - notification.inbox       : dedup table for consumed events (event_id PK).
--
-- Cross-service IDs (user_id, template_id, payment_id, request_id) are
-- UUID columns WITHOUT foreign keys (DATA_OWNERSHIP.md). The
-- notification.deliveries composite PK is (id, created_at) per the canonical
-- partition template (DATABASE_ARCHITECTURE.md §6).

-- =========================================================================
-- 1. notification.deliveries (partitioned parent)
-- =========================================================================
CREATE TABLE IF NOT EXISTS notification.deliveries (
    id                                UUID NOT NULL,
    user_id                           UUID NOT NULL,
    template_id                       UUID NOT NULL,
    template_version_snapshot_id      UUID,
    rendered_template_version         INT,
    rendered_template_type            TEXT,
    rendered_provider_template_id     TEXT,
    rendered_provider_template_language TEXT,
    template_name                     TEXT NOT NULL,
    category                          TEXT NOT NULL,
    channel                           TEXT NOT NULL,
    locale                            TEXT NOT NULL,
    status                            TEXT NOT NULL DEFAULT 'queued',
    attempt                           INT  NOT NULL DEFAULT 0,
    rendered_subject_encrypted        BYTEA,
    rendered_body_encrypted           BYTEA,
    dedup_key                         TEXT NOT NULL,
    request_idempotency_key           TEXT,
    correlation_id                    UUID NOT NULL,
    gateway_request_id                TEXT,
    gateway_response_status           INT,
    gateway_response_body             JSONB,
    failure_reason                    TEXT,
    request_id                        UUID,
    service                           TEXT,
    payment_id                        UUID,
    sent_at                           TIMESTAMPTZ,
    delivered_at                      TIMESTAMPTZ,
    read_at                           TIMESTAMPTZ,
    failed_at                         TIMESTAMPTZ,
    suppressed_at                     TIMESTAMPTZ,
    version                           BIGINT NOT NULL DEFAULT 0,
    created_at                        TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at                        TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (id, created_at),
    CONSTRAINT deliveries_channel_check
        CHECK (channel IN ('push', 'sms', 'email', 'in_app', 'whatsapp')),
    CONSTRAINT deliveries_status_check
        CHECK (status IN ('queued', 'rendering', 'suppressed', 'sending', 'sent', 'delivered', 'read', 'failed')),
    CONSTRAINT deliveries_service_check
        CHECK (service IS NULL OR service IN ('trip', 'food_order', 'courier_delivery', 'chat', 'reward', 'refund', 'deal', 'onboarding', 'safety')),
    CONSTRAINT deliveries_whatsapp_provider_template_required_chk CHECK (
        (channel = 'whatsapp' AND rendered_provider_template_id IS NOT NULL) OR
        (channel <> 'whatsapp')
    ),
    CONSTRAINT deliveries_read_only_whatsapp_chk CHECK (
        (status = 'read') OR (status <> 'read')
    )
) PARTITION BY RANGE (created_at);

-- DEFAULT partition guarantees writes never fail because of a missing child.
-- The PartitionMaintenanceJob materialises explicit monthly children for the
-- pre-create window so most writes hit a properly bounded child.
CREATE TABLE IF NOT EXISTS notification.deliveries_default
    PARTITION OF notification.deliveries DEFAULT;

-- Indexes from ERD §3
CREATE INDEX IF NOT EXISTS idx_deliveries_user_created
    ON notification.deliveries (user_id, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_deliveries_template_name_created
    ON notification.deliveries (template_name, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_deliveries_status_created
    ON notification.deliveries (status, created_at)
    WHERE status IN ('failed', 'suppressed');

CREATE INDEX IF NOT EXISTS idx_deliveries_correlation
    ON notification.deliveries (correlation_id);

CREATE INDEX IF NOT EXISTS idx_deliveries_dedup_key
    ON notification.deliveries (dedup_key);

CREATE INDEX IF NOT EXISTS idx_deliveries_request_idempotency_key
    ON notification.deliveries (request_idempotency_key)
    WHERE request_idempotency_key IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_deliveries_template_version_snapshot
    ON notification.deliveries (template_version_snapshot_id)
    WHERE template_version_snapshot_id IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_deliveries_channel_provider_template
    ON notification.deliveries (channel, rendered_provider_template_id)
    WHERE channel = 'whatsapp';

CREATE INDEX IF NOT EXISTS idx_deliveries_request_service
    ON notification.deliveries (request_id, service)
    WHERE request_id IS NOT NULL;

-- Pre-create partitions for previous, current and next month so INSERTs work
-- out of the box on a fresh database. The PartitionMaintenanceJob extends
-- this to the next 12 months on its daily run.
DO $$
DECLARE
    i INT;
    start_month DATE;
    end_month DATE;
    part_name TEXT;
BEGIN
    FOR i IN -1..2 LOOP
        start_month := date_trunc('month', now() + (i || ' month')::interval)::date;
        end_month := (date_trunc('month', now() + ((i + 1) || ' month')::interval))::date;
        part_name := 'notification.deliveries_' || to_char(start_month, 'YYYY_MM');
        EXECUTE format(
            'CREATE TABLE IF NOT EXISTS %I PARTITION OF notification.deliveries FOR VALUES FROM (%L) TO (%L)',
            part_name, start_month, end_month
        );
    END LOOP;
END $$;

-- =========================================================================
-- 2. notification.outbox (transactional outbox)
-- =========================================================================
CREATE TABLE IF NOT EXISTS notification.outbox (
    id              UUID PRIMARY KEY,
    aggregate_type  TEXT NOT NULL,
    aggregate_id    UUID,
    topic           TEXT NOT NULL,
    event_name      TEXT NOT NULL,
    payload         JSONB NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    published_at    TIMESTAMPTZ,
    attempts        INT NOT NULL DEFAULT 0,
    last_error      TEXT
);

CREATE INDEX IF NOT EXISTS idx_outbox_unpublished
    ON notification.outbox (created_at)
    WHERE published_at IS NULL;

CREATE INDEX IF NOT EXISTS idx_outbox_aggregate
    ON notification.outbox (aggregate_type, aggregate_id);

-- =========================================================================
-- 3. notification.inbox (consumer-side dedup on event_id)
-- =========================================================================
CREATE TABLE IF NOT EXISTS notification.inbox (
    event_id      UUID PRIMARY KEY,
    topic         TEXT NOT NULL,
    consumer      TEXT NOT NULL,
    received_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    processed_at  TIMESTAMPTZ,
    error         TEXT
);

CREATE INDEX IF NOT EXISTS idx_inbox_topic_received
    ON notification.inbox (topic, received_at DESC);

CREATE INDEX IF NOT EXISTS idx_inbox_unprocessed
    ON notification.inbox (received_at)
    WHERE processed_at IS NULL;