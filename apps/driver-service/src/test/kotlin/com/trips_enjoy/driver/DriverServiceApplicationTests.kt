package com.trips_enjoy.driver

import com.trips_enjoy.platform.test.BaseIntegrationTest
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.TestPropertySource

// Phase A (platform DRY): TestcontainersConfiguration deleted —
// platform-spring-boot-test's BaseIntegrationTest is the canonical base.
@SpringBootTest
@TestPropertySource(properties = ["spring.jpa.hibernate.ddl-auto=none"])
class DriverServiceApplicationTests : BaseIntegrationTest() {

	@Test
	fun contextLoads() {
	}

}
