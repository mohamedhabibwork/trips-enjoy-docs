-- V8__customer_entity_to_base_entity.sql
--
-- Phase C (platform DRY): migrate the `customer.customers` row to the
-- platform `BaseEntity` column shape:
--   * `created_by` / `updated_by` from UUID to VARCHAR(255) so the
--     `PlatformAuditorAware` (JWT `sub`) round-trips correctly.
--   * `row_version` → `version` so the JPA `@Version` mapping on
--     `BaseEntity.version` lines up with the column.
--
-- The 5 insert-only entities (`customer_audit_log`,
-- `customer_kyc_history`, `customer_segment_history`,
-- `customer_ltv_history`, `customer.outbox`) are intentionally NOT
-- covered here — they use `@Id UUID` / embedded ids and do not extend
-- `BaseEntity`. Their DB-side triggers and constraint shape remain
-- authoritative.
--
-- Authoritative docs:
--   * docs/architecture/PLATFORM_BASELINE.md
--   * docs/services/customer-service/ERD.md §3
--   * packages/platform-spring-boot/platform-spring-boot-data/BaseEntity.kt

ALTER TABLE customer.customers
    ALTER COLUMN created_by TYPE VARCHAR(255) USING created_by::text;

ALTER TABLE customer.customers
    ALTER COLUMN updated_by TYPE VARCHAR(255) USING updated_by::text;

ALTER TABLE customer.customers
    RENAME COLUMN row_version TO version;
