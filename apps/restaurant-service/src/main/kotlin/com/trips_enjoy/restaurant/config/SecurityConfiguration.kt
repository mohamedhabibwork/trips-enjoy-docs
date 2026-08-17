package com.trips_enjoy.restaurant.config

import com.trips_enjoy.platform.security.SecurityProperties
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.web.SecurityFilterChain
import org.springframework.web.cors.CorsConfiguration
import org.springframework.web.cors.CorsConfigurationSource
import org.springframework.web.cors.UrlBasedCorsConfigurationSource

/**
 * Spring Security wiring for restaurant-service.
 *
 * Phase C (platform DRY): the platform SecurityAutoConfiguration is
 * picked up via Spring Boot auto-configuration imports so the default
 * SecurityFilterChain and CORS configuration source are inherited from
 * the platform. The platform's defaultSecurityFilterChain bean is guarded
 * by @ConditionalOnMissingBean, so the subclass-supplied @Primary
 * filter chain (this one) wins for the main request flow.
 *
 * Service-specific overrides:
 *   - admin authority — restaurant.admin for /admin/v1 (not the
 *     generic platform.admin).
 *   - feature flag — restaurant.security.enabled=false => permitAll.
 *   - public paths — service-specific paths layered on top of
 *     SecurityProperties.publicPaths.
 */
@Configuration
@EnableMethodSecurity
@EnableConfigurationProperties(SecurityProperties::class)
class SecurityConfiguration {

    private val restaurantServicePublicPaths: List<String> = listOf(
        "/openapi.json",
        "/openapi.yaml",
        "/docs",
        "/docs/**",
        "/v3/api-docs/**",
        "/swagger-ui/**",
        "/swagger-ui.html",
    )

    @Bean
    @Primary
    fun defaultSecurityFilterChain(
        http: HttpSecurity,
        properties: SecurityProperties,
        @Value("\${restaurant.security.enabled:true}") securityEnabled: Boolean,
    ): SecurityFilterChain {
        val allPublicPaths = properties.publicPaths + restaurantServicePublicPaths
        val builder = http
            .csrf { it.disable() }
            .cors { it.configurationSource(corsConfigurationSource(properties)) }
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
            .authorizeHttpRequests { auth ->
                auth.requestMatchers(*allPublicPaths.toTypedArray()).permitAll()
                if (securityEnabled) {
                    auth.requestMatchers("/admin/v1/**").hasAuthority("restaurant.admin")
                    auth.anyRequest().authenticated()
                } else {
                    auth.anyRequest().permitAll()
                }
            }
        return if (securityEnabled) {
            builder.oauth2ResourceServer { oauth2 -> oauth2.jwt { } }.build()
        } else {
            builder.build()
        }
    }

    private fun corsConfigurationSource(properties: SecurityProperties): CorsConfigurationSource {
        val config = CorsConfiguration().apply {
            allowedOrigins = properties.cors.allowedOrigins
            allowedMethods = properties.cors.allowedMethods
            allowedHeaders = properties.cors.allowedHeaders
            allowCredentials = true
            maxAge = 3600L
        }
        val source = UrlBasedCorsConfigurationSource()
        source.registerCorsConfiguration("/**", config)
        return source
    }
}
