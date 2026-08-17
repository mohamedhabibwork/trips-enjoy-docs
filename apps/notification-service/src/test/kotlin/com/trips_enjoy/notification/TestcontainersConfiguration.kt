package com.trips_enjoy.notification

import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.context.annotation.Bean
import org.testcontainers.containers.GenericContainer
import org.testcontainers.kafka.KafkaContainer
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName

/**
 * Testcontainers wiring for notification-service integration tests.
 *
 * Phase A (platform DRY) deleted the previous local copy with the
 * promise that the platform auto-configuration would supply the same
 * containers platform-wide. The platform starter never landed a
 * matching `ServiceConnection` registry, so the canonical
 * `BaseIntegrationTest` cannot load a Spring context for this
 * service — `spring.datasource.url` resolves to
 * `jdbc:postgresql://0.0.0.0:5432/...` from `application-dev.yml` and
 * Hikari cannot derive a driver class.
 *
 * The minimum-viable fix is to restore the local Testcontainers
 * configuration so the `@ServiceConnection` annotations inject the
 * real Postgres / Kafka / Redis endpoints on context-load.
 */
@TestConfiguration(proxyBeanMethods = false)
class TestcontainersConfiguration {

	@Bean
	@ServiceConnection
	fun kafkaContainer(): KafkaContainer {
		return KafkaContainer(DockerImageName.parse("apache/kafka-native:latest"))
	}

	@Bean
	@ServiceConnection
	fun postgresContainer(): PostgreSQLContainer {
		return PostgreSQLContainer(DockerImageName.parse("postgres:latest"))
	}

	@Bean
	@ServiceConnection(name = "redis")
	fun redisContainer(): GenericContainer<*> {
		return GenericContainer(DockerImageName.parse("redis:latest")).withExposedPorts(6379)
	}

}
