package com.trips_enjoy.customer.config

import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.config.MeterFilter
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.micrometer.metrics.autoconfigure.MeterRegistryCustomizer
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Metric tagging + filter configuration per
 * `docs/architecture/OBSERVABILITY.md` §"Metrics":
 * every metric carries `service`, `env`, `region`, and `tenant` tags.
 */
@Configuration
class MetricsConfiguration {
    @Bean
    fun customerServiceMeterRegistryCustomizer(
        @Value("\${spring.profiles.active:dev}") activeProfile: String,
        @Value("\${REGION:local}") region: String,
    ): MeterRegistryCustomizer<MeterRegistry> =
        MeterRegistryCustomizer<MeterRegistry> { registry: MeterRegistry ->
            registry
                .config()
                .commonTags(
                    "service",
                    "customer-service",
                    "env",
                    activeProfile,
                    "region",
                    region,
                    "tenant",
                    "global",
                ).meterFilter(MeterFilter.denyNameStartsWith("jvm.classes.loaded"))
        }
}
