package com.trips_enjoy.payment.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/**
 * The merchant settlement aggregate — one row per (merchant_id, period_start).
 * Mirrors `payment.merchant_settlements` per docs/services/payment-service/ERD.md §3.
 * Period kinds: weekly by default. Settlements aggregate food-order revenue
 * minus platform commission minus adjustments/refund reversals.
 */
@Entity
@Table(name = "merchant_settlements", schema = "payment")
class MerchantSettlement(
    @Id val id: UUID,
    @Column(name = "merchant_id", nullable = false) val merchantId: UUID,
    @Column(name = "period_start", nullable = false) val periodStart: Instant,
    @Column(name = "period_end", nullable = false) val periodEnd: Instant,
    @Column(nullable = false, length = 3) val currency: String,
    @Column(name = "orders_count", nullable = false) var ordersCount: Int = 0,
    @Column(name = "gross_revenue_minor", nullable = false) var grossRevenueMinor: Long = 0L,
    @Column(name = "commission_minor", nullable = false) var commissionMinor: Long = 0L,
    @Column(name = "adjustments_minor", nullable = false) var adjustmentsMinor: Long = 0L,
    @Column(name = "refund_reversal_minor", nullable = false) var refundReversalMinor: Long = 0L,
    @Column(name = "net_payout_minor", nullable = false) var netPayoutMinor: Long = 0L,
    @Column(name = "paid_out_at") var paidOutAt: Instant? = null,
    @Column(name = "payout_reference") var payoutReference: String? = null,
    @Column(nullable = false) var state: String = STATE_OPEN,
    @Column(name = "row_version", nullable = false) var rowVersion: Long = 1L,
    @Column(name = "created_at", nullable = false) val createdAt: Instant = Instant.now(),
    @Column(name = "updated_at", nullable = false) var updatedAt: Instant = Instant.now(),
    @Column(name = "created_by", nullable = false) val createdBy: UUID,
    @Column(name = "updated_by", nullable = false) var updatedBy: UUID,
) {
    companion object {
        const val STATE_OPEN = "open"
        const val STATE_FINALIZED = "finalized"
        const val STATE_PAID_OUT = "paid_out"
        const val STATE_DISPUTED = "disputed"
    }

    /**
     * Per TYPE_CATALOG.md §8.7 "Platform margin doctrine":
     *   net_payout = gross_revenue + adjustments - commission - refund_reversal
     */
    fun applyOrderLine(
        grossRevenueMinor: Long, commissionMinor: Long,
        adjustmentMinor: Long, refundReversalMinor: Long, at: Instant,
    ) {
        check(state == STATE_OPEN) { "cannot add lines to a $state settlement" }
        ordersCount += 1
        this.grossRevenueMinor += grossRevenueMinor
        this.commissionMinor += commissionMinor
        this.adjustmentsMinor += adjustmentMinor
        this.refundReversalMinor += refundReversalMinor
        recomputeNetPayout()
        updatedAt = at
        rowVersion += 1
    }

    private fun recomputeNetPayout() {
        netPayoutMinor = grossRevenueMinor + adjustmentsMinor - commissionMinor - refundReversalMinor
    }

    fun finalize(at: Instant) {
        check(state == STATE_OPEN) { "cannot finalize $state settlement" }
        state = STATE_FINALIZED
        updatedAt = at
    }

    fun markPaidOut(reference: String, at: Instant) {
        check(state == STATE_FINALIZED) { "cannot pay out $state settlement" }
        require(reference.isNotBlank()) { "payout reference required" }
        state = STATE_PAID_OUT
        paidOutAt = at
        payoutReference = reference
        updatedAt = at
    }

    fun dispute(at: Instant) {
        check(state in setOf(STATE_FINALIZED, STATE_PAID_OUT)) {
            "cannot dispute $state settlement"
        }
        state = STATE_DISPUTED
        updatedAt = at
    }
}