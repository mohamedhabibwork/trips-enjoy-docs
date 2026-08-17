-- V5__platform_partition_cron_marker.sql
--
-- Phase D (platform DRY): adoption of `platform-spring-boot-partition`.
-- The local `PartitionMaintenanceJob.kt` (which called `partman.ensure_partitions`
-- / `partman.drop_expired_partitions` directly) has been deleted; the
-- canonical partition maintenance cron now lives in
-- `platform-spring-boot-partition` and is enabled by the platform starter.
--
-- This migration is intentionally a no-op marker so the schema version
-- advances in lockstep with the application-side adoption. Driver-service
-- has only one time-partitioned parent (`driver.driver_rating_history`)
-- and `platform-spring-boot-partition` already knows its retention from
-- `platform.partition.<service>.<table>.retention-days` configuration
-- per `docs/architecture/PLATFORM_BASELINE.md`.
--
-- No DDL changes required.

-- Phase D platform-spring-boot-partition adoption.
SELECT 1;
