package com.trips_enjoy.notification.application

import com.fasterxml.jackson.databind.ObjectMapper
import com.trips_enjoy.notification.domain.OutboxEvent
import com.trips_enjoy.notification.domain.OutboxEventRepository
import com.trips_enjoy.notification.util.uuidV7
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.UUID

/**
 * Writers for the platform event envelope per
 * docs/architecture/EVENT_ARCHITECTURE.md §2. Each `publish*` method writes a
 * row into `notification.outbox`; `OutboxPublisher` then publishes to Kafka.
 *
 * Topics carry `notification.notification.<event>` per INTEGRATION.md §3.1.
 * Partition key is `user_id` for fan-out events; `template_id` for
 * template-published.
 *
 * Events emitted by this service (per INTEGRATION.md §3.1):
 *  - notification.sent.v1
 *  - notification.failed.v1
 *  - notification.suppressed.v1
 *  - notification.delivered.v1
 *  - notification.read.v1
 *  - notification.template.published.v1
 */
@Component
class NotificationEventPublishers(
	private val outbox: OutboxEventRepository,
	private val mapper: ObjectMapper,
) {

	fun publishSent(
		deliveryId: UUID,
		userId: UUID,
		templateId: UUID,
		channel: String,
		correlationId: String,
		causationId: String? = null,
		occurredAt: Instant = Instant.now(),
	) = write(
		eventName = "notification.sent.v1",
		topic = "notification.notification.sent",
		aggregateType = "Delivery",
		aggregateId = deliveryId,
		correlationId = correlationId,
		causationId = causationId,
		occurredAt = occurredAt,
		data = mapOf(
			"user_id" to userId,
			"template_id" to templateId,
			"channel" to channel,
			"delivery_id" to deliveryId,
		),
	)

	fun publishFailed(
		deliveryId: UUID,
		userId: UUID,
		templateId: UUID,
		channel: String,
		reason: String,
		correlationId: String,
		causationId: String? = null,
		occurredAt: Instant = Instant.now(),
	) = write(
		eventName = "notification.failed.v1",
		topic = "notification.notification.failed",
		aggregateType = "Delivery",
		aggregateId = deliveryId,
		correlationId = correlationId,
		causationId = causationId,
		occurredAt = occurredAt,
		data = mapOf(
			"user_id" to userId,
			"template_id" to templateId,
			"channel" to channel,
			"delivery_id" to deliveryId,
			"reason" to reason,
		),
	)

	fun publishSuppressed(
		userId: UUID,
		templateId: UUID,
		channel: String?,
		reason: String,
		correlationId: String,
		causationId: String? = null,
		occurredAt: Instant = Instant.now(),
	) = write(
		eventName = "notification.suppressed.v1",
		topic = "notification.notification.suppressed",
		aggregateType = "Delivery",
		aggregateId = userId,
		correlationId = correlationId,
		causationId = causationId,
		occurredAt = occurredAt,
		data = mapOf(
			"user_id" to userId,
			"template_id" to templateId,
			"channel" to channel,
			"reason" to reason,
		),
	)

	fun publishDelivered(
		deliveryId: UUID,
		userId: UUID,
		channel: String,
		gatewayRequestId: String?,
		correlationId: String,
		causationId: String? = null,
		occurredAt: Instant = Instant.now(),
	) = write(
		eventName = "notification.delivered.v1",
		topic = "notification.notification.delivered",
		aggregateType = "Delivery",
		aggregateId = deliveryId,
		correlationId = correlationId,
		causationId = causationId,
		occurredAt = occurredAt,
		data = mapOf(
			"user_id" to userId,
			"channel" to channel,
			"delivery_id" to deliveryId,
			"gateway_request_id" to gatewayRequestId,
		),
	)

	fun publishRead(
		deliveryId: UUID,
		userId: UUID,
		correlationId: String,
		causationId: String? = null,
		occurredAt: Instant = Instant.now(),
	) = write(
		eventName = "notification.read.v1",
		topic = "notification.notification.read",
		aggregateType = "Delivery",
		aggregateId = deliveryId,
		correlationId = correlationId,
		causationId = causationId,
		occurredAt = occurredAt,
		data = mapOf(
			"user_id" to userId,
			"delivery_id" to deliveryId,
		),
	)

	fun publishTemplatePublished(
		templateId: UUID,
		templateHistoryId: UUID,
		channel: String,
		providerTemplateId: String?,
		providerTemplateStatus: String,
		publishedBy: UUID,
		approvedBy: UUID?,
		diffSummary: Map<String, Any?>,
		correlationId: String,
		occurredAt: Instant = Instant.now(),
	) = write(
		eventName = "notification.template.published.v1",
		topic = "notification.notification.published",
		aggregateType = "Template",
		aggregateId = templateId,
		correlationId = correlationId,
		causationId = null,
		occurredAt = occurredAt,
		data = mapOf(
			"template_id" to templateId,
			"template_history_id" to templateHistoryId,
			"channel" to channel,
			"provider_template_id" to providerTemplateId,
			"provider_template_status" to providerTemplateStatus,
			"published_by" to publishedBy,
			"approved_by" to approvedBy,
			"diff_summary" to diffSummary,
		),
	)

	private fun write(
		eventName: String,
		topic: String,
		aggregateType: String,
		aggregateId: UUID?,
		correlationId: String,
		causationId: String?,
		occurredAt: Instant,
		data: Map<String, Any?>,
	) {
		val payload = mapper.writeValueAsString(
			mapOf(
				"event_id" to uuidV7().toString(),
				"event_name" to eventName,
				"occurred_at" to occurredAt.toString(),
				"schema_version" to 1,
				"producer" to "notification-service",
				"tenant_id" to "global",
				"correlation_id" to correlationId,
				"causation_id" to causationId,
				"aggregate_type" to aggregateType,
				"aggregate_id" to aggregateId?.toString(),
				"data" to data,
			),
		)
		outbox.save(
			OutboxEvent(
				id = uuidV7(),
				aggregateType = aggregateType,
				aggregateId = aggregateId,
				topic = topic,
				eventName = eventName,
				payload = payload,
			),
		)
	}
}