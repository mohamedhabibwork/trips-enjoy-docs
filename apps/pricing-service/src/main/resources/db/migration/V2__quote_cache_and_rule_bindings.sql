-- V2__quote_cache_and_rule_bindings.sql
-- Per docs/services/pricing-service/ERD.md §3:
--   pricing.quote_cache             : short-lived quote cache (active/consumed/expired).
--   pricing.idempotency             : Idempotency-Key dedupe (PK = idempotency_key, NOT scope+key).
--   pricing.outbox_events           : transactional outbox for kafka publication.
--   pricing.inbox_events            : idempotent inbox for kafka consumption.
--   pricing.surge_cache             : last-known surge multiplier per zone.
--   pricing.rating_density_cache    : aggregated driver-rating-per-zone signal.
--   pricing.loyalty_frequent_cache   : aggregated frequent-zone loyalty signal.
--   pricing.rule_bindings           : immutable append-only geo-config rule table.
--   pricing.geo_overrides           : alias projection of od_corridor rule_bindings.
--   pricing.rule_bindings_history   : append-only audit of every binding version.
--
-- Schema-wide conventions (per customer-service + payment-service +
-- driver-service + courier-service + restaurant-service):
--   * primary keys are UUIDv7 (UUID PRIMARY KEY; v7 ordering enforced
--     by the application via kotlin.uuid.Uuid.generateV7().toJavaUuid()).
--   * cross-service references (customer_id, zone_id) are plain UUIDs
--     WITHOUT database FKs (DATA--003).
--   * audit columns (created_at, updated_at, created_by, updated_by)
--     on every mutable table (DATA--005).

-- 1) pricing.quote_cache : the quote cache.
CREATE TABLE IF NOT EXISTS pricing.quote_cache (
    id UUID PRIMARY KEY,
    customer_id UUID,
    product_type TEXT NOT NULL,
    request JSONB NOT NULL,
    quote JSONB NOT NULL,
    config_snapshot JSONB NOT NULL,
    status TEXT NOT NULL DEFAULT 'active',
    expires_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    consumed_at TIMESTAMPTZ,
    CONSTRAINT quote_cache_product_type_check
        CHECK (product_type IN ('ride','food')),
    CONSTRAINT quote_cache_status_check
        CHECK (status IN ('active','consumed','expired'))
);

CREATE INDEX IF NOT EXISTS quote_cache_customer_id_idx
    ON pricing.quote_cache (customer_id, created_at DESC)
    WHERE customer_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS quote_cache_expires_at_idx
    ON pricing.quote_cache (expires_at)
    WHERE status = 'active';
CREATE INDEX IF NOT EXISTS quote_cache_active_idx
    ON pricing.quote_cache (status)
    WHERE status = 'active';

-- 2) pricing.idempotency : the newer PK-on-key Idempotency pattern
-- (vs the legacy scope+key pattern used by other graduates).
CREATE TABLE IF NOT EXISTS pricing.idempotency (
    idempotency_key UUID PRIMARY KEY,
    request_hash TEXT NOT NULL,
    response_status INT NOT NULL,
    response_body JSONB NOT NULL,
    actor_id UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT idempotency_request_hash_length_check
        CHECK (length(request_hash) = 64)
);

CREATE INDEX IF NOT EXISTS idempotency_expires_at_idx
    ON pricing.idempotency (expires_at);

-- 3) pricing.outbox_events.
CREATE TABLE IF NOT EXISTS pricing.outbox_events (
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
    ON pricing.outbox_events (next_attempt_at)
    WHERE published_at IS NULL;
CREATE INDEX IF NOT EXISTS outbox_events_aggregate_idx
    ON pricing.outbox_events (aggregate_type, aggregate_id);

-- 4) pricing.inbox_events.
CREATE TABLE IF NOT EXISTS pricing.inbox_events (
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
    ON pricing.inbox_events (source_topic, source_event_id);

-- 5) pricing.surge_cache : last-known surge multiplier per zone.
CREATE TABLE IF NOT EXISTS pricing.surge_cache (
    zone_id UUID PRIMARY KEY,
    multiplier NUMERIC(4, 2) NOT NULL,
    version INT NOT NULL DEFAULT 1,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT surge_cache_multiplier_check
        CHECK (multiplier >= 1.0)
);

