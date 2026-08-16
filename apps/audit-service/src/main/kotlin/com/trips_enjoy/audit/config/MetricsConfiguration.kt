package com.trips_enjoy.audit.config

import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.config.MeterFilter
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.micrometer.metrics.autoconfigure.MeterRegistryCustomizer
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Audit-service metric tagging + filter configuration per
 * `docs/architecture/OBSERVABILITY.md` §"Metrics":
 * every metric carries `service`, `env`, `region`, and `tenant` tags.
 *
 * The platform auto-installs a Prometheus registry when the
 * `micrometer-registry-prometheus` dependency is on the classpath;
 * the `application.yml` already exposes the `/actuator/prometheus`
 * scrape endpoint via Spring Boot Actuator.
 */
@Configuration
class MetricsConfiguration {

    @Bean
    fun auditServiceMeterRegistryCustomizer(
        @Value("\${spring.profiles.active:dev}") activeProfile: String,
        @Value("\${REGION:local}") region: String,
    ): MeterRegistryCustomizer<MeterRegistry> = MeterRegistryCustomizer<MeterRegistry> { registry: MeterRegistry ->
        registry.config()
            .commonTags(
                "service", "audit-service",
                "env", activeProfile,
                "region", region,
                "tenant", "global",
            )
            // Drop the per-JVM "instance" tag from JVM metrics when the
            // pod name is the only thing changing across replicas (it
            // doesn't add cardinality for service-level dashboards).
            .meterFilter(MeterFilter.denyNameStartsWith("jvm.classes.loaded"))
    }
}
