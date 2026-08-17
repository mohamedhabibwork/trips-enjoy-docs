package com.trips_enjoy.customer.integration.events

import com.fasterxml.jackson.databind.ObjectMapper
import com.trips_enjoy.customer.application.LtvUpdateService
import com.trips_enjoy.customer.domain.InboxEvent
import com.trips_enjoy.customer.domain.InboxRepository
import org.slf4j.LoggerFactory
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.messaging.handler.annotation.Payload
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.UUID

/**
 * Consumes `ride.payment.completed.v1` and `food.payment.completed.v1`
 * (INTEGRATION.md §4.9 / §4.10) and applies the LTV update + segment
 * recompute on the customer's row.
 */
@Component
class PaymentCompletedConsumer(
    private val mapper: ObjectMapper,
    private val inbox: InboxRepository,
    private val ltvUpdateService: LtvUpdateService,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @KafkaListener(
        topics = ["ride.payment.completed"],
        groupId = "\${customer-service.kafka.consumer.group-id:customer-service}",
        containerFactory = "customerKafkaListenerContainerFactory",
    )
    fun onRidePaymentCompleted(
        @Payload payload: String,
    ) {
        handle(
            payload = payload,
            topic = "ride.payment.completed",
            defaultService = "ride",
        )
    }

    @KafkaListener(
        topics = ["food.payment.completed"],
        groupId = "\${customer-service.kafka.consumer.group-id:customer-service}",
        containerFactory = "customerKafkaListenerContainerFactory",
    )
    fun onFoodPaymentCompleted(
        @Payload payload: String,
    ) {
        handle(
            payload = payload,
            topic = "food.payment.completed",
            defaultService = "food",
        )
    }

    private fun handle(
        payload: String,
        topic: String,
        defaultService: String,
    ) {
        val event =
            try {
                mapper.readTree(payload)
            } catch (exception: Exception) {
                log.warn("Skipping malformed $topic payload: {}", exception.message)
                return
            }
        val eventId = runCatching { UUID.fromString(event.path("event_id").asText()) }.getOrNull()
            ?: return
        if (inbox.existsByEventId(eventId)) return
        val data = event.path("data")
        val customerId = runCatching { UUID.fromString(data.path("customer_id").asText()) }.getOrNull()
        val amountMinor = data.path("amount_minor").asLong(0L)
        val currency = data.path("currency").asText("USD")
        val requestId = runCatching { UUID.fromString(data.path("request_id").asText()) }.getOrNull()
        val serviceOverride = data.path("service").asText(null)?.takeIf { it.isNotBlank() } ?: defaultService
        val correlationId = runCatching {
            UUID.fromString(event.path("correlation_id").asText())
        }.getOrNull() ?: UUID.randomUUID()
        if (customerId == null || amountMinor <= 0L) {
            log.warn("$topic skipped: customer_id={} amount_minor={}", customerId, amountMinor)
            return
        }
        runCatching {
            ltvUpdateService.applyPayment(
                customerId = customerId,
                amountMinor = amountMinor,
                currency = currency,
                service = serviceOverride,
                requestId = requestId,
                correlationId = correlationId,
            )
        }.onFailure { log.warn("applyPayment failed for {}: {}", customerId, it.message) }
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
