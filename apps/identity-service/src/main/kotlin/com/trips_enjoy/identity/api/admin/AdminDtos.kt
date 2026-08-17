package com.trips_enjoy.identity.api.admin

import com.trips_enjoy.identity.application.RoleListResponse
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
import java.util.UUID

data class AdminSuspendRequest(
    @field:Pattern(regexp = "fraud|payment_failure|manual_review|security|legal") val reason: String,
    @field:Size(max = 512) val note: String? = null,
    @field:Size(min = 0, max = 365) val expected_duration_days: Int? = null,
)

data class AdminReinstateRequest(val note: String? = null)

data class AdminRoleGrantRequest(
    val preset: String? = null,
    @field:NotBlank @field:Size(min = 8, max = 512) val reason_code: String,
)

data class AdminRoleRevokeRequest(
    val preset: String? = null,
    @field:NotBlank @field:Size(min = 8, max = 512) val reason_code: String,
)

/** Response wrapper used for both role-list and grant/revoke (full updated list). */
typealias AdminRolesResponse = RoleListResponse

/** Result of /admin/v1/identities/{id}/suspend or /unsuspend or /force-claims-refresh. */
data class AdminActionResponse(
    val identity_id: UUID,
    val status: String,
    val message: String? = null,
)

/** Result of /admin/v1/identities/{id}/force-claims-refresh. */
data class ForceClaimsRefreshResponse(
    val identity_id: UUID,
    val refreshed_at: java.time.Instant,
    val source: String,
)
