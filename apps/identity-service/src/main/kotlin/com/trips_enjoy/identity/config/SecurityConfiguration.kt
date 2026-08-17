package com.trips_enjoy.identity.config

import com.trips_enjoy.platform.security.SecurityProperties
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary
import org.springframework.core.annotation.Order
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter
import org.springframework.security.web.SecurityFilterChain
import org.springframework.web.client.RestClient

/**
 * Spring Security wiring for `identity-service`.
 *
 * Phase C (platform DRY): the platform `SecurityConfiguration` registers a
 * `defaultSecurityFilterChain` bean guarded by
 * `@ConditionalOnMissingBean(name = ["defaultSecurityFilterChain"])` and an
 * `adminSecurityFilterChain` bean — both are inherited as-is from the
 * platform `platform-spring-boot-security` module. The local config now
 * only contributes service-specific overrides:
 *
 *   1. `oidcFilterChain` — OIDC endpoints + probes (Order=1, `permitAll`).
 *      Pre-empts the JWT chain on `/oauth2/STAR_STAR`, `/.well-known/STAR_STAR`, and the
 *      three probe endpoints so the public OIDC surface and the platform
 *      health checks don't require a token.
 *   2. `mainFilterChain` (`@Primary`) — every other path (v1 + admin +
 *      actuator). Requires a valid JWT bearer; layer the platform
 *      `SecurityProperties.publicPaths` (health, swagger, docs) plus the
 *      identity-service-specific extensions (`/actuator/info`, the swagger
 *      UI page itself).
 *   3. `jwtDecoder` — NimbusJwtDecoder wired to the identity-service
 *      Keycloak JWKS URI (`identity.keycloak.jwks-uri`). The platform does
 *      NOT own a `JwtDecoder` bean, so this stays as a service-local bean.
 *   4. `jwtAuthenticationConverter` — claims-to-authorities mapping
 *      aligned with ADR-0025 (`SCOPE_<UPPER>`, `ROLE_<UPPER>`,
 *      `ROLE_<CLIENT>_<UPPER>`).
 *
 * The platform's `adminSecurityFilterChain` (mounted on
 * `platform.security.admin.basePath` + `/STAR_STAR`, default `/admin/v1/STAR_STAR`) is
 * inherited unchanged.
 */
@Configuration
@EnableMethodSecurity
class SecurityConfiguration {

    /**
     * Service-specific public paths layered on top of the platform
     * defaults (defined in `SecurityProperties.publicPaths`).
     */
    private val identityServicePublicPaths: List<String> =
        listOf(
            "/actuator/info",
            "/docs",
            "/docs/**",
            "/swagger-ui.html",
            "/swagger-ui/**",
            "/openapi.json",
            "/openapi.json/**",
            "/v3/api-docs/**",
        )

    @Bean
    fun restClientBuilder(): RestClient.Builder = RestClient.builder()

    @Bean
    fun jwtDecoder(@Value("\${identity.keycloak.jwks-uri}") jwksUri: String): JwtDecoder =
        NimbusJwtDecoder.withJwkSetUri(jwksUri).build()

    @Bean
    fun jwtAuthenticationConverter(): JwtAuthenticationConverter = JwtAuthenticationConverter().apply {
        setJwtGrantedAuthoritiesConverter { jwt ->
            val scopes = jwt.getClaimAsString("scope")?.split(' ')?.filter(String::isNotBlank).orEmpty().map { SimpleGrantedAuthority("SCOPE_${it.uppercase()}") }
            val realmAccess = jwt.getClaimAsMap("realm_access").orEmpty()
            val realmRoles = (realmAccess["roles"] as? Collection<*>)?.filterIsInstance<String>().orEmpty().map { SimpleGrantedAuthority("ROLE_${it.uppercase()}") }
            val resources = jwt.getClaimAsMap("resource_access").orEmpty()
            val clientRoles = resources.values.filterIsInstance<Map<*, *>>().flatMap { (it["roles"] as? Collection<*>)?.filterIsInstance<String>().orEmpty() }
                .flatMap { listOf(SimpleGrantedAuthority("ROLE_${it.uppercase()}"), SimpleGrantedAuthority("SCOPE_${it.uppercase()}")) }
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
    @Primary
    fun mainFilterChain(
        http: HttpSecurity,
        properties: SecurityProperties,
    ): SecurityFilterChain {
        val allPublicPaths = properties.publicPaths + identityServicePublicPaths
        http
            .csrf { it.disable() }
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
            .authorizeHttpRequests {
                it.requestMatchers(*allPublicPaths.toTypedArray()).permitAll()
                    .anyRequest().authenticated()
            }
            .oauth2ResourceServer { it.jwt { jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter()) } }
        return http.build()
    }
}