package com.trips_enjoy.platform.web

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import jakarta.servlet.Filter

/**
 * Auto-configuration for the platform-spring-boot-web module.
 *
 * Registers the [RequestCorrelationFilter] as a Spring bean so it runs
 * early in the servlet filter chain (before security). The filter is
 * component-scanned via [Configuration] + [Bean] declaration.
 *
 * Phase 0 (ADR-0030 conformance): every bean here is guarded with
 * `@ConditionalOnMissingBean` so that service-local shadow classes can
 * be deleted without leaving an orphan wiring.
 */
@Configuration
@EnableConfigurationProperties(PiiRedactionProperties::class)
internal class WebAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(name = ["requestCorrelationFilter"])
    fun requestCorrelationFilter(): RequestCorrelationFilter = RequestCorrelationFilter()

    @Bean
    @ConditionalOnMissingBean(PiiRedactor::class)
    fun piiRedactor(properties: PiiRedactionProperties): PiiRedactor = PiiRedactor(properties)
}
