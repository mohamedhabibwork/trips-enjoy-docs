package com.trips_enjoy.notification.config

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.KotlinModule
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary

/**
 * Provides a primary Jackson 2 `ObjectMapper` for the application services
 * (idempotency hashing, outbox payload serialization, admin audit envelopes).
 * Spring Boot 4 ships Jackson 3 as the default; without this bean, app code
 * importing `com.fasterxml.jackson.databind.ObjectMapper` would be ambiguous.
 *
 * Mirrors the audit-service/identity-service pattern.
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