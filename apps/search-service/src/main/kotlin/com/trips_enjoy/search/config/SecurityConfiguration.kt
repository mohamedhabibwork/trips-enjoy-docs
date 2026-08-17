package com.trips_enjoy.search.config

import com.trips_enjoy.platform.security.SecurityProperties
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary
import org.springframework.security.config.Customizer
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.web.SecurityFilterChain

/**
 * Spring Security wiring for search-service.
 *
 * Phase C (platform DRY): the platform `SecurityAutoConfiguration` is
 * picked up via Spring Boot's auto-configuration imports (the platform
 * registers it through `META-INF/spring/...AutoConfiguration.imports`),
 * so the admin filter chain and the CORS configuration source are
 * inherited as-is. The default `SecurityProperties.publicPaths` are
 * extended with the 8 paths unique to this service (openapi.json,
 * openapi.yaml, docs, actuat.or health, etc.).
 *
 * The platform's `defaultSecurityFilterChain` bean is guarded by
 * `@ConditionalOnMissingBean(name = ["defaultSecurityFilterChain"])`,
 * so the subclass-supplied `@Primary` filter chain wins for the main
 * request flow. The admin chain and CORS source remain platform-owned.
 *
 * Service-specific authz rules preserved:
 *   - `v1.admin` paths            — requires `search.admin` authority
 *   - `v1.search` paths           — requires one of `search.read`,
 *                                    `SCOPE_search.read`, `customer.write`,
 *                                    `driver.write`
 *   - `search.security.enabled`    — when false, the filter chain permits
 *                                    all requests (dev-only escape hatch)
 */
@Configuration
@EnableConfigurationProperties(SecurityProperties::class)
class SecurityConfiguration(
    @Value("\${search.security.enabled:true}") private val securityEnabled: Boolean,
) {
    /**
     * Service-specific public paths (the 8 paths unique to
     * search-service, layered on top of the platform defaults).
     */
    private val searchServicePublicPaths: List<String> =
        listOf(
            "/actuator/health",
            "/actuator/health/**",
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
    ): SecurityFilterChain {
        val allPublicPaths = properties.publicPaths + searchServicePublicPaths
        val builder = http
            .csrf { it.disable() }
            .cors(Customizer.withDefaults())
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
            .authorizeHttpRequests { auth ->
                auth.requestMatchers(*allPublicPaths.toTypedArray()).permitAll()
                if (securityEnabled) {
                    auth.requestMatchers("/v1/admin/**").hasAuthority("search.admin")
                    auth.requestMatchers("/v1/search/**").hasAnyAuthority(
                        "search.read", "SCOPE_search.read",
                        "customer.write", "driver.write",
                    )
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
}
