package com.trips_enjoy.audit.config

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.KotlinModule
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary

/**
 * Provides a primary Jackson 2 ObjectMapper for the application services and
 * the canonicalization layer. Mirrors identity-service's pattern so the
 * codebase keeps a single Jackson-2 entrypoint across all Spring Boot 4
 * services (Spring Boot 4 ships Jackson 3 as the default).
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
