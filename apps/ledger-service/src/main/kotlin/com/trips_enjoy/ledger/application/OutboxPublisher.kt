package com.trips_enjoy.ledger.application

import com.trips_enjoy.ledger.domain.OutboxEventRepository
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.data.domain.PageRequest
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.time.Duration as JDuration

/**
 * Polls unpublished outbox rows and ships them to Kafka. Mirrors the
 * audit-service / identity-service pattern.
 */
@Component
class OutboxPublisher(
    private val events: OutboxEventRepository,
    private val kafka: KafkaTemplate<String, String>,
    private val meters: MeterRegistry,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(fixedDelayString = "\${ledger-service.outbox.publish-interval-ms:1000}")
    @Transactional
    fun publishPending() {
        val pending = events.findTop100ByPublishedAtIsNullOrderByCreatedAtAsc(
            PageRequest.of(0, 100),
        )
        if (pending.isEmpty()) return

        val unpublishedAge = pending.minOfOrNull { it.createdAt }?.let {
            JDuration.between(it, Instant.now()).toSeconds()
        } ?: 0L
        meters.gauge("ledger_outbox_oldest_unpublished_seconds", unpublishedAge.toDouble())

        pending.forEach { event ->
            try {
                kafka.send(event.topic, event.aggregateId?.toString() ?: event.id.toString(), event.payload).get()
                event.publishedAt = Instant.now()
            } catch (exception: Exception) {
                event.attempts += 1
                event.lastError = exception.javaClass.simpleName + ": " + exception.message
                log.warn("Failed to publish outbox event {} to topic {}: {}",
                    event.id, event.topic, exception.message)
            }
        }
    }
}
