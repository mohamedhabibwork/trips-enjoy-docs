package com.trips_enjoy.admin.api

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
import java.time.Instant
import java.util.UUID

data class PerformActionRequest(
    @field:NotBlank @field:Size(max = 100) val actionType: String,
    val actorKind: String = "admin",
    val subjectKind: String? = null,
    val subjectId: String? = null,
    val payload: Map<String, Any?>? = null,
    val reason: String? = null,
    val breakGlassId: String? = null,
)

data class ActionLogResponse(
    val actionId: UUID,
    val actionType: String,
    val actorKcSub: UUID,
    val actorKind: String,
    val subjectKind: String?,
    val subjectId: UUID?,
    val reason: String?,
    val breakGlassId: UUID?,
    val occurredAt: Instant,
)

data class CoSignBreakGlassRequest(
    @field:NotBlank val actionLogId: String,
    @field:NotBlank val cosignerEmail: String,
    @field:NotBlank @field:Size(min = 8) val reason: String,
    @field:NotBlank val signature: String,
)

data class BreakGlassResponse(
    val breakGlassId: UUID,
    val actionLogId: UUID,
    val cosignerKcSub: UUID,
    val cosignerEmail: String?,
    val reason: String,
    val occurredAt: Instant,
    val expiresAt: Instant,
    val revokedAt: Instant?,
)

data class GrantSuperAdminRequest(
    @field:NotBlank val granteeKcSub: String,
    val granteeEmail: String? = null,
    val grantedByEmail: String? = null,
    @field:NotBlank @field:Size(min = 8) val reason: String,
    @field:Pattern(regexp = "^(permanent|time_bounded)$") val aliasKind: String = "permanent",
    val aliasExpiresAt: Instant? = null,
)

data class RevokeSuperAdminRequest(
    @field:NotBlank val grantId: String,
)

data class SuperAdminGrantResponse(
    val grantId: UUID,
    val granteeKcSub: UUID,
    val granteeEmail: String?,
    val grantedByKcSub: UUID,
    val reason: String,
    val aliasKind: String,
    val aliasExpiresAt: Instant?,
    val revokedAt: Instant?,
    val createdAt: Instant,
)

data class UpsertGeoConfigRequest(
    @field:NotBlank @field:Pattern(regexp = "^[a-zA-Z0-9_-]{1,50}$") val tenantId: String,
    val cityId: String? = null,
    val originZoneId: String? = null,
    val destinationZoneId: String? = null,
    val rideType: String? = null,
    @field:NotBlank @field:Pattern(
        regexp = "^(base_fare_override|per_km_override|per_min_override|surge_pressure|loyalty_discount|min_fare_override|od_corridor)$"
    ) val ruleKind: String,
    val value: Map<String, Any?> = emptyMap(),
    val priority: Int = 100,
    val effectiveFrom: Instant? = null,
    val effectiveTo: Instant? = null,
)

data class RollbackGeoConfigRequest(
    @field:NotBlank @field:Size(min = 8) val reason: String,
)

data class GeoConfigResponse(
    val configId: UUID,
    val tenantId: String,
    val cityId: String?,
    val originZoneId: UUID?,
    val destinationZoneId: UUID?,
    val rideType: String?,
    val ruleKind: String,
    val priority: Int,
    val effectiveFrom: Instant?,
    val effectiveTo: Instant?,
    val createdByKcSub: UUID,
    val createdAt: Instant,
)

data class PermissionsResponse(
    val actorKcSub: UUID,
    val roles: List<String>,
    val isSuperAdmin: Boolean,
)