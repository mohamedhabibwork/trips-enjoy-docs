package com.trips_enjoy.platform.partition

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Configuration for the canonical platform partition maintenance.
 *
 * Properties (application.yml `platform.partition.*`):
 * - [enabled]            master switch; default `true`. When `false`
 *                        neither the cron nor the health indicator is
 *                        activated.
 * - [cron]               cron expression for `ensurePartitions` (default
 *                        `0 0 2 * * *` per ADR-0029 — daily at 02:00 UTC).
 * - [retentionMonths]    retention window for the drop step.
 * - [horizonMonths]      future-month horizon for the ensure step.
 *                        Default `12` matches DATABASE_ARCHITECTURE §3.
 * - [healthTablePattern] regex applied to qualified parent table names
 *                        (`<schema>.<table>`). Only tables matching this
 *                        pattern get their health surfaced through the
 *                        `partitions` actuator endpoint. Default `.*`
 *                        (every partitioned parent owned by the service).
 */
@ConfigurationProperties("platform.partition")
data class PartitionProperties(
    val enabled: Boolean = true,
    val cron: String = "0 0 2 * * *",
    val retentionMonths: Int = 36,
    val horizonMonths: Int = 12,
    val healthTablePattern: String = ".*",
)
