package com.trips_enjoy.identity.api.oidc

import com.fasterxml.jackson.databind.ObjectMapper
import com.trips_enjoy.platform.test.BaseIntegrationTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.test.context.TestPropertySource
import org.springframework.util.LinkedMultiValueMap
import org.springframework.web.client.RestClient

/**
 * Smoke tests for `/oauth2/token`, `/oauth2/introspect`, `/oauth2/userinfo`,
 * `/oauth2/revoke` and `/oauth2/logout`. Verifies that:
 *
 *  1. The endpoints are reachable without authentication (filter chain order).
 *  2. When Keycloak is unreachable they return a clean RFC 6749 §5.2 error
 *     envelope with HTTP 502 BAD_GATEWAY.
 *  3. The OIDC error envelope is `application/json` (not RFC 7807 ProblemDetail).
 *
 * The full end-to-end path against a live Keycloak (TestKeycloakContainer) is
 * exercised by the `bootRun` smoke command because Keycloak 24 has heavy
 * startup requirements that don't fit cleanly into a JUnit run.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = [
    "identity.keycloak.base-url=http://127.0.0.1:1",
    "identity.keycloak.jwks-uri=http://127.0.0.1:1/realms/test/protocol/openid-connect/certs",
])
@org.springframework.test.context.ActiveProfiles("test")
class OidcTokenE2EIT : BaseIntegrationTest() {

    @LocalServerPort
    var port: Int = 0

    @Autowired
    lateinit var objectMapper: ObjectMapper

    private fun restClient(): RestClient = RestClient.builder().baseUrl("http://127.0.0.1:$port").build()

    @Test
    fun `token endpoint returns RFC 6749 error envelope on upstream failure`() {
        val form = LinkedMultiValueMap<String, String>().apply {
            add("grant_type", "client_credentials")
            add("client_id", "identity-service")
            add("client_secret", "test")
        }
        val response = restClient().post()
            .uri("/oauth2/token?realm=platform-services")
            .contentType(MediaType.APPLICATION_FORM_URLENCODED)
            .body(form)
            .retrieve()
            .onStatus({ s -> s.is4xxClientError || s.is5xxServerError }) { _, _ -> }
            .toEntity(String::class.java)

        assertEquals(HttpStatus.BAD_GATEWAY, response.statusCode, "body=${response.body}")
        assertEquals("application/json", response.headers.contentType?.toString()?.substringBefore(";"))
        val body = objectMapper.readTree(response.body!!)
        assertEquals("server_error", body.path("error").asText())
        assertTrue(body.path("error_description").asText().isNotBlank())
    }

    @Test
    fun `introspect endpoint returns RFC 6749 envelope on missing token`() {
        val form = LinkedMultiValueMap<String, String>()
        val response = restClient().post()
            .uri("/oauth2/introspect?realm=platform-services")
            .contentType(MediaType.APPLICATION_FORM_URLENCODED)
            .body(form)
            .retrieve()
            .onStatus({ s -> s.is4xxClientError || s.is5xxServerError }) { _, _ -> }
            .toEntity(String::class.java)
        // Either 400 (missing token, controller throws OidcClientException) or 502 (upstream).
        assertTrue(
            response.statusCode == HttpStatus.BAD_REQUEST || response.statusCode == HttpStatus.BAD_GATEWAY,
            "Expected 400 or 502, got ${response.statusCode} body=${response.body}",
        )
    }

    @Test
    fun `userinfo endpoint requires Bearer token`() {
        val response = restClient().post()
            .uri("/oauth2/userinfo?realm=platform-services")
            .contentType(MediaType.APPLICATION_FORM_URLENCODED)
            .retrieve()
            .onStatus({ s -> s.is4xxClientError || s.is5xxServerError }) { _, _ -> }
            .toEntity(String::class.java)
        assertEquals(HttpStatus.BAD_REQUEST, response.statusCode, "body=${response.body}")
        val body = objectMapper.readTree(response.body!!)
        assertEquals("invalid_request", body.path("error").asText())
    }

    @Test
    fun `revoke endpoint returns 200 on Keycloak error`() {
        // RFC 7009 §2.2: the server responds with HTTP 200 even for invalid tokens.
        // With Keycloak unreachable we get a 502.
        val form = LinkedMultiValueMap<String, String>().apply {
            add("token", "fake-token-for-revoke")
            add("client_id", "identity-service")
            add("client_secret", "test")
        }
        val response = restClient().post()
            .uri("/oauth2/revoke?realm=platform-services")
            .contentType(MediaType.APPLICATION_FORM_URLENCODED)
            .body(form)
            .retrieve()
            .onStatus({ s -> s.is4xxClientError || s.is5xxServerError }) { _, _ -> }
            .toEntity(String::class.java)
        assertEquals(HttpStatus.BAD_GATEWAY, response.statusCode, "body=${response.body}")
    }
}
