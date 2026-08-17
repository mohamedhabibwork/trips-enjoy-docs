package com.trips_enjoy.identity.domain

import com.trips_enjoy.platform.data.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/**
 * The identity aggregate — one row per platform identity.
 *
 * Mirrors `identity.identities` per docs/services/identity-service/ERD.md §3.
 * PII columns (`name`, `email`, `phone`) are stored as plain TEXT today; the
 * envelope-encryption contract is at the application boundary (TECH §6).
 *
 * Custody regions: `id` is UUIDv7 (assigned by `BaseEntity`); `kc_sub` is the
 * canonical Keycloak subject identifier (per-realm unique). All other *_id
 * fields (`customer_id`, `driver_id`, etc.) are cross-service UUIDs WITHOUT
 * database FKs (DATA--003).
 *
 * Phase C (platform DRY): extends [BaseEntity] so the `id`, `createdAt`,
 * `updatedAt`, `createdBy`, `updatedBy`, `version`, and `deletedAt` columns
 * are inherited from the platform canonical shape. The corresponding column
 * migration is V8 (`created_by` / `updated_by` `UUID` → `VARCHAR(255)`,
 * `row_version` → `version`).
 */
@Entity
@Table(name = "identities", schema = "identity")
class Identity(
    @Column(name = "kc_sub", nullable = false)
    var keycloakSubject: String,
    @Column(nullable = false)
    var realm: String,
    @Column(name = "user_type", nullable = false)
    var userType: String,
    @Column(nullable = false)
    @jakarta.persistence.Enumerated(jakarta.persistence.EnumType.STRING)
    var status: IdentityStatus = IdentityStatus.ACTIVE,
    var region: String? = null,
    @Column(name = "tenant_id")
    var tenantId: UUID? = null,
    @Column(name = "customer_id")
    var customerId: UUID? = null,
    @Column(name = "driver_id")
    var driverId: UUID? = null,
    @Column(name = "courier_id")
    var courierId: UUID? = null,
    @Column(name = "merchant_id")
    var merchantId: UUID? = null,
    @Column(name = "restaurant_staff_id")
    var restaurantStaffId: UUID? = null,
    var name: String? = null,
    var email: String? = null,
    @Column(name = "email_verified", nullable = false)
    var emailVerified: Boolean = false,
    var phone: String? = null,
    @Column(name = "phone_verified", nullable = false)
    var phoneVerified: Boolean = false,
    var locale: String? = null,
    @Column(name = "mfa_enabled", nullable = false)
    var mfaEnabled: Boolean = false,
    @Column(name = "suspended_reason")
    var suspendedReason: String? = null,
    @Column(name = "suspended_at")
    var suspendedAt: Instant? = null,
    @Column(name = "suspended_by")
    var suspendedBy: UUID? = null,
    @Column(name = "disabled_at")
    var disabledAt: Instant? = null,
    @Column(name = "disabled_by")
    var disabledBy: UUID? = null,
    @Column(name = "erased_at")
    var erasedAt: Instant? = null,
    @Column(name = "erased_by")
    var erasedBy: UUID? = null,
) : BaseEntity()