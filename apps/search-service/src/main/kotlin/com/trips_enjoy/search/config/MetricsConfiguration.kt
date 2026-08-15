package com.trips_enjoy.search.config

import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.config.MeterFilter
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.micrometer.metrics.autoconfigure.MeterRegistryCustomizer
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class MetricsConfiguration(
    @Value("\${spring.application.name}") private val serviceName: String,
    @Value("\${app.env:dev}") private val env: String,
    @Value("\${app.region:local}") private val region: String,
) {
    @Bean
    fun searchServiceMetricsCustomizer(): MeterRegistryCustomizer<MeterRegistry> =
        MeterRegistryCustomizer { registry: MeterRegistry ->
            registry.config()
                .commonTags("service", serviceName, "env", env, "region", region)
                .meterFilter(MeterFilter.denyNameStartsWith("jvm.gc.pause"))
        }
}