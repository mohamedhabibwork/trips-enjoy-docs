package com.trips_enjoy.foodorder.domain

import com.trips_enjoy.platform.data.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Table
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

/**
 * The active food-order aggregate (after restaurant accepts). Mirrors
 * `food_order.orders` per docs/services/food-order-service/ERD.md §3.
 *
 * 8-state lifecycle (after acceptance):
 *   pending → accepted → preparing → ready → picked_up → delivered
 *   pending → cancelled (any time before picked_up)
 *   picked_up → no_show (courier couldn't find customer)
 *
 * Single-UUID PK (NOT composite @IdClass) per the canonical
 * lift-forward pattern.
 *
 * Phase C (platform DRY): extends [BaseEntity] so the `id`, `createdAt`,
 * `updatedAt`, `createdBy`, `updatedBy`, `version`, and `deletedAt`
 * columns are inherited from the platform canonical shape. The
 * corresponding column migration is V5 (`created_by` / `updated_by`
 * `UUID` → `VARCHAR(255)`, `row_version` → `version`). `version` is
 * the optimistic-lock counter; `createdBy` / `updatedBy` are
 * auto-populated by `PlatformAuditorAware` from the JWT `sub` and
 * stored as `String?`.
 */
@Entity
@Table(name = "orders", schema = "food_order")
class Order(
    @Column(name = "request_id", nullable = false) val requestId: UUID,
    @Column(name = "customer_id", nullable = false) val customerId: UUID,
    @Column(name = "restaurant_id", nullable = false) val restaurantId: UUID,
    @Column(name = "branch_id") var branchId: UUID? = null,
    @Column(name = "courier_id") var courierId: UUID? = null,
    @Column(name = "order_type", nullable = false) var orderType: String = Request.ORDER_TYPE_DELIVERY,
    @Column(nullable = false) var status: String = STATUS_PENDING,
    @Column(name = "total_minor", nullable = false) var totalMinor: Long,
    @Column(nullable = false) var currency: String = "USD",
    @Column(name = "delivery_address") var deliveryAddress: String? = null,
    @Column(name = "delivery_zone_id") var deliveryZoneId: UUID? = null,
    @Column(name = "distance_km") var distanceKm: BigDecimal? = null,
    @Column(name = "estimated_delivery_at") var estimatedDeliveryAt: Instant? = null,
    @Column(name = "delivered_at") var deliveredAt: Instant? = null,
    @Column(name = "correlation_id", nullable = false) var correlationId: UUID = UUID.randomUUID(),
    @Column(name = "idempotency_key") val idempotencyKey: String? = null,
    @Column(name = "placed_at") var placedAt: Instant? = null,
    @Column(name = "accepted_at") var acceptedAt: Instant? = null,
    @Column(name = "preparing_at") var preparingAt: Instant? = null,
    @Column(name = "ready_at") var readyAt: Instant? = null,
    @Column(name = "picked_up_at") var pickedUpAt: Instant? = null,
    @Column(name = "cancelled_at") var cancelledAt: Instant? = null,
    @Column(name = "cancellation_reason") var cancellationReason: String? = null,
) : BaseEntity() {
    companion object {
        const val STATUS_PENDING = "pending"
        const val STATUS_ACCEPTED = "accepted"
        const val STATUS_PREPARING = "preparing"
        const val STATUS_READY = "ready"
        const val STATUS_PICKED_UP = "picked_up"
        const val STATUS_DELIVERED = "delivered"
        const val STATUS_CANCELLED = "cancelled"
        const val STATUS_NO_SHOW = "no_show"

        val TERMINAL_STATUSES: Set<String> = setOf(
            STATUS_DELIVERED, STATUS_CANCELLED, STATUS_NO_SHOW,
        )
    }

    init {
        require(totalMinor > 0) { "total_minor must be > 0" }
        require(status in setOf(STATUS_PENDING, STATUS_ACCEPTED, STATUS_PREPARING,
            STATUS_READY, STATUS_PICKED_UP, STATUS_DELIVERED, STATUS_CANCELLED, STATUS_NO_SHOW)) {
            "unknown status $status"
        }
    }

    fun assignCourier(courierId: UUID, at: Instant) {
        check(status == STATUS_READY) { "cannot assign courier in status $status" }
        check(this.courierId == null) { "courier already assigned" }
        this.courierId = courierId
        this.updatedAt = at
        this.version += 1
    }

    fun accept(at: Instant) {
        check(status == STATUS_PENDING) { "cannot accept order in status $status" }
        status = STATUS_ACCEPTED
        acceptedAt = at
        this.updatedAt = at
        this.version += 1
    }

    fun startPreparing(at: Instant) {
        check(status == STATUS_ACCEPTED) { "cannot start preparing in status $status" }
        status = STATUS_PREPARING
        preparingAt = at
        this.updatedAt = at
        this.version += 1
    }

    fun markReady(at: Instant) {
        check(status == STATUS_PREPARING) { "cannot mark ready in status $status" }
        status = STATUS_READY
        readyAt = at
        this.updatedAt = at
        this.version += 1
    }

    fun markPickedUp(at: Instant) {
        check(status == STATUS_READY) { "cannot pick up in status $status" }
        require(courierId != null) { "no courier assigned" }
        status = STATUS_PICKED_UP
        pickedUpAt = at
        this.updatedAt = at
        this.version += 1
    }

    fun markDelivered(at: Instant) {
        check(status == STATUS_PICKED_UP) { "cannot deliver in status $status" }
        status = STATUS_DELIVERED
        deliveredAt = at
        this.updatedAt = at
        this.version += 1
    }

    fun cancel(reason: String, at: Instant) {
        check(status !in TERMINAL_STATUSES) { "cannot cancel terminal order" }
        check(status != STATUS_DELIVERED) { "cannot cancel a delivered order" }
        status = STATUS_CANCELLED
        cancelledAt = at
        cancellationReason = reason
        this.updatedAt = at
        this.version += 1
    }

    fun markNoShow(at: Instant, reason: String) {
        check(status == STATUS_PICKED_UP) { "cannot no-show in status $status" }
        status = STATUS_NO_SHOW
        cancelledAt = at
        cancellationReason = reason
        this.updatedAt = at
        this.version += 1
    }
}