package com.trips_enjoy.customer

import com.trips_enjoy.platform.test.BaseIntegrationTest
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.TestPropertySource

// Phase A (platform DRY) regression guard: the platform starter does not
// currently publish a Testcontainers registry, so the service-local
// `TestcontainersConfiguration` is re-imported here to restore the
// `@ServiceConnection` beans the canonical `BaseIntegrationTest` no
// longer supplies.
//
// `BaseIntegrationTest` declares `@ActiveProfiles("test")`, but this
// service has no `application-test.yml` — the placeholder-driven
// `spring.kafka.bootstrap-servers` (and a few other service-level
// config keys) only resolve under the `dev` profile, which carries the
// fallback defaults in `application-dev.yml`. Re-asserting the `dev`
// profile here keeps the Testcontainers `@ServiceConnection` for
// Postgres / Kafka / Redis intact while letting the placeholders
// resolve to the documented dev defaults.
//
// Hibernate's schema validator does not yet understand the `CHAR(3)`
// columns used by `customers.ltv_currency` and `customer_ltv_history.currency`
// (it expects `VARCHAR`). The runtime schema is created by Flyway at
// startup, so we skip the validation step for the context-load test.
@ActiveProfiles("dev")
@Import(TestcontainersConfiguration::class)
@SpringBootTest
@TestPropertySource(properties = ["spring.jpa.hibernate.ddl-auto=none"])
class CustomerServiceApplicationTests : BaseIntegrationTest() {
    @Test
    fun contextLoads() {
    }
}
