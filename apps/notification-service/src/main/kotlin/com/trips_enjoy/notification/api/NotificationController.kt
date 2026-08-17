package com.trips_enjoy.notification.api

import com.trips_enjoy.notification.application.NotificationSendService
import com.trips_enjoy.notification.domain.enums.Channel
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * POST /v1/notifications (synchronous send)
 * GET  /v1/notifications/{id} (read delivery state)
 *
 * Per docs/services/notification-service/INTEGRATION.md §1 + SRS.md §FR--001, 010, 016.
 */
@RestController
@RequestMapping("/v1/notifications")
class NotificationController(private val sendService: NotificationSendService) {

	@PostMapping
	@PreAuthorize("hasAnyAuthority('ROLE_service', 'ROLE_platform.admin', 'ROLE_platform.super_admin')")
	fun send(
		@AuthenticationPrincipal jwt: Jwt,
		@RequestHeader("Idempotency-Key", required = false) idempotencyKey: UUID?,
		@RequestHeader("X-Request-Id", required = false) requestId: String?,
		@RequestBody request: SendNotificationRequest,
	): ResponseEntity<SendNotificationResponse> {
		val correlationId = UUID.fromString(requestId ?: jwt.getClaimAsString("sub"))
		val actorId = UUID.fromString(jwt.getClaimAsString("sub"))
		val response = sendService.send(
			NotificationSendService.SendRequestInput(
				userId = request.user_id,
				templateName = request.template_name ?: throw com.trips_enjoy.notification.api.ApiException(
					HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "template_name is required",
				),
				data = request.data,
				whatsappVariables = request.whatsapp_variables,
				dedupKey = request.dedup_key,
				localeHint = request.locale_hint,
				priority = request.priority,
				actorId = actorId,
				actorIdempotencyKey = idempotencyKey,
				correlationId = correlationId,
				requestId = request.request_id,
				service = request.service,
				paymentId = request.payment_id,
			),
		)
		return ResponseEntity.accepted().body(
			SendNotificationResponse(
				notification_id = response.notificationId,
				status = response.status,
				channel = response.channel.value,
				occurred_at = response.occurredAt,
			),
		)
	}

	@GetMapping("/{id}")
	@PreAuthorize("isAuthenticated()")
	fun get(
		@PathVariable id: UUID,
		@AuthenticationPrincipal jwt: Jwt,
	): ResponseEntity<DeliveryStateResponse> {
		val delivery = sendService.get(id)
			?: throw com.trips_enjoy.notification.api.ApiException(
				HttpStatus.NOT_FOUND, "NOT_FOUND", "Delivery $id not found",
			)
		val sub = jwt.getClaimAsString("sub")?.let(UUID::fromString)
		val isAdmin = jwt.getClaimAsMap("realm_access").orEmpty()["roles"]?.let { roles ->
			(roles as? Collection<*>)
				?.filterIsInstance<String>()
				?.any { it in setOf("platform.admin", "platform.super_admin", "platform.support", "notification.support") }
		} ?: false
		if (!isAdmin && sub != null && delivery.userId != sub) {
			throw com.trips_enjoy.notification.api.ApiException(
				HttpStatus.FORBIDDEN, "FORBIDDEN", "Caller is not the recipient of this delivery",
			)
		}
		return ResponseEntity.ok(DeliveryStateResponse.from(delivery))
	}

	/** Helper for Channel parsing in URL paths. */
	@Suppress("unused")
	private fun parseChannel(value: String): Channel = Channel.fromValue(value)
}