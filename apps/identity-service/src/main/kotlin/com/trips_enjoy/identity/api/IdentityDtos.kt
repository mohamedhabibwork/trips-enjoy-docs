package com.trips_enjoy.identity.api

import com.trips_enjoy.identity.domain.Identity
import com.trips_enjoy.identity.domain.IdentityStatus
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
import java.time.Instant
import java.util.UUID

data class CreateIdentityRequest(
    @field:NotBlank @field:Size(max = 255) val kc_sub: String,
    @field:Pattern(regexp = "platform-(customer|driver|courier|staff|internal|services)") val realm: String,
    @field:NotBlank @field:Size(max = 64) val user_type: String,
    val region: String? = null,
    val tenant_id: UUID? = null,
    val name: String? = null,
    val email: String? = null,
    val phone: String? = null,
    val locale: String? = null,
)

data class UpdateIdentityRequest(
    val name: String? = null,
    val email: String? = null,
    val phone: String? = null,
    val locale: String? = null,
    val email_verified: Boolean? = null,
    val phone_verified: Boolean? = null,
    val mfa_enabled: Boolean? = null,
    val row_version: Long? = null,
)

data class SuspensionRequest(
    @field:Pattern(regexp = "fraud|payment_failure|manual_review|security|legal") val reason: String,
    @field:Size(max = 512) val note: String? = null,
    @field:Size(min = 0, max = 365) val expected_duration_days: Int? = null,
)
data class DisableRequest(
    @field:Pattern(regexp = "fraud|payment_failure|manual_review|security|legal") val reason: String,
    val note: String? = null,
)
data class ReinstateRequest(val note: String? = null)
data class EraseRequest(
    @field:Pattern(regexp = "user_request|legal_hold|compliance") val legal_basis: String,
    val note: String? = null,
)
data class LogoutRequest(@field:NotBlank val reason: String, val note: String? = null)
data class IntrospectionRequest(@field:NotBlank val token: String)

data class IdentityResponse(
    val id: UUID,
    val kc_sub: String,
    val realm: String,
    val user_type: String,
    val region: String?,
    val tenant_id: UUID?,
    val name: String?,
    val email: String?,
    val email_verified: Boolean,
    val phone: String?,
    val phone_verified: Boolean,
    val locale: String?,
    val mfa_enabled: Boolean,
    val status: String,
    val suspended_reason: String?,
    val suspended_at: Instant?,
    val erased_at: Instant?,
    val created_at: Instant,
    val updated_at: Instant,
)

fun Identity.toResponse() = IdentityResponse(
    requireNotNull(id) { "Identity.id must be assigned after save" },
    keycloakSubject, realm, userType, region, tenantId, name, email, emailVerified, phone,
    phoneVerified, locale, mfaEnabled, status.name.lowercase(), suspendedReason, suspendedAt,
    erasedAt, createdAt ?: java.time.Instant.EPOCH, updatedAt ?: java.time.Instant.EPOCH,
)

data class ErasureResponse(val id: UUID, val status: String, val erased_at: Instant, val warnings: List<String> = emptyList())
data class LogoutResponse(val id: UUID, val sessions_revoked: Int, val revoked_jtis: List<String> = emptyList())
data class IntrospectionResponse(
    val identity_id: UUID,
    val kc_sub: String,
    val realm: String,
    val user_type: String,
    val roles: List<String>,
    val scopes: List<String>,
    val tenant_id: UUID?,
    val status: IdentityStatus,
    val claims: Map<String, Any?>,
)
