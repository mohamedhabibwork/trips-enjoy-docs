package com.trips_enjoy.notification.api.admin

import com.trips_enjoy.notification.application.NotificationAdminAuditPublisher
import com.trips_enjoy.notification.application.NotificationSuppressionService
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping("/v1/admin/suppressions")
@PreAuthorize(
	"hasAnyAuthority(" +
		"'ROLE_notification.admin', 'ROLE_notification_ops', " +
		"'ROLE_platform.admin', 'ROLE_platform.super_admin'" +
	")",
)
class AdminSuppressionController(
	private val suppressions: NotificationSuppressionService,
	private val events: NotificationAdminAuditPublisher,
) {

	@PostMapping
	fun create(
		@AuthenticationPrincipal jwt: Jwt,
		@RequestHeader("X-Request-Id", required = false) requestId: String?,
		@RequestBody req: CreateSuppressionRequest,
	): ResponseEntity<SuppressionResponse> {
		val actor = UUID.fromString(jwt.getClaimAsString("sub"))
		val s = suppressions.create(req.category, req.reason, req.expires_at, actor)
		emit(requestId, jwt, "POST /v1/admin/suppressions", "create", s.id.toString())
		return ResponseEntity.status(201).body(
			SuppressionResponse(
				id = s.id, category = s.category, reason = s.reason,
				expires_at = s.expiresAt, created_at = s.createdAt, created_by = s.createdBy,
			),
		)
	}

	@GetMapping
	fun list(): ResponseEntity<Map<String, Any>> {
		val rows = suppressions.list().map {
			SuppressionResponse(
				id = it.id, category = it.category, reason = it.reason,
				expires_at = it.expiresAt, created_at = it.createdAt, created_by = it.createdBy,
			)
		}
		return ResponseEntity.ok(mapOf("suppressions" to rows))
	}

	@DeleteMapping("/{id}")
	fun delete(
		@PathVariable id: UUID,
		@AuthenticationPrincipal jwt: Jwt,
		@RequestHeader("X-Request-Id", required = false) requestId: String?,
	): ResponseEntity<Void> {
		suppressions.delete(id)
		emit(requestId, jwt, "DELETE /v1/admin/suppressions/$id", "delete", id.toString())
		return ResponseEntity.noContent().build()
	}

	private fun emit(requestId: String?, jwt: Jwt, endpoint: String, action: String, target: String) {
		events.publish(
			actorId = UUID.fromString(jwt.getClaimAsString("sub")),
			actorUsername = jwt.getClaimAsString("preferred_username"),
			actorRoles = emptyList(),
			endpoint = endpoint,
			action = action,
			targetResource = target,
			reasonCode = null,
			requestId = requestId,
			traceId = null,
			result = "ok",
			durationMs = 0L,
		)
	}
}