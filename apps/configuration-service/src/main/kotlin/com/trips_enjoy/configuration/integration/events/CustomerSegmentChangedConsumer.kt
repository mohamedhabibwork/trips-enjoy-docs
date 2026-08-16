package com.trips_enjoy.configuration.integration.events

import com.fasterxml.jackson.databind.ObjectMapper
import com.trips_enjoy.configuration.domain.InboxEvent
import com.trips_enjoy.configuration.domain.InboxRepository
import org.slf4j.LoggerFactory
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.messaging.handler.annotation.Payload
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.UUID

/**
 * Consumes `customer.segment.changed.v1` and invalidates per-user config
 * caches under `cache:user:<user_id>:*` (INTEGRATION.md §4.1).
 *
 * Dedup is via the configuration.inbox table; a redelivery is a no-op.
 */
@Component
class CustomerSegmentChangedConsumer(
    private val mapper: ObjectMapper,
    private val inbox: InboxRepository,
    private val redis: StringRedisTemplate,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @KafkaListener(
        topics = ["customer.segment.changed"],
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
                log.warn("Skipping malformed customer.segment.changed payload: {}", exception.message)
                return
            }
        val eventId = runCatching { UUID.fromString(event.path("event_id").asText()) }.getOrNull() ?: return
        if (inbox.existsByEventId(eventId)) {
            log.debug("Skipping already-processed event {}", eventId)
            return
        }
        val userId = event.path("data").path("user_id").asText()
        if (userId.isBlank()) {
            log.warn("customer.segment.changed missing user_id, eventId={}", eventId)
            return
        }
        val pattern = "cache:user:$userId:*"
        val keys = redis.keys(pattern)
        if (keys.isNotEmpty()) {
            redis.delete(keys)
            log.info("Invalidated {} cache entries for user={} on segment change", keys.size, userId)
        }
        recordInbox(eventId, "customer.segment.changed")
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
