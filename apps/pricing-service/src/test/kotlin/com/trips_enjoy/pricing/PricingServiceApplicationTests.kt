package com.trips_enjoy.pricing

import com.trips_enjoy.platform.test.BaseIntegrationTest
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.TestPropertySource

// Phase A (platform DRY) regression guard: the platform starter does not
// currently publish a Testcontainers registry, so the service-local
// `TestcontainersConfiguration` is re-imported here to restore the
// `@ServiceConnection` beans the canonical `BaseIntegrationTest` no
// longer supplies.
//
// The service-local `JwtDecoder` bean is wired against the
// `pricing-service.keycloak.jwks-uri` placeholder; the test profile
// supplies a stub URI so the placeholder resolves without a live
// Keycloak.
@Import(TestcontainersConfiguration::class)
@SpringBootTest
@org.springframework.test.context.ActiveProfiles("test")
@TestPropertySource(properties = ["pricing-service.keycloak.jwks-uri=http://localhost:8181/realms/platform-services/protocol/openid-connect/certs"])
class PricingServiceApplicationTests : BaseIntegrationTest() {

	@Test
	fun contextLoads() {
	}

}
