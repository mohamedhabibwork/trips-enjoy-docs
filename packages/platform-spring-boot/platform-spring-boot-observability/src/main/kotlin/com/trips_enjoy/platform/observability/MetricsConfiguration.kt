package com.trips_enjoy.platform.observability

import io.micrometer.core.instrument.MeterRegistry
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Stamps platform-wide tags on every metric emitted. Every service that
 * consumes the starter inherits these tags automatically in
 * `MeterRegistry` (Prometheus exposition in `/actuator/prometheus`).
 */
@Configuration
internal class MetricsConfiguration {

    @Bean
    @ConditionalOnMissingBean(name = ["platformMetricsCustomizer"])
    fun platformMetricsCustomizer(
        properties: ObservabilityProperties,
        meterRegistry: ObjectProvider<MeterRegistry>,
    ): () -> Unit = {
        meterRegistry.ifAvailable { registry ->
            registry.config().commonTags(
                "service", properties.service,
                "env", properties.env,
                "region", properties.region,
                "tenant", properties.tenant,
            )
        }
    }
}
