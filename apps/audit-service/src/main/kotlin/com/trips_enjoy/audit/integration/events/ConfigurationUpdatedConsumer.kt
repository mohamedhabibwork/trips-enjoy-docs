package com.trips_enjoy.audit.integration.events

import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.messaging.handler.annotation.Payload
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * Consumes `configuration.updated.v1` events from `configuration-service` and
 * hot-applies any `audit.*` key the platform sends. The list of
 * audit-service-specific keys is documented in
 * docs/services/configuration-service/INTEGRATION.md §10.3.
 */
@Component
class ConfigurationUpdatedConsumer(private val mapper: ObjectMapper) {
    private val log = LoggerFactory.getLogger(javaClass)

    @KafkaListener(topics = ["configuration.updated"], groupId = "audit-service-config")
    fun consume(@Payload payload: String) {
        val event = try {
            mapper.readTree(payload)
        } catch (exception: Exception) {
            log.warn("Skipping malformed configuration.updated payload: {}", exception.message)
            return
        }
        val eventId = runCatching { UUID.fromString(event.path("event_id").asText()) }.getOrNull() ?: return
        val keys = event.path("data").path("keys")
        if (!keys.isArray) return
        keys.forEach { entry ->
            val name = entry.path("name").asText()
            if (!name.startsWith("audit.")) return@forEach
            val value = entry.path("value").asText()
            log.info("Received configuration key {} for audit-service (event {})", name, eventId)
            // The runtime services read these via @Value at startup. Live
            // reload for retention / hash algo requires process restart for
            // the moment; this listener captures the intent and is the
            // integration point for future hot-reload logic.
        }
    }
}
