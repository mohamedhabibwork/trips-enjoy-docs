package com.trips_enjoy.identity.integration.keycloak

import com.trips_enjoy.identity.application.KeycloakAdminClient
import com.trips_enjoy.identity.application.KeycloakUserSnapshot
import com.trips_enjoy.identity.domain.Identity
import com.trips_enjoy.identity.domain.IdentityRepository
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.UUID

/**
 * Higher-level facade over `KeycloakAdminClient` for user-directory operations
 * that span both the local `identities` table and Keycloak. Used by
 * `IdentityApplicationService.erase()` and the `KeycloakSeeder` bootstrap.
 */
@Component
class KeycloakUserDirectory(
    private val keycloak: KeycloakAdminClient,
    private val identities: IdentityRepository,
) {
    /**
     * Looks up a Keycloak user, then reconciles into the local `identities` row
     * (back-channel per `INTEGRATION.md` §4.1–§4.5).
     *
     * Returns the reconciled `Identity` row, or `null` if Keycloak has no user
     * with that `kc_sub` in the given realm.
     */
    fun reconcileFromKeycloak(realm: String, kcSub: String, userType: String): Identity? {
        val snapshot = runCatching { keycloak.getUser(realm, kcSub) }.getOrNull() ?: return null
        val existing = identities.findByKeycloakSubjectAndRealm(kcSub, realm)
        val now = Instant.now()
        if (existing == null) {
            val id = Identity(
                id = UUID.randomUUID(),
                keycloakSubject = kcSub,
                realm = realm,
                userType = userType,
                name = "${snapshot.firstName ?: ""} ${snapshot.lastName ?: ""}".trim().ifBlank { null },
                email = snapshot.email,
                emailVerified = snapshot.emailVerified,
                phone = null,
                locale = null,
                createdBy = UUID(0, 0),
                updatedBy = UUID(0, 0),
                createdAt = now,
                updatedAt = now,
            )
            identities.save(id)
            return id
        }
        existing.name = "${snapshot.firstName ?: ""} ${snapshot.lastName ?: ""}".trim().ifBlank { existing.name }
        existing.email = snapshot.email
        existing.emailVerified = snapshot.emailVerified
        existing.updatedAt = now
        identities.save(existing)
        return existing
    }

    /**
     * Returns the Keycloak snapshot for the user identified by `kc_sub`, or
     * null if Keycloak has no such user. Used by `IdentityApplicationService.erase`
     * to verify Keycloak's state before issuing the delete.
     */
    fun findInKeycloak(realm: String, kcSub: String): KeycloakUserSnapshot? =
        runCatching { keycloak.getUser(realm, kcSub) }.getOrNull()

    /**
     * Returns the local identity row by its `kc_sub` in the given realm
     * (delegates to `IdentityRepository`).
     */
    fun findLocal(realm: String, kcSub: String): Identity? =
        identities.findByKeycloakSubjectAndRealm(kcSub, realm)
}
