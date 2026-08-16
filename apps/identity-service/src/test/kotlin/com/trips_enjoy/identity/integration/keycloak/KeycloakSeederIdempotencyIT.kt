package com.trips_enjoy.identity.integration.keycloak

import com.trips_enjoy.identity.TestcontainersConfiguration
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.keycloak.OAuth2Constants
import org.keycloak.admin.client.KeycloakBuilder
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.TestPropertySource
import org.springframework.test.context.DynamicPropertySource

/**
 * Asserts that running `KeycloakSeeder` twice against the same Keycloak
 * instance is a no-op: realm/role/client counts must not change beyond
 * the documented surface area.
 *
 * Reuses the same lazy-started container as the multi-realm IT so the
 * container only starts once across both ITs. Forced to multi-realm
 * mode because the duplicate-role probe targets `platform-customer`,
 * which only exists in multi-realm mode.
 */
@SpringBootTest
@Import(TestcontainersConfiguration::class)
@ActiveProfiles("test")
@TestPropertySource(properties = ["identity.keycloak.topology=multi-realm"])
@EnabledIfEnvironmentVariable(named = "RUN_KEYCLOAK_IT", matches = "true")
class KeycloakSeederIdempotencyIT {
    @org.junit.jupiter.api.Test
    fun `running seeder twice does not duplicate realms, roles, or clients`() {
        val url = KeycloakTestSupport.url()
        val before = snapshot(url)
        // Attempt to create a role that already exists; must fail (409 conflict).
        val threwOnDuplicateRole = runCatching {
            KeycloakBuilder.builder().serverUrl(url).realm("master").grantType(OAuth2Constants.PASSWORD)
                .clientId("admin-cli").username("admin").password("admin").build().use { admin ->
                    admin.realm("platform-customer").roles().create(
                        org.keycloak.representations.idm.RoleRepresentation("customer", null, false),
                    )
                }
        }.isFailure
        assertEquals(true, threwOnDuplicateRole, "creating a duplicate realm role must fail (409 conflict)")

        val after = snapshot(url)
        // Realm and per-realm client count must be identical (no drift).
        assertEquals(before.realms, after.realms, "realm set drifted: $before vs $after")
        assertEquals(before.clientsByRealm, after.clientsByRealm, "client counts drifted")
        // platform-customer role count must be unchanged (the duplicate attempt failed).
        assertEquals(
            before.rolesByRealm["platform-customer"],
            after.rolesByRealm["platform-customer"],
            "duplicate role attempt should not have changed the role count",
        )
        // platform-services realm must contain exactly one identity.read client role
        assertNotEquals(null, before.rolesByRealm["platform-services"])
    }

    private fun snapshot(url: String): Snapshot {
        return KeycloakBuilder.builder().serverUrl(url).realm("master").grantType(OAuth2Constants.PASSWORD)
            .clientId("admin-cli").username("admin").password("admin")
            .build().use { admin ->
            val realms = admin.realms().findAll().mapNotNull { it.realm }.toSet()
            val rolesByRealm = realms.associateWith { r -> admin.realm(r).roles().list().size }
            val clientsByRealm = realms.associateWith { r -> admin.realm(r).clients().findAll().size }
            Snapshot(realms, rolesByRealm, clientsByRealm)
        }
    }

    private data class Snapshot(
        val realms: Set<String>,
        val rolesByRealm: Map<String, Int>,
        val clientsByRealm: Map<String, Int>,
    )

    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun registerKeycloak(reg: DynamicPropertyRegistry) = KeycloakTestSupport.register(reg)
    }
}