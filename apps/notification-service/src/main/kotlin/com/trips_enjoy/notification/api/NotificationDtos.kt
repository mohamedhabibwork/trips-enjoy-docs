package com.trips_enjoy.notification.api

import com.fasterxml.jackson.annotation.JsonInclude
import com.trips_enjoy.notification.domain.Delivery
import com.trips_enjoy.notification.domain.Preference
import com.trips_enjoy.notification.domain.enums.Channel
import java.time.Instant
import java.util.UUID

/** Snake_case field names per docs/shared/CONVENTIONS.md §7. */

@JsonInclude(JsonInclude.Include.NON_NULL)
data class SendNotificationRequest(
	val user_id: UUID,
	val template_id: UUID? = null,
	val template_name: String? = null,
	val data: Map<String, Any?> = emptyMap(),
	val whatsapp_variables: Map<String, String> = emptyMap(),
	val dedup_key: String,
	val category: String,
	val locale_hint: String? = null,
	val priority: String = "normal", // normal | urgent
	val request_id: UUID? = null,
	val service: String? = null,
	val payment_id: UUID? = null,
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class SendNotificationResponse(
	val notification_id: UUID,
	val status: String,
	val channel: String,
	val occurred_at: Instant,
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class DeliveryStateResponse(
	val notification_id: UUID,
	val user_id: UUID?,
	val template_id: UUID?,
	val template_name: String,
	val category: String,
	val channel: String,
	val locale: String,
	val status: String,
	val attempt: Int,
	val correlation_id: UUID,
	val template_version_snapshot_id: UUID?,
	val rendered_provider_template_id: String?,
	val created_at: Instant,
	val sent_at: Instant?,
	val delivered_at: Instant?,
	val read_at: Instant?,
	val failed_at: Instant?,
) {
	companion object {
		fun from(d: Delivery): DeliveryStateResponse = DeliveryStateResponse(
			notification_id = d.id,
			user_id = if (d.userId.toString() == "00000000-0000-0000-0000-000000000000") null else d.userId,
			template_id = d.templateId,
			template_name = d.templateName,
			category = d.category,
			channel = d.channel.value,
			locale = d.locale,
			status = d.status.value,
			attempt = d.attempt,
			correlation_id = d.correlationId,
			template_version_snapshot_id = d.templateVersionSnapshotId,
			rendered_provider_template_id = d.renderedProviderTemplateId,
			created_at = d.createdAt,
			sent_at = d.sentAt,
			delivered_at = d.deliveredAt,
			read_at = d.readAt,
			failed_at = d.failedAt,
		)
	}
}

@JsonInclude(JsonInclude.Include.NON_NULL)
data class PreferenceResponse(
	val user_id: UUID,
	val entries: List<PreferenceEntry>,
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class PreferenceEntry(
	val category: String,
	val channel: String,
	val opt_in: Boolean,
	val quiet_hours_start: Int?,
	val quiet_hours_end: Int?,
	val timezone: String,
) {
	companion object {
		fun from(p: Preference): PreferenceEntry = PreferenceEntry(
			category = p.category,
			channel = p.channel.value,
			opt_in = p.optIn,
			quiet_hours_start = p.quietHoursStart,
			quiet_hours_end = p.quietHoursEnd,
			timezone = p.timezone,
		)
	}
}

@JsonInclude(JsonInclude.Include.NON_NULL)
data class UpsertPreferencesRequest(
	val entries: List<UpsertPreferencesEntry>,
)

data class UpsertPreferencesEntry(
	val category: String,
	val channel: String,
	val opt_in: Boolean = true,
	val quiet_hours_start: Int? = null,
	val quiet_hours_end: Int? = null,
	val timezone: String = "UTC",
)

/** Convenience extractor for the orchestrator: pick a channel by its value text. */
fun Channel.Companion.fromWire(value: String): Channel = Channel.fromValue(value)