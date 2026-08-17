package com.trips_enjoy.audit.application

import com.fasterxml.jackson.databind.ObjectMapper
import com.trips_enjoy.audit.domain.OutboxEvent
import com.trips_enjoy.audit.domain.OutboxEventRepository
import com.trips_enjoy.audit.util.uuidV7
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Duration
import java.time.Instant

/**
 * WORKFLOWS §5 — Daily purge. Per ERD §9 (mixed retention) the job refuses to
 * drop a partition whose upper bound is still inside the financial-retention
 * window even if every row in it has `retention_class='default'`.
 *
 * Implementation strategy: walk monthly child partitions older than the
 * retention window, and DROP only if the entire child contains no
 * `retention_class='financial'` rows AND no `litigation_hold=true` rows.
 * The actual drop is delegated to a native SQL block because JPA repositories
 * cannot easily iterate pg_inherits entries.
 */
@Service
class RetentionService(
    private val jdbc: JdbcTemplate,
    private val outbox: OutboxEventRepository,
    private val objectMapper: ObjectMapper,
    @Value("\${audit-service.retention.financial-years:7}") private val financialYears: Int,
    @Value("\${audit-service.retention.default-years:1}") private val defaultYears: Int,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    data class PurgeReport(
        val scanned: Int,
        val dropped: Int,
        val skippedRetentionHold: Int,
        val skippedFinancialRow: Int,
        val jobId: String,
    )

    @Transactional
    fun runDailyPurge(): PurgeReport {
        val jobId = uuidV7().toString()
        val now = Instant.now()
        val financialCutoff = now.minus(Duration.ofDays(365L * financialYears))
        val defaultCutoff = now.minus(Duration.ofDays(365L * defaultYears))

        val children = jdbc.queryForList(
            """
            SELECT inhrelid::regclass::text AS child,
                   pg_get_expr(c.relpartbound, c.oid) AS bound
            FROM pg_inherits i
            JOIN pg_class c ON c.oid = i.inhrelid
            JOIN pg_class p ON p.oid = i.inhparent
            WHERE p.relname = 'events' AND p.relnamespace = 'audit'::regnamespace
            ORDER BY 1
            """,
        )
        var dropped = 0
        var skippedHold = 0
        var skippedFinancial = 0
        var scanned = 0

        for (row in children) {
            val child = row["child"] as String
            val bound = row["bound"] as? String ?: continue
            scanned += 1
            // Parse upper bound from PARTITION OF ... FOR VALUES FROM (...) TO (...)
            val upper = extractUpperBound(bound) ?: continue
            // Skip children whose upper bound is still inside the financial window.
            if (upper.isAfter(financialCutoff)) continue

            // Refuse to drop if any row is under litigation hold or financial-retention.
            val blockingRows = jdbc.queryForObject(
                """
                SELECT
                  (SELECT COUNT(*) FROM $child WHERE litigation_hold = TRUE) AS holds,
                  (SELECT COUNT(*) FROM $child
                    WHERE retention_class = 'financial'
                      AND (retention_until IS NULL OR retention_until > ?)) AS financial_active
                """,
                Long::class.java,
                now,
            ) ?: 0L

            // For default rows past their 1-year window, drop is allowed if no holds remain.
            val pastDefaultCutoff = !upper.isAfter(defaultCutoff)
            if (!pastDefaultCutoff) {
                // Upper bound older than financial but still inside default window means
                // there are still default rows alive — only drop if every default row is
                // past the default cutoff (which we already know via pastDefaultCutoff).
            }

            // Blocking row check (combined hold + financial).
            if (blockingRows > 0) {
                skippedHold += 1
                log.info("Skip drop of {}: {} blocking rows (holds or financial retention)", child, blockingRows)
                continue
            }
            try {
                jdbc.execute("DROP TABLE IF EXISTS $child")
                dropped += 1
                log.info("Dropped audit partition {}", child)
            } catch (exception: Exception) {
                log.warn("Failed to drop partition {}: {}", child, exception.message)
                skippedFinancial += 1
            }
        }

        emitPurgeCompletedEvent(jobId = jobId, dropped = dropped, scanned = scanned)
        return PurgeReport(scanned, dropped, skippedHold, skippedFinancial, jobId)
    }

    private fun emitPurgeCompletedEvent(jobId: String, dropped: Int, scanned: Int) {
        val envelope = objectMapper.writeValueAsString(
            mapOf(
                "event_id" to uuidV7().toString(),
                "event_name" to "audit.retention.purge_completed.v1",
                "occurred_at" to Instant.now().toString(),
                "schema_version" to 1,
                "producer" to "audit-service",
                "tenant_id" to "global",
                "correlation_id" to jobId,
                "aggregate_type" to "RetentionJob",
                "aggregate_id" to jobId,
                "data" to mapOf(
                    "job_id" to jobId,
                    "scanned" to scanned,
                    "dropped" to dropped,
                ),
            ),
        )
        outbox.save(
            OutboxEvent(
                id = uuidV7(),
                aggregateType = "RetentionJob",
                aggregateId = null,
                topic = "platform.audit.retention",
                eventName = "audit.retention.purge_completed.v1",
                payload = envelope,
            ),
        )
    }

    /**
     * Parse the upper bound of a `FOR VALUES FROM (...) TO (...)` clause and
     * return it as an `Instant`. Returns `null` for DEFAULT partitions.
     */
    private fun extractUpperBound(bound: String): Instant? {
        val regex = Regex("TO \\('([^']+)'\\)")
        val match = regex.find(bound) ?: return null
        val raw = match.groupValues[1]
        // Examples: "2026-07-01 00:00:00+00", "2026-07-01"
        return runCatching {
            java.time.OffsetDateTime.parse(raw.replace(" ", "T")).toInstant()
        }.recoverCatching {
            java.time.LocalDateTime.parse(raw.replace("+00", "Z")).toInstant(java.time.ZoneOffset.UTC)
        }.recoverCatching {
            java.time.LocalDate.parse(raw).atStartOfDay().toInstant(java.time.ZoneOffset.UTC)
        }.getOrNull()
    }
}
