package com.trips_enjoy.platform.data

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
@EnableConfigurationProperties
internal class DataAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(PlatformAuditorAware::class)
    fun platformAuditorAware(): PlatformAuditorAware = PlatformAuditorAware()
}
