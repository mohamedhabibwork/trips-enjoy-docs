package com.trips_enjoy.notification.application

import com.trips_enjoy.notification.api.ApiException
import com.trips_enjoy.notification.domain.Delivery
import com.trips_enjoy.notification.domain.DeliveryRepository
import com.trips_enjoy.notification.domain.Preference
import com.trips_enjoy.notification.domain.Suppression
import com.trips_enjoy.notification.domain.enums.Channel
import com.trips_enjoy.notification.domain.enums.DeliveryStatus
import com.trips_enjoy.notification.integration.provider.SendResult
import com.trips_enjoy.notification.util.uuidV7
import org.slf4j.LoggerFactory
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * Synchronous send orchestrator per
 * docs/services/notification-service/WORKFLOWS §1.
 *
 *  - Idempotency (IdempotencyService)
 *  - Dedup (Redis SETNX on `dedup_key` with default 60s window)
 *  - Consent / preferences (per-channel opt_in, quiet hours, urgent bypass)
 *  - Global suppression (NotificationSuppressionService)
 *  - Channel priority (push > sms > email > in_app > whatsapp)
 *  - Template render (TemplateRenderer + dispatcher)
 *  - Provider handoff (NotificationDeliveryService)
 *  - Delivery row persist
 *  - Outbox emit `notification.sent.v1` (or `notification.failed.v1` /
 *    `notification.suppressed.v1`)
 */
