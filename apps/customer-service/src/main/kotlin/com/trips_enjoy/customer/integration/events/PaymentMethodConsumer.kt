package com.trips_enjoy.customer.integration.events

import com.fasterxml.jackson.databind.ObjectMapper
import com.trips_enjoy.customer.application.CustomerReadService
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
 * Consumes `payment.method.saved.v1` and `payment.method.removed.v1`
 * (INTEGRATION.md §4.7 / §4.8).
 *
 * Sets or clears the customer's default payment method reference. The
 * `payment_method_id` is the cross-service UUID owned by payment-service.
 *
 * Removal clears the default reference; the cancel path is exposed via
 * the explicit `PUT /v1/customers/{id}/default-payment-method/{pm_id}`
 * endpoint so the dashboard can pick a new default.
 */
@Component
class PaymentMethodConsumer(
    private val mapper: ObjectMapper,
    private val inbox: InboxRepository,
    private val readService: CustomerReadService,
    private val writeService: CustomerWriteService,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @KafkaListener(
        topics = ["payment.method.saved"],
        groupId = "\${customer-service.kafka.consumer.group-id:customer-service}",
        containerFactory = "customerKafkaListenerContainerFactory",
    )
    fun onSaved(
        @Payload payload: String,
    ) {
        val event =
            try {
                mapper.readTree(payload)
            } catch (exception: Exception) {
                log.warn("Skipping malformed payment.method.saved payload: {}", exception.message)
                return
            }
        val eventId = runCatching { UUID.fromString(event.path("event_id").asText()) }.getOrNull()
            ?: return
        if (inbox.existsByEventId(eventId)) return
        val data = event.path("data")
        val identityId = runCatching { UUID.fromString(data.path("identity_id").asText()) }.getOrNull()
        val paymentMethodId = runCatching { UUID.fromString(data.path("payment_method_id").asText()) }.getOrNull()
        if (identityId == null || paymentMethodId == null) return
        val customer = readService.getByIdentityId(identityId)
        if (customer == null) {
            log.debug("payment.method.saved for unknown identity {}", identityId)
            return
        }
        val correlationId = runCatching {
            UUID.fromString(event.path("correlation_id").asText())
        }.getOrNull() ?: UUID.randomUUID()
        // Per INTEGRATION.md §4.7: only set when the customer has no
        // default or the saved method is the most-recent.
        val isMostRecent = data.path("most_recent").asBoolean(false)
        if (customer.defaultPaymentMethodId == null || isMostRecent) {
            runCatching {
                writeService.setDefaultPaymentMethod(
                    customerId = customer.id,
                    paymentMethodId = paymentMethodId,
                    actorId = identityId,
                    actorType = "service",
                    correlationId = correlationId,
                )
            }.onFailure { log.warn("setDefaultPaymentMethod failed for {}: {}", customer.id, it.message) }
        }
        inbox.save(
            InboxEvent(
                eventId = eventId,
                topic = "payment.method.saved",
                receivedAt = Instant.now(),
                processedAt = Instant.now(),
            ),
        )
    }

    @KafkaListener(
        topics = ["payment.method.removed"],
        groupId = "\${customer-service.kafka.consumer.group-id:customer-service}",
        containerFactory = "customerKafkaListenerContainerFactory",
    )
    fun onRemoved(
        @Payload payload: String,
    ) {
        val event =
            try {
                mapper.readTree(payload)
            } catch (exception: Exception) {
                log.warn("Skipping malformed payment.method.removed payload: {}", exception.message)
                return
            }
        val eventId = runCatching { UUID.fromString(event.path("event_id").asText()) }.getOrNull()
            ?: return
        if (inbox.existsByEventId(eventId)) return
        val data = event.path("data")
        val identityId = runCatching { UUID.fromString(data.path("identity_id").asText()) }.getOrNull()
            ?: return
        val paymentMethodId = runCatching { UUID.fromString(data.path("payment_method_id").asText()) }.getOrNull()
            ?: return
        val customer = readService.getByIdentityId(identityId) ?: return
        val correlationId = runCatching {
            UUID.fromString(event.path("correlation_id").asText())
        }.getOrNull() ?: UUID.randomUUID()
        if (customer.defaultPaymentMethodId == paymentMethodId) {
            runCatching {
                writeService.setDefaultPaymentMethod(
                    customerId = customer.id,
                    paymentMethodId = paymentMethodId,
                    actorId = identityId,
                    actorType = "service",
                    correlationId = correlationId,
                )
            }.onFailure { log.warn("payment.method.removed default-clear failed for {}: {}", customer.id, it.message) }
        }
        inbox.save(
            InboxEvent(
                eventId = eventId,
                topic = "payment.method.removed",
                receivedAt = Instant.now(),
                processedAt = Instant.now(),
            ),
        )
    }
}
