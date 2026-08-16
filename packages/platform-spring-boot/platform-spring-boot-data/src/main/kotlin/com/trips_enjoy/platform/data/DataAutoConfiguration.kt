package com.trips_enjoy.platform.data

import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
@EnableConfigurationProperties
internal class DataAutoConfiguration {

    @Bean
    fun platformAuditorAware(): PlatformAuditorAware = PlatformAuditorAware()
}
