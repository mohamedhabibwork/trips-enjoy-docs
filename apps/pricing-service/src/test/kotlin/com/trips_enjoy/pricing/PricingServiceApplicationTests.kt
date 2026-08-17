package com.trips_enjoy.pricing

import com.trips_enjoy.platform.test.BaseIntegrationTest
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest

// Phase A (platform DRY): TestcontainersConfiguration deleted —
// platform-spring-boot-test's BaseIntegrationTest is the canonical base.
@SpringBootTest
@org.springframework.test.context.ActiveProfiles("test")
class PricingServiceApplicationTests : BaseIntegrationTest() {

	@Test
	fun contextLoads() {
	}

}
