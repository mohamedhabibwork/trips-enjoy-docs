package com.trips_enjoy.trip.config

import com.trips_enjoy.platform.security.SecurityProperties
import org.springframework.beans.factory.annotation.Value
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
 * Spring Security wiring for trip-service.
 *
 * Phase C (platform DRY): the platform `AutoConfiguration` is picked
 * up via Spring Boot's auto-configuration imports (the platform
 * registers it through `META-INF/spring/...AutoConfiguration.imports`),
 * so the admin filter chain (for `/admin/v1` with double-star suffix per
 * `platform.security.admin.base-path`) and the CORS configuration
 * source are inherited as-is. The default `SecurityProperties.publicPaths`
 * are extended with the 6 paths unique to this service (`/openapi.json`,
 * `/openapi.yaml`, `/docs`, `/docs` + double-star, `/swagger-ui` + double-star,
 * `/swagger-ui.html`).
 *
 * The platform's `defaultSecurityFilterChain` bean is guarded by
 * `@ConditionalOnMissingBean(name = ["defaultSecurityFilterChain"])`,
 * so the subclass-supplied `@Primary` filter chain wins for the main
 * request flow. The admin chain and CORS source remain platform-owned.
 *
 * Service-specific beans still defined here:
 *   - `jwtDecoder` — NimbusJwtDecoder wired to the trip-service
 *     Keycloak JWKS URI (`trip-service.keycloak.jwks-uri`).
 *   - `jwtAuthenticationConverter` — claims-to-authorities mapping
 *     aligned with ADR-0025 (`SCOPE_<UPPER>`, `ROLE_<UPPER>`,
 *     `ROLE_<CLIENT>_<UPPER>`).
 *   - Service-specific scope rules (`SCOPE_trip.write` /
 *     `SCOPE_trip.read` / `SCOPE_trip.admin` plus cross-service
 *     `SCOPE_driver.*` / `SCOPE_rider.*` for the BFF write paths).
 *   - `trip.security.enabled` toggle for local-dev / smoke tests
 *     (the platform default is `true`; this service keeps its
 *     service-local knob for backward compatibility).
 */
@Configuration
@EnableMethodSecurity
class SecurityConfiguration(
    @Value("\${trip.security.enabled:true}") private val securityEnabled: Boolean,
) {

    /**
     * Service-specific public paths (the 5 paths unique to
     * trip-service, layered on top of the platform defaults).
     */
    private val tripServicePublicPaths: List<String> =
        listOf(
            "/openapi.json",
            "/openapi.yaml",
            "/docs",
            "/docs/**",
            "/swagger-ui/**",
            "/swagger-ui.html",
        )

    @Bean
    @Primary
    fun defaultSecurityFilterChain(
        http: HttpSecurity,
        properties: SecurityProperties,
    ): SecurityFilterChain {
        val allPublicPaths = properties.publicPaths + tripServicePublicPaths
        val builder = http
            .csrf { it.disable() }
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
            .authorizeHttpRequests { auth ->
                auth.requestMatchers(*allPublicPaths.toTypedArray()).permitAll()
                if (securityEnabled) {
                    auth.requestMatchers("/v1/trips/*").hasAnyAuthority(
                        "SCOPE_trip.write", "SCOPE_trip.read",
                        "SCOPE_driver.write", "SCOPE_rider.write",
                    )
                    auth.requestMatchers("/admin/v1/**").hasAuthority("SCOPE_trip.admin")
                    auth.anyRequest().authenticated()
                } else {
                    auth.anyRequest().permitAll()
                }
            }
        return if (securityEnabled) {
            builder
                .oauth2ResourceServer { oauth2 ->
                    oauth2.jwt { jwt ->
                        jwt.jwtAuthenticationConverter(jwtAuthenticationConverter())
                    }
                }
                .build()
        } else {
            builder.build()
        }
    }

    @Bean
    fun jwtDecoder(
        @Value("\${trip-service.keycloak.jwks-uri}") jwksUri: String,
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
}