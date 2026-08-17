package com.trips_enjoy.notification.domain.enums

/**
 * Delivery channels per docs/services/notification-service/BRD.md §channel-matrix
 * and ERD.md. Order is the canonical channel priority (push > sms > email >
 * in_app > whatsapp); see NotificationSendService for channel selection.
 */
enum class Channel(val value: String) {
	PUSH("push"),
	SMS("sms"),
	EMAIL("email"),
	IN_APP("in_app"),
	WHATSAPP("whatsapp");

	companion object {
		fun fromValue(value: String): Channel = entries.firstOrNull { it.value == value }
			?: throw IllegalArgumentException("Unknown channel: $value")

		/** Default priority order for channel selection / fallback. */
		fun priority(): List<Channel> = listOf(PUSH, SMS, EMAIL, IN_APP, WHATSAPP)
	}
}