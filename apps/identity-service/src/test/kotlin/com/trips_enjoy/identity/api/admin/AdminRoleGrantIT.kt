package com.trips_enjoy.identity.api.admin

import com.trips_enjoy.identity.api.admin.AdminRoleGrantRequest
import com.trips_enjoy.identity.application.KeycloakAdminClient
import com.trips_enjoy.identity.domain.Identity
import com.trips_enjoy.identity.domain.IdentityRepository
import com.trips_enjoy.identity.testing.JwtTestUtils
import org.junit.jupiter.api.Assertions.assertEquals
import com.trips_enjoy.platform.test.BaseIntegrationTest
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.web.client.RestClient
import java.time.Instant
import java.util.UUID

/**
 * Integration tests for the admin role-grant gates (TECH §10.5 + INTEGRATION §1.12).
 *
 * Uses Testcontainers (Postgres + Kafka + Redis) and a stubbed JwtDecoder wired
 * against a local RSA keypair via JwtTestUtils. Keycloak admin calls are mocked.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(AdminRoleGrantIT.TestJwtConfig::class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@org.springframework.test.context.ActiveProfiles("test")
class AdminRoleGrantIT : BaseIntegrationTest() {

    companion object {
        val jwtUtils: JwtTestUtils = JwtTestUtils()

        @JvmStatic
        @DynamicPropertySource
        fun jwtProperties(registry: DynamicPropertyRegistry) {
            registry.add("identity.keycloak.jwks-uri") { "http://stub/jwks" }
        }
    }

    @TestConfiguration
    class TestJwtConfig {
        @Bean
        @Primary
        fun testJwtDecoder(): JwtDecoder = NimbusJwtDecoder.withPublicKey(jwtUtils.publicKey).build()

        @Bean
        @Primary
        fun stubKeycloakAdminClient(): KeycloakAdminClient = mock(KeycloakAdminClient::class.java)
    }

    @LocalServerPort
    var port: Int = 0

    @Autowired
    lateinit var identityRepository: IdentityRepository

    @Autowired
    lateinit var keycloak: KeycloakAdminClient

    private fun restClient(): RestClient = RestClient.builder().baseUrl("http://localhost:$port").build()

    private fun authHeaders(role: String): HttpHeaders {
        val token = jwtUtils.mintAdminToken(role = role)
        return HttpHeaders().apply {
            setBearerAuth(token)
            contentType = MediaType.APPLICATION_JSON
        }
    }

    @BeforeAll
    fun configureMocks() {
        // Default: any logout call returns an empty result; tests override per-case.
        `when`(keycloak.logout(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString()))
            .thenReturn(com.trips_enjoy.identity.application.LogoutResult(0, emptyList()))
    }

    @Test
    fun `non-super role grant succeeds without cosigner or signature`() {
        val identity = seedIdentity()
        `when`(keycloak.listRealmRoles(identity.realm, identity.keycloakSubject)).thenReturn(emptyList(), listOf("identity.admin"))
        val response = restClient().post()
            .uri("/admin/v1/identities/${identity.id}/roles/identity.admin")
            .headers { h -> h.addAll(authHeaders("platform.admin")) }
            .header("Idempotency-Key", UUID.randomUUID().toString())
            .header("X-Audit-Reason", "ops-onboarding-1234")
            .body(AdminRoleGrantRequest(preset = null, reason_code = "ops-onboarding-1234"))
            .retrieve()
            .onStatus({ s -> s.is4xxClientError }) { _, _ -> }
            .toEntity(String::class.java)
        assertEquals(HttpStatus.OK, response.statusCode, "Expected 200 OK, got body=${response.body}")
        assert(response.body!!.contains("identity.admin")) { "Response should include the granted role: ${response.body}" }
    }

    @Test
    fun `super-admin role grant without cosigner returns 403 CO_SIGNER_REQUIRED`() {
        val identity = seedIdentity()
        val response = restClient().post()
            .uri("/admin/v1/identities/${identity.id}/roles/platform.super_admin")
            .headers { h -> h.addAll(authHeaders("platform.super_admin")) }
            .header("Idempotency-Key", UUID.randomUUID().toString())
            .header("X-Audit-Reason", "ops-onboarding-1234")
            .body(AdminRoleGrantRequest(reason_code = "ops-onboarding-1234"))
            .retrieve()
            .onStatus({ s -> s.is4xxClientError }) { _, _ -> }
            .toEntity(String::class.java)
        assertEquals(HttpStatus.FORBIDDEN, response.statusCode, "Expected 403, got body=${response.body}")
        assert(response.body!!.contains("CO_SIGNER_REQUIRED")) { "Body should indicate CO_SIGNER_REQUIRED: ${response.body}" }
    }

    @Test
    fun `super-admin grant with X-Audit-Reason shorter than 8 chars returns 400`() {
        val identity = seedIdentity()
        val response = restClient().post()
            .uri("/admin/v1/identities/${identity.id}/roles/platform.super_admin")
            .headers { h -> h.addAll(authHeaders("platform.super_admin")) }
            .header("Idempotency-Key", UUID.randomUUID().toString())
            .header("X-Audit-Reason", "short")
            .body(AdminRoleGrantRequest(reason_code = "ops-onboarding-1234"))
            .retrieve()
            .onStatus({ s -> s.is4xxClientError }) { _, _ -> }
            .toEntity(String::class.java)
        assertEquals(HttpStatus.BAD_REQUEST, response.statusCode, "Expected 400, got body=${response.body}")
    }

    @Test
    fun `get roles returns computed presets list`() {
        val identity = seedIdentity()
        `when`(keycloak.listRealmRoles(identity.realm, identity.keycloakSubject))
            .thenReturn(listOf("platform.super_admin", "identity.admin", "customer"))
        val response = restClient().get()
            .uri("/admin/v1/identities/${identity.id}/roles")
            .headers { h -> h.addAll(authHeaders("platform.admin")) }
            .retrieve()
            .onStatus({ s -> s.is4xxClientError }) { _, _ -> }
            .toEntity(String::class.java)
        assertEquals(HttpStatus.OK, response.statusCode, "Expected 200 OK, got body=${response.body}")
        assert(response.body!!.contains("SUPER_ADMIN")) { "Response should contain SUPER_ADMIN preset: ${response.body}" }
    }

    private fun seedIdentity(): Identity {
        val now = Instant.now()
        val identity = Identity(
            id = UUID.randomUUID(),
            keycloakSubject = "kc-sub-${UUID.randomUUID()}",
            realm = "platform-internal",
            userType = "admin",
            createdBy = UUID(0, 0),
            updatedBy = UUID(0, 0),
            createdAt = now,
            updatedAt = now,
        )
        return identityRepository.save(identity)
    }
}
