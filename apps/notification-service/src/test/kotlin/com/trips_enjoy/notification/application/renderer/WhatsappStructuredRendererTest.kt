package com.trips_enjoy.notification.application.renderer

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.trips_enjoy.notification.api.ApiException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class WhatsappStructuredRendererTest {

	private lateinit var mapper: ObjectMapper
	private lateinit var renderer: WhatsappStructuredRenderer

	private val tripCompletedSource = """
		{
		  "header": { "type": "text", "text": "Trip to {{1}}" },
		  "body":   { "type": "text", "text": "Hi {{2}}, your trip {{3}} ended at {{4}}." },
		  "footer": { "type": "text", "text": "{{platform_brand}}" },
		  "buttons": [
		    { "type": "url",   "text": "Receipt", "url": "https://app.trips-enjoy.com/r/{{5}}" }
		  ],
		  "variables": [
		    { "key": "destination_address", "index": 1 },
		    { "key": "user_first_name",     "index": 2 },
		    { "key": "trip_id",             "index": 3 },
		    { "key": "arrived_at",          "index": 4 },
		    { "key": "receipt_code",        "index": 5 }
		  ]
		}
	""".trimIndent()

	@BeforeEach
	fun setUp() {
		mapper = ObjectMapper().registerModule(KotlinModule.Builder().build())
		renderer = WhatsappStructuredRenderer(mapper)
	}

	@Test
	fun `numbered placeholders are substituted`() {
		val out = renderer.render(
			templateSourceJson = tripCompletedSource,
			whatsappVariables = mapOf(
				"{1}" to "Dubai Marina",
				"{2}" to "Alex",
				"{3}" to "trip-123",
				"{4}" to "14:32",
				"{5}" to "R-9F2K1",
				"platform_brand" to "TripsEnjoy",
			),
		)
		val parsed = mapper.readTree(out)
		assertEquals("Trip to Dubai Marina", parsed.get("header").get("text").asText())
		assertEquals("Hi Alex, your trip trip-123 ended at 14:32.", parsed.get("body").get("text").asText())
		assertEquals("TripsEnjoy", parsed.get("footer").get("text").asText())
		assertEquals("Receipt", parsed.get("buttons").get(0).get("text").asText())
		assertEquals("https://app.trips-enjoy.com/r/R-9F2K1", parsed.get("buttons").get(0).get("url").asText())
	}

	@Test
	fun `missing index for a referenced placeholder throws RENDER_MISSING_INDEX`() {
		val ex = assertThrows(ApiException::class.java) {
			renderer.render(
				templateSourceJson = tripCompletedSource,
				whatsappVariables = mapOf(
					"{1}" to "Dubai Marina",
					"{2}" to "Alex",
					"{3}" to "trip-123",
					// {4} missing on purpose
					"{5}" to "R-9F2K1",
				),
			)
		}
		assertEquals("RENDER_MISSING_INDEX", ex.code)
	}

	@Test
	fun `validation enforces required_variables match body_structured variables`() {
		val required = listOf("destination_address", "user_first_name", "trip_id", "arrived_at", "receipt_code")
		renderer.validateRequiredVariables(tripCompletedSource, required)
		// Negative case: required includes a key NOT declared in body_structured.variables[]
		val negative = assertThrows(ApiException::class.java) {
			renderer.validateRequiredVariables(
				tripCompletedSource,
				listOf("destination_address", "user_first_name", "trip_id", "arrived_at", "receipt_code", "extra_undeclared_key"),
			)
		}
		assertEquals("TEMPLATE_VALIDATION_FAILED", negative.code)
	}

	@Test
	fun `assertStructured rejects plain templates`() {
		val ex = assertThrows(ApiException::class.java) {
			WhatsappStructuredRenderer.assertStructured(
				com.trips_enjoy.notification.domain.enums.TemplateType.PLAIN,
			)
		}
		assertEquals("TEMPLATE_HAS_NO_BODY_STRUCTURED", ex.code)
	}
}