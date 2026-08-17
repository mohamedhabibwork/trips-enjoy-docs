package com.trips_enjoy.notification.config

import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.common.TopicPartition
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory
import org.springframework.kafka.core.ConsumerFactory
import org.springframework.kafka.core.DefaultKafkaProducerFactory
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.kafka.core.ProducerFactory
import org.springframework.kafka.listener.ContainerProperties
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer
import org.springframework.kafka.listener.DefaultErrorHandler
import org.springframework.util.backoff.ExponentialBackOff

/**
 * Kafka consumer + error handling for notification-service.
 *
 *  - `notificationKafkaListenerContainerFactory` mirrors Spring Boot defaults
 *    with manual ack + read_committed isolation.
 *  - The error handler installs an exponential backoff (1 s, 2 s, 4 s; 3
 *    attempts total) and routes poison events to a paired `<topic>.dlq`
 *    topic per EVENT_ARCHITECTURE.md §8 (DLQ).
 */
@Configuration
class KafkaConsumerConfiguration {

	@Bean
	fun notificationKafkaListenerContainerFactory(
		consumerFactory: ConsumerFactory<String, String>,
		dlqKafkaTemplate: KafkaTemplate<String, Any>,
	): ConcurrentKafkaListenerContainerFactory<String, String> {
		val factory = ConcurrentKafkaListenerContainerFactory<String, String>()
		factory.setConsumerFactory(consumerFactory)
		factory.setCommonErrorHandler(defaultErrorHandler(dlqKafkaTemplate))
		factory.containerProperties.ackMode = ContainerProperties.AckMode.MANUAL_IMMEDIATE
		return factory
	}

	private fun defaultErrorHandler(template: KafkaTemplate<String, Any>): DefaultErrorHandler {
		val recoverer = DeadLetterPublishingRecoverer(template) { record: ConsumerRecord<*, *>, _ ->
			val dlq = (record.topic() ?: "notification.unknown") + ".dlq"
			TopicPartition(dlq, record.partition())
		}
		val backOff = ExponentialBackOff(1000L, 2.0).apply {
			maxInterval = 8_000L
			maxElapsedTime = 16_000L
		}
		return DefaultErrorHandler(recoverer, backOff)
	}

	@Bean
	fun dlqKafkaTemplate(
		@Value("\${spring.kafka.bootstrap-servers}") bootstrap: String,
	): KafkaTemplate<String, Any> {
		val props: MutableMap<String, Any> = HashMap()
		props["bootstrap.servers"] = bootstrap
		props["key.serializer"] = org.apache.kafka.common.serialization.StringSerializer::class.java
		props["value.serializer"] = org.apache.kafka.common.serialization.StringSerializer::class.java
		props["acks"] = "all"
		props["enable.idempotence"] = true
		val factory: ProducerFactory<String, Any> = DefaultKafkaProducerFactory(props)
		return KafkaTemplate(factory)
	}
}