package com.trips_enjoy.customer.integration.events

import com.fasterxml.jackson.databind.ObjectMapper
import com.trips_enjoy.customer.application.LoyaltyAccountService
import com.trips_enjoy.customer.domain.InboxEvent
import com.trips_enjoy.customer.domain.InboxRepository
import org.slf4j.LoggerFactory
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.messaging.handler.annotation.Payload
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.UUID

/**
 * Consumes `loyalty.tier.changed.v1` from `pricing-service` (per
 * README §A.4) and updates the loyalty account's projected tier.
 */
@Component
class LoyaltyTierChangedConsumer(
    private val mapper: ObjectMapper,
    private val inbox: InboxRepository,
    private val loyaltyAccountService: LoyaltyAccountService,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @KafkaListener(
        topics = ["loyalty.tier.changed"],
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
                log.warn("Skipping malformed loyalty.tier.changed payload: {}", exception.message)
                return
            }
        val eventId = runCatching { UUID.fromString(event.path("event_id").asText()) }.getOrNull()
            ?: return
        if (inbox.existsByEventId(eventId)) return
        val data = event.path("data")
        val customerId = runCatching { UUID.fromString(data.path("customer_id").asText()) }.getOrNull()
        val newTier = data.path("new_tier").asText(null)
        if (customerId == null || newTier.isNullOrBlank()) return
        val correlationId = runCatching {
            UUID.fromString(event.path("correlation_id").asText())
        }.getOrNull() ?: UUID.randomUUID()
        runCatching {
            loyaltyAccountService.applyTierChanged(
                customerId = customerId,
                newTier = newTier,
                correlationId = correlationId,
            )
        }.onFailure { log.warn("loyalty.tier.changed handler failed for {}: {}", customerId, it.message) }
        inbox.save(
            InboxEvent(
                eventId = eventId,
                topic = "loyalty.tier.changed",
                receivedAt = Instant.now(),
                processedAt = Instant.now(),
            ),
        )
    }
}
