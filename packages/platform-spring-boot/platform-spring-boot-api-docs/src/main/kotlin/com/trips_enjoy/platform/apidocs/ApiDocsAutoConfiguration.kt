package com.trips_enjoy.platform.apidocs

import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.ComponentScan
import org.springframework.context.annotation.Configuration

@Configuration
@ComponentScan
@EnableConfigurationProperties(ApiDocsProperties::class)
internal class ApiDocsAutoConfiguration
