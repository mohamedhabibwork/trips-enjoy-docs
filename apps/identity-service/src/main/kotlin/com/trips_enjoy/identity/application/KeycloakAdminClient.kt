package com.trips_enjoy.identity.application

import com.trips_enjoy.identity.api.ApiException
import org.keycloak.OAuth2Constants
import org.keycloak.admin.client.Keycloak
import org.keycloak.admin.client.KeycloakBuilder
import org.keycloak.representations.idm.CredentialRepresentation
import org.keycloak.representations.idm.FederatedIdentityRepresentation
import org.keycloak.representations.idm.GroupRepresentation
import org.keycloak.representations.idm.RoleRepresentation
import org.keycloak.representations.idm.UserRepresentation
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component

/** Result of a force-logout request. */
data class LogoutResult(val sessionsRevoked: Int, val revokedJtis: List<String>)

/** A Keycloak user session. */
data class KeycloakSession(
    val id: String,
    val ipAddress: String?,
    val started: Long,
    val lastAccess: Long,
)

/**
 * Snapshot of a Keycloak user's state. Mirrors a subset of
 * `org.keycloak.representations.idm.UserRepresentation` plus a denormalised
 * field for the Keycloak internal id (uuid string).
 */
data class KeycloakUserSnapshot(
    val id: String,
    val username: String,
    val email: String?,
    val firstName: String?,
    val lastName: String?,
    val enabled: Boolean,
    val emailVerified: Boolean,
    val attributes: Map<String, List<String>>,
    val requiredActions: List<String>,
    val realmRoles: List<String>,
)

interface KeycloakAdminClient {
    // ---- Lifecycle (existing) ----
    fun setEnabled(realm: String, subject: String, enabled: Boolean)
    fun logout(realm: String, subject: String): LogoutResult
    fun listSessions(realm: String, subject: String): List<KeycloakSession>
    fun listRealmRoles(realm: String, subject: String): List<String>
    fun grantRealmRole(realm: String, subject: String, role: String)
    fun revokeRealmRole(realm: String, subject: String, role: String)

    // ---- User CRUD (new — full admin-client surface) ----
    fun getUser(realm: String, subject: String): KeycloakUserSnapshot
    fun findByUsername(realm: String, username: String, exact: Boolean = true): List<KeycloakUserSnapshot>
    fun findByEmail(realm: String, email: String): List<KeycloakUserSnapshot>
    fun createUser(realm: String, snapshot: KeycloakUserSnapshot): String
    fun deleteUser(realm: String, subject: String)
    fun setUserAttributes(realm: String, subject: String, attributes: Map<String, List<String>>)
    fun setRequiredActions(realm: String, subject: String, actions: List<String>)
    fun disableCredentialTypes(realm: String, subject: String, credentialTypes: List<String>)
    fun resetPassword(realm: String, subject: String, password: String, temporary: Boolean)
    fun sendPasswordResetEmail(realm: String, subject: String)
    fun sendExecuteActionsEmail(realm: String, subject: String, actions: List<String>, lifespanSeconds: Int? = null)

    // ---- Group + role mapping (new) ----
    fun setGroups(realm: String, subject: String, groupPaths: List<String>)
    fun getUserGroups(realm: String, subject: String): List<String>
    fun addRealmRoleMapping(realm: String, subject: String, role: String)
    fun removeRealmRoleMapping(realm: String, subject: String, role: String)
    fun linkFederatedIdentity(realm: String, subject: String, identity: FederatedIdentityRepresentation)
}

