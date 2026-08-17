-- V2__admin_core.sql
-- Per docs/services/admin-service/ERD.md §3:
--   admin.action_log                  : every admin action (append-only, partitioned).
--   admin.break_glass                 : break-glass co-signature records (per-super-admin action).
--   admin.super_admin_grant           : current SUPER_ADMIN preset (1 + 20 members per canonical preset).
--   admin.pricing_geo_config          : pricing-service rule_bindings (admin-side mirror).
--   admin.pricing_geo_config_history  : append-only history per rollback.
--   admin.idempotency_keys             : Idempotency-Key middleware.
--   admin.outbox_events               : transactional outbox for kafka publication.
--   admin.inbox_events                : idempotent inbox for kafka consumption.
--
-- Schema-wide conventions (per the prior 9 graduates):
--   * primary keys are UUIDv7; v7 ordering enforced via kotlin.uuid.Uuid.generateV7().toJavaUuid().
--   * cross-service references (subject_id, actor_id, correlation_id) are plain UUIDs
--     WITHOUT database FKs (DATA--003).
--   * soft delete via deleted_at (DATA--006) where applicable.
--   * audit columns (created_at, updated_at, created_by, updated_by) on every mutable table.
--   * row_version (BIGINT) is the optimistic-lock counter (SRS §14).

-- 1) admin.action_log : append-only audit (time-partitioned by occurred_at).
CREATE TABLE IF NOT EXISTS admin.action_log (
    id UUID NOT NULL,
    action_type TEXT NOT NULL,
    actor_kc_sub UUID NOT NULL,
    actor_kind TEXT NOT NULL,
    subject_kind TEXT,
    subject_id UUID,
    payload JSONB,
    reason TEXT,
    signature_id UUID,
    correlation_id UUID NOT NULL,
    break_glass_id UUID,
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (id, occurred_at),
    CONSTRAINT action_log_action_type_check
        CHECK (length(action_type) BETWEEN 1 AND 100),
    CONSTRAINT action_log_actor_kind_check
        CHECK (actor_kind IN ('admin','owner','staff','system','model')),
    CONSTRAINT action_log_subject_kind_check
        CHECK (subject_kind IS NULL OR subject_kind IN ('customer','driver','courier','merchant','restaurant','config','pricing','identity','document'))
) PARTITION BY RANGE (occurred_at);

CREATE INDEX IF NOT EXISTS action_log_actor_kc_sub_idx
    ON admin.action_log (actor_kc_sub, occurred_at DESC);
CREATE INDEX IF NOT EXISTS action_log_correlation_id_idx
    ON admin.action_log (correlation_id);
CREATE INDEX IF NOT EXISTS action_log_action_type_idx
    ON admin.action_log (action_type, occurred_at DESC);

-- 2) admin.break_glass : per-super-admin-action co-signature record.
CREATE TABLE IF NOT EXISTS admin.break_glass (
    id UUID PRIMARY KEY,
    action_log_id UUID NOT NULL,
    cosigner_kc_sub UUID NOT NULL,
    cosigner_email TEXT,
    reason TEXT NOT NULL,
    signature TEXT NOT NULL,
    correlation_id UUID NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at TIMESTAMPTZ NOT NULL,
    revoked_at TIMESTAMPTZ,
    revoked_by UUID,
    row_version BIGINT NOT NULL DEFAULT 1,
    created_by UUID NOT NULL,
    CONSTRAINT break_glass_reason_length_check
        CHECK (length(reason) >= 8)
);
CREATE INDEX IF NOT EXISTS break_glass_action_log_id_idx
    ON admin.break_glass (action_log_id);
CREATE INDEX IF NOT EXISTS break_glass_cosigner_kc_sub_idx
    ON admin.break_glass (cosigner_kc_sub);
CREATE INDEX IF NOT EXISTS break_glass_expires_at_idx
    ON admin.break_glass (expires_at);

