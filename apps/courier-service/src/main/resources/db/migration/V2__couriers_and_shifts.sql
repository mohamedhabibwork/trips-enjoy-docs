-- V2__couriers_and_shifts.sql
-- Per docs/services/courier-service/ERD.md §3:
--   courier.couriers                  : the courier aggregate (one row per courier).
--   courier.courier_documents         : KYC documents (id, license, vehicle, ...).
--   courier.courier_shifts            : scheduled shift blocks (scheduled→active→completed/cancelled).
--   courier.courier_city_eligibility  : per-city eligibility (many-to-many).
--   courier.courier_rating_history    : append-only rating line items.
--   courier.courier_audit_log         : append-only audit chain.
--   courier.idempotency_keys          : Idempotency-Key middleware dedup.
--   courier.outbox_events             : transactional outbox for kafka publishing.
--   courier.inbox_events              : idempotent inbox for kafka consumption.
--
-- Schema-wide conventions (per customer-service + driver-service):
--   * primary keys are UUIDv7 (UUID PRIMARY KEY; v7 ordering enforced by
--     the application via kotlin.uuid.Uuid.generateV7().toJavaUuid()).
--   * cross-service references (identity_id, primary_vehicle_id,
--     kyc_verification_id, background_check_verification_id,
--     document_file_id, city_id) are plain UUIDs WITHOUT database FKs
--     (DATA--003).
--   * soft delete via deleted_at (DATA--006).
--   * audit columns (created_at, updated_at, created_by, updated_by)
--     on every mutable table (DATA--005).
--   * row_version (BIGINT) is the optimistic-lock counter (SRS §14).

-- 1) courier.couriers : the courier aggregate.
CREATE TABLE IF NOT EXISTS courier.couriers (
    id UUID PRIMARY KEY,
    identity_id UUID NOT NULL,
    name TEXT,
    email TEXT,
    phone TEXT,
    primary_vehicle_id UUID,
    kyc_verification_id UUID,
    kyc_verified_at TIMESTAMPTZ,
    background_check_verification_id UUID,
    background_check_verified_at TIMESTAMPTZ,
    rating NUMERIC(3, 2) NOT NULL DEFAULT 0.00,
    rating_count INT NOT NULL DEFAULT 0,
    rating_updated_at TIMESTAMPTZ,
    status TEXT NOT NULL DEFAULT 'pending_review',
    rejected_reason TEXT,
    suspended_reason TEXT,
    suspended_at TIMESTAMPTZ,
    suspended_by UUID,
    disabled_at TIMESTAMPTZ,
    erased_at TIMESTAMPTZ,
    documents_warn BOOLEAN NOT NULL DEFAULT false,
    last_online_at TIMESTAMPTZ,
    row_version BIGINT NOT NULL DEFAULT 1,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by UUID NOT NULL,
    updated_by UUID NOT NULL,
    deleted_at TIMESTAMPTZ,
    CONSTRAINT couriers_status_check
        CHECK (status IN ('pending_review','approved','rejected','suspended','inactive','erased')),
    CONSTRAINT couriers_rating_check
        CHECK (rating >= 0 AND rating <= 5)
);

CREATE UNIQUE INDEX IF NOT EXISTS couriers_identity_id_uniq
    ON courier.couriers (identity_id)
    WHERE deleted_at IS NULL;
CREATE INDEX IF NOT EXISTS couriers_status_idx
    ON courier.couriers (status)
    WHERE status IN ('pending_review','approved','suspended');
CREATE INDEX IF NOT EXISTS couriers_primary_vehicle_id_idx
    ON courier.couriers (primary_vehicle_id)
    WHERE primary_vehicle_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS couriers_documents_warn_idx
    ON courier.couriers (documents_warn)
    WHERE documents_warn = true;

-- 2) courier.courier_documents : KYC documents.
CREATE TABLE IF NOT EXISTS courier.courier_documents (
    id UUID PRIMARY KEY,
    courier_id UUID NOT NULL,
    type TEXT NOT NULL,
    file_id UUID NOT NULL,
    verification_id UUID,
    verified_at TIMESTAMPTZ,
    expiry_date TIMESTAMPTZ,
    critical BOOLEAN NOT NULL DEFAULT true,
    status TEXT NOT NULL DEFAULT 'pending',
    rejected_reason TEXT,
    row_version BIGINT NOT NULL DEFAULT 1,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by UUID NOT NULL,
    updated_by UUID NOT NULL,
    deleted_at TIMESTAMPTZ,
    CONSTRAINT courier_documents_type_check
        CHECK (type IN ('id','license','vehicle_reg','insurance','selfie','background_check','medical','permit')),
    CONSTRAINT courier_documents_status_check
        CHECK (status IN ('pending','verified','rejected','expired'))
);

CREATE INDEX IF NOT EXISTS courier_documents_courier_id_idx
    ON courier.courier_documents (courier_id)
    WHERE deleted_at IS NULL;
CREATE INDEX IF NOT EXISTS courier_documents_expiry_date_idx
    ON courier.courier_documents (expiry_date)
    WHERE deleted_at IS NULL AND expiry_date IS NOT NULL;
CREATE INDEX IF NOT EXISTS courier_documents_status_idx
    ON courier.courier_documents (status)
    WHERE status = 'verified' AND deleted_at IS NULL;

