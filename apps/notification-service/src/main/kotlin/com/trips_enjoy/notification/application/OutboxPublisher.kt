package com.trips_enjoy.notification.application

import com.trips_enjoy.notification.domain.OutboxEventRepository
import org.slf4j.LoggerFactory
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

/**
 * Polls `notification.outbox` for unpublished rows, sends each to its
 * topic via the string KafkaTemplate, and stamps `published_at` on success.
 * Failures bump `attempts` and capture `last_error` so the operational
 * dashboards can alert.
 *
 * Mirrors audit-service's OutboxPublisher per
 * docs/architecture/EVENT_ARCHITECTURE.md §7.
 */
@Component
class OutboxPublisher(
	private val events: OutboxEventRepository,
	private val kafka: KafkaTemplate<String, String>,
) {
	private val log = LoggerFactory.getLogger(javaClass)

	@Scheduled(fixedDelayString = "\${notification-service.outbox.publish-interval-ms:1000}")
	@Transactional
	fun publishPending() {
		val pending = events.findTop100ByPublishedAtIsNullOrderByCreatedAtAsc()
		if (pending.isEmpty()) return
		pending.forEach { event ->
			try {
				kafka.send(event.topic, event.aggregateId?.toString() ?: event.id.toString(), event.payload).get()
				event.publishedAt = Instant.now()
			} catch (exception: Exception) {
				event.attempts += 1
				event.lastError = exception.javaClass.simpleName + ": " + exception.message
				log.warn(
					"Failed to publish outbox event {} to topic {}: {}",
					event.id, event.topic, exception.message,
				)
			}
		}
	}
}