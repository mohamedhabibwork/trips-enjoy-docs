package com.trips_enjoy.admin.api

import com.fasterxml.jackson.databind.ObjectMapper
import com.trips_enjoy.admin.application.AdminWriteService
import com.trips_enjoy.admin.domain.ActionLog
import com.trips_enjoy.admin.domain.ActionLogKey
import com.trips_enjoy.admin.domain.BreakGlass
import com.trips_enjoy.admin.domain.IdempotencyKey
import com.trips_enjoy.admin.domain.PricingGeoConfig
import com.trips_enjoy.admin.domain.SuperAdminGrant
import com.trips_enjoy.admin.domain.repositories.ActionLogRepository
import com.trips_enjoy.admin.domain.repositories.BreakGlassRepository
import com.trips_enjoy.admin.domain.repositories.PricingGeoConfigHistoryRepository
import com.trips_enjoy.admin.domain.repositories.PricingGeoConfigRepository
import com.trips_enjoy.admin.domain.repositories.SuperAdminGrantRepository
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.security.MessageDigest
import java.util.UUID

/**
 * The admin-service REST controller. Mirrors
 * docs/services/admin-service/INTEGRATION.md §1.
 *
 * The surface is intentionally minimal for this graduation (a small
 * subset of the 60+ BFF wrappers in INTEGRATION.md): the SUPER_ADMIN
 * grant/revoke + break-glass co-sign + pricing geo-config upsert/
 * rollback + a permissions probe. A future graduate can wire the
 * remaining 50+ per-service BFF wrappers using the AdminWriteService
 * primitives + the canonical pattern from the prior graduates.
 */
