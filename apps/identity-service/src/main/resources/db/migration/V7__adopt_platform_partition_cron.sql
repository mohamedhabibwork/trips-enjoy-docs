-- V7: marker migration for Phase D platform-spring-boot-partition adoption.
--
-- identity-service now inherits the canonical `PartitionMaintenanceService`
-- cron from `platform-spring-boot-partition:4.1.4` (ADR-0029). The local
-- `PartitionMaintenanceJob.kt` cron has been deleted; the pg_cron schedule
-- registered in `Z99__partition_functions.sql` remains authoritative for
-- `identity.identity_claim_history` and `identity.role_assignment_history`,
-- with the platform `@Scheduled` cron as the fallback.
--
-- No schema changes — this migration is a forward-only marker so the
-- Flyway checksum trail reflects the Phase D fan-out across all 15 services.

SELECT 1;