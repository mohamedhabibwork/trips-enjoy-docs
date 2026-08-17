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
 * Consumes `feature_flag.updated.v1` and invalidates the affected key
 * under `cache:feature_flag:<key>:*` (INTEGRATION.md §4.6).
 */
@Component
class FeatureFlagUpdatedConsumer(
    private val mapper: ObjectMapper,
    private val inbox: InboxRepository,
    private val redis: StringRedisTemplate,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @KafkaListener(
        topics = ["feature_flag.updated"],
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
                log.warn("Skipping malformed feature_flag.updated payload: {}", exception.message)
                return
            }
        val eventId = runCatching { UUID.fromString(event.path("event_id").asText()) }.getOrNull() ?: return
        if (inbox.existsByEventId(eventId)) return
        val key = event.path("data").path("key").asText()
        if (key.isBlank()) {
            log.warn("feature_flag.updated missing key, eventId={}", eventId)
            return
        }
        val pattern = "cache:feature_flag:$key:*"
        val keys = redis.keys(pattern)
        if (keys.isNotEmpty()) {
            redis.delete(keys)
            log.info("Invalidated {} cache entries for feature flag={}", keys.size, key)
        }
        recordInbox(eventId, "feature_flag.updated")
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
