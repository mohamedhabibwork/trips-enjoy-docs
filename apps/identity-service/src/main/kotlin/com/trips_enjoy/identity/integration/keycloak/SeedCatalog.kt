package com.trips_enjoy.identity.integration.keycloak

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Canonical lists that drive the seeder and the OpenAPI generator. The
 * 21-service bounded-context roster matches
 * `docs/architecture/ARCHITECTURE.md` (5 Kotlin + 4 Go + 5 Python + 6
 * Spring Boot per the consolidation memo). The realm/role taxonomy
 * matches `docs/architecture/KEYCLOAK_ARCHITECTURE.md` §"Scopes and
 * Claims" and `docs/shared/TIME_BOUNDED_ALIASES.md`.
 *
 * Two topology modes (selected by `identity.keycloak.topology`,
 * default `single-realm`):
 *
 * 1. `single-realm` — one realm (`platform-dev` by default) holds every
 *    client, role, mapper, and user. This is the dev / CI shape: a fresh
 *    developer runs `./gradlew bootRun` and gets a fully provisioned
 *    platform in one realm, no per-realm env wiring.
 *
 * 2. `multi-realm` — the documented 6-realm split:
 *    `platform-customer`, `platform-driver`, `platform-courier`,
 *    `platform-staff`, `platform-internal`, `platform-services`. Stg /
 *    prod default. Operators opt in via
 *    `IDENTITY_KEYCLOAK_TOPOLOGY=multi-realm`.
 *
 * In either mode the seeder reads `SeedSpec.servicesRealm` /
 * `SeedSpec.adminRealm` instead of hardcoded realm names, so the seeder
 * doesn't branch on topology.
 *
 * The bean is exposed (not just `val`) so tests can override individual
 * fields via `@TestConfiguration` while still benefiting from the
 * defaults in production.
 */
@Configuration
@ConditionalOnProperty("identity.keycloak.seed.enabled", havingValue = "true")
@EnableConfigurationProperties(SeedTopologyProperties::class)
class SeedCatalog(private val props: SeedTopologyProperties) {

    @Bean
    fun seedSpec(): SeedSpec {
        require(props.topology in setOf("single-realm", "multi-realm")) {
            "identity.keycloak.topology must be 'single-realm' or 'multi-realm', got '${props.topology}'"
        }
        val devRealmName = props.effectiveDevRealmName()
        val realms = if (props.topology == "single-realm") singleRealmSpecs(devRealmName) else multiRealmSpecs()
        val servicesRealm = props.effectiveServicesRealm()
        val adminRealm = props.effectiveAdminRealm()
        return SeedSpec(
            realms = realms,
            serviceClients = services,
            devUsers = devUsers,
            serviceClaims = services.map(SeedServiceClaim::canonicalFor),
            servicesRealm = servicesRealm,
            adminRealm = adminRealm,
            topology = props.topology,
        ).also { it.attachDevUserRealm(devRealmName) }
    }

    /** In single-realm mode, every dev user lives in [realm]. */
    private fun SeedSpec.attachDevUserRealm(realm: String): SeedSpec =
        if (props.topology == "single-realm") copy(devUsers = devUsers.map { it.copy(realm = realm) })
        else this

    /** Single realm: every client + every role + the platform/service claim scopes. */
    private fun singleRealmSpecs(realm: String): List<SeedRealmSpec> {
        return listOf(
            SeedRealmSpec(
                realm = realm,
                realmRoles = allRealmRolesForSingleRealm(),
                channelClients = allChannelClientsForSingleRealm(realm),
                additionalDefaultScopes = listOf("service-claims"),
            ),
        )
    }

