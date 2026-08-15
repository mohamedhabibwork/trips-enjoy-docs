-- V2__food_order_core.sql
-- Per docs/services/food-order-service/ERD.md §3:
--   food_order.requests                : the order request (pre-acceptance).
--   food_order.orders                  : the accepted order (after restaurant accepts).
--   food_order.order_items             : line items (menu items + modifiers + addons).
--   food_order.order_item_modifiers    : modifiers per item.
--   food_order.order_item_addons       : addons per item.
--   food_order.order_state_history     : append-only state transition log.
--   food_order.outbox                  : transactional outbox for kafka publication.
--   food_order.inbox                   : idempotent inbox for kafka consumption.
--
-- Schema-wide conventions (per the prior 9 graduates):
--   * primary keys are UUIDv7 (single UUID, NOT composite @IdClass).
--   * cross-service references (customer_id, restaurant_id, branch_id,
--     courier_id, menu_item_id, fare_id, payment_id) are plain UUIDs
--     WITHOUT database FKs (DATA--003).
--   * soft delete via deleted_at where applicable.
--   * audit columns (created_at, updated_at, created_by, updated_by).
--   * row_version (BIGINT) is the optimistic-lock counter.

-- 1) food_order.requests : the order request (pre-acceptance).
CREATE TABLE IF NOT EXISTS food_order.requests (
    id UUID PRIMARY KEY,
    customer_id UUID NOT NULL,
    restaurant_id UUID NOT NULL,
    branch_id UUID,
    order_type TEXT NOT NULL DEFAULT 'delivery',
    status TEXT NOT NULL DEFAULT 'draft',
    quote_snapshot JSONB,
    total_minor BIGINT,
    currency TEXT NOT NULL DEFAULT 'USD',
    idempotency_key TEXT,
    correlation_id UUID NOT NULL,
    placed_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at TIMESTAMPTZ,
    accepted_at TIMESTAMPTZ,
    rejected_at TIMESTAMPTZ,
    cancelled_at TIMESTAMPTZ,
    cancellation_reason TEXT,
    row_version BIGINT NOT NULL DEFAULT 1,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by UUID NOT NULL,
    updated_by UUID NOT NULL,
    deleted_at TIMESTAMPTZ,
    CONSTRAINT requests_order_type_check
        CHECK (order_type IN ('delivery','pickup','dine_in')),
    CONSTRAINT requests_status_check
        CHECK (status IN ('draft','priced','placed','accepted','rejected','preparing','ready','picked_up','delivered','cancelled','expired','no_show'))
);
CREATE INDEX IF NOT EXISTS requests_customer_id_idx ON food_order.requests (customer_id, placed_at DESC);
CREATE INDEX IF NOT EXISTS requests_restaurant_id_idx ON food_order.requests (restaurant_id, placed_at DESC);
CREATE INDEX IF NOT EXISTS requests_status_idx ON food_order.requests (status)
    WHERE deleted_at IS NULL;

-- 2) food_order.orders : the accepted order (after restaurant accepts).
CREATE TABLE IF NOT EXISTS food_order.orders (
    id UUID PRIMARY KEY,
    request_id UUID NOT NULL,
    customer_id UUID NOT NULL,
    restaurant_id UUID NOT NULL,
    branch_id UUID,
    courier_id UUID,
    order_type TEXT NOT NULL DEFAULT 'delivery',
    status TEXT NOT NULL DEFAULT 'pending',
    total_minor BIGINT NOT NULL,
    currency TEXT NOT NULL DEFAULT 'USD',
    delivery_address TEXT,
    delivery_zone_id UUID,
    distance_km NUMERIC(8, 2),
    estimated_delivery_at TIMESTAMPTZ,
    delivered_at TIMESTAMPTZ,
    correlation_id UUID NOT NULL,
    idempotency_key TEXT,
    placed_at TIMESTAMPTZ,
    accepted_at TIMESTAMPTZ,
    preparing_at TIMESTAMPTZ,
    ready_at TIMESTAMPTZ,
    picked_up_at TIMESTAMPTZ,
    cancelled_at TIMESTAMPTZ,
    cancellation_reason TEXT,
    row_version BIGINT NOT NULL DEFAULT 1,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by UUID NOT NULL,
    updated_by UUID NOT NULL,
    deleted_at TIMESTAMPTZ,
    CONSTRAINT orders_status_check
        CHECK (status IN ('pending','accepted','preparing','ready','picked_up','delivered','cancelled','no_show')),
    CONSTRAINT orders_order_type_check
        CHECK (order_type IN ('delivery','pickup','dine_in')),
    CONSTRAINT orders_total_minor_check CHECK (total_minor > 0)
);
CREATE INDEX IF NOT EXISTS orders_customer_id_idx ON food_order.orders (customer_id, created_at DESC);
CREATE INDEX IF NOT EXISTS orders_restaurant_id_idx ON food_order.orders (restaurant_id, created_at DESC);
CREATE INDEX IF NOT EXISTS orders_courier_id_idx ON food_order.orders (courier_id, created_at DESC)
    WHERE courier_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS orders_status_idx ON food_order.orders (status)
    WHERE deleted_at IS NULL;
