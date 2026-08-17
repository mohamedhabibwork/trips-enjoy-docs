package com.trips_enjoy.notification.api.admin

import com.trips_enjoy.notification.application.NotificationAdminAuditPublisher
import com.trips_enjoy.notification.api.DeliveryStateResponse
import com.trips_enjoy.notification.domain.DeliveryRepository
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * GET /v1/admin/deliveries — list deliveries (PII access requires
 * `X-Audit-Reason` per SECURITY_ARCHITECTURE.md §admin).
 */
@RestController
@RequestMapping("/v1/admin/deliveries")
@PreAuthorize(
	"hasAnyAuthority(" +
		"'ROLE_notification.admin', 'ROLE_notification_ops', " +
		"'ROLE_platform.admin', 'ROLE_platform.super_admin', " +
		"'ROLE_platform.support', 'ROLE_support_agent'" +
	")",
)
class AdminDeliveryController(
	private val deliveries: DeliveryRepository,
	private val events: NotificationAdminAuditPublisher,
) {

	@GetMapping
	fun list(
		@RequestParam("user_id", required = false) userId: UUID?,
		@RequestParam("limit", defaultValue = "50") limit: Int,
		@AuthenticationPrincipal jwt: Jwt,
		@RequestHeader("X-Request-Id", required = false) requestId: String?,
		@RequestHeader("X-Audit-Reason", required = false) reason: String?,
	): ResponseEntity<DeliveryListResponse> {
		val rows = deliveries.findAll().let { all ->
			if (userId != null) all.filter { it.userId == userId } else all
		}.take(limit.coerceIn(1, 100)).map(DeliveryStateResponse.Companion::from)
		events.publish(
			actorId = UUID.fromString(jwt.getClaimAsString("sub")),
			actorUsername = jwt.getClaimAsString("preferred_username"),
			actorRoles = emptyList(),
			endpoint = "GET /v1/admin/deliveries",
			action = "list",
			targetResource = userId?.toString() ?: "(all)",
			reasonCode = reason,
			requestId = requestId,
			traceId = null,
			result = "ok",
			durationMs = 0L,
		)
		return ResponseEntity.ok(DeliveryListResponse(deliveries = rows))
	}
}