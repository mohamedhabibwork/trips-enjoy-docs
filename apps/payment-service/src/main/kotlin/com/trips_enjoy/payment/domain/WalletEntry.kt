package com.trips_enjoy.payment.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/**
 * One double-entry ledger line for a wallet. Append-only by V4 trigger.
 *
 * Mirrors `payment.wallet_entries` per docs/services/payment-service/ERD.md §3.
 * The canonical idempotency primitive is `(event_id, source)` — the unique
 * index on those two columns. The amount is always positive; the
 * `direction` column records whether it's a credit or debit, and
 * `balance_after_minor` records the resulting wallet balance (for audit
 * and reconciliation).
 */
@Entity
@Table(name = "wallet_entries", schema = "payment")
class WalletEntry(
    @Id val id: UUID,
    @Column(name = "wallet_id", nullable = false) val walletId: UUID,
    @Column(name = "event_id", nullable = false) val eventId: UUID,
    @Column(nullable = false) val direction: String,
    @Column(name = "amount_minor", nullable = false) val amountMinor: Long,
    @Column(name = "balance_after_minor", nullable = false) val balanceAfterMinor: Long,
    @Column(nullable = false, length = 3) val currency: String,
    @Column(nullable = false) val source: String,
    @Column(name = "source_id") val sourceId: UUID? = null,
    @Column var description: String? = null,
    @Column(name = "correlation_id", nullable = false) val correlationId: UUID,
    @Column(name = "posted_at", nullable = false) val postedAt: Instant = Instant.now(),
    @Column(name = "created_by", nullable = false) val createdBy: UUID,
) {
    companion object {
        const val DIRECTION_CREDIT = "credit"
        const val DIRECTION_DEBIT = "debit"

        const val SOURCE_PAYMENT_CAPTURE = "payment_capture"
        const val SOURCE_REFUND = "refund"
        const val SOURCE_REWARD_GRANT = "reward_grant"
        const val SOURCE_REWARD_REVERSAL = "reward_reversal"
        const val SOURCE_MANUAL_ADJUSTMENT = "manual_adjustment"
        const val SOURCE_WALLET_TOPUP = "wallet_topup"
        const val SOURCE_WALLET_TRANSFER = "wallet_transfer"
        const val SOURCE_MERCHANT_PAYOUT = "merchant_payout"
        const val SOURCE_DRIVER_PAYOUT = "driver_payout"
        const val SOURCE_COURIER_PAYOUT = "courier_payout"
        const val SOURCE_PLATFORM_COMMISSION = "platform_commission"

        val VALID_SOURCES: Set<String> = setOf(
            SOURCE_PAYMENT_CAPTURE, SOURCE_REFUND, SOURCE_REWARD_GRANT, SOURCE_REWARD_REVERSAL,
            SOURCE_MANUAL_ADJUSTMENT, SOURCE_WALLET_TOPUP, SOURCE_WALLET_TRANSFER,
            SOURCE_MERCHANT_PAYOUT, SOURCE_DRIVER_PAYOUT, SOURCE_COURIER_PAYOUT,
            SOURCE_PLATFORM_COMMISSION
        )
    }

    init {
        require(amountMinor > 0) { "amount_minor must be positive" }
        require(balanceAfterMinor >= 0) { "balance_after_minor must be >= 0" }
        require(source in VALID_SOURCES) { "unknown source $source" }
    }
}