package com.trips_enjoy.payment.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/**
 * The customer wallet aggregate — one row per (customer_id, wallet_kind, currency).
 *
 * Mirrors `payment.wallets` per docs/services/payment-service/ERD.md §3.
 * `balance_minor` and `held_balance_minor` are denormalised counters
 * maintained by the WalletService whenever a `WalletEntry` is posted.
 * The customer wallet cannot go below 0 (enforced by the
 * `wallets_balance_minor_check` constraint and the application-layer
 * pre-credit check).
 */
@Entity
@Table(name = "wallets", schema = "payment")
class Wallet(
    @Id val id: UUID,
    @Column(name = "customer_id", nullable = false) val customerId: UUID,
    @Column(name = "wallet_kind", nullable = false) val walletKind: String = KIND_CUSTOMER,
    @Column(nullable = false, length = 3) val currency: String,
    @Column(nullable = false) var state: String = STATE_ACTIVE,
    @Column(name = "balance_minor", nullable = false) var balanceMinor: Long = 0L,
    @Column(name = "held_balance_minor", nullable = false) var heldBalanceMinor: Long = 0L,
    @Column(name = "last_entry_id") var lastEntryId: UUID? = null,
    @Column(name = "last_activity_at") var lastActivityAt: Instant? = null,
    @Column(name = "row_version", nullable = false) var rowVersion: Long = 1L,
    @Column(name = "created_at", nullable = false) val createdAt: Instant = Instant.now(),
    @Column(name = "updated_at", nullable = false) var updatedAt: Instant = Instant.now(),
    @Column(name = "created_by", nullable = false) val createdBy: UUID,
    @Column(name = "updated_by", nullable = false) var updatedBy: UUID,
    @Column(name = "deleted_at") var deletedAt: Instant? = null,
) {
    companion object {
        const val KIND_CUSTOMER = "customer"
        const val KIND_DRIVER = "driver"
        const val KIND_COURIER = "courier"
        const val KIND_MERCHANT = "merchant"
        const val KIND_PLATFORM = "platform"

        const val STATE_ACTIVE = "active"
        const val STATE_FROZEN = "frozen"
        const val STATE_CLOSED = "closed"

        val VALID_KINDS: Set<String> = setOf(KIND_CUSTOMER, KIND_DRIVER, KIND_COURIER, KIND_MERCHANT, KIND_PLATFORM)
        val VALID_STATES: Set<String> = setOf(STATE_ACTIVE, STATE_FROZEN, STATE_CLOSED)
    }

    fun ensureActive() {
        check(state == STATE_ACTIVE) { "wallet $id is $state, not active" }
    }

    /**
     * Apply a credit (positive amount). The caller is responsible for the
     * matching `WalletEntry` row that backs this credit.
     */
    fun credit(amountMinor: Long, entryId: UUID, at: Instant) {
        require(amountMinor > 0) { "credit amount must be positive" }
        ensureActive()
        balanceMinor += amountMinor
        lastEntryId = entryId
        lastActivityAt = at
        rowVersion += 1
        updatedAt = at
    }

    /**
     * Apply a debit (positive amount). Pre-checks the balance so a customer
     * wallet cannot go below 0. Returns the resulting balance.
     */
    fun debit(amountMinor: Long, entryId: UUID, at: Instant): Long {
        require(amountMinor > 0) { "debit amount must be positive" }
        ensureActive()
        require(balanceMinor >= amountMinor) {
            "insufficient balance: wallet $id has $balanceMinor, debit $amountMinor"
        }
        balanceMinor -= amountMinor
        lastEntryId = entryId
        lastActivityAt = at
        rowVersion += 1
        updatedAt = at
        return balanceMinor
    }

    fun freeze(at: Instant) {
        check(state == STATE_ACTIVE) { "cannot freeze wallet in state $state" }
        state = STATE_FROZEN
        updatedAt = at
    }

    fun unfreeze(at: Instant) {
        check(state == STATE_FROZEN) { "cannot unfreeze wallet in state $state" }
        state = STATE_ACTIVE
        updatedAt = at
    }

    fun close(at: Instant) {
        check(state != STATE_CLOSED) { "wallet already closed" }
        check(balanceMinor == 0L) { "cannot close wallet with non-zero balance $balanceMinor" }
        state = STATE_CLOSED
        updatedAt = at
    }
}