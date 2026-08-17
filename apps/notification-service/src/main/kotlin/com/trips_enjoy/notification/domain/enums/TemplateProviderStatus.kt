package com.trips_enjoy.notification.domain.enums

/**
 * WhatsApp provider-template lifecycle status (V2
 * `templates_provider_status_check`). Maps to WhatsApp Business API state
 * machine: draft → submitted → approved | rejected → paused → retired.
 */
enum class TemplateProviderStatus(val value: String) {
	DRAFT("draft"),
	SUBMITTED("submitted"),
	APPROVED("approved"),
	REJECTED("rejected"),
	PAUSED("paused"),
	RETIRED("retired");

	companion object {
		fun fromValue(value: String): TemplateProviderStatus = entries.firstOrNull { it.value == value }
			?: throw IllegalArgumentException("Unknown provider template status: $value")
	}
}