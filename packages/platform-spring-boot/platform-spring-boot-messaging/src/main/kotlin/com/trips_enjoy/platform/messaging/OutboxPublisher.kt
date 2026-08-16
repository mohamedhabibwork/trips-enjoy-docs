package com.trips_enjoy.platform.messaging

import org.springframework.data.domain.PageRequest
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

interface OutboxRepository : JpaRepository<OutboxEvent, UUID> {
    @Query("SELECT o FROM OutboxEvent o WHERE o.publishedAt IS NULL ORDER BY o.createdAt ASC")
    fun findUnpublished(pageable: PageRequest): List<OutboxEvent>
}

@Component
open class OutboxPublisher(
    private val outboxRepository: OutboxRepository,
    private val kafkaTemplate: KafkaTemplate<String, String>,
) {

    @Scheduled(fixedDelayString = "\${platform.messaging.outbox-interval-ms:100}")
    @Transactional
    open fun publish() {
        val batch = outboxRepository.findUnpublished(PageRequest.of(0, 200))
        for (event in batch) {
            try {
                val key = event.aggregateId ?: event.id?.toString() ?: event.eventName
                kafkaTemplate.send(event.topic, key, event.payload).get()
                event.publishedAt = Instant.now()
                event.attempts += 1
                event.lastError = null
            } catch (e: Exception) {
                event.attempts += 1
                event.lastError = e.message?.take(2000)
            }
        }
    }
}
