package com.trips_enjoy.notification.application

import com.fasterxml.jackson.databind.ObjectMapper
import com.trips_enjoy.notification.domain.InboxEvent
import com.trips_enjoy.notification.domain.InboxEventRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

/**
 * Kafka event dispatcher per docs/services/notification-service/WORKFLOWS §2
 * + EVENT_ARCHITECTURE.md §9 (inbox dedup).
 *
 *  - On every inbound event: INSERT INTO notification.inbox ON CONFLICT
 *    (event_id, consumer) DO NOTHING (Skips duplicates via
 *    `existsByEventIdAndConsumer`).
 *  - Delegates to the consumer caller's handler (single `trip.completed`
 *    handler in this slice; other topics are logged + acked).
 *  - Updates `processed_at` on success.
 */
@Service
class NotificationIngestService(
	private val inbox: InboxEventRepository,
	private val mapper: ObjectMapper,
	private val sendService: NotificationSendService,
) {
	private val log = LoggerFactory.getLogger(javaClass)

	/** Returns `true` if the event was newly inserted (not a duplicate). */
	@Transactional
	fun accept(topic: String, consumer: String, envelope: Map<String, Any?>): Boolean {
		val eventId = envelope["event_id"]?.toString()?.let(UUID::fromString) ?: return false
		if (inbox.existsByEventIdAndConsumer(eventId, consumer)) return false
		inbox.save(
			InboxEvent(
				eventId = eventId,
				topic = topic,
				consumer = consumer,
				receivedAt = Instant.now(),
			),
		)
		return true
	}

	@Transactional
	fun markProcessed(eventId: UUID, consumer: String, error: String? = null) {
		val row = inbox.findById(eventId).orElse(null) ?: return
		row.processedAt = Instant.now()
		row.error = error
	}

	/**
	 * Convenience for tests + the dispatch handler — synthesise an envelope
	 * and drive a send. Caller supplies the `trip.completed.v1` payload.
	 */
	fun dispatchTripCompleted(eventId: UUID, tripId: UUID, correlationId: UUID, actorId: UUID) {
		val userId = UUID.randomUUID() // Resolved via customer-service in real impl
		sendService.send(
			NotificationSendService.SendRequestInput(
				userId = userId,
				templateName = "trip.completed",
				data = mapOf("trip_id" to tripId.toString()),
				dedupKey = "trip:event:$tripId:trip.completed",
				localeHint = "en",
				actorId = actorId,
				actorIdempotencyKey = eventId,
				correlationId = correlationId,
				service = "trip",
			),
		)
	}

	/** Test seam — JSON parse helper. */
	fun readEnvelope(payload: String): Map<String, Any?> =
		mapper.readValue(payload, MAP_TYPE)

	companion object {
		private val MAP_TYPE = objectMapperType()
		private fun objectMapperType(): com.fasterxml.jackson.core.type.TypeReference<Map<String, Any?>> =
			object : com.fasterxml.jackson.core.type.TypeReference<Map<String, Any?>>() {}
	}
}