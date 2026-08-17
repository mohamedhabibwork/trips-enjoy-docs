package com.trips_enjoy.payment.config

import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.config.Customizer
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.web.SecurityFilterChain

// Security configuration — JWT bearer auth via Keycloak (platform identity
// bridge), realm platform-internal. Public endpoints: /actuator/health
// (K8s probes), /openapi.json, /docs (Swagger UI). Everything else
// requires a Bearer JWT validated by Keycloak JWKS (cached by
// platform-spring-boot-starter).
//
// Authorization is enforced via method-level @PreAuthorize on controllers
// (see docs/services/payment-service/TECH.md §10).
//
// The `payment.security.enabled` flag (default true) lets the test
// profile turn off OAuth2 wiring so the SpringBootTest loads without a
// real Keycloak server.
@Configuration
class SecurityConfiguration(
    @Value("\${payment.security.enabled:true}") private val securityEnabled: Boolean,
) {

    @Bean
    fun securityFilterChain(http: HttpSecurity): SecurityFilterChain {
        val builder = http
            .csrf { it.disable() }
            .cors(Customizer.withDefaults())
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
            .authorizeHttpRequests { auth ->
                auth.requestMatchers(
                    "/actuator/health",
                    "/actuator/health/**",
                    "/openapi.json",
                    "/openapi.yaml",
                    "/docs",
                    "/docs/**",
                    "/v3/api-docs/**",
                    "/swagger-ui/**",
                    "/swagger-ui.html",
                ).permitAll()
                if (securityEnabled) {
                    auth.requestMatchers("/admin/v1/**").hasAuthority("payment.admin")
                    auth.anyRequest().authenticated()
                } else {
                    auth.anyRequest().permitAll()
                }
            }
        return if (securityEnabled) {
            builder.oauth2ResourceServer { oauth2 ->
                oauth2.jwt(Customizer.withDefaults())
            }.build()
        } else {
            builder.build()
        }
    }
}