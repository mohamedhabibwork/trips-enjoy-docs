-- V6__driver_entity_to_base_entity.sql
--
-- Phase C (platform DRY): migrate 3 driver-service tables to the
-- platform `BaseEntity` column shape:
--   * `created_by` / `updated_by` from UUID to VARCHAR(255) so the
--     `PlatformAuditorAware` (JWT `sub`) round-trips correctly.
--   * `row_version` → `version` so the JPA `@Version` mapping on
--     `BaseEntity.version` lines up with the column.
--
-- The 5 insert-only entities (`driver_audit_log`,
-- `driver_rating_history`, `outbox_events`, `inbox_events`,
-- `idempotency_keys`) are intentionally NOT covered here — they use
-- `@Id UUID` and continue to take `created_by UUID` directly. Their
-- DB-side triggers, unique indexes, and partition shape remain
-- authoritative.
--
-- Authoritative docs:
--   * docs/architecture/PLATFORM_BASELINE.md
--   * docs/services/driver-service/ERD.md §3
--   * packages/platform-spring-boot/platform-spring-boot-data/BaseEntity.kt

-- driver.drivers
ALTER TABLE driver.drivers
    ALTER COLUMN created_by TYPE VARCHAR(255) USING created_by::text;

ALTER TABLE driver.drivers
    ALTER COLUMN updated_by TYPE VARCHAR(255) USING updated_by::text;

ALTER TABLE driver.drivers
    RENAME COLUMN row_version TO version;

-- driver.driver_documents
ALTER TABLE driver.driver_documents
    ALTER COLUMN created_by TYPE VARCHAR(255) USING created_by::text;

ALTER TABLE driver.driver_documents
    ALTER COLUMN updated_by TYPE VARCHAR(255) USING updated_by::text;

ALTER TABLE driver.driver_documents
    RENAME COLUMN row_version TO version;

-- driver.driver_city_eligibility
ALTER TABLE driver.driver_city_eligibility
    ALTER COLUMN created_by TYPE VARCHAR(255) USING created_by::text;

ALTER TABLE driver.driver_city_eligibility
    ALTER COLUMN updated_by TYPE VARCHAR(255) USING updated_by::text;

ALTER TABLE driver.driver_city_eligibility
    RENAME COLUMN row_version TO version;
