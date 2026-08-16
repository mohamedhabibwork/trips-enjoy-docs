package com.trips_enjoy.notification.api.admin

import com.fasterxml.jackson.databind.ObjectMapper
import com.trips_enjoy.notification.api.ApiException
import com.trips_enjoy.notification.application.NotificationAdminAuditPublisher
import com.trips_enjoy.notification.application.NotificationTemplateService
import com.trips_enjoy.notification.domain.Template
import com.trips_enjoy.notification.domain.TemplateRepository
import com.trips_enjoy.notification.domain.enums.Channel
import com.trips_enjoy.notification.domain.enums.TemplateProviderStatus
import com.trips_enjoy.notification.domain.enums.TemplateType
import com.trips_enjoy.notification.util.uuidV7
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * /v1/admin/templates lifecycle per
 * docs/services/notification-service/INTEGRATION.md §1 +
 * WORKFLOWS §9.2/9.3/9.5.
 *
 *  - POST /v1/admin/templates                — create draft
 *  - GET  /v1/admin/templates                — list
 *  - PATCH /v1/admin/templates/{id}          — mutate (creates new version)
 *  - POST /v1/admin/templates/{id}/submit-for-approval
 *  - POST /v1/admin/templates/{id}/approve   — record provider approval
 *  - POST /v1/admin/templates/{id}/publish   — atomic across (channel, locale)
 *  - GET  /v1/admin/templates/{id}/history   — full publication history
 *
 * Auth: ROLE notification.admin, notification_ops, platform.admin, platform.super_admin.
 * HMAC + Idempotency-Key enforced at the application layer (TECH §10.2 + INTEG §1.6).
 */