-- 3) courier.courier_shifts : scheduled shift blocks.
-- The new sub-aggregate vs driver-service: a courier has many scheduled
-- shifts; the active shift is the one whose status='active'. The dispatch
-- saga binds an inbound delivery offer to a courier's active shift.
CREATE TABLE IF NOT EXISTS courier.courier_shifts (
    id UUID PRIMARY KEY,
    courier_id UUID NOT NULL,
    start_at TIMESTAMPTZ NOT NULL,
    end_at TIMESTAMPTZ NOT NULL,
    actual_start_at TIMESTAMPTZ,
    actual_end_at TIMESTAMPTZ,
    status TEXT NOT NULL DEFAULT 'scheduled',
    cancelled_reason TEXT,
    row_version BIGINT NOT NULL DEFAULT 1,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by UUID NOT NULL,
    updated_by UUID NOT NULL,
    deleted_at TIMESTAMPTZ,
    CONSTRAINT courier_shifts_status_check
        CHECK (status IN ('scheduled','active','completed','cancelled')),
    CONSTRAINT courier_shifts_end_after_start_check
        CHECK (end_at > start_at)
);

CREATE INDEX IF NOT EXISTS courier_shifts_courier_id_idx
    ON courier.courier_shifts (courier_id)
    WHERE deleted_at IS NULL;
CREATE INDEX IF NOT EXISTS courier_shifts_start_at_idx
    ON courier.courier_shifts (start_at);
CREATE INDEX IF NOT EXISTS courier_shifts_status_idx
    ON courier.courier_shifts (status)
    WHERE status = 'active';
CREATE INDEX IF NOT EXISTS courier_shifts_courier_active_uniq
    ON courier.courier_shifts (courier_id)
    WHERE status = 'active' AND deleted_at IS NULL;

-- 4) courier.courier_city_eligibility : per-city eligibility.
CREATE TABLE IF NOT EXISTS courier.courier_city_eligibility (
    id UUID PRIMARY KEY,
    courier_id UUID NOT NULL,
    city_id UUID NOT NULL,
    granted_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    revoked_at TIMESTAMPTZ,
    granted_by UUID,
    revoked_by UUID,
    notes TEXT,
    row_version BIGINT NOT NULL DEFAULT 1,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by UUID NOT NULL,
    updated_by UUID NOT NULL
);

CREATE UNIQUE INDEX IF NOT EXISTS courier_city_eligibility_courier_city_uniq
    ON courier.courier_city_eligibility (courier_id, city_id)
    WHERE revoked_at IS NULL;
CREATE INDEX IF NOT EXISTS courier_city_eligibility_courier_id_idx
    ON courier.courier_city_eligibility (courier_id)
    WHERE revoked_at IS NULL;

-- 5) courier.courier_rating_history : append-only rating line items.
CREATE TABLE IF NOT EXISTS courier.courier_rating_history (
    id UUID PRIMARY KEY,
    courier_id UUID NOT NULL,
    request_id UUID NOT NULL,
    service TEXT NOT NULL,
    rating SMALLINT NOT NULL,
    comment TEXT,
    rated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    correlation_id UUID NOT NULL,
    created_by UUID NOT NULL,
    CONSTRAINT courier_rating_history_rating_check
        CHECK (rating >= 1 AND rating <= 5)
);

CREATE INDEX IF NOT EXISTS courier_rating_history_courier_id_idx
    ON courier.courier_rating_history (courier_id, rated_at DESC);
CREATE INDEX IF NOT EXISTS courier_rating_history_request_id_idx
    ON courier.courier_rating_history (request_id);

-- 6) courier.courier_audit_log : append-only audit chain.
CREATE TABLE IF NOT EXISTS courier.courier_audit_log (
    id UUID PRIMARY KEY,
    courier_id UUID NOT NULL,
    action TEXT NOT NULL,
    before JSONB,
    after JSONB,
    actor_id UUID NOT NULL,
    actor_email TEXT,
    reason TEXT,
    correlation_id UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT courier_audit_log_action_check
        CHECK (action IN ('created','approved','rejected','suspended','reinstated','disabled','erased','document_added','document_verified','document_rejected','document_expired','city_granted','city_revoked','rating_added','primary_vehicle_changed','profile_updated','shift_scheduled','shift_activated','shift_completed','shift_cancelled'))
);

CREATE INDEX IF NOT EXISTS courier_audit_log_courier_id_idx
    ON courier.courier_audit_log (courier_id, created_at DESC);
CREATE INDEX IF NOT EXISTS courier_audit_log_correlation_id_idx
    ON courier.courier_audit_log (correlation_id);

-- 7) courier.idempotency_keys : Idempotency-Key middleware.
CREATE TABLE IF NOT EXISTS courier.idempotency_keys (
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
        CHECK (scope IN ('courier_create','courier_update','courier_approve','courier_reject','courier_suspend','courier_reinstate','courier_disable','courier_erase','document_add','document_delete','eligibility_grant','eligibility_revoke','shift_schedule','shift_activate','shift_complete','shift_cancel'))
);

CREATE UNIQUE INDEX IF NOT EXISTS idempotency_keys_scope_key_uniq
    ON courier.idempotency_keys (scope, idem_key);

-- 8) courier.outbox_events : transactional outbox.
CREATE TABLE IF NOT EXISTS courier.outbox_events (
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
    ON courier.outbox_events (next_attempt_at)
    WHERE published_at IS NULL;
CREATE INDEX IF NOT EXISTS outbox_events_aggregate_idx
    ON courier.outbox_events (aggregate_type, aggregate_id);

-- 9) courier.inbox_events : idempotent inbox.
CREATE TABLE IF NOT EXISTS courier.inbox_events (
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
    ON courier.inbox_events (source_topic, source_event_id);