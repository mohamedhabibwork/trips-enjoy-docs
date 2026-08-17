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
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.context.TestPropertySource

/**
 * End-to-end test for `KeycloakSeeder` in `single-realm` mode (the
 * dev / CI default — one realm `platform-dev` containing every client,
 * role, mapper, and user). Boots the application against a real
 * Keycloak Testcontainer.
 *
 * Gated on `RUN_KEYCLOAK_IT=true` because Testcontainers Keycloak
 * takes ~30s for first-boot and we don't want to pay that on every
 * dev run. CI sets the env var.
 */
@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = [
    "identity.keycloak.topology=single-realm",
    "identity.keycloak.dev-realm-name=platform-dev",
])
@EnabledIfEnvironmentVariable(named = "RUN_KEYCLOAK_IT", matches = "true")
class KeycloakSeederSingleRealmIT : BaseIntegrationTest() {

    private fun adminClient(): org.keycloak.admin.client.Keycloak = KeycloakBuilder.builder()
        .serverUrl(KeycloakTestSupport.url())
        .realm("master").grantType(OAuth2Constants.PASSWORD)
        .clientId("admin-cli").username("admin").password("admin")
        .build()

    @Test
    fun `seeder creates exactly one platform-dev realm with every realm role`() {
        adminClient().use { keycloak ->
            val realms = keycloak.realms().findAll().mapNotNull { it.realm }.toSet()
            // master is auto-created by Keycloak; the seeder adds exactly
            // one platform realm (platform-dev).
            assertTrue("platform-dev" in realms, "missing platform-dev; had $realms")
            // platform-customer etc. should NOT exist in single-realm mode.
            listOf("platform-customer", "platform-driver", "platform-courier", "platform-staff", "platform-internal", "platform-services")
                .forEach { assertTrue(it !in realms, "$it should NOT exist in single-realm mode") }
            val roles = keycloak.realm("platform-dev").roles().list().mapNotNull { it.name }.toSet()
            // Spot-check that roles from EVERY per-realm role set were merged.
            assertTrue("customer" in roles, "customer role missing in platform-dev")
            assertTrue("driver" in roles, "driver role missing in platform-dev")
            assertTrue("courier" in roles, "courier role missing in platform-dev")
            assertTrue("restaurant_staff" in roles, "restaurant_staff role missing in platform-dev")
            assertTrue("super_admin" in roles, "super_admin role missing in platform-dev")
            assertTrue("platform.super_admin" in roles, "platform.super_admin role missing in platform-dev")
            // Spot-check per-service realm roles.
            listOf("trip.read", "trip.write", "trip.admin", "trip.support", "trip.svc").forEach {
                assertTrue(it in roles, "$it missing in platform-dev")
            }
        }
    }

    @Test
    fun `seeder creates every channel client and every service client in platform-dev`() {
        adminClient().use { keycloak ->
            val realm = keycloak.realm("platform-dev")
            val clients = realm.clients().findAll().mapNotNull { it.clientId }.toSet()
            // 10 channel clients
            listOf(
                "web-customer", "mobile-customer",
                "web-driver", "mobile-driver",
                "web-courier", "mobile-courier",
                "web-restaurant", "web-merchant",
                "web-support", "web-admin",
            ).forEach { assertTrue(it in clients, "channel client $it missing in platform-dev") }
            // 21 service clients
            listOf(
                "identity-service", "api-gateway", "audit-service", "admin-service",
                "configuration-service", "notification-service", "reporting-service",
                "fraud-risk-service", "customer-service", "search-service",
                "driver-service", "trip-service", "pricing-service", "restaurant-service",
                "food-order-service", "courier-service", "geolocation-service",
                "payment-service", "ledger-service", "chat-service", "file-service",
            ).forEach { assertTrue(it in clients, "service client $it missing in platform-dev") }
            // Total = 10 channel + 21 service = 31 clients (Keycloak also creates
            // built-ins like `account`, `realm-management`, `security-admin-console`,
            // `admin-cli`, `broker`, `account-console`; we don't assert those).
            assertTrue(clients.size >= 31, "expected >=31 clients in platform-dev, had ${clients.size}")
        }
    }

    @Test
    fun `seeder creates platform-claims and service-claims scopes on platform-dev`() {
        adminClient().use { keycloak ->
            val realm = keycloak.realm("platform-dev")
            val scopes = realm.clientScopes().findAll().mapNotNull { it.name }
            assertTrue("platform-claims" in scopes, "platform-claims scope missing on platform-dev")
            assertTrue("service-claims" in scopes, "service-claims scope missing on platform-dev")
        }
    }