@RestController
@RequestMapping("/admin/v1")
class AdminController(
    private val adminWriteService: AdminWriteService,
    private val actionLogRepository: ActionLogRepository,
    private val breakGlassRepository: BreakGlassRepository,
    private val superAdminGrantRepository: SuperAdminGrantRepository,
    private val pricingGeoConfigRepository: PricingGeoConfigRepository,
    private val pricingGeoConfigHistoryRepository: PricingGeoConfigHistoryRepository,
    private val objectMapper: ObjectMapper,
) {

    @PostMapping("/actions")
    @PreAuthorize("hasAuthority('SCOPE_admin.write') or hasAuthority('SCOPE_platform.admin')")
    fun performAction(
        @Valid @RequestBody req: PerformActionRequest,
        @RequestHeader("Idempotency-Key") idempotencyKey: String,
        @RequestHeader("X-User-Id") actingUser: String,
    ): ResponseEntity<ActionLogResponse> {
        val requestHash = sha256(objectMapper.writeValueAsString(req))
        val correlationId = UUID.randomUUID()
        val actorId = UUID.fromString(actingUser)
        val action = adminWriteService.performAction(
            actionType = req.actionType,
            actorKcSub = actorId,
            actorKind = req.actorKind,
            subjectKind = req.subjectKind,
            subjectId = req.subjectId?.let(UUID::fromString),
            payload = req.payload,
            reason = req.reason,
            breakGlassId = req.breakGlassId?.let(UUID::fromString),
            correlationId = correlationId,
            createdBy = actorId,
            idempotencyKey = idempotencyKey,
            requestHash = requestHash,
        )
        return ResponseEntity.status(HttpStatus.CREATED).body(action.toResponse())
    }

    @GetMapping("/actions")
    @PreAuthorize("hasAuthority('SCOPE_admin.read') or hasAuthority('SCOPE_platform.admin')")
    fun listActions(
        @RequestParam("actor_kc_sub") actorKcSub: UUID,
    ): List<ActionLogResponse> {
        val rows: List<com.trips_enjoy.admin.domain.ActionLog> =
            actionLogRepository.findByActorKcSubOrderByOccurredAtDesc(actorKcSub)
        return rows.map { it.toResponse() }
    }

    @GetMapping("/actions/{id}")
    @PreAuthorize("hasAuthority('SCOPE_admin.read') or hasAuthority('SCOPE_platform.admin')")
    fun getAction(@PathVariable("id") id: String): ActionLogResponse =
        actionLogRepository.findById(ActionLogKey(UUID.fromString(id), java.time.Instant.EPOCH))
            .orElseThrow { NoSuchElementException("action $id not found") }
            .toResponse()

    @PostMapping("/actions/{id}/break-glass")
    @PreAuthorize("hasAuthority('SCOPE_platform.super_admin')")
    fun coSignBreakGlass(
        @PathVariable("id") id: String,
        @Valid @RequestBody req: CoSignBreakGlassRequest,
        @RequestHeader("X-User-Id") cosignerKcSub: String,
        @RequestHeader("X-Correlation-Id") correlationId: String? = null,
    ): ResponseEntity<BreakGlassResponse> {
        val bg = adminWriteService.coSignBreakGlass(
            actionLogId = UUID.fromString(req.actionLogId),
            cosignerKcSub = UUID.fromString(cosignerKcSub),
            cosignerEmail = req.cosignerEmail,
            reason = req.reason,
            signature = req.signature,
            correlationId = UUID.fromString(correlationId ?: UUID.randomUUID().toString()),
            createdBy = UUID.fromString(cosignerKcSub),
        )
        return ResponseEntity.status(HttpStatus.CREATED).body(bg.toResponse())
    }

    @GetMapping("/permissions")
    @PreAuthorize("isAuthenticated()")
    fun permissions(
        @RequestHeader("X-User-Id") userId: String,
    ): PermissionsResponse {
        val userUuid = UUID.fromString(userId)
        val rawGrants: List<com.trips_enjoy.admin.domain.SuperAdminGrant> =
            superAdminGrantRepository.findByGranteeKcSubAndRevokedAtIsNull(userUuid)
        val activeGrants = rawGrants.filter { it.isActive() }
        val isSuperAdmin = activeGrants.isNotEmpty()
        val roles = activeGrants.flatMap { listOf("platform.super_admin") }
            .plus(SuperAdminGrantRepository.DEFAULT_PRESET_SCOPES)
        return PermissionsResponse(userUuid, roles.distinct(), isSuperAdmin)
    }

    @PostMapping("/identity/grant-super-admin")
    @PreAuthorize("hasAuthority('SCOPE_platform.super_admin')")
    fun grantSuperAdmin(
        @Valid @RequestBody req: GrantSuperAdminRequest,
        @RequestHeader("Idempotency-Key") idempotencyKey: String,
        @RequestHeader("X-User-Id") grantedByKcSub: String,
    ): ResponseEntity<SuperAdminGrantResponse> {
        val requestHash = sha256(objectMapper.writeValueAsString(req))
        val correlationId = UUID.randomUUID()
        val grant = adminWriteService.grantSuperAdmin(
            granteeKcSub = UUID.fromString(req.granteeKcSub),
            granteeEmail = req.granteeEmail,
            grantedByKcSub = UUID.fromString(grantedByKcSub),
            grantedByEmail = req.grantedByEmail,
            reason = req.reason,
            aliasKind = req.aliasKind,
            aliasExpiresAt = req.aliasExpiresAt,
            correlationId = correlationId,
            createdBy = UUID.fromString(grantedByKcSub),
            idempotencyKey = idempotencyKey,
            requestHash = requestHash,
        )
        return ResponseEntity.status(HttpStatus.CREATED).body(grant.toResponse())
    }

    @PostMapping("/identity/revoke-super-admin")
    @PreAuthorize("hasAuthority('SCOPE_platform.super_admin')")
    fun revokeSuperAdmin(
        @Valid @RequestBody req: RevokeSuperAdminRequest,
        @RequestHeader("Idempotency-Key") idempotencyKey: String,
        @RequestHeader("X-User-Id") revokedByKcSub: String,
    ): ResponseEntity<SuperAdminGrantResponse> {
        val requestHash = sha256(objectMapper.writeValueAsString(req))
        val correlationId = UUID.randomUUID()
        val grant = adminWriteService.revokeSuperAdmin(
            grantId = UUID.fromString(req.grantId),
            revokedByKcSub = UUID.fromString(revokedByKcSub),
            correlationId = correlationId,
            createdBy = UUID.fromString(revokedByKcSub),
            idempotencyKey = idempotencyKey,
            requestHash = requestHash,
        )
        return ResponseEntity.ok(grant.toResponse())
    }

    @GetMapping("/identity/permissions/{user_id}")
    @PreAuthorize("hasAuthority('SCOPE_platform.super_admin') or hasAuthority('SCOPE_admin.read')")
    fun userPermissions(@PathVariable("user_id") userId: String): PermissionsResponse {
        val userUuid = UUID.fromString(userId)
        val rawGrants: List<com.trips_enjoy.admin.domain.SuperAdminGrant> =
            superAdminGrantRepository.findByGranteeKcSubAndRevokedAtIsNull(userUuid)
        val activeGrants = rawGrants.filter { it.isActive() }
        val isSuperAdmin = activeGrants.isNotEmpty()
        val roles = activeGrants.flatMap { listOf("platform.super_admin") }
            .plus(SuperAdminGrantRepository.DEFAULT_PRESET_SCOPES)
        return PermissionsResponse(userUuid, roles.distinct(), isSuperAdmin)
    }

    @PostMapping("/pricing/geo-config")
    @PreAuthorize("hasAuthority('SCOPE_pricing.admin') or hasAuthority('SCOPE_platform.super_admin')")
    fun upsertGeoConfig(
        @Valid @RequestBody req: UpsertGeoConfigRequest,
        @RequestHeader("Idempotency-Key") idempotencyKey: String,
        @RequestHeader("X-User-Id") actingUser: String,
    ): ResponseEntity<GeoConfigResponse> {
        val requestHash = sha256(objectMapper.writeValueAsString(req))
        val correlationId = UUID.randomUUID()
        val actorId = UUID.fromString(actingUser)
        val cfg = adminWriteService.upsertPricingGeoConfig(
            tenantId = req.tenantId,
            cityId = req.cityId,
            originZoneId = req.originZoneId?.let(UUID::fromString),
            destinationZoneId = req.destinationZoneId?.let(UUID::fromString),
            rideType = req.rideType,
            ruleKind = req.ruleKind,
            value = req.value,
            priority = req.priority,
            effectiveFrom = req.effectiveFrom,
            effectiveTo = req.effectiveTo,
            createdByKcSub = actorId,
            correlationId = correlationId,
            idempotencyKey = idempotencyKey,
            requestHash = requestHash,
        )
        return ResponseEntity.status(HttpStatus.CREATED).body(cfg.toResponse())
    }

    @PostMapping("/pricing/geo-config/{id}/rollback")
    @PreAuthorize("hasAuthority('SCOPE_pricing.admin') or hasAuthority('SCOPE_platform.super_admin')")
    fun rollbackGeoConfig(
        @PathVariable("id") id: String,
        @Valid @RequestBody req: RollbackGeoConfigRequest,
        @RequestHeader("X-User-Id") actingUser: String,
        @RequestHeader("X-Correlation-Id") correlationId: String? = null,
    ): ResponseEntity<GeoConfigResponse> {
        val actorId = UUID.fromString(actingUser)
        val cfg = adminWriteService.rollbackPricingGeoConfig(
            configId = UUID.fromString(id),
            reason = req.reason,
            actorKcSub = actorId,
            correlationId = UUID.fromString(correlationId ?: UUID.randomUUID().toString()),
            createdBy = actorId,
        )
        return ResponseEntity.ok(cfg.toResponse())
    }

    private fun sha256(payload: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val bytes = digest.digest(payload.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }
}

private fun ActionLog.toResponse(): ActionLogResponse = ActionLogResponse(
    actionId = id,
    actionType = actionType,
    actorKcSub = actorKcSub,
    actorKind = actorKind,
    subjectKind = subjectKind,
    subjectId = subjectId,
    reason = reason,
    breakGlassId = breakGlassId,
    occurredAt = occurredAt,
)

private fun BreakGlass.toResponse(): BreakGlassResponse = BreakGlassResponse(
    breakGlassId = id,
    actionLogId = actionLogId,
    cosignerKcSub = cosignerKcSub,
    cosignerEmail = cosignerEmail,
    reason = reason,
    occurredAt = occurredAt,
    expiresAt = expiresAt,
    revokedAt = revokedAt,
)

private fun SuperAdminGrant.toResponse(): SuperAdminGrantResponse = SuperAdminGrantResponse(
    grantId = id,
    granteeKcSub = granteeKcSub,
    granteeEmail = granteeEmail,
    grantedByKcSub = grantedByKcSub,
    reason = reason,
    aliasKind = aliasKind,
    aliasExpiresAt = aliasExpiresAt,
    revokedAt = revokedAt,
    createdAt = createdAt,
)

private fun PricingGeoConfig.toResponse(): GeoConfigResponse = GeoConfigResponse(
    configId = id,
    tenantId = tenantId,
    cityId = cityId,
    originZoneId = originZoneId,
    destinationZoneId = destinationZoneId,
    rideType = rideType,
    ruleKind = ruleKind,
    priority = priority,
    effectiveFrom = effectiveFrom,
    effectiveTo = effectiveTo,
    createdByKcSub = createdByKcSub,
    createdAt = createdAt,
)