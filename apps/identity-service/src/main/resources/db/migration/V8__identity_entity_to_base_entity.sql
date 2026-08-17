-- V8__identity_entity_to_base_entity.sql
--
-- Phase C (platform DRY): migrate the `identity.identities` row to the
-- platform `BaseEntity` column shape:
--   * `created_by` / `updated_by` from UUID to VARCHAR(255) so the
--     `PlatformAuditorAware` (JWT `sub`) round-trips correctly.
--   * `row_version` → `version` so the JPA `@Version` mapping on
--     `BaseEntity.version` lines up with the column.
--
-- The 5 insert-only / composite-PK entities (`identity_audit_log`,
-- `identity_claim_history`, `role_assignment_history`, `outbox`,
-- `inbox`, `idempotency_keys`) are intentionally NOT covered here —
-- they use `@Id UUID` / composite PKs and do not extend `BaseEntity`.
-- Their DB-side triggers and constraint shape remain authoritative.
--
-- `identity.identity_claims` is also skipped — its table lacks
-- `created_by` / `updated_by` columns (it is a cache row, not a
-- aggregate), so migrating it to `BaseEntity` would require adding
-- columns and rewriting the upsert helper. Deferred to a later phase
-- if/when claim-writer audit becomes required.
--
-- Authoritative docs:
--   * docs/architecture/PLATFORM_BASELINE.md
--   * docs/services/identity-service/ERD.md §3
--   * packages/platform-spring-boot/platform-spring-boot-data/BaseEntity.kt

ALTER TABLE identity.identities
    ALTER COLUMN created_by TYPE VARCHAR(255) USING created_by::text;

ALTER TABLE identity.identities
    ALTER COLUMN updated_by TYPE VARCHAR(255) USING updated_by::text;

ALTER TABLE identity.identities
    RENAME COLUMN row_version TO version;