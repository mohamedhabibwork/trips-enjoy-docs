package com.trips_enjoy.configuration

import com.trips_enjoy.platform.test.BaseIntegrationTest
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest

// Phase A (platform DRY): TestcontainersConfiguration deleted —
// platform-spring-boot-test's BaseIntegrationTest is the canonical base.
@SpringBootTest
class ConfigurationServiceApplicationTests : BaseIntegrationTest() {
    @Test
    fun contextLoads() {
    }
}