package com.trips_enjoy.customer.application

import com.trips_enjoy.customer.domain.OutboxRepository
import org.slf4j.LoggerFactory
import org.springframework.data.domain.PageRequest
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

/**
 * Outbox poller — publishes unpublished events to Kafka and marks
 * `published_at`. On failure, increments `attempts` and stores the
 * error; the third failure routes the event to `<topic>.dlq` via Spring
 * Kafka's configured error handler.
 *
 * Mirrors the pattern used by audit-service and configuration-service.
 */
@Component
class OutboxPublisher(
    private val outboxRepository: OutboxRepository,
    private val kafka: KafkaTemplate<String, String>,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(fixedDelayString = "\${customer-service.outbox.publish-interval-ms:1000}")
    @Transactional
    fun publishPending() {
        val pending = outboxRepository.findTop100ByPublishedAtIsNullOrderByCreatedAtAsc(PageRequest.of(0, 100))
        if (pending.isEmpty()) return
        for (event in pending) {
            try {
                val partitionKey = event.id.toString()
                kafka.send(event.topic, partitionKey, event.payload).get()
                event.publishedAt = Instant.now()
            } catch (exception: Exception) {
                event.attempts += 1
                event.lastError = exception.javaClass.simpleName + ": " + exception.message
                log.warn(
                    "Failed to publish outbox event {} to topic {}: {}",
                    event.id,
                    event.topic,
                    exception.message,
                )
                if (event.attempts >= 3) {
                    try {
                        kafka.send(event.topic + ".dlq", event.id.toString(), event.payload).get()
                        event.publishedAt = Instant.now()
                    } catch (dlqException: Exception) {
                        log.error(
                            "Failed to DLQ outbox event {}: {}",
                            event.id,
                            dlqException.message,
                        )
                    }
                }
            }
        }
    }
}
