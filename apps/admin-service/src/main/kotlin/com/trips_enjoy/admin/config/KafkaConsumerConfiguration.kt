package com.trips_enjoy.admin.config

import com.trips_enjoy.admin.domain.InboxEvent
import com.trips_enjoy.admin.domain.InboxEventRepository
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

    @KafkaListener(topics = ["customer.suspended.v1", "driver.suspended.v1", "courier.suspended.v1"], groupId = "admin-service")
    @Transactional
    fun onSubjectSuspended(payload: Map<String, Any?>, ack: Acknowledgment) {
        ingest(payload["topic"] as? String ?: "unknown", payload, ack)
    }

    @KafkaListener(topics = ["configuration.updated.v1"], groupId = "admin-service")
    @Transactional
    fun onConfigurationUpdated(payload: Map<String, Any?>, ack: Acknowledgment) {
        ingest("configuration.updated.v1", payload, ack)
    }

    @KafkaListener(topics = ["chat.message.reported.v1"], groupId = "admin-service")
    @Transactional
    fun onChatMessageReported(payload: Map<String, Any?>, ack: Acknowledgment) {
        ingest("chat.message.reported.v1", payload, ack)
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