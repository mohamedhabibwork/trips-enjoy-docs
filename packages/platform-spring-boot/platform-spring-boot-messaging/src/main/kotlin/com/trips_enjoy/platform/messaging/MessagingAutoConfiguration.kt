package com.trips_enjoy.platform.messaging

import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.ComponentScan
import org.springframework.context.annotation.Configuration

@Configuration
@ComponentScan
@EnableConfigurationProperties(MessagingProperties::class)
internal class MessagingAutoConfiguration
