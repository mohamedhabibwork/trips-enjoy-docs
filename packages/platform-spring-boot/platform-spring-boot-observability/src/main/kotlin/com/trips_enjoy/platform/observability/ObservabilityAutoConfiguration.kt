package com.trips_enjoy.platform.observability

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.ComponentScan
import org.springframework.context.annotation.Configuration

@Configuration
@ComponentScan
@EnableConfigurationProperties(ObservabilityProperties::class)
internal class ObservabilityAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(TraceContextMdcFilter::class)
    fun traceContextMdcFilter(): TraceContextMdcFilter = TraceContextMdcFilter()
}
