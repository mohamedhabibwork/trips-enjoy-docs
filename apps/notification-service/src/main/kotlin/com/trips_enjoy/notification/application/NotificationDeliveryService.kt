package com.trips_enjoy.notification.application

import com.trips_enjoy.notification.domain.Delivery
import com.trips_enjoy.notification.domain.DeliveryRepository
import com.trips_enjoy.notification.domain.enums.DeliveryStatus
import com.trips_enjoy.notification.integration.provider.ProviderRegistry
import com.trips_enjoy.notification.integration.provider.SendRequest
import com.trips_enjoy.notification.integration.provider.SendResult
import com.trips_enjoy.notification.util.uuidV7
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

/**
 * Provider handoff per docs/services/notification-service/WORKFLOWS §1 + 3.4.
 *
 *  - Hot path: delivery is already persisted (status=queued), we render, then
 *    call the provider driver registered for the channel, then stamp status
 *    transitions on the same DB row.
 *  - Outbox emission of `notification.sent.v1` happens in
 *    `NotificationSendService` (the orchestrator) on success; failures emit
 *    `notification.failed.v1` and a retry is scheduled by the calling layer.
 */
@Service
class NotificationDeliveryService(
	private val deliveries: DeliveryRepository,
	private val registry: ProviderRegistry,
) {

	@Transactional
	fun performSend(
		deliveryId: UUID,
		createdAt: Instant,
		body: String?,
		subject: String?,
		bodyStructuredJson: String?,
		whatsappVariables: Map<String, String>,
		recipientUserId: String,
		templateName: String,
		providerTemplateId: String?,
		providerTemplateLanguage: String?,
		correlationId: String,
		idempotencyKey: String?,
		dedupKey: String?,
	): SendResult {
		val delivery = deliveries.findByIdAndCreatedAt(deliveryId, createdAt)
			?: return SendResult(false, null, 500, errorMessage = "delivery $deliveryId/$createdAt not found")
		delivery.status = DeliveryStatus.SENDING
		delivery.attempt = delivery.attempt + 1
		delivery.updatedAt = Instant.now()
		val driver = registry.firstHealthy(delivery.channel)
		val result = driver.send(
			SendRequest(
				recipientUserId = recipientUserId,
				templateName = templateName,
				subject = subject,
				body = body,
				bodyStructuredJson = bodyStructuredJson,
				idempotencyKey = idempotencyKey,
				dedupKey = dedupKey,
				whatsappVariables = whatsappVariables,
				providerTemplateId = providerTemplateId,
				providerTemplateLanguage = providerTemplateLanguage,
				correlationId = correlationId,
			),
		)
		delivery.gatewayRequestId = result.providerMessageId
		delivery.gatewayResponseStatus = result.rawStatusCode
		delivery.gatewayResponseBody = result.rawResponseBody?.let { mapper -> mapper.take(4000) }
		if (result.success) {
			delivery.status = DeliveryStatus.SENT
			delivery.sentAt = Instant.now()
			delivery.updatedAt = Instant.now()
		} else {
			delivery.failureReason = result.errorMessage ?: "provider_failed"
			delivery.failedAt = Instant.now()
			delivery.status = DeliveryStatus.FAILED
			delivery.updatedAt = Instant.now()
		}
		return result
	}

	/** Webhook reconcile entry — provider confirms delivery. */
	@Transactional
	fun markDelivered(deliveryId: UUID, createdAt: Instant): Delivery? {
		val delivery = deliveries.findByIdAndCreatedAt(deliveryId, createdAt) ?: return null
		delivery.status = DeliveryStatus.DELIVERED
		delivery.deliveredAt = Instant.now()
		delivery.updatedAt = Instant.now()
		return delivery
	}

	/** Webhook reconcile entry — WhatsApp read receipt. */
	@Transactional
	fun markRead(deliveryId: UUID, createdAt: Instant): Delivery? {
		val delivery = deliveries.findByIdAndCreatedAt(deliveryId, createdAt) ?: return null
		if (delivery.channel != com.trips_enjoy.notification.domain.enums.Channel.WHATSAPP) return delivery
		delivery.status = DeliveryStatus.READ
		delivery.readAt = Instant.now()
		delivery.updatedAt = Instant.now()
		return delivery
	}

	/** Persist a freshly-rendered delivery row before the provider call. */
	@Transactional
	fun persist(
		deliveryId: UUID = uuidV7(),
		userId: UUID,
		templateId: UUID,
		templateVersionSnapshotId: UUID?,
		renderedTemplateVersion: Int?,
		renderedTemplateType: String?,
		renderedProviderTemplateId: String?,
		renderedProviderTemplateLanguage: String?,
		templateName: String,
		category: String,
		channel: com.trips_enjoy.notification.domain.enums.Channel,
		locale: String,
		dedupKey: String,
		requestIdempotencyKey: UUID?,
		correlationId: UUID,
		subjectEncrypted: ByteArray?,
		bodyEncrypted: ByteArray?,
		requestId: UUID? = null,
		service: String? = null,
		paymentId: UUID? = null,
	): Delivery {
		val delivery = Delivery(
			id = deliveryId,
			userId = userId,
			templateId = templateId,
			templateVersionSnapshotId = templateVersionSnapshotId,
			renderedTemplateVersion = renderedTemplateVersion,
			renderedTemplateType = renderedTemplateType,
			renderedProviderTemplateId = renderedProviderTemplateId,
			renderedProviderTemplateLanguage = renderedProviderTemplateLanguage,
			templateName = templateName,
			category = category,
			channel = channel,
			locale = locale,
			status = DeliveryStatus.QUEUED,
			dedupKey = dedupKey,
			requestIdempotencyKey = requestIdempotencyKey,
			correlationId = correlationId,
			renderedSubjectEncrypted = subjectEncrypted,
			renderedBodyEncrypted = bodyEncrypted,
			requestId = requestId,
			service = service,
			paymentId = paymentId,
		)
		return deliveries.save(delivery)
	}
}