package com.trips_enjoy.configuration.config

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
 *
 * The platform auto-installs a Prometheus registry when the
 * `micrometer-registry-prometheus` dependency is on the classpath;
 * the `application.yml` already exposes the `/actuator/prometheus`
 * scrape endpoint via Spring Boot Actuator.
 *
 * The configuration-service is in the read-hot path (every other service
 * consumes its GET), so cardinality discipline matters here more than
 * for write-cold services: we drop `jvm.classes.loaded` (high churn)
 * and `jvm.gc.*` is preserved but with `region`/`tenant` as a low-card
 * tag set.
 */
@Configuration
class MetricsConfiguration {
    @Bean
    fun configurationServiceMeterRegistryCustomizer(
        @Value("\${spring.profiles.active:dev}") activeProfile: String,
        @Value("\${REGION:local}") region: String,
    ): MeterRegistryCustomizer<MeterRegistry> =
        MeterRegistryCustomizer<MeterRegistry> { registry: MeterRegistry ->
            registry
                .config()
                .commonTags(
                    "service",
                    "configuration-service",
                    "env",
                    activeProfile,
                    "region",
                    region,
                    "tenant",
                    "global",
                ).meterFilter(MeterFilter.denyNameStartsWith("jvm.classes.loaded"))
        }
}
