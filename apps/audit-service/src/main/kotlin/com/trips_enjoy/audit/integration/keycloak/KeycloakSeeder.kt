package com.trips_enjoy.audit.integration.keycloak

import org.keycloak.OAuth2Constants
import org.keycloak.admin.client.Keycloak
import org.keycloak.admin.client.KeycloakBuilder
import org.keycloak.representations.idm.ClientRepresentation
import org.keycloak.representations.idm.CredentialRepresentation
import org.keycloak.representations.idm.RealmRepresentation
import org.keycloak.representations.idm.RoleRepresentation
import org.keycloak.representations.idm.UserRepresentation
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component

// Idempotent provisioning of the Keycloak realms, roles, and clients
// required by audit-service TECH §10.5 and §10.7.
//
// Realms provisioned:
//   - platform-customer, platform-driver, platform-courier, platform-staff,
//     platform-internal, platform-services — same set identity-service
//     creates (audit-service shares the platform Keycloak)
//
// Roles provisioned per realm:
//   - platform-internal: admin, super_admin, platform.admin,
//     platform.super_admin, audit.admin
//   - platform-services: audit.read, audit.write, audit.admin, audit.svc
//   - all other realms: audit.read, audit.write, audit.admin
//
// Service-to-service access:
//   - identity-service, admin-service, notification-service,
//     configuration-service, reporting-service, fraud-risk-service,
//     customer-service, search-service, driver-service, trip-service,
//     pricing-service, restaurant-service, food-order-service,
//     courier-service, geolocation-service, payment-service,
//     ledger-service, chat-service, file-service, api-gateway —
//     each gets an `audit.read` client role granted to its service
//     account (so the service-account token has read scope)
//
// SUPER_ADMIN preset:
//   - One platform user `audit-super-admin` is created (if creds are
//     supplied) and granted both `platform.super_admin` and
//     `audit.admin` so the operator can hit /admin/v1/audit/* endpoints
//
// Activated only when:
//   - `audit-service.keycloak-seed.enabled=true`
//   - the audit-service.seed.username and password env vars are set
//     (else the seeder logs a warning and exits without provisioning)
@Component
@ConditionalOnProperty(name = ["audit-service.keycloak-seed.enabled"], havingValue = "true")
@Order(Ordered.LOWEST_PRECEDENCE - 10)
class KeycloakSeeder(
    @Value("\${audit-service.keycloak.base-url}") private val baseUrl: String,
    @Value("\${audit-service.keycloak.admin-realm}") private val adminRealm: String,
    @Value("\${audit-service.keycloak.admin-client-id}") private val adminClientId: String,
    @Value("\${audit-service.keycloak.admin-client-secret}") private val adminClientSecret: String,
    @Value("\${audit-service.seed-username:}") private val seedUsername: String,
    @Value("\${audit-service.seed-password:}") private val seedPassword: String,
) : ApplicationRunner {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun run(args: ApplicationArguments) {
        if (adminClientId.isBlank() || adminClientSecret.isBlank()) {
            log.warn(
                "KeycloakSeeder is enabled but admin client id/secret are blank; " +
                    "skipping provisioning. Set audit-service.keycloak.admin-client-id and " +
                    "audit-service.keycloak.admin-client-secret to enable.",
            )
            return
        }
        KeycloakBuilder.builder().serverUrl(baseUrl).realm(adminRealm).grantType(OAuth2Constants.CLIENT_CREDENTIALS)
            .clientId(adminClientId).clientSecret(adminClientSecret).build().use { keycloak ->
                realms.forEach { (realm, roles) -> ensureRealm(keycloak, realm); roles.forEach { ensureRealmRole(keycloak, realm, it) } }
                channelClients.forEach { ensureClient(keycloak, it.realm, it.clientId, it.publicClient) }
                services.forEach { service ->
                    ensureClient(keycloak, "platform-services", service, false)
                    val prefix = permissionPrefix(service)
                    listOf("$prefix.read", "$prefix.write", "$prefix.admin").forEach { ensureClientRole(keycloak, "platform-services", service, it) }
                    listOf("$prefix.read", "$prefix.write", "$prefix.admin", "$prefix.support").forEach { ensureRealmRole(keycloak, "platform-internal", it) }
                    ensureRealmRole(keycloak, "platform-services", "$prefix.svc")
                }
                grantAuditReadToServiceAccounts(keycloak)
                if (seedUsername.isNotBlank() && seedPassword.isNotBlank()) {
                    ensureSuperAdmin(keycloak)
                } else {
                    log.warn("Super-admin was not seeded: AUDIT_SEED_SUPER_ADMIN_USERNAME and AUDIT_SEED_SUPER_ADMIN_PASSWORD must be set to enable.")
                }
            }
        log.info(
            "Keycloak seed completed for {} realms, {} service clients, and {} channel clients",
            realms.size, services.size, channelClients.size,
        )
    }

    private fun ensureRealm(keycloak: Keycloak, name: String) {
        if (keycloak.realms().findAll().any { it.realm == name }) return
        keycloak.realms().create(
            RealmRepresentation().apply {
                realm = name
                isEnabled = true
                isRegistrationAllowed = false
                isResetPasswordAllowed = true
                isLoginWithEmailAllowed = true
                isDuplicateEmailsAllowed = false
            },
        )
    }

    private fun ensureRealmRole(keycloak: Keycloak, realm: String, name: String) {
        val roles = keycloak.realm(realm).roles().list()
        if (roles.none { it.name == name }) {
            keycloak.realm(realm).roles().create(RoleRepresentation(name, null, false))
        }
    }

    private fun ensureClient(keycloak: Keycloak, realm: String, clientId: String, publicClient: Boolean) {
        if (clientId(keycloak, realm, clientId) != null) return
        keycloak.realm(realm).clients().create(
            ClientRepresentation().apply {
                this.clientId = clientId
                protocol = "openid-connect"
                isEnabled = true
                isPublicClient = publicClient
                isStandardFlowEnabled = true
                isDirectAccessGrantsEnabled = false
                isServiceAccountsEnabled = !publicClient
                isFullScopeAllowed = false
                attributes = mapOf("pkce.code.challenge.method" to "S256")
            },
        )
    }

    private fun ensureClientRole(keycloak: Keycloak, realm: String, client: String, role: String) {
        val clientUuid = clientId(keycloak, realm, client) ?: error("Client $client was not created in $realm")
        val resource = keycloak.realm(realm).clients().get(clientUuid).roles()
        if (resource.list().none { it.name == role }) {
            resource.create(RoleRepresentation(role, null, false))
        }
    }

    /**
     * Grants each service's service-account user the `audit.read` client
     * role so service-account tokens have read scope on audit-service.
     * This is the platform's standard "every service can read audit"
     * convention from TECH.md §14.
     */
    private fun grantAuditReadToServiceAccounts(keycloak: Keycloak) {
        val realm = keycloak.realm("platform-services")
        val auditClientUuid = clientId(keycloak, "platform-services", "audit-service")
            ?: error("audit-service client missing in platform-services realm")
        val auditReadRole = realm.clients().get(auditClientUuid).roles().list().first { it.name == "audit.read" }
        services.forEach { service ->
            val clientUuid = clientId(keycloak, "platform-services", service) ?: return@forEach
            val user = realm.clients().get(clientUuid).serviceAccountUser
            val current = realm.users().get(user.id).roles().clientLevel(auditClientUuid).listAll()
            if (current.none { it.name == auditReadRole.name }) {
                realm.users().get(user.id).roles().clientLevel(auditClientUuid).add(listOf(auditReadRole))
            }
        }
    }

    private fun ensureSuperAdmin(keycloak: Keycloak) {
        val user = ensureUser(keycloak, "platform-internal", seedUsername, seedPassword)
        val realm = keycloak.realm("platform-internal")
        val current = realm.users().get(user.id).roles().realmLevel().listAll().mapNotNull { it.name }.toSet()
        val permissions = listOf(
            "admin", "super_admin", "platform.admin", "platform.super_admin",
            "audit.admin",
        )
        val missing = realm.roles().list().filter { it.name in permissions && it.name !in current }
        if (missing.isNotEmpty()) realm.users().get(user.id).roles().realmLevel().add(missing)
    }

    private fun ensureUser(keycloak: Keycloak, realmName: String, username: String, password: String): UserRepresentation {
        val realm = keycloak.realm(realmName)
        val user = realm.users().searchByUsername(username, true).firstOrNull() ?: run {
            realm.users().create(
                UserRepresentation().apply {
                    this.username = username
                    email = username
                    isEnabled = true
                    isEmailVerified = true
                },
            )
            realm.users().searchByUsername(username, true).firstOrNull() ?: error("User creation failed")
        }
        realm.users().get(user.id).resetPassword(
            CredentialRepresentation().apply {
                type = CredentialRepresentation.PASSWORD
                value = password
                isTemporary = true
            },
        )
        return user
    }

    private fun clientId(keycloak: Keycloak, realm: String, client: String): String? =
        keycloak.realm(realm).clients().findByClientId(client).firstOrNull()?.id ?: null

    private fun permissionPrefix(service: String) = service.removeSuffix("-service")

    private data class ChannelClient(val realm: String, val clientId: String, val publicClient: Boolean)

    // Mirror of identity-service's realm set; audit-service shares the platform Keycloak.
    private val realms = mapOf(
        "platform-customer" to listOf("customer", "customer.suspended"),
        "platform-driver" to listOf("driver", "driver.pending_review", "driver.suspended", "driver.admin"),
        "platform-courier" to listOf("courier", "courier.pending_review", "courier.suspended", "courier.admin"),
        "platform-staff" to listOf("restaurant_staff", "restaurant_manager", "merchant_staff", "merchant_manager"),
        "platform-internal" to listOf(
            "support_agent_l1", "support_agent_l2", "support_agent_l3",
            "operations", "finance", "fraud_reviewer",
            "admin", "super_admin", "platform.admin", "platform.super_admin",
            "identity.admin", "audit.admin",
        ),
        "platform-services" to emptyList<String>(),
    )

    private val channelClients = listOf(
        ChannelClient("platform-customer", "web-customer", true),
        ChannelClient("platform-customer", "mobile-customer", true),
        ChannelClient("platform-driver", "web-driver", true),
        ChannelClient("platform-driver", "mobile-driver", true),
        ChannelClient("platform-courier", "web-courier", true),
        ChannelClient("platform-courier", "mobile-courier", true),
        ChannelClient("platform-staff", "web-restaurant", false),
        ChannelClient("platform-staff", "web-merchant", false),
        ChannelClient("platform-internal", "web-support", false),
        ChannelClient("platform-internal", "web-admin", false),
    )

    private val services = listOf(
        "identity-service", "api-gateway", "audit-service", "admin-service",
        "configuration-service", "notification-service", "reporting-service",
        "fraud-risk-service", "customer-service", "search-service",
        "driver-service", "trip-service", "pricing-service", "restaurant-service",
        "food-order-service", "courier-service", "geolocation-service",
        "payment-service", "ledger-service", "chat-service", "file-service",
    )
}
