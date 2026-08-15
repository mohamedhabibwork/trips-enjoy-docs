package com.trips_enjoy.courier.config

import com.trips_enjoy.courier.domain.InboxEvent
import com.trips_enjoy.courier.domain.InboxEventRepository
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

    @KafkaListener(topics = ["identity.user.created.v1"], groupId = "courier-service")
    @Transactional
    fun onIdentityUserCreated(payload: Map<String, Any?>, ack: Acknowledgment) {
        ingest("identity.user.created.v1", payload, ack)
    }

    @KafkaListener(topics = ["food.order.completed.v1"], groupId = "courier-service")
    @Transactional
    fun onFoodOrderCompleted(payload: Map<String, Any?>, ack: Acknowledgment) {
        ingest("food.order.completed.v1", payload, ack)
    }

    @KafkaListener(topics = ["food.order.rating.added.v1"], groupId = "courier-service")
    @Transactional
    fun onFoodOrderRatingAdded(payload: Map<String, Any?>, ack: Acknowledgment) {
        ingest("food.order.rating.added.v1", payload, ack)
    }

    @KafkaListener(topics = ["configuration.updated.v1"], groupId = "courier-service")
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