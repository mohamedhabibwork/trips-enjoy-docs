package com.trips_enjoy.notification.integration.events

import com.trips_enjoy.notification.application.NotificationIngestService
import org.slf4j.LoggerFactory
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.kafka.support.Acknowledgment
import org.springframework.kafka.support.KafkaHeaders
import org.springframework.messaging.handler.annotation.Header
import org.springframework.messaging.handler.annotation.Payload
import org.springframework.stereotype.Component

/**
 * Master consumer for inbound command events per
 * docs/services/notification-service/INTEGRATION.md §"Consumed Events".
 *
 * Topics covered (per the integrator contract):
 *   - trip.*.v1                   (started, arrived, completed, cancelled, reward.granted/reversed)
 *   - food.order.*.v1             (placed..delivered)
 *   - delivery.*.v1               (pickup..failed)
 *   - payment.*.v1                (failed, refund.completed)
 *   - ride.safety.sos.v1          (urgent; bypasses quiet hours)
 *   - pricing.geo_config.updated.v1 (best-effort; suppressed)
 *   - *.deal.*.v1                 (Phase 7.5)
 *   - chat.message.offline_delivery_required.v1 (Phase 7.7)
 *
 * This slice fully handles `trip.completed` (logs + acks); all other events
 * are accepted into the inbox and logged so the consumer compiles and
 * survives a topic landing.
 */
@Component
class NotificationCommandConsumer(
	private val ingest: NotificationIngestService,
) {
	private val log = LoggerFactory.getLogger(javaClass)

	@KafkaListener(
		topics = [
			"trip.started", "trip.arrived", "trip.completed", "trip.cancelled",
			"trip.reward.granted", "trip.reward.reversed",
			"food.order.placed", "food.order.accepted", "food.order.preparing", "food.order.ready", "food.order.delivered", "food.order.cancelled",
			"delivery.pickup", "delivery.in_transit", "delivery.completed", "delivery.failed",
			"payment.failed", "payment.refund.completed",
			"ride.safety.sos",
			"pricing.geo_config.updated",
			"chat.message.offline_delivery_required",
		],
		groupId = "\${notification-service.consumer.group-id:notification-service}",
		containerFactory = "notificationKafkaListenerContainerFactory",
	)
	fun consume(
		@Payload payload: String,
		@Header(KafkaHeaders.RECEIVED_TOPIC) topic: String,
		@Header(KafkaHeaders.OFFSET) offset: Long,
		ack: Acknowledgment,
	) {
		try {
			val envelope = ingest.readEnvelope(payload)
			val eventId = envelope["event_id"]?.toString() ?: return
			val accepted = ingest.accept(topic, "notification_command_consumer", envelope)
			if (accepted && topic == "trip.completed") {
				// Slice: dispatch the headline flow. Full Phase 7.5/7.6/7.7
				// dispatch maps land in their respective consumer handlers.
				val tripId = envelope["data"]?.let { (it as? Map<*, *>)?.get("trip_id")?.toString() }
					?.let(java.util.UUID::fromString)
				val correlationId = envelope["correlation_id"]?.toString()?.let(java.util.UUID::fromString)
					?: java.util.UUID.randomUUID()
				if (tripId != null && correlationId != null) {
					ingest.dispatchTripCompleted(
						eventId = java.util.UUID.fromString(eventId),
						tripId = tripId,
						correlationId = correlationId,
						actorId = java.util.UUID.fromString(envelope["aggregate_id"]?.toString() ?: java.util.UUID.randomUUID().toString()),
					)
				}
			}
			ingest.markProcessed(java.util.UUID.fromString(eventId), "notification_command_consumer")
			ack.acknowledge()
		} catch (exception: Exception) {
			log.warn("notification command consumer failed topic={} offset={}: {}", topic, offset, exception.message)
			// Rethrow so the container's DLQ recoverer routes.
			throw exception
		}
	}
}