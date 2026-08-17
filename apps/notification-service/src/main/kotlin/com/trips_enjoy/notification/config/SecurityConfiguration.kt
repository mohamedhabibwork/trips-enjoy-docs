package com.trips_enjoy.notification.config

import com.trips_enjoy.platform.security.SecurityProperties
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary
import org.springframework.http.HttpMethod
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter
import org.springframework.security.web.SecurityFilterChain

// Spring Security wiring for notification-service.
//
// Phase C (platform DRY): the platform SecurityAutoConfiguration is
// picked up via Spring Boot's auto-configuration imports (the platform
// registers it through META-INF/spring/...AutoConfiguration.imports), so
// the CORS configuration source is inherited as-is. The default
// SecurityProperties.publicPaths are extended with the 7 paths unique to
// this service (the per-service health probes, OpenAPI/Swagger paths,
// and the WhatsApp webhook path under /admin/v1/notifications/webhooks).
//
// The platform defaultSecurityFilterChain bean is guarded by
// @ConditionalOnMissingBean(name = ["defaultSecurityFilterChain"]), so
// the subclass-supplied @Primary filter chain wins for the main request
// flow.
//
// The platform adminSecurityFilterChain bean is overridden locally so it
// matches a narrower set of paths (/admin/v1/notify-control/**) that
// excludes the WhatsApp webhook path under /admin/v1/notifications/webhooks,
// which must remain permitAll for the WhatsApp gateway to deliver
// comms.whatsapp.*.v1 callbacks without holding an admin role.
//
// Service-specific beans still defined here:
//   - jwtDecoder - NimbusJwtDecoder wired to the notification-service
//     Keycloak JWKS URI (notification-service.keycloak.jwks-uri).
//   - jwtAuthenticationConverter - claims-to-authorities mapping aligned
//     with ADR-0025 (SCOPE_<UPPER>, ROLE_<UPPER>, ROLE_<CLIENT>_<UPPER>).
@Configuration
@EnableMethodSecurity
class SecurityConfiguration {

    // Service-specific public paths (the 7 paths unique to
    // notification-service, layered on top of the platform defaults).
    private val notificationServicePublicPaths: List<String> =
        listOf(
            "/health",
            "/ready",
            "/started",
            "/actuator/info",
            "/openapi.json/**",
            "/docs/**",
            "/admin/v1/notifications/webhooks/whatsapp",
        )

    @Bean
    @Primary
    fun defaultSecurityFilterChain(
        http: HttpSecurity,
        properties: SecurityProperties,
    ): SecurityFilterChain {
        val allPublicPaths = properties.publicPaths + notificationServicePublicPaths
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
    fun adminSecurityFilterChain(
        http: HttpSecurity,
        properties: SecurityProperties,
    ): SecurityFilterChain {
        val adminBase = properties.admin.basePath
        http
            // Narrow the admin matcher so it does NOT match the webhook
            // path under /admin/v1/notifications/webhooks (handled by
            // the @Primary default chain above as permitAll).
            .securityMatcher("$adminBase/notify-control/**")
            .csrf { it.disable() }
            .cors { it.configurationSource(corsConfigurationSource(properties)) }
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
            .authorizeHttpRequests { auth ->
                auth.requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                auth.anyRequest().hasRole(properties.admin.minRole.removePrefix("ROLE_").removePrefix("role_"))
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
        @Value("\${notification-service.keycloak.jwks-uri}") jwksUri: String,
    ): JwtDecoder = NimbusJwtDecoder.withJwkSetUri(jwksUri).build()

    @Bean
    fun jwtAuthenticationConverter(): JwtAuthenticationConverter = JwtAuthenticationConverter().apply {
        setJwtGrantedAuthoritiesConverter { jwt ->
            val scopes =
                jwt.getClaimAsString("scope")
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
                    .flatMap { entry ->
                        val roles = entry["roles"] as? Collection<*>
                        roles?.filterIsInstance<String>().orEmpty()
                    }
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
    // SecurityProperties. Kept local so the notification-service filter
    // chains (which win via @Primary / @Bean override) and the platform
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