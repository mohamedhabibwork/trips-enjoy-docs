-- V6__restaurant_entity_to_base_entity.sql
--
-- Phase C (platform DRY): migrate the `restaurant.restaurants` row to the
-- platform `BaseEntity` column shape:
--   * `created_by` / `updated_by` from UUID to VARCHAR(255) so the
--     `PlatformAuditorAware` (JWT `sub`) round-trips correctly.
--   * `row_version` → `version` so the JPA `@Version` mapping on
--     `BaseEntity.version` lines up with the column.
--
-- The 6 insert-only / composite-PK entities (`restaurant_audit_log`,
-- `idempotency_keys`, `restaurant_cuisines`, `restaurant_tags`,
-- `outbox_events`, `inbox_events`) are intentionally NOT covered here:
--   * `restaurant_audit_log` is append-only and uses an explicit
--     `@Id UUID` constructor; the `actor_kc_sub` column stays
--     `UUID` (audit-event actor identity, not the JWT sub of the
--     request).
--   * `idempotency_keys`, `outbox_events`, `inbox_events` are
--     insert-only per ADR-0027 / 0028; `created_by` is the cross-
--     service actor UUID, intentionally NOT collapsed to VARCHAR.
--   * `restaurant_cuisines`, `restaurant_tags` are composite-PK join
--     tables — they have no `updated_by` / `row_version` / `deleted_at`.
--
-- Authoritative docs:
--   * docs/architecture/PLATFORM_BASELINE.md
--   * docs/services/restaurant-service/ERD.md §3
--   * packages/platform-spring-boot/platform-spring-boot-data/BaseEntity.kt

ALTER TABLE restaurant.restaurants
    ALTER COLUMN created_by TYPE VARCHAR(255) USING created_by::text;

ALTER TABLE restaurant.restaurants
    ALTER COLUMN updated_by TYPE VARCHAR(255) USING updated_by::text;

ALTER TABLE restaurant.restaurants
    RENAME COLUMN row_version TO version;
