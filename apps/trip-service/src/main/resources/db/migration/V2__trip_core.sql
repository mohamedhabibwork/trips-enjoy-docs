-- V2__trip_core.sql
-- Per docs/services/trip-service/ERD.md §3:
--   trip.request                  : the trip request (passenger's order).
--   trip.trip                     : the active trip (after dispatch).
--   trip.trip_stop                : additional stops on multi-stop trips.
--   trip.trip_location_point      : time-series location pings.
--   trip.trip_state_history       : append-only state transition log.
--   trip.trip_reward              : reward grant for completed trip.
--   trip.trip_reward_reversal     : reversal of reward (refund flow).
--   trip.idempotency_record       : the canonical scope+key Idempotency-Key.
--   trip.outbox_event             : transactional outbox for kafka publication.
--   trip.inbox_event              : idempotent inbox for kafka consumption.
--
-- Schema-wide conventions (per the prior 9 graduates):
--   * primary keys are UUIDv7.
--   * cross-service references (rider_id, driver_id, vehicle_id, merchant_id,
--     city_id, fare_id, payment_id) are plain UUIDs WITHOUT database FKs.
--   * soft delete via deleted_at where applicable.
--   * audit columns (created_at, updated_at, created_by, updated_by).
--   * row_version (BIGINT) is the optimistic-lock counter.
--
-- IMPORTANT NOTE: trip.trip_state_history uses a SINGLE UUID PK (not a
-- composite id+occurred_at PK) to avoid the Spring Data JPA + Kotlin
-- type-inference blocker that hit admin-service's @IdClass design.
-- The occurred_at column is a regular indexed TIMESTAMPTZ; the partition
-- key is still occurred_at. This is the canonical lift-forward pattern
-- for time-series audit tables going forward.

-- 1) trip.request : the trip request.
CREATE TABLE IF NOT EXISTS trip.request (
    id UUID PRIMARY KEY,
    rider_id UUID NOT NULL,
    city_id UUID,
    origin_zone_id UUID,
    destination_zone_id UUID,
    ride_type TEXT NOT NULL DEFAULT 'standard',
    status TEXT NOT NULL DEFAULT 'draft',
    fare_id UUID,
    quote_snapshot JSONB,
    correlation_id UUID NOT NULL,
    idempotency_key TEXT,
    requested_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at TIMESTAMPTZ,
    row_version BIGINT NOT NULL DEFAULT 1,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by UUID NOT NULL,
    updated_by UUID NOT NULL,
    deleted_at TIMESTAMPTZ,
    CONSTRAINT request_ride_type_check
        CHECK (ride_type IN ('standard','xl','comfort','pool','premium','van','accessible')),
    CONSTRAINT request_status_check
        CHECK (status IN ('draft','priced','submitted','matching','rejected','cancelled','expired','converted'))
);
CREATE INDEX IF NOT EXISTS request_rider_id_idx ON trip.request (rider_id, requested_at DESC);
CREATE INDEX IF NOT EXISTS request_status_idx ON trip.request (status)
    WHERE deleted_at IS NULL;

-- 2) trip.trip : the active trip after dispatch.
CREATE TABLE IF NOT EXISTS trip.trip (
    id UUID PRIMARY KEY,
    request_id UUID NOT NULL,
    rider_id UUID NOT NULL,
    driver_id UUID,
    vehicle_id UUID,
    city_id UUID,
    ride_type TEXT NOT NULL,
    status TEXT NOT NULL DEFAULT 'pending',
    fare_id UUID,
    final_price_minor BIGINT,
    final_currency TEXT NOT NULL DEFAULT 'USD',
    origin_zone_id UUID,
    destination_zone_id UUID,
    distance_km NUMERIC(8, 2),
    duration_min NUMERIC(8, 2),
    correlation_id UUID NOT NULL,
    matched_at TIMESTAMPTZ,
    arrived_at TIMESTAMPTZ,
    started_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    cancelled_at TIMESTAMPTZ,
    cancellation_reason TEXT,
    rating SMALLINT,
    rating_comment TEXT,
    rating_at TIMESTAMPTZ,
    row_version BIGINT NOT NULL DEFAULT 1,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by UUID NOT NULL,
    updated_by UUID NOT NULL,
    deleted_at TIMESTAMPTZ,
    CONSTRAINT trip_status_check
        CHECK (status IN ('pending','matched','driver_assigned','arrived','in_progress','completed','cancelled','no_show')),
    CONSTRAINT trip_ride_type_check
        CHECK (ride_type IN ('standard','xl','comfort','pool','premium','van','accessible'))
);
CREATE INDEX IF NOT EXISTS trip_rider_id_idx ON trip.trip (rider_id, created_at DESC);
CREATE INDEX IF NOT EXISTS trip_driver_id_idx ON trip.trip (driver_id, created_at DESC);
CREATE INDEX IF NOT EXISTS trip_status_idx ON trip.trip (status)
    WHERE deleted_at IS NULL;
CREATE INDEX IF NOT EXISTS trip_request_id_idx ON trip.trip (request_id);

-- 3) trip.trip_stop : additional stops on multi-stop trips.
CREATE TABLE IF NOT EXISTS trip.trip_stop (
    id UUID PRIMARY KEY,
    trip_id UUID NOT NULL,
    sequence INT NOT NULL,
    zone_id UUID,
    address TEXT,
    arrived_at TIMESTAMPTZ,
    departed_at TIMESTAMPTZ,
    row_version BIGINT NOT NULL DEFAULT 1,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by UUID NOT NULL,
    updated_by UUID NOT NULL,
    CONSTRAINT trip_stop_sequence_check CHECK (sequence >= 0)
);
CREATE INDEX IF NOT EXISTS trip_stop_trip_id_idx ON trip.trip_stop (trip_id, sequence);

