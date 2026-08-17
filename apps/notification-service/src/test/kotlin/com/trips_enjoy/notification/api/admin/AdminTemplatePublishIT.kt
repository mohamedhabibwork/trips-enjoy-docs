package com.trips_enjoy.notification.api.admin

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.trips_enjoy.notification.NotificationServiceApplication
import com.trips_enjoy.notification.TestcontainersConfiguration
import com.trips_enjoy.notification.testing.JwtTestUtils
import com.trips_enjoy.platform.test.BaseIntegrationTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder
import org.springframework.test.context.ActiveProfiles
import org.springframework.web.client.RestTemplate
import java.util.UUID

/**
 * Admin template lifecycle IT — exercises:
 *   POST /v1/admin/templates → PATCH → POST /publish → GET /history
 *
 * Requires Testcontainers (Postgres + Redis + Kafka). Marked as IT to make
 * the distinction visible in the JUnit XML reports. Test run skips if
 * Docker is unavailable (existing `NotificationServiceApplicationTests`
 * inherits the same posture — see audit/identity memory).
 *
 * Phase A (platform DRY) regression guard: the platform starter does
 * not currently publish a Testcontainers registry, so the service-local
 * `TestcontainersConfiguration` is re-imported here. The `dev` profile
 * is re-asserted so the placeholder-driven
 * `spring.kafka.bootstrap-servers` resolves to the dev-default value
 * (see `application-dev.yml`).
 */
@ActiveProfiles("dev")
@Import(TestcontainersConfiguration::class, AdminTemplatePublishIT.TestJwtConfig::class)
@SpringBootTest(
	classes = [NotificationServiceApplication::class],
	webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AdminTemplatePublishIT : BaseIntegrationTest() {

	companion object {
		val jwt = JwtTestUtils()
	}

	@LocalServerPort
	var port: Int = 0

	@Autowired
	private lateinit var rest: RestTemplate

	private val mapper = ObjectMapper()
		.registerModule(JavaTimeModule())
		.registerModule(KotlinModule.Builder().build())

	private fun auth(role: String = "notification.admin"): HttpHeaders {
		val h = HttpHeaders()
		h.contentType = MediaType.APPLICATION_JSON
		h.setBearerAuth(jwt.mintAdminToken(role))
		h.set("X-Request-Id", UUID.randomUUID().toString())
		return h
	}

	@Test
	fun `admin publish lifecycle writes history snapshot and returns audit chain`() {
		val name = "trip.completed"

		// 1. POST template
		val create = CreateTemplateRequest(
			name = name,
			category = "trip",
			channel = "push",
			locale = "en",
			body = "Hi {{name}}, trip {{trip_id}} is complete.",
			subject = "Trip done",
			template_type = "plain",
			required_variables = listOf("name", "trip_id"),
		)
		val createResponse = rest.exchange(
			"http://localhost:$port/v1/admin/templates",
			org.springframework.http.HttpMethod.POST,
			HttpEntity(create, auth()),
			String::class.java,
		)
		assertEquals(HttpStatus.CREATED, createResponse.statusCode)
		val templateId = mapper.readTree(createResponse.body!!).get("id").asText()
		assertNotNull(templateId)

		// 2. PATCH (edit body)
		val patch = UpdateTemplateRequest(body = "Hi {{name}}, your trip {{trip_id}} is complete.")
		val patchResponse = rest.exchange(
			"http://localhost:$port/v1/admin/templates/$templateId",
			org.springframework.http.HttpMethod.PATCH,
			HttpEntity(patch, auth()),
			String::class.java,
		)
		assertEquals(HttpStatus.OK, patchResponse.statusCode)

		// 3. POST /publish (atomic across (channel, locale))
		val publish = PublishTemplateRequest(diff_summary = mapOf("note" to "first release"))
		val publishResponse = rest.exchange(
			"http://localhost:$port/v1/admin/templates/$templateId/publish",
			org.springframework.http.HttpMethod.POST,
			HttpEntity(publish, auth()),
			String::class.java,
		)
		assertEquals(HttpStatus.OK, publishResponse.statusCode)
		val publishBody = mapper.readTree(publishResponse.body!!)
		val history = publishBody.get("history")
		assertTrue(history.isArray)
		assertTrue(history.size() >= 1)
		val firstRevision = history.get(0)
		assertEquals(1, firstRevision.get("revision_no").asInt())
		assertTrue(firstRevision.get("version").asInt() >= 2)

		// 4. GET /history
		val historyResponse = rest.exchange(
			"http://localhost:$port/v1/admin/templates/$templateId/history",
			org.springframework.http.HttpMethod.GET,
			HttpEntity(null, auth()),
			String::class.java,
		)
		assertEquals(HttpStatus.OK, historyResponse.statusCode)
		assertTrue(mapper.readTree(historyResponse.body!!).get("history").size() >= 1)
	}

	@TestConfiguration
	class TestJwtConfig {
		@Bean
		@Primary
		fun testJwtDecoder(): JwtDecoder =
			NimbusJwtDecoder.withPublicKey(jwt.publicKey).build()

		@Bean
		@Primary
		fun testRestTemplate(): RestTemplate = RestTemplate()
	}
}