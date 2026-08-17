package com.trips_enjoy.courier.domain

import com.trips_enjoy.platform.data.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/**
 * A courier KYC document. Mirrors `courier.courier_documents` per
 * docs/services/courier-service/ERD.md §3. Append-only in spirit —
 * the only mutable columns are `status` (pending → verified/rejected/
 * expired) and `verification_id` + `verified_at` (set by the
 * KYC provider callback).
 *
 * The courier document type list adds `id` vs driver-service (per the
 * courier ERD §3: 'id' / 'license' / 'vehicle_reg' / 'insurance' /
 * 'selfie' / 'background_check' / 'medical' / 'permit').
 *
 * Phase C (platform DRY): extends [BaseEntity]. See V6 migration
 * (`created_by` / `updated_by` `UUID` → `VARCHAR(255)`,
 * `row_version` → `version`).
 */
@Entity
@Table(name = "courier_documents", schema = "courier")
class CourierDocument(
    @Column(name = "courier_id", nullable = false) val courierId: UUID,
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
        const val TYPE_ID = "id"
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
            TYPE_ID, TYPE_LICENSE, TYPE_VEHICLE_REG, TYPE_INSURANCE, TYPE_SELFIE,
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
        updatedAt = at
        version += 1
    }

    fun reject(reason: String, at: Instant) {
        check(status == STATUS_PENDING) { "cannot reject document in status $status" }
        require(reason.isNotBlank()) { "rejection reason required" }
        status = STATUS_REJECTED
        rejectedReason = reason
        updatedAt = at
        version += 1
    }

    fun expire(at: Instant) {
        check(status == STATUS_VERIFIED) { "cannot expire document in status $status" }
        status = STATUS_EXPIRED
        updatedAt = at
        version += 1
    }
}
