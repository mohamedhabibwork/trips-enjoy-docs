package com.trips_enjoy.notification.integration.events

import com.fasterxml.jackson.databind.ObjectMapper
import com.trips_enjoy.notification.application.NotificationDeliveryService
import com.trips_enjoy.notification.application.NotificationEventPublishers
import org.slf4j.LoggerFactory
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.kafka.support.Acknowledgment
import org.springframework.kafka.support.KafkaHeaders
import org.springframework.messaging.handler.annotation.Header
import org.springframework.messaging.handler.annotation.Payload
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.UUID

/**
 * Inbound WhatsApp webhooks per docs/services/notification-service/WORKFLOWS §9.1.4-9.1.6.
 *
 * Topics:
 *   - comms.whatsapp.template_status_update.v1  (provider approval/rejection)
 *   - comms.whatsapp.delivered.v1
 *   - comms.whatsapp.read.v1
 *   - comms.whatsapp.failed.v1
 *
 * Reconciles `notification.deliveries` state and emits
 * `notification.delivered.v1` / `notification.read.v1`.
 */
@Component
class WhatsappWebhookConsumer(
	private val objectMapper: ObjectMapper,
	private val deliveryService: NotificationDeliveryService,
	private val events: NotificationEventPublishers,
) {
	private val log = LoggerFactory.getLogger(javaClass)

	@KafkaListener(
		topics = [
			"comms.whatsapp.template_status_update",
			"comms.whatsapp.delivered",
			"comms.whatsapp.read",
			"comms.whatsapp.failed",
		],
		groupId = "\${notification-service.consumer.whatsapp-group-id:notification-service-whatsapp}",
		containerFactory = "notificationKafkaListenerContainerFactory",
	)
	fun consume(
		@Payload payload: String,
		@Header(KafkaHeaders.RECEIVED_TOPIC) topic: String,
		ack: Acknowledgment,
	) {
		try {
			val envelope = objectMapper.readValue(payload, MAP_TYPE)
			val correlationId = envelope["correlation_id"]?.toString() ?: UUID.randomUUID().toString()
			when (topic) {
				"comms.whatsapp.delivered" -> {
					val deliveryId = extractUuid(envelope, "delivery_id") ?: return
					val createdAt = extractCreatedAt(envelope) ?: return
					deliveryService.markDelivered(deliveryId, createdAt)
					events.publishDelivered(
						deliveryId = deliveryId,
						userId = extractUuid(envelope, "user_id") ?: UUID.randomUUID(),
						channel = "whatsapp",
						gatewayRequestId = envelope["wamid"]?.toString(),
						correlationId = correlationId,
					)
				}
				"comms.whatsapp.read" -> {
					val deliveryId = extractUuid(envelope, "delivery_id") ?: return
					val createdAt = extractCreatedAt(envelope) ?: return
					deliveryService.markRead(deliveryId, createdAt)
					events.publishRead(deliveryId = deliveryId, userId = extractUuid(envelope, "user_id") ?: UUID.randomUUID(), correlationId = correlationId)
				}
				"comms.whatsapp.failed" -> {
					val deliveryId = extractUuid(envelope, "delivery_id") ?: return
					val userId = extractUuid(envelope, "user_id") ?: UUID.randomUUID()
					val reason = envelope["reason"]?.toString() ?: "whatsapp_failed"
					events.publishFailed(
						deliveryId = deliveryId,
						userId = userId,
						templateId = extractUuid(envelope, "template_id") ?: UUID.randomUUID(),
						channel = "whatsapp",
						reason = reason,
						correlationId = correlationId,
					)
				}
				"comms.whatsapp.template_status_update" -> {
					log.info("whatsapp template status update: {}", envelope["data"])
					// Handled by the admin publish-approval path; emit a metric/scope marker here.
				}
			}
			ack.acknowledge()
		} catch (exception: Exception) {
			log.warn("whatsapp webhook consumer failed topic={}: {}", topic, exception.message)
			throw exception
		}
	}

	private fun extractUuid(env: Map<String, Any?>, key: String): UUID? =
		(env["data"] as? Map<*, *>)?.get(key)?.toString()?.let(UUID::fromString)

	private fun extractCreatedAt(env: Map<String, Any?>): Instant? =
		(env["data"] as? Map<*, *>)?.get("created_at")?.toString()?.let(java.time.Instant::parse)

	companion object {
		private val MAP_TYPE = objectMapperType()
		private fun objectMapperType(): com.fasterxml.jackson.core.type.TypeReference<Map<String, Any?>> =
			object : com.fasterxml.jackson.core.type.TypeReference<Map<String, Any?>>() {}
	}
}