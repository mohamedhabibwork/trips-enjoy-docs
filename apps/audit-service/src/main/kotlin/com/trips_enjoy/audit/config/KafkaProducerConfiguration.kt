package com.trips_enjoy.audit.config

import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.common.serialization.StringSerializer
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.kafka.core.DefaultKafkaProducerFactory
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.kafka.core.ProducerFactory

/**
 * Explicit string-typed `KafkaTemplate<String, String>` for application use
 * (the outbox publisher, future event producers).
 *
 * The `KafkaConsumerConfiguration` separately declares a `KafkaTemplate<String,
 * Any>` for the DLQ recoverer. Spring Boot 4 doesn't auto-wire a KafkaTemplate
 * bean by default in this configuration because the auto-config conditions
 * differ between consumer- and producer-side wiring, so we declare both
 * producers explicitly to avoid surprise.
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
