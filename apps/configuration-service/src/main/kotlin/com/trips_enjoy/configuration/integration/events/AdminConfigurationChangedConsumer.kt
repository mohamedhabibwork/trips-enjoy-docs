package com.trips_enjoy.configuration.integration.events

import com.fasterxml.jackson.databind.ObjectMapper
import com.trips_enjoy.configuration.domain.InboxEvent
import com.trips_enjoy.configuration.domain.InboxRepository
import org.slf4j.LoggerFactory
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.messaging.handler.annotation.Payload
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.UUID

/**
 * Consumes `admin.configuration.changed.v1` (INTEGRATION.md §4.4).
 *
 * The audit-service is the eventual sink for cross-service audit events;
 * this consumer's job is to log the event for observability and keep
 * the inbox dedup record. Finer-grained handlers (cache invalidation,
 * reload) live in the per-domain services.
 */
@Component
class AdminConfigurationChangedConsumer(
    private val mapper: ObjectMapper,
    private val inbox: InboxRepository,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @KafkaListener(
        topics = ["admin.configuration.changed"],
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
                log.warn("Skipping malformed admin.configuration.changed payload: {}", exception.message)
                return
            }
        val eventId = runCatching { UUID.fromString(event.path("event_id").asText()) }.getOrNull() ?: return
        if (inbox.existsByEventId(eventId)) return
        val key = event.path("data").path("key").asText()
        log.info("Observed admin-side configuration change for key={} (event {})", key, eventId)
        recordInbox(eventId, "admin.configuration.changed")
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
