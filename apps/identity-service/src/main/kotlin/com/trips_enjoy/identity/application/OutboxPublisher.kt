package com.trips_enjoy.identity.application

import com.trips_enjoy.identity.domain.OutboxEventRepository
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

@Component
class OutboxPublisher(private val events: OutboxEventRepository, private val kafka: KafkaTemplate<String, String>) {
    @Scheduled(fixedDelayString = "\${identity.outbox.publish-interval-ms:1000}")
    @Transactional
    fun publishPending() {
        events.findTop100ByPublishedAtIsNullOrderByCreatedAtAsc().forEach { event ->
            try {
                kafka.send(event.topic, event.aggregateId.toString(), event.payload).get()
                event.publishedAt = Instant.now()
            } catch (exception: Exception) {
                event.attempts += 1
                event.lastError = exception.javaClass.simpleName
            }
        }
    }
}
