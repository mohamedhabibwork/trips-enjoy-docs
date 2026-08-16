package com.trips_enjoy.customer.config

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.KotlinModule
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary

/**
 * Jackson 2 ObjectMapper wiring.
 *
 * Spring Boot 4 ships Jackson 3 by default; this service declares a
 * `@Primary` Jackson 2 mapper for downstream library compatibility
 * (Spring Kafka, json-schema-validator, etc.).
 */
@Configuration
class JacksonConfiguration {
    @Bean
    @Primary
    @ConditionalOnMissingBean(name = ["jackson2ObjectMapper"])
    fun jackson2ObjectMapper(): ObjectMapper =
        ObjectMapper()
            .registerModule(JavaTimeModule())
            .registerModule(KotlinModule.Builder().build())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
}
