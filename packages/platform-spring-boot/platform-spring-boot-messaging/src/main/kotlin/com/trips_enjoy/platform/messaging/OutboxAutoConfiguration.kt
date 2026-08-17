package com.trips_enjoy.platform.messaging

import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.kafka.core.KafkaTemplate

/**
 * Wires the canonical outbox publisher as a Spring bean.
 *
 * Activates only when the consuming service has:
 *
 * 1. Adopted `platform-spring-boot-messaging` (so
 *    [OutboxEventCanonical] is on the classpath), AND
 * 2. Declared a repository extending [OutboxRepositoryCanonical]
 *    (so an `OutboxRepositoryCanonical` bean exists), AND
 * 3. NOT declared its own [OutboxPublisherCanonical] bean — this lets
 *    service-local publishers (in services that haven't migrated to
 *    canonical yet) continue to win via `@ConditionalOnMissingBean`.
 *
 * Cadence is read from `platform.outbox.*` (see [OutboxProperties]);
 * the default `@Scheduled(fixedDelayString = "1000")` is wired in the
 * publisher itself.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnClass(OutboxEventCanonical::class)
@ConditionalOnBean(type = ["com.trips_enjoy.platform.messaging.OutboxRepositoryCanonical"])
@ConditionalOnProperty(prefix = "platform.outbox", name = ["enabled"], matchIfMissing = true)
@EnableConfigurationProperties(OutboxProperties::class)
open class OutboxAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    open fun outboxPublisherCanonical(
        repository: OutboxRepositoryCanonical,
        kafkaTemplate: KafkaTemplate<String, String>,
        properties: OutboxProperties,
    ): OutboxPublisherCanonical =
        OutboxPublisherCanonical(repository, kafkaTemplate, properties)
}