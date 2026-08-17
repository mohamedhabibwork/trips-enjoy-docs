package com.trips_enjoy.platform.partition

import java.time.Instant
import javax.sql.DataSource
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.health.contributor.Health
import org.springframework.boot.health.contributor.HealthIndicator
import org.springframework.boot.health.contributor.Status
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component

/**
 * Aggregates partition health for every partitioned parent owned by
 * this service and exposes the result at `/actuator/health/partitions`.
 *
 * Status mapping:
 * - `UP`        every parent has at least one future partition AND
 *               `today_missing` is `false` AND no row counts exist in
 *               dropped partition references.
 * - `DEGRADED`  one or more parents are missing a future partition
 *               window, but still has *some* partition covering
 *               `now()`.
 * - `DOWN`      one or more parents have `today_missing = true` OR
 *               zero matching children.
 *
 * Each parent contributes one entry to `details.<schema.table>` with
 * the raw `partman.partition_health(...)` columns plus a derived
 * `expected_retention_gap` so on-call can spot retention gaps without
 * recalculating.
 */
@Component("partitions")
@ConditionalOnMissingBean(name = ["partitions"])
class PartitionHealthIndicator(
    private val dataSource: DataSource,
    private val properties: PartitionProperties,
) : HealthIndicator {
    private val log = LoggerFactory.getLogger(javaClass)
    private val jdbcTemplate = JdbcTemplate(dataSource)

    override fun health(): Health {
        if (!properties.enabled) {
            return Health.status(Status.UP)
                .withDetail("enabled", false)
                .withDetail("note", "platform.partition.enabled=false; partition monitoring disabled")
                .build()
        }

        val parents = runCatching { tablesToMonitor() }
            .getOrElse {
                log.warn("platform.partition.health: failed to enumerate parents", it)
                return Health.down()
                    .withDetail("error", it.message ?: it.javaClass.simpleName)
                    .build()
            }

        if (parents.isEmpty()) {
            return Health.up()
                .withDetail("match_count", 0)
                .withDetail("note", "no partitioned parents matched health-table-pattern")
                .build()
        }

        var downCount = 0
        var degradedCount = 0
        val builder = Health.up()
        parents.forEach { parent ->
            val detail = runCatching { fetchHealth(parent) }
                .getOrElse {
                    log.warn("platform.partition.health: {} failed", parent, it)
                    downCount += 1
                    mapOf(
                        "parent" to parent,
                        "error" to (it.message ?: it.javaClass.simpleName),
                    )
                }
            applyDetail(builder, detail, parent)
            when (statusOf(detail)) {
                Status.DOWN -> downCount += 1
                Status.OUT_OF_SERVICE -> degradedCount += 1
                else -> { /* up */ }
            }
        }
        return when {
            downCount > 0 -> builder.down().withDetail("down_count", downCount).withDetail("degraded_count", degradedCount).build()
            degradedCount > 0 -> builder.status(Status.OUT_OF_SERVICE)
                .withDetail("down_count", downCount)
                .withDetail("degraded_count", degradedCount)
                .build()
            else -> builder.withDetail("down_count", 0).withDetail("degraded_count", 0).build()
        }
    }

    private fun applyDetail(builder: Health.Builder, detail: Map<String, Any?>, parent: String) {
        val sanitizedKey = parent.replace('.', '_')
        detail.forEach { (k, v) ->
            if (v != null) builder.withDetail("${sanitizedKey}.$k", v)
        }
    }

    private fun statusOf(detail: Map<String, Any?>): Status {
        if (detail.containsKey("error")) return Status.DOWN
        val todayMissing = detail["today_missing"] as? Boolean ?: return Status.DOWN
        val currentCount = (detail["current_count"] as? Number)?.toInt() ?: -1
        val futureCount = (detail["future_count"] as? Number)?.toInt() ?: -1
        return when {
            todayMissing || currentCount <= 0 -> Status.DOWN
            futureCount <= 0 -> Status.OUT_OF_SERVICE
            else -> Status.UP
        }
    }

    private fun fetchHealth(parent: String): Map<String, Any?> {
        val row = jdbcTemplate.queryForMap(
            "SELECT * FROM partman.partition_health(?::REGCLASS)",
            parent,
        )
        val now = Instant.now()
        val oldestPastLower = row["oldest_past_lower"] as? Instant
        val expectedRetentionGap = oldestPastLower?.let {
            java.time.Duration.between(it, now).toDays()
        }
        return mapOf(
            "parent" to parent,
            "current_count" to row["current_count"],
            "future_count" to row["future_count"],
            "past_count" to row["past_count"],
            "today_missing" to row["today_missing"],
            "oldest_past_lower" to oldestPastLower,
            "expected_retention_gap_days" to expectedRetentionGap,
        )
    }

    private fun tablesToMonitor(): List<String> {
        val sql = """
            SELECT n.nspname || '.' || c.relname
            FROM pg_class c
            JOIN pg_namespace n ON n.oid = c.relnamespace
            WHERE c.relkind = 'p'
              AND NOT n.nspname IN ('pg_catalog', 'information_schema', 'pg_partman')
              AND NOT c.relispartition
        """.trimIndent()
        val regex = Regex(properties.healthTablePattern)
        @Suppress("UNCHECKED_CAST")
        val rows = jdbcTemplate.queryForList(sql, String::class.java) as List<String>
        return rows.filter { regex.matches(it) }
    }
}