    @Test
    fun `seeder wires 63 protocol mappers on service-claims scope in platform-dev`() {
        adminClient().use { keycloak ->
            val realm = keycloak.realm("platform-dev")
            val svc = realm.clientScopes().findAll().firstOrNull { it.name == "service-claims" }
            assertNotNull(svc, "service-claims scope missing on platform-dev")
            val mappers = realm.clientScopes().get(svc!!.id).getProtocolMappers().getMappers()
            assertEquals(63, mappers.size, "expected 63 protocol mappers on service-claims in platform-dev; had ${mappers.size}")
            val scopeMapper = mappers.first { it.name == "trip-scopes" }
            assertEquals("oidc-script-based-property-mapper", scopeMapper.protocolMapper)
            assertTrue(scopeMapper.config["include.in.access.token"] == "true")
            assertTrue(scopeMapper.config["claim.is.multivalued"] == "true")
        }
    }

    @Test
    fun `seeder creates super-admin and dev users in platform-dev with full per-service admin set`() {
        adminClient().use { keycloak ->
            val realm = keycloak.realm("platform-dev")
            val services = listOf(
                "identity-service", "api-gateway", "audit-service", "admin-service",
                "configuration-service", "notification-service", "reporting-service",
                "fraud-risk-service", "customer-service", "search-service",
                "driver-service", "trip-service", "pricing-service", "restaurant-service",
                "food-order-service", "courier-service", "geolocation-service",
                "payment-service", "ledger-service", "chat-service", "file-service",
            )

            // admin@inovoria.com should hold <prefix>.admin for every service.
            val superAdmin = realm.users().searchByUsername("admin@inovoria.com", true).firstOrNull()
            assertNotNull(superAdmin, "super-admin user missing in platform-dev")
            val superRoles = realm.users().get(superAdmin!!.id).roles().realmLevel().listAll().mapNotNull { it.name }.toSet()
            services.forEach { svc ->
                val prefix = svc.removeSuffix("-service")
                assertTrue("$prefix.admin" in superRoles, "super-admin should have $prefix.admin in platform-dev")
            }

            // customer@trips-enjoy.com should have customer + customer-service.read + trip.read + payment.read.
            val customer = realm.users().searchByUsername("customer@trips-enjoy.com", true).firstOrNull()
            assertNotNull(customer, "customer user missing in platform-dev")
            val customerRoles = realm.users().get(customer!!.id).roles().realmLevel().listAll().mapNotNull { it.name }.toSet()
            assertTrue("customer" in customerRoles, "customer role missing on customer@trips-enjoy.com")
            assertTrue("customer.read" in customerRoles, "customer-service.read missing on customer@trips-enjoy.com")
            assertTrue("trip.read" in customerRoles, "trip.read missing on customer@trips-enjoy.com")
            assertTrue("payment.read" in customerRoles, "payment.read missing on customer@trips-enjoy.com")

            // driver@trips-enjoy.com should have driver + driver-service.read/.write + trip.read + geolocation.read/.write.
            val driver = realm.users().searchByUsername("driver@trips-enjoy.com", true).firstOrNull()
            assertNotNull(driver, "driver user missing in platform-dev")
            val driverRoles = realm.users().get(driver!!.id).roles().realmLevel().listAll().mapNotNull { it.name }.toSet()
            assertTrue("driver" in driverRoles)
            assertTrue("driver.read" in driverRoles)
            assertTrue("driver.write" in driverRoles)
            assertTrue("trip.read" in driverRoles)
            assertTrue("geolocation.read" in driverRoles)
            assertTrue("geolocation.write" in driverRoles)
        }
    }

    @Test
    fun `seeder grants identity read to every service account in platform-dev`() {
        adminClient().use { keycloak ->
            val realm = keycloak.realm("platform-dev")
            val identityClientId = realm.clients().findByClientId("identity-service").first().id
            val auditClientId = realm.clients().findByClientId("audit-service").first().id
            val auditUser = realm.clients().get(auditClientId).serviceAccountUser
            val auditRoles = realm.users().get(auditUser.id).roles().clientLevel(identityClientId).listAll().mapNotNull { it.name }.toSet()
            assertTrue("identity.read" in auditRoles, "audit-service should hold identity.read; had $auditRoles")
        }
    }

    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun registerKeycloak(reg: DynamicPropertyRegistry) = KeycloakTestSupport.register(reg)
    }
}
