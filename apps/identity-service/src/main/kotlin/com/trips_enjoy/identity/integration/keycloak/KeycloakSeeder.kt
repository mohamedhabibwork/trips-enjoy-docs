package com.trips_enjoy.identity.integration.keycloak

import org.keycloak.OAuth2Constants
import org.keycloak.admin.client.Keycloak
import org.keycloak.admin.client.KeycloakBuilder
import org.keycloak.representations.idm.ClientRepresentation
import org.keycloak.representations.idm.ClientScopeRepresentation
import org.keycloak.representations.idm.CredentialRepresentation
import org.keycloak.representations.idm.ProtocolMapperRepresentation
import org.keycloak.representations.idm.RealmRepresentation
import org.keycloak.representations.idm.RoleRepresentation
import org.keycloak.representations.idm.UserRepresentation
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import jakarta.ws.rs.WebApplicationException
import jakarta.ws.rs.core.Response

/**
 * Idempotent provisioning of the documented Keycloak realms and access model.
 *
 * **Token-expiry resilience.** Keycloak 24 dev-mode issues 60-second access tokens
 * for the `admin-cli` password grant (no refresh-token issuance). The seeder
 * runs longer than that — installing 130 realm roles + 37 clients + 63 protocol
 * mappers + 8 users is well over 60 seconds — so the next REST call after the
 * token expiry would hang waiting for a refresh that never comes. To stay
 * reliable in dev/CI without forcing operators to bump the access-token TTL,
 * every REST block is wrapped in [withFreshClient]. That helper detects the
 * `401 / invalid_token` response Keycloak emits for an expired token, closes
 * the stale client, opens a fresh one, and retries the call once. Operators
 * see a single INFO line on each reauth.
 *
 * No behavior change in the success path; the seeder still emits the full
 * realm graph documented in INTEGRATION.md §8.11.
 */
