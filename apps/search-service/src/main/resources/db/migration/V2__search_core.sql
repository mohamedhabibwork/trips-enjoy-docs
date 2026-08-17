-- V2__search_core.sql
-- Per docs/services/search-service/ERD.md §3:
--   search.reindex_job          : a reindex job (cursor for backfilling OpenSearch).
--   search.query_log            : append-only audit of executed search queries.
--   search.relevance_config      : per-vertical relevance tuning knobs.
--   search.index_health         : point-in-time snapshot of OpenSearch cluster health.
--   search.outbox               : transactional outbox for kafka publication.
--   search.inbox                : idempotent inbox for kafka consumption.
--
-- Schema-wide conventions (per the prior 9 graduates):
--   * primary keys are UUIDv7 (single UUID, NOT composite @IdClass per
--     the lift-forward pattern from food-order-service + the admin-service
--     EntityManager fix).
--   * cross-service references (tenant_id, subject_id) are plain UUIDs
--     WITHOUT database FKs (DATA--003).
--   * soft delete via deleted_at where applicable.
--   * audit columns (created_at, updated_at, created_by, updated_by).
--   * row_version (BIGINT) is the optimistic-lock counter.

-- 1) search.reindex_job : a reindex job (cursor for backfilling OpenSearch).
CREATE TABLE IF NOT EXISTS search.reindex_job (
    id UUID PRIMARY KEY,
    tenant_id TEXT NOT NULL DEFAULT 'global',
    vertical TEXT NOT NULL,
    scope TEXT NOT NULL DEFAULT 'all',
    state TEXT NOT NULL DEFAULT 'pending',
    total_docs BIGINT NOT NULL DEFAULT 0,
    processed_docs BIGINT NOT NULL DEFAULT 0,
    failed_docs BIGINT NOT NULL DEFAULT 0,
    started_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    error_message TEXT,
    requested_by UUID NOT NULL,
    correlation_id UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    row_version BIGINT NOT NULL DEFAULT 1,
    created_by UUID NOT NULL, updated_by UUID NOT NULL,
    CONSTRAINT reindex_job_vertical_check
        CHECK (vertical IN ('restaurants','menu_items','merchants','tickets','all')),
    CONSTRAINT reindex_job_state_check
        CHECK (state IN ('pending','running','completed','failed','cancelled'))
);
CREATE INDEX IF NOT EXISTS reindex_job_tenant_id_idx
    ON search.reindex_job (tenant_id, created_at DESC);
CREATE INDEX IF NOT EXISTS reindex_job_state_idx
    ON search.reindex_job (state)
    WHERE state IN ('pending','running');

-- 2) search.query_log : append-only audit of executed search queries.
CREATE TABLE IF NOT EXISTS search.query_log (
    id UUID PRIMARY KEY,
    tenant_id TEXT NOT NULL DEFAULT 'global',
    vertical TEXT NOT NULL,
    query_text TEXT NOT NULL,
    filters JSONB,
    result_count INT NOT NULL DEFAULT 0,
    duration_ms INT NOT NULL DEFAULT 0,
    actor_kc_sub UUID,
    actor_kind TEXT NOT NULL DEFAULT 'rider',
    correlation_id UUID NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT query_log_vertical_check
        CHECK (vertical IN ('restaurants','menu_items','merchants','tickets')),
    CONSTRAINT query_log_actor_kind_check
        CHECK (actor_kind IN ('rider','driver','admin','system','merchant'))
);
CREATE INDEX IF NOT EXISTS query_log_tenant_id_idx
    ON search.query_log (tenant_id, occurred_at DESC);
CREATE INDEX IF NOT EXISTS query_log_vertical_idx
    ON search.query_log (vertical, occurred_at DESC);

-- 3) search.relevance_config : per-vertical relevance tuning knobs.
CREATE TABLE IF NOT EXISTS search.relevance_config (
    id UUID PRIMARY KEY,
    tenant_id TEXT NOT NULL DEFAULT 'global',
    vertical TEXT NOT NULL,
    field TEXT NOT NULL,
    boost DOUBLE PRECISION NOT NULL DEFAULT 1.0,
    decay_days INT,
    enabled BOOLEAN NOT NULL DEFAULT true,
    updated_by_kc_sub UUID NOT NULL,
    correlation_id UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    row_version BIGINT NOT NULL DEFAULT 1,
    created_by UUID NOT NULL, updated_by UUID NOT NULL,
    CONSTRAINT relevance_config_vertical_check
        CHECK (vertical IN ('restaurants','menu_items','merchants','tickets')),
    CONSTRAINT relevance_config_field_length_check
        CHECK (length(field) BETWEEN 1 AND 100)
);
CREATE UNIQUE INDEX IF NOT EXISTS relevance_config_tenant_field_uniq
    ON search.relevance_config (tenant_id, vertical, field);

-- 4) search.index_health : point-in-time snapshot of OpenSearch cluster health.
CREATE TABLE IF NOT EXISTS search.index_health (
    id UUID PRIMARY KEY,
    cluster_name TEXT NOT NULL,
    status TEXT NOT NULL,
    node_count INT NOT NULL DEFAULT 0,
    active_shards INT NOT NULL DEFAULT 0,
    unassigned_shards INT NOT NULL DEFAULT 0,
    correlation_id UUID NOT NULL,
    recorded_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT index_health_status_check
        CHECK (status IN ('green','yellow','red','unknown'))
);
CREATE INDEX IF NOT EXISTS index_health_cluster_name_idx
    ON search.index_health (cluster_name, recorded_at DESC);

-- 5) search.outbox : transactional outbox for kafka publication.
CREATE TABLE IF NOT EXISTS search.outbox (
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
    created_by UUID NOT NULL, updated_by UUID NOT NULL
);
CREATE INDEX IF NOT EXISTS outbox_pending_idx ON search.outbox (next_attempt_at)
    WHERE published_at IS NULL;
CREATE INDEX IF NOT EXISTS outbox_aggregate_idx ON search.outbox (aggregate_type, aggregate_id);

-- 6) search.inbox : idempotent inbox for kafka consumption.
CREATE TABLE IF NOT EXISTS search.inbox (
    id UUID PRIMARY KEY,
    source_topic TEXT NOT NULL,
    source_event_id UUID NOT NULL,
    event_type TEXT NOT NULL,
    payload JSONB NOT NULL,
    correlation_id UUID NOT NULL,
    consumed_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    processed_at TIMESTAMPTZ,
    created_by UUID NOT NULL, updated_by UUID NOT NULL
);
CREATE UNIQUE INDEX IF NOT EXISTS inbox_topic_event_uniq
    ON search.inbox (source_topic, source_event_id);

-- 7) search.idempotency_keys : the canonical scope+key Idempotency-Key.
CREATE TABLE IF NOT EXISTS search.idempotency_keys (
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
        CHECK (scope IN ('search_query','reindex_start','relevance_update'))
);
CREATE UNIQUE INDEX IF NOT EXISTS idempotency_keys_scope_key_uniq
    ON search.idempotency_keys (scope, idem_key);