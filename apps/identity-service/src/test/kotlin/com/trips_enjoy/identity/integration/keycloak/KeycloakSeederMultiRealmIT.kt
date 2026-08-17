package com.trips_enjoy.identity.integration.keycloak

import com.trips_enjoy.platform.test.BaseIntegrationTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
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
 * End-to-end test for `KeycloakSeeder` in `multi-realm` mode (the
 * documented 6-realm topology: `platform-customer`, `platform-driver`,
 * `platform-courier`, `platform-staff`, `platform-internal`,
 * `platform-services`). Boots the application against a real Keycloak
 * Testcontainer and asserts the seeder produces the documented realm
 * graph.
 *
 * Gated on `RUN_KEYCLOAK_IT=true` because Testcontainers Keycloak takes
 * ~30s for first-boot and we don't want to pay that on every dev run.
 * CI sets the env var; local runs that need to exercise the seeder end
 * to end can opt in via `RUN_KEYCLOAK_IT=true ./gradlew test`.
 */
@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = ["identity.keycloak.topology=multi-realm"])
@EnabledIfEnvironmentVariable(named = "RUN_KEYCLOAK_IT", matches = "true")
class KeycloakSeederMultiRealmIT : BaseIntegrationTest() {

    private fun adminClient(): org.keycloak.admin.client.Keycloak = KeycloakBuilder.builder()
        .serverUrl(KeycloakTestSupport.url())
        .realm("master").grantType(OAuth2Constants.PASSWORD)
        .clientId("admin-cli").username("admin").password("admin")
        .build()

    @Test
    fun `seeder creates all 6 platform realms with the canonical role taxonomy`() {
        adminClient().use { keycloak ->
            val realms = keycloak.realms().findAll().mapNotNull { it.realm }.toSet()
            val expected = setOf(
                "platform-customer", "platform-driver", "platform-courier",
                "platform-staff", "platform-internal", "platform-services",
            )
            assertTrue(expected.all { it in realms }, "missing realms: ${expected - realms}")
            val driverRoles = keycloak.realm("platform-driver").roles().list().mapNotNull { it.name }.toSet()
            assertTrue("driver" in driverRoles)
            assertTrue("driver.admin" in driverRoles)
        }
    }

    @Test
    fun `seeder creates service clients with per-service roles`() {
        adminClient().use { keycloak ->
            val realm = keycloak.realm("platform-services")
            val services = listOf("identity-service", "audit-service", "admin-service", "payment-service", "ledger-service")
            services.forEach { svc ->
                val client = realm.clients().findByClientId(svc).firstOrNull()
                assertNotNull(client, "service client $svc missing")
                val clientId = client!!.id
                val roleNames = realm.clients().get(clientId).roles().list().mapNotNull { it.name }.toSet()
                val prefix = svc.removeSuffix("-service")
                assertTrue("$prefix.read" in roleNames, "missing $prefix.read on $svc")
                assertTrue("$prefix.write" in roleNames, "missing $prefix.write on $svc")
                assertTrue("$prefix.admin" in roleNames, "missing $prefix.admin on $svc")
            }
        }
    }

    @Test
    fun `seeder creates channel clients (web and mobile) per realm`() {
        adminClient().use { keycloak ->
            val realm = keycloak.realm("platform-customer")
            val clients = realm.clients().findAll().mapNotNull { it.clientId }.toSet()
            assertTrue("web-customer" in clients)
            assertTrue("mobile-customer" in clients)
            val web = realm.clients().findByClientId("web-customer").first()
            assertTrue(web.isPublicClient, "web-customer should be public")
        }
    }

    @Test
    fun `seeder creates the platform-claims scope on every realm`() {
        adminClient().use { keycloak ->
            listOf("platform-customer", "platform-driver", "platform-internal", "platform-services").forEach { realmName ->
                val scopes = keycloak.realm(realmName).clientScopes().findAll().mapNotNull { it.name }
                assertTrue("platform-claims" in scopes, "platform-claims scope missing on $realmName")
            }
        }
    }

    @Test
    fun `seeder grants identity read to every service account`() {
        adminClient().use { keycloak ->
            val realm = keycloak.realm("platform-services")
            val identityClientId = realm.clients().findByClientId("identity-service").first().id
            val auditClientId = realm.clients().findByClientId("audit-service").first().id
            val auditUser = realm.clients().get(auditClientId).serviceAccountUser
            val auditRoles = realm.users().get(auditUser.id).roles().clientLevel(identityClientId).listAll().mapNotNull { it.name }.toSet()
            assertTrue("identity.read" in auditRoles, "audit-service should hold identity.read; had $auditRoles")
        }
    }

    @Test
    fun `seeder creates the super-admin user with the canonical 21-entry preset`() {
        adminClient().use { keycloak ->
            val realm = keycloak.realm("platform-internal")
            val user = realm.users().searchByUsername("admin@inovoria.com", true).firstOrNull()
            assertNotNull(user, "super-admin user missing")
            val userId = user!!.id
            val roles = realm.users().get(userId).roles().realmLevel().listAll().mapNotNull { it.name }.toSet()
            assertTrue("super_admin" in roles)
            assertTrue("platform.super_admin" in roles)
            assertTrue("identity.admin" in roles)
            assertTrue("audit.admin" in roles)
            assertTrue("payment.admin" in roles)
            val adminScopeCount = roles.count { it.endsWith(".admin") || it == "platform.super_admin" }
            assertEquals(21, adminScopeCount, "expected 21-entry preset, had $adminScopeCount")
        }
    }

