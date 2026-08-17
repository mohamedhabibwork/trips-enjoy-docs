package com.trips_enjoy.audit

import com.trips_enjoy.platform.test.BaseIntegrationTest
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest

// Phase A (platform DRY): TestcontainersConfiguration deleted — extends the
// canonical BaseIntegrationTest from platform-spring-boot-test.
@SpringBootTest
class AuditServiceApplicationTests : BaseIntegrationTest() {

	@Test
	fun contextLoads() {
	}

}
