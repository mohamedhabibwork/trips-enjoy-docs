package com.trips_enjoy.notification.api

import com.trips_enjoy.notification.application.NotificationPreferenceService
import com.trips_enjoy.notification.domain.enums.Channel
import org.springframework.cache.annotation.CacheEvict
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * GET / PATCH /v1/preferences/{user_id} per
 * docs/services/notification-service/INTEGRATION.md §1 + WORKFLOWS §3.
 *
 *  - GET: own sub OR admin.
 *  - PATCH: own sub OR admin; `Idempotency-Key` header recommended.
 */
@RestController
@RequestMapping("/v1/preferences")
class PreferenceController(
	private val preferencesService: NotificationPreferenceService,
) {

	@GetMapping("/{userId}")
	@PreAuthorize("isAuthenticated()")
	fun get(
		@PathVariable userId: UUID,
		@AuthenticationPrincipal jwt: Jwt,
	): ResponseEntity<PreferenceResponse> {
		val sub = jwt.getClaimAsString("sub")?.let(UUID::fromString)
		val isAdmin = jwtRoles(jwt).any { it in setOf("platform.admin", "platform.super_admin", "notification.admin") }
		if (!isAdmin && sub != userId) {
			throw ApiException(org.springframework.http.HttpStatus.FORBIDDEN, "FORBIDDEN", "Caller is not the owner")
		}
		val entries = preferencesService.findForUser(userId).map(PreferenceEntry.Companion::from)
		return ResponseEntity.ok(PreferenceResponse(user_id = userId, entries = entries))
	}

	@PatchMapping("/{userId}")
	@PreAuthorize("isAuthenticated()")
	@CacheEvict(cacheNames = ["notification-preferences"], key = "#userId.toString()")
	fun update(
		@PathVariable userId: UUID,
		@RequestBody request: UpsertPreferencesRequest,
		@AuthenticationPrincipal jwt: Jwt,
	): ResponseEntity<PreferenceResponse> {
		val sub = jwt.getClaimAsString("sub")?.let(UUID::fromString)
		val isAdmin = jwtRoles(jwt).any { it in setOf("platform.admin", "platform.super_admin", "notification.admin") }
		if (!isAdmin && sub != userId) {
			throw ApiException(org.springframework.http.HttpStatus.FORBIDDEN, "FORBIDDEN", "Caller is not the owner")
		}
		val actor = sub ?: userId
		request.entries.forEach { e ->
			preferencesService.upsert(
				userId = userId,
				category = e.category,
				channel = Channel.fromValue(e.channel),
				optIn = e.opt_in,
				quietHoursStart = e.quiet_hours_start,
				quietHoursEnd = e.quiet_hours_end,
				timezone = e.timezone,
				actorId = actor,
			)
		}
		val entries = preferencesService.findForUser(userId).map(PreferenceEntry.Companion::from)
		return ResponseEntity.ok(PreferenceResponse(user_id = userId, entries = entries))
	}

	private fun jwtRoles(jwt: Jwt): List<String> =
		(jwt.getClaimAsMap("realm_access").orEmpty()["roles"] as? Collection<*>)
			?.filterIsInstance<String>().orEmpty()
}