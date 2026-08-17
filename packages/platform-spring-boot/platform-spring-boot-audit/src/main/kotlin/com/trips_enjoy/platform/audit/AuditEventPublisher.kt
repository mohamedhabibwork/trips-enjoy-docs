package com.trips_enjoy.platform.audit

import com.fasterxml.jackson.databind.ObjectMapper
import com.trips_enjoy.platform.messaging.OutboxEventCanonical
import com.trips_enjoy.platform.messaging.OutboxRepositoryCanonical
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * Publishes audit events through the transactional outbox. The audit event
 * is woven into the same database transaction as the business write, so
 * the at-least-once guarantee holds across the platform boundary.
 *
 * Per ADR-0028 the canonical outbox row shape is the 11-column
 * [OutboxEventCanonical]; the legacy 14-column `OutboxEvent` was
 * retired in Phase B (commit e81cea9) and its remaining callers in
 * this module were migrated to the canonical entity in Phase D.
 */
@Component
open class AuditEventPublisher(
    private val outboxRepository: OutboxRepositoryCanonical,
    private val objectMapper: ObjectMapper,
) {

    @Transactional
    open fun publish(event: AuditEvent, topic: String) {
        val outboxEvent = OutboxEventCanonical(
            id = event.auditId,
            eventId = UUID.randomUUID(),
            topic = topic,
            partitionKey = event.actorId ?: event.auditId.toString(),
            payload = objectMapper.writeValueAsString(event),
            headers = objectMapper.writeValueAsString(
                mapOf("requestId" to event.requestId, "traceId" to event.traceId)
            ),
        )
        outboxRepository.save(outboxEvent)
    }
}
