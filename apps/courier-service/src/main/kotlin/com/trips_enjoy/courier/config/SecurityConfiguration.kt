package com.trips_enjoy.courier.config

import com.trips_enjoy.platform.security.SecurityProperties
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary
import org.springframework.security.config.Customizer
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter
import org.springframework.security.web.SecurityFilterChain

/**
 * Spring Security wiring for courier-service.
 *
 * Phase C (platform DRY): the platform `SecurityAutoConfiguration` is
 * picked up via Spring Boot's auto-configuration imports (the platform
 * registers it through `META-INF/spring/...AutoConfiguration.imports`),
 * so the admin filter chain and the CORS configuration source are
 * inherited as-is. The default `SecurityProperties.publicPaths` are
 * extended with the 9 paths unique to this service
 * (`/openapi.yaml`, `/docs/DOUBLE_STAR`, swagger-ui specifics).
 *
 * The platform's `defaultSecurityFilterChain` bean is guarded by
 * `@ConditionalOnMissingBean(name = ["defaultSecurityFilterChain"])`,
 * so the subclass-supplied `@Primary` filter chain wins for the main
 * request flow. The admin chain and CORS source remain platform-owned.
 *
 * Service-specific knobs still defined here:
 *   - `courier.security.enabled` — when `false`, the filter chain
 *     allows every request (developer convenience only; production
 *     values come from `application-{stg,prod}.yml`).
 *   - The platform's `defaultSecurityFilterChain` requires
 *     authentication for `/admin/v1/<...>` but we tighten that to a
 *     `courier.admin` authority check (ADR-0025 SCOPE_<UPPER> shape).
 *   - `jwtAuthenticationConverter` — claims-to-authorities mapping
 *     aligned with the platform `JwtRoleConverter` style
 *     (`SCOPE_<UPPER>`, `ROLE_<UPPER>`,
 *     `ROLE_<CLIENT>_<UPPER>`).
 */
@Configuration
@EnableMethodSecurity
class SecurityConfiguration {

    /**
     * Service-specific public paths (the 9 paths unique to
     * courier-service, layered on top of the platform defaults).
     */
    private val courierServicePublicPaths: List<String> =
        listOf(
            "/openapi.yaml",
            "/docs",
            "/docs/**",
            "/swagger-ui.html",
            "/swagger-ui/**",
        )

    @Bean
    @Primary
    fun defaultSecurityFilterChain(
        http: HttpSecurity,
        properties: SecurityProperties,
        @Value("\${courier.security.enabled:true}") securityEnabled: Boolean,
    ): SecurityFilterChain {
        val allPublicPaths = properties.publicPaths + courierServicePublicPaths
        val builder = http
            .csrf { it.disable() }
            .cors(Customizer.withDefaults())
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
            .authorizeHttpRequests { auth ->
                auth.requestMatchers(*allPublicPaths.toTypedArray()).permitAll()
                if (securityEnabled) {
                    auth.requestMatchers("/admin/v1/**").hasAuthority("courier.admin")
                    auth.anyRequest().authenticated()
                } else {
                    auth.anyRequest().permitAll()
                }
            }
        return if (securityEnabled) {
            builder.oauth2ResourceServer { oauth2 ->
                oauth2.jwt { jwt ->
                    jwt.jwtAuthenticationConverter(jwtAuthenticationConverter())
                }
            }.build()
        } else {
            builder.build()
        }
    }

    private fun jwtAuthenticationConverter(): JwtAuthenticationConverter {
        val converter = JwtAuthenticationConverter()
        converter.setJwtGrantedAuthoritiesConverter { jwt: Jwt ->
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
        return converter
    }
}