-- 6) pricing.rating_density_cache : aggregated driver-rating-per-zone.
CREATE TABLE IF NOT EXISTS pricing.rating_density_cache (
    zone_id UUID NOT NULL,
    window_minutes INT NOT NULL,
    avg_rating NUMERIC(3, 2) NOT NULL,
    sample_size INT NOT NULL,
    computed_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (zone_id, window_minutes),
    CONSTRAINT rating_density_cache_avg_rating_check
        CHECK (avg_rating >= 0 AND avg_rating <= 5)
);

-- 7) pricing.loyalty_frequent_cache : composite-key (customer_id, zone_id).
CREATE TABLE IF NOT EXISTS pricing.loyalty_frequent_cache (
    customer_id UUID NOT NULL,
    zone_id UUID NOT NULL,
    trip_count_30d INT NOT NULL,
    tier_at_trip TEXT NOT NULL,
    most_recent_qualifying_at TIMESTAMPTZ NOT NULL,
    computed_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (customer_id, zone_id),
    CONSTRAINT loyalty_frequent_cache_tier_check
        CHECK (tier_at_trip IN ('silver','gold','platinum'))
);

CREATE INDEX IF NOT EXISTS loyalty_frequent_cache_expires_at_idx
    ON pricing.loyalty_frequent_cache (expires_at);

-- 8) pricing.rule_bindings : immutable append-only rule bindings.
CREATE TABLE IF NOT EXISTS pricing.rule_bindings (
    id UUID PRIMARY KEY,
    version INT NOT NULL DEFAULT 1,
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
    created_by UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    superseded_by_id UUID,
    CONSTRAINT rule_bindings_rule_kind_check
        CHECK (rule_kind IN ('base_fare_override','per_km_override','per_min_override','surge_pressure','loyalty_discount','min_fare_override','od_corridor'))
);

CREATE INDEX IF NOT EXISTS rule_bindings_tenant_idx
    ON pricing.rule_bindings (tenant_id, priority)
    WHERE effective_to IS NULL;
CREATE INDEX IF NOT EXISTS rule_bindings_origin_dest_idx
    ON pricing.rule_bindings (origin_zone_id, destination_zone_id)
    WHERE origin_zone_id IS NOT NULL AND destination_zone_id IS NOT NULL;

-- 9) pricing.geo_overrides : alias projection of od_corridor rule_bindings.
CREATE TABLE IF NOT EXISTS pricing.geo_overrides (
    id UUID PRIMARY KEY,
    origin_zone_id UUID NOT NULL,
    destination_zone_id UUID NOT NULL,
    ride_type TEXT NOT NULL DEFAULT '*',
    multiplier_adjustment NUMERIC(5, 4) NOT NULL,
    version INT NOT NULL DEFAULT 1,
    effective_from TIMESTAMPTZ,
    effective_to TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS geo_overrides_origin_dest_idx
    ON pricing.geo_overrides (origin_zone_id, destination_zone_id, ride_type);

-- 10) pricing.rule_bindings_history : append-only audit.
CREATE TABLE IF NOT EXISTS pricing.rule_bindings_history (
    id UUID PRIMARY KEY,
    binding_id UUID NOT NULL,
    version INT NOT NULL,
    action TEXT NOT NULL,
    actor_id UUID NOT NULL,
    payload JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT rule_bindings_history_action_check
        CHECK (action IN ('create','update','disable','rollback'))
);

CREATE INDEX IF NOT EXISTS rule_bindings_history_binding_id_idx
    ON pricing.rule_bindings_history (binding_id, created_at DESC);