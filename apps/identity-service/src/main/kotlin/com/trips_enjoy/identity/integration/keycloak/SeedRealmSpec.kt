package com.trips_enjoy.identity.integration.keycloak

/**
 * Declarative shape of one Keycloak realm that `KeycloakSeeder` provisions on
 * startup when `identity.keycloak.seed.enabled=true`. The spec is consumed by
 * both the seeder (idempotent ensureX helpers) and `OpenApiConfiguration`
 * (Server URL + per-channel SecurityScheme + tag) so there is exactly one
 * source of truth for the realm graph.
 *
 * Field defaults mirror the OIDC scopes listed in
 * `docs/architecture/KEYCLOAK_ARCHITECTURE.md` §"Scopes and Claims".
 */
data class SeedRealmSpec(
    val realm: String,
    val realmRoles: List<String> = emptyList(),
    val protocolMappers: List<SeedProtocolMapper> = emptyList(),
    val defaultClientScopes: List<String> = listOf(
        "openid", "profile", "email", "phone", "offline_access",
    ),
    val channelClients: List<SeedChannelClient> = emptyList(),
    /**
     * Names of realm-level client scopes that should be attached as
     * `defaultDefaultClientScopes` on the channel clients in this realm.
     * The seeder wires the `platform-claims` scope automatically for
     * every realm; per-realm extras live here (e.g. `service-claims`
     * on `platform-services`).
     */
    val additionalDefaultScopes: List<String> = emptyList(),
)

/** A public OAuth client bound to one realm (mobile/web/native). */
data class SeedChannelClient(
    val realm: String,
    val clientId: String,
    val publicClient: Boolean,
)

/**
 * One protocol mapper on a realm-level client scope (the platform-claims
 * scope). Mirrors the canonical claim set in
 * `docs/architecture/KEYCLOAK_ARCHITECTURE.md` §"Scopes and Claims".
 *
 * `mapperType` values correspond to Keycloak's
 * `org.keycloak.protocol.oidc.mappers.*` package:
 *   - `oidc-usermodel-attribute-mapper`  → user attribute → claim
 *   - `oidc-usermodel-realm-role-mapper` → realm role name → claim (string or array)
 *   - `oidc-hardcoded-claim`             → fixed literal claim
 *   - `oidc-group-mapper`                → group → claim
 *   - `oidc-session-note-mapper`         → session note → claim
 */
data class SeedProtocolMapper(
    val name: String,
    val claim: String,
    val mapperType: String = "oidc-usermodel-attribute-mapper",
    val userAttribute: String? = null,
    val realmRole: String? = null,
    val groupPath: String? = null,
    val sessionNote: String? = null,
    val hardcodedValue: String? = null,
    val isMultivalued: Boolean = false,
)

/**
 * Per-service claim contract. Emitted by protocol mappers on the
 * `service-claims` realm-level client scope attached to each
 * `<service>-service` client in `platform-services`. The mapper
 * generator reads realm-role membership of `<prefix>.read/.write/.admin/.support`
 * and projects it into:
 *   - `<service>.scopes` — multivalued string array of the names the user holds
 *   - `<service>.level`  — int 0..4 = the highest mapped level (read=1, write=2,
 *                          admin=3, support=4); 0 if the user holds none
 *   - `<service>.tenant` — first `tenant:<service>:*` realm role name (or absent)
 *
 * Authorization across `<service>` endpoints can therefore check
 * `<service>.level >= 2` instead of calling Keycloak for the role list.
 */
data class SeedServiceClaim(
    val service: String,
    val scopesClaim: String,
    val levelClaim: String,
    val tenantClaim: String,
    val readRole: String,
    val writeRole: String,
    val adminRole: String,
    val supportRole: String,
) {
    /** Prefix used in role names — e.g. `trip` for `trip-service`. */
    val prefix: String get() = service.removeSuffix("-service")

    /** All role names that participate in the level computation. */
    val roleNames: List<String> get() = listOf(readRole, writeRole, adminRole, supportRole)

    companion object {
        /** Construct the canonical triple for a `<service>-service` client. */
        fun canonicalFor(service: String,
                          readRole: String = "${service.removeSuffix("-service")}.read",
                          writeRole: String = "${service.removeSuffix("-service")}.write",
                          adminRole: String = "${service.removeSuffix("-service")}.admin",
                          supportRole: String = "${service.removeSuffix("-service")}.support"): SeedServiceClaim {
            val s = service.removeSuffix("-service")
            return SeedServiceClaim(
                service = service,
                scopesClaim = "$s.scopes",
                levelClaim = "$s.level",
                tenantClaim = "$s.tenant",
                readRole = readRole,
                writeRole = writeRole,
                adminRole = adminRole,
                supportRole = supportRole,
            )
        }
    }
}