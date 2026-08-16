package com.trips_enjoy.ledger.application

import com.fasterxml.jackson.databind.ObjectMapper
import com.trips_enjoy.ledger.domain.OutboxEvent
import com.trips_enjoy.ledger.domain.OutboxEventRepository
import com.trips_enjoy.ledger.util.uuidV7
import org.springframework.security.core.Authentication
import org.springframework.security.core.GrantedAuthority
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

/**
 * Emits `audit.admin.ledger.v1` events for every admin call on this
 * service (TECH.md §10.2). The audit-service consumes this topic and writes
 * the immutable audit log row.
 */
@Service
class AdminAuditPublisher(
    private val outbox: OutboxEventRepository,
    private val objectMapper: ObjectMapper,
) {

    @Transactional
    fun record(
        authentication: Authentication,
        endpoint: String,
        targetResource: String?,
        action: String,
        reasonCode: String?,
        requestId: String?,
        traceId: String?,
        result: String,
        durationMs: Long,
    ) {
        val actorId = runCatching { UUID.fromString(authentication.name) }.getOrElse { UUID(0, 0) }
        val actorUsername = authentication.name
        val roles = authentication.authorities.mapNotNull(GrantedAuthority::getAuthority)
        val payload = objectMapper.writeValueAsString(
            mapOf(
                "event_id" to uuidV7().toString(),
                "event_name" to "audit.admin.ledger.v1",
                "occurred_at" to Instant.now().toString(),
                "schema_version" to 1,
                "producer" to "ledger-service",
                "tenant_id" to "global",
                "correlation_id" to (requestId ?: uuidV7().toString()),
                "aggregate_type" to "AdminAction",
                "aggregate_id" to actorId.toString(),
                "data" to mapOf(
                    "actor_id" to actorId.toString(),
                    "actor_username" to actorUsername,
                    "roles" to roles,
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
                aggregateType = "AdminAction",
                aggregateId = actorId,
                topic = "audit.admin.ledger",
                eventName = "audit.admin.ledger.v1",
                payload = payload,
            ),
        )
    }
}
