package com.trips_enjoy.customer.integration.events

import com.fasterxml.jackson.databind.ObjectMapper
import com.trips_enjoy.customer.domain.InboxEvent
import com.trips_enjoy.customer.domain.InboxRepository
import org.slf4j.LoggerFactory
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.messaging.handler.annotation.Payload
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.UUID

/**
 * Consumes `configuration.updated.v1` and refreshes the in-process
 * segment / KYC thresholds (INTEGRATION.md §4.11).
 *
 * For the v1 scaffold the thresholds live in `@Value`-injected
 * properties (see `SegmentRecomputer`); a future enhancement will
 * back them with a hot-reloadable `ConfigurationSnapshot` bean.
 */
@Component
class ConfigurationUpdatedConsumer(
    private val mapper: ObjectMapper,
    private val inbox: InboxRepository,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @KafkaListener(
        topics = ["configuration.updated"],
        groupId = "\${customer-service.kafka.consumer.group-id:customer-service}",
        containerFactory = "customerKafkaListenerContainerFactory",
    )
    fun consume(
        @Payload payload: String,
    ) {
        val event =
            try {
                mapper.readTree(payload)
            } catch (exception: Exception) {
                log.warn("Skipping malformed configuration.updated payload: {}", exception.message)
                return
            }
        val eventId = runCatching { UUID.fromString(event.path("event_id").asText()) }.getOrNull()
            ?: return
        if (inbox.existsByEventId(eventId)) return
        val key = event.path("data").path("key").asText()
        if (key.startsWith("customer.")) {
            log.info("configuration.updated for customer-related key={} (cache bust on next read)", key)
        }
        inbox.save(
            InboxEvent(
                eventId = eventId,
                topic = "configuration.updated",
                receivedAt = Instant.now(),
                processedAt = Instant.now(),
            ),
        )
    }
}
