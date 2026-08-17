package com.trips_enjoy.platform.messaging

import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Wires the canonical inbox cleanup job.
 *
 * Activates when:
 *
 * 1. [InboxEventCanonical] is on the classpath, AND
 * 2. The service has declared a repository extending
 *    [InboxRepositoryCanonicalCleanup] (so a bean of that type exists),
 *    AND
 * 3. The service has NOT declared its own [InboxCleanupJobCanonical]
 *    bean — service-local cleanup jobs continue to win.
 *
 * Schedule + retention are read from `platform.inbox.*` (see
 * [InboxCleanupPropertiesCanonical]).
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnClass(InboxEventCanonical::class)
@ConditionalOnBean(type = ["com.trips_enjoy.platform.messaging.InboxRepositoryCanonicalCleanup"])
@ConditionalOnProperty(prefix = "platform.inbox", name = ["enabled"], matchIfMissing = true)
@EnableConfigurationProperties(InboxCleanupPropertiesCanonical::class)
open class InboxAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    open fun inboxCleanupJobCanonical(
        repository: InboxRepositoryCanonicalCleanup,
        properties: InboxCleanupPropertiesCanonical,
    ): InboxCleanupJobCanonical =
        InboxCleanupJobCanonical(repository, properties)
}