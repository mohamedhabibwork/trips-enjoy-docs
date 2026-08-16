package com.trips_enjoy.identity.application

import com.fasterxml.jackson.databind.ObjectMapper
import com.trips_enjoy.identity.domain.Identity
import com.trips_enjoy.identity.domain.OutboxEvent
import com.trips_enjoy.identity.domain.OutboxEventRepository
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.UUID

/**
 * Emits the platform audit envelope `audit.admin.identity.v1` per TECH §10.2.
 * Consumers: `audit-service` (writes to its immutable `audit` schema).
 *
 * Fields per the spec:
 *   actor_id, actor_username, roles, endpoint, target_resource, action,
 *   reason_code (required for PII access), request_id, trace_id, result,
 *   duration_ms
 */
@Component
class AdminAuditPublisher(
    private val outbox: OutboxEventRepository,
    private val mapper: ObjectMapper,
) {
    fun publish(
        identity: Identity,
        actorId: UUID,
        actorUsername: String?,
        actorRoles: List<String>,
        endpoint: String,
        action: String,
        reasonCode: String?,
        requestId: String?,
        traceId: String?,
        result: String,
        durationMs: Long,
    ) {
        val payload = mapper.writeValueAsString(
            mapOf(
                "event_id" to UUID.randomUUID().toString(),
                "event_name" to "audit.admin.identity.v1",
                "occurred_at" to Instant.now().toString(),
                "data" to mapOf(
                    "actor_id" to actorId,
                    "actor_username" to actorUsername,
                    "roles" to actorRoles,
                    "endpoint" to endpoint,
                    "target_resource" to "identity:${identity.id}",
                    "identity_id" to identity.id,
                    "kc_sub" to identity.keycloakSubject,
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
                id = UUID.randomUUID(),
                aggregateType = "Identity",
                aggregateId = identity.id,
                topic = "audit.admin.identity.v1",
                eventName = "audit.admin.identity.v1",
                payload = payload,
            ),
        )
    }
}
