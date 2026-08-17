package com.trips_enjoy.courier.api

import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

data class CreateCourierRequest(
    @field:NotBlank val identityId: String,
    val name: String? = null,
    val email: String? = null,
    val phone: String? = null,
    val correlationId: String? = null,
) {
    fun identityIdAsUuid(): UUID = UUID.fromString(identityId)
}

data class CourierResponse(
    val courierId: String,
    val identityId: String,
    val status: String,
    val rating: Double,
    val ratingCount: Int,
    val primaryVehicleId: String?,
    val kycVerifiedAt: String?,
    val lastOnlineAt: String?,
)

data class UpdateProfileRequest(
    val name: String? = null,
    val email: String? = null,
    val phone: String? = null,
)

data class RejectRequest(@field:NotBlank val reason: String)
data class SuspendRequest(@field:NotBlank val reason: String)
data class ApproveRequest(val note: String? = null)

data class AddDocumentRequest(
    @field:NotBlank @field:Pattern(regexp = "^(id|license|vehicle_reg|insurance|selfie|background_check|medical|permit)$")
    val type: String,
    @field:NotBlank val fileId: String,
    val critical: Boolean = true,
    val expiryDate: Instant? = null,
)

data class VerifyDocumentRequest(@field:NotBlank val verificationId: String)

data class DocumentResponse(
    val documentId: String,
    val courierId: String,
    val type: String,
    val fileId: String,
    val verificationId: String?,
    val verifiedAt: String?,
    val expiryDate: String?,
    val critical: Boolean,
    val status: String,
)

data class GrantCityRequest(
    @field:NotBlank val cityId: String,
    val notes: String? = null,
)

data class EligibilityResponse(
    val eligibilityId: String,
    val courierId: String,
    val cityId: String,
    val grantedAt: String,
    val revokedAt: String?,
    val notes: String?,
)

data class RatingRequest(
    @field:NotBlank val requestId: String,
    @field:NotBlank @field:Pattern(regexp = "^(trip|food_order)$") val service: String,
    @field:Min(1) val rating: Short,
    val comment: String? = null,
) {
    fun requestIdAsUuid(): UUID = UUID.fromString(requestId)
}

data class RatingResponse(
    val courierId: String,
    val rating: Double,
    val ratingCount: Int,
)

data class SetPrimaryVehicleRequest(@field:NotBlank val vehicleId: String)

data class ScheduleShiftRequest(
    @field:NotBlank val startAt: String,
    @field:NotBlank val endAt: String,
) {
    fun startAtAsInstant(): Instant = Instant.parse(startAt)
    fun endAtAsInstant(): Instant = Instant.parse(endAt)
}

data class CancelShiftRequest(@field:NotBlank val reason: String)

data class ShiftResponse(
    val shiftId: String,
    val courierId: String,
    val startAt: String,
    val endAt: String,
    val actualStartAt: String?,
    val actualEndAt: String?,
    val status: String,
)