package com.trips_enjoy.audit.application

import com.fasterxml.jackson.databind.ObjectMapper
import com.trips_enjoy.audit.domain.AuditEventRepository
import com.trips_enjoy.audit.domain.OutboxEvent
import com.trips_enjoy.audit.domain.OutboxEventRepository
import com.trips_enjoy.audit.util.uuidV7
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Tags
import io.micrometer.core.instrument.Timer
import org.slf4j.LoggerFactory
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * WORKFLOWS §4 — Daily export. Streams events from yesterday into a JSON
 * blob, hands it to the [S3Exporter] driver, and enqueues an
 * `audit.export.completed.v1` event in the outbox.
 *
 * Metrics: `audit_export_seconds` (Timer; tagged with `status=success|error`)
 * per SRS §22 + README §15.
 */
@Service
class ExportService(
    private val events: AuditEventRepository,
    private val outbox: OutboxEventRepository,
    private val exporter: S3Exporter,
    private val objectMapper: ObjectMapper,
    private val meterRegistry: MeterRegistry,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    private val exportTimer: Timer = Timer.builder("audit_export_seconds")
        .description("Wall-clock seconds spent exporting the daily audit log to S3")
        .publishPercentiles(0.5, 0.95, 0.99)
        .register(meterRegistry)
    private val successCounter: Counter = Counter.builder("audit_export_total")
        .description("Number of daily audit exports completed (per outcome)")
        .tag("status", "success")
        .register(meterRegistry)
    private val errorCounter: Counter = Counter.builder("audit_export_total")
        .description("Number of daily audit exports failed (per outcome)")
        .tag("status", "error")
        .register(meterRegistry)

    data class ExportResult(val s3Path: String, val eventCount: Long, val sizeBytes: Long, val tenantId: String)

    /**
     * @param date the date whose events to export (UTC).
     * @param tenantId the tenant scope to export.
     */
    @Transactional
    fun exportDay(date: LocalDate, tenantId: String): ExportResult {
        return try {
            val result = doExport(date, tenantId)
            successCounter.increment()
            result
        } catch (exception: Exception) {
            errorCounter.increment()
            throw exception
        }
    }

    private fun doExport(date: LocalDate, tenantId: String): ExportResult {
        val sample = Timer.start(meterRegistry)
        try {
            return doExportInner(date, tenantId)
        } finally {
            sample.stop(exportTimer)
        }
    }

    private fun doExportInner(date: LocalDate, tenantId: String): ExportResult {
        val startOfDay = date.atStartOfDay().toInstant(ZoneOffset.UTC)
        val endOfDay = date.plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC)
        val pageable = PageRequest.of(0, EXPORT_PAGE_SIZE, Sort.by(Sort.Direction.ASC, "occurredAt", "id"))
        val rows = events.search(
            topic = null,
            tenantId = tenantId,
            subjectType = null,
            subjectId = null,
            correlationId = null,
            from = startOfDay,
            to = endOfDay,
            pageable = pageable,
        )
        val payload = rows.map { row ->
            mapOf(
                "id" to row.id.toString(),
                "event_id" to row.eventId.toString(),
                "event_name" to row.eventName,
                "occurred_at" to row.occurredAt.toString(),
                "producer" to row.producer,
                "tenant_id" to row.tenantId,
                "correlation_id" to row.correlationId.toString(),
                "aggregate_type" to row.aggregateType,
                "aggregate_id" to row.aggregateId?.toString(),
                "data" to row.data,
                "hash" to row.hash,
                "prev_hash" to row.prevHash,
                "topic" to row.topic,
                "partition" to row.partition,
                "offset" to row.offset,
                "retention_class" to row.retentionClass,
                "litigation_hold" to row.litigationHold,
            )
        }
        val body = objectMapper.writeValueAsString(
            mapOf(
                "tenant_id" to tenantId,
                "date" to date.toString(),
                "event_count" to rows.size.toLong(),
                "generated_at" to Instant.now().toString(),
                "items" to payload,
            ),
        )
        val s3Path = exporter.export(date, tenantId, body)
        val sizeBytes = body.toByteArray().size.toLong()

        // Emit audit.export.completed.v1 (INTEGRATION §3.1)
        val envelope = objectMapper.writeValueAsString(
            mapOf(
                "event_id" to uuidV7().toString(),
                "event_name" to "audit.export.completed.v1",
                "occurred_at" to Instant.now().toString(),
                "schema_version" to 1,
                "producer" to "audit-service",
                "tenant_id" to tenantId,
                "correlation_id" to uuidV7().toString(),
                "aggregate_type" to "AuditExport",
                "aggregate_id" to date.toString(),
                "data" to mapOf(
                    "s3_path" to s3Path,
                    "event_count" to rows.size.toLong(),
                    "size_bytes" to sizeBytes,
                    "date" to date.toString(),
                ),
            ),
        )
        outbox.save(
            OutboxEvent(
                id = uuidV7(),
                aggregateType = "AuditExport",
                aggregateId = null,
                topic = "audit.export.completed",
                eventName = "audit.export.completed.v1",
                payload = envelope,
            ),
        )
        log.info("Exported {} audit events for tenant={} date={} to {}", rows.size, tenantId, date, s3Path)
        return ExportResult(s3Path, rows.size.toLong(), sizeBytes, tenantId)
    }

    companion object {
        // Export is one big JSON per tenant per day; the page size keeps memory
        // bounded even on the busiest days. ~10k rows × ~1KB ≈ 10 MB per page.
        private const val EXPORT_PAGE_SIZE = 10_000
    }
}
