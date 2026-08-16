package com.trips_enjoy.configuration.config

import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter
import org.springframework.security.web.SecurityFilterChain

/**
 * Spring Security wiring for configuration-service.
 *
 * - Bearer JWT (RS256) validated against the platform Keycloak JWKS.
 * - Roles and scopes are derived from the standard Keycloak claim shape:
 *     scope         -> SCOPE_<name>
 *     realm_access  -> ROLE_<name>
 *     resource_access -> ROLE_<name> + SCOPE_<name>
 * - Public paths: actuator health/info/prometheus, OpenAPI, swagger-ui.
 * - Everything else requires an authenticated principal.
 *
 * Per-INTEGRATION.md §1, the per-endpoint authority requirement is enforced
 * via @PreAuthorize on the controllers (config.read / config.admin / etc.).
 */
@Configuration
@EnableMethodSecurity
class SecurityConfiguration {
    @Bean
    fun securityFilterChain(http: HttpSecurity): SecurityFilterChain =
        http
            .csrf { it.disable() }
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
            .authorizeHttpRequests { auth ->
                auth
                    .requestMatchers(
                        "/health",
                        "/ready",
                        "/started",
                        "/actuator/health/**",
                        "/actuator/info",
                        "/actuator/prometheus",
                        "/openapi.json/**",
                        "/v3/api-docs/**",
                        "/docs/**",
                    ).permitAll()
                    .anyRequest()
                    .authenticated()
            }.oauth2ResourceServer { it.jwt { jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter()) } }
            .build()

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
                        .map { SimpleGrantedAuthority("SCOPE_$it") }
                val realmAccess = jwt.getClaimAsMap("realm_access").orEmpty()
                val realmRoles =
                    (realmAccess["roles"] as? Collection<*>)
                        ?.filterIsInstance<String>()
                        .orEmpty()
                        .map { SimpleGrantedAuthority("ROLE_$it") }
                val resources = jwt.getClaimAsMap("resource_access").orEmpty()
                val clientRoles =
                    resources.values
                        .filterIsInstance<Map<*, *>>()
                        .flatMap { (it["roles"] as? Collection<*>)?.filterIsInstance<String>().orEmpty() }
                        .flatMap { listOf(SimpleGrantedAuthority("ROLE_$it"), SimpleGrantedAuthority("SCOPE_$it")) }
                (scopes + realmRoles + clientRoles).toSet()
            }
        }
}
