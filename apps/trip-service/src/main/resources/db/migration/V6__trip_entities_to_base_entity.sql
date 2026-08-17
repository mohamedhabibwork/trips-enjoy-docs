-- V6__trip_entities_to_base_entity.sql
--
-- Phase C (platform DRY): migrate the 3 simple-PK + audit-column
-- trip-service aggregates (`trip.request`, `trip.trip`,
-- `trip.trip_stop`) to the platform `BaseEntity` column shape:
--   * `created_by` / `updated_by` from UUID to VARCHAR(255) so the
--     `PlatformAuditorAware` (JWT `sub`) round-trips correctly.
--   * `row_version` -> `version` so the JPA `@Version` mapping on
--     `BaseEntity.version` lines up with the column.
--
-- The 6 append-only / composite-PK / non-`@MappedSuperclass` entities
-- are intentionally NOT covered here:
--   * `trip.trip_location_point`     — composite PK (id, recorded_at);
--                                       partitioned by month.
--   * `trip.trip_state_history`       — single-UUID PK but append-only
--                                       (V3 trigger blocks update +
--                                       delete); no audit columns.
--   * `trip.trip_reward`              — single-UUID PK, no audit
--                                       columns (`created_at` only).
--   * `trip.trip_reward_reversal`     — single-UUID PK, no audit
--                                       columns (`reversed_at` only).
--   * `trip.outbox_event`             — Phase B canonical shape, no
--                                       BaseEntity mapping.
--   * `trip.inbox_event`              — Phase B canonical shape, no
--                                       BaseEntity mapping.
--   * `trip.idempotency_record`       — Phase B canonical shape, no
--                                       BaseEntity mapping.
--
-- Authoritative docs:
--   * docs/architecture/PLATFORM_BASELINE.md
--   * docs/services/trip-service/ERD.md §3
--   * packages/platform-spring-boot/platform-spring-boot-data/BaseEntity.kt

ALTER TABLE trip.request
    ALTER COLUMN created_by TYPE VARCHAR(255) USING created_by::text;

ALTER TABLE trip.request
    ALTER COLUMN updated_by TYPE VARCHAR(255) USING updated_by::text;

ALTER TABLE trip.request
    RENAME COLUMN row_version TO version;

ALTER TABLE trip.trip
    ALTER COLUMN created_by TYPE VARCHAR(255) USING created_by::text;

ALTER TABLE trip.trip
    ALTER COLUMN updated_by TYPE VARCHAR(255) USING updated_by::text;

ALTER TABLE trip.trip
    RENAME COLUMN row_version TO version;

ALTER TABLE trip.trip_stop
    ALTER COLUMN created_by TYPE VARCHAR(255) USING created_by::text;

ALTER TABLE trip.trip_stop
    ALTER COLUMN updated_by TYPE VARCHAR(255) USING updated_by::text;

ALTER TABLE trip.trip_stop
    RENAME COLUMN row_version TO version;

-- Phase C (platform DRY): the platform `BaseEntity` adds a
-- `deleted_at TIMESTAMPTZ` column to every subclass; `trip.trip_stop`
-- did not previously declare one (the other migrated tables do), so
-- add it now (NULL = active per DATA--006).
ALTER TABLE trip.trip_stop
    ADD COLUMN deleted_at TIMESTAMPTZ;