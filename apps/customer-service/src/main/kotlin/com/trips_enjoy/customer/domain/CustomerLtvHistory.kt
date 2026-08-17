package com.trips_enjoy.customer.domain

import jakarta.persistence.Column
import jakarta.persistence.Embeddable
import jakarta.persistence.EmbeddedId
import jakarta.persistence.Entity
import jakarta.persistence.Table
import java.io.Serializable
import java.time.Instant
import java.util.UUID

/**
 * Composite PK for the partitioned `customer_ltv_history` table — the
 * partition key (`occurred_at`) MUST be part of the PK per
 * docs/architecture/DATABASE_ARCHITECTURE.md §12 (canonical partitioning
 * template).
 */
@Embeddable
data class CustomerLtvHistoryPk(
    @Column(name = "id", nullable = false) val id: UUID,
    @Column(name = "occurred_at", nullable = false) val occurredAt: Instant,
) : Serializable

/**
 * Append-only LTV delta row. One row per `*.payment.completed.v1` event
 * (positive), refund event (negative), or admin adjustment.
 */
@Entity
@Table(name = "customer_ltv_history", schema = "customer")
class CustomerLtvHistory(
    @EmbeddedId val pk: CustomerLtvHistoryPk,
    @Column(name = "customer_id", nullable = false) val customerId: UUID,
    @Column(name = "delta_minor", nullable = false) val deltaMinor: Long,
    @Column(name = "currency", nullable = false, length = 3) val currency: String,
    @Column(name = "service", nullable = false) val service: String,
    @Column(name = "request_id") val requestId: UUID? = null,
)
