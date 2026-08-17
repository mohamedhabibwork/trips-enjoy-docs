package com.trips_enjoy.courier.domain

import com.trips_enjoy.platform.data.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Table
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

/**
 * The courier aggregate — one row per platform courier.
 *
 * Mirrors `courier.couriers` per docs/services/courier-service/ERD.md §3.
 * The shape is the canonical driver-shape lift-forward; the only
 * difference vs `driver-service` is the addition of the
 * `courier.courier_shifts` sub-aggregate (a courier has many
 * scheduled shift blocks; the active shift binds to delivery offers).
 *
 * State machine (per INTEGRATION §1.10–1.15):
 *   pending_review → approved | rejected | erased
 *   approved       → suspended | inactive | erased
 *   suspended      → reinstated (→ approved) | erased
 *   inactive       → approved (manual reactivate) | erased
 *   rejected       → erased (terminal)
 *
 * Phase C (platform DRY): extends [BaseEntity] so the `id`,
 * `createdAt`, `updatedAt`, `createdBy`, `updatedBy`, `version`, and
 * `deletedAt` columns are inherited from the platform canonical
 * shape. The corresponding column migration is V6
 * (`created_by` / `updated_by` `UUID` → `VARCHAR(255)`,
 * `row_version` → `version`).
 */
@Entity
@Table(name = "couriers", schema = "courier")
class Courier(
    @Column(name = "identity_id", nullable = false) val identityId: UUID,
    @Column var name: String? = null,
    @Column var email: String? = null,
    @Column var phone: String? = null,
    @Column(name = "primary_vehicle_id") var primaryVehicleId: UUID? = null,
    @Column(name = "kyc_verification_id") var kycVerificationId: UUID? = null,
    @Column(name = "kyc_verified_at") var kycVerifiedAt: Instant? = null,
    @Column(name = "background_check_verification_id") var backgroundCheckVerificationId: UUID? = null,
    @Column(name = "background_check_verified_at") var backgroundCheckVerifiedAt: Instant? = null,
    @Column(nullable = false) var rating: BigDecimal = BigDecimal.ZERO,
    @Column(name = "rating_count", nullable = false) var ratingCount: Int = 0,
    @Column(name = "rating_updated_at") var ratingUpdatedAt: Instant? = null,
    @Column(nullable = false) var status: String = STATUS_PENDING_REVIEW,
    @Column(name = "rejected_reason") var rejectedReason: String? = null,
    @Column(name = "suspended_reason") var suspendedReason: String? = null,
    @Column(name = "suspended_at") var suspendedAt: Instant? = null,
    @Column(name = "suspended_by") var suspendedBy: UUID? = null,
    @Column(name = "disabled_at") var disabledAt: Instant? = null,
    @Column(name = "erased_at") var erasedAt: Instant? = null,
    @Column(name = "documents_warn", nullable = false) var documentsWarn: Boolean = false,
    @Column(name = "last_online_at") var lastOnlineAt: Instant? = null,
) : BaseEntity() {
    companion object {
        const val STATUS_PENDING_REVIEW = "pending_review"
        const val STATUS_APPROVED = "approved"
        const val STATUS_REJECTED = "rejected"
        const val STATUS_SUSPENDED = "suspended"
        const val STATUS_INACTIVE = "inactive"
        const val STATUS_ERASED = "erased"

        val ACTIVE_STATUSES: Set<String> = setOf(STATUS_APPROVED, STATUS_INACTIVE)
    }

    fun approve(at: Instant) {
        check(status in setOf(STATUS_PENDING_REVIEW, STATUS_INACTIVE)) {
            "cannot approve courier in state $status"
        }
        status = STATUS_APPROVED
        rejectedReason = null
        updatedAt = at
        version += 1
    }

    fun reject(reason: String, at: Instant) {
        check(status == STATUS_PENDING_REVIEW) { "cannot reject courier in state $status" }
        require(reason.isNotBlank()) { "rejection reason required" }
        status = STATUS_REJECTED
        rejectedReason = reason
        updatedAt = at
        version += 1
    }

    fun suspend(reason: String, actorId: UUID, at: Instant) {
        check(status == STATUS_APPROVED) { "cannot suspend courier in state $status" }
        require(reason.isNotBlank()) { "suspension reason required" }
        status = STATUS_SUSPENDED
        suspendedReason = reason
        suspendedAt = at
        suspendedBy = actorId
        updatedAt = at
        version += 1
    }

    fun reinstate(at: Instant) {
        check(status == STATUS_SUSPENDED) { "cannot reinstate courier in state $status" }
        status = STATUS_APPROVED
        suspendedReason = null
        suspendedAt = null
        suspendedBy = null
        updatedAt = at
        version += 1
    }

    fun disable(at: Instant) {
        check(status in ACTIVE_STATUSES) { "cannot disable courier in state $status" }
        status = STATUS_INACTIVE
        disabledAt = at
        updatedAt = at
        version += 1
    }

    fun erase(at: Instant) {
        check(status != STATUS_ERASED) { "courier already erased" }
        status = STATUS_ERASED
        erasedAt = at
        updatedAt = at
        version += 1
    }

    fun setPrimaryVehicle(vehicleId: UUID, at: Instant) {
        check(status != STATUS_ERASED) { "cannot set primary vehicle on erased courier" }
        primaryVehicleId = vehicleId
        updatedAt = at
        version += 1
    }

    fun applyRating(newRating: BigDecimal, at: Instant) {
        require(newRating.toDouble() in 1.0..5.0) { "rating must be 1.0..5.0" }
        val total = rating.multiply(BigDecimal(ratingCount)).add(newRating)
        ratingCount += 1
        rating = total.divide(BigDecimal(ratingCount), 2, java.math.RoundingMode.HALF_UP)
        ratingUpdatedAt = at
        updatedAt = at
        version += 1
    }

    fun setDocumentsWarn(warn: Boolean, at: Instant) {
        documentsWarn = warn
        updatedAt = at
        version += 1
    }

    fun touchOnline(at: Instant) {
        lastOnlineAt = at
        updatedAt = at
        version += 1
    }
}
