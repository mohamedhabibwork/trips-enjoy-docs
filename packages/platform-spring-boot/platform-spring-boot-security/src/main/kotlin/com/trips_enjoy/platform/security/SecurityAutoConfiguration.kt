package com.trips_enjoy.platform.security

import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.ComponentScan
import org.springframework.context.annotation.Configuration

@Configuration
@ComponentScan
@EnableConfigurationProperties(SecurityProperties::class)
internal class SecurityAutoConfiguration
