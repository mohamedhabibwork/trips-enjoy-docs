package com.trips_enjoy.platform.web

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.KotlinModule
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary

/**
 * Configures the platform's primary [ObjectMapper] for both the servlet
 * `MappingJackson2HttpMessageConverter` and the reactive `WebClient` JSON
 * codecs. Conditionally overrides only if the consuming service does not
 * declare a bean named `jackson2ObjectMapper`.
 */
@Configuration
open class JacksonConfiguration {

    @Bean
    @Primary
    @ConditionalOnMissingBean(name = ["jackson2ObjectMapper"])
    open fun jackson2ObjectMapper(): ObjectMapper = ObjectMapper().apply {
        registerModule(JavaTimeModule())
        registerModule(Jdk8Module())
        registerModule(KotlinModule.Builder().build())
        setSerializationInclusion(JsonInclude.Include.NON_NULL)
        disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
        disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
    }
}
