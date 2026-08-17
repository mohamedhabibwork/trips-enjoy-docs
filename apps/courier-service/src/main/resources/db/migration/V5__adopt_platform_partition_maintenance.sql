-- V5__adopt_platform_partition_maintenance.sql
--
-- Phase D platform-spring-boot-partition adoption.
-- Per ADR-0029.
--
-- This is a marker migration. The local cron
-- `PartitionMaintenanceJob` was deleted in this commit and the
-- canonical `partman`/`platform-spring-boot-partition` cron is now
-- inherited from the platform starter.

SELECT 1;
