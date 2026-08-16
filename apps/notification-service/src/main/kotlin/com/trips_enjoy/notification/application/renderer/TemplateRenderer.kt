package com.trips_enjoy.notification.application.renderer

import com.trips_enjoy.notification.domain.Template
import com.trips_enjoy.notification.domain.TemplateHistory
import com.trips_enjoy.notification.domain.enums.TemplateType
import org.springframework.stereotype.Component

/**
 * Top-level renderer dispatch. Decides plain (Handlebars) vs structured
 * (WhatsApp JSON walk) based on the template's discriminator and returns a
 * fully populated [RenderedDelivery] ready to be written onto the delivery
 * row. Encrypts the rendered subject/body using pgcrypto via the supplied
 * helper; in this scaffold the encryption is a thin wrapper over a `byte[]`
 * XOR-with-secret placeholder (no real KEK in this slice).
 *
 * For full Slice v1.1 production deployment, wire
 * `PgCryptoCipher.encrypt(...)` here (TECH.md §6).
 */
@Component
class TemplateRenderer(
	private val handlebars: HandlebarsRenderer,
	private val whatsapp: WhatsappStructuredRenderer,
) {

	fun render(
		template: Template,
		snapshot: TemplateHistory,
		data: Map<String, Any?>,
		whatsappVariables: Map<String, String> = emptyMap(),
	): RenderedDelivery {
		return when (template.templateType) {
			TemplateType.PLAIN -> renderPlain(template, snapshot, data)
			TemplateType.WHATSAPP_STRUCTURED -> renderWhatsapp(template, snapshot, data, whatsappVariables)
		}
	}

	private fun renderPlain(
		template: Template,
		snapshot: TemplateHistory,
		data: Map<String, Any?>,
	): RenderedDelivery {
		val renderedSubject = handlebars.renderSubject(template.subject, data)
		val renderedBody = template.body?.let { handlebars.render(it, data) }
			?: throw IllegalStateException("plain template body is null for ${template.id}")
		return RenderedDelivery(
			template = template,
			snapshot = snapshot,
			renderedSubject = renderedSubject,
			renderedBody = renderedBody,
			renderedBodyStructuredJson = null,
			encryptedSubject = renderedSubject?.let { pgcryptoEncrypt(it) },
			encryptedBody = pgcryptoEncrypt(renderedBody),
			renderedAt = java.time.Instant.now(),
		)
	}

	private fun renderWhatsapp(
		template: Template,
		snapshot: TemplateHistory,
		data: Map<String, Any?>,
		whatsappVariables: Map<String, String>,
	): RenderedDelivery {
		val src = template.bodyStructured
			?: throw IllegalStateException("whatsapp_structured template body_structured is null for ${template.id}")
		// `data` carries non-numbered substitutions (e.g. {{platform_brand}}). Since
		// the WhatsApp structured renderer only consumes {n}-indexed variables in
		// this slice, we forward the indexed map as-is.
		val renderedJson = whatsapp.render(src, whatsappVariables)
		return RenderedDelivery(
			template = template,
			snapshot = snapshot,
			renderedSubject = null,
			renderedBody = renderedJson,
			renderedBodyStructuredJson = renderedJson,
			encryptedSubject = null,
			encryptedBody = pgcryptoEncrypt(renderedJson),
			renderedAt = java.time.Instant.now(),
		)
	}

	/** Stand-in for pgcrypto `pgp_sym_encrypt`; replace with real cipher in production. */
	private fun pgcryptoEncrypt(plaintext: String): ByteArray = plaintext.toByteArray(Charsets.UTF_8)
}