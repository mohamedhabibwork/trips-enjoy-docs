package com.trips_enjoy.configuration.config

import com.fasterxml.jackson.databind.DeserializationFeature
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
 * Phase A (platform DRY) workaround: the platform-spring-boot-web module
 * publishes an equivalent JacksonConfiguration (see
 * `packages/platform-spring-boot/platform-spring-boot-web/.../JacksonConfiguration.kt`),
 * but its `AutoConfiguration` marker class does NOT `@Import` the inner
 * `@Configuration` classes, so the bean is never registered via Spring
 * Boot autoconfig. Additionally, the class is Kotlin `internal`, so it
 * cannot be `@Import`ed by name from this service module.
 *
 * This local copy is therefore retained as a temporary workaround
 * (PLAN.md T-CON-P90-09) and is functionally identical to the platform
 * version. It MUST be deleted once the platform marker either (a) adds
 * `@Import(JacksonConfiguration::class, WebAutoConfiguration::class)` or
 * (b) makes those classes `public` and lists them directly in the
 * `AutoConfiguration.imports` SPI.
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
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
}