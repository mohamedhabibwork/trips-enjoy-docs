-- V2__drivers_and_documents.sql
-- Per docs/services/driver-service/ERD.md §3:
--   driver.drivers                 : the driver aggregate (one row per driver).
--   driver.driver_documents        : KYC documents (license, vehicle_reg, insurance, ...).
--   driver.driver_city_eligibility : per-city eligibility (many-to-many).
--   driver.driver_rating_history   : append-only rating line items.
--   driver.driver_audit_log        : append-only audit chain.
--   driver.idempotency_keys        : Idempotency-Key middleware dedup.
--   driver.outbox_events           : transactional outbox for kafka publishing.
--   driver.inbox_events            : idempotent inbox for kafka consumption.
--
-- Schema-wide conventions (per customer-service V2__customers.sql +
-- payment-service V2__payment_intents_and_gateways.sql):
--   * primary keys are UUIDv7 (UUID in PG, with a UUID PRIMARY KEY
--     constraint; the v7 ordering is enforced by the application via
--     kotlin.uuid.Uuid.generateV7().toJavaUuid()).
--   * cross-service references (identity_id, primary_vehicle_id,
--     kyc_verification_id, background_check_verification_id,
--     document_file_id, city_id) are plain UUIDs WITHOUT database FKs
--     (DATA--003).
--   * soft delete via deleted_at (DATA--006).
--   * audit columns (created_at, updated_at, created_by, updated_by)
--     on every mutable table (DATA--005).
--   * row_version (BIGINT) is the optimistic-lock counter (SRS §14).

-- 1) driver.drivers : the driver aggregate.
CREATE TABLE IF NOT EXISTS driver.drivers (
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
    CONSTRAINT drivers_status_check
        CHECK (status IN ('pending_review','approved','rejected','suspended','inactive','erased')),
    CONSTRAINT drivers_rating_check
        CHECK (rating >= 0 AND rating <= 5)
);

CREATE UNIQUE INDEX IF NOT EXISTS drivers_identity_id_uniq
    ON driver.drivers (identity_id)
    WHERE deleted_at IS NULL;
CREATE INDEX IF NOT EXISTS drivers_status_idx
    ON driver.drivers (status)
    WHERE status IN ('pending_review','approved','suspended');
CREATE INDEX IF NOT EXISTS drivers_primary_vehicle_id_idx
    ON driver.drivers (primary_vehicle_id)
    WHERE primary_vehicle_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS drivers_documents_warn_idx
    ON driver.drivers (documents_warn)
    WHERE documents_warn = true;

-- 2) driver.driver_documents : KYC documents.
CREATE TABLE IF NOT EXISTS driver.driver_documents (
    id UUID PRIMARY KEY,
    driver_id UUID NOT NULL,
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
    CONSTRAINT driver_documents_type_check
        CHECK (type IN ('license','vehicle_reg','insurance','selfie','background_check','medical','permit')),
    CONSTRAINT driver_documents_status_check
        CHECK (status IN ('pending','verified','rejected','expired'))
);

CREATE INDEX IF NOT EXISTS driver_documents_driver_id_idx
    ON driver.driver_documents (driver_id)
    WHERE deleted_at IS NULL;
CREATE INDEX IF NOT EXISTS driver_documents_expiry_date_idx
    ON driver.driver_documents (expiry_date)
    WHERE deleted_at IS NULL AND expiry_date IS NOT NULL;
CREATE INDEX IF NOT EXISTS driver_documents_status_idx
    ON driver.driver_documents (status)
    WHERE status = 'verified' AND deleted_at IS NULL;

-- 3) driver.driver_city_eligibility : per-city eligibility.
CREATE TABLE IF NOT EXISTS driver.driver_city_eligibility (
    id UUID PRIMARY KEY,
    driver_id UUID NOT NULL,
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

CREATE UNIQUE INDEX IF NOT EXISTS driver_city_eligibility_driver_city_uniq
    ON driver.driver_city_eligibility (driver_id, city_id)
    WHERE revoked_at IS NULL;
CREATE INDEX IF NOT EXISTS driver_city_eligibility_driver_id_idx
    ON driver.driver_city_eligibility (driver_id)
    WHERE revoked_at IS NULL;

-- 4) driver.driver_rating_history : append-only rating line items.
CREATE TABLE IF NOT EXISTS driver.driver_rating_history (
    id UUID PRIMARY KEY,
    driver_id UUID NOT NULL,
    request_id UUID NOT NULL,
    service TEXT NOT NULL,
    rating SMALLINT NOT NULL,
    comment TEXT,
    rated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    correlation_id UUID NOT NULL,
    created_by UUID NOT NULL,
    CONSTRAINT driver_rating_history_rating_check
        CHECK (rating >= 1 AND rating <= 5)
);

CREATE INDEX IF NOT EXISTS driver_rating_history_driver_id_idx
    ON driver.driver_rating_history (driver_id, rated_at DESC);
CREATE INDEX IF NOT EXISTS driver_rating_history_request_id_idx
    ON driver.driver_rating_history (request_id);

-- 5) driver.driver_audit_log : append-only audit chain.
CREATE TABLE IF NOT EXISTS driver.driver_audit_log (
    id UUID PRIMARY KEY,
    driver_id UUID NOT NULL,
    action TEXT NOT NULL,
    before JSONB,
    after JSONB,
    actor_id UUID NOT NULL,
    actor_email TEXT,
    reason TEXT,
    correlation_id UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT driver_audit_log_action_check
        CHECK (action IN ('created','approved','rejected','suspended','reinstated','disabled','erased','document_added','document_verified','document_rejected','document_expired','city_granted','city_revoked','rating_added','primary_vehicle_changed','profile_updated'))
);

CREATE INDEX IF NOT EXISTS driver_audit_log_driver_id_idx
    ON driver.driver_audit_log (driver_id, created_at DESC);
CREATE INDEX IF NOT EXISTS driver_audit_log_correlation_id_idx
    ON driver.driver_audit_log (correlation_id);

-- 6) driver.idempotency_keys : Idempotency-Key middleware.
CREATE TABLE IF NOT EXISTS driver.idempotency_keys (
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
        CHECK (scope IN ('driver_create','driver_update','driver_approve','driver_reject','driver_suspend','driver_reinstate','driver_disable','driver_erase','document_add','document_delete','eligibility_grant','eligibility_revoke'))
);

CREATE UNIQUE INDEX IF NOT EXISTS idempotency_keys_scope_key_uniq
    ON driver.idempotency_keys (scope, idem_key);

-- 7) driver.outbox_events : transactional outbox.
CREATE TABLE IF NOT EXISTS driver.outbox_events (
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
    ON driver.outbox_events (next_attempt_at)
    WHERE published_at IS NULL;
CREATE INDEX IF NOT EXISTS outbox_events_aggregate_idx
    ON driver.outbox_events (aggregate_type, aggregate_id);

-- 8) driver.inbox_events : idempotent inbox.
CREATE TABLE IF NOT EXISTS driver.inbox_events (
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
    ON driver.inbox_events (source_topic, source_event_id);