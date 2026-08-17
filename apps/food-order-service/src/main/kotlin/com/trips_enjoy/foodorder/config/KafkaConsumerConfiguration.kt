package com.trips_enjoy.foodorder.config

import com.trips_enjoy.foodorder.domain.InboxEvent
import com.trips_enjoy.foodorder.domain.InboxEventRepository
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
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * BFF consumers for the 5 upstream services that drive
     * food-order-service:
     *   - restaurant-service: restaurant.accepted / restaurant.rejected /
     *     restaurant.preparing → food-order-service reacts.
     *   - courier-service: courier.assigned / courier.delivered →
     *     food-order-service tracks dispatch + delivery.
     *   - customer-service: customer.suspended → food-order-service
     *     cancels active orders on rider suspension.
     *   - payment-service: payment.completed / payment.failed →
     *     food-order-service marks the order as paid / cancels on failure.
     *   - pricing-service: pricing.geo_config.updated → food-order-service
     *     refreshes cached fare rules.
     */
    @KafkaListener(
        topics = [
            "restaurant.accepted.v1",
            "restaurant.rejected.v1",
            "restaurant.preparing.v1",
            "courier.assigned.v1",
            "courier.delivered.v1",
            "customer.suspended.v1",
            "payment.completed.v1",
            "payment.failed.v1",
            "pricing.geo_config.updated.v1",
        ],
        groupId = "food-order-service",
    )
    @Transactional
    fun onBffEvent(payload: Map<String, Any?>, ack: Acknowledgment) {
        ingest(payload["topic"] as? String ?: "unknown", payload, ack)
    }

    @KafkaListener(topics = ["configuration.updated.v1"], groupId = "food-order-service")
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
}