@Component
class KeycloakAdminApiClient(
    @Value("\${identity.keycloak.base-url}") baseUrl: String,
    @Value("\${identity.keycloak.admin-realm}") private val adminRealm: String,
    @Value("\${identity.keycloak.admin-client-id}") private val adminClientId: String,
    @Value("\${identity.keycloak.admin-client-secret}") private val adminClientSecret: String,
) : KeycloakAdminClient {
    private val serverUrl = baseUrl

    // ---- Existing lifecycle ops ----

    override fun setEnabled(realm: String, subject: String, enabled: Boolean) {
        guarded {
            openClient().use { keycloak ->
                val user = keycloak.realm(realm).users()[subject]
                val representation = user.toRepresentation().apply { isEnabled = enabled }
                user.update(representation)
            }
        }
    }

    override fun logout(realm: String, subject: String): LogoutResult {
        return guarded {
            openClient().use { keycloak ->
                val userResource = keycloak.realm(realm).users()[subject]
                val sessions = userResource.userSessions
                val revokedJtis: List<String> = sessions.mapNotNull { it.id }
                userResource.logout()
                LogoutResult(revokedJtis.size, revokedJtis)
            }
        }
    }

    override fun listSessions(realm: String, subject: String): List<KeycloakSession> {
        return guarded {
            openClient().use { keycloak ->
                keycloak.realm(realm).users()[subject].userSessions.map { s ->
                    KeycloakSession(
                        id = s.id ?: "",
                        ipAddress = s.ipAddress,
                        started = s.start,
                        lastAccess = s.lastAccess,
                    )
                }
            }
        }
    }

    override fun listRealmRoles(realm: String, subject: String): List<String> {
        return guarded {
            openClient().use { keycloak ->
                keycloak.realm(realm).users()[subject].roles().realmLevel().listAll().mapNotNull { it.name }
            }
        }
    }

    override fun grantRealmRole(realm: String, subject: String, role: String) {
        guarded {
            openClient().use { keycloak ->
                val r = keycloak.realm(realm).roles()[role].toRepresentation()
                keycloak.realm(realm).users()[subject].roles().realmLevel().add(listOf(r))
            }
        }
    }

    override fun revokeRealmRole(realm: String, subject: String, role: String) {
        guarded {
            openClient().use { keycloak ->
                val r = keycloak.realm(realm).roles()[role].toRepresentation()
                keycloak.realm(realm).users()[subject].roles().realmLevel().remove(listOf(r))
            }
        }
    }

    // ---- User CRUD ----

    override fun getUser(realm: String, subject: String): KeycloakUserSnapshot {
        return guarded {
            openClient().use { keycloak ->
                val user = keycloak.realm(realm).users()[subject].toRepresentation()
                KeycloakUserSnapshot(
                    id = user.id ?: subject,
                    username = user.username ?: "",
                    email = user.email,
                    firstName = user.firstName,
                    lastName = user.lastName,
                    enabled = user.isEnabled,
                    emailVerified = user.isEmailVerified,
                    attributes = user.attributes ?: emptyMap(),
                    requiredActions = user.requiredActions ?: emptyList(),
                    realmRoles = keycloak.realm(realm).users()[subject].roles().realmLevel().listAll().mapNotNull { it.name },
                )
            }
        }
    }

    override fun findByUsername(realm: String, username: String, exact: Boolean): List<KeycloakUserSnapshot> {
        return guarded {
            openClient().use { keycloak ->
                keycloak.realm(realm).users().searchByUsername(username, exact).map { it.toSnapshot(realm, keycloak) }
            }
        }
    }

    override fun findByEmail(realm: String, email: String): List<KeycloakUserSnapshot> {
        return guarded {
            openClient().use { keycloak ->
                val users = keycloak.realm(realm).users().searchByAttributes(0, 50, false, false, "email:$email")
                users.map { it.toSnapshot(realm, keycloak) }
            }
        }
    }

    override fun createUser(realm: String, snapshot: KeycloakUserSnapshot): String {
        return guarded {
            openClient().use { keycloak ->
                val rep = snapshot.toRepresentation()
                val response = keycloak.realm(realm).users().create(rep)
                val path = response.location.path
                path.substringAfterLast('/')
            }
        }
    }

    override fun deleteUser(realm: String, subject: String) {
        guarded {
            openClient().use { keycloak ->
                keycloak.realm(realm).users()[subject].remove()
            }
        }
    }

    override fun setUserAttributes(realm: String, subject: String, attributes: Map<String, List<String>>) {
        guarded {
            openClient().use { keycloak ->
                val user = keycloak.realm(realm).users()[subject].toRepresentation()
                user.attributes = attributes
                keycloak.realm(realm).users()[subject].update(user)
            }
        }
    }

    override fun setRequiredActions(realm: String, subject: String, actions: List<String>) {
        guarded {
            openClient().use { keycloak ->
                val user = keycloak.realm(realm).users()[subject].toRepresentation()
                user.requiredActions = actions
                keycloak.realm(realm).users()[subject].update(user)
            }
        }
    }

    override fun disableCredentialTypes(realm: String, subject: String, credentialTypes: List<String>) {
        guarded {
            openClient().use { keycloak ->
                keycloak.realm(realm).users()[subject].disableCredentialType(credentialTypes)
            }
        }
    }

    override fun resetPassword(realm: String, subject: String, password: String, temporary: Boolean) {
        guarded {
            openClient().use { keycloak ->
                val credential = CredentialRepresentation().apply {
                    type = CredentialRepresentation.PASSWORD
                    value = password
                    isTemporary = temporary
                }
                keycloak.realm(realm).users()[subject].resetPassword(credential)
            }
        }
    }

    override fun sendPasswordResetEmail(realm: String, subject: String) {
        guarded {
            openClient().use { keycloak ->
                keycloak.realm(realm).users()[subject].resetPasswordEmail()
            }
        }
    }

    override fun sendExecuteActionsEmail(realm: String, subject: String, actions: List<String>, lifespanSeconds: Int?) {
        guarded {
            openClient().use { keycloak ->
                if (lifespanSeconds != null) {
                    keycloak.realm(realm).users()[subject].executeActionsEmail(actions, lifespanSeconds)
                } else {
                    keycloak.realm(realm).users()[subject].executeActionsEmail(actions)
                }
            }
        }
    }

    // ---- Group + role mapping ----

    override fun setGroups(realm: String, subject: String, groupPaths: List<String>) {
        guarded {
            openClient().use { keycloak ->
                val current = keycloak.realm(realm).users()[subject].groups().mapNotNull { it.path }
                current.forEach { keycloak.realm(realm).users()[subject].leaveGroup(it) }
                groupPaths.forEach { keycloak.realm(realm).users()[subject].joinGroup(it) }
            }
        }
    }

    override fun getUserGroups(realm: String, subject: String): List<String> {
        return guarded {
            openClient().use { keycloak ->
                keycloak.realm(realm).users()[subject].groups().mapNotNull { it.path }
            }
        }
    }

    override fun addRealmRoleMapping(realm: String, subject: String, role: String) {
        guarded {
            openClient().use { keycloak ->
                val r = keycloak.realm(realm).roles()[role].toRepresentation()
                keycloak.realm(realm).users()[subject].roles().realmLevel().add(listOf(r))
            }
        }
    }

    override fun removeRealmRoleMapping(realm: String, subject: String, role: String) {
        guarded {
            openClient().use { keycloak ->
                val r = keycloak.realm(realm).roles()[role].toRepresentation()
                keycloak.realm(realm).users()[subject].roles().realmLevel().remove(listOf(r))
            }
        }
    }

    override fun linkFederatedIdentity(realm: String, subject: String, identity: FederatedIdentityRepresentation) {
        guarded {
            openClient().use { keycloak ->
                val idpAlias = identity.identityProvider
                keycloak.realm(realm).users()[subject].addFederatedIdentity(idpAlias, identity)
            }
        }
    }

    // ---- Helpers ----

    private fun UserRepresentation.toSnapshot(realm: String, keycloak: Keycloak): KeycloakUserSnapshot {
        val id = this.id ?: ""
        return KeycloakUserSnapshot(
            id = id,
            username = username ?: "",
            email = email,
            firstName = firstName,
            lastName = lastName,
            enabled = isEnabled,
            emailVerified = isEmailVerified,
            attributes = attributes ?: emptyMap(),
            requiredActions = requiredActions ?: emptyList(),
            realmRoles = keycloak.realm(realm).users()[id].roles().realmLevel().listAll().mapNotNull { it.name },
        )
    }

    private fun KeycloakUserSnapshot.toRepresentation(): UserRepresentation {
        return UserRepresentation().apply {
            username = this@toRepresentation.username
            email = this@toRepresentation.email
            firstName = this@toRepresentation.firstName
            lastName = this@toRepresentation.lastName
            isEnabled = this@toRepresentation.enabled
            isEmailVerified = this@toRepresentation.emailVerified
            attributes = this@toRepresentation.attributes
            requiredActions = this@toRepresentation.requiredActions
        }
    }

    private fun openClient(): Keycloak {
        require(adminClientId.isNotBlank() && adminClientSecret.isNotBlank()) {
            "Keycloak admin client credentials are not configured"
        }
        return KeycloakBuilder.builder().serverUrl(serverUrl).realm(adminRealm).grantType(OAuth2Constants.CLIENT_CREDENTIALS)
            .clientId(adminClientId).clientSecret(adminClientSecret).build()
    }

    private inline fun <T> guarded(block: () -> T): T = try {
        block()
    } catch (exception: Exception) {
        throw ApiException(HttpStatus.BAD_GATEWAY, "DEPENDENCY_UPSTREAM_FAILURE", "Keycloak admin API is unavailable")
    }
}
