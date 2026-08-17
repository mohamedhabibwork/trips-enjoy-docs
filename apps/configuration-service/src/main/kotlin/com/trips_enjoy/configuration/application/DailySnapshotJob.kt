package com.trips_enjoy.configuration.application

import com.fasterxml.jackson.databind.ObjectMapper
import com.trips_enjoy.configuration.domain.DocumentRepository
import com.trips_enjoy.configuration.domain.OutboxEvent
import com.trips_enjoy.configuration.domain.OutboxRepository
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.UUID

/**
 * Daily snapshot export (FR-015 / INTEGRATION.md §3.4).
 *
 * Walks every active document, writes a JSON dictionary `{key -> {value,
 * version, schemaVersion}}` to the configured local fallback directory
 * (and emits a `configuration.snapshot.exported.v1` event so downstream
 * services can substitute the S3 path once the S3 exporter is wired).
 *
 * The S3 upload is intentionally deferred — the canonical export event
 * captures the path so an out-of-band process can pick it up.
 */
@Component
class DailySnapshotJob(
    private val documentRepository: DocumentRepository,
    private val outboxRepository: OutboxRepository,
    private val mapper: ObjectMapper,
    @Value("\${configuration-service.snapshot.local-fallback-dir:/tmp/configuration-snapshots}")
    private val localDir: String,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(cron = "\${configuration-service.snapshot.cron:0 0 3 * * *}")
    @Transactional
    fun exportSnapshot() {
        val today = LocalDate.now()
        val timestamp = today.format(DateTimeFormatter.ISO_LOCAL_DATE)
        val outDir: Path = Paths.get(localDir, today.year.toString(), "%02d".format(today.monthValue))
        try {
            Files.createDirectories(outDir)
        } catch (e: Exception) {
            log.error("Cannot create snapshot dir {}: {}", outDir, e.message)
            return
        }
        val outFile = outDir.resolve("$timestamp-global.json")
        val documents = documentRepository.findAll()
        val payload =
            documents.associate { doc ->
                doc.key to
                    mapOf(
                        "value" to doc.value?.let { mapper.readTree(it) },
                        "version" to doc.currentVersion,
                        "value_type" to doc.valueType,
                        "schema_id" to doc.schemaId.toString(),
                        "deactivated" to (doc.deactivatedAt != null),
                    )
            }
        try {
            Files.writeString(outFile, mapper.writeValueAsString(payload))
        } catch (e: Exception) {
            log.error("Failed to write snapshot to {}: {}", outFile, e.message)
            return
        }
        val s3Path =
            "s3://trips-enjoy-platform-audit/configuration/snapshots/${today.year}/%02d/%02d/$timestamp-global.json"
                .format(
                    today.monthValue,
                    today.dayOfMonth,
                )
        val eventId = UUID.randomUUID()
        val eventPayload =
            mapper.writeValueAsString(
                mapOf(
                    "event_id" to eventId.toString(),
                    "event_name" to "configuration.snapshot.exported.v1",
                    "occurred_at" to Instant.now().toString(),
                    "schema_version" to 1,
                    "producer" to "configuration-service",
                    "tenant_id" to "global",
                    "correlation_id" to eventId.toString(),
                    "causation_id" to null,
                    "aggregate_type" to "ConfigurationSnapshot",
                    "aggregate_id" to today.toString(),
                    "data" to
                        mapOf(
                            "s3_path" to s3Path,
                            "key_count" to documents.size,
                        ),
                ),
            )
        outboxRepository.save(
            OutboxEvent(
                id = UUID.randomUUID(),
                topic = "configuration.snapshot.exported",
                eventId = eventId,
                payload = eventPayload,
            ),
        )
        log.info("Exported configuration snapshot to {} ({} keys)", outFile, documents.size)
    }
}
