package com.trips_enjoy.identity.application.oidc

import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.test.util.ReflectionTestUtils

/**
 * Unit tests for `OidcDiscoveryRewriter`. These verify the URL rewriting logic
 * (the heart of the OIDC BFF) without needing a live Keycloak.
 */
class OidcDiscoveryRewriterTest {

    private val mapper = ObjectMapper()

    private fun rewriter(rewriteBaseUrl: String = ""): OidcDiscoveryRewriter {
        val r = OidcDiscoveryRewriter(mapper, rewriteBaseUrl)
        return r
    }

    private fun keycloakDiscovery(): String = """
        {
          "issuer": "http://keycloak.local:8181/realms/platform-services",
          "authorization_endpoint": "http://keycloak.local:8181/realms/platform-services/protocol/openid-connect/auth",
          "token_endpoint": "http://keycloak.local:8181/realms/platform-services/protocol/openid-connect/token",
          "introspection_endpoint": "http://keycloak.local:8181/realms/platform-services/protocol/openid-connect/token/introspect",
          "userinfo_endpoint": "http://keycloak.local:8181/realms/platform-services/protocol/openid-connect/userinfo",
          "jwks_uri": "http://keycloak.local:8181/realms/platform-services/protocol/openid-connect/certs",
          "end_session_endpoint": "http://keycloak.local:8181/realms/platform-services/protocol/openid-connect/logout",
          "revocation_endpoint": "http://keycloak.local:8181/realms/platform-services/protocol/openid-connect/revoke",
          "grant_types_supported": ["authorization_code", "client_credentials"],
          "response_types_supported": ["code"],
          "id_token_signing_alg_values_supported": ["RS256"]
        }
    """.trimIndent()

    @Test
    fun `rewriter replaces every endpoint URL with the configured base`() {
        val r = rewriter(rewriteBaseUrl = "https://identity.trips-enjoy.example.com")
        val rewritten = r.rewrite(mapper.readTree(keycloakDiscovery()), fallbackBaseUrl = "")
        // Issuer stays at the Keycloak URL (clients use it to verify token `iss`).
        assertEquals(
            "http://keycloak.local:8181/realms/platform-services",
            rewritten.path("issuer").asText(),
        )
        // Every endpoint URL is rewritten to the identity-service base.
        listOf(
            "authorization_endpoint",
            "token_endpoint",
            "introspection_endpoint",
            "userinfo_endpoint",
            "jwks_uri",
            "end_session_endpoint",
            "revocation_endpoint",
        ).forEach { key ->
            val url = rewritten.path(key).asText()
            assertTrue(
                url.startsWith("https://identity.trips-enjoy.example.com"),
                "$key should be rewritten to identity-service base, was $url",
            )
            assertFalse(
                url.contains("keycloak.local"),
                "$key should not contain the original Keycloak host: $url",
            )
            assertTrue(
                url.contains("/realms/platform-services/protocol/openid-connect"),
                "$key should preserve the realm + path: $url",
            )
        }
        // Non-URL fields pass through unchanged.
        assertEquals(
            listOf("authorization_code", "client_credentials"),
            mapper.convertValue(rewritten.path("grant_types_supported"), List::class.java),
        )
    }

    @Test
    fun `rewriter falls back to request base url when no rewrite base is configured`() {
        val r = rewriter(rewriteBaseUrl = "")
        val rewritten = r.rewrite(
            mapper.readTree(keycloakDiscovery()),
            fallbackBaseUrl = "https://id.example.com",
        )
        // All endpoint URLs become https://id.example.com/<keycloak-path>.
        // The BFF's controller mounts /oauth2/jwks etc. on identity-service, so
        // the JWKS URL still includes /realms/.../certs path; only the host changes.
        assertEquals(
            "https://id.example.com/realms/platform-services/protocol/openid-connect/auth",
            rewritten.path("authorization_endpoint").asText(),
        )
        assertEquals(
            "https://id.example.com/realms/platform-services/protocol/openid-connect/certs",
            rewritten.path("jwks_uri").asText(),
        )
    }
}
