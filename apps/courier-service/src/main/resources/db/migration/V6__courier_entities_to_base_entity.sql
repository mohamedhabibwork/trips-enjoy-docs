-- V6__courier_entities_to_base_entity.sql
--
-- Phase C (platform DRY): migrate 4 writable courier tables to the
-- platform `BaseEntity` column shape:
--   * `created_by` / `updated_by` from UUID to VARCHAR(255) so the
--     `PlatformAuditorAware` (JWT `sub`) round-trips correctly.
--   * `row_version` → `version` so the JPA `@Version` mapping on
--     `BaseEntity.version` lines up with the column.
--   * `courier_city_eligibility` had no `deleted_at` from V2 — add
--     it now so the BaseEntity soft-delete column is present on every
--     migrated table.
--
-- The 4 simple-PK + audit entities (`couriers`, `courier_shifts`,
-- `courier_city_eligibility`, `courier_documents`) are migrated to
-- extend `BaseEntity`. The 5 insert-only entities
-- (`courier_rating_history`, `courier_audit_log`, `courier.outbox`,
-- `courier.inbox`, `courier.idempotency`) are intentionally NOT
-- covered here — they use `@Id UUID` / embedded ids and do not extend
-- `BaseEntity`. Their DB-side triggers and constraint shape remain
-- authoritative.
--
-- Authoritative docs:
--   * docs/architecture/PLATFORM_BASELINE.md
--   * docs/services/courier-service/ERD.md §3
--   * packages/platform-spring-boot/platform-spring-boot-data/BaseEntity.kt

-- --- couriers ---
ALTER TABLE courier.couriers
    ALTER COLUMN created_by TYPE VARCHAR(255) USING created_by::text;

ALTER TABLE courier.couriers
    ALTER COLUMN updated_by TYPE VARCHAR(255) USING updated_by::text;

ALTER TABLE courier.couriers
    RENAME COLUMN row_version TO version;

-- --- courier_shifts ---
ALTER TABLE courier.courier_shifts
    ALTER COLUMN created_by TYPE VARCHAR(255) USING created_by::text;

ALTER TABLE courier.courier_shifts
    ALTER COLUMN updated_by TYPE VARCHAR(255) USING updated_by::text;

ALTER TABLE courier.courier_shifts
    RENAME COLUMN row_version TO version;

-- --- courier_city_eligibility ---
-- (Note: this table did not originally have a `deleted_at` column;
-- BaseEntity requires one for soft-delete. Add it now.)
ALTER TABLE courier.courier_city_eligibility
    ADD COLUMN IF NOT EXISTS deleted_at TIMESTAMPTZ;

ALTER TABLE courier.courier_city_eligibility
    ALTER COLUMN created_by TYPE VARCHAR(255) USING created_by::text;

ALTER TABLE courier.courier_city_eligibility
    ALTER COLUMN updated_by TYPE VARCHAR(255) USING updated_by::text;

ALTER TABLE courier.courier_city_eligibility
    RENAME COLUMN row_version TO version;

-- --- courier_documents ---
ALTER TABLE courier.courier_documents
    ALTER COLUMN created_by TYPE VARCHAR(255) USING created_by::text;

ALTER TABLE courier.courier_documents
    ALTER COLUMN updated_by TYPE VARCHAR(255) USING updated_by::text;

ALTER TABLE courier.courier_documents
    RENAME COLUMN row_version TO version;
