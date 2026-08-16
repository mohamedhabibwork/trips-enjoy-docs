package com.trips_enjoy.platform.web

import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Auto-configuration for the platform-spring-boot-web module.
 *
 * Registers the [RequestCorrelationFilter] as a Spring bean so it runs
 * early in the servlet filter chain (before security). The filter is
 * component-scanned via [Configuration] + [Bean] declaration.
 */
@Configuration
@EnableConfigurationProperties(PiiRedactionProperties::class)
internal class WebAutoConfiguration {

    @Bean
    fun requestCorrelationFilter(): RequestCorrelationFilter = RequestCorrelationFilter()

    @Bean
    fun piiRedactor(properties: PiiRedactionProperties): PiiRedactor = PiiRedactor(properties)
}
