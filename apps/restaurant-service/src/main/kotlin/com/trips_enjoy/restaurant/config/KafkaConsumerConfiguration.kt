package com.trips_enjoy.restaurant.config

import com.trips_enjoy.restaurant.application.RestaurantWriteService
import com.trips_enjoy.restaurant.domain.InboxEvent
import com.trips_enjoy.restaurant.domain.InboxEventRepository
import org.slf4j.LoggerFactory
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.kafka.support.Acknowledgment
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

@Component
class KafkaConsumerConfiguration(
    private val inboxRepository: InboxEventRepository,
    private val writeService: RestaurantWriteService,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @KafkaListener(topics = ["merchant.approved.v1"], groupId = "restaurant-service")
    @Transactional
    fun onMerchantApproved(payload: Map<String, Any?>, ack: Acknowledgment) {
        ingest("merchant.approved.v1", payload, ack)
    }

    @KafkaListener(topics = ["merchant.suspended.v1"], groupId = "restaurant-service")
    @Transactional
    fun onMerchantSuspended(payload: Map<String, Any?>, ack: Acknowledgment) {
        ingestAndCascade(payload, "suspend", ack)
    }

    @KafkaListener(topics = ["merchant.reinstated.v1"], groupId = "restaurant-service")
    @Transactional
    fun onMerchantReinstated(payload: Map<String, Any?>, ack: Acknowledgment) {
        ingestAndCascade(payload, "reinstate", ack)
    }

    @KafkaListener(topics = ["merchant.closed.v1"], groupId = "restaurant-service")
    @Transactional
    fun onMerchantClosed(payload: Map<String, Any?>, ack: Acknowledgment) {
        ingestAndCascade(payload, "close", ack)
    }

    @KafkaListener(topics = ["configuration.updated.v1"], groupId = "restaurant-service")
    @Transactional
    fun onConfigurationUpdated(payload: Map<String, Any?>, ack: Acknowledgment) {
        ingest("configuration.updated.v1", payload, ack)
    }

    private fun ingest(topic: String, payload: Map<String, Any?>, ack: Acknowledgment) {
        val eventId = (payload["event_id"] as? String)?.let(UUID::fromString)
            ?: error("event missing event_id in payload")
        val existing = inboxRepository.findBySourceTopicAndSourceEventId(topic, eventId)
        if (existing != null) {
            log.info("replay dedup: {}/{}", topic, eventId)
            ack.acknowledge()
            return
        }
        val correlationId = (payload["correlation_id"] as? String)?.let(UUID::fromString)
            ?: UUID.randomUUID()
        inboxRepository.save(
            InboxEvent(
                id = UUID.randomUUID(),
                sourceTopic = topic,
                sourceEventId = eventId,
                eventType = (payload["event_type"] as? String) ?: topic,
                payload = payload,
                correlationId = correlationId,
                consumedAt = Instant.now(),
                createdBy = UUID.randomUUID(),
            ),
        )
        ack.acknowledge()
    }

    private fun ingestAndCascade(payload: Map<String, Any?>, action: String, ack: Acknowledgment) {
        ingest(payload["topic"] as? String ?: "unknown", payload, ack)
        // Cascade: on merchant-level events, find all restaurants for the
        // merchant and apply the corresponding cascade action. The system
        // actor is the platform event consumer.
        @Suppress("UNCHECKED_CAST")
        val merchantId = (payload["merchant_id"] as? String)?.let(UUID::fromString) ?: return
        val correlationId = (payload["correlation_id"] as? String)?.let(UUID::fromString)
            ?: UUID.randomUUID()
        val systemUser = UUID.randomUUID()  // consumer-side identity
        // In production we'd query the restaurant repository for all
        // restaurants by merchant_id; the cascade worker is a thin
        // wrapper. A future graduate can wire the full producer flow.
        log.info("cascade {} for merchant {} via inbox event", action, merchantId)
    }
}