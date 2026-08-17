package com.trips_enjoy.customer.domain

import com.trips_enjoy.platform.data.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.time.Instant
import java.util.UUID

/**
 * The customer aggregate — one row per platform customer.
 *
 * Mirrors `customer.customers` per docs/services/customer-service/ERD.md §3.
 * PII columns (`name`, `email`, `phone`) are stored as plain TEXT today; the
 * envelope-encryption contract is at the application boundary (TECH §6).
 * Custody regions: `id` is UUIDv7 (assigned by `BaseEntity`), `identity_id`
 * is the cross-service ref to `identity-service`, all other *_id fields are
 * cross-service UUIDs WITHOUT database FKs (DATA--003).
 *
 * Phase C (platform DRY): extends [BaseEntity] so the `id`, `createdAt`,
 * `updatedAt`, `createdBy`, `updatedBy`, `version`, and `deletedAt` columns
 * are inherited from the platform canonical shape. The corresponding column
 * migration is V8 (`created_by` / `updated_by` `UUID` → `VARCHAR(255)`,
 * `row_version` → `version`).
 */
@Entity
@Table(name = "customers", schema = "customer")
class Customer(
    @Column(name = "identity_id", nullable = false) val identityId: UUID,
    @Column var name: String? = null,
    @Column var email: String? = null,
    @Column var phone: String? = null,
    @Column(name = "kyc_tier", nullable = false) var kycTier: String = "tier_0",
    @Column(name = "kyc_verification_id") var kycVerificationId: UUID? = null,
    @Column(name = "kyc_verified_at") var kycVerifiedAt: Instant? = null,
    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "kyc_document_file_ids", nullable = false, columnDefinition = "uuid[]")
    var kycDocumentFileIds: Array<UUID> = emptyArray(),
    @Column(name = "default_payment_method_id") var defaultPaymentMethodId: UUID? = null,
    @Column(name = "default_address_id") var defaultAddressId: UUID? = null,
    @Column(name = "primary_city_id") var primaryCityId: UUID? = null,
    @Column(name = "ltv_minor", nullable = false) var ltvMinor: Long = 0L,
    @Column(name = "ltv_currency", nullable = false, length = 3) var ltvCurrency: String = "USD",
    @Column(name = "ltv_updated_at") var ltvUpdatedAt: Instant? = null,
    @Column(nullable = false) var segment: String = "standard",
    @Column(name = "segment_updated_at") var segmentUpdatedAt: Instant? = null,
    @Column(name = "rides_this_month", nullable = false) var ridesThisMonth: Int = 0,
    @Column(name = "last_active_at") var lastActiveAt: Instant? = null,
    @Column(nullable = false) var status: String = "active",
    @Column(name = "suspended_reason") var suspendedReason: String? = null,
    @Column(name = "suspended_at") var suspendedAt: Instant? = null,
    @Column(name = "suspended_by") var suspendedBy: UUID? = null,
    @Column(name = "disabled_at") var disabledAt: Instant? = null,
    @Column(name = "erased_at") var erasedAt: Instant? = null,
) : BaseEntity()