    /** Multi-realm: the 6 documented realms. */
    private fun multiRealmSpecs(): List<SeedRealmSpec> = listOf(
        SeedRealmSpec(
            realm = "platform-customer",
            realmRoles = listOf("customer", "customer.suspended"),
            channelClients = listOf(
                SeedChannelClient("platform-customer", "web-customer", true),
                SeedChannelClient("platform-customer", "mobile-customer", true),
            ),
        ),
        SeedRealmSpec(
            realm = "platform-driver",
            realmRoles = listOf("driver", "driver.pending_review", "driver.suspended", "driver.admin"),
            channelClients = listOf(
                SeedChannelClient("platform-driver", "web-driver", true),
                SeedChannelClient("platform-driver", "mobile-driver", true),
            ),
        ),
        SeedRealmSpec(
            realm = "platform-courier",
            realmRoles = listOf("courier", "courier.pending_review", "courier.suspended", "courier.admin"),
            channelClients = listOf(
                SeedChannelClient("platform-courier", "web-courier", true),
                SeedChannelClient("platform-courier", "mobile-courier", true),
            ),
        ),
        SeedRealmSpec(
            realm = "platform-staff",
            realmRoles = listOf("restaurant_staff", "restaurant_manager", "merchant_staff", "merchant_manager"),
            channelClients = listOf(
                SeedChannelClient("platform-staff", "web-restaurant", false),
                SeedChannelClient("platform-staff", "web-merchant", false),
            ),
        ),
        SeedRealmSpec(
            realm = "platform-internal",
            realmRoles = listOf(
                "support_agent_l1", "support_agent_l2", "support_agent_l3",
                "operations", "finance", "fraud_reviewer",
                "admin", "super_admin",
                "platform.admin", "platform.super_admin", "identity.admin",
            ),
            channelClients = listOf(
                SeedChannelClient("platform-internal", "web-support", false),
                SeedChannelClient("platform-internal", "web-admin", false),
            ),
        ),
        SeedRealmSpec(
            realm = "platform-services",
            realmRoles = emptyList(),
            channelClients = emptyList(),
            additionalDefaultScopes = listOf("service-claims"),
        ),
    )

    /** Union of every realm role set that multi-realm mode distributes across 6 realms. */
    private fun allRealmRolesForSingleRealm(): List<String> {
        val perRealmRoles = listOf(
            listOf("customer", "customer.suspended"),
            listOf("driver", "driver.pending_review", "driver.suspended", "driver.admin"),
            listOf("courier", "courier.pending_review", "courier.suspended", "courier.admin"),
            listOf("restaurant_staff", "restaurant_manager", "merchant_staff", "merchant_manager"),
            listOf(
                "support_agent_l1", "support_agent_l2", "support_agent_l3",
                "operations", "finance", "fraud_reviewer",
                "admin", "super_admin",
                "platform.admin", "platform.super_admin", "identity.admin",
            ),
        ).flatten()
        val serviceRoles = services.flatMap { svc ->
            val prefix = svc.removeSuffix("-service")
            listOf("$prefix.read", "$prefix.write", "$prefix.admin", "$prefix.support", "$prefix.svc")
        }
        return (perRealmRoles + serviceRoles).distinct()
    }

    /** Every channel client the multi-realm mode distributes across 5 realms — collapsed to one. */
    private fun allChannelClientsForSingleRealm(realm: String): List<SeedChannelClient> = listOf(
        SeedChannelClient(realm, "web-customer", true),
        SeedChannelClient(realm, "mobile-customer", true),
        SeedChannelClient(realm, "web-driver", true),
        SeedChannelClient(realm, "mobile-driver", true),
        SeedChannelClient(realm, "web-courier", true),
        SeedChannelClient(realm, "mobile-courier", true),
        SeedChannelClient(realm, "web-restaurant", false),
        SeedChannelClient(realm, "web-merchant", false),
        SeedChannelClient(realm, "web-support", false),
        SeedChannelClient(realm, "web-admin", false),
    )

    /** 21-service bounded-context roster (matches ARCHITECTURE.md). */
    private val services: List<String> = listOf(
        "identity-service",
        "api-gateway",
        "audit-service",
        "admin-service",
        "configuration-service",
        "notification-service",
        "reporting-service",
        "fraud-risk-service",
        "customer-service",
        "search-service",
        "driver-service",
        "trip-service",
        "pricing-service",
        "restaurant-service",
        "food-order-service",
        "courier-service",
        "geolocation-service",
        "payment-service",
        "ledger-service",
        "chat-service",
        "file-service",
    )