CREATE INDEX IF NOT EXISTS orders_request_id_idx ON food_order.orders (request_id);

-- 3) food_order.order_items : line items.
CREATE TABLE IF NOT EXISTS food_order.order_items (
    id UUID PRIMARY KEY,
    order_id UUID NOT NULL,
    menu_item_id UUID NOT NULL,
    name TEXT NOT NULL,
    quantity INT NOT NULL DEFAULT 1,
    unit_price_minor BIGINT NOT NULL,
    total_price_minor BIGINT NOT NULL,
    special_instructions TEXT,
    row_version BIGINT NOT NULL DEFAULT 1,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by UUID NOT NULL,
    updated_by UUID NOT NULL,
    CONSTRAINT order_items_quantity_check CHECK (quantity >= 1),
    CONSTRAINT order_items_unit_price_minor_check CHECK (unit_price_minor >= 0),
    CONSTRAINT order_items_total_price_minor_check CHECK (total_price_minor >= 0)
);
CREATE INDEX IF NOT EXISTS order_items_order_id_idx ON food_order.order_items (order_id);

-- 4) food_order.order_item_modifiers : modifiers per item (e.g. "no onions").
CREATE TABLE IF NOT EXISTS food_order.order_item_modifiers (
    id UUID PRIMARY KEY,
    order_item_id UUID NOT NULL,
    modifier_id UUID NOT NULL,
    name TEXT NOT NULL,
    price_delta_minor BIGINT NOT NULL DEFAULT 0,
    row_version BIGINT NOT NULL DEFAULT 1,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by UUID NOT NULL,
    updated_by UUID NOT NULL,
    CONSTRAINT order_item_modifiers_price_delta_check CHECK (price_delta_minor >= 0)
);
CREATE INDEX IF NOT EXISTS order_item_modifiers_order_item_id_idx ON food_order.order_item_modifiers (order_item_id);

-- 5) food_order.order_item_addons : addons per item (e.g. "extra cheese").
CREATE TABLE IF NOT EXISTS food_order.order_item_addons (
    id UUID PRIMARY KEY,
    order_item_id UUID NOT NULL,
    addon_id UUID NOT NULL,
    name TEXT NOT NULL,
    price_delta_minor BIGINT NOT NULL DEFAULT 0,
    row_version BIGINT NOT NULL DEFAULT 1,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by UUID NOT NULL,
    updated_by UUID NOT NULL
);
CREATE INDEX IF NOT EXISTS order_item_addons_order_item_id_idx ON food_order.order_item_addons (order_item_id);

-- 6) food_order.order_state_history : append-only audit log.
-- SINGLE UUID PK (not composite) to avoid the Spring Data JPA + Kotlin
-- type-inference blocker (see uber-admin-service memory entry).
CREATE TABLE IF NOT EXISTS food_order.order_state_history (
    id UUID PRIMARY KEY,
    subject_id UUID NOT NULL,
    subject_kind TEXT NOT NULL DEFAULT 'order',
    from_state TEXT,
    to_state TEXT NOT NULL,
    actor_kc_sub UUID,
    actor_kind TEXT NOT NULL,
    reason TEXT,
    correlation_id UUID NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT order_state_history_actor_kind_check
        CHECK (actor_kind IN ('customer','restaurant','courier','admin','system','dispatch')),
    CONSTRAINT order_state_history_subject_kind_check
        CHECK (subject_kind IN ('order','request'))
);
CREATE INDEX IF NOT EXISTS order_state_history_subject_id_idx
    ON food_order.order_state_history (subject_id, occurred_at DESC);
CREATE INDEX IF NOT EXISTS order_state_history_occurred_at_idx
    ON food_order.order_state_history (occurred_at);

-- 7) food_order.outbox : transactional outbox.
CREATE TABLE IF NOT EXISTS food_order.outbox (
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
CREATE INDEX IF NOT EXISTS outbox_pending_idx ON food_order.outbox (next_attempt_at)
    WHERE published_at IS NULL;
CREATE INDEX IF NOT EXISTS outbox_aggregate_idx ON food_order.outbox (aggregate_type, aggregate_id);

-- 8) food_order.inbox : idempotent inbox.
CREATE TABLE IF NOT EXISTS food_order.inbox (
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
    ON food_order.inbox (source_topic, source_event_id);

-- 9) food_order.idempotency_record : the canonical scope+key Idempotency-Key.
CREATE TABLE IF NOT EXISTS food_order.idempotency_record (
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
        CHECK (scope IN ('order_request','order_cancel','order_state_transition','order_complete','order_rate','deal_create','deal_counter','deal_accept','deal_reject'))
);
CREATE UNIQUE INDEX IF NOT EXISTS idempotency_record_scope_key_uniq
    ON food_order.idempotency_record (scope, idem_key);