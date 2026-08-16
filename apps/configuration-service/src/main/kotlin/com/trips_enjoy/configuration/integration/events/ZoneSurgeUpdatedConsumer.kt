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
 * Consumes `zone.surge.updated.v1` and invalidates per-zone override
 * caches under `cache:zone:<zone_id>:*` (INTEGRATION.md §4.3).
 */
@Component
class ZoneSurgeUpdatedConsumer(
    private val mapper: ObjectMapper,
    private val inbox: InboxRepository,
    private val redis: StringRedisTemplate,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @KafkaListener(
        topics = ["zone.surge.updated"],
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
                log.warn("Skipping malformed zone.surge.updated payload: {}", exception.message)
                return
            }
        val eventId = runCatching { UUID.fromString(event.path("event_id").asText()) }.getOrNull() ?: return
        if (inbox.existsByEventId(eventId)) return
        val zoneId = event.path("data").path("zone_id").asText()
        if (zoneId.isBlank()) {
            log.warn("zone.surge.updated missing zone_id, eventId={}", eventId)
            return
        }
        val pattern = "cache:zone:$zoneId:*"
        val keys = redis.keys(pattern)
        if (keys.isNotEmpty()) {
            redis.delete(keys)
            log.info("Invalidated {} cache entries for zone={} on surge update", keys.size, zoneId)
        }
        recordInbox(eventId, "zone.surge.updated")
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
