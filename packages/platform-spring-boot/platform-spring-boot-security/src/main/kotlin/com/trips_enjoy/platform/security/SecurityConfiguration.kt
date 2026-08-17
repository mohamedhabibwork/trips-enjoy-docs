package com.trips_enjoy.platform.security

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpMethod
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.web.SecurityFilterChain
import org.springframework.web.cors.CorsConfiguration
import org.springframework.web.cors.CorsConfigurationSource
import org.springframework.web.cors.UrlBasedCorsConfigurationSource

@ConfigurationProperties("platform.security")
data class SecurityProperties(
    val publicPaths: List<String> = listOf(
        "/healthz",
        "/actuator/health",
        "/actuator/health/**",
        "/actuator/prometheus",
        "/v3/api-docs/**",
        "/swagger-ui/**",
        "/swagger-ui.html",
    ),
    val admin: AdminProperties = AdminProperties(),
    val cors: CorsProperties = CorsProperties(),
)

data class AdminProperties(
    val basePath: String = "/admin/v1",
    val minRole: String = "platform.admin",
)

data class CorsProperties(
    val allowedOrigins: List<String> = listOf("http://localhost:3000"),
    val allowedMethods: List<String> = listOf("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"),
    val allowedHeaders: List<String> = listOf(
        "Authorization",
        "Content-Type",
        "X-Request-Id",
        "X-Correlation-Id",
        "Idempotency-Key",
    ),
)

@Configuration
internal class SecurityConfiguration(
    private val properties: SecurityProperties,
) {

    @Bean
    @ConditionalOnMissingBean(name = ["defaultSecurityFilterChain"])
    fun defaultSecurityFilterChain(http: HttpSecurity): SecurityFilterChain {
        http
            .csrf { it.disable() }
            .cors { it.configurationSource(corsConfigurationSource()) }
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
            .authorizeHttpRequests { auth ->
                auth.requestMatchers(*properties.publicPaths.toTypedArray()).permitAll()
                auth.anyRequest().authenticated()
            }
            .oauth2ResourceServer { oauth2 ->
                oauth2.jwt { jwt ->
                    jwt.jwtAuthenticationConverter(JwtRoleConverter())
                }
            }
        return http.build()
    }

    @Bean
    @ConditionalOnMissingBean(name = ["adminSecurityFilterChain"])
    fun adminSecurityFilterChain(http: HttpSecurity): SecurityFilterChain {
        http
            .securityMatcher(properties.admin.basePath + "/**")
            .csrf { it.disable() }
            .cors { it.configurationSource(corsConfigurationSource()) }
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
            .authorizeHttpRequests { auth ->
                auth.requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                auth.anyRequest().hasRole(properties.admin.minRole.removePrefix("ROLE_").removePrefix("role_"))
            }
            .oauth2ResourceServer { oauth2 ->
                oauth2.jwt { jwt ->
                    jwt.jwtAuthenticationConverter(JwtRoleConverter())
                }
            }
        return http.build()
    }

    @Bean
    @ConditionalOnMissingBean(CorsConfigurationSource::class)
    fun corsConfigurationSource(): CorsConfigurationSource {
        val config = CorsConfiguration().apply {
            allowedOrigins = properties.cors.allowedOrigins
            allowedMethods = properties.cors.allowedMethods
            allowedHeaders = properties.cors.allowedHeaders
            allowCredentials = true
            maxAge = 3600L
        }
        val source = UrlBasedCorsConfigurationSource()
        source.registerCorsConfiguration("/**", config)
        return source
    }
}
