package com.trips_enjoy.customer.config

import com.trips_enjoy.platform.security.SecurityProperties
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter
import org.springframework.security.web.SecurityFilterChain

/**
 * Spring Security wiring for customer-service.
 *
 * Phase C (platform DRY): the platform `SecurityAutoConfiguration` is
 * picked up via Spring Boot's auto-configuration imports (the platform
 * registers it through `META-INF/spring/...AutoConfiguration.imports`),
 * so the admin filter chain and the CORS configuration source are
 * inherited as-is. The default `SecurityProperties.publicPaths` are
 * extended with the 6 paths unique to this service (`/health`, `/ready`,
 * `/started`, `/actuator/info`, `/openapi.json/DOUBLE_STAR`,
 * `/docs/DOUBLE_STAR`).
 *
 * The platform's `defaultSecurityFilterChain` bean is guarded by
 * `@ConditionalOnMissingBean(name = ["defaultSecurityFilterChain"])`,
 * so the subclass-supplied `@Primary` filter chain wins for the main
 * request flow. The admin chain and CORS source remain platform-owned.
 *
 * Service-specific beans still defined here:
 *   - `jwtDecoder` — NimbusJwtDecoder wired to the customer-service
 *     Keycloak JWKS URI (`customer-service.keycloak.jwks-uri`).
 *   - `jwtAuthenticationConverter` — claims-to-authorities mapping
 *     aligned with ADR-0025 (`SCOPE_<UPPER>`, `ROLE_<UPPER>`,
 *     `ROLE_<CLIENT>_<UPPER>`).
 */
@Configuration
@EnableMethodSecurity
@EnableConfigurationProperties(SecurityProperties::class)
class SecurityConfiguration {
    /**
     * Service-specific public paths (the 6 paths unique to
     * customer-service, layered on top of the platform defaults).
     */
    private val customerServicePublicPaths: List<String> =
        listOf(
            "/health",
            "/ready",
            "/started",
            "/actuator/info",
            "/openapi.json/**",
            "/docs/**",
        )

    @Bean
    @Primary
    fun defaultSecurityFilterChain(
        http: HttpSecurity,
        properties: SecurityProperties,
    ): SecurityFilterChain {
        val allPublicPaths = properties.publicPaths + customerServicePublicPaths
        http
            .csrf { it.disable() }
            .cors { it.configurationSource(corsConfigurationSource(properties)) }
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
            .authorizeHttpRequests { auth ->
                auth.requestMatchers(*allPublicPaths.toTypedArray()).permitAll()
                auth.anyRequest().authenticated()
            }
            .oauth2ResourceServer { oauth2 ->
                oauth2.jwt { jwt ->
                    jwt.jwtAuthenticationConverter(jwtAuthenticationConverter())
                }
            }
        return http.build()
    }

    @Bean
    fun jwtDecoder(
        @Value("\${customer-service.keycloak.jwks-uri}") jwksUri: String,
    ): JwtDecoder = NimbusJwtDecoder.withJwkSetUri(jwksUri).build()

    @Bean
    fun jwtAuthenticationConverter(): JwtAuthenticationConverter =
        JwtAuthenticationConverter().apply {
            setJwtGrantedAuthoritiesConverter { jwt ->
                val scopes =
                    jwt
                        .getClaimAsString("scope")
                        ?.split(' ')
                        ?.filter(String::isNotBlank)
                        .orEmpty()
                        .map { SimpleGrantedAuthority("SCOPE_${it.uppercase()}") }
                val realmAccess = jwt.getClaimAsMap("realm_access").orEmpty()
                val realmRoles =
                    (realmAccess["roles"] as? Collection<*>)
                        ?.filterIsInstance<String>()
                        .orEmpty()
                        .map { SimpleGrantedAuthority("ROLE_${it.uppercase()}") }
                val resources = jwt.getClaimAsMap("resource_access").orEmpty()
                val clientRoles =
                    resources.values
                        .filterIsInstance<Map<*, *>>()
                        .flatMap { (it["roles"] as? Collection<*>)?.filterIsInstance<String>().orEmpty() }
                        .flatMap { role ->
                            listOf(
                                SimpleGrantedAuthority("ROLE_${role.uppercase()}"),
                                SimpleGrantedAuthority("SCOPE_${role.uppercase()}"),
                            )
                        }
                (scopes + realmRoles + clientRoles).toSet()
            }
        }

    /**
     * Re-creates the platform's CORS source from the bound
     * [SecurityProperties]. Kept local so the customer-service
     * filter chain (which is the `@Primary` one) and the platform
     * admin chain share the same CORS configuration.
     */
    private fun corsConfigurationSource(properties: SecurityProperties): org.springframework.web.cors.CorsConfigurationSource {
        val config = org.springframework.web.cors.CorsConfiguration().apply {
            allowedOrigins = properties.cors.allowedOrigins
            allowedMethods = properties.cors.allowedMethods
            allowedHeaders = properties.cors.allowedHeaders
            allowCredentials = true
            maxAge = 3600L
        }
        val source = org.springframework.web.cors.UrlBasedCorsConfigurationSource()
        source.registerCorsConfiguration("/**", config)
        return source
    }
}
