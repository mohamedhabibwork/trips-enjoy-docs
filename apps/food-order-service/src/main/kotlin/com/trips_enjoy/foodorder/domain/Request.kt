package com.trips_enjoy.foodorder.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.time.Instant
import java.util.UUID

/**
 * The order request aggregate — pre-acceptance. Mirrors
 * `food_order.requests` per docs/services/food-order-service/ERD.md §3.
 *
 * 12-state lifecycle:
 *   draft → priced → placed → accepted → preparing → ready → picked_up →
 *   delivered → cancelled / rejected / expired / no_show
 *
 * Single-UUID PK (NOT composite @IdClass) per the canonical
 * lift-forward pattern from trip-service.
 */
@Entity
@Table(name = "requests", schema = "food_order")
class Request(
    @Id val id: UUID,
    @Column(name = "customer_id", nullable = false) val customerId: UUID,
    @Column(name = "restaurant_id", nullable = false) val restaurantId: UUID,
    @Column(name = "branch_id") var branchId: UUID? = null,
    @Column(name = "order_type", nullable = false) var orderType: String = ORDER_TYPE_DELIVERY,
    @Column(nullable = false) var status: String = STATUS_DRAFT,
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "quote_snapshot", columnDefinition = "jsonb") var quoteSnapshot: Map<String, Any?>? = null,
    @Column(name = "total_minor") var totalMinor: Long? = null,
    @Column(nullable = false) var currency: String = "USD",
    @Column(name = "idempotency_key") val idempotencyKey: String? = null,
    @Column(name = "correlation_id", nullable = false) var correlationId: UUID = UUID.randomUUID(),
    @Column(name = "placed_at", nullable = false) var placedAt: Instant = Instant.now(),
    @Column(name = "expires_at") var expiresAt: Instant? = null,
    @Column(name = "accepted_at") var acceptedAt: Instant? = null,
    @Column(name = "rejected_at") var rejectedAt: Instant? = null,
    @Column(name = "cancelled_at") var cancelledAt: Instant? = null,
    @Column(name = "cancellation_reason") var cancellationReason: String? = null,
    @Column(name = "row_version", nullable = false) var rowVersion: Long = 1L,
    @Column(name = "created_at", nullable = false) val createdAt: Instant = Instant.now(),
    @Column(name = "updated_at", nullable = false) var updatedAt: Instant = Instant.now(),
    @Column(name = "created_by", nullable = false) val createdBy: UUID,
    @Column(name = "updated_by", nullable = false) var updatedBy: UUID = createdBy,
    @Column(name = "deleted_at") var deletedAt: Instant? = null,
) {
    companion object {
        const val ORDER_TYPE_DELIVERY = "delivery"
        const val ORDER_TYPE_PICKUP = "pickup"
        const val ORDER_TYPE_DINE_IN = "dine_in"

        const val STATUS_DRAFT = "draft"
        const val STATUS_PRICED = "priced"
        const val STATUS_PLACED = "placed"
        const val STATUS_ACCEPTED = "accepted"
        const val STATUS_REJECTED = "rejected"
        const val STATUS_PREPARING = "preparing"
        const val STATUS_READY = "ready"
        const val STATUS_PICKED_UP = "picked_up"
        const val STATUS_DELIVERED = "delivered"
        const val STATUS_CANCELLED = "cancelled"
        const val STATUS_EXPIRED = "expired"
        const val STATUS_NO_SHOW = "no_show"

        val VALID_ORDER_TYPES: Set<String> = setOf(
            ORDER_TYPE_DELIVERY, ORDER_TYPE_PICKUP, ORDER_TYPE_DINE_IN,
        )
        val VALID_STATUSES: Set<String> = setOf(
            STATUS_DRAFT, STATUS_PRICED, STATUS_PLACED, STATUS_ACCEPTED,
            STATUS_REJECTED, STATUS_PREPARING, STATUS_READY, STATUS_PICKED_UP,
            STATUS_DELIVERED, STATUS_CANCELLED, STATUS_EXPIRED, STATUS_NO_SHOW,
        )
        val TERMINAL_STATUSES: Set<String> = setOf(
            STATUS_DELIVERED, STATUS_CANCELLED, STATUS_REJECTED, STATUS_EXPIRED,
        )
    }

    init {
        require(orderType in VALID_ORDER_TYPES) { "unknown order_type $orderType" }
        require(status in VALID_STATUSES) { "unknown status $status" }
    }

    fun price(totalMinor: Long, snapshot: Map<String, Any?>, currency: String, at: Instant) {
        check(status == STATUS_DRAFT) { "cannot price request in status $status" }
        check(totalMinor > 0) { "total_minor must be > 0" }
        this.totalMinor = totalMinor
        this.quoteSnapshot = snapshot
        this.currency = currency
        status = STATUS_PRICED
        updatedAt = at
        rowVersion += 1
    }

    fun place(at: Instant) {
        check(status in setOf(STATUS_DRAFT, STATUS_PRICED)) { "cannot place in status $status" }
        status = STATUS_PLACED
        placedAt = at
        updatedAt = at
        rowVersion += 1
    }

    fun accept(at: Instant) {
        check(status == STATUS_PLACED) { "cannot accept in status $status" }
        status = STATUS_ACCEPTED
        acceptedAt = at
        updatedAt = at
        rowVersion += 1
    }

    fun reject(reason: String, at: Instant) {
        check(status == STATUS_PLACED) { "cannot reject in status $status" }
        status = STATUS_REJECTED
        rejectedAt = at
        cancellationReason = reason
        updatedAt = at
        rowVersion += 1
    }

    fun cancel(reason: String, at: Instant) {
        check(status !in TERMINAL_STATUSES) { "cannot cancel a terminal request" }
        status = STATUS_CANCELLED
        cancelledAt = at
        cancellationReason = reason
        updatedAt = at
        rowVersion += 1
    }
}