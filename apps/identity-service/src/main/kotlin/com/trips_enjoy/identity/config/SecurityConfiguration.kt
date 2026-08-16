package com.trips_enjoy.identity.config

import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.annotation.Order
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.web.SecurityFilterChain
import org.springframework.web.client.RestClient

/**
 * Resource-server wiring for `identity-service`.
 *
 * Two filter chains (ordered):
 *
 *  1. `oidcFilterChain` — OIDC endpoints + probes. Permits all (these endpoints
 *     proxy Keycloak's public OIDC surface and the service's own probes; no
 *     JWT is required). Order = 1 so this chain wins over the JWT chain.
 *
 *  2. `mainFilterChain` — everything else (v1 + admin + actuator). Requires a
 *     valid JWT bearer (resource-server). All controller-level
 *     `@PreAuthorize` annotations are honoured.
 */
@Configuration
@EnableMethodSecurity
class SecurityConfiguration {

    @Bean
    fun restClientBuilder(): RestClient.Builder = RestClient.builder()

    @Bean
    fun jwtDecoder(@Value("\${identity.keycloak.jwks-uri}") jwksUri: String): JwtDecoder =
        NimbusJwtDecoder.withJwkSetUri(jwksUri).build()

    @Bean
    fun jwtAuthenticationConverter(): JwtAuthenticationConverter = JwtAuthenticationConverter().apply {
        setJwtGrantedAuthoritiesConverter { jwt ->
            val scopes = jwt.getClaimAsString("scope")?.split(' ')?.filter(String::isNotBlank).orEmpty().map { SimpleGrantedAuthority("SCOPE_$it") }
            val realmAccess = jwt.getClaimAsMap("realm_access").orEmpty()
            val realmRoles = (realmAccess["roles"] as? Collection<*>)?.filterIsInstance<String>().orEmpty().map { SimpleGrantedAuthority("ROLE_$it") }
            val resources = jwt.getClaimAsMap("resource_access").orEmpty()
            val clientRoles = resources.values.filterIsInstance<Map<*, *>>().flatMap { (it["roles"] as? Collection<*>)?.filterIsInstance<String>().orEmpty() }
                .flatMap { listOf(SimpleGrantedAuthority("ROLE_$it"), SimpleGrantedAuthority("SCOPE_$it")) }
            (scopes + realmRoles + clientRoles).toSet()
        }
    }

    @Bean
    @Order(1)
    fun oidcFilterChain(http: HttpSecurity): SecurityFilterChain = http
        .securityMatcher("/oauth2/**", "/.well-known/**", "/health", "/ready", "/started")
        .csrf { it.disable() }
        .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
        .authorizeHttpRequests { it.anyRequest().permitAll() }
        .build()

    @Bean
    fun mainFilterChain(http: HttpSecurity): SecurityFilterChain = http
        .csrf { it.disable() }
        .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
        .authorizeHttpRequests {
            it.requestMatchers(
                "/actuator/health/**", "/actuator/info",
                "/docs", "/docs/**", "/swagger-ui.html", "/swagger-ui/**",
                "/openapi.json", "/openapi.json/**", "/v3/api-docs/**",
            ).permitAll()
                .anyRequest().authenticated()
        }
        .oauth2ResourceServer { it.jwt { jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter()) } }
        .build()
}
