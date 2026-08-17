package com.trips_enjoy.customer.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/**
 * Append-only KYC tier change log. The database rejects UPDATE / DELETE
 * via the `customer_kyc_history_append_only` trigger installed in V3.
 */
@Entity
@Table(name = "customer_kyc_history", schema = "customer")
class CustomerKycHistory(
    @Id val id: UUID,
    @Column(name = "customer_id", nullable = false) val customerId: UUID,
    @Column(name = "from_tier") val fromTier: String? = null,
    @Column(name = "to_tier", nullable = false) val toTier: String,
    @Column(name = "verification_id") val verificationId: UUID? = null,
    @Column(name = "actor") val actor: UUID? = null,
    @Column(name = "reason") val reason: String? = null,
    @Column(name = "occurred_at", nullable = false) val occurredAt: Instant = Instant.now(),
)
