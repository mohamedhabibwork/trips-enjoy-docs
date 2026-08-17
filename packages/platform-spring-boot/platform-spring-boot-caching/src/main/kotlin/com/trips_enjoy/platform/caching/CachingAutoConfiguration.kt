package com.trips_enjoy.platform.caching

import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.ComponentScan
import org.springframework.context.annotation.Configuration

@Configuration
@ComponentScan
@EnableConfigurationProperties(CacheProperties::class)
internal class CachingAutoConfiguration
