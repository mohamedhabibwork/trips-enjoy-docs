package com.trips_enjoy.driver.config

import com.trips_enjoy.platform.security.SecurityProperties
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary
import org.springframework.security.config.Customizer
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder
import org.springframework.security.web.SecurityFilterChain
import org.springframework.web.cors.CorsConfiguration
import org.springframework.web.cors.CorsConfigurationSource
import org.springframework.web.cors.UrlBasedCorsConfigurationSource

/**
 * Spring Security wiring for driver-service.
 *
 * Phase C (platform DRY): the platform `SecurityAutoConfiguration` is
 * picked up via Spring Boot's auto-configuration imports, so the admin
 * filter chain and the CORS configuration source are inherited as-is.
 * The default `SecurityProperties.publicPaths` are extended with the
 * 4 paths unique to this service: `/openapi.json`, `/openapi.yaml`,
 * `/docs`, and `/docs` plus its wildcard child paths.
 *
 * The platform's `defaultSecurityFilterChain` bean is guarded by
 * `@ConditionalOnMissingBean(name = ["defaultSecurityFilterChain"])`,
 * so the subclass-supplied `@Primary` filter chain wins for the main
 * request flow. The admin chain and the default CORS source remain
 * platform-owned.
 *
 * Service-specific beans still defined here: `jwtDecoder`
 * (NimbusJwtDecoder wired to the driver-service Keycloak JWKS URI).
 *
 * The `driver.security.enabled` toggle (default true) keeps the
 * original escape hatch: when set to false, the JWT resource-server
 * filter is omitted (Testcontainers dev wiring). When true, the
 * canonical JWT-resource-server flow is applied with the
 * `JwtAuthenticationConverter` aligned with ADR-0025 (SCOPE prefix).
 */
@Configuration
@EnableMethodSecurity
@EnableConfigurationProperties(SecurityProperties::class)
class SecurityConfiguration {

    private val driverServicePublicPaths: List<String> =
        listOf(
            "/openapi.json",
            "/openapi.yaml",
            "/docs",
            "/docs/**",
        )

    @Bean
    @Primary
    fun defaultSecurityFilterChain(
        http: HttpSecurity,
        properties: SecurityProperties,
        @Value("\${driver.security.enabled:true}") securityEnabled: Boolean,
    ): SecurityFilterChain {
        val allPublicPaths = properties.publicPaths + driverServicePublicPaths
        val builder = http
            .csrf { it.disable() }
            .cors { it.configurationSource(corsConfigurationSource(properties)) }
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
            .authorizeHttpRequests { auth ->
                auth.requestMatchers(*allPublicPaths.toTypedArray()).permitAll()
                if (securityEnabled) {
                    auth.requestMatchers("/admin/v1/**").hasAuthority("driver.admin")
                    auth.anyRequest().authenticated()
                } else {
                    auth.anyRequest().permitAll()
                }
            }
        return if (securityEnabled) {
            builder.oauth2ResourceServer { oauth2 -> oauth2.jwt(Customizer.withDefaults()) }.build()
        } else {
            builder.build()
        }
    }

    @Bean
    fun jwtDecoder(
        @Value("\${driver-service.keycloak.jwks-uri}") jwksUri: String,
    ): JwtDecoder =
        NimbusJwtDecoder.withJwkSetUri(jwksUri).build()

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
