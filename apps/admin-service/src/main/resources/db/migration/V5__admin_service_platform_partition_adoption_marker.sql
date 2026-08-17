-- V5__admin_service_platform_partition_adoption_marker.sql
--
-- Phase D (platform DRY): adoption of `platform-spring-boot-partition`.
-- Per ADR-0029 the platform's `@Scheduled` cron (`0 0 2 * * *` ensure /
-- `0 30 2 * * *` drop) now drives partition maintenance for every
-- partitioned table across the fleet.
--
-- admin-service has no locally-managed partitioned tables of its own
-- (no `PartitionMaintenanceJob.kt` to delete; the platform-side cron
-- covers every partitioned table via the starter). V5 documents the
-- platform-side adoption per ADR-0029 only; no schema change required.
--
-- This migration is intentionally a no-op marker so the Flyway
-- checksum trail reflects the Phase D fan-out across the Kotlin tier.
-- SELECT 1 keeps the file syntactically valid.

SELECT 1;