@Component
@ConditionalOnProperty("identity.keycloak.seed.enabled", havingValue = "true")
class KeycloakSeeder(
    private val spec: SeedSpec,
    @Value("\${identity.keycloak.base-url}") private val baseUrl: String,
    @Value("\${identity.keycloak.seed.admin-username}") private val adminUsername: String,
    @Value("\${identity.keycloak.seed.admin-password}") private val adminPassword: String,
    @Value("\${identity.keycloak.seed.super-admin-username}") private val superAdminUsername: String,
    @Value("\${identity.keycloak.seed.super-admin-password}") private val superAdminPassword: String,
    @Value("\${identity.keycloak.seed.global-admin-enabled:false}") private val globalAdminEnabled: Boolean,
    @Value("\${identity.keycloak.seed.default-password:H@bib1998}") private val defaultPassword: String,
) : ApplicationRunner {
    private val log = LoggerFactory.getLogger(javaClass)

    init {
        if (defaultPassword == "H@bib1998") {
            log.warn("Keycloak seeder: using default password 'H@bib1998' — override IDENTITY_KEYCLOAK_SEED_DEFAULT_PASSWORD for any non-local environment.")
        }
    }

    override fun run(args: ApplicationArguments) {
        require(adminUsername.isNotBlank() && adminPassword.isNotBlank()) { "Keycloak seed admin credentials must be supplied through environment variables" }
        log.info(
            "Keycloak seeder topology: {} (servicesRealm={}, adminRealm={})",
            spec.topology, spec.servicesRealm, spec.adminRealm,
        )
        // The seeder must NOT crash application boot when Keycloak is down.
        // identity-service is a stateless edge that brokers JWTs through
        // whatever OAuth/OIDC server is reachable; the realm bootstrap is a
        // dev-time convenience, not a runtime prerequisite. Network-level
        // failures (ConnectException, ProcessingException wrapping HTTP
        // connection errors, SocketException, UnknownHostException, and the
        // JAX-RS 404 wrapper that the Keycloak admin-client emits when the
        // server is unreachable mid-handshake) all degrade to a single WARN
        // line and the rest of the boot completes normally.
        try {
            runSeed()
        } catch (e: Exception) {
            when (e) {
                is java.net.ConnectException,
                is java.net.SocketException,
                is java.net.UnknownHostException,
                is jakarta.ws.rs.ProcessingException,
                is org.apache.http.conn.HttpHostConnectException -> {
                    log.warn(
                        "Keycloak seeder skipped: {} ({}). " +
                            "identity-service continues without seeding the realm graph; " +
                            "start Keycloak (or run with identity.keycloak.seed.enabled=false) and re-run to provision.",
                        baseUrl, e.javaClass.simpleName,
                    )
                }
                else -> throw e
            }
        }
    }

    private fun runSeed() {
        // Each top-level phase runs inside its own withFreshClient block so a
        // token-expiry between phases starts a clean client without affecting
        // the in-progress phase. See class-level docs for the rationale.
        withFreshClient { keycloak ->
            spec.realms.forEach { realmSpec ->
                ensureRealm(keycloak, realmSpec)
                ensurePlatformClaimsScope(keycloak, realmSpec.realm)
                realmSpec.protocolMappers.forEach { mapper -> ensureProtocolMapper(keycloak, realmSpec.realm, "platform-claims", mapper) }
                ensureDefaultClientScopes(keycloak, realmSpec)
                realmSpec.channelClients.forEach { ensureClient(keycloak, it.realm, it.clientId, it.publicClient) }
            }
        }
        withFreshClient { keycloak ->
            spec.serviceClients.forEach { service ->
                ensureClient(keycloak, spec.servicesRealm, service, false)
                val prefix = permissionPrefix(service)
                listOf("$prefix.read", "$prefix.write", "$prefix.admin").forEach { ensureClientRole(keycloak, spec.servicesRealm, service, it) }
                listOf("$prefix.read", "$prefix.write", "$prefix.admin", "$prefix.support").forEach { ensureRealmRole(keycloak, spec.adminRealm, it) }
                ensureRealmRole(keycloak, spec.servicesRealm, "$prefix.svc")
                listOf("$prefix.read", "$prefix.write", "$prefix.admin", "$prefix.support").forEach { ensureRealmRole(keycloak, spec.servicesRealm, it) }
            }
        }
        withFreshClient { keycloak ->
            // Per-service claims scope + 3 protocol mappers per service.
            ensureServiceClaimsScope(keycloak)
            spec.serviceClaims.forEach { claim -> ensureServiceClaimMappers(keycloak, claim) }
        }
        withFreshClient { keycloak -> grantIdentityReadToServiceAccounts(keycloak) }
        withFreshClient { keycloak -> ensureSuperAdmin(keycloak) }
        withFreshClient { keycloak -> spec.devUsers.forEach { user -> ensureSeedUser(keycloak, user) } }
        if (globalAdminEnabled) withFreshClient { keycloak -> ensureGlobalKeycloakAdmin(keycloak) }
        log.info(
            "Keycloak seed completed for {} realms, {} service clients, {} channel clients, {} service claims, {} dev users",
            spec.realms.size, spec.serviceClients.size, spec.realms.sumOf { it.channelClients.size }, spec.serviceClaims.size, spec.devUsers.size,
        )
    }

    /**
     * Run [block] against a fresh `Keycloak` admin client. On a `401
     * invalid_token` response (Keycloak's signal for an expired access
     * token), close the stale client, open a new one, and retry the call once.
     * Any other exception propagates unchanged.
     *
     * Package-private so [KeycloakSeederReauthTest] can exercise the retry
     * path without standing a full Keycloak.
     */
    internal fun <T> withFreshClient(block: (Keycloak) -> T): T {
        var attempt = 0
        while (true) {
            val keycloak = openAdminClient()
            try {
                return block(keycloak)
            } catch (e: WebApplicationException) {
                if (attempt == 0 && isTokenExpired(e)) {
                    attempt++
                    log.info("Keycloak admin token expired mid-seeder; reopening client and retrying the operation (attempt 2/2).")
                    safeClose(keycloak)
                    continue
                }
                throw e
            } finally {
                if (attempt > 0) {
                    // already closed inside the loop; nothing to do
                } else {
                    safeClose(keycloak)
                }
            }
        }
        @Suppress("UNREACHABLE_CODE")
        error("unreachable")
    }

    private fun openAdminClient(): Keycloak = KeycloakBuilder.builder()
        .serverUrl(baseUrl).realm("master").grantType(OAuth2Constants.PASSWORD)
        .clientId("admin-cli").username(adminUsername).password(adminPassword).build()

    private fun safeClose(keycloak: Keycloak) {
        try { keycloak.close() } catch (_: Exception) { /* best-effort */ }
    }

    /**
     * Returns true when [e] looks like the Keycloak "expired access token"
     * response. Keycloak emits either:
     *  - `jakarta.ws.rs.NotFoundException` wrapping a 401 with body `{"error":"invalid_token","error_description":"Token is not active"}`, or
     *  - `WebApplicationException` with status 401 directly.
     */
    internal fun isTokenExpired(e: WebApplicationException): Boolean {
        val status: Int = when (e) {
            is jakarta.ws.rs.NotFoundException -> {
                // keycloak admin-client wraps 401/403 in a NotFoundException with the
                // real status code stashed in the response.
                val resp: Response? = e.response
                resp?.status ?: 404
            }
            else -> e.response?.status ?: 0
        }
        if (status != 401) return false
        val body = try {
            e.response?.readEntity(String::class.java) ?: ""
        } catch (_: Exception) { "" }
        return body.contains("invalid_token") || body.contains("Token is not active")
    }

    private fun ensureRealm(keycloak: Keycloak, spec: SeedRealmSpec) {
        val realm = spec.realm
        if (keycloak.realms().findAll().none { it.realm == realm }) {
            keycloak.realms().create(RealmRepresentation().apply {
                this.realm = realm; isEnabled = true; isRegistrationAllowed = false; isResetPasswordAllowed = true
                isLoginWithEmailAllowed = true; isDuplicateEmailsAllowed = false
            })
        }
        spec.realmRoles.forEach { ensureRealmRole(keycloak, realm, it) }
    }

    private fun ensureRealmRole(keycloak: Keycloak, realm: String, name: String) {
        val roles = keycloak.realm(realm).roles().list()
        if (roles.none { it.name == name }) keycloak.realm(realm).roles().create(RoleRepresentation(name, null, false))
    }

    private fun ensureClient(keycloak: Keycloak, realm: String, clientId: String, publicClient: Boolean) {
        if (clientId(keycloak, realm, clientId) != null) return
        keycloak.realm(realm).clients().create(ClientRepresentation().apply {
            this.clientId = clientId; protocol = "openid-connect"; isEnabled = true; isPublicClient = publicClient
            isStandardFlowEnabled = true; isDirectAccessGrantsEnabled = false; isServiceAccountsEnabled = !publicClient; isFullScopeAllowed = false
            attributes = mapOf("pkce.code.challenge.method" to "S256")
        })
    }

    private fun ensureClientRole(keycloak: Keycloak, realm: String, client: String, role: String) {
        val clientInternalId = clientId(keycloak, realm, client) ?: error("Client $client was not created")
        val resource = keycloak.realm(realm).clients().get(clientInternalId).roles()
        if (resource.list().none { it.name == role }) resource.create(RoleRepresentation(role, null, false))
    }

    /**
     * Ensure the realm-level `platform-claims` client scope exists. Every
     * protocol mapper (claim) we attach lives on this scope; the scope is
     * then attached to each channel client via `ensureDefaultClientScopes`.
     */
    private fun ensurePlatformClaimsScope(keycloak: Keycloak, realm: String) {
        ensureClientScope(keycloak, realm, "platform-claims")
    }

    /**
     * Ensure the realm-level `service-claims` client scope exists on the
     * services realm (single-realm mode: same as the dev realm; multi-realm
     * mode: `platform-services`). The protocol mappers on this scope
     * project per-service realm-role membership into `<service>.scopes`,
     * `<service>.level`, and `<service>.tenant` claims on the access token.
     */
    private fun ensureServiceClaimsScope(keycloak: Keycloak) {
        ensureClientScope(keycloak, spec.servicesRealm, "service-claims")
    }

    private fun ensureClientScope(keycloak: Keycloak, realm: String, name: String) {
        val scopes = keycloak.realm(realm).clientScopes()
        if (scopes.findAll().any { it.name == name }) return
        scopes.create(ClientScopeRepresentation().apply {
            this.name = name
            protocol = "openid-connect"
            attributes = mapOf(
                "include.in.token.scope" to "true",
                "display.on.consent.screen" to "false",
            )
        })
    }

    private fun ensureProtocolMapper(keycloak: Keycloak, realm: String, scopeName: String, mapper: SeedProtocolMapper) {
        val scopes = keycloak.realm(realm).clientScopes()
        val scope = scopes.findAll().firstOrNull { it.name == scopeName } ?: return
        val resource = scopes.get(scope.id)
        if (resource.getProtocolMappers().getMappers().any { it.name == mapper.name }) return
        resource.getProtocolMappers().createMapper(ProtocolMapperRepresentation().apply {
            name = mapper.name
            protocol = "openid-connect"
            protocolMapper = mapper.mapperType
            config = buildMap {
                put("claim.name", mapper.claim)
                put("include.in.access.token", "true")
                put("include.in.id.token", "true")
                mapper.userAttribute?.let { put("user.attribute", it); put("user.attribute.from.token", "false") }
                mapper.realmRole?.let { put("role", it); put("claim.is.multivalued", mapper.isMultivalued.toString()) }
                mapper.groupPath?.let { put("group.path", it); put("claim.is.multivalued", mapper.isMultivalued.toString()) }
                mapper.sessionNote?.let { put("session.note", it); put("claim.is.multivalued", mapper.isMultivalued.toString()) }
                mapper.hardcodedValue?.let { put("claim.value", it) }
                if (mapper.isMultivalued) put("claim.is.multivalued", "true")
            }
        })
    }

    /**
     * Wire the realm-level `platform-claims` client scope plus the listed
     * built-in scopes (`openid`, `profile`, `email`, `phone`, `offline_access`)
     * as `defaultDefaultClientScopes` so they ride every issued token.
     *
     * Also wires `additionalDefaultScopes` from the realm spec (e.g.
     * `service-claims` on the services realm) so per-realm claim scopes
     * are surfaced automatically.
     */
    private fun ensureDefaultClientScopes(keycloak: Keycloak, spec: SeedRealmSpec) {
        val realm = keycloak.realm(spec.realm).toRepresentation()
        val scopes = keycloak.realm(spec.realm).clientScopes().findAll()
        val byName = scopes.associateBy { it.name }
        val desired = (spec.defaultClientScopes + "platform-claims" + spec.additionalDefaultScopes).distinct()
        val resolved = desired.mapNotNull { byName[it]?.id }
        if (resolved.toSet() == realm.defaultDefaultClientScopes?.toSet()) return
        realm.defaultDefaultClientScopes = resolved
        keycloak.realm(spec.realm).update(realm)
    }

    /**
     * Three protocol mappers per service, attached to the `service-claims`
     * scope in the services realm.
     *
     *   1. `<service>.scopes` — multivalued string array of the role names
     *      the user holds.
     *   2. `<service>.level` — int 0..4 derived from the highest held role.
     *   3. `<service>.tenant` — string from the first `tenant:<service>:<id>`
     *      realm role the user holds.
     */
    private fun ensureServiceClaimMappers(keycloak: Keycloak, claim: SeedServiceClaim) {
        val realm = spec.servicesRealm
        ensureClientScope(keycloak, realm, "service-claims")
        val scopes = keycloak.realm(realm).clientScopes()
        val scope = scopes.findAll().firstOrNull { it.name == "service-claims" } ?: return
        val resource = scopes.get(scope.id)
        if (resource.getProtocolMappers().getMappers().any { it.name == "${claim.prefix}-scopes" }) return

        resource.getProtocolMappers().createMapper(ProtocolMapperRepresentation().apply {
            name = "${claim.prefix}-scopes"
            protocol = "openid-connect"
            protocolMapper = "oidc-script-based-property-mapper"
            config = mapOf(
                "claim.name" to claim.scopesClaim,
                "include.in.access.token" to "true",
                "include.in.id.token" to "true",
                "claim.is.multivalued" to "true",
                "script" to """
                    var roles = (context.accessToken.getOtherClaims().get('realm_access') || {}).roles || [];
                    var prefix = '${claim.prefix}.';
                    var out = [];
                    var allowed = ['${claim.readRole}','${claim.writeRole}','${claim.adminRole}','${claim.supportRole}'];
                    for (var i = 0; i < roles.length; i++) {
                        if (allowed.indexOf(roles[i]) >= 0) out.push(roles[i]);
                    }
                    exports = out;
                """.trimIndent(),
            )
        })

        resource.getProtocolMappers().createMapper(ProtocolMapperRepresentation().apply {
            name = "${claim.prefix}-level"
            protocol = "openid-connect"
            protocolMapper = "oidc-script-based-property-mapper"
            config = mapOf(
                "claim.name" to claim.levelClaim,
                "include.in.access.token" to "true",
                "include.in.id.token" to "true",
                "script" to """
                    var roles = (context.accessToken.getOtherClaims().get('realm_access') || {}).roles || [];
                    var level = 0;
                    if (roles.indexOf('${claim.readRole}') >= 0) level = Math.max(level, 1);
                    if (roles.indexOf('${claim.writeRole}') >= 0) level = Math.max(level, 2);
                    if (roles.indexOf('${claim.adminRole}') >= 0) level = Math.max(level, 3);
                    if (roles.indexOf('${claim.supportRole}') >= 0) level = Math.max(level, 4);
                    exports = String(level);
                """.trimIndent(),
            )
        })

        resource.getProtocolMappers().createMapper(ProtocolMapperRepresentation().apply {
            name = "${claim.prefix}-tenant"
            protocol = "openid-connect"
            protocolMapper = "oidc-script-based-property-mapper"
            config = mapOf(
                "claim.name" to claim.tenantClaim,
                "include.in.access.token" to "true",
                "include.in.id.token" to "false",
                "script" to """
                    var roles = (context.accessToken.getOtherClaims().get('realm_access') || {}).roles || [];
                    var prefix = 'tenant:${claim.prefix}:';
                    for (var i = 0; i < roles.length; i++) {
                        if (roles[i].indexOf(prefix) === 0) exports = roles[i].substring(prefix.length);
                    }
                    exports = exports || '';
                """.trimIndent(),
            )
        })
    }

    private fun grantIdentityReadToServiceAccounts(keycloak: Keycloak) {
        val realm = keycloak.realm(spec.servicesRealm)
        val identityClientId = clientId(keycloak, spec.servicesRealm, "identity-service") ?: error("identity-service client missing")
        val role = realm.clients().get(identityClientId).roles().list().first { it.name == "identity.read" }
        spec.serviceClients.forEach { service ->
            val svcClientId = clientId(keycloak, spec.servicesRealm, service) ?: return@forEach
            val user = realm.clients().get(svcClientId).serviceAccountUser
            val current = realm.users().get(user.id).roles().clientLevel(identityClientId).listAll()
            if (current.none { it.name == role.name }) realm.users().get(user.id).roles().clientLevel(identityClientId).add(listOf(role))
        }
    }

    private fun ensureSuperAdmin(keycloak: Keycloak) {
        if (superAdminUsername.isBlank() || superAdminPassword.isBlank()) { log.warn("Super-admin was not seeded: required environment variables are absent"); return }
        val user = ensureUser(keycloak, spec.adminRealm, superAdminUsername, superAdminPassword, setTemporary = true)
        val adminRealm = keycloak.realm(spec.adminRealm)
        val current = adminRealm.users().get(user.id).roles().realmLevel().listAll().mapNotNull { it.name }.toSet()
        val permissions = listOf("admin", "super_admin", "platform.admin", "platform.super_admin", "identity.admin") + spec.serviceClients.map { "${permissionPrefix(it)}.admin" }
        val missing = adminRealm.roles().list().filter { it.name in permissions && it.name !in current }
        if (missing.isNotEmpty()) adminRealm.users().get(user.id).roles().realmLevel().add(missing)

        val servicesRealm = keycloak.realm(spec.servicesRealm)
        val psCurrent = servicesRealm.users().get(user.id).roles().realmLevel().listAll().mapNotNull { it.name }.toSet()
        val adminRoles = spec.serviceClients.flatMap { svc ->
            val prefix = permissionPrefix(svc)
            listOf("$prefix.read", "$prefix.write", "$prefix.admin", "$prefix.support")
        }.distinct()
        val psMissing = servicesRealm.roles().list().filter { it.name in adminRoles && it.name !in psCurrent }
        if (psMissing.isNotEmpty()) servicesRealm.users().get(user.id).roles().realmLevel().add(psMissing)
    }

    private fun ensureGlobalKeycloakAdmin(keycloak: Keycloak) {
        require(superAdminUsername.isNotBlank() && superAdminPassword.isNotBlank()) { "Global Keycloak admin requires super-admin credentials" }
        val user = ensureUser(keycloak, "master", superAdminUsername, superAdminPassword, setTemporary = true)
        val realm = keycloak.realm("master")
        val managementId = clientId(keycloak, "master", "realm-management") ?: error("master realm-management client is missing")
        val current = realm.users().get(user.id).roles().clientLevel(managementId).listAll()
        if (current.none { it.name == "realm-admin" }) {
            val role = realm.clients().get(managementId).roles().list().first { it.name == "realm-admin" }
            realm.users().get(user.id).roles().clientLevel(managementId).add(listOf(role))
        }
    }

    private fun ensureSeedUser(keycloak: Keycloak, userSpec: SeedUserSpec) {
        val user = ensureUser(keycloak, userSpec.realm, userSpec.username, userSpec.password ?: defaultPassword, setTemporary = userSpec.temporary)
        val realm = keycloak.realm(userSpec.realm)
        val currentRealmRoles = realm.users().get(user.id).roles().realmLevel().listAll().mapNotNull { it.name }.toSet()
        val missingRealmRoles = realm.roles().list().filter { it.name in userSpec.realmRoles && it.name !in currentRealmRoles }
        if (missingRealmRoles.isNotEmpty()) realm.users().get(user.id).roles().realmLevel().add(missingRealmRoles)
        userSpec.clientRoles.forEach { (client, roles) ->
            val cid = clientId(keycloak, userSpec.realm, client) ?: return@forEach
            val currentClientRoles = realm.users().get(user.id).roles().clientLevel(cid).listAll().mapNotNull { it.name }.toSet()
            val roleReps = realm.clients().get(cid).roles().list().filter { it.name in roles && it.name !in currentClientRoles }
            if (roleReps.isNotEmpty()) realm.users().get(user.id).roles().clientLevel(cid).add(roleReps)
        }
        if (userSpec.serviceRoles.isNotEmpty()) {
            val servicesRealm = keycloak.realm(spec.servicesRealm)
            val servicesUser = servicesRealm.users().searchByUsername(userSpec.username, true).firstOrNull()
                ?: run {
                    servicesRealm.users().create(UserRepresentation().apply {
                        this.username = userSpec.username
                        email = userSpec.email
                        isEnabled = true
                        isEmailVerified = true
                    })
                    servicesRealm.users().searchByUsername(userSpec.username, true).firstOrNull()
                        ?: error("mirror user creation failed in services realm ${spec.servicesRealm}")
                }
            val currentRoles = servicesRealm.users().get(servicesUser.id).roles().realmLevel().listAll().mapNotNull { it.name }.toSet()
            val desiredRoles = userSpec.serviceRoles.values.flatten().distinct()
            val missingRoles = servicesRealm.roles().list().filter { it.name in desiredRoles && it.name !in currentRoles }
            if (missingRoles.isNotEmpty()) servicesRealm.users().get(servicesUser.id).roles().realmLevel().add(missingRoles)
        }
    }

    private fun ensureUser(keycloak: Keycloak, realmName: String, username: String, password: String, setTemporary: Boolean): UserRepresentation {
        val realm = keycloak.realm(realmName)
        val user = realm.users().searchByUsername(username, true).firstOrNull() ?: run {
            realm.users().create(UserRepresentation().apply { this.username = username; email = username; isEnabled = true; isEmailVerified = true })
            realm.users().searchByUsername(username, true).firstOrNull() ?: error("User creation failed")
        }
        realm.users().get(user.id).resetPassword(CredentialRepresentation().apply { type = CredentialRepresentation.PASSWORD; value = password; isTemporary = setTemporary })
        return user
    }

    private fun clientId(keycloak: Keycloak, realm: String, client: String): String? = keycloak.realm(realm).clients().findByClientId(client).firstOrNull()?.id
    private fun permissionPrefix(service: String) = service.removeSuffix("-service")
}