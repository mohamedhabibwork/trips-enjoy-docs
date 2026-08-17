package com.trips_enjoy.admin

import com.trips_enjoy.platform.test.BaseIntegrationTest
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles

// Phase A (platform DRY): TestcontainersConfiguration deleted — extends the
// canonical BaseIntegrationTest from platform-spring-boot-test.
@SpringBootTest
@ActiveProfiles("test")
class AdminServiceApplicationTests : BaseIntegrationTest() {

	@Test
	fun contextLoads() {
	}

}