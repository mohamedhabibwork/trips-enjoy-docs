package com.trips_enjoy.notification.config

import org.apache.kafka.common.serialization.StringSerializer
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.kafka.core.DefaultKafkaProducerFactory
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.kafka.core.ProducerFactory

/**
 * Explicit `KafkaTemplate<String, String>` for application use — the outbox
 * publisher, the notification event publishers, and any direct producer in
 * application code.
 *
 * The DLQ recoverer (KafkaConsumerConfiguration) declares its own typed
 * template. We declare both producers explicitly to keep the wiring visible.
 */
@Configuration
class KafkaProducerConfiguration {

	@Bean
	fun stringProducerFactory(
		@Value("\${spring.kafka.bootstrap-servers}") bootstrap: String,
	): ProducerFactory<String, String> {
		val props = mapOf<String, Any>(
			"bootstrap.servers" to bootstrap,
			"key.serializer" to StringSerializer::class.java,
			"value.serializer" to StringSerializer::class.java,
			"acks" to "all",
			"enable.idempotence" to true,
		)
		return DefaultKafkaProducerFactory(props)
	}

	@Bean
	fun stringKafkaTemplate(producerFactory: ProducerFactory<String, String>): KafkaTemplate<String, String> =
		KafkaTemplate(producerFactory)
}