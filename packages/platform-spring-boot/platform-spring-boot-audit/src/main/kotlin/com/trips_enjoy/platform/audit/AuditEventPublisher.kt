package com.trips_enjoy.platform.audit

import com.fasterxml.jackson.databind.ObjectMapper
import com.trips_enjoy.platform.messaging.OutboxEvent
import com.trips_enjoy.platform.messaging.OutboxRepository
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

/**
 * Publishes audit events through the transactional outbox. The audit event
 * is woven into the same database transaction as the business write, so
 * the at-least-once guarantee holds across the platform boundary.
 */
@Component
open class AuditEventPublisher(
    private val outboxRepository: OutboxRepository,
    private val objectMapper: ObjectMapper,
) {

    @Transactional
    open fun publish(event: AuditEvent, topic: String) {
        val outboxEvent = OutboxEvent(
            aggregateType = "audit",
            aggregateId = event.actorId,
            topic = topic,
            eventName = topic,
            payload = objectMapper.writeValueAsString(event),
            headers = objectMapper.writeValueAsString(
                mapOf("requestId" to event.requestId, "traceId" to event.traceId)
            ),
        )
        outboxRepository.save(outboxEvent)
    }
}
