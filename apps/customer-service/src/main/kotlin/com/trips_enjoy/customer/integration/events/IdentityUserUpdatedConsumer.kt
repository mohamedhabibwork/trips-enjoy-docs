package com.trips_enjoy.customer.integration.events

import com.fasterxml.jackson.databind.ObjectMapper
import com.trips_enjoy.customer.application.CustomerWriteService
import com.trips_enjoy.customer.domain.InboxEvent
import com.trips_enjoy.customer.domain.InboxRepository
import org.slf4j.LoggerFactory
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.messaging.handler.annotation.Payload
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.UUID

/**
 * Consumes `identity.user.updated.v1` and refreshes the cached claims
 * (INTEGRATION.md §4.2).
 */
@Component
class IdentityUserUpdatedConsumer(
    private val mapper: ObjectMapper,
    private val inbox: InboxRepository,
    private val writeService: CustomerWriteService,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @KafkaListener(
        topics = ["identity.user.updated"],
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
                log.warn("Skipping malformed identity.user.updated payload: {}", exception.message)
                return
            }
        val eventId = runCatching { UUID.fromString(event.path("event_id").asText()) }.getOrNull()
            ?: return
        if (inbox.existsByEventId(eventId)) return
        val data = event.path("data")
        val identityId = runCatching { UUID.fromString(data.path("identity_id").asText()) }.getOrNull()
            ?: return
        val name = data.path("name").asText(null).takeIf { it.isNullOrBlank().not() }
        val email = data.path("email").asText(null).takeIf { it.isNullOrBlank().not() }
        val phone = data.path("phone").asText(null).takeIf { it.isNullOrBlank().not() }
        val primaryCityId = runCatching { UUID.fromString(data.path("primary_city_id").asText()) }.getOrNull()
        val correlationId = runCatching {
            UUID.fromString(event.path("correlation_id").asText())
        }.getOrNull() ?: UUID.randomUUID()
        runCatching {
            writeService.upsertFromIdentity(
                identityId = identityId,
                name = name,
                email = email,
                phone = phone,
                primaryCityId = primaryCityId,
                actorId = identityId,
                correlationId = correlationId,
            )
        }.onFailure { log.warn("identity.user.updated upsert failed for {}: {}", identityId, it.message) }
        inbox.save(
            InboxEvent(
                eventId = eventId,
                topic = "identity.user.updated",
                receivedAt = Instant.now(),
                processedAt = Instant.now(),
            ),
        )
    }
}
