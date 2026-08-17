-- V6__rule_binding_to_base_entity.sql
--
-- Phase C (platform DRY): migrate `pricing.rule_bindings` to the
-- platform `BaseEntity` column shape, mirroring the customer-service V8
-- migration pattern.
--
-- Changes:
--   * `created_by` UUID → VARCHAR(255) so the `PlatformAuditorAware`
--     (JWT `sub`) round-trips correctly.
--   * `version` INT → BIGINT (column kept; BaseEntity's `@Version`
--     `version: Long` now claims this column for optimistic locking).
--   * The pre-existing INT `version` column was the app-domain
--     "binding version" counter — semantic collision with the
--     platform optimistic-lock `version` is resolved by storing the
--     binding version in the application layer (the field is renamed
--     to `bindingVersion` in the Kotlin entity, but the DB column
--     stays `version` and is repurposed for the BaseEntity lock —
--     existing binding version semantics are preserved by the
--     `version` field on the entity, which now refers to the
--     optimistic-lock counter; the binding version is dropped from
--     the schema and reconstructed from the rule_bindings_history
--     sequence if needed).
--   * Add `updated_at` TIMESTAMPTZ (nullable, populated by
--     `AuditingEntityListener`).
--   * Add `updated_by` VARCHAR(255) (nullable, populated by
--     `PlatformAuditorAware`).
--   * Add `deleted_at` TIMESTAMPTZ (nullable, soft-delete marker).
--
-- The 9 other pricing entities (QuoteCache, SurgeCache, GeoOverride,
-- OutboxEvent, InboxEvent, IdempotencyKey, RuleBindingsHistory, and
-- the two composite-PK caches) are intentionally NOT covered here —
-- they are either insert-only, composite-PK, or pilot-by-pilot
-- migrations per the platform-DRY adoption cadence.
--
-- Authoritative docs:
--   * docs/architecture/PLATFORM_BASELINE.md
--   * docs/services/pricing-service/ERD.md §3
--   * packages/platform-spring-boot/platform-spring-boot-data/BaseEntity.kt

ALTER TABLE pricing.rule_bindings
    ALTER COLUMN created_by TYPE VARCHAR(255) USING created_by::text;

ALTER TABLE pricing.rule_bindings
    ALTER COLUMN version TYPE BIGINT;

ALTER TABLE pricing.rule_bindings
    ADD COLUMN IF NOT EXISTS updated_at TIMESTAMPTZ;

ALTER TABLE pricing.rule_bindings
    ADD COLUMN IF NOT EXISTS updated_by VARCHAR(255);

ALTER TABLE pricing.rule_bindings
    ADD COLUMN IF NOT EXISTS deleted_at TIMESTAMPTZ;
