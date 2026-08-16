package com.trips_enjoy.ledger.config

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.KotlinModule
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary

/**
 * Primary Jackson 2 ObjectMapper for application services and outbox payload
 * serialization. Mirrors audit-service / identity-service so all Spring Boot
 * 4 services in the platform share one Jackson entrypoint (Spring Boot 4
 * ships Jackson 3 as the default).
 */
@Configuration
class JacksonConfiguration {
    @Bean
    @Primary
    @ConditionalOnMissingBean(name = ["jackson2ObjectMapper"])
    fun jackson2ObjectMapper(): ObjectMapper = ObjectMapper()
        .registerModule(JavaTimeModule())
        .registerModule(KotlinModule.Builder().build())
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
}