    /**
     * Per-realm dev/test users (multi-realm mode) OR per-dev-realm users in
     * the single realm (single-realm mode — [attachDevUserRealm] rewrites
     * `realm` to `props.devRealmName`). The super-admin row is the canonical
     * 21-entry preset (1 × platform.super_admin + 20 × <service>.admin) and
     * is wired by `KeycloakSeeder.ensureSuperAdmin`; we leave it out of
     * `devUsers` so the seeder doesn't double-grant.
     *
     * `serviceRoles` is the per-service role bundle the user holds in the
     * services realm — used to test the `<service>.scopes` / `.level`
     * claims without going through the admin endpoint.
     */
    private val devUsers: List<SeedUserSpec> = listOf(
        SeedUserSpec(
            username = "customer@trips-enjoy.com",
            email = "customer@trips-enjoy.com",
            realm = "platform-customer",
            realmRoles = listOf("customer"),
            serviceRoles = mapOf(
                "customer-service" to listOf("customer.read"),
                "trip-service" to listOf("trip.read"),
                "payment-service" to listOf("payment.read"),
            ),
        ),
        SeedUserSpec(
            username = "driver@trips-enjoy.com",
            email = "driver@trips-enjoy.com",
            realm = "platform-driver",
            realmRoles = listOf("driver"),
            serviceRoles = mapOf(
                "driver-service" to listOf("driver.read", "driver.write"),
                "trip-service" to listOf("trip.read"),
                "geolocation-service" to listOf("geolocation.read", "geolocation.write"),
                "notification-service" to listOf("notification.read"),
            ),
        ),
        SeedUserSpec(
            username = "courier@trips-enjoy.com",
            email = "courier@trips-enjoy.com",
            realm = "platform-courier",
            realmRoles = listOf("courier"),
            serviceRoles = mapOf(
                "courier-service" to listOf("courier.read", "courier.write"),
                "trip-service" to listOf("trip.read"),
                "geolocation-service" to listOf("geolocation.read", "geolocation.write"),
            ),
        ),
        SeedUserSpec(
            username = "restaurant-staff@trips-enjoy.com",
            email = "restaurant-staff@trips-enjoy.com",
            realm = "platform-staff",
            realmRoles = listOf("restaurant_staff"),
            serviceRoles = mapOf(
                "restaurant-service" to listOf("restaurant.read", "restaurant.write"),
                "food-order-service" to listOf("food-order.read", "food-order.write"),
                "notification-service" to listOf("notification.read"),
            ),
        ),
        SeedUserSpec(
            username = "merchant-staff@trips-enjoy.com",
            email = "merchant-staff@trips-enjoy.com",
            realm = "platform-staff",
            realmRoles = listOf("merchant_staff"),
            serviceRoles = mapOf(
                "restaurant-service" to listOf("restaurant.read"),
                "payment-service" to listOf("payment.read"),
            ),
        ),
        SeedUserSpec(
            username = "support@trips-enjoy.com",
            email = "support@trips-enjoy.com",
            realm = "platform-internal",
            realmRoles = listOf("support_agent_l1", "operations"),
            serviceRoles = services.associate { svc ->
                val prefix = svc.removeSuffix("-service")
                svc to listOf("$prefix.read", "$prefix.support")
            },
        ),
        SeedUserSpec(
            username = "finance@trips-enjoy.com",
            email = "finance@trips-enjoy.com",
            realm = "platform-internal",
            realmRoles = listOf("finance"),
            serviceRoles = mapOf(
                "payment-service" to listOf("payment.read", "payment.admin"),
                "ledger-service" to listOf("ledger.read", "ledger.admin"),
                "reporting-service" to listOf("reporting.read"),
            ),
        ),
    )
}