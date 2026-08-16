package com.trips_enjoy.platform.observability

import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.ComponentScan
import org.springframework.context.annotation.Configuration

@Configuration
@ComponentScan
@EnableConfigurationProperties(ObservabilityProperties::class)
internal class ObservabilityAutoConfiguration {

    @Bean
    fun traceContextMdcFilter(): TraceContextMdcFilter = TraceContextMdcFilter()
}
