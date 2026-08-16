package com.trips_enjoy.notification.api.admin

import com.trips_enjoy.notification.application.NotificationErasureService
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * POST /v1/admin/erasure/{user_id} per
 * docs/services/notification-service/WORKFLOWS §9.4.
 *
 *  - `reason_code` is REQUIRED (PII access).
 *  - `notification.template_history` rows are NEVER touched (no PII).
 *  - `notification.deliveries` rows have user_id / encrypted body nulled.
 *  - `notification.preferences` rows are anonymised.
 *  - Audit emit `audit.notification.erasure.v1`.
 */
@RestController
@RequestMapping("/v1/admin/erasure")
@PreAuthorize(
	"hasAnyAuthority(" +
		"'ROLE_platform.admin', 'ROLE_platform.super_admin', 'ROLE_platform.support', " +
		"'ROLE_support_agent'" +
	")",
)
class AdminErasureController(private val erasure: NotificationErasureService) {

	@PostMapping("/{userId}")
	fun erase(
		@PathVariable userId: UUID,
		@RequestBody request: ErasureRequest,
		@AuthenticationPrincipal jwt: Jwt,
		@RequestHeader("X-Request-Id", required = false) requestId: String?,
		@RequestHeader("X-Audit-Reason", required = false) reasonHeader: String?,
	): ResponseEntity<Map<String, Any?>> {
		val reason = request.reason_code.ifBlank { reasonHeader ?: "n/a" }
		val actorId = UUID.fromString(jwt.getClaimAsString("sub"))
		erasure.erase(
			userId = userId,
			actorId = actorId,
			actorUsername = jwt.getClaimAsString("preferred_username"),
			reasonCode = reason,
			requestId = requestId,
		)
		return ResponseEntity.ok(mapOf("user_id" to userId, "status" to "erased"))
	}
}