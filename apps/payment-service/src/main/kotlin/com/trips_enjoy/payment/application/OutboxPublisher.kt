package com.trips_enjoy.payment.application

import com.trips_enjoy.payment.domain.OutboxEvent
import com.trips_enjoy.payment.domain.OutboxEventRepository
import org.slf4j.LoggerFactory
import org.springframework.data.domain.PageRequest
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Duration
import java.time.Instant

/**
 * The transactional outbox publisher — polls `payment.outbox_events`
 * every 200ms, publishes to Kafka, marks `published_at`. Lifted from
 * audit-service / ledger-service / notification-service / customer-service.
 *
 * On publish failure, increments `attempts`, sets `last_error`, and
 * schedules the next attempt with exponential backoff up to 5 minutes.
 * Records older than 7 days are deleted by `OutboxCleanupJob`.
 */
@Component
class OutboxPublisher(
    private val outboxRepository: OutboxEventRepository,
    private val kafkaTemplate: KafkaTemplate<String, Map<String, Any?>>,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(fixedDelayString = "\${payment.outbox.poll-interval-ms:200}")
    @Transactional
    fun poll() {
        val pending = outboxRepository.findPending(
            now = Instant.now(),
            pageable = PageRequest.of(0, 100),
        )
        if (pending.isEmpty()) return

        for (event in pending) {
            try {
                publish(event)
                event.markPublished(Instant.now())
            } catch (e: Exception) {
                val backoff = nextBackoff(event.attempts + 1)
                event.markFailed(e.message ?: e.javaClass.simpleName, Instant.now().plus(backoff))
                log.warn("outbox publish failed (event={}, attempt={}): {}", event.id, event.attempts + 1, e.message)
            }
        }
    }

    private fun publish(event: OutboxEvent) {
        // Use the aggregate id as the partition key so per-aggregate order
        // is preserved on the consumer side.
        kafkaTemplate.send(event.topic, event.aggregateId.toString(), event.payload)
    }

    private fun nextBackoff(attempt: Int): Duration {
        val seconds = minOf(300L, 1L shl minOf(attempt - 1, 8))
        return Duration.ofSeconds(seconds)
    }
}