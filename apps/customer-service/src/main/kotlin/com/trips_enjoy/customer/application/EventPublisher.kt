package com.trips_enjoy.customer.application

import com.fasterxml.jackson.databind.ObjectMapper
import com.trips_enjoy.customer.domain.OutboxEvent
import com.trips_enjoy.customer.domain.OutboxRepository
import com.trips_enjoy.customer.util.uuidV7
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.UUID

/**
 * Writes the platform event envelope to the `customer.outbox` table in
 * the SAME transaction as the state change that produced it. The
 * OutboxPublisher (see `OutboxPublisher.kt`) drains the table to Kafka.
 *
 * The envelope shape matches the platform convention:
 *   event_id, event_name, schema_version, producer, tenant_id,
 *   correlation_id, occurred_at, aggregate_type, aggregate_id, data
 */
@Component
class EventPublisher(
    private val outboxRepository: OutboxRepository,
    private val mapper: ObjectMapper,
) {
    fun publish(
        topic: String,
        eventName: String,
        aggregateType: String,
        aggregateId: UUID,
        data: Map<String, Any?>,
        correlationId: UUID,
        tenantId: String = "global",
    ) {
        val envelope =
            mapOf(
                "event_id" to uuidV7().toString(),
                "event_name" to eventName,
                "schema_version" to 1,
                "producer" to "customer-service",
                "tenant_id" to tenantId,
                "correlation_id" to correlationId.toString(),
                "occurred_at" to Instant.now().toString(),
                "aggregate_type" to aggregateType,
                "aggregate_id" to aggregateId.toString(),
                "data" to data,
            )
        outboxRepository.save(
            OutboxEvent(
                id = uuidV7(),
                topic = topic,
                eventId = uuidV7(),
                payload = mapper.writeValueAsString(envelope),
            ),
        )
    }
}
