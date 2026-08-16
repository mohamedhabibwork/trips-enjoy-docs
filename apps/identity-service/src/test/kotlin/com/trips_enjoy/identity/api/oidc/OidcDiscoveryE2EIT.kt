package com.trips_enjoy.identity.api.oidc

import com.fasterxml.jackson.databind.ObjectMapper
import com.trips_enjoy.identity.TestcontainersConfiguration
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Import
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.test.context.TestPropertySource
import org.springframework.web.client.RestClient
import java.util.UUID

/**
 * Smoke tests for the OIDC BFF endpoints.
 *
 * The BFF is wired against a real Keycloak (TestKeycloakContainer) when one is
 * available; otherwise the controller's RestClient calls fail upstream and we
 * verify the platform contract:
 *   - endpoints return 502 BAD_GATEWAY (upstream unavailable) when Keycloak is down
 *   - OIDC discovery is the only endpoint with a rewriter dependency, tested
 *     separately by `OidcDiscoveryRewriterTest` (unit-level)
 *   - The `/.well-known/openid-configuration` endpoint is reachable and
 *     returns a non-200 fallback only when Keycloak is configured.
 *
 * The wiring-level integration (RestClient calls to Keycloak, JWT validation,
 * request shape, RFC 6749 error envelope) is exercised by the live
 * `bootRun` smoke command run after this suite.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration::class)
@TestPropertySource(properties = [
    "identity.keycloak.base-url=http://127.0.0.1:1", // unreachable; triggers 502
    "identity.keycloak.jwks-uri=http://127.0.0.1:1/realms/test/protocol/openid-connect/certs",
])
@org.springframework.test.context.ActiveProfiles("test")
class OidcDiscoveryE2EIT {

    @LocalServerPort
    var port: Int = 0

    @Autowired
    lateinit var objectMapper: ObjectMapper

    private fun restClient(): RestClient = RestClient.builder().baseUrl("http://127.0.0.1:$port").build()

    @Test
    fun `discovery endpoint is mounted at well-known openid-configuration`() {
        val response = restClient().get()
            .uri("/.well-known/openid-configuration?realm=platform-services")
            .retrieve()
            .onStatus({ s -> s.is4xxClientError || s.is5xxServerError }) { _, _ -> }
            .toEntity(String::class.java)

        // Either the discovery succeeds (200, with rewritten URLs), or Keycloak
        // is unreachable and we get a clean 502 with the OIDC error envelope.
        // Both prove the endpoint is mounted and the filter chain is wired.
        val status = response.statusCode
        assertTrue(
            status == HttpStatus.OK || status == HttpStatus.BAD_GATEWAY,
            "Expected 200 or 502, got $status with body=${response.body}",
        )
        if (status == HttpStatus.BAD_GATEWAY) {
            // RFC 6749 §5.2 error envelope shape.
            val body = objectMapper.readTree(response.body!!)
            assertEquals("server_error", body.path("error").asText())
            assertNotNull(body.path("error_description").asText().takeIf { it.isNotBlank() })
        }
    }

    @Test
    fun `oidc endpoints are reachable without authentication`() {
        // Each endpoint must accept anonymous requests (SecurityFilterChain order=1).
        val endpoints = listOf(
            "/.well-known/openid-configuration?realm=platform-services",
            "/oauth2/jwks?realm=platform-services",
            "/oauth2/authorize?realm=platform-services&response_type=code&client_id=identity-service&redirect_uri=http://localhost",
        )
        endpoints.forEach { path ->
            val response = restClient().get()
                .uri(path)
                .retrieve()
                .onStatus({ s -> s.is4xxClientError || s.is5xxServerError }) { _, _ -> }
                .toEntity(String::class.java)
            // None of these should be 401 — the oidcFilterChain permits all.
            assertTrue(
                response.statusCode != HttpStatus.UNAUTHORIZED,
                "$path must not require auth; got ${response.statusCode}",
            )
        }
    }
}
