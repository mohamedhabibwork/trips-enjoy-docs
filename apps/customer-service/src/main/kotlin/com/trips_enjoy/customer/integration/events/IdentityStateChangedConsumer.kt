package com.trips_enjoy.customer.integration.events

import com.fasterxml.jackson.databind.ObjectMapper
import com.trips_enjoy.customer.api.ApiException
import com.trips_enjoy.customer.application.CustomerReadService
import com.trips_enjoy.customer.application.CustomerWriteService
import com.trips_enjoy.customer.domain.InboxEvent
import com.trips_enjoy.customer.domain.InboxRepository
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.messaging.handler.annotation.Payload
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.UUID

/**
 * Consumes identity status events that drive the customer-side state
 * mirror (INTEGRATION.md §4.3 / §4.4 / §4.5 / §4.6):
 *   - identity.user.suspended.v1
 *   - identity.user.disabled.v1
 *   - identity.user.reinstated.v1
 *   - identity.user.erased.v1
 *
 * Each handler is idempotent on `event_id` (inbox) and on the projection
 * state (the application service returns 409 if the state machine
 * rejects the transition).
 */
@Component
class IdentityStateChangedConsumer(
    private val mapper: ObjectMapper,
    private val inbox: InboxRepository,
    private val readService: CustomerReadService,
    private val writeService: CustomerWriteService,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @KafkaListener(
        topics = ["identity.user.suspended"],
        groupId = "\${customer-service.kafka.consumer.group-id:customer-service}",
        containerFactory = "customerKafkaListenerContainerFactory",
    )
    fun onSuspended(
        @Payload payload: String,
    ) {
        handleStateChange(
            payload = payload,
            topic = "identity.user.suspended",
            verbose = "suspended",
        ) { identityId, reason, correlationId ->
            val customer = readService.getByIdentityId(identityId)
                ?: throw ApiException(HttpStatus.NOT_FOUND, "CUSTOMER_NOT_FOUND", "no customer for identity $identityId")
            if (customer.status == "suspended") return@handleStateChange
            writeService.suspend(
                customerId = customer.id,
                reason = reason ?: "identity_suspended",
                note = null,
                actorId = identityId,
                actorType = "service",
                correlationId = correlationId,
            )
        }
    }

    @KafkaListener(
        topics = ["identity.user.disabled"],
        groupId = "\${customer-service.kafka.consumer.group-id:customer-service}",
        containerFactory = "customerKafkaListenerContainerFactory",
    )
    fun onDisabled(
        @Payload payload: String,
    ) {
        handleStateChange(
            payload = payload,
            topic = "identity.user.disabled",
            verbose = "disabled",
        ) { identityId, reason, correlationId ->
            val customer = readService.getByIdentityId(identityId)
                ?: throw ApiException(HttpStatus.NOT_FOUND, "CUSTOMER_NOT_FOUND", "no customer for identity $identityId")
            if (customer.status == "disabled") return@handleStateChange
            writeService.disable(
                customerId = customer.id,
                reason = reason ?: "identity_disabled",
                note = null,
                actorId = identityId,
                actorType = "service",
                correlationId = correlationId,
            )
        }
    }

    @KafkaListener(
        topics = ["identity.user.reinstated"],
        groupId = "\${customer-service.kafka.consumer.group-id:customer-service}",
        containerFactory = "customerKafkaListenerContainerFactory",
    )
    fun onReinstated(
        @Payload payload: String,
    ) {
        handleStateChange(
            payload = payload,
            topic = "identity.user.reinstated",
            verbose = "reinstated",
        ) { identityId, _, correlationId ->
            val customer = readService.getByIdentityId(identityId)
                ?: throw ApiException(HttpStatus.NOT_FOUND, "CUSTOMER_NOT_FOUND", "no customer for identity $identityId")
            if (customer.status == "active") return@handleStateChange
            if (customer.status == "suspended") {
                writeService.reinstate(
                    customerId = customer.id,
                    note = null,
                    actorId = identityId,
                    actorType = "service",
                    correlationId = correlationId,
                )
            }
        }
    }

    @KafkaListener(
        topics = ["identity.user.erased"],
        groupId = "\${customer-service.kafka.consumer.group-id:customer-service}",
        containerFactory = "customerKafkaListenerContainerFactory",
    )
    fun onErased(
        @Payload payload: String,
    ) {
        handleStateChange(
            payload = payload,
            topic = "identity.user.erased",
            verbose = "erased",
        ) { identityId, _, correlationId ->
            val customer = readService.getByIdentityId(identityId)
                ?: throw ApiException(HttpStatus.NOT_FOUND, "CUSTOMER_NOT_FOUND", "no customer for identity $identityId")
            if (customer.status == "erased") return@handleStateChange
            writeService.erase(
                customerId = customer.id,
                legalBasis = "identity_erased",
                note = null,
                actorId = identityId,
                actorType = "service",
                correlationId = correlationId,
            )
        }
    }

    private fun handleStateChange(
        payload: String,
        topic: String,
        verbose: String,
        action: (identityId: UUID, reason: String?, correlationId: UUID) -> Unit,
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
        val identityId = runCatching { UUID.fromString(data.path("identity_id").asText()) }.getOrNull()
            ?: return
        val reason = data.path("reason").asText(null).takeIf { !it.isNullOrBlank() }
        val correlationId = runCatching {
            UUID.fromString(event.path("correlation_id").asText())
        }.getOrNull() ?: UUID.randomUUID()
        runCatching {
            action(identityId, reason, correlationId)
        }.onFailure { log.warn("$topic handler failed for identity={}: {}", identityId, it.message) }
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