@RestController
@RequestMapping("/v1/admin/templates")
@PreAuthorize(
	"hasAnyAuthority(" +
		"'ROLE_notification.admin', 'ROLE_notification_ops', " +
		"'ROLE_platform.admin', 'ROLE_platform.super_admin'" +
	")",
)
class AdminTemplateController(
	private val templates: TemplateRepository,
	private val templateService: NotificationTemplateService,
	private val events: NotificationAdminAuditPublisher,
	private val mapper: ObjectMapper,
) {

	@PostMapping
	fun create(
		@AuthenticationPrincipal jwt: Jwt,
		@RequestHeader("X-Request-Id", required = false) requestId: String?,
		@RequestBody req: CreateTemplateRequest,
	): ResponseEntity<TemplateResponse> {
		val actor = UUID.fromString(jwt.getClaimAsString("sub"))
		val tmpl = Template(
			id = uuidV7(),
			name = req.name,
			category = req.category,
			channel = Channel.fromValue(req.channel),
			locale = req.locale,
			subject = req.subject,
			body = if (req.template_type == TemplateType.PLAIN.value) req.body else null,
			templateType = TemplateType.fromValue(req.template_type),
			bodyStructured = req.body_structured?.let { mapper.writeValueAsString(it) },
			providerTemplateId = req.provider_template_id,
			providerTemplateLanguage = req.provider_template_language,
			providerTemplateStatus = TemplateProviderStatus.fromValue(req.provider_template_status),
			requiredVariables = req.required_variables,
			metadata = mapper.writeValueAsString(req.metadata),
			createdBy = actor,
			updatedBy = actor,
		)
		val saved = templates.save(tmpl)
		emit(requestId, jwt, "POST /v1/admin/templates", "create", saved.id.toString())
		return ResponseEntity.status(HttpStatus.CREATED).body(toResponse(saved))
	}

	@GetMapping
	fun list(): ResponseEntity<Map<String, Any>> =
		ResponseEntity.ok(mapOf("templates" to templates.findAll().map(::toResponse)))

	@PatchMapping("/{id}")
	fun update(
		@PathVariable id: UUID,
		@AuthenticationPrincipal jwt: Jwt,
		@RequestHeader("X-Request-Id", required = false) requestId: String?,
		@RequestBody req: UpdateTemplateRequest,
	): ResponseEntity<TemplateResponse> {
		val actor = UUID.fromString(jwt.getClaimAsString("sub"))
		val tmpl = templates.findById(id).orElseThrow {
			ApiException(HttpStatus.NOT_FOUND, "TEMPLATE_NOT_FOUND", "Template $id not found")
		}
		req.subject?.let { tmpl.subject = it }
		req.body?.let { tmpl.body = it }
		req.body_structured?.let { tmpl.bodyStructured = mapper.writeValueAsString(it) }
		req.required_variables?.let { tmpl.requiredVariables = it }
		req.metadata?.let { tmpl.metadata = mapper.writeValueAsString(it) }
		tmpl.updatedAt = java.time.Instant.now()
		tmpl.updatedBy = actor
		val saved = templates.save(tmpl)
		emit(requestId, jwt, "PATCH /v1/admin/templates/$id", "update", id.toString())
		return ResponseEntity.ok(toResponse(saved))
	}

	@PostMapping("/{id}/submit-for-approval")
	fun submitForApproval(
		@PathVariable id: UUID,
		@AuthenticationPrincipal jwt: Jwt,
		@RequestHeader("X-Request-Id", required = false) requestId: String?,
	): ResponseEntity<TemplateResponse> {
		val tmpl = templates.findById(id).orElseThrow {
			ApiException(HttpStatus.NOT_FOUND, "TEMPLATE_NOT_FOUND", "Template $id not found")
		}
		templateService.updateProviderStatus(id, TemplateProviderStatus.SUBMITTED, null)
		emit(requestId, jwt, "POST /v1/admin/templates/$id/submit-for-approval", "submit", id.toString())
		return ResponseEntity.ok(toResponse(tmpl))
	}

	@PostMapping("/{id}/approve")
	fun approve(
		@PathVariable id: UUID,
		@AuthenticationPrincipal jwt: Jwt,
		@RequestHeader("X-Request-Id", required = false) requestId: String?,
		@RequestBody req: ApproveTemplateRequest,
	): ResponseEntity<TemplateResponse> {
		val actor = UUID.fromString(jwt.getClaimAsString("sub"))
		val updated = templateService.updateProviderStatus(id, TemplateProviderStatus.APPROVED, actor)
		updated.providerTemplateId = req.provider_template_id
		templates.save(updated)
		emit(requestId, jwt, "POST /v1/admin/templates/$id/approve", "approve", id.toString())
		return ResponseEntity.ok(toResponse(updated))
	}

	@PostMapping("/{id}/publish")
	fun publish(
		@PathVariable id: UUID,
		@AuthenticationPrincipal jwt: Jwt,
		@RequestHeader("X-Request-Id", required = false) requestId: String?,
		@RequestHeader("X-Audit-Reason", required = false) reason: String?,
		@RequestBody req: PublishTemplateRequest,
	): ResponseEntity<HistoryResponse> {
		val actor = UUID.fromString(jwt.getClaimAsString("sub"))
		val tmpl = templates.findById(id).orElseThrow {
			ApiException(HttpStatus.NOT_FOUND, "TEMPLATE_NOT_FOUND", "Template $id not found")
		}
		val correlationId = requestId ?: actor.toString()
		templateService.publish(
			templateName = tmpl.name,
			publishedBy = actor,
			approvedBy = tmpl.providerTemplateApprovedAt?.let { actor },
			diffSummary = req.diff_summary + ("reason" to (reason ?: "n/a")),
			correlationId = correlationId,
		)
		val rows = templateService.history(id).map { h ->
			@Suppress("UNCHECKED_CAST")
			val diff = mapper.readValue(h.diffSummary, MAP_TYPE) as Map<String, Any?>
			TemplateHistoryResponse.from(h, diff)
		}
		emit(requestId, jwt, "POST /v1/admin/templates/$id/publish", "publish", id.toString())
		return ResponseEntity.ok(HistoryResponse(template_id = id.toString(), history = rows))
	}

	@GetMapping("/{id}/history")
	@PreAuthorize("hasAnyAuthority(" +
		"'ROLE_notification.admin', 'ROLE_notification.support', " +
		"'ROLE_platform.admin', 'ROLE_platform.super_admin', 'ROLE_platform.support', " +
		"'ROLE_support_agent'" +
	")")
	fun history(
		@PathVariable id: UUID,
		@AuthenticationPrincipal jwt: Jwt,
		@RequestHeader("X-Request-Id", required = false) requestId: String?,
	): ResponseEntity<HistoryResponse> {
		val rows = templateService.history(id).map { h ->
			@Suppress("UNCHECKED_CAST")
			val diff = mapper.readValue(h.diffSummary, MAP_TYPE) as Map<String, Any?>
			TemplateHistoryResponse.from(h, diff)
		}
		emit(requestId, jwt, "GET /v1/admin/templates/$id/history", "history_read", id.toString())
		return ResponseEntity.ok(HistoryResponse(template_id = id.toString(), history = rows))
	}

	// ----- helpers -----

	private fun emit(requestId: String?, jwt: Jwt, endpoint: String, action: String, target: String) {
		events.publish(
			actorId = UUID.fromString(jwt.getClaimAsString("sub")),
			actorUsername = jwt.getClaimAsString("preferred_username"),
			actorRoles = jwtRoles(jwt),
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

	private fun jwtRoles(jwt: Jwt): List<String> =
		(jwt.getClaimAsMap("realm_access").orEmpty()["roles"] as? Collection<*>)
			?.filterIsInstance<String>().orEmpty()

	private fun toResponse(t: Template): TemplateResponse = TemplateResponse(
		id = t.id.toString(),
		name = t.name,
		category = t.category,
		channel = t.channel.value,
		locale = t.locale,
		subject = t.subject,
		body = t.body,
		template_type = t.templateType.value,
		provider_template_id = t.providerTemplateId,
		provider_template_language = t.providerTemplateLanguage,
		provider_template_status = t.providerTemplateStatus.value,
		required_variables = t.requiredVariables,
		status = t.status,
		version = t.version,
		created_at = t.createdAt.toString(),
		updated_at = t.updatedAt.toString(),
	)

	companion object {
		private val MAP_TYPE = objectMapperType()
		private fun objectMapperType(): com.fasterxml.jackson.core.type.TypeReference<Map<String, Any?>> =
			object : com.fasterxml.jackson.core.type.TypeReference<Map<String, Any?>>() {}
	}
}