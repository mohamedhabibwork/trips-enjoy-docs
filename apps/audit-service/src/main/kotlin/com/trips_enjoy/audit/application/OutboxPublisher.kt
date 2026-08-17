package com.trips_enjoy.audit.application

import com.trips_enjoy.audit.domain.OutboxEventRepository
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import jakarta.annotation.PostConstruct
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.concurrent.atomic.AtomicLong

/**
 * Mirrors identity-service's OutboxPublisher. Polls unpublished outbox rows,
 * sends each to Kafka, and updates `published_at`. Failures bump `attempts`
 * and capture `last_error` so the operational dashboards can alert.
 *
 * Metrics: `audit_outbox_oldest_unpublished_seconds` (gauge) — the age in
 * seconds of the oldest unpublished row. Used by
 * `apps/audit-service/monitoring/audit-service-alerts.yaml`
 * (AuditServiceOutboxLag).
 */
@Component
class OutboxPublisher(
    private val events: OutboxEventRepository,
    private val jdbc: JdbcTemplate,
    private val kafka: KafkaTemplate<String, String>,
    private val meterRegistry: MeterRegistry,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    private val oldestUnpublishedSeconds = AtomicLong(0)

    @PostConstruct
    fun registerGauge() {
        Gauge.builder("audit_outbox_oldest_unpublished_seconds") { oldestUnpublishedSeconds.get().toDouble() }
            .description("Age in seconds of the oldest unpublished audit outbox row")
            .register(meterRegistry)
    }

    @Scheduled(fixedDelayString = "\${audit-service.outbox.publish-interval-ms:1000}")
    @Transactional
    fun publishPending() {
        val pending = events.findTop100ByPublishedAtIsNullOrderByCreatedAtAsc()
        // Update lag gauge from the database (cheap — uses the partition-
        // pruned `created_at` index on the outbox table).
        oldestUnpublishedSeconds.set(currentOldestLagSeconds())
        if (pending.isEmpty()) return
        pending.forEach { event ->
            try {
                kafka.send(event.topic, event.aggregateId?.toString() ?: event.id.toString(), event.payload).get()
                event.publishedAt = Instant.now()
            } catch (exception: Exception) {
                event.attempts += 1
                event.lastError = exception.javaClass.simpleName + ": " + exception.message
                log.warn("Failed to publish outbox event {} to topic {}: {}", event.id, event.topic, exception.message)
            }
        }
    }

    private fun currentOldestLagSeconds(): Long {
        return try {
            val oldest: java.sql.Timestamp? = jdbc.queryForObject(
                "SELECT MIN(created_at) FROM audit.outbox WHERE published_at IS NULL",
                java.sql.Timestamp::class.java,
            )
            oldest?.let { (Instant.now().toEpochMilli() - it.time) / 1000 } ?: 0L
        } catch (_: Exception) {
            0L
        }
    }
}
