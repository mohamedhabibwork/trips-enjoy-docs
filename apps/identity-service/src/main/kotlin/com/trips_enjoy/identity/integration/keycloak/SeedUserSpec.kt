package com.trips_enjoy.identity.integration.keycloak

/**
 * Declarative shape of one user the seeder creates per realm for local-dev
 * and integration-test convenience. Passwords default to the platform
 * default (`H@bib1998`) unless overridden via
 * `IDENTITY_KEYCLOAK_SEED_DEFAULT_PASSWORD`.
 *
 * Realm roles + client roles + per-service roles are granted idempotently:
 * the user is looked up by username first; missing role mappings are added;
 * existing role mappings are left in place.
 *
 * `serviceRoles` carries per-service `<prefix>.read/.write/.admin/.support`
 * grants in the `platform-services` realm — these are the roles the
 * `<service>.scopes` / `<service>.level` claims reflect, so a dev user
 * can exercise authorization without going through the admin endpoint.
 */
data class SeedUserSpec(
    val username: String,
    val email: String,
    val realm: String,
    val realmRoles: List<String> = emptyList(),
    val clientRoles: Map<String, List<String>> = emptyMap(),
    /**
     * Map of `<service>-service` client → list of `<prefix>.read/.write/.admin/.support`
     * realm roles in `platform-services`. The seeder grants these as realm
     * role mappings on the user's `platform-services` representation so
     * the `<service>.scopes` claim reflects them.
     */
    val serviceRoles: Map<String, List<String>> = emptyMap(),
    val password: String? = null,
    val temporary: Boolean = true,
)

/**
 * Single-aggregator bean built once at startup. Holds the canonical realm,
 * channel-client, service-client, per-realm user lists, and per-service
 * claim triple the seeder and the OpenAPI generator both consume.
 * `serviceClients` is the 20-service bounded-context roster from
 * `docs/architecture/ARCHITECTURE.md`.
 *
 * `servicesRealm` + `adminRealm` resolve at catalog-build time:
 * - in `single-realm` mode (default) both equal `devRealmName`
 *   (default `platform-dev`),
 * - in `multi-realm` mode they default to `platform-services` /
 *   `platform-internal` respectively.
 *
 * The seeder reads these two fields instead of hardcoded realm names so
 * it doesn't have to branch on topology.
 */
data class SeedSpec(
    val realms: List<SeedRealmSpec>,
    val serviceClients: List<String>,
    val devUsers: List<SeedUserSpec>,
    /** Per-service claim contract used by protocol mappers + OpenAPI. */
    val serviceClaims: List<SeedServiceClaim> = emptyList(),
    /** Realm where the per-service promoted realm roles + service clients live. */
    val servicesRealm: String = "platform-services",
    /** Realm where the per-service read/write/admin/support realm roles live (used for super-admin grant). */
    val adminRealm: String = "platform-internal",
    /** Topology mode: `"single-realm"` (default) or `"multi-realm"`. */
    val topology: String = "single-realm",
)