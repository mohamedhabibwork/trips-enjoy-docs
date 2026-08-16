package com.trips_enjoy.identity.integration.keycloak

import com.trips_enjoy.identity.integration.keycloak.SeedSpec
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Test

/**
 * Resilience contract for [KeycloakSeeder.run] — the seeder is a dev-time
 * realm bootstrap, NOT a runtime prerequisite. identity-service must boot
 * cleanly even when Keycloak is unreachable so the service can stand up
 * before (or without) the OAuth/OIDC tier. This test pins that contract:
 * the seeder's `run()` method catches network-level failures from
 * `openAdminClient()` and degrades to a WARN log; non-network errors
 * still propagate so genuine bugs surface.
 */
class KeycloakSeederResilienceTest {

    /**
     * Connecting to `127.0.0.1:1` deterministically fails with
     * `Connection refused` (port 1 is reserved by IANA and never accepts
     * connections). The seeder must absorb this and return normally.
     */
    @Test
    fun `run does not throw when Keycloak is unreachable`() {
        val s = KeycloakSeeder(
            spec = SeedSpec(realms = emptyList(), serviceClients = emptyList(), devUsers = emptyList()),
            baseUrl = "http://127.0.0.1:1",
            adminUsername = "admin",
            adminPassword = "admin",
            superAdminUsername = "",
            superAdminPassword = "",
            globalAdminEnabled = false,
            defaultPassword = "test",
        )
        assertDoesNotThrow { s.run(org.springframework.boot.DefaultApplicationArguments()) }
    }

    /**
     * An unreachable hostname resolves to `UnknownHostException`. The
     * seeder must also catch that variant — covers the
     * "Keycloak not yet deployed / DNS not yet warmed" failure mode.
     */
    @Test
    fun `run does not throw when Keycloak host is unresolvable`() {
        val s = KeycloakSeeder(
            spec = SeedSpec(realms = emptyList(), serviceClients = emptyList(), devUsers = emptyList()),
            baseUrl = "http://keycloak.invalid.local:8080",
            adminUsername = "admin",
            adminPassword = "admin",
            superAdminUsername = "",
            superAdminPassword = "",
            globalAdminEnabled = false,
            defaultPassword = "test",
        )
        assertDoesNotThrow { s.run(org.springframework.boot.DefaultApplicationArguments()) }
    }
}