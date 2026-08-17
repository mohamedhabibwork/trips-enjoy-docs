-- V5__platform_partition_cron_marker.sql
--
-- Phase D (platform DRY): adoption of `platform-spring-boot-partition`.
-- The local `PartitionMaintenanceJob.kt` (which called
-- `partman.ensure_partitions` / `partman.drop_expired_partitions`
-- directly) was not present in restaurant-service (this service has no
-- time-partitioned parent; partition functions land in V3 and are
-- exercised only by the read path), but the application-side adoption
-- is in lockstep with the rest of the Kotlin tier. The canonical
-- partition maintenance cron now lives in `platform-spring-boot-partition`
-- and is enabled by the platform starter; retention is sourced from
-- `platform.partition.<service>.<table>.retention-days` per
-- `docs/architecture/PLATFORM_BASELINE.md`.
--
-- This migration is intentionally a no-op marker so the schema version
-- advances in lockstep with the application-side adoption. No DDL
-- changes required.

-- Phase D platform-spring-boot-partition adoption.
SELECT 1;
