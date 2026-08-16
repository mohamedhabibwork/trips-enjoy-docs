package com.trips_enjoy.ledger

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import

/**
 * Integration test that boots the full Spring context with Testcontainers
 * (PostgreSQL, Kafka, Redis). Disabled by default because it requires
 * Docker to be available on the host. Run explicitly with:
 *   `DOCKER_AVAILABLE=true ./gradlew test --tests LedgerServiceApplicationTests`
 */
@EnabledIfEnvironmentVariable(named = "DOCKER_AVAILABLE", matches = "true")
@Import(TestcontainersConfiguration::class)
@SpringBootTest
class LedgerServiceApplicationTests {

	@Test
	fun contextLoads() {
	}

}
