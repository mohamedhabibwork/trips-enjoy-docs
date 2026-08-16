package com.trips_enjoy.identity.config

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.KotlinModule
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary

/**
 * Spring Boot 4 ships with Jackson 3 (`tools.jackson`) by default. The legacy
 * `IdentityApplicationService` and `AdminAuditPublisher` use the Jackson 2
 * `com.fasterxml.jackson.databind.ObjectMapper`. We register a primary
 * Jackson 2 ObjectMapper bean so both the new (Jackson 3) and existing
 * (Jackson 2) code paths can coexist during the migration.
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
