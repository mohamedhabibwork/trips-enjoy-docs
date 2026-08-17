package com.trips_enjoy.platform.partition

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Canonical default retention-class set. Values come from
 * `docs/shared/PARTITION_FUNCTIONS.md` §13 and
 * `DATABASE_ARCHITECTURE.md` §8 (retention classes).
 *
 * - `default`        36 months  — operational data (most tables).
 * - `operational`    36 months  — explicit alias for default.
 * - `financial`      84 months  — 7 years; SEC / SOX floor.
 *                    Required by audit-service
 *                    (`audit.audit_events` financial rows).
 * - `regulatory`    120 months  — 10 years; some telecom /
 *                    financial-industry regimes.
 * - `audit`         120 months  — generic audit log floor.
 * - `legal-hold`    240 months  — 20 years; indefinite legal hold
 *                    treated as long-retention for sweep safety.
 *
 * A service can override any of these per-class in its own
 * `application.yml` (see `docs/shared/PARTITION_FUNCTIONS.md` §13
 * example) — but cannot shorten `financial` below 84 months without
 * an ADR.
 */
val DEFAULT_RETENTION_CLASSES: List<RetentionClass> = listOf(
    RetentionClass("default", 36),
    RetentionClass("operational", 36),
    RetentionClass("financial", 84),
    RetentionClass("regulatory", 120),
    RetentionClass("audit", 120),
    RetentionClass("legal-hold", 240),
)

/**
 * A single retention-class entry mapping a logical class name
 * (declared on a partitioned parent via `pg_class.reloptions`
 * `retention_class`) to its retention window in whole months.
 *
 * Example Postgres DDL:
 *
 * ```sql
 * ALTER TABLE audit.audit_events SET (retention_class = 'financial');
 * ```
 */
data class RetentionClass(val name: String, val retentionMonths: Int)

/**
 * Configuration for the canonical platform partition maintenance.
 *
 * Properties (application.yml `platform.partition.*`):
 * - [enabled]            master switch; default `true`. When `false`
 *                        neither the cron nor the health indicator is
 *                        activated.
 * - [cron]               cron expression for `ensurePartitions` (default
 *                        `0 0 2 * * *` per ADR-0029 — daily at 02:00 UTC).
 * - [retentionClasses]   ordered list of `name -> retentionMonths`
 *                        mappings used by `dropExpiredPartitions()`. Each
 *                        partitioned parent's `retention_class`
 *                        `pg_class.reloptions` is looked up here; the
 *                        first matching entry wins. If a parent has no
 *                        `retention_class` set (or the value is not in
 *                        the list) the `default` entry is used. See
 *                        `docs/shared/PARTITION_FUNCTIONS.md` §13 for
 *                        the full per-class contract.
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
    val retentionClasses: List<RetentionClass> = DEFAULT_RETENTION_CLASSES,
    val horizonMonths: Int = 12,
    val healthTablePattern: String = ".*",
) {
    /**
     * Resolves the retention window (in months) for a given
     * `retention_class` value read from `pg_class.reloptions`.
     *
     * Lookup order:
     * 1. First entry in [retentionClasses] whose [RetentionClass.name]
     *    matches [className] exactly (case-sensitive, matching the
     *    Postgres `SET (retention_class = '...')` literal).
     * 2. First entry named `"default"`.
     * 3. The first entry (fallback).
     *
     * @param className value of the `retention_class` `reloptions` entry
     *                  on the partitioned parent; may be `null` or blank
     *                  when the parent has no class set.
     * @return retention window in whole months (always positive).
     */
    fun retentionFor(className: String?): Int {
        val byName = retentionClasses.firstOrNull { it.name == className }
        if (byName != null) return byName.retentionMonths
        val defaultEntry = retentionClasses.firstOrNull { it.name == "default" }
        if (defaultEntry != null) return defaultEntry.retentionMonths
        return retentionClasses.firstOrNull()?.retentionMonths ?: 36
    }
}
