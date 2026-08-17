package com.trips_enjoy.configuration.config

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

// Spring Security wiring for configuration-service.
//
// Phase C (platform DRY): mirrors the customer-service subclass pattern.
// The platform `SecurityAutoConfiguration` is intended to be picked up
// via Spring Boot's auto-configuration imports (the platform would
// register it through `META-INF/spring/...AutoConfiguration.imports`),
// so the admin filter chain and the CORS configuration source would
// be inherited as-is. The default `SecurityProperties.publicPaths` are
// extended with the paths unique to this service.
//
// The platform's `defaultSecurityFilterChain` bean is guarded by
// `@ConditionalOnMissingBean(name = ["defaultSecurityFilterChain"])`,
// so the subclass-supplied `@Primary` filter chain wins for the main
// request flow. The admin chain and CORS source would remain
// platform-owned.
//
// KNOWN PLATFORM-SIDE BLOCKER (workaround applied locally): the
// platform-security module's `AutoConfiguration.imports` file lists
// an empty marker class (`com.trips_enjoy.platform.security.AutoConfiguration`)
// rather than the real `SecurityAutoConfiguration` that carries the
// `@EnableConfigurationProperties(SecurityProperties::class)` +
// `@ComponentScan` + the two filter chain beans. The same marker-gap
// pattern is called out in T-CON-P90-09 (local `JacksonConfiguration`
// workaround). Until the platform module is fixed, this service must
// explicitly `@EnableConfigurationProperties(SecurityProperties::class)`
// (annotation on the class below) so the `SecurityProperties` bean is
// wired for the `@Primary` filter chain.
//
// Service-specific beans still defined here:
//   - `jwtDecoder` — NimbusJwtDecoder wired to the configuration-service
//     Keycloak JWKS URI (`configuration-service.keycloak.jwks-uri`).
//   - `jwtAuthenticationConverter` — claims-to-authorities mapping
//     aligned with ADR-0025 (`SCOPE_<UPPER>`, `ROLE_<UPPER>`,
//     `ROLE_<CLIENT>_<UPPER>`).
//
// Per-INTEGRATION.md §1, the per-endpoint authority requirement is enforced
// via @PreAuthorize on the controllers (config.read / config.admin / etc.).
@Configuration
@EnableMethodSecurity
@EnableConfigurationProperties(SecurityProperties::class)
class SecurityConfiguration {
    // Service-specific public paths (the paths unique to
    // configuration-service, layered on top of the platform defaults).
    private val configurationServicePublicPaths: List<String> =
        listOf(
            "/health",
            "/ready",
            "/started",
            "/actuator/health",
            "/actuator/health/**",
            "/actuator/info",
            "/actuator/prometheus",
            "/openapi.json",
            "/openapi.json/**",
            "/v3/api-docs",
            "/v3/api-docs/**",
            "/docs/**",
        )

    @Bean
    @Primary
    fun defaultSecurityFilterChain(
        http: HttpSecurity,
        properties: SecurityProperties,
    ): SecurityFilterChain {
        val allPublicPaths = properties.publicPaths + configurationServicePublicPaths
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
        @Value("\${configuration-service.keycloak.jwks-uri}") jwksUri: String,
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

    // Re-creates the platform's CORS source from the bound
    // [SecurityProperties]. Kept local so the configuration-service
    // filter chain (which is the `@Primary` one) and the platform
    // admin chain share the same CORS configuration.
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
