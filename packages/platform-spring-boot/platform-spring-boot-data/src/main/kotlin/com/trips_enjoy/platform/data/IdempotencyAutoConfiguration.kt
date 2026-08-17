package com.trips_enjoy.platform.data

import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Wires the canonical idempotency service + cleanup job.
 *
 * Activates when:
 *
 * 1. [IdempotencyRecordCanonical] is on the classpath, AND
 * 2. The service has declared a repository extending
 *    [IdempotencyRepositoryCanonical] (so a bean of that type exists),
 *    AND
 * 3. The service has NOT declared its own [PlatformIdempotencyService]
 *    or [IdempotencyCleanupJobCanonical] bean — service-local
 *    implementations continue to win.
 *
 * TTL + cron are read from `platform.idempotency.*` (see
 * [PlatformIdempotencyProperties] and [IdempotencyCleanupPropertiesCanonical]).
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnClass(IdempotencyRecordCanonical::class)
@ConditionalOnBean(type = ["com.trips_enjoy.platform.data.IdempotencyRepositoryCanonical"])
@ConditionalOnProperty(prefix = "platform.idempotency", name = ["enabled"], matchIfMissing = true)
@EnableConfigurationProperties(
    PlatformIdempotencyProperties::class,
    IdempotencyCleanupPropertiesCanonical::class,
)
open class PlatformIdempotencyAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    open fun platformIdempotencyService(
        repository: IdempotencyRepositoryCanonical,
        properties: PlatformIdempotencyProperties,
    ): PlatformIdempotencyService =
        PlatformIdempotencyService(repository, properties)

    @Bean
    @ConditionalOnMissingBean
    open fun idempotencyCleanupJobCanonical(
        repository: IdempotencyRepositoryCanonicalCleanup,
        properties: IdempotencyCleanupPropertiesCanonical,
    ): IdempotencyCleanupJobCanonical =
        IdempotencyCleanupJobCanonical(repository, properties)
}