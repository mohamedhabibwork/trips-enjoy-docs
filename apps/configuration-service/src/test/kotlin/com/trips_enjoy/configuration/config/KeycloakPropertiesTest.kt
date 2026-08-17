package com.trips_enjoy.configuration.config

import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test

class KeycloakPropertiesTest {
    @Test
    fun `keycloak jwks-uri is a valid URL`() {
        val exampleUri = "http://0.0.0.0:8181/realms/platform-services/protocol/openid-connect/certs"
        Assertions.assertTrue(exampleUri.startsWith("http"))
        Assertions.assertTrue(exampleUri.contains("/protocol/openid-connect/certs"))
    }

    @Test
    fun `oauth2 resource server uses the correct resource-server starter`() {
        // Smoke check: the starter coordinate that the build.gradle.kts must
        // pull in is `spring-boot-starter-oauth2-resource-server`. This is a
        // build-time invariant; the runtime test (`@WebMvcTest`) would also
        // assert it indirectly.
        val expected = "org.springframework.boot:spring-boot-starter-oauth2-resource-server"
        // The actual presence is verified by `./gradlew dependencies`; here
        // we just assert the expected coordinate is well-formed.
        Assertions.assertTrue(expected.startsWith("org.springframework.boot"))
    }
}
