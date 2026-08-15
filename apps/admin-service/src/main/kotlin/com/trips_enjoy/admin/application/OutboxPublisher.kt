package com.trips_enjoy.admin.application

import com.trips_enjoy.admin.domain.OutboxEvent
import com.trips_enjoy.admin.domain.repositories.OutboxEventRepository
import org.slf4j.LoggerFactory
import org.springframework.data.domain.PageRequest
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Duration
import java.time.Instant

@Component
class OutboxPublisher(
    private val outboxRepository: OutboxEventRepository,
    private val kafkaTemplate: KafkaTemplate<String, Map<String, Any?>>,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(fixedDelayString = "\${admin.outbox.poll-interval-ms:200}")
    @Transactional
    fun poll() {
        val pending = outboxRepository.findPending(
            now = Instant.now(),
            pageable = PageRequest.of(0, 100),
        )
        if (pending.isEmpty()) return

        for (event in pending) {
            try {
                kafkaTemplate.send(event.topic, event.aggregateId.toString(), event.payload)
                event.markPublished(Instant.now())
            } catch (e: Exception) {
                val backoff = nextBackoff(event.attempts + 1)
                event.markFailed(e.message ?: e.javaClass.simpleName, Instant.now().plus(backoff))
                log.warn("outbox publish failed (event={}, attempt={}): {}", event.id, event.attempts + 1, e.message)
            }
        }
    }

    private fun nextBackoff(attempt: Int): Duration {
        val seconds = minOf(300L, 1L shl minOf(attempt - 1, 9))
        return Duration.ofSeconds(seconds)
    }
}