    @Test
    fun `seeder creates per-realm dev users with the expected roles`() {
        adminClient().use { keycloak ->
            data class Expected(val realm: String, val username: String, val role: String)
            val expectations = listOf(
                Expected("platform-customer", "customer@trips-enjoy.com", "customer"),
                Expected("platform-driver", "driver@trips-enjoy.com", "driver"),
                Expected("platform-courier", "courier@trips-enjoy.com", "courier"),
                Expected("platform-staff", "restaurant-staff@trips-enjoy.com", "restaurant_staff"),
                Expected("platform-staff", "merchant-staff@trips-enjoy.com", "merchant_staff"),
                Expected("platform-internal", "support@trips-enjoy.com", "support_agent_l1"),
                Expected("platform-internal", "finance@trips-enjoy.com", "finance"),
            )
            expectations.forEach { (realmName, username, role) ->
                val realm = keycloak.realm(realmName)
                val user = realm.users().searchByUsername(username, true).firstOrNull()
                assertNotNull(user, "user $username missing in $realmName")
                val userId = user!!.id
                val roles = realm.users().get(userId).roles().realmLevel().listAll().mapNotNull { it.name }.toSet()
                assertTrue(role in roles, "$username in $realmName should have $role; had $roles")
            }
        }
    }

    @Test
    fun `seeder promotes per-service client roles to realm roles in platform-services`() {
        adminClient().use { keycloak ->
            val realm = keycloak.realm("platform-services")
            val sample = listOf("trip-service", "payment-service", "driver-service", "identity-service")
            sample.forEach { svc ->
                val prefix = svc.removeSuffix("-service")
                listOf("$prefix.read", "$prefix.write", "$prefix.admin", "$prefix.support").forEach { role ->
                    val present = realm.roles().list().any { it.name == role }
                    assertTrue(present, "realm role $role missing in platform-services")
                }
            }
        }
    }

    @Test
    fun `seeder creates service-claims scope with three protocol mappers per service`() {
        adminClient().use { keycloak ->
            val realm = keycloak.realm("platform-services")
            val scopes = realm.clientScopes().findAll()
            val svc = scopes.firstOrNull { it.name == "service-claims" }
            assertNotNull(svc, "service-claims scope missing on platform-services")
            val mappers = realm.clientScopes().get(svc!!.id).getProtocolMappers().getMappers().map { it.name }
            // 3 mappers per service × 21 services = 63 total.
            assertEquals(63, mappers.size, "expected 63 protocol mappers, had ${mappers.size}")
            // Spot-check that all three mapper shapes exist for trip-service.
            assertTrue("trip-scopes" in mappers, "missing trip-scopes mapper")
            assertTrue("trip-level" in mappers, "missing trip-level mapper")
            assertTrue("trip-tenant" in mappers, "missing trip-tenant mapper")
            // Mapper types should be JavaScript-based so we can project realm-role
            // membership into a multivalued array / int.
            val scopesMapper = realm.clientScopes().get(svc.id).getProtocolMappers().getMappers().first { it.name == "trip-scopes" }
            assertEquals("oidc-script-based-property-mapper", scopesMapper.protocolMapper)
            assertTrue(scopesMapper.config["include.in.access.token"] == "true")
            assertTrue(scopesMapper.config["claim.is.multivalued"] == "true")
        }
    }

    @Test
    fun `seeder grants per-service roles to dev-user mirror in platform-services`() {
        adminClient().use { keycloak ->
            val realm = keycloak.realm("platform-services")
            // driver@trips-enjoy.com is seeded with driver-service.read/.write
            // + trip.read + geolocation-service.read/.write per SeedCatalog.
            val user = realm.users().searchByUsername("driver@trips-enjoy.com", true).firstOrNull()
            assertNotNull(user, "driver mirror user missing in platform-services")
            val userId = user!!.id
            val roles = realm.users().get(userId).roles().realmLevel().listAll().mapNotNull { it.name }.toSet()
            assertTrue("driver.read" in roles, "driver should have driver.read; had $roles")
            assertTrue("driver.write" in roles)
            assertTrue("trip.read" in roles)
            assertTrue("geolocation.read" in roles)
            assertTrue("geolocation.write" in roles)
            // courier@trips-enjoy.com should have courier.read/.write too
            val courier = realm.users().searchByUsername("courier@trips-enjoy.com", true).firstOrNull()
            assertNotNull(courier, "courier mirror user missing in platform-services")
            val courierRoles = realm.users().get(courier!!.id).roles().realmLevel().listAll().mapNotNull { it.name }.toSet()
            assertTrue("courier.read" in courierRoles)
            assertTrue("courier.write" in courierRoles)
        }
    }

    @Test
    fun `seeder grants all 21 per-service admin roles to super-admin in platform-services`() {
        adminClient().use { keycloak ->
            val realm = keycloak.realm("platform-services")
            val user = realm.users().searchByUsername("admin@inovoria.com", true).firstOrNull()
            assertNotNull(user, "super-admin mirror user missing in platform-services")
            val userId = user!!.id
            val roles = realm.users().get(userId).roles().realmLevel().listAll().mapNotNull { it.name }.toSet()
            val services = listOf(
                "identity-service", "api-gateway", "audit-service", "admin-service",
                "configuration-service", "notification-service", "reporting-service",
                "fraud-risk-service", "customer-service", "search-service",
                "driver-service", "trip-service", "pricing-service", "restaurant-service",
                "food-order-service", "courier-service", "geolocation-service",
                "payment-service", "ledger-service", "chat-service", "file-service",
            )
            services.forEach { svc ->
                val prefix = svc.removeSuffix("-service")
                assertTrue("$prefix.admin" in roles, "super-admin should have $prefix.admin; had $roles")
            }
        }
    }

    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun registerKeycloak(reg: DynamicPropertyRegistry) = KeycloakTestSupport.register(reg)
    }
}