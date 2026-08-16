package com.trips_enjoy.notification.integration.events

import com.fasterxml.jackson.databind.ObjectMapper
import com.trips_enjoy.notification.NotificationServiceApplication
import com.trips_enjoy.notification.TestcontainersConfiguration
import com.trips_enjoy.notification.domain.OutboxEventRepository
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.kafka.core.KafkaTemplate
import java.time.Duration
import java.util.UUID
import kotlin.test.assertTrue

/**
 * Testcontainers-backed integration test for the `trip.completed` Kafka
 * consumer path. Verifies that an event published to `trip.completed`
 * results in a `notification.sent.v1` row in `notification.outbox`.
 *
 * Requires Docker (Testcontainers). Marked as IT for visibility; CI can
 * gate these separately.
 */
@SpringBootTest(classes = [NotificationServiceApplication::class])
@Import(TestcontainersConfiguration::class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class NotificationCommandConsumerIT {

	@Autowired
	private lateinit var kafka: KafkaTemplate<String, String>

	@Autowired
	private lateinit var outbox: OutboxEventRepository

	private val mapper = ObjectMapper()

	@Test
	fun `trip completed event triggers notification sent v1 outbox row`() {
		val envelope = mapper.writeValueAsString(
			mapOf(
				"event_id" to UUID.randomUUID().toString(),
				"event_name" to "trip.completed.v1",
				"occurred_at" to java.time.Instant.now().toString(),
				"schema_version" to 1,
				"producer" to "trip-service",
				"tenant_id" to "global",
				"correlation_id" to UUID.randomUUID().toString(),
				"aggregate_type" to "Trip",
				"aggregate_id" to UUID.randomUUID().toString(),
				"data" to mapOf(
					"trip_id" to UUID.randomUUID().toString(),
					"user_id" to UUID.randomUUID().toString(),
				),
			),
		)
		kafka.send("trip.completed", UUID.randomUUID().toString(), envelope)

		await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofSeconds(1)).until {
			outbox.findAll().any { it.eventName == "notification.sent.v1" }
		}
		assertTrue(outbox.findAll().any { it.eventName == "notification.sent.v1" })
	}
}