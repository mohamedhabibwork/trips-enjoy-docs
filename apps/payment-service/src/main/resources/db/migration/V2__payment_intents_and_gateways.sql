-- V2__payment_intents_and_gateways.sql
-- Per docs/services/payment-service/ERD.md §3:
--   payment.payment_gateways  : the 46-gateway registry (one row per gateway).
--   payment.payment_intents   : the payment intent aggregate (one row per intent).
--   payment.payment_attempts  : per-attempt audit log (auth/capture/void/refund).
--   payment.payment_gateway_assignments : per-intent gateway-source audit.
--   payment.idempotency_keys   : Idempotency-Key dedup table (per ADR-0019).
--   payment.outbox_events      : transactional outbox for kafka publishing.
--   payment.inbox_events       : idempotent inbox for kafka consumption.
--
-- Schema-wide conventions (per customer-service V2__customers.sql):
--   * primary keys are UUIDv7 (UUID in PG, with a UUID PRIMARY KEY
--     constraint; the v7 ordering is enforced by the application via
--     kotlin.uuid.Uuid.generateV7().toJavaUuid()).
--   * cross-service references (customer_id, driver_id, courier_id,
--     merchant_id, request_id, wallet_id, gateway_id) are plain UUIDs / TEXT
--     WITHOUT database FKs (DATA--003).
--   * soft delete via deleted_at (DATA--006).
--   * audit columns (created_at, updated_at, created_by, updated_by)
--     on every mutable table (DATA--005).
--   * row_version (BIGINT) is the optimistic-lock counter (SRS §14).

-- 1) payment.payment_gateways : registry of the 46 supported drivers.
-- Mirrors file-service's storage_drivers table (see
-- docs/services/file-service/ERD.md §3 "StorageDriver"). The 46 initial
-- rows are seeded by V7 (V8 in customer-service / V8 in payment-service)
-- from configuration-service.payment.gateway.<id>.* family.
CREATE TABLE IF NOT EXISTS payment.payment_gateways (
    id TEXT PRIMARY KEY,
    kind TEXT NOT NULL,
    display_name TEXT NOT NULL,
    state TEXT NOT NULL DEFAULT 'enabled',
    priority INT NOT NULL DEFAULT 100,
    regions TEXT[] NOT NULL DEFAULT '{}',
    supported_currencies TEXT[] NOT NULL DEFAULT '{}',
    supported_methods TEXT[] NOT NULL DEFAULT '{}',
    signature_scheme TEXT NOT NULL,
    verify_style TEXT NOT NULL,
    vault_path TEXT NOT NULL,
    health_url TEXT,
    health TEXT NOT NULL DEFAULT 'healthy',
    health_last_checked_at TIMESTAMPTZ,
    is_default BOOLEAN NOT NULL DEFAULT false,
    config_hash TEXT NOT NULL,
    metadata JSONB,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by UUID NOT NULL,
    updated_by UUID NOT NULL,
    version INT NOT NULL DEFAULT 1,
    deleted_at TIMESTAMPTZ,
    CONSTRAINT payment_gateways_kind_check
        CHECK (kind IN ('card','mena_wallet','mena_aggregator','crypto','e_currency','direct_card_3ds','payout','latam','apac','local_apm')),
    CONSTRAINT payment_gateways_state_check
        CHECK (state IN ('enabled','draining','disabled')),
    CONSTRAINT payment_gateways_health_check
        CHECK (health IN ('healthy','degraded','unreachable')),
    CONSTRAINT payment_gateways_signature_scheme_check
        CHECK (signature_scheme IN ('hmac_sha256','hmac_sha512','rsa_sha256','md5','sha256','paypal_sdk','paymob_hmac','kashier_hmac','none')),
    CONSTRAINT payment_gateways_verify_style_check
        CHECK (verify_style IN ('get_redirect','webhook_post','signed_webhook','cache_lookup','iframe_postback')),
    CONSTRAINT payment_gateways_version_check
        CHECK (version > 0)
);

CREATE INDEX IF NOT EXISTS payment_gateways_kind_idx ON payment.payment_gateways (kind);
CREATE INDEX IF NOT EXISTS payment_gateways_state_idx ON payment.payment_gateways (state)
    WHERE state <> 'disabled';
CREATE INDEX IF NOT EXISTS payment_gateways_priority_idx ON payment.payment_gateways (priority);
CREATE UNIQUE INDEX IF NOT EXISTS payment_gateways_default_uniq ON payment.payment_gateways (is_default)
    WHERE is_default;

-- 2) payment.payment_intents : the payment intent aggregate (one row per intent).
-- Per docs/services/payment-service/INTEGRATION.md §1.1 the intent is the
-- canonical state machine: created -> authorized -> captured / voided.
-- Cross-service references (customer_id, request_id, merchant_id, driver_id,
-- courier_id) are plain UUIDs without FKs.
CREATE TABLE IF NOT EXISTS payment.payment_intents (
    id UUID PRIMARY KEY,
    customer_id UUID NOT NULL,
    request_id UUID NOT NULL,
    service TEXT NOT NULL,
    amount_minor BIGINT NOT NULL,
    currency VARCHAR(3) NOT NULL,
    gateway_id TEXT NOT NULL,
    gateway_region TEXT NOT NULL,
    gateway_intent_id TEXT,
    gateway_token TEXT,
    capture_mode TEXT NOT NULL DEFAULT 'manual',
    state TEXT NOT NULL DEFAULT 'created',
    city_id UUID,
    description TEXT,
    metadata JSONB,
    correlation_id UUID NOT NULL,
    authorized_at TIMESTAMPTZ,
    captured_at TIMESTAMPTZ,
    voided_at TIMESTAMPTZ,
    captured_minor BIGINT,
    refunded_minor BIGINT NOT NULL DEFAULT 0,
    failure_code TEXT,
    failure_message TEXT,
    wallet_id UUID,
    row_version BIGINT NOT NULL DEFAULT 1,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by UUID NOT NULL,
    updated_by UUID NOT NULL,
    deleted_at TIMESTAMPTZ,
    CONSTRAINT payment_intents_service_check
        CHECK (service IN ('trip','food_order','courier_delivery','wallet_topup','manual')),
    CONSTRAINT payment_intents_capture_mode_check
        CHECK (capture_mode IN ('manual','auto')),
    CONSTRAINT payment_intents_state_check
        CHECK (state IN ('created','authorized','captured','voided','failed','refunded','partially_refunded')),
    CONSTRAINT payment_intents_amount_minor_check
        CHECK (amount_minor > 0),
    CONSTRAINT payment_intents_captured_minor_check
        CHECK (captured_minor IS NULL OR captured_minor >= 0),
    CONSTRAINT payment_intents_refunded_minor_check
        CHECK (refunded_minor >= 0)
);

