-- V5: Phase D + Phase C marker migration for food-order-service.
--
-- This is a composite marker migration combining two platform-DRY
-- adoption steps in a single Flyway checkpoint:
--   * Phase D: platform-spring-boot-partition adoption.
--   * Phase C: BaseEntity / rowVersion->version adoption for the
--     5 simple-PK + audit entities (Request, Order, OrderItem,
--     OrderItemModifier, OrderItemAddon).
--
-- Phase D: food-order-service did NOT own a local
-- PartitionMaintenanceJob (no shadow deletion required). The
-- platform's PartitionMaintenanceService (cron `0 0 2 * * *` for
-- ensurePartitions and `0 30 2 * * *` for dropExpiredPartitions) is
-- inherited via `com.trips-enjoy.platform:spring-boot-starter:4.1.4`
-- auto-config. Platform config defaults apply (retention-months=24,
-- horizon-months=3, health-table-pattern=food_order.*).
--
-- Phase C: align 5 entity tables with `BaseEntity` column shape so
-- `PlatformAuditorAware` (JWT `sub`) round-trips `created_by` /
-- `updated_by` correctly and `BaseEntity.@Version` lines up with
-- `version` (was `row_version`).
--
-- Insert-only entities (OutboxEvent, InboxEvent, IdempotencyRecord)
-- and the append-only OrderStateHistory are intentionally NOT covered
-- here — they use `@Id UUID` and do not extend BaseEntity.
--
-- Idempotency: this migration is safe to re-apply. Each `ALTER COLUMN
-- ... TYPE VARCHAR(255)` and `RENAME COLUMN row_version TO version`
-- runs inside a `DO $$ ... $$` block that checks the current
-- `information_schema.columns` state before issuing the DDL, so a
-- partial application (where `created_by` was already converted but
-- `row_version` was already renamed) does not fail.
--
-- Authoritative docs:
--   * docs/architecture/adrs/0029-partition-maintenance.md
--   * docs/architecture/adrs/0030-base-entity.md
--   * packages/platform-spring-boot/platform-spring-boot-data/BaseEntity.kt
--   * docs/services/food-order-service/ERD.md §3

-- ----------------------------------------------------------------------------
-- Phase D: platform-spring-boot-partition adoption marker.
-- ----------------------------------------------------------------------------
SELECT 1;

-- ----------------------------------------------------------------------------
-- Phase C: align `Request` and `Order` (soft-delete tables) with BaseEntity.
-- ----------------------------------------------------------------------------
DO $$
DECLARE
    col_type TEXT;
BEGIN
    -- requests.created_by -> VARCHAR(255)
    SELECT data_type INTO col_type FROM information_schema.columns
        WHERE table_schema = 'food_order' AND table_name = 'requests' AND column_name = 'created_by';
    IF col_type IS DISTINCT FROM 'character varying' THEN
        EXECUTE 'ALTER TABLE food_order.requests ALTER COLUMN created_by TYPE VARCHAR(255) USING created_by::text';
    END IF;

    SELECT data_type INTO col_type FROM information_schema.columns
        WHERE table_schema = 'food_order' AND table_name = 'requests' AND column_name = 'updated_by';
    IF col_type IS DISTINCT FROM 'character varying' THEN
        EXECUTE 'ALTER TABLE food_order.requests ALTER COLUMN updated_by TYPE VARCHAR(255) USING updated_by::text';
    END IF;

    IF EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = 'food_order' AND table_name = 'requests' AND column_name = 'row_version'
    ) THEN
        EXECUTE 'ALTER TABLE food_order.requests RENAME COLUMN row_version TO version';
    END IF;

    SELECT data_type INTO col_type FROM information_schema.columns
        WHERE table_schema = 'food_order' AND table_name = 'orders' AND column_name = 'created_by';
    IF col_type IS DISTINCT FROM 'character varying' THEN
        EXECUTE 'ALTER TABLE food_order.orders ALTER COLUMN created_by TYPE VARCHAR(255) USING created_by::text';
    END IF;

    SELECT data_type INTO col_type FROM information_schema.columns
        WHERE table_schema = 'food_order' AND table_name = 'orders' AND column_name = 'updated_by';
    IF col_type IS DISTINCT FROM 'character varying' THEN
        EXECUTE 'ALTER TABLE food_order.orders ALTER COLUMN updated_by TYPE VARCHAR(255) USING updated_by::text';
    END IF;

    IF EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = 'food_order' AND table_name = 'orders' AND column_name = 'row_version'
    ) THEN
        EXECUTE 'ALTER TABLE food_order.orders RENAME COLUMN row_version TO version';
    END IF;
END$$;

-- ----------------------------------------------------------------------------
-- Phase C: align `OrderItem`, `OrderItemModifier`, `OrderItemAddon`
-- (no soft-delete) with BaseEntity.
--
-- `BaseEntity.deletedAt` is a nullable `Instant` column, so we ADD the
-- column with no default and no NOT NULL — soft delete stays opt-in
-- per row. The corresponding `delete_at` index is also deferred
-- (we don't query on `deleted_at` for these child rows; the parent
-- `Order.deleted_at` is the canonical soft-delete signal).
-- ----------------------------------------------------------------------------
DO $$
DECLARE
    tbl TEXT;
    col_type TEXT;
BEGIN
    FOREACH tbl IN ARRAY ARRAY['order_items', 'order_item_modifiers', 'order_item_addons'] LOOP
        SELECT data_type INTO col_type FROM information_schema.columns
            WHERE table_schema = 'food_order' AND table_name = tbl AND column_name = 'created_by';
        IF col_type IS DISTINCT FROM 'character varying' THEN
            EXECUTE format('ALTER TABLE food_order.%I ALTER COLUMN created_by TYPE VARCHAR(255) USING created_by::text', tbl);
        END IF;

        SELECT data_type INTO col_type FROM information_schema.columns
            WHERE table_schema = 'food_order' AND table_name = tbl AND column_name = 'updated_by';
        IF col_type IS DISTINCT FROM 'character varying' THEN
            EXECUTE format('ALTER TABLE food_order.%I ALTER COLUMN updated_by TYPE VARCHAR(255) USING updated_by::text', tbl);
        END IF;

        IF EXISTS (
            SELECT 1 FROM information_schema.columns
            WHERE table_schema = 'food_order' AND table_name = tbl AND column_name = 'row_version'
        ) THEN
            EXECUTE format('ALTER TABLE food_order.%I RENAME COLUMN row_version TO version', tbl);
        END IF;

        IF NOT EXISTS (
            SELECT 1 FROM information_schema.columns
            WHERE table_schema = 'food_order' AND table_name = tbl AND column_name = 'deleted_at'
        ) THEN
            EXECUTE format('ALTER TABLE food_order.%I ADD COLUMN deleted_at TIMESTAMPTZ', tbl);
        END IF;
    END LOOP;
END$$;