package com.trips_enjoy.driver.domain

import com.trips_enjoy.platform.data.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Table
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

/**
 * The driver aggregate — one row per platform driver.
 *
 * Mirrors `driver.drivers` per docs/services/driver-service/ERD.md §3.
 * PII columns (name, email, phone) are stored as plain TEXT; the
 * column-level encryption contract is at the application boundary
 * (TECH §6). Custody regions: `id` is UUIDv7 (assigned by `BaseEntity`),
 * `identity_id` is the cross-service ref to `identity-service`, all
 * other `_id` columns are cross-service UUIDs WITHOUT database FKs
 * (DATA--003).
 *
 * State machine (per INTEGRATION §1.10–1.15):
 *   pending_review → approved | rejected | erased
 *   approved       → suspended | inactive | erased
 *   suspended      → reinstated (→ approved) | erased
 *   inactive       → approved (manual reactivate) | erased
 *   rejected       → erased (terminal)
 *
 * Phase C (platform DRY): extends [BaseEntity] so the `id`, `createdAt`,
 * `updatedAt`, `createdBy`, `updatedBy`, `version`, and `deletedAt`
 * columns are inherited from the platform canonical shape. The
 * corresponding column migration is V6 (`created_by` / `updated_by`
 * `UUID` → `VARCHAR(255)`, `row_version` → `version`). The state-machine
 * methods no longer bump `rowVersion`/`updatedAt` manually — Hibernate's
 * `@Version` and the JPA `AuditingEntityListener` populate them on save.
 */
@Entity
@Table(name = "drivers", schema = "driver")
class Driver(
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
        val TERMINAL_STATUSES: Set<String> = setOf(STATUS_REJECTED, STATUS_ERASED)
    }

    fun approve(@Suppress("UNUSED_PARAMETER") at: Instant) {
        check(status in setOf(STATUS_PENDING_REVIEW, STATUS_INACTIVE)) {
            "cannot approve driver in state $status"
        }
        status = STATUS_APPROVED
        rejectedReason = null
    }

    fun reject(reason: String, @Suppress("UNUSED_PARAMETER") at: Instant) {
        check(status == STATUS_PENDING_REVIEW) { "cannot reject driver in state $status" }
        require(reason.isNotBlank()) { "rejection reason required" }
        status = STATUS_REJECTED
        rejectedReason = reason
    }

    fun suspend(reason: String, actorId: UUID, at: Instant) {
        check(status == STATUS_APPROVED) { "cannot suspend driver in state $status" }
        require(reason.isNotBlank()) { "suspension reason required" }
        status = STATUS_SUSPENDED
        suspendedReason = reason
        suspendedAt = at
        suspendedBy = actorId
    }

    fun reinstate(@Suppress("UNUSED_PARAMETER") at: Instant) {
        check(status == STATUS_SUSPENDED) { "cannot reinstate driver in state $status" }
        status = STATUS_APPROVED
        suspendedReason = null
        suspendedAt = null
        suspendedBy = null
    }

    fun disable(@Suppress("UNUSED_PARAMETER") at: Instant) {
        check(status in ACTIVE_STATUSES) { "cannot disable driver in state $status" }
        status = STATUS_INACTIVE
        disabledAt = at
    }

    fun erase(at: Instant) {
        check(status != STATUS_ERASED) { "driver already erased" }
        status = STATUS_ERASED
        erasedAt = at
    }

    fun setPrimaryVehicle(vehicleId: UUID, @Suppress("UNUSED_PARAMETER") at: Instant) {
        check(status != STATUS_ERASED) { "cannot set primary vehicle on erased driver" }
        primaryVehicleId = vehicleId
    }

    fun applyRating(newRating: BigDecimal, at: Instant) {
        require(newRating.toDouble() in 1.0..5.0) { "rating must be 1.0..5.0" }
        val total = rating.multiply(BigDecimal(ratingCount)).add(newRating)
        ratingCount += 1
        rating = total.divide(BigDecimal(ratingCount), 2, java.math.RoundingMode.HALF_UP)
        ratingUpdatedAt = at
    }

    fun setDocumentsWarn(warn: Boolean, @Suppress("UNUSED_PARAMETER") at: Instant) {
        documentsWarn = warn
    }

    fun touchOnline(at: Instant) {
        lastOnlineAt = at
    }
}
