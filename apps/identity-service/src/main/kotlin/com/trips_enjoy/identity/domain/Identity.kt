package com.trips_enjoy.identity.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.Version
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "identities", schema = "identity")
class Identity(
    @Id
    val id: UUID,
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
    @Column(name = "deleted_at")
    var deletedAt: Instant? = null,
    @Column(name = "created_by", nullable = false)
    var createdBy: UUID,
    @Column(name = "updated_by", nullable = false)
    var updatedBy: UUID,
    @Column(name = "created_at", nullable = false)
    val createdAt: Instant = Instant.now(),
    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now(),
    @Version
    @Column(name = "row_version", nullable = false)
    var rowVersion: Long = 0,
)
