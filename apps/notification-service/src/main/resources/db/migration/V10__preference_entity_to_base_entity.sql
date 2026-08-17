-- V10__preference_entity_to_base_entity.sql
--
-- Phase C (platform DRY): migrate the `notification.preferences` row to
-- the platform `BaseEntity` column shape:
--   * `created_by` / `updated_by` from UUID to VARCHAR(255) so the
--     `PlatformAuditorAware` (JWT `sub`) round-trips correctly.
--   * Add `version BIGINT NOT NULL DEFAULT 0` for the `BaseEntity`
--     optimistic-lock counter (the existing preferences table does
--     not carry an optimistic-lock column).
--
-- notification.preferences was selected for migration because it has
-- the canonical simple-PK + audit-column shape (`id`, `created_at`,
-- `updated_at`, `created_by`, `updated_by`, `deleted_at`) with no
-- domain-meaningful `version` column to collide with the
-- `BaseEntity.version` optimistic-lock field.
--
-- The other mutable entities in notification-service are deliberately
-- NOT covered by this migration:
--
--   * `notification.templates` carries a domain-meaningful
--     `version INT` column (template-versioning per
--     docs/services/notification-service/TEMPLATE_HISTORY.md).
--     `BaseEntity.version: Long` (optimistic-lock) cannot be
--     substituted onto the same column without breaking the
--     template-version semantics. Templates will be revisited once
--     a domain-version-aware BaseEntity variant exists, or once
--     templates moves to the canonical "publish a new row" pattern
--     used by template_history.
--   * `notification.suppressions` only carries `created_at` /
--     `created_by` / `deleted_at` (no `updated_at`, no `updated_by`)
--     — it does not have the full canonical audit-column shape.
--   * `notification.deliveries` uses a composite PK `(id, created_at)`
--     because the table is RANGE-partitioned on `created_at`
--     (DATABASE_ARCHITECTURE.md §6). Composite-PK entities do not
--     extend BaseEntity.
--   * `notification.outbox` (OutboxEvent), `notification.inbox`
--     (InboxEvent), `notification.idempotency_records`
--     (IdempotencyRecord), and `notification.template_history` are
--     INSERT-only / append-only — they don't extend BaseEntity.
--
-- Authoritative docs:
--   * docs/architecture/PLATFORM_BASELINE.md
--   * docs/services/notification-service/ERD.md §Preference
--   * packages/platform-spring-boot/platform-spring-boot-data/BaseEntity.kt

ALTER TABLE notification.preferences
    ALTER COLUMN created_by TYPE VARCHAR(255) USING created_by::text;

ALTER TABLE notification.preferences
    ALTER COLUMN updated_by TYPE VARCHAR(255) USING updated_by::text;

ALTER TABLE notification.preferences
    ADD COLUMN version BIGINT NOT NULL DEFAULT 0;