CREATE INDEX IF NOT EXISTS payment_intents_customer_id_idx ON payment.payment_intents (customer_id)
    WHERE deleted_at IS NULL;
CREATE INDEX IF NOT EXISTS payment_intents_request_id_idx ON payment.payment_intents (request_id, service)
    WHERE deleted_at IS NULL;
CREATE INDEX IF NOT EXISTS payment_intents_gateway_id_idx ON payment.payment_intents (gateway_id)
    WHERE deleted_at IS NULL;
CREATE INDEX IF NOT EXISTS payment_intents_state_idx ON payment.payment_intents (state)
    WHERE state NOT IN ('failed','voided');
CREATE INDEX IF NOT EXISTS payment_intents_correlation_id_idx ON payment.payment_intents (correlation_id);

-- 3) payment.payment_attempts : per-attempt audit log (auth / capture / void / refund).
-- Append-only by trigger (raised via V5 partition_functions). Drives the
-- GatewayHealth + PaymentRetryDashboard.
CREATE TABLE IF NOT EXISTS payment.payment_attempts (
    id UUID PRIMARY KEY,
    payment_intent_id UUID NOT NULL,
    operation TEXT NOT NULL,
    gateway_id TEXT NOT NULL,
    gateway_attempt_id TEXT,
    state TEXT NOT NULL,
    amount_minor BIGINT,
    request_payload JSONB,
    response_payload JSONB,
    error_code TEXT,
    error_message TEXT,
    started_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    finished_at TIMESTAMPTZ,
    latency_ms INT,
    correlation_id UUID NOT NULL,
    created_by UUID NOT NULL,
    CONSTRAINT payment_attempts_operation_check
        CHECK (operation IN ('authorize','capture','void','refund','verify')),
    CONSTRAINT payment_attempts_state_check
        CHECK (state IN ('started','succeeded','failed','timed_out'))
);

CREATE INDEX IF NOT EXISTS payment_attempts_intent_id_idx
    ON payment.payment_attempts (payment_intent_id, started_at DESC);
CREATE INDEX IF NOT EXISTS payment_attempts_gateway_id_idx
    ON payment.payment_attempts (gateway_id, started_at DESC)
    WHERE state = 'failed';

-- 4) payment.payment_gateway_assignments : per-intent gateway-source audit.
-- Records which rule fired when the registry resolved a gateway for a
-- payment intent (gateway_pin / tenant_override / region_default / ...).
CREATE TABLE IF NOT EXISTS payment.payment_gateway_assignments (
    id UUID PRIMARY KEY,
    payment_intent_id UUID NOT NULL,
    gateway_id TEXT NOT NULL,
    source TEXT NOT NULL,
    rule_id TEXT,
    effective_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by UUID NOT NULL,
    CONSTRAINT payment_gateway_assignments_source_check
        CHECK (source IN ('gateway_pin','tenant_override','region_default','currency_default','method_default','env_default','auto'))
);

CREATE INDEX IF NOT EXISTS payment_gateway_assignments_intent_idx
    ON payment.payment_gateway_assignments (payment_intent_id, effective_at DESC);
CREATE INDEX IF NOT EXISTS payment_gateway_assignments_gateway_idx
    ON payment.payment_gateway_assignments (gateway_id);
CREATE INDEX IF NOT EXISTS payment_gateway_assignments_source_idx
    ON payment.payment_gateway_assignments (source);

-- 5) payment.idempotency_keys : per INTEGRATION.md §1 the Idempotency-Key
-- middleware writes one row per mutating route. The unique index on
-- (scope, key) is the canonical dedup primitive.
CREATE TABLE IF NOT EXISTS payment.idempotency_keys (
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
        CHECK (scope IN ('payment_intent','payment_capture','payment_void','payment_refund','wallet_topup','wallet_debit'))
);

CREATE UNIQUE INDEX IF NOT EXISTS idempotency_keys_scope_key_uniq
    ON payment.idempotency_keys (scope, idem_key);

-- 6) payment.outbox_events : transactional outbox for kafka publishing.
-- Polled by OutboxPublisher (200ms interval) per the canonical pattern.
CREATE TABLE IF NOT EXISTS payment.outbox_events (
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
    ON payment.outbox_events (next_attempt_at)
    WHERE published_at IS NULL;
CREATE INDEX IF NOT EXISTS outbox_events_aggregate_idx
    ON payment.outbox_events (aggregate_type, aggregate_id);

-- 7) payment.inbox_events : idempotent inbox for kafka consumption.
-- Per the canonical pattern, dedup window is 7 days.
CREATE TABLE IF NOT EXISTS payment.inbox_events (
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
    ON payment.inbox_events (source_topic, source_event_id);