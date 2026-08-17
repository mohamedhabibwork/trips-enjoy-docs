package com.trips_enjoy.audit.config

import com.trips_enjoy.platform.security.SecurityProperties
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.context.properties.EnableConfigurationProperties
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
 * Spring Security resource-server config for audit-service.
 *
 * Phase C (platform-DRY initiative): the hardcoded public-path list is
 * replaced by the platform-owned [SecurityProperties.publicPaths],
 * layered with the paths unique to this service. Everything else
 * requires a JWT bearer token.
 *
 * Two deliberate departures from the customer-service pilot (e744e1a),
 * both required to avoid regressions in this service:
 *
 * 1. **Authorities keep their original case.** Every `@PreAuthorize` in
 *    this service asserts lowercase dotted authorities — e.g.
 *    `hasAnyAuthority('ROLE_audit.read', 'ROLE_platform.admin')` in
 *    `AuditController` and `AdminAuditController`. The platform's
 *    `JwtRoleConverter` emits `ROLE_<UPPER>` / `SCOPE_<UPPER>`, which
 *    would turn `ROLE_audit.read` into `ROLE_AUDIT.READ` and cause every
 *    audit endpoint to return 403. The local converter below is therefore
 *    retained verbatim; migrating to the platform converter requires a
 *    coordinated rewrite of all 11 `@PreAuthorize` expressions and is out
 *    of scope for Phase C.
 *
 * 2. **The platform admin filter chain is intentionally not activated.**
 *    It applies `securityMatcher("/admin/v1/DOUBLE_STAR")` and gates the whole
 *    subtree on `hasRole(platform.security.admin.min-role)`, which is
 *    `audit.admin` for this service. `AdminAuditController` deliberately
 *    admits `platform.admin` / `platform.super_admin` *without*
 *    `audit.admin` on `POST /admin/v1/audit/search` and
 *    `POST /admin/v1/audit/export`; a chain-level `hasRole('audit.admin')`
 *    would reject those callers before method security runs. Admin
 *    authorization stays per-endpoint via `@PreAuthorize`.
 *
 * CORS is likewise not wired here. This service previously had no CORS
 * configuration, and enabling cross-origin access to an immutable audit
 * log is a security-relevant change that belongs in its own reviewed
 * decision rather than riding along with a DRY refactor.
 *
 * [SecurityProperties] is bound explicitly via
 * `@EnableConfigurationProperties`: the platform security module's
 * registered `AutoConfiguration` entry is a no-op stub, and the class
 * that carries `@EnableConfigurationProperties(SecurityProperties::class)`
 * (`SecurityAutoConfiguration`) is `internal`, so it cannot be imported
 * from this module.
 */
@Configuration
@EnableMethodSecurity
@EnableConfigurationProperties(SecurityProperties::class)
class SecurityConfiguration {

    /**
     * Public paths unique to audit-service, layered on top of the
     * platform defaults (`/healthz`, `/actuator/health`,
     * `/actuator/health/DOUBLE_STAR`, `/actuator/prometheus`,
     * `/v3/api-docs/DOUBLE_STAR`, `/swagger-ui/DOUBLE_STAR`,
     * `/swagger-ui.html`).
     *
     * `/docs` and `/openapi.json` are this service's springdoc paths (see
     * `springdoc.*` in application.yml); the platform's
     * `/swagger-ui/DOUBLE_STAR` and `/v3/api-docs/DOUBLE_STAR` defaults
     * cover the framework's stock locations for the same, already-public,
     * API contract.
     *
     * (`DOUBLE_STAR` stands in for the Ant wildcard, which cannot be
     * written literally inside a KDoc block comment.)
     */
    private val auditServicePublicPaths: List<String> =
        listOf(
            "/health",
            "/ready",
            "/started",
            "/actuator/info",
            "/openapi.json/**",
            "/docs/**",
        )

    @Bean
    @Primary
    fun defaultSecurityFilterChain(
        http: HttpSecurity,
        properties: SecurityProperties,
    ): SecurityFilterChain {
        val allPublicPaths = (properties.publicPaths + auditServicePublicPaths).distinct()
        return http
            .csrf { it.disable() }
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
            .authorizeHttpRequests { auth ->
                auth.requestMatchers(*allPublicPaths.toTypedArray()).permitAll()
                auth.anyRequest().authenticated()
            }
            .oauth2ResourceServer { it.jwt { jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter()) } }
            .build()
    }

    @Bean
    fun jwtDecoder(@Value("\${audit-service.keycloak.jwks-uri}") jwksUri: String): JwtDecoder =
        NimbusJwtDecoder.withJwkSetUri(jwksUri).build()

    /**
     * Maps Keycloak claims to authorities, preserving the original role
     * and scope casing:
     *   `scope`           -> `SCOPE_<name>`
     *   `realm_access`    -> `ROLE_<name>`
     *   `resource_access` -> `ROLE_<name>` + `SCOPE_<name>`
     *
     * See the class-level note on why this is not replaced by the
     * platform `JwtRoleConverter`.
     */
    @Bean
    fun jwtAuthenticationConverter(): JwtAuthenticationConverter = JwtAuthenticationConverter().apply {
        setJwtGrantedAuthoritiesConverter { jwt ->
            val scopes = jwt.getClaimAsString("scope")
                ?.split(' ')?.filter(String::isNotBlank).orEmpty()
                .map { SimpleGrantedAuthority("SCOPE_$it") }
            val realmAccess = jwt.getClaimAsMap("realm_access").orEmpty()
            val realmRoles = (realmAccess["roles"] as? Collection<*>)
                ?.filterIsInstance<String>().orEmpty()
                .map { SimpleGrantedAuthority("ROLE_$it") }
            val resources = jwt.getClaimAsMap("resource_access").orEmpty()
            val clientRoles = resources.values
                .filterIsInstance<Map<*, *>>()
                .flatMap { (it["roles"] as? Collection<*>)
                    ?.filterIsInstance<String>().orEmpty() }
                .flatMap { listOf(SimpleGrantedAuthority("ROLE_$it"), SimpleGrantedAuthority("SCOPE_$it")) }
            (scopes + realmRoles + clientRoles).toSet()
        }
    }
}
