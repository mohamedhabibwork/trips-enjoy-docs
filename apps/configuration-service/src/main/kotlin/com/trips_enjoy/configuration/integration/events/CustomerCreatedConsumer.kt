package com.trips_enjoy.configuration.integration.events

import com.fasterxml.jackson.databind.ObjectMapper
import com.trips_enjoy.configuration.domain.InboxEvent
import com.trips_enjoy.configuration.domain.InboxRepository
import org.slf4j.LoggerFactory
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.messaging.handler.annotation.Payload
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * Consumes `customer.created.v1` and pre-warms the per-user config cache
 * with a sentinel entry (INTEGRATION.md §4.2).
 */
@Component
class CustomerCreatedConsumer(
    private val mapper: ObjectMapper,
    private val inbox: InboxRepository,
    private val redis: StringRedisTemplate,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @KafkaListener(
        topics = ["customer.created"],
        groupId = "\${configuration-service.kafka.consumer.group-id:configuration-service}",
        containerFactory = "configurationKafkaListenerContainerFactory",
    )
    fun consume(
        @Payload payload: String,
    ) {
        val event =
            try {
                mapper.readTree(payload)
            } catch (exception: Exception) {
                log.warn("Skipping malformed customer.created payload: {}", exception.message)
                return
            }
        val eventId = runCatching { UUID.fromString(event.path("event_id").asText()) }.getOrNull() ?: return
        if (inbox.existsByEventId(eventId)) return
        val customerId = event.path("data").path("customer_id").asText()
        if (customerId.isBlank()) {
            log.warn("customer.created missing customer_id, eventId={}", eventId)
            return
        }
        // Insert a sentinel so the per-user override path is pre-warmed.
        redis.opsForValue().set(
            "cache:user:$customerId:prewarm",
            "true",
            Duration.ofHours(24),
        )
        log.info("Pre-warmed per-user cache for customer={}", customerId)
        recordInbox(eventId, "customer.created")
    }

    private fun recordInbox(
        eventId: UUID,
        topic: String,
    ) {
        inbox.save(
            InboxEvent(
                eventId = eventId,
                topic = topic,
                receivedAt = Instant.now(),
                processedAt = Instant.now(),
            ),
        )
    }
}
