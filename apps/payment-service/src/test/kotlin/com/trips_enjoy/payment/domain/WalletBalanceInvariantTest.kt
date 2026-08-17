package com.trips_enjoy.payment.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/**
 * Unit tests for the Wallet aggregate. Covers:
 *   * credit increments balance
 *   * debit decrements balance
 *   * debit below 0 is rejected (the canonical customer-wallet invariant)
 *   * freeze / unfreeze / close lifecycle
 *   * close with non-zero balance is rejected
 *   * invalid amounts are rejected
 */
class WalletBalanceInvariantTest {

    private val now = Instant.parse("2026-08-15T12:00:00Z")
    private val systemUser = UUID.randomUUID()

    private fun newWallet(balance: Long = 0L): Wallet = Wallet(
        id = UUID.randomUUID(),
        customerId = UUID.randomUUID(),
        walletKind = Wallet.KIND_CUSTOMER,
        currency = "USD",
        balanceMinor = balance,
        createdBy = systemUser,
        updatedBy = systemUser,
    )

    @Test
    fun `credit increments balance`() {
        val wallet = newWallet()
        val entryId = UUID.randomUUID()
        wallet.credit(1000L, entryId, now)
        assertEquals(1000L, wallet.balanceMinor)
        assertEquals(entryId, wallet.lastEntryId)
        assertEquals(now, wallet.lastActivityAt)
    }

    @Test
    fun `credit rejects zero amount`() {
        val wallet = newWallet()
        assertThrows(IllegalArgumentException::class.java) {
            wallet.credit(0L, UUID.randomUUID(), now)
        }
    }

    @Test
    fun `credit rejects negative amount`() {
        val wallet = newWallet()
        assertThrows(IllegalArgumentException::class.java) {
            wallet.credit(-100L, UUID.randomUUID(), now)
        }
    }

    @Test
    fun `debit decrements balance`() {
        val wallet = newWallet(5000L)
        val newBalance = wallet.debit(2000L, UUID.randomUUID(), now)
        assertEquals(3000L, newBalance)
        assertEquals(3000L, wallet.balanceMinor)
    }

    @Test
    fun `debit to exact zero is allowed`() {
        val wallet = newWallet(1000L)
        val newBalance = wallet.debit(1000L, UUID.randomUUID(), now)
        assertEquals(0L, newBalance)
    }

    @Test
    fun `debit below zero is rejected`() {
        val wallet = newWallet(500L)
        val ex = assertThrows(IllegalArgumentException::class.java) {
            wallet.debit(1000L, UUID.randomUUID(), now)
        }
        assertTrue(ex.message!!.contains("insufficient balance"))
    }

    @Test
    fun `credit then debit cycle ends at zero`() {
        val wallet = newWallet()
        wallet.credit(1000L, UUID.randomUUID(), now)
        wallet.debit(500L, UUID.randomUUID(), now.plusSeconds(1))
        assertEquals(500L, wallet.balanceMinor)
        wallet.debit(500L, UUID.randomUUID(), now.plusSeconds(2))
        assertEquals(0L, wallet.balanceMinor)
    }

    @Test
    fun `freeze moves active to frozen`() {
        val wallet = newWallet()
        wallet.freeze(now)
        assertEquals(Wallet.STATE_FROZEN, wallet.state)
    }

    @Test
    fun `freeze from non-active is rejected`() {
        val wallet = newWallet()
        wallet.freeze(now)
        assertThrows(IllegalStateException::class.java) {
            wallet.freeze(now.plusSeconds(1))
        }
    }

    @Test
    fun `unfreeze moves frozen to active`() {
        val wallet = newWallet()
        wallet.freeze(now)
        wallet.unfreeze(now.plusSeconds(1))
        assertEquals(Wallet.STATE_ACTIVE, wallet.state)
    }

    @Test
    fun `credit on frozen wallet is rejected`() {
        val wallet = newWallet()
        wallet.freeze(now)
        assertThrows(IllegalStateException::class.java) {
            wallet.credit(100L, UUID.randomUUID(), now.plusSeconds(1))
        }
    }

    @Test
    fun `debit on frozen wallet is rejected`() {
        val wallet = newWallet(1000L)
        wallet.freeze(now)
        assertThrows(IllegalStateException::class.java) {
            wallet.debit(100L, UUID.randomUUID(), now.plusSeconds(1))
        }
    }

    @Test
    fun `close on zero-balance wallet succeeds`() {
        val wallet = newWallet()
        wallet.close(now)
        assertEquals(Wallet.STATE_CLOSED, wallet.state)
    }

    @Test
    fun `close on non-zero-balance wallet is rejected`() {
        val wallet = newWallet(100L)
        val ex = assertThrows(IllegalStateException::class.java) {
            wallet.close(now)
        }
        assertTrue(ex.message!!.contains("non-zero balance"))
    }

    @Test
    fun `close on already-closed is rejected`() {
        val wallet = newWallet()
        wallet.close(now)
        assertThrows(IllegalStateException::class.java) {
            wallet.close(now.plusSeconds(1))
        }
    }

    @Test
    fun `row_version increments on every mutation`() {
        val wallet = newWallet()
        val v0 = wallet.rowVersion
        wallet.credit(100L, UUID.randomUUID(), now)
        val v1 = wallet.rowVersion
        wallet.debit(50L, UUID.randomUUID(), now.plusSeconds(1))
        val v2 = wallet.rowVersion
        assertEquals(v0 + 1, v1)
        assertEquals(v1 + 1, v2)
    }
}