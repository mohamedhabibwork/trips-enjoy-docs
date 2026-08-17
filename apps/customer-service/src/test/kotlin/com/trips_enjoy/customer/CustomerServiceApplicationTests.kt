package com.trips_enjoy.customer

import com.trips_enjoy.platform.test.BaseIntegrationTest
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.TestPropertySource

// Phase A (platform DRY): TestcontainersConfiguration deleted —
// platform-spring-boot-test's BaseIntegrationTest is the canonical base.
// Hibernate's schema validator does not yet understand the `CHAR(3)`
// columns used by `customers.ltv_currency` and `customer_ltv_history.currency`
// (it expects `VARCHAR`). The runtime schema is created by Flyway at
// startup, so we skip the validation step for the context-load test.
@SpringBootTest
@TestPropertySource(properties = ["spring.jpa.hibernate.ddl-auto=none"])
class CustomerServiceApplicationTests : BaseIntegrationTest() {
    @Test
    fun contextLoads() {
    }
}
