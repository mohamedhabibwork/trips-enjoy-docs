package com.trips_enjoy.notification.integration.events

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.kafka.support.Acknowledgment
import org.springframework.kafka.support.KafkaHeaders
import org.springframework.messaging.handler.annotation.Header
import org.springframework.messaging.handler.annotation.Payload
import org.springframework.stereotype.Component

/**
 * Listens to `configuration.updated` events per
 * docs/services/notification-service/INTEGRATION.md "Consumed Events".
 *
 *  - Hot-reload keys with the `notification.` prefix
 *    (notification.default_locale, notification.channel.priority,
 *     notification.retry.max_attempts, notification.dedup.window_seconds,
 *     notification.whatsapp.approval_required, etc.).
 *  - For this slice the consumer logs the change so the wiring is observable
 *    in the platform dashboards; the actual `notification.*` config refresh
 *    runtime lands in the shared `platform-spring-boot-caching` starter.
 */
@Component
class ConfigurationUpdatedConsumer(
	@Value("\${notification-service.config.prefix:notification.}") private val prefix: String,
) {
	private val log = LoggerFactory.getLogger(javaClass)

	@KafkaListener(
		topics = ["configuration.updated"],
		groupId = "\${notification-service.consumer.config-group-id:notification-service-config}",
		containerFactory = "notificationKafkaListenerContainerFactory",
	)
	fun consume(
		@Payload payload: String,
		@Header(KafkaHeaders.RECEIVED_TOPIC) topic: String,
		ack: Acknowledgment,
	) {
		try {
			if (payload.contains(prefix)) {
				log.info("config updated (matches prefix={}): {}", prefix, payload.take(300))
			}
			ack.acknowledge()
		} catch (exception: Exception) {
			log.warn("config updated consumer failed: {}", exception.message)
			throw exception
		}
	}
}