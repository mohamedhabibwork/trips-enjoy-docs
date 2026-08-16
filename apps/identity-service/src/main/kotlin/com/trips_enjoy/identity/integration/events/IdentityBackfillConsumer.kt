package com.trips_enjoy.identity.integration.events

import com.trips_enjoy.identity.domain.Identity
import com.trips_enjoy.identity.domain.IdentityRepository
import com.trips_enjoy.identity.domain.InboxEvent
import com.trips_enjoy.identity.domain.InboxEventRepository
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.kafka.support.KafkaHeaders
import org.springframework.messaging.handler.annotation.Header
import org.springframework.messaging.handler.annotation.Payload
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.ObjectMapper
import java.time.Instant
import java.util.UUID

/**
 * Consumes `customer.created`, `driver.created`, `courier.created`,
 * `merchant.created`, `restaurant.created` per INTEGRATION §4.1–§4.5
 * and ensures an `identities` row exists with the appropriate
 * cross-service reference id (DATA--003 + ERD §2).
 */
@Component
class IdentityBackfillConsumer(
    private val mapper: ObjectMapper,
    private val inbox: InboxEventRepository,
    private val identities: IdentityRepository,
) {
    @KafkaListener(
        topics = [
            "customer.created",
            "driver.created",
            "courier.created",
            "merchant.created",
            "restaurant.created",
        ],
        groupId = "identity-service",
    )
    @Transactional
    fun consume(
        @Header(KafkaHeaders.RECEIVED_TOPIC) topic: String,
        @Payload payload: String,
    ) {
        val event = try { mapper.readTree(payload) } catch (_: Exception) { return }
        val eventId = try { UUID.fromString(event.path("event_id").asString()) } catch (_: Exception) { return }
        if (inbox.existsByEventId(eventId)) return
        val data = event.path("data")
        val identityId = data.path("identity_id").asString().takeIf { it.isNotBlank() }?.let(UUID::fromString) ?: return
        val subject = data.path("kc_sub").asString().takeIf { it.isNotBlank() } ?: return
        val realm = data.path("realm").asString().takeIf { it.isNotBlank() } ?: return
        val userType = data.path("user_type").asString("customer")
        val personaId = data.path("id").asString().takeIf { it.isNotBlank() }?.let(UUID::fromString)
        val existing = identities.findById(identityId).orElse(null)
        val now = Instant.now()
        if (existing == null) {
            val newIdentity = Identity(
                id = identityId,
                keycloakSubject = subject,
                realm = realm,
                userType = userType,
                createdBy = UUID(0, 0),
                updatedBy = UUID(0, 0),
                createdAt = now,
                updatedAt = now,
            )
            applyCrossServiceId(newIdentity, topic, personaId)
            identities.save(newIdentity)
        } else if (personaId != null) {
            applyCrossServiceId(existing, topic, personaId)
            existing.updatedAt = now
            identities.save(existing)
        }
        inbox.save(InboxEvent(UUID.randomUUID(), eventId, topic))
    }

    private fun applyCrossServiceId(identity: Identity, topic: String, personaId: UUID?) {
        when (topic) {
            "customer.created" -> identity.customerId = personaId ?: identity.customerId
            "driver.created" -> identity.driverId = personaId ?: identity.driverId
            "courier.created" -> identity.courierId = personaId ?: identity.courierId
            "merchant.created" -> identity.merchantId = personaId ?: identity.merchantId
            "restaurant.created" -> identity.restaurantStaffId = personaId ?: identity.restaurantStaffId
        }
    }
}
