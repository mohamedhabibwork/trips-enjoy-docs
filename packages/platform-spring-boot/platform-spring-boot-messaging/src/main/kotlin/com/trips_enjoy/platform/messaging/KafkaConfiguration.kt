package com.trips_enjoy.platform.messaging

import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.common.serialization.StringDeserializer
import org.apache.kafka.common.serialization.StringSerializer
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.kafka.annotation.EnableKafka
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory
import org.springframework.kafka.core.ConsumerFactory
import org.springframework.kafka.core.DefaultKafkaConsumerFactory
import org.springframework.kafka.core.DefaultKafkaProducerFactory
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.kafka.core.ProducerFactory
import org.springframework.kafka.listener.ContainerProperties
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer
import org.springframework.kafka.listener.DefaultErrorHandler
import org.springframework.util.backoff.ExponentialBackOff

@ConfigurationProperties("platform.messaging")
data class MessagingProperties(
    val bootstrapServers: String = "localhost:9092",
    val consumerGroup: String = "platform-spring-boot",
    val autoOffsetReset: String = "earliest",
    val outboxIntervalMs: Long = 100,
    val outboxBatchSize: Int = 200,
    val maxRetries: Int = 3,
)

@Configuration
@EnableConfigurationProperties(MessagingProperties::class)
@EnableKafka
internal class KafkaConfiguration {

    @Bean
    @ConditionalOnMissingBean(ProducerFactory::class)
    fun producerFactory(properties: MessagingProperties): ProducerFactory<String, String> {
        val config: MutableMap<String, Any> = HashMap()
        config[ProducerConfig.BOOTSTRAP_SERVERS_CONFIG] = properties.bootstrapServers
        config[ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG] = StringSerializer::class.java
        config[ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG] = StringSerializer::class.java
        config[ProducerConfig.ACKS_CONFIG] = "all"
        config[ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG] = true
        config[ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION] = 5
        config[ProducerConfig.RETRIES_CONFIG] = Int.MAX_VALUE
        config[ProducerConfig.COMPRESSION_TYPE_CONFIG] = "lz4"
        config[ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG] = 120_000
        return DefaultKafkaProducerFactory(config)
    }

    @Bean
    @ConditionalOnMissingBean(KafkaTemplate::class)
    fun kafkaTemplate(producerFactory: ProducerFactory<String, String>): KafkaTemplate<String, String> =
        KafkaTemplate(producerFactory)

    @Bean
    @ConditionalOnMissingBean(ConsumerFactory::class)
    fun consumerFactory(properties: MessagingProperties): ConsumerFactory<String, String> {
        val config: MutableMap<String, Any> = HashMap()
        config[ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG] = properties.bootstrapServers
        config[ConsumerConfig.GROUP_ID_CONFIG] = properties.consumerGroup
        config[ConsumerConfig.AUTO_OFFSET_RESET_CONFIG] = properties.autoOffsetReset
        config[ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG] = false
        config[ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG] = StringDeserializer::class.java
        config[ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG] = StringDeserializer::class.java
        config[ConsumerConfig.ISOLATION_LEVEL_CONFIG] = "read_committed"
        return DefaultKafkaConsumerFactory(config)
    }

    @Bean
    @ConditionalOnMissingBean(name = ["kafkaListenerContainerFactory"])
    fun kafkaListenerContainerFactory(
        consumerFactory: ConsumerFactory<String, String>,
        kafkaTemplate: KafkaTemplate<String, String>,
    ): ConcurrentKafkaListenerContainerFactory<String, String> {
        val backOff = ExponentialBackOff(500L, 2.0).apply {
            maxElapsedTime = 8_000L
        }
        val recoverer = DeadLetterPublishingRecoverer(
            kafkaTemplate,
            { record, _ -> TopicPartition(record.topic() + ".dlq", record.partition()) },
        )
        val factory = ConcurrentKafkaListenerContainerFactory<String, String>()
        factory.setConsumerFactory(consumerFactory)
        factory.setCommonErrorHandler(DefaultErrorHandler(recoverer, backOff))
        return factory
    }
}
