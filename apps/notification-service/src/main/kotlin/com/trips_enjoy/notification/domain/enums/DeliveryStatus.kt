package com.trips_enjoy.notification.domain.enums

/**
 * Delivery state machine per docs/services/notification-service/SRS.md
 * (state diagram). Stored as TEXT in the DB and constrained by the
 * `deliveries_status_check` CHECK in V4.
 *
 *  queued → rendering → suppressed | sending → sent → delivered → read
 *                                       → retrying → sending | failed
 *  sent → failed (post-ack provider failure)
 */
enum class DeliveryStatus(val value: String) {
	QUEUED("queued"),
	RENDERING("rendering"),
	SUPPRESSED("suppressed"),
	SENDING("sending"),
	SENT("sent"),
	DELIVERED("delivered"),
	READ("read"),
	FAILED("failed");

	companion object {
		fun fromValue(value: String): DeliveryStatus = entries.firstOrNull { it.value == value }
			?: throw IllegalArgumentException("Unknown delivery status: $value")
	}
}