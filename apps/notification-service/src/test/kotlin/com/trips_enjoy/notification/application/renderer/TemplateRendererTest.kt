package com.trips_enjoy.notification.application.renderer

import com.trips_enjoy.notification.domain.Template
import com.trips_enjoy.notification.domain.TemplateHistory
import com.trips_enjoy.notification.domain.enums.Channel
import com.trips_enjoy.notification.domain.enums.TemplateProviderStatus
import com.trips_enjoy.notification.domain.enums.TemplateType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class TemplateRendererTest {

	private lateinit var plain: HandlebarsRenderer
	private lateinit var whatsapp: WhatsappStructuredRenderer
	private lateinit var renderer: TemplateRenderer

	@BeforeEach
	fun setUp() {
		plain = HandlebarsRenderer()
		whatsapp = WhatsappStructuredRenderer(com.fasterxml.jackson.databind.ObjectMapper().registerModule(com.fasterxml.jackson.module.kotlin.KotlinModule.Builder().build()))
		renderer = TemplateRenderer(plain, whatsapp)
	}

	@Test
	fun `plain template renders subject and body and binds encrypted blobs`() {
		val template = template(
			id = UUID.randomUUID(),
			type = TemplateType.PLAIN,
			body = "Hi {{name}}, trip {{trip_id}} is complete.",
			subject = "Your trip {{trip_id}}",
		)
		val snapshot = history(template.id, template.version, Channel.PUSH)
		val result = renderer.render(template, snapshot, mapOf("name" to "Alex", "trip_id" to "trip-1"))
		assertEquals("Hi Alex, trip trip-1 is complete.", result.renderedBody)
		assertEquals("Your trip trip-1", result.renderedSubject)
		assertNull(result.renderedBodyStructuredJson)
		assertNotNull(result.encryptedSubject)
		assertNotNull(result.encryptedBody)
	}

	@Test
	fun `whatsapp structured template renders body_structured via JSON walker`() {
		val src = """{"header":{"type":"text","text":"Trip {{1}}"},"body":{"type":"text","text":"Hi {{2}}"},"variables":[{"key":"destination_address","index":1},{"key":"user_first_name","index":2}]}"""
		val template = template(
			id = UUID.randomUUID(),
			type = TemplateType.WHATSAPP_STRUCTURED,
			bodyStructured = src,
			providerTemplateId = "tpl_ABC123",
			providerTemplateLanguage = "en_US",
		)
		val snapshot = history(template.id, template.version, Channel.WHATSAPP)
		val result = renderer.render(
			template,
			snapshot,
			data = mapOf("unused" to "ignored"),
			whatsappVariables = mapOf("{1}" to "Dubai Marina", "{2}" to "Alex"),
		)
		assertEquals(null, result.renderedSubject)
		assertNotNull(result.renderedBodyStructuredJson)
		val parsed = com.fasterxml.jackson.databind.ObjectMapper().readTree(result.renderedBodyStructuredJson)
		assertEquals("Trip Dubai Marina", parsed.get("header").get("text").asText())
		assertEquals("Hi Alex", parsed.get("body").get("text").asText())
	}

	private fun template(
		id: UUID,
		type: TemplateType,
		body: String? = null,
		subject: String? = null,
		bodyStructured: String? = null,
		providerTemplateId: String? = null,
		providerTemplateLanguage: String? = null,
	): Template {
		val now = Instant.now()
		return Template(
			id = id, name = "trip.completed", category = "trip",
			channel = Channel.PUSH, locale = "en",
			subject = subject, body = body,
			templateType = type, bodyStructured = bodyStructured,
			providerTemplateId = providerTemplateId,
			providerTemplateLanguage = providerTemplateLanguage,
			requiredVariables = emptyList(),
			createdAt = now, updatedAt = now,
		)
	}

	private fun history(templateId: UUID, version: Int, channel: Channel): TemplateHistory =
		TemplateHistory(
			id = UUID.randomUUID(), templateId = templateId,
			revisionNo = 1, version = version,
			name = "trip.completed", category = "trip",
			channel = channel, locale = "en",
			templateType = TemplateType.PLAIN,
			providerTemplateStatus = TemplateProviderStatus.APPROVED,
			diffSummary = "{}",
			publishedBy = UUID.randomUUID(),
			createdAt = Instant.now(),
		)
}