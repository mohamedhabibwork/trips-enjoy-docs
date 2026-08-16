package com.trips_enjoy.identity.integration.keycloak

import jakarta.ws.rs.NotFoundException
import jakarta.ws.rs.WebApplicationException
import jakarta.ws.rs.core.Response
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

/**
 * Unit tests for `KeycloakSeeder.isTokenExpired`. No real Keycloak is
 * required — we mock the JAX-RS `WebApplicationException` that Keycloak
 * emits when an access token is no longer valid.
 *
 * The retry loop itself (`withFreshClient { }`) requires mocking the
 * `Keycloak` admin client itself, which KeycloakAdminClient doesn't
 * expose. Full end-to-end coverage lives in
 * `KeycloakSeederSingleRealmIT` (gated on `RUN_KEYCLOAK_IT=true`) where
 * the helper fires against a real Keycloak Testcontainer.
 */
class KeycloakSeederReauthTest {

    private fun seeder(): KeycloakSeeder = KeycloakSeeder(
        spec = SeedSpec(realms = emptyList(), serviceClients = emptyList(), devUsers = emptyList()),
        baseUrl = "http://localhost:8182",
        adminUsername = "admin",
        adminPassword = "admin",
        superAdminUsername = "",
        superAdminPassword = "",
        globalAdminEnabled = false,
        defaultPassword = "test",
    )

    /**
     * The Keycloak admin-client wraps a 401 in a `NotFoundException`
     * (the legacy `Response.Status.NOT_FOUND` quirk for non-GET endpoints
     * with no body) with the real status stashed in the response. For
     * other status codes we use a generic `WebApplicationException` with
     * a custom response — `NotFoundException`'s constructor enforces
     * status 404 only.
     */
    private fun mockWae(status: Int, body: String): WebApplicationException {
        val resp = mock(Response::class.java)
        val statusInfo = mock(Response.StatusType::class.java)
        `when`(resp.status).thenReturn(status)
        `when`(resp.statusInfo).thenReturn(statusInfo)
        `when`(statusInfo.family).thenReturn(
            when (status) {
                in 400..499 -> Response.Status.Family.CLIENT_ERROR
                in 500..599 -> Response.Status.Family.SERVER_ERROR
                else -> Response.Status.Family.SUCCESSFUL
            },
        )
        `when`(resp.readEntity(String::class.java)).thenReturn(body)
        return if (status == 404) {
            NotFoundException("not found", resp)
        } else {
            WebApplicationException("status $status", resp)
        }
    }

    @Test
    fun `isTokenExpired returns true on 401 invalid_token`() {
        val s = seeder()
        assertTrue(
            s.isTokenExpired(
                mockWae(401, """{"error":"invalid_token","error_description":"Token is not active"}"""),
            ),
        )
    }

    @Test
    fun `isTokenExpired returns true on 401 with Token is not active`() {
        val s = seeder()
        assertTrue(
            s.isTokenExpired(
                mockWae(401, """{"error":"invalid_token","error_description":"Token is not active (expired)"}"""),
            ),
        )
    }

    @Test
    fun `isTokenExpired returns false on 404`() {
        val s = seeder()
        assertFalse(s.isTokenExpired(mockWae(404, """{"error":"not_found"}""")))
    }

    @Test
    fun `isTokenExpired returns false on 401 with non-token body`() {
        val s = seeder()
        // 401 but body says invalid_client — that's a credentials problem,
        // not a token expiry, so the helper must NOT retry (which would
        // just hit the same auth failure again).
        assertFalse(s.isTokenExpired(mockWae(401, """{"error":"invalid_client","error_description":"Public client not allowed to retrieve service account"}""")))
    }

    @Test
    fun `isTokenExpired returns false on 403`() {
        val s = seeder()
        // 403 is "forbidden" — could be a missing realm-management role.
        // We don't retry, because retrying with a fresh token doesn't
        // change the underlying permission state.
        assertFalse(s.isTokenExpired(mockWae(403, """{"error":"forbidden"}""")))
    }

    @Test
    fun `isTokenExpired returns false on generic WAE without response`() {
        val s = seeder()
        // Edge case: a WAE without a response object (rare in practice).
        // Fail safe — do NOT retry, because we can't determine status.
        assertFalse(s.isTokenExpired(WebApplicationException()))
    }

    @Test
    fun `isTokenExpired returns false on 500`() {
        val s = seeder()
        // 500 is server-side. A retry with a fresh token won't help; the
        // operator must investigate the server.
        assertFalse(s.isTokenExpired(mockWae(500, """{"error":"server_error"}""")))
    }
}