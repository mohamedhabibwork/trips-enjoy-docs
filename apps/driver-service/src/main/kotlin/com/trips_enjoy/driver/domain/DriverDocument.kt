package com.trips_enjoy.driver.domain

import com.trips_enjoy.platform.data.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/**
 * A driver KYC document. Mirrors `driver.driver_documents` per
 * docs/services/driver-service/ERD.md §3. Append-only in spirit —
 * the only mutable columns are `status` (pending → verified/rejected/
 * expired) and `verification_id` + `verified_at` (set by the
 * KYC provider callback). The V3 trigger blocks UPDATE/DELETE.
 *
 * The nightly expiry job (TECH §5.4) flips `status` from `verified`
 * to `expired` when `expiry_date < now()` AND `critical = true`,
 * and the Driver aggregate's `documents_warn` flag is set.
 *
 * Phase C (platform DRY): extends [BaseEntity] so the `id`, `createdAt`,
 * `updatedAt`, `createdBy`, `updatedBy`, `version`, and `deletedAt`
 * columns are inherited from the platform canonical shape (V6 migration).
 * The state-machine methods no longer bump `rowVersion`/`updatedAt`
 * manually — Hibernate's `@Version` and the JPA `AuditingEntityListener`
 * populate them on save.
 */
@Entity
@Table(name = "driver_documents", schema = "driver")
class DriverDocument(
    @Column(name = "driver_id", nullable = false) val driverId: UUID,
    @Column(nullable = false) var type: String,
    @Column(name = "file_id", nullable = false) val fileId: UUID,
    @Column(name = "verification_id") var verificationId: UUID? = null,
    @Column(name = "verified_at") var verifiedAt: Instant? = null,
    @Column(name = "expiry_date") var expiryDate: Instant? = null,
    @Column(nullable = false) var critical: Boolean = true,
    @Column(nullable = false) var status: String = STATUS_PENDING,
    @Column(name = "rejected_reason") var rejectedReason: String? = null,
) : BaseEntity() {
    companion object {
        const val TYPE_LICENSE = "license"
        const val TYPE_VEHICLE_REG = "vehicle_reg"
        const val TYPE_INSURANCE = "insurance"
        const val TYPE_SELFIE = "selfie"
        const val TYPE_BACKGROUND_CHECK = "background_check"
        const val TYPE_MEDICAL = "medical"
        const val TYPE_PERMIT = "permit"

        const val STATUS_PENDING = "pending"
        const val STATUS_VERIFIED = "verified"
        const val STATUS_REJECTED = "rejected"
        const val STATUS_EXPIRED = "expired"

        val VALID_TYPES: Set<String> = setOf(
            TYPE_LICENSE, TYPE_VEHICLE_REG, TYPE_INSURANCE, TYPE_SELFIE,
            TYPE_BACKGROUND_CHECK, TYPE_MEDICAL, TYPE_PERMIT,
        )
        val VALID_STATUSES: Set<String> = setOf(
            STATUS_PENDING, STATUS_VERIFIED, STATUS_REJECTED, STATUS_EXPIRED,
        )
    }

    init {
        require(type in VALID_TYPES) { "unknown document type $type" }
        require(status in VALID_STATUSES) { "unknown document status $status" }
    }

    fun verify(verificationId: UUID, at: Instant) {
        check(status == STATUS_PENDING) { "cannot verify document in status $status" }
        status = STATUS_VERIFIED
        this.verificationId = verificationId
        verifiedAt = at
    }

    fun reject(reason: String, @Suppress("UNUSED_PARAMETER") at: Instant) {
        check(status == STATUS_PENDING) { "cannot reject document in status $status" }
        require(reason.isNotBlank()) { "rejection reason required" }
        status = STATUS_REJECTED
        rejectedReason = reason
    }

    fun expire(@Suppress("UNUSED_PARAMETER") at: Instant) {
        check(status == STATUS_VERIFIED) { "cannot expire document in status $status" }
        status = STATUS_EXPIRED
    }
}
