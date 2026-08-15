package com.trips_enjoy.payment.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/**
 * The driver period-earnings aggregate — one row per (driver_id,
 * period_kind, period_start). Mirrors `payment.driver_earnings` per
 * docs/services/payment-service/ERD.md §3. Period kinds: hourly / daily /
 * weekly / monthly. Lines are added as ride completions and reward grants
 * arrive. State transitions open -> finalized -> paid_out (with disputed
 * as a terminal state).
 */
@Entity
@Table(name = "driver_earnings", schema = "payment")
class DriverEarnings(
    @Id val id: UUID,
    @Column(name = "driver_id", nullable = false) val driverId: UUID,
    @Column(name = "period_kind", nullable = false) val periodKind: String,
    @Column(name = "period_start", nullable = false) val periodStart: Instant,
    @Column(name = "period_end", nullable = false) val periodEnd: Instant,
    @Column(nullable = false, length = 3) val currency: String,
    @Column(name = "rides_count", nullable = false) var ridesCount: Int = 0,
    @Column(name = "gross_fare_minor", nullable = false) var grossFareMinor: Long = 0L,
    @Column(name = "commission_minor", nullable = false) var commissionMinor: Long = 0L,
    @Column(name = "tip_minor", nullable = false) var tipMinor: Long = 0L,
    @Column(name = "bonus_minor", nullable = false) var bonusMinor: Long = 0L,
    @Column(name = "guaranteed_topup_minor", nullable = false) var guaranteedTopupMinor: Long = 0L,
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

    init {
        require(periodKind in setOf(PERIOD_HOURLY, PERIOD_DAILY, PERIOD_WEEKLY, PERIOD_MONTHLY)) {
            "unknown period_kind $periodKind"
        }
        require(periodEnd > periodStart) { "period_end must be after period_start" }
    }

    /**
     * Apply a ride earnings line. Increments the matching counter, the
     * total rides count, and recomputes net_pay_minor.
     */
    fun applyRideLine(
        grossFareMinor: Long,
        commissionMinor: Long,
        tipMinor: Long,
        bonusMinor: Long,
        guaranteedTopupMinor: Long,
        correctionMinor: Long,
        at: Instant,
    ) {
        check(state == STATE_OPEN) { "cannot add lines to a $state earnings period" }
        require(grossFareMinor >= 0) { "grossFareMinor must be >= 0" }
        require(commissionMinor >= 0) { "commissionMinor must be >= 0" }
        require(tipMinor >= 0) { "tipMinor must be >= 0" }
        require(bonusMinor >= 0) { "bonusMinor must be >= 0" }
        require(guaranteedTopupMinor >= 0) { "guaranteedTopupMinor must be >= 0" }
        require(correctionMinor >= 0) { "correctionMinor must be >= 0" }

        ridesCount += 1
        this.grossFareMinor += grossFareMinor
        this.commissionMinor += commissionMinor
        this.tipMinor += tipMinor
        this.bonusMinor += bonusMinor
        this.guaranteedTopupMinor += guaranteedTopupMinor
        this.correctionMinor += correctionMinor
        recomputeNetPay()
        updatedAt = at
        rowVersion += 1
    }

    /**
     * Per TYPE_CATALOG.md §8.7 "Platform margin doctrine":
     *   net_pay = gross_fare + tip + bonus + guaranteed_topup + correction - commission
     */
    private fun recomputeNetPay() {
        netPayMinor = grossFareMinor + tipMinor + bonusMinor + guaranteedTopupMinor + correctionMinor - commissionMinor
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