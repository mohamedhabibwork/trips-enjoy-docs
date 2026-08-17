-- V10__phase_d_partition_cron_centralized.sql
--
-- Phase D platform-spring-boot-partition adoption.
-- Per ADR-0029: partition maintenance cron now centralized at
-- platform level (cron 0 0 2 * * * ensure / 0 30 2 * * * drop).
--
-- The local `apps/configuration-service/.../PartitionMaintenanceJob.kt`
-- @Scheduled wrapper was deleted. The `V7__partition_functions.sql`
-- definitions of `partman.ensure_partitions` etc. remain authoritative;
-- the platform's centralized cron now drives them, with the cluster's
-- existing pg_cron schedule as backup.
--
-- Authoritative docs:
--   * docs/architecture/PLATFORM_BASELINE.md
--   * docs/services/configuration-service/PLAN.md (Phase D)
--   * docs/shared/PARTITION_FUNCTIONS.md §7 + §12
--   * packages/platform-spring-boot-partition/

SELECT 1;
