package com.trips_enjoy.payment.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/**
 * The courier period-earnings aggregate — same shape as `DriverEarnings`
 * but for courier deliveries (food-order-service drop-offs).
 * Mirrors `payment.courier_earnings` per docs/services/payment-service/ERD.md §3.
 */
@Entity
@Table(name = "courier_earnings", schema = "payment")
class CourierEarnings(
    @Id val id: UUID,
    @Column(name = "courier_id", nullable = false) val courierId: UUID,
    @Column(name = "period_kind", nullable = false) val periodKind: String,
    @Column(name = "period_start", nullable = false) val periodStart: Instant,
    @Column(name = "period_end", nullable = false) val periodEnd: Instant,
    @Column(nullable = false, length = 3) val currency: String,
    @Column(name = "deliveries_count", nullable = false) var deliveriesCount: Int = 0,
    @Column(name = "gross_fee_minor", nullable = false) var grossFeeMinor: Long = 0L,
    @Column(name = "commission_minor", nullable = false) var commissionMinor: Long = 0L,
    @Column(name = "tip_minor", nullable = false) var tipMinor: Long = 0L,
    @Column(name = "bonus_minor", nullable = false) var bonusMinor: Long = 0L,
    @Column(name = "correction_minor", nullable = false) var correctionMinor: Long = 0L,
    @Column(name = "net_pay_minor", nullable = false) var netPayMinor: Long = 0L,
    @Column(name = "paid_out_at") var paidOutAt: Instant? = null,
    @Column(nullable = false) var state: String = STATE_OPEN,
    @Column(name = "row_version", nullable = false) var rowVersion: Long = 1L,
    @Column(name = "created_at", nullable = false) val createdAt: Instant = Instant.now(),
    @Column(name = "updated_at", nullable = false) var updatedAt: Instant = Instant.now(),
    @Column(name = "created_by", nullable = false) val createdBy: UUID,
    @Column(name = "updated_by", nullable = false) var updatedBy: UUID,
) {
    companion object {
        const val PERIOD_HOURLY = "hourly"
        const val PERIOD_DAILY = "daily"
        const val PERIOD_WEEKLY = "weekly"
        const val PERIOD_MONTHLY = "monthly"

        const val STATE_OPEN = "open"
        const val STATE_FINALIZED = "finalized"
        const val STATE_PAID_OUT = "paid_out"
        const val STATE_DISPUTED = "disputed"
    }

    fun applyDeliveryLine(
        grossFeeMinor: Long, commissionMinor: Long, tipMinor: Long,
        bonusMinor: Long, correctionMinor: Long, at: Instant,
    ) {
        check(state == STATE_OPEN) { "cannot add lines to a $state earnings period" }
        deliveriesCount += 1
        this.grossFeeMinor += grossFeeMinor
        this.commissionMinor += commissionMinor
        this.tipMinor += tipMinor
        this.bonusMinor += bonusMinor
        this.correctionMinor += correctionMinor
        recomputeNetPay()
        updatedAt = at
        rowVersion += 1
    }

    private fun recomputeNetPay() {
        netPayMinor = grossFeeMinor + tipMinor + bonusMinor + correctionMinor - commissionMinor
        require(netPayMinor >= 0) { "net_pay_minor computed negative: $netPayMinor" }
    }

    fun finalize(at: Instant) {
        check(state == STATE_OPEN) { "cannot finalize $state earnings" }
        state = STATE_FINALIZED
        updatedAt = at
    }

    fun markPaidOut(at: Instant) {
        check(state == STATE_FINALIZED) { "cannot pay out $state earnings" }
        state = STATE_PAID_OUT
        paidOutAt = at
        updatedAt = at
    }

    fun dispute(at: Instant) {
        check(state in setOf(STATE_FINALIZED, STATE_PAID_OUT)) {
            "cannot dispute $state earnings"
        }
        state = STATE_DISPUTED
        updatedAt = at
    }
}