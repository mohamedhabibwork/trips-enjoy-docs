package com.trips_enjoy.identity.config

import com.trips_enjoy.identity.integration.keycloak.SeedChannelClient
import com.trips_enjoy.identity.integration.keycloak.SeedRealmSpec
import com.trips_enjoy.identity.integration.keycloak.SeedSpec
import com.trips_enjoy.identity.integration.keycloak.SeedUserSpec
import org.junit.jupiter.api.Test
import org.springframework.mock.env.MockEnvironment
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class OpenApiConfigurationTest {
    private val spec = SeedSpec(
        realms = listOf(
            SeedRealmSpec("platform-customer", channelClients = listOf(SeedChannelClient("platform-customer", "web-customer", true))),
            SeedRealmSpec("platform-driver", channelClients = listOf(SeedChannelClient("platform-driver", "mobile-driver", true))),
            SeedRealmSpec("platform-courier", channelClients = listOf(SeedChannelClient("platform-courier", "web-courier", true))),
            SeedRealmSpec("platform-staff", channelClients = listOf(SeedChannelClient("platform-staff", "web-restaurant", false))),
            SeedRealmSpec("platform-internal", channelClients = listOf(SeedChannelClient("platform-internal", "web-admin", false))),
            SeedRealmSpec("platform-services"),
            SeedRealmSpec("master"),
        ),
        serviceClients = listOf("identity-service"),
        devUsers = listOf(SeedUserSpec("customer@trips-enjoy.com", "customer@trips-enjoy.com", "platform-customer", listOf("customer"))),
    )

    private fun openApi(publicUrl: String = "http://localhost:8082", defaultRealm: String = "platform-services", seed: SeedSpec? = spec) =
        OpenApiConfiguration.buildOpenApi(
            MockEnvironment().apply {
                setProperty("identity.public-url", publicUrl)
                setProperty("identity.keycloak.default-realm", defaultRealm)
            },
            seed,
        )

    @Test
    fun `server URL points at identity service not keycloak`() {
        val openApi = openApi(defaultRealm = "platform-services")
        assertEquals(1, openApi.servers.size)
        assertEquals("http://localhost:8082", openApi.servers[0].url)
    }

    @Test
    fun `registers bearerAuth plus one oauth2 scheme per channel client`() {
        val openApi = openApi()
        val schemes = openApi.components.securitySchemes
        assertNotNull(schemes)
        assertEquals(6, schemes.size, "expected 6 security schemes, got ${schemes.keys}")
        assertTrue(schemes.containsKey("bearerAuth"))
        listOf(
            "kc-platform-customer-web-customer",
            "kc-platform-driver-mobile-driver",
            "kc-platform-courier-web-courier",
            "kc-platform-staff-web-restaurant",
            "kc-platform-internal-web-admin",
        ).forEach { key -> assertTrue(schemes.containsKey(key), "missing oauth2 scheme $key") }
    }

    @Test
    fun `oauth2 scheme points at identity service bff endpoints`() {
        val openApi = openApi()
        val scheme = openApi.components.securitySchemes["kc-platform-driver-mobile-driver"]!!
        assertEquals("oauth2", scheme.type.toString())
        val flows = scheme.flows.authorizationCode
        assertEquals("http://localhost:8082/oauth2/authorize?realm=platform-driver", flows.authorizationUrl)
        assertEquals("http://localhost:8082/oauth2/token?realm=platform-driver", flows.tokenUrl)
    }

    @Test
    fun `tags list every seeded realm and flag the default`() {
        val openApi = openApi(defaultRealm = "platform-driver")
        val tags = openApi.tags
        assertEquals(spec.realms.size, tags.size)
        val names = tags.map { it.name }
        assertTrue("platform-driver" in names)
        assertTrue("platform-services" in names)
        val defaultTag = tags.first { it.name == "platform-driver" }
        assertEquals("true", defaultTag.extensions?.get("x-seed-default")?.toString())
        val nonDefault = tags.first { it.name == "platform-customer" }
        assertEquals("false", nonDefault.extensions?.get("x-seed-default")?.toString())
    }

    @Test
    fun `info description enumerates seeded realms and default`() {
        val openApi = openApi(defaultRealm = "platform-services")
        val description = openApi.info.description
        assertTrue(description.contains("platform-customer"), "missing realm in description")
        assertTrue(description.contains("platform-services"))
        assertTrue(description.contains("Default realm: platform-services"))
    }

    @Test
    fun `info description documents per-service claims contract`() {
        val openApi = openApi()
        val description = openApi.info.description
        assertTrue(description.contains("<service>.scopes"), "missing <service>.scopes in description: $description")
        assertTrue(description.contains("<service>.level"), "missing <service>.level in description: $description")
        assertTrue(description.contains("<service>.tenant"), "missing <service>.tenant in description: $description")
    }

    @Test
    fun `default realm falls back to platform-services when env var absent`() {
        val openApi = OpenApiConfiguration.buildOpenApi(MockEnvironment().apply { setProperty("identity.public-url", "http://identity.local:8082") }, spec)
        assertEquals("http://identity.local:8082", openApi.servers[0].url)
        assertEquals("http://identity.local:8082/oauth2/authorize?realm=platform-customer", openApi.components.securitySchemes["kc-platform-customer-web-customer"]!!.flows.authorizationCode.authorizationUrl)
    }

    @Test
    fun `server URL follows identity default realm in single-realm mode`() {
        val singleRealmSpec = SeedSpec(
            realms = listOf(SeedRealmSpec("platform-dev", channelClients = listOf(SeedChannelClient("platform-dev", "web-admin", false)))),
            serviceClients = listOf("identity-service"),
            devUsers = emptyList(),
            topology = "single-realm",
            servicesRealm = "platform-dev",
            adminRealm = "platform-dev",
        )
        val openApi = OpenApiConfiguration.buildOpenApi(
            MockEnvironment().apply {
                setProperty("identity.public-url", "http://localhost:8082")
                setProperty("identity.keycloak.default-realm", "platform-dev")
                setProperty("identity.keycloak.topology", "single-realm")
                setProperty("identity.keycloak.dev-realm-name", "platform-dev")
            },
            singleRealmSpec,
        )
        assertEquals(1, openApi.servers.size)
        assertEquals("http://localhost:8082", openApi.servers[0].url)
        assertEquals(1, openApi.tags.size)
        assertEquals("platform-dev", openApi.tags[0].name)
        assertEquals("true", openApi.tags[0].extensions?.get("x-seed-default")?.toString())
    }

    @Test
    fun `minimal contract is produced when no SeedSpec is present`() {
        val openApi = openApi(seed = null)
        assertTrue(openApi.servers.isNullOrEmpty())
        assertTrue(openApi.tags.isNullOrEmpty())
        assertEquals(1, openApi.components.securitySchemes.size)
        assertTrue(openApi.components.securitySchemes.containsKey("bearerAuth"))
        assertTrue(openApi.info.description == "Internal Keycloak identity adapter API.")
    }
}
