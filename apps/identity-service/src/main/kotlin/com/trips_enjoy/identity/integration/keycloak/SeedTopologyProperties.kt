package com.trips_enjoy.identity.integration.keycloak

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Topology selection for the `KeycloakSeeder`. Two shapes are supported:
 *
 * - **`single-realm`** (default): one realm — `devRealmName` (default
 *   `platform-dev`) — holds every client, role, mapper, and user. This is
 *   the dev / CI shape: a fresh developer runs `./gradlew bootRun` and gets
 *   a fully provisioned platform in one realm, no per-realm env wiring.
 *
 * - **`multi-realm`** (opt-in via `IDENTITY_KEYCLOAK_TOPOLOGY=multi-realm`):
 *   the documented 6-realm split — `platform-customer`, `platform-driver`,
 *   `platform-courier`, `platform-staff`, `platform-internal`,
 *   `platform-services`. This is the stg / prod shape.
 *
 * The two properties `servicesRealmName` + `adminRealmName` are
 * optional escape hatches: they let an operator split the services
 * realm away from the admin realm even in single-realm mode (e.g. for
 * testing a tenant-isolation failure). When unset, they default to
 * `devRealmName` in single-realm mode and to `platform-services` /
 * `platform-internal` in multi-realm mode.
 */
@ConfigurationProperties(prefix = "identity.keycloak")
@ConditionalOnProperty("identity.keycloak.seed.enabled", havingValue = "true")
data class SeedTopologyProperties(
    /** `"single-realm"` (default) or `"multi-realm"`. */
    val topology: String = "single-realm",
    /** When [topology] is `single-realm`, the realm that holds everything. */
    val devRealmName: String = "platform-dev",
    /** Optional override for the realm that holds the per-service admin/read/write/support realm roles. */
    val adminRealmName: String? = null,
    /** Optional override for the realm that holds service clients + per-service claims. */
    val servicesRealmName: String? = null,
) {
    /** Resolve the effective dev realm (default = `platform-dev` when blank). */
    public fun effectiveDevRealmName(): String = devRealmName.takeIf { it.isNotBlank() } ?: "platform-dev"

/** Resolve the effective services realm (overrides win; else topology default). */
    public fun effectiveServicesRealm(): String = servicesRealmName?.takeIf { it.isNotBlank() }
        ?: when (topology) {
            "single-realm" -> effectiveDevRealmName()
            "multi-realm" -> "platform-services"
            else -> error("identity.keycloak.topology must be 'single-realm' or 'multi-realm', got '$topology'")
        }

    /** Resolve the effective admin realm. */
    public fun effectiveAdminRealm(): String = adminRealmName?.takeIf { it.isNotBlank() }
        ?: when (topology) {
            "single-realm" -> effectiveDevRealmName()
            "multi-realm" -> "platform-internal"
            else -> error("identity.keycloak.topology must be 'single-realm' or 'multi-realm', got '$topology'")
        }
}