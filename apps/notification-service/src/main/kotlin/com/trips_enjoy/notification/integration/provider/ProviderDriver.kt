package com.trips_enjoy.notification.integration.provider

import com.trips_enjoy.notification.domain.enums.Channel

/**
 * Provider-driver abstraction per docs/shared/INTEGRATION.md §provider-plug-in
 * and TECH.md (provider ACL absorbed into notification-service).
 *
 *  - Real production: each channel has 1+ providers (Twilio, MessageBird,
 *    Meta Cloud, FCM, APNs, SMTP, SendGrid, etc.). Provider selection is
 *    per-channel via `ProviderRegistry` and may route around a tripped
 *    circuit.
 *  - This scaffold: each channel has 1 `NoopProviderDriver` stub that returns
 *    a successful `SendResult` after a synthetic delay. The boundary is real
 *    and replaceable.
 */
interface ProviderDriver {

	/** Stable driver identifier (e.g. `noop`, `twilio`, `meta_cloud`). */
	val name: String

	/** The channel this driver implements. */
	val channel: Channel

	/**
	 * Perform the actual send. Implementations MUST be idempotent on
	 * `request.idempotencyKey` (if provided) and `request.dedupKey`.
	 */
	fun send(request: SendRequest): SendResult

	/** Health check used by the consumer/reconcile path. */
	fun healthy(): Boolean = true
}

data class SendRequest(
	val recipientUserId: String,
	val templateName: String,
	val subject: String?,
	val body: String?,
	val bodyStructuredJson: String?,
	val idempotencyKey: String?,
	val dedupKey: String?,
	/** WhatsApp-only; mirror of `body_structured.variables[]`. */
	val whatsappVariables: Map<String, String> = emptyMap(),
	/** WhatsApp-only; provider-registered template id. */
	val providerTemplateId: String? = null,
	val providerTemplateLanguage: String? = null,
	val correlationId: String,
)

data class SendResult(
	val success: Boolean,
	val providerMessageId: String?,
	val rawStatusCode: Int?,
	val rawResponseBody: String? = null,
	val errorMessage: String? = null,
)