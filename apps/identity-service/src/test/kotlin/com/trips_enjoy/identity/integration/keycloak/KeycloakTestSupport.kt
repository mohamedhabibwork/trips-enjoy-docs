package com.trips_enjoy.identity.integration.keycloak

import dasniko.testcontainers.keycloak.KeycloakContainer
import org.springframework.test.context.DynamicPropertyRegistry

/**
 * Shared Keycloak Testcontainer lifecycle for the seeder ITs. The
 * container starts lazily once and is reused across `KeycloakSeederIT`
 * and `KeycloakSeederIdempotencyIT` so we don't pay ~30s twice.
 *
 * Only in scope when `RUN_KEYCLOAK_IT=true`; otherwise the ITs are skipped
 * via `@EnabledIfEnvironmentVariable` and this object is never touched.
 */
internal object KeycloakTestSupport {
    val container: KeycloakContainer by lazy {
        KeycloakContainer("quay.io/keycloak/keycloak:24.0").apply { start() }
    }

    fun url(): String = container.getAuthServerUrl().removeSuffix("/auth")

    fun register(reg: DynamicPropertyRegistry) {
        val url = url()
        reg.add("identity.keycloak.base-url") { url }
        reg.add("identity.keycloak.admin-username") { "admin" }
        reg.add("identity.keycloak.admin-password") { "admin" }
        reg.add("identity.keycloak.seed.admin-username") { "admin" }
        reg.add("identity.keycloak.seed.admin-password") { "admin" }
        reg.add("identity.keycloak.seed.super-admin-username") { "admin@inovoria.com" }
        reg.add("identity.keycloak.seed.super-admin-password") { "H@bib1998" }
        reg.add("identity.keycloak.seed.enabled") { "true" }
        reg.add("identity.keycloak.seed.default-password") { "H@bib1998" }
    }
}