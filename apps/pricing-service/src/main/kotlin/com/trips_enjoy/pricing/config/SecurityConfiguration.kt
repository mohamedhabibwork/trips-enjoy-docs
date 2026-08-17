package com.trips_enjoy.pricing.config

import com.trips_enjoy.platform.security.SecurityProperties
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
 * Spring Security wiring for pricing-service.
 *
 * Phase C (platform DRY): the platform SecurityAutoConfiguration is
 * picked up via Spring Boot auto-configuration imports. The default
 * SecurityProperties.publicPaths are extended with the 4 paths unique
 * to this service.
 *
 * The platforms defaultSecurityFilterChain bean is guarded by
 * ConditionalOnMissingBean (name = defaultSecurityFilterChain), so
 * the subclass-supplied Primary filter chain wins for the main
 * request flow. The admin chain and CORS source remain platform-owned.
 *
 * Service-specific behaviour:
 *   - hasAuthority("pricing.admin") (case-sensitive) on the
 *     /admin/v1/STAR path. The platform hasRole filter requires
 *     ROLE_<name> (lowercase prefix) and would not match the
 *     platform JwtRoleConverter output ROLE_<UPPER>. The local
 *     authority check aligns with the platform role converter.
 *   - The pre-Phase-C pricing.security.enabled toggle is dropped.
 *     It was a dead-code anti-pattern (defaulted to true, never set
 *     to false anywhere) that bypassed defense-in-depth. Security is
 *     always on now; the canonical platform defaults apply.
 */
@Configuration
@EnableMethodSecurity
class SecurityConfiguration {
    /**
     * Service-specific public paths (the 4 paths unique to
     * pricing-service, layered on top of the platform defaults).
     */
    private val pricingServicePublicPaths: List<String> =
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
    ): SecurityFilterChain {
        val allPublicPaths = properties.publicPaths + pricingServicePublicPaths
        http
            .csrf { it.disable() }
            .cors { it.configurationSource(corsConfigurationSource(properties)) }
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
            .authorizeHttpRequests { auth ->
                auth.requestMatchers(*allPublicPaths.toTypedArray()).permitAll()
                auth.requestMatchers("/admin/v1/**").hasAuthority("pricing.admin")
                auth.anyRequest().authenticated()
            }
            .oauth2ResourceServer { oauth2 ->
                oauth2.jwt { it.jwtAuthenticationConverter(com.trips_enjoy.platform.security.JwtRoleConverter()) }
            }
        return http.build()
    }

    /**
     * Re-creates the platforms CORS source from the bound
     * SecurityProperties. Kept local so the pricing-service
     * filter chain (which is the Primary one) and the platform
     * admin chain share the same CORS configuration.
     */
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