-- 3) admin.super_admin_grant : current SUPER_ADMIN preset (1 + 20 members per canonical preset).
CREATE TABLE IF NOT EXISTS admin.super_admin_grant (
    id UUID PRIMARY KEY,
    grantee_kc_sub UUID NOT NULL,
    grantee_email TEXT,
    granted_by_kc_sub UUID NOT NULL,
    granted_by_email TEXT,
    reason TEXT NOT NULL,
    alias_kind TEXT NOT NULL DEFAULT 'permanent',
    alias_expires_at TIMESTAMPTZ,
    revoked_at TIMESTAMPTZ,
    revoked_by_kc_sub UUID,
    correlation_id UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    row_version BIGINT NOT NULL DEFAULT 1,
    created_by UUID NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by UUID NOT NULL,
    CONSTRAINT super_admin_grant_alias_kind_check
        CHECK (alias_kind IN ('permanent','time_bounded'))
);

CREATE INDEX IF NOT EXISTS super_admin_grant_grantee_kc_sub_idx
    ON admin.super_admin_grant (grantee_kc_sub)
    WHERE revoked_at IS NULL;
CREATE INDEX IF NOT EXISTS super_admin_grant_alias_expires_at_idx
    ON admin.super_admin_grant (alias_expires_at)
    WHERE revoked_at IS NULL AND alias_expires_at IS NOT NULL;

-- 4) admin.pricing_geo_config : pricing-service rule_bindings mirror.
CREATE TABLE IF NOT EXISTS admin.pricing_geo_config (
    id UUID PRIMARY KEY,
    tenant_id TEXT NOT NULL DEFAULT 'global',
    city_id TEXT,
    origin_zone_id UUID,
    destination_zone_id UUID,
    ride_type TEXT,
    rule_kind TEXT NOT NULL,
    value JSONB NOT NULL,
    priority INT NOT NULL DEFAULT 100,
    effective_from TIMESTAMPTZ,
    effective_to TIMESTAMPTZ,
    created_by_kc_sub UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by_kc_sub UUID,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    row_version BIGINT NOT NULL DEFAULT 1,
    CONSTRAINT pricing_geo_config_rule_kind_check
        CHECK (rule_kind IN ('base_fare_override','per_km_override','per_min_override','surge_pressure','loyalty_discount','min_fare_override','od_corridor'))
);

CREATE INDEX IF NOT EXISTS pricing_geo_config_tenant_idx
    ON admin.pricing_geo_config (tenant_id, priority);

-- 5) admin.pricing_geo_config_history : append-only history.
CREATE TABLE IF NOT EXISTS admin.pricing_geo_config_history (
    id UUID PRIMARY KEY,
    config_id UUID NOT NULL,
    version INT NOT NULL,
    action TEXT NOT NULL,
    actor_kc_sub UUID NOT NULL,
    actor_email TEXT,
    payload JSONB NOT NULL,
    reason TEXT,
    correlation_id UUID NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT pricing_geo_config_history_action_check
        CHECK (action IN ('create','update','disable','rollback'))
);
CREATE INDEX IF NOT EXISTS pricing_geo_config_history_config_id_idx
    ON admin.pricing_geo_config_history (config_id, occurred_at DESC);

-- 6) admin.idempotency_keys (newer PK-on-key pattern, like pricing-service).
CREATE TABLE IF NOT EXISTS admin.idempotency_keys (
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
        CHECK (scope IN ('admin_action','admin_preset','super_admin_grant','super_admin_revoke','break_glass_cosign','geo_config_upsert','geo_config_rollback'))
);
CREATE UNIQUE INDEX IF NOT EXISTS idempotency_keys_scope_key_uniq
    ON admin.idempotency_keys (scope, idem_key);

-- 7) admin.outbox_events.
CREATE TABLE IF NOT EXISTS admin.outbox_events (
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
    ON admin.outbox_events (next_attempt_at)
    WHERE published_at IS NULL;
CREATE INDEX IF NOT EXISTS outbox_events_aggregate_idx
    ON admin.outbox_events (aggregate_type, aggregate_id);

-- 8) admin.inbox_events.
CREATE TABLE IF NOT EXISTS admin.inbox_events (
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
    ON admin.inbox_events (source_topic, source_event_id);

-- Default partition for action_log (covers pre-existing data).
CREATE TABLE IF NOT EXISTS admin.action_log_default PARTITION OF admin.action_log DEFAULT;