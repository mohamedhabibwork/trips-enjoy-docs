package com.trips_enjoy.foodorder.config

import com.trips_enjoy.platform.security.SecurityProperties
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder
import org.springframework.security.web.SecurityFilterChain

/**
 * Spring Security wiring for food-order-service.
 *
 * Phase C (platform DRY): the platform `SecurityAutoConfiguration` is
 * picked up via Spring Boot's auto-configuration imports (the platform
 * registers it through `META-INF/spring/...AutoConfiguration.imports`),
 * so the admin filter chain and the CORS configuration source are
 * inherited as-is.
 *
 * The platform's `defaultSecurityFilterChain` bean is guarded by
 * `@ConditionalOnMissingBean(name = ["defaultSecurityFilterChain"])`,
 * so the subclass-supplied `@Primary` filter chain wins for the main
 * request flow. The admin chain remains platform-owned.
 *
 * Service-specific differences from the platform default:
 *   - 8 service-specific public paths (food-order adds openapi.yaml,
 *     docs, v3 api-docs (wildcard), swagger-ui (wildcard),
 *     swagger-ui.html on top of the platform's 6 defaults).
 *   - Granular scope-based authorization: v1 orders endpoint accepts
 *     SCOPE_food_order.read or .write plus SCOPE_customer.write,
 *     SCOPE_restaurant.write, SCOPE_courier.write; v1 deals endpoint
 *     requires SCOPE_food_order.write; admin v1 endpoint requires
 *     SCOPE_food_order.admin.
 *   - **`food-order.security.enabled` toggle**: when `false`, the
 *     filter chain drops JWT verification entirely and permits all
 *     requests (matches the Phase 9 test profile wiring and the dev
 *     smoke-test override).
 *
 * The service-specific `jwtDecoder` bean binds
 * `food-order-service.keycloak.jwks-uri` to a `NimbusJwtDecoder`
 * (matches customer-service).
 */
@Configuration
@EnableMethodSecurity
@EnableConfigurationProperties(SecurityProperties::class)
class SecurityConfiguration {
    /**
     * Service-specific public paths layered on top of the platform
     * defaults (see [SecurityProperties.publicPaths]).
     */
    private val foodOrderPublicPaths: List<String> =
        listOf(
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
        @Value("\${food-order.security.enabled:true}") securityEnabled: Boolean,
    ): SecurityFilterChain {
        val allPublicPaths = properties.publicPaths + foodOrderPublicPaths
        http
            .csrf { it.disable() }
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
            .authorizeHttpRequests { auth ->
                auth.requestMatchers(*allPublicPaths.toTypedArray()).permitAll()
                if (securityEnabled) {
                    auth.requestMatchers("/v1/orders/*").hasAnyAuthority(
                        "SCOPE_food_order.write",
                        "SCOPE_food_order.read",
                        "SCOPE_customer.write",
                        "SCOPE_restaurant.write",
                        "SCOPE_courier.write",
                    )
                    auth.requestMatchers("/v1/deals/*").hasAuthority("SCOPE_food_order.write")
                    auth.requestMatchers("/admin/v1/**").hasAuthority("SCOPE_food_order.admin")
                    auth.anyRequest().authenticated()
                } else {
                    auth.anyRequest().permitAll()
                }
            }
        if (securityEnabled) {
            http.oauth2ResourceServer { oauth2 -> oauth2.jwt { it.jwtAuthenticationConverter(com.trips_enjoy.platform.security.JwtRoleConverter()) } }
        }
        return http.build()
    }

    @Bean
    fun jwtDecoder(
        @Value("\${food-order-service.keycloak.jwks-uri}") jwksUri: String,
    ): JwtDecoder = NimbusJwtDecoder.withJwkSetUri(jwksUri).build()
}