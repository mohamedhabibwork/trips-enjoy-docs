package com.trips_enjoy.platform.test

import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles

/**
 * Base class for integration tests. Subclasses annotate with
 * `@SpringBootTest(classes = MyServiceApplication::class)` and
 * pick up the platform's auto-configuration via the umbrella starter.
 *
 * Service-specific tests extend this and add Testcontainers via
 * `@Container` + `@ServiceConnection` annotations on individual fields.
 */
@SpringBootTest
@ActiveProfiles("test")
abstract class BaseIntegrationTest
