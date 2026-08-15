-- 0002_chat_core.sql
-- Per docs/services/chat-service/ERD.md §3:
--   chat.threads               : a chat thread (rider <-> driver/courier/merchant).
--   chat.participants          : thread membership (1 thread = N participants).
--   chat.messages              : the message log.
--   chat.message_attachments   : file/message attachments.
--   chat.read_states           : per-participant read cursors.
--   chat.moderation_reports    : user reports of abuse (triggers admin review).
--   chat.blocked_users         : per-user block list.
--   chat.outbox + chat.inbox   : canonical platform pattern.
--
-- Schema-wide conventions (per the prior 10 graduates):
--   * primary keys are UUIDv7.
--   * cross-service references are plain UUIDs WITHOUT database FKs.
--   * soft delete via deleted_at where applicable.
--   * audit columns (created_at, updated_at, created_by, updated_by).
--   * row_version (BIGINT) is the optimistic-lock counter.

CREATE SCHEMA IF NOT EXISTS chat;

-- 1) chat.threads : a chat thread (rider <-> driver/courier/merchant).
CREATE TABLE IF NOT EXISTS chat.threads (
    id UUID PRIMARY KEY,
    tenant_id TEXT NOT NULL DEFAULT 'global',
    subject_kind TEXT NOT NULL,
    subject_id UUID NOT NULL,
    thread_kind TEXT NOT NULL DEFAULT 'rider_driver',
    status TEXT NOT NULL DEFAULT 'active',
    title TEXT,
    last_message_at TIMESTAMPTZ,
    last_message_preview TEXT,
    created_by UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    archived_at TIMESTAMPTZ,
    row_version BIGINT NOT NULL DEFAULT 1,
    CONSTRAINT threads_subject_kind_check
        CHECK (subject_kind IN ('trip','order','support','merchant')),
    CONSTRAINT threads_thread_kind_check
        CHECK (thread_kind IN ('rider_driver','rider_courier','rider_merchant','rider_support','merchant_support')),
    CONSTRAINT threads_status_check
        CHECK (status IN ('active','archived','closed'))
);

CREATE INDEX IF NOT EXISTS threads_subject_idx
    ON chat.threads (subject_kind, subject_id);
CREATE INDEX IF NOT EXISTS threads_last_message_at_idx
    ON chat.threads (last_message_at DESC)
    WHERE status = 'active';

-- 2) chat.participants : thread membership (1 thread = N participants).
CREATE TABLE IF NOT EXISTS chat.participants (
    thread_id UUID NOT NULL,
    user_id UUID NOT NULL,
    role TEXT NOT NULL,
    joined_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    left_at TIMESTAMPTZ,
    last_read_message_id UUID,
    muted_until TIMESTAMPTZ,
    PRIMARY KEY (thread_id, user_id),
    CONSTRAINT participants_role_check
        CHECK (role IN ('rider','driver','courier','merchant','admin','support'))
);

CREATE INDEX IF NOT EXISTS participants_user_id_idx
    ON chat.participants (user_id);

-- 3) chat.messages : the message log.
CREATE TABLE IF NOT EXISTS chat.messages (
    id UUID PRIMARY KEY,
    thread_id UUID NOT NULL,
    sender_id UUID NOT NULL,
    body TEXT NOT NULL,
    message_kind TEXT NOT NULL DEFAULT 'text',
    reply_to_message_id UUID,
    edited_at TIMESTAMPTZ,
    deleted_at TIMESTAMPTZ,
    correlation_id UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT messages_message_kind_check
        CHECK (message_kind IN ('text','image','file','location','system'))
);

CREATE INDEX IF NOT EXISTS messages_thread_id_idx
    ON chat.messages (thread_id, created_at DESC);
CREATE INDEX IF NOT EXISTS messages_sender_id_idx
    ON chat.messages (sender_id);

-- 4) chat.message_attachments : file/message attachments.
CREATE TABLE IF NOT EXISTS chat.message_attachments (
    id UUID PRIMARY KEY,
    message_id UUID NOT NULL,
    file_id UUID NOT NULL,
    content_type TEXT NOT NULL,
    size_bytes BIGINT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT message_attachments_size_check
        CHECK (size_bytes >= 0)
);

CREATE INDEX IF NOT EXISTS message_attachments_message_id_idx
    ON chat.message_attachments (message_id);

-- 5) chat.read_states : per-participant read cursors.
CREATE TABLE IF NOT EXISTS chat.read_states (
    thread_id UUID NOT NULL,
    user_id UUID NOT NULL,
    last_read_message_id UUID,
    last_read_at TIMESTAMPTZ,
    PRIMARY KEY (thread_id, user_id)
);

-- 6) chat.moderation_reports : user reports of abuse (triggers admin review).
CREATE TABLE IF NOT EXISTS chat.moderation_reports (
    id UUID PRIMARY KEY,
    thread_id UUID NOT NULL,
    message_id UUID NOT NULL,
    reporter_id UUID NOT NULL,
    reason TEXT NOT NULL,
    status TEXT NOT NULL DEFAULT 'pending',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    resolved_at TIMESTAMPTZ,
    resolver_id UUID,
    CONSTRAINT moderation_reports_status_check
        CHECK (status IN ('pending','reviewed','dismissed','actioned'))
);

CREATE INDEX IF NOT EXISTS moderation_reports_status_idx
    ON chat.moderation_reports (status, created_at);

-- 7) chat.blocked_users : per-user block list.
CREATE TABLE IF NOT EXISTS chat.blocked_users (
    blocker_user_id UUID NOT NULL,
    blocked_user_id UUID NOT NULL,
    blocked_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    reason TEXT,
    PRIMARY KEY (blocker_user_id, blocked_user_id)
);

CREATE INDEX IF NOT EXISTS blocked_users_blocked_user_id_idx
    ON chat.blocked_users (blocked_user_id);

-- 8) chat.outbox : transactional outbox.
CREATE TABLE IF NOT EXISTS chat.outbox (
    id UUID PRIMARY KEY,
    aggregate_type TEXT NOT NULL,
    aggregate_id UUID NOT NULL,
    event_type TEXT NOT NULL,
    topic TEXT NOT NULL,
    payload JSONB NOT NULL,
    headers JSONB,
    correlation_id UUID NOT NULL,
    attempts INT NOT NULL DEFAULT 0,
    last_error TEXT,
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    published_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by UUID NOT NULL,
    updated_by UUID NOT NULL
);

CREATE INDEX IF NOT EXISTS outbox_pending_idx ON chat.outbox (next_attempt_at)
    WHERE published_at IS NULL;
CREATE INDEX IF NOT EXISTS outbox_aggregate_idx ON chat.outbox (aggregate_type, aggregate_id);

-- 9) chat.inbox : idempotent inbox.
CREATE TABLE IF NOT EXISTS chat.inbox (
    id UUID PRIMARY KEY,
    source_topic TEXT NOT NULL,
    source_event_id UUID NOT NULL,
    event_type TEXT NOT NULL,
    payload JSONB NOT NULL,
    correlation_id UUID NOT NULL,
    consumed_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    processed_at TIMESTAMPTZ,
    created_by UUID NOT NULL,
    updated_by UUID NOT NULL
);

CREATE UNIQUE INDEX IF NOT EXISTS inbox_topic_event_uniq
    ON chat.inbox (source_topic, source_event_id);

-- 10) chat.idempotency_keys : the canonical scope+key Idempotency-Key.
CREATE TABLE IF NOT EXISTS chat.idempotency_keys (
    id UUID PRIMARY KEY,
    scope TEXT NOT NULL,
    idem_key TEXT NOT NULL,
    request_hash TEXT NOT NULL,
    response_status INT,
    response_body JSONB,
    locked_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    completed_at TIMESTAMPTZ,
    created_by UUID NOT NULL,
    CONSTRAINT idempotency_keys_scope_check
        CHECK (scope IN ('chat_message','thread_create','moderation_report','Mute','unmute'))
);

CREATE UNIQUE INDEX IF NOT EXISTS idempotency_keys_scope_key_uniq
    ON chat.idempotency_keys (scope, idem_key);