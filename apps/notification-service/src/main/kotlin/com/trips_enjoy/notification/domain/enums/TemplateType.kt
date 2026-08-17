package com.trips_enjoy.notification.domain.enums

/**
 * Template discriminator (V2 `templates_type_check`). `plain` uses the
 * `body` TEXT column (Handlebars); `whatsapp_structured` uses the
 * `body_structured` JSONB column mirroring WhatsApp Business API components.
 *
 * The DB-level `templates_body_discriminator_chk` constraint enforces mutual
 * exclusivity — exactly one of `body` / `body_structured` is non-null.
 */
enum class TemplateType(val value: String) {
	PLAIN("plain"),
	WHATSAPP_STRUCTURED("whatsapp_structured");

	companion object {
		fun fromValue(value: String): TemplateType = entries.firstOrNull { it.value == value }
			?: throw IllegalArgumentException("Unknown template type: $value")
	}
}