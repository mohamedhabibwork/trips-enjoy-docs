package com.trips_enjoy.trip.config

import com.trips_enjoy.trip.domain.InboxEvent
import com.trips_enjoy.trip.domain.InboxEventRepository
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
     * BFF consumers for the 4 upstream services that drive trip-service:
     *   - customer-service: customer.suspended / customer.reinstated
     *     → trip-service cancels active trips on rider suspension.
     *   - driver-service: driver.suspended / driver.reinstated
     *     → trip-service cancels active trips on driver suspension.
     *   - pricing-service: pricing.geo_config.updated.v1
     *     → trip-service refreshes cached fare rules for new requests.
     *   - payment-service: payment.failed.v1 (Phase 7 trip saga)
     *     → trip-service reverses the trip reward if the payment failed.
     */
    @KafkaListener(
        topics = [
            "customer.suspended.v1",
            "customer.reinstated.v1",
            "driver.suspended.v1",
            "driver.reinstated.v1",
            "pricing.geo_config.updated.v1",
            "payment.failed.v1",
        ],
        groupId = "trip-service",
    )
    @Transactional
    fun onBffEvent(payload: Map<String, Any?>, ack: Acknowledgment) {
        ingest(payload["topic"] as? String ?: "unknown", payload, ack)
    }

    @KafkaListener(topics = ["configuration.updated.v1"], groupId = "trip-service")
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