-- 4) trip.trip_location_point : time-series location pings (partitioned).
CREATE TABLE IF NOT EXISTS trip.trip_location_point (
    id UUID NOT NULL,
    trip_id UUID NOT NULL,
    latitude NUMERIC(10, 7) NOT NULL,
    longitude NUMERIC(10, 7) NOT NULL,
    accuracy_m NUMERIC(8, 2),
    speed_kmh NUMERIC(6, 2),
    heading_deg NUMERIC(5, 2),
    recorded_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    correlation_id UUID NOT NULL,
    PRIMARY KEY (id, recorded_at),
    CONSTRAINT trip_location_point_latitude_check CHECK (latitude BETWEEN -90 AND 90),
    CONSTRAINT trip_location_point_longitude_check CHECK (longitude BETWEEN -180 AND 180)
) PARTITION BY RANGE (recorded_at);
CREATE INDEX IF NOT EXISTS trip_location_point_trip_id_idx ON trip.trip_location_point (trip_id, recorded_at DESC);
CREATE TABLE IF NOT EXISTS trip.trip_location_point_default PARTITION OF trip.trip_location_point DEFAULT;

-- 5) trip.trip_state_history : append-only state transition log.
-- SINGLE UUID PK (not composite) to avoid the @IdClass Kotlin type-inference
-- blocker encountered in admin-service (see uber-admin-service-implementation
-- memory entry). occurred_at is a regular indexed TIMESTAMPTZ column.
CREATE TABLE IF NOT EXISTS trip.trip_state_history (
    id UUID PRIMARY KEY,
    trip_id UUID NOT NULL,
    from_state TEXT,
    to_state TEXT NOT NULL,
    actor_kc_sub UUID,
    actor_kind TEXT NOT NULL,
    reason TEXT,
    correlation_id UUID NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT trip_state_history_actor_kind_check
        CHECK (actor_kind IN ('rider','driver','admin','system','dispatch'))
);
CREATE INDEX IF NOT EXISTS trip_state_history_trip_id_idx
    ON trip.trip_state_history (trip_id, occurred_at DESC);
CREATE INDEX IF NOT EXISTS trip_state_history_occurred_at_idx
    ON trip.trip_state_history (occurred_at);

-- 6) trip.trip_reward : reward grant for completed trip.
CREATE TABLE IF NOT EXISTS trip.trip_reward (
    id UUID PRIMARY KEY,
    trip_id UUID NOT NULL,
    driver_id UUID NOT NULL,
    rider_id UUID NOT NULL,
    amount_minor BIGINT NOT NULL,
    currency TEXT NOT NULL DEFAULT 'USD',
    granted_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    reason TEXT NOT NULL,
    reversal_id UUID,
    correlation_id UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT trip_reward_amount_check CHECK (amount_minor > 0)
);
CREATE INDEX IF NOT EXISTS trip_reward_driver_id_idx ON trip.trip_reward (driver_id, granted_at DESC);
CREATE INDEX IF NOT EXISTS trip_reward_trip_id_idx ON trip.trip_reward (trip_id);

-- 7) trip.trip_reward_reversal : reversal of reward (refund flow).
CREATE TABLE IF NOT EXISTS trip.trip_reward_reversal (
    id UUID PRIMARY KEY,
    reward_id UUID NOT NULL,
    trip_id UUID NOT NULL,
    reversed_by_kc_sub UUID NOT NULL,
    reason TEXT NOT NULL,
    correlation_id UUID NOT NULL,
    reversed_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS trip_reward_reversal_reward_id_idx ON trip.trip_reward_reversal (reward_id);

-- 8) trip.idempotency_record : the canonical scope+key Idempotency-Key.
CREATE TABLE IF NOT EXISTS trip.idempotency_record (
    id UUID PRIMARY KEY,
    scope TEXT NOT NULL,
    idem_key TEXT NOT NULL,
    request_hash TEXT NOT NULL,
    response_status INT,
    response_body JSONB,
    locked_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    completed_at TIMESTAMPTZ,
    created_by UUID NOT NULL,
    CONSTRAINT idempotency_record_scope_check
        CHECK (scope IN ('trip_request','trip_cancel','trip_complete','trip_rate','trip_reward_re_evaluate','trip_reward_reverse','trip_location_ping','trip_stop','trip_start','trip_arrive','trip_dropoff'))
);
CREATE UNIQUE INDEX IF NOT EXISTS idempotency_record_scope_key_uniq
    ON trip.idempotency_record (scope, idem_key);

-- 9) trip.outbox_event : transactional outbox.
CREATE TABLE IF NOT EXISTS trip.outbox_event (
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
CREATE INDEX IF NOT EXISTS outbox_event_pending_idx ON trip.outbox_event (next_attempt_at)
    WHERE published_at IS NULL;
CREATE INDEX IF NOT EXISTS outbox_event_aggregate_idx ON trip.outbox_event (aggregate_type, aggregate_id);

-- 10) trip.inbox_event : idempotent inbox.
CREATE TABLE IF NOT EXISTS trip.inbox_event (
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
CREATE UNIQUE INDEX IF NOT EXISTS inbox_event_topic_event_uniq
    ON trip.inbox_event (source_topic, source_event_id);