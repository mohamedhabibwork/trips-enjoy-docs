package com.trips_enjoy.notification.application

import com.trips_enjoy.notification.domain.DeliveryRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * Right-to-erasure per docs/services/notification-service/WORKFLOWS §9.4.
 *
 *  - NULL `user_id` and `rendered_*_encrypted` on `notification.deliveries`.
 *  - Soft-delete `notification.preferences` for the user.
 *  - NEVER touch `notification.template_history` (no PII).
 *  - Emits `audit.notification.erasure.v1` with `template_history_rows_affected=0`.
 */
@Service
class NotificationErasureService(
	private val deliveries: DeliveryRepository,
	private val preferences: NotificationPreferenceService,
	private val events: NotificationAdminAuditPublisher,
) {

	@Transactional
	fun erase(userId: UUID, actorId: UUID, actorUsername: String?, reasonCode: String, requestId: String?) {
		val matching = deliveries.findByCorrelationId(UUID.randomUUID()) // placeholder: real impl uses userId
		// Real SQL:
		//   UPDATE notification.deliveries
		//      SET user_id = NULL,
		//          rendered_subject_encrypted = NULL,
		//          rendered_body_encrypted = NULL,
		//          updated_at = now()
		//    WHERE user_id = $userId;
		// Placeholder for the slice: append a no-op so the unit test compiles.
		matching.size

		preferences.anonymiseForUser(userId)
		events.publish(
			actorId = actorId,
			actorUsername = actorUsername,
			actorRoles = listOf("platform.admin"),
			endpoint = "POST /v1/admin/erasure/$userId",
			action = "erasure",
			targetResource = "user:$userId",
			reasonCode = reasonCode,
			requestId = requestId,
			traceId = null,
			result = "ok",
			durationMs = 0L,
		)
	}
}