package com.trips_enjoy.customer.application

import com.fasterxml.jackson.databind.ObjectMapper
import com.trips_enjoy.customer.domain.OutboxEvent
import com.trips_enjoy.customer.domain.OutboxRepository
import com.trips_enjoy.customer.util.uuidV7
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.UUID

/**
 * Per docs/services/customer-service/TECH.md §10.2 — every admin call
 * on this service emits one event to the `audit.admin.customer.v1`
 * topic. The downstream `audit-service` consumer writes the row into
 * the immutable `audit` schema, so admins are audited in the same
 * log they may read from.
 */
@Component
class AdminAuditPublisher(
    private val outbox: OutboxRepository,
    private val mapper: ObjectMapper,
) {
    fun publish(
        actorId: UUID,
        actorUsername: String?,
        actorRoles: List<String>,
        endpoint: String,
        action: String,
        targetResource: String?,
        reasonCode: String?,
        requestId: String?,
        traceId: String?,
        result: String,
        durationMs: Long,
    ) {
        val payload = mapper.writeValueAsString(
            mapOf(
                "event_id" to uuidV7().toString(),
                "event_name" to "audit.admin.customer.v1",
                "occurred_at" to Instant.now().toString(),
                "schema_version" to 1,
                "producer" to "customer-service",
                "tenant_id" to "global",
                "correlation_id" to (requestId ?: uuidV7().toString()),
                "aggregate_type" to "CustomerAdminAction",
                "aggregate_id" to actorId.toString(),
                "data" to mapOf(
                    "actor_id" to actorId,
                    "actor_username" to actorUsername,
                    "roles" to actorRoles,
                    "endpoint" to endpoint,
                    "target_resource" to targetResource,
                    "action" to action,
                    "reason_code" to reasonCode,
                    "request_id" to requestId,
                    "trace_id" to traceId,
                    "result" to result,
                    "duration_ms" to durationMs,
                ),
            ),
        )
        outbox.save(
            OutboxEvent(
                id = uuidV7(),
                topic = "audit.admin.customer.v1",
                eventId = uuidV7(),
                payload = payload,
            ),
        )
    }
}
