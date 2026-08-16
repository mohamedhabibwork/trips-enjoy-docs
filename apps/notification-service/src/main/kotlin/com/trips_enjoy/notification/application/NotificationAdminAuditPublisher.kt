package com.trips_enjoy.notification.application

import com.fasterxml.jackson.databind.ObjectMapper
import com.trips_enjoy.notification.domain.OutboxEvent
import com.trips_enjoy.notification.domain.OutboxEventRepository
import com.trips_enjoy.notification.util.uuidV7
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.UUID

/**
 * TECH §10.2 / SECURITY §Admin — every admin call on this service emits one
 * event to the `audit.admin.notification.v1` topic. The downstream
 * `audit-service` consumer writes it into the immutable `audit` schema.
 *
 * Mirrors audit-service's `AdminAuditPublisher`.
 */
@Component
class NotificationAdminAuditPublisher(
	private val outbox: OutboxEventRepository,
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
				"event_name" to "audit.admin.notification.v1",
				"occurred_at" to Instant.now().toString(),
				"schema_version" to 1,
				"producer" to "notification-service",
				"tenant_id" to "global",
				"correlation_id" to (requestId ?: uuidV7().toString()),
				"aggregate_type" to "NotificationAdminAction",
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
				aggregateType = "NotificationAdminAction",
				aggregateId = actorId,
				topic = "audit.admin.notification.v1",
				eventName = "audit.admin.notification.v1",
				payload = payload,
			),
		)
	}
}