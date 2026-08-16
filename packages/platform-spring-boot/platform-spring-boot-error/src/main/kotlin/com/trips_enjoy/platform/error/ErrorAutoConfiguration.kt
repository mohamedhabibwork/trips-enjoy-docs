package com.trips_enjoy.platform.error

import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.ComponentScan
import org.springframework.context.annotation.Configuration

/**
 * Auto-configuration for the platform-spring-boot-error module.
 * Registers the [GlobalExceptionHandler] as a Spring bean by component-scan.
 */
@Configuration
@ComponentScan
@EnableConfigurationProperties
internal class ErrorAutoConfiguration
