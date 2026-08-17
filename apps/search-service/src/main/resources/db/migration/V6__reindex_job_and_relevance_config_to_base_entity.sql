-- V6__reindex_job_and_relevance_config_to_base_entity.sql
--
-- Phase C (platform DRY): migrate the two `search-service` aggregates
-- that have an audit + version shape (`reindex_job`, `relevance_config`)
-- to the platform `BaseEntity` column shape:
--   * `created_by` / `updated_by` from UUID to VARCHAR(255) so the
--     `PlatformAuditorAware` (JWT `sub`) round-trips correctly.
--   * `row_version` → `version` so the JPA `@Version` mapping on
--     `BaseEntity.version` lines up with the column.
--   * `deleted_at` (TIMESTAMPTZ, nullable) added so the inherited
--     soft-delete column has a backing column.
--
-- The 5 insert-only entities (`query_log`, `index_health`, `outbox`,
-- `inbox`, `idempotency_keys`) are intentionally NOT covered here —
-- they use `@Id UUID` and do not extend `BaseEntity`. Their DB-side
-- triggers and constraint shape remain authoritative.
--
-- Authoritative docs:
--   * docs/architecture/PLATFORM_BASELINE.md
--   * docs/services/search-service/ERD.md §3
--   * packages/platform-spring-boot/platform-spring-boot-data/BaseEntity.kt

-- ----- search.reindex_job ---------------------------------------------------
ALTER TABLE search.reindex_job
    ALTER COLUMN created_by TYPE VARCHAR(255) USING created_by::text,
    ALTER COLUMN updated_by TYPE VARCHAR(255) USING updated_by::text;

ALTER TABLE search.reindex_job
    RENAME COLUMN row_version TO version;

ALTER TABLE search.reindex_job
    ADD COLUMN IF NOT EXISTS deleted_at TIMESTAMPTZ;

-- ----- search.relevance_config ---------------------------------------------
ALTER TABLE search.relevance_config
    ALTER COLUMN created_by TYPE VARCHAR(255) USING created_by::text,
    ALTER COLUMN updated_by TYPE VARCHAR(255) USING updated_by::text;

ALTER TABLE search.relevance_config
    RENAME COLUMN row_version TO version;

ALTER TABLE search.relevance_config
    ADD COLUMN IF NOT EXISTS deleted_at TIMESTAMPTZ;
