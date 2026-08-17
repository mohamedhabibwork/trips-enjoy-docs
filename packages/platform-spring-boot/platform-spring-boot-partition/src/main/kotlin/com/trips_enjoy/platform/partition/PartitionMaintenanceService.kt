package com.trips_enjoy.platform.partition

import javax.sql.DataSource
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Configuration
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service

/**
 * Canonical partition maintenance cron service.
 *
 * Replaces the per-service `PartitionMaintenanceJob.kt` copies that
 * previously existed in 9 of 14 Kotlin services. The behaviour is
 * identical; the only difference is that the parent tables are
 * discovered dynamically from `pg_class` instead of being hard-coded in
 * each service.
 *
 * - [ensurePartitions]          runs daily at 02:00 UTC per ADR-0029.
 * - [dropExpiredPartitions]     runs 30 minutes later (02:30 UTC) so the
 *                               same service row's ensure + drop never
 *                               contend on the advisory lock.
 *
 * Reference:
 * - docs/shared/PARTITION_FUNCTIONS.md
 * - docs/architecture/DATABASE_ARCHITECTURE.md §12
 * - ADR-0029 — daily 02:00 UTC maintenance window
 */
@Service
@ConditionalOnMissingBean(PartitionMaintenanceService::class)
class PartitionMaintenanceService(
    private val dataSource: DataSource,
    private val properties: PartitionProperties,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val jdbcTemplate = JdbcTemplate(dataSource)

    /**
     * Pre-creates the future-month partition window for every partitioned
     * parent owned by this service. Acquires the per-service partition
     * advisory lock before the function call so two replicas don't
     * race.
     */
    @Scheduled(cron = "\${platform.partition.cron:0 0 2 * * *}")
    fun ensurePartitions() {
        if (!properties.enabled) return
        val parents = tablesToMaintain()
        if (parents.isEmpty()) {
            log.debug("platform.partition: no partitioned parents matched pattern {}", properties.healthTablePattern)
            return
        }

        val acquired = runCatching {
            jdbcTemplate.queryForObject(
                "SELECT pg_try_advisory_xact_lock(hashtext('platform'), hashtext('partition'))",
                Boolean::class.java,
            ) ?: false
        }.getOrElse {
            log.warn("platform.partition: failed to acquire advisory lock", it)
            return
        }
        if (!acquired) {
            log.debug("platform.partition: advisory lock busy; skipping ensure step")
            return
        }

        parents.forEach { parent ->
            runCatching {
                val json = jdbcTemplate.queryForObject(
                    "SELECT partman.ensure_partitions(?::REGCLASS, ?)",
                    String::class.java,
                    parent,
                    properties.horizonMonths,
                )
                log.info("platform.partition: ensured {} horizon={}m -> {}", parent, properties.horizonMonths, json)
            }.onFailure { log.error("platform.partition: ensure failed for {}", parent, it) }
        }
    }

    /**
     * Drops partitions whose upper bound is older than the retention
     * window. Runs at 02:30 UTC to avoid overlap with [ensurePartitions].
     */
    @Scheduled(cron = "0 30 2 * * *")
    fun dropExpiredPartitions() {
        if (!properties.enabled) return
        val parents = tablesToMaintain()
        if (parents.isEmpty()) return

        val acquired = runCatching {
            jdbcTemplate.queryForObject(
                "SELECT pg_try_advisory_xact_lock(hashtext('platform'), hashtext('partition-drop'))",
                Boolean::class.java,
            ) ?: false
        }.getOrElse {
            log.warn("platform.partition: failed to acquire drop advisory lock", it)
            return
        }
        if (!acquired) {
            log.debug("platform.partition: drop advisory lock busy; skipping")
            return
        }

        val retention = "${properties.retentionMonths} months"
        parents.forEach { parent ->
            runCatching {
                val json = jdbcTemplate.queryForObject(
                    "SELECT partman.drop_expired_partitions(?::REGCLASS, ?::INTERVAL)",
                    String::class.java,
                    parent,
                    retention,
                )
                log.info("platform.partition: dropped expired ({}) for {} -> {}", retention, parent, json)
            }.onFailure { log.error("platform.partition: drop failed for {}", parent, it) }
        }
    }

    /**
     * Returns the qualified names (`schema.table`) of every partitioned
     * parent owned by the connected database. The result is filtered by
     * [PartitionProperties.healthTablePattern] so a service can scope
     * partition maintenance to a subset of its tables.
     *
     * The default pattern `.*` matches everything.
     */
    internal fun tablesToMaintain(): List<String> {
        val sql = """
            SELECT n.nspname || '.' || c.relname
            FROM pg_class c
            JOIN pg_namespace n ON n.oid = c.relnamespace
            WHERE c.relkind = 'p'
              AND NOT n.nspname IN ('pg_catalog', 'information_schema', 'pg_partman')
              AND NOT c.relispartition
        """.trimIndent()
        val regex = Regex(properties.healthTablePattern)
        return runCatching {
            @Suppress("UNCHECKED_CAST")
            (jdbcTemplate.queryForList(sql, String::class.java) as List<String>)
        }.getOrElse {
            log.warn("platform.partition: failed to enumerate partitioned parents", it)
            emptyList()
        }.filter { regex.matches(it) }
    }
}

/**
 * Scheduling lives on the auto-configuration so the cron is wired only
 * once per application context. Declared here (rather than in
 * [PartitionAutoConfiguration]) so the `@Scheduled` annotations on
 * [PartitionMaintenanceService] are picked up regardless of the order
 * in which Spring instantiates the two beans.
 */
@Configuration
@EnableScheduling
@ConditionalOnMissingBean(name = ["platformPartitionScheduling"])
internal class PartitionSchedulingConfiguration
