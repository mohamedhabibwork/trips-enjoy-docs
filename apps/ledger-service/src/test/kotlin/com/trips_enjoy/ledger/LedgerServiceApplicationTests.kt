package com.trips_enjoy.ledger

import com.trips_enjoy.platform.test.BaseIntegrationTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.springframework.boot.test.context.SpringBootTest

/**
 * Integration test that boots the full Spring context with the platform's
 * Testcontainers wiring (PostgreSQL, Kafka, Redis) via the canonical
 * `BaseIntegrationTest`. Disabled by default because it requires Docker to
 * be available on the host. Run explicitly with:
 *   `DOCKER_AVAILABLE=true ./gradlew test --tests LedgerServiceApplicationTests`
 *
 * Phase A (platform DRY): the local `TestcontainersConfiguration` was deleted
 * and `@Import(...)` was dropped — Testcontainers are now sourced from
 * `platform-spring-boot-test`'s `BaseIntegrationTest`.
 */
@EnabledIfEnvironmentVariable(named = "DOCKER_AVAILABLE", matches = "true")
@SpringBootTest
class LedgerServiceApplicationTests : BaseIntegrationTest() {

	@Test
	fun contextLoads() {
	}

}
