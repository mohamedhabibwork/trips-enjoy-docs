package com.trips_enjoy.audit.config

import com.trips_enjoy.platform.security.SecurityProperties
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.security.oauth2.jwt.Jwt

/**
 * Guards the two Phase C decisions in [SecurityConfiguration] that are
 * easy to regress:
 *
 * 1. The public-path list is the union of the platform defaults and the
 *    audit-service-specific paths — so adopting [SecurityProperties] must
 *    not silently drop a path this service previously permitted.
 * 2. The JWT authority mapping preserves role/scope casing, because every
 *    `@PreAuthorize` in this service asserts lowercase dotted authorities
 *    such as `ROLE_audit.read`.
 */
class SecurityConfigurationTest {

    private val config = SecurityConfiguration()

    /** The 9 paths audit-service permitted before Phase C. */
    private val prePhaseCPublicPaths = listOf(
        "/health",
        "/ready",
        "/started",
        "/actuator/health/**",
        "/actuator/info",
        "/actuator/prometheus",
        "/openapi.json/**",
        "/v3/api-docs/**",
        "/docs/**",
    )

    private fun resolvedPublicPaths(
        properties: SecurityProperties = SecurityProperties(),
    ): List<String> {
        val servicePaths = SecurityConfiguration::class.java
            .getDeclaredField("auditServicePublicPaths")
            .apply { isAccessible = true }
            .get(config)
        @Suppress("UNCHECKED_CAST")
        return (properties.publicPaths + (servicePaths as List<String>)).distinct()
    }

    @Test
    fun `every pre-phase-C public path is still permitted`() {
        val resolved = resolvedPublicPaths()
        val missing = prePhaseCPublicPaths.filterNot { it in resolved }
        assertTrue(missing.isEmpty(), "Phase C dropped previously-public paths: $missing")
    }

    @Test
    fun `resolved public paths contain no duplicates`() {
        val resolved = resolvedPublicPaths()
        assertEquals(resolved.distinct(), resolved)
    }

    @Test
    fun `configured public paths are honoured over the platform defaults`() {
        val resolved = resolvedPublicPaths(SecurityProperties(publicPaths = listOf("/custom")))
        assertTrue("/custom" in resolved)
        // The service-specific paths are always layered on top.
        assertTrue("/docs/**" in resolved)
        // A platform default that was overridden is no longer present.
        assertTrue("/swagger-ui.html" !in resolved)
    }

    @Test
    fun `realm and client roles keep their original lowercase dotted casing`() {
        val authorities = convert(
            claims = mapOf(
                "realm_access" to mapOf("roles" to listOf("audit.read")),
                "resource_access" to mapOf("audit-service" to mapOf("roles" to listOf("audit.admin"))),
            ),
        )

        // These are the exact authority strings asserted by @PreAuthorize
        // in AuditController / AdminAuditController.
        assertTrue("ROLE_audit.read" in authorities, "got $authorities")
        assertTrue("ROLE_audit.admin" in authorities, "got $authorities")
        assertTrue("SCOPE_audit.admin" in authorities, "got $authorities")

        // Regression guard: the platform JwtRoleConverter would uppercase.
        assertTrue("ROLE_AUDIT.READ" !in authorities, "authorities must not be uppercased")
    }

    @Test
    fun `scope claim is mapped to SCOPE_ authorities without case change`() {
        val authorities = convert(claims = mapOf("scope" to "audit.read audit.export"))
        assertTrue("SCOPE_audit.read" in authorities, "got $authorities")
        assertTrue("SCOPE_audit.export" in authorities, "got $authorities")
    }

    @Test
    fun `a jwt with no role or scope claims yields no granted roles or scopes`() {
        // Spring Security adds its own `FACTOR_BEARER` authority to every
        // bearer token, so assert on the ROLE_/SCOPE_ authorities that this
        // converter is actually responsible for.
        val derived = convert(claims = emptyMap())
            .filter { it.startsWith("ROLE_") || it.startsWith("SCOPE_") }
        assertTrue(derived.isEmpty(), "expected no derived authorities but got $derived")
    }

    private fun convert(claims: Map<String, Any>): Set<String> {
        val jwt = Jwt.withTokenValue("token")
            .header("alg", "none")
            .subject("subject")
            .apply { claims.forEach { (key, value) -> claim(key, value) } }
            .build()
        return config.jwtAuthenticationConverter()
            .convert(jwt)!!
            .authorities
            .mapNotNull { it.authority }
            .toSet()
    }
}
