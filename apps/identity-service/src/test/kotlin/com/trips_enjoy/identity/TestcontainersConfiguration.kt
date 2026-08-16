package com.trips_enjoy.identity

import dasniko.testcontainers.keycloak.KeycloakContainer
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.context.annotation.Bean
import org.testcontainers.containers.GenericContainer
import org.testcontainers.kafka.KafkaContainer
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName

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

	/**
	 * Keycloak 24.0 testcontainer for `KeycloakSeederIT` and friends.
	 * The container is reused across test classes via `withReuse(true)` when
	 * `TESTCONTAINERS_REUSE_ENABLE=true` is set (default off in CI).
	 *
	 * Wired without `@ServiceConnection` because the seeder uses PASSWORD
	 * grant + admin-cli (not client credentials), and we want tests to read
	 * the boot-time admin password via `keycloakContainer.getAdminUsername()`
	 * / `getAdminPassword()` rather than rely on auto-config.
	 *
	 * Gated on `RUN_KEYCLOAK_IT=true` so that the bulk of the test suite
	 * (which uses an unreachable `identity.keycloak.base-url` to exercise
	 * the upstream-failure path) does not have to wait for the ~30s Keycloak
	 * boot, and so that this configuration can be imported by every IT
	 * without paying the Keycloak startup cost on each. The `*Keycloak*IT`
	 * classes are themselves gated with `@EnabledIfEnvironmentVariable(
	 * named = "RUN_KEYCLOAK_IT", matches = "true")`, so this condition keeps
	 * the bean only present when an actual Keycloak IT is selected.
	 */
	@Bean
	@ConditionalOnExpression("'\${RUN_KEYCLOAK_IT:false}' == 'true'")
	fun keycloakContainer(): KeycloakContainer {
		return KeycloakContainer("quay.io/keycloak/keycloak:24.0").withRealmImportFile("keycloak/empty-realm.json")
	}
}