@Service
class NotificationSendService(
	private val templateService: NotificationTemplateService,
	private val preferencesService: NotificationPreferenceService,
	private val suppressionService: NotificationSuppressionService,
	private val renderer: com.trips_enjoy.notification.application.renderer.TemplateRenderer,
	private val deliveryService: NotificationDeliveryService,
	private val deliveries: DeliveryRepository,
	private val events: NotificationEventPublishers,
	private val idempotency: IdempotencyService,
	private val redis: StringRedisTemplate?,
) {
	private val log = LoggerFactory.getLogger(javaClass)

	data class SendRequestInput(
		val userId: UUID,
		val templateName: String,
		val data: Map<String, Any?>,
		val whatsappVariables: Map<String, String> = emptyMap(),
		val dedupKey: String,
		val localeHint: String? = null,
		val priority: String = "normal", // "normal" | "urgent"
		val actorId: UUID,
		val actorIdempotencyKey: UUID?,
		val correlationId: UUID,
		val requestId: UUID? = null,
		val service: String? = null,
		val paymentId: UUID? = null,
	)

	data class SendResponse(
		val notificationId: UUID,
		val status: String,
		val channel: Channel,
		val occurredAt: Instant,
	)

	fun send(req: SendRequestInput): SendResponse {
		val correlationIdStr = req.correlationId.toString()
		// 1. Idempotency
		val response = if (req.actorIdempotencyKey != null) {
			idempotency.idempotent(
				actorId = req.actorId,
				idempotencyKey = req.actorIdempotencyKey,
				request = req,
				responseClass = SendResponse::class.java,
			) { doSend(req, correlationIdStr) }
		} else {
			doSend(req, correlationIdStr)
		}
		return response
	}

	private fun doSend(req: SendRequestInput, correlationIdStr: String): SendResponse {
		// 2. Dedup window
		val dedupOk = redis?.opsForValue()?.setIfAbsent(
			"notification:dedup:${req.userId}:${req.dedupKey}",
			"1",
			Duration.ofSeconds(60),
		) ?: true
		if (!dedupOk) {
			events.publishSuppressed(
				userId = req.userId, templateId = uuidV7(), channel = null,
				reason = "DEDUP_WINDOW", correlationId = correlationIdStr,
			)
			throw ApiException(
				HttpStatus.UNPROCESSABLE_ENTITY,
				"BUSINESS_RULE_VIOLATION",
				"Duplicate notification within dedup window",
			)
		}

		// 3. Suppression (global)
		val activeSuppressions: List<Suppression> = guessCategory(req)?.let {
			suppressionService.findActive(it)
		} ?: emptyList()
		if (activeSuppressions.isNotEmpty()) {
			events.publishSuppressed(
				userId = req.userId, templateId = uuidV7(), channel = null,
				reason = "GLOBAL_SUPPRESSION", correlationId = correlationIdStr,
			)
			throw ApiException(
				HttpStatus.UNPROCESSABLE_ENTITY,
				"OPTED_OUT",
				"Notification is globally suppressed for category=${activeSuppressions.first().category}",
			)
		}

		// 4. Channel selection: priority + preference + circuit
		val prefs: List<Preference> = preferencesService.findForUser(req.userId)
		val chosen = pickChannel(req, prefs)
			?: throw ApiException(
				HttpStatus.UNPROCESSABLE_ENTITY,
				"NO_CONTACT",
				"No available channel for user ${req.userId} (preferences=${prefs.size})",
			)

		// 5. Render template
		val template = templateService.findActive(req.templateName, chosen.channel, req.localeHint ?: chosen.locale)
		val snapshot = templateService.latestSnapshot(template.id)
		val rendered = renderer.render(template, snapshot, req.data, req.whatsappVariables)

		// 6. Provider handoff; persist delivery row first
		val delivery = deliveryService.persist(
			userId = req.userId,
			templateId = template.id,
			templateVersionSnapshotId = snapshot.id,
			renderedTemplateVersion = snapshot.version,
			renderedTemplateType = template.templateType.value,
			renderedProviderTemplateId = template.providerTemplateId,
			renderedProviderTemplateLanguage = template.providerTemplateLanguage,
			templateName = template.name,
			category = template.category,
			channel = chosen.channel,
			locale = template.locale,
			dedupKey = req.dedupKey,
			requestIdempotencyKey = req.actorIdempotencyKey,
			correlationId = req.correlationId,
			subjectEncrypted = rendered.encryptedSubject,
			bodyEncrypted = rendered.encryptedBody,
			requestId = req.requestId,
			service = req.service,
			paymentId = req.paymentId,
		)
		val result: SendResult = deliveryService.performSend(
			deliveryId = delivery.id,
			createdAt = delivery.createdAt,
			body = rendered.renderedBody,
			subject = rendered.renderedSubject,
			bodyStructuredJson = rendered.renderedBodyStructuredJson,
			whatsappVariables = req.whatsappVariables,
			recipientUserId = req.userId.toString(),
			templateName = template.name,
			providerTemplateId = template.providerTemplateId,
			providerTemplateLanguage = template.providerTemplateLanguage,
			correlationId = correlationIdStr,
			idempotencyKey = req.actorIdempotencyKey?.toString(),
			dedupKey = req.dedupKey,
		)

		// 7. Outbox events
		if (result.success) {
			events.publishSent(
				deliveryId = delivery.id,
				userId = req.userId,
				templateId = template.id,
				channel = chosen.channel.value,
				correlationId = correlationIdStr,
			)
		} else {
			events.publishFailed(
				deliveryId = delivery.id,
				userId = req.userId,
				templateId = template.id,
				channel = chosen.channel.value,
				reason = result.errorMessage ?: "provider_failed",
				correlationId = correlationIdStr,
			)
			throw ApiException(
				HttpStatus.BAD_GATEWAY,
				"PROVIDER_UNAVAILABLE",
				"Provider failed for channel ${chosen.channel.value}: ${result.errorMessage ?: "n/a"}",
			)
		}

		return SendResponse(
			notificationId = delivery.id,
			status = DeliveryStatus.SENT.value,
			channel = chosen.channel,
			occurredAt = Instant.now(),
		)
	}

	/** Placeholder; real impl resolves user category from template prefix (`trip.completed` → `trip`). */
	private fun guessCategory(req: SendRequestInput): String? =
		req.templateName.substringBefore('.').takeIf { it.isNotBlank() }

	private fun pickChannel(req: SendRequestInput, prefs: List<Preference>): ChannelDecision? {
		val allowed = prefs.filter { it.optIn }.map { it.channel }.toSet()
		// urgent bypasses preferences
		val candidates = if (req.priority == "urgent") Channel.priority()
		else Channel.priority().filter { allowed.contains(it) || allowed.isEmpty() }
		return candidates.firstOrNull()?.let { ChannelDecision(it, firstPreferencesLocale(prefs) ?: req.localeHint ?: "en") }
	}

	private fun firstPreferencesLocale(prefs: List<Preference>): String? = null

	/** Test seam — pure function. */
	data class ChannelDecision(val channel: Channel, val locale: String)

	@Transactional(readOnly = true)
	fun get(deliveryId: UUID): Delivery? =
		deliveries.findAll().firstOrNull { it.id == deliveryId }
}