-- V2__restaurants_and_audit.sql
-- Per docs/services/restaurant-service/ERD.md §3:
--   restaurant.restaurants            : the restaurant brand (one per merchant row).
--   restaurant.restaurant_cuisines   : many-to-many cuisines.
--   restaurant.restaurant_tags       : many-to-many free-form tags.
--   restaurant.restaurant_audit_log  : append-only admin + cascade audit.
--   restaurant.idempotency_keys      : Idempotency-Key middleware.
--   restaurant.outbox_events         : transactional outbox for kafka publishing.
--   restaurant.inbox_events          : idempotent inbox for kafka consumption.
--
-- Schema-wide conventions (per customer-service + driver-service +
-- payment-service):
--   * primary keys are UUIDv7 (UUID PRIMARY KEY; v7 ordering enforced
--     by the application via kotlin.uuid.Uuid.generateV7().toJavaUuid()).
--   * cross-service references (merchant_id, logo_file_id, cover_file_id)
--     are plain UUIDs WITHOUT database FKs (DATA--003).
--   * soft delete via deleted_at (DATA--006).
--   * audit columns (created_at, updated_at, created_by, updated_by)
--     on every mutable table (DATA--005).
--   * row_version (BIGINT) is the optimistic-lock counter (SRS §14).
--
-- 8-state lifecycle per INTEGRATION §1.4–1.11:
--   draft → pending_review → approved → online ↔ offline
--                                             ↓
--                                          suspended | closed | rejected
--   pending_review → resubmit → pending_review (loops back)

-- 1) restaurant.restaurants : the restaurant brand.
CREATE TABLE IF NOT EXISTS restaurant.restaurants (
    id UUID PRIMARY KEY,
    merchant_id UUID NOT NULL,
    name TEXT NOT NULL,
    slug TEXT NOT NULL UNIQUE,
    type TEXT NOT NULL,
    description TEXT,
    logo_file_id UUID,
    cover_file_id UUID,
    state TEXT NOT NULL DEFAULT 'draft',
    online BOOLEAN NOT NULL DEFAULT false,
    auto_offline_enabled BOOLEAN NOT NULL DEFAULT true,
    avg_rating NUMERIC(3, 2) NOT NULL DEFAULT 0.00,
    review_count INT NOT NULL DEFAULT 0,
    last_rating_update_at TIMESTAMPTZ,
    state_reason_code TEXT,
    state_actor_kc_sub UUID,
    state_changed_at TIMESTAMPTZ,
    row_version BIGINT NOT NULL DEFAULT 1,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by UUID NOT NULL,
    updated_by UUID NOT NULL,
    deleted_at TIMESTAMPTZ,
    CONSTRAINT restaurants_state_check
        CHECK (state IN ('draft','pending_review','approved','rejected','online','offline','suspended','closed')),
    CONSTRAINT restaurants_type_check
        CHECK (type IN ('restaurant','cafe','bakery','cloud_kitchen','food_truck','other')),
    CONSTRAINT restaurants_name_length_check
        CHECK (length(name) BETWEEN 1 AND 120),
    CONSTRAINT restaurants_avg_rating_check
        CHECK (avg_rating >= 0 AND avg_rating <= 5),
    CONSTRAINT restaurants_review_count_check
        CHECK (review_count >= 0)
);

CREATE UNIQUE INDEX IF NOT EXISTS restaurants_slug_uniq
    ON restaurant.restaurants (slug);
CREATE INDEX IF NOT EXISTS restaurants_merchant_id_idx
    ON restaurant.restaurants (merchant_id)
    WHERE deleted_at IS NULL;
CREATE INDEX IF NOT EXISTS restaurants_state_idx
    ON restaurant.restaurants (state)
    WHERE deleted_at IS NULL;
CREATE INDEX IF NOT EXISTS restaurants_hot_state_idx
    ON restaurant.restaurants (state)
    WHERE state IN ('pending_review','approved','online') AND deleted_at IS NULL;
CREATE INDEX IF NOT EXISTS restaurants_online_idx
    ON restaurant.restaurants (online)
    WHERE online = true AND deleted_at IS NULL;

-- 2) restaurant.restaurant_cuisines : many-to-many cuisines.
CREATE TABLE IF NOT EXISTS restaurant.restaurant_cuisines (
    restaurant_id UUID NOT NULL,
    cuisine TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT restaurant_cuisines_pkey PRIMARY KEY (restaurant_id, cuisine),
    CONSTRAINT restaurant_cuisines_length_check
        CHECK (length(cuisine) BETWEEN 1 AND 50)
);

CREATE INDEX IF NOT EXISTS restaurant_cuisines_cuisine_idx
    ON restaurant.restaurant_cuisines (cuisine);

-- 3) restaurant.restaurant_tags : many-to-many free-form tags.
CREATE TABLE IF NOT EXISTS restaurant.restaurant_tags (
    restaurant_id UUID NOT NULL,
    tag TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT restaurant_tags_pkey PRIMARY KEY (restaurant_id, tag),
    CONSTRAINT restaurant_tags_length_check
        CHECK (length(tag) <= 50)
);

CREATE INDEX IF NOT EXISTS restaurant_tags_tag_idx
    ON restaurant.restaurant_tags (tag);

-- 4) restaurant.restaurant_audit_log : append-only audit chain.
CREATE TABLE IF NOT EXISTS restaurant.restaurant_audit_log (
    id UUID PRIMARY KEY,
    restaurant_id UUID NOT NULL,
    action TEXT NOT NULL,
    actor_kc_sub UUID,
    actor_type TEXT NOT NULL,
    reason_code TEXT,
    reason_text TEXT,
    from_state TEXT,
    to_state TEXT,
    signature_id UUID,
    correlation_id UUID NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT restaurant_audit_log_action_check
        CHECK (action IN ('approve','reject','suspend','reinstate','close','online','offline','submit','resubmit','merchant_suspend_cascade','merchant_reinstate_cascade','merchant_close_cascade')),
    CONSTRAINT restaurant_audit_log_actor_type_check
        CHECK (actor_type IN ('admin','owner','staff','system'))
);

CREATE INDEX IF NOT EXISTS restaurant_audit_log_restaurant_id_idx
    ON restaurant.restaurant_audit_log (restaurant_id, occurred_at DESC);
CREATE INDEX IF NOT EXISTS restaurant_audit_log_actor_idx
    ON restaurant.restaurant_audit_log (actor_kc_sub, occurred_at DESC)
    WHERE actor_kc_sub IS NOT NULL;
CREATE INDEX IF NOT EXISTS restaurant_audit_log_correlation_id_idx
    ON restaurant.restaurant_audit_log (correlation_id);

-- 5) restaurant.idempotency_keys.
CREATE TABLE IF NOT EXISTS restaurant.idempotency_keys (
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
        CHECK (scope IN ('restaurant_create','restaurant_update','restaurant_submit','restaurant_approve','restaurant_reject','restaurant_online','restaurant_offline','restaurant_suspend','restaurant_reinstate','restaurant_close','restaurant_resubmit'))
);

CREATE UNIQUE INDEX IF NOT EXISTS idempotency_keys_scope_key_uniq
    ON restaurant.idempotency_keys (scope, idem_key);

-- 6) restaurant.outbox_events.
CREATE TABLE IF NOT EXISTS restaurant.outbox_events (
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
    created_by UUID NOT NULL
);

CREATE INDEX IF NOT EXISTS outbox_events_pending_idx
    ON restaurant.outbox_events (next_attempt_at)
    WHERE published_at IS NULL;
CREATE INDEX IF NOT EXISTS outbox_events_aggregate_idx
    ON restaurant.outbox_events (aggregate_type, aggregate_id);

-- 7) restaurant.inbox_events.
CREATE TABLE IF NOT EXISTS restaurant.inbox_events (
    id UUID PRIMARY KEY,
    source_topic TEXT NOT NULL,
    source_event_id UUID NOT NULL,
    event_type TEXT NOT NULL,
    payload JSONB NOT NULL,
    correlation_id UUID NOT NULL,
    consumed_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    processed_at TIMESTAMPTZ,
    created_by UUID NOT NULL
);

CREATE UNIQUE INDEX IF NOT EXISTS inbox_events_topic_event_uniq
    ON restaurant.inbox_events (source_topic, source_event_id);