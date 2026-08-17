package com.trips_enjoy.configuration.integration.events

import com.fasterxml.jackson.databind.ObjectMapper
import com.trips_enjoy.configuration.application.ConfigurationIngestService
import com.trips_enjoy.configuration.domain.InboxEvent
import com.trips_enjoy.configuration.domain.InboxRepository
import org.slf4j.LoggerFactory
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.messaging.handler.annotation.Payload
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.UUID

/**
 * Consumes `admin.configuration.rollback_requested.v1` and calls the
 * ConfigurationIngestService.rollback() pipeline (INTEGRATION.md §4.5).
 *
 * The actor is the admin who triggered the rollback (extracted from the
 * event payload). The correlation_id is propagated.
 */
@Component
class AdminConfigurationRollbackRequestedConsumer(
    private val mapper: ObjectMapper,
    private val inbox: InboxRepository,
    private val ingestService: ConfigurationIngestService,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @KafkaListener(
        topics = ["admin.configuration.rollback_requested"],
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
                log.warn("Skipping malformed admin.configuration.rollback_requested payload: {}", exception.message)
                return
            }
        val eventId = runCatching { UUID.fromString(event.path("event_id").asText()) }.getOrNull() ?: return
        if (inbox.existsByEventId(eventId)) return
        val data = event.path("data")
        val key = data.path("key").asText()
        val toVersion = data.path("to_version").asLong()
        val reason = data.path("reason").asText("Rollback requested via admin event")
        val actorId = runCatching { UUID.fromString(data.path("actor_id").asText()) }.getOrNull() ?: UUID(0, 0)
        val correlationId =
            runCatching { UUID.fromString(event.path("correlation_id").asText()) }.getOrNull()
                ?: UUID.randomUUID()
        try {
            ingestService.rollback(
                key = key,
                toVersion = toVersion,
                reason = reason,
                actorId = actorId,
                actorIp = null,
                correlationId = correlationId,
            )
            log.info("Rolled back key={} to version={} via admin event {}", key, toVersion, eventId)
        } catch (e: Exception) {
            log.error("Failed to rollback key={} via admin event: {}", key, e.message)
        }
        recordInbox(eventId, "admin.configuration.rollback_requested")
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
