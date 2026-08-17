package com.trips_enjoy.foodorder

import com.trips_enjoy.platform.test.BaseIntegrationTest
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest

// Phase A (platform DRY): TestcontainersConfiguration deleted — extends the
// canonical BaseIntegrationTest from platform-spring-boot-test.
@SpringBootTest
@org.springframework.test.context.ActiveProfiles("test")
class FoodOrderServiceApplicationTests : BaseIntegrationTest() {

	@Test
	fun contextLoads() {
	}

}