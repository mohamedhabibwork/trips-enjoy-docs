package com.trips_enjoy.payment.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/**
 * Unit tests for the PaymentIntent state machine. Covers:
 *   * Happy path: created → authorized → captured
 *   * Void from created / authorized
 *   * Refund (full + partial) from captured
 *   * Illegal transitions raise IllegalStateException
 *   * Fail from created / authorized sets state=failed
 */
class PaymentIntentStateMachineTest {

    private val now = Instant.parse("2026-08-15T12:00:00Z")
    private val systemUser = UUID.randomUUID()

    private fun newIntent(state: String = PaymentIntent.STATE_CREATED): PaymentIntent =
        PaymentIntent(
            id = UUID.randomUUID(),
            customerId = UUID.randomUUID(),
            requestId = UUID.randomUUID(),
            service = "trip",
            amountMinor = 2350L,
            currency = "EUR",
            gatewayId = "stripe",
            gatewayRegion = "eu-west",
            captureMode = "manual",
            state = state,
            correlationId = UUID.randomUUID(),
            createdBy = systemUser,
            updatedBy = systemUser,
        )

    @Test
    fun `authorize moves created to authorized`() {
        val intent = newIntent()
        val later = now.plusSeconds(60)
        intent.authorize(later, "pi_test123")
        assertEquals(PaymentIntent.STATE_AUTHORIZED, intent.state)
        assertEquals(later, intent.authorizedAt)
        assertEquals("pi_test123", intent.gatewayIntentId)
    }

    @Test
    fun `authorize rejects from unauthorized state`() {
        val intent = newIntent(PaymentIntent.STATE_AUTHORIZED)
        val ex = assertThrows(IllegalStateException::class.java) {
            intent.authorize(now, "pi_dup")
        }
        assert(ex.message!!.contains("cannot authorize"))
    }

    @Test
    fun `capture moves authorized to captured with amount`() {
        val intent = newIntent(PaymentIntent.STATE_AUTHORIZED)
        val later = now.plusSeconds(120)
        intent.capture(later, 2350L)
        assertEquals(PaymentIntent.STATE_CAPTURED, intent.state)
        assertEquals(2350L, intent.capturedMinor)
        assertEquals(later, intent.capturedAt)
    }

    @Test
    fun `capture rejects amount exceeding intent`() {
        val intent = newIntent(PaymentIntent.STATE_AUTHORIZED)
        assertThrows(IllegalArgumentException::class.java) {
            intent.capture(now, 2351L)
        }
    }

    @Test
    fun `capture rejects amount zero`() {
        val intent = newIntent(PaymentIntent.STATE_AUTHORIZED)
        assertThrows(IllegalArgumentException::class.java) {
            intent.capture(now, 0L)
        }
    }

    @Test
    fun `capture rejects from created state`() {
        val intent = newIntent(PaymentIntent.STATE_CREATED)
        assertThrows(IllegalStateException::class.java) {
            intent.capture(now, 2350L)
        }
    }

    @Test
    fun `void from created moves to voided`() {
        val intent = newIntent(PaymentIntent.STATE_CREATED)
        intent.voidAt(now, "customer_cancelled")
        assertEquals(PaymentIntent.STATE_VOIDED, intent.state)
        assertEquals("customer_cancelled", intent.failureMessage)
    }

    @Test
    fun `void from captured is rejected`() {
        val intent = newIntent(PaymentIntent.STATE_CAPTURED)
        assertThrows(IllegalStateException::class.java) {
            intent.voidAt(now)
        }
    }

    @Test
    fun `full refund moves captured to refunded`() {
        val intent = newIntent(PaymentIntent.STATE_CAPTURED).apply {
            capturedMinor = 2350L
        }
        intent.recordRefund(2350L)
        assertEquals(PaymentIntent.STATE_REFUNDED, intent.state)
        assertEquals(2350L, intent.refundedMinor)
    }

    @Test
    fun `partial refund moves captured to partially_refunded`() {
        val intent = newIntent(PaymentIntent.STATE_CAPTURED).apply {
            capturedMinor = 2350L
        }
        intent.recordRefund(1000L)
        assertEquals(PaymentIntent.STATE_PARTIALLY_REFUNDED, intent.state)
        assertEquals(1000L, intent.refundedMinor)
    }

    @Test
    fun `partial then full refund ends in refunded`() {
        val intent = newIntent(PaymentIntent.STATE_CAPTURED).apply {
            capturedMinor = 2350L
        }
        intent.recordRefund(1000L)
        intent.recordRefund(1350L)
        assertEquals(PaymentIntent.STATE_REFUNDED, intent.state)
        assertEquals(2350L, intent.refundedMinor)
    }

    @Test
    fun `refund exceeding captured is rejected`() {
        val intent = newIntent(PaymentIntent.STATE_CAPTURED).apply {
            capturedMinor = 2350L
        }
        assertThrows(IllegalArgumentException::class.java) {
            intent.recordRefund(2351L)
        }
    }

    @Test
    fun `refund from created is rejected`() {
        val intent = newIntent(PaymentIntent.STATE_CREATED)
        assertThrows(IllegalStateException::class.java) {
            intent.recordRefund(100L)
        }
    }

    @Test
    fun `fail from created moves to failed with code`() {
        val intent = newIntent(PaymentIntent.STATE_CREATED)
        intent.fail("CARD_DECLINED", "insufficient funds")
        assertEquals(PaymentIntent.STATE_FAILED, intent.state)
        assertEquals("CARD_DECLINED", intent.failureCode)
        assertEquals("insufficient funds", intent.failureMessage)
    }

    @Test
    fun `fail from captured is rejected`() {
        val intent = newIntent(PaymentIntent.STATE_CAPTURED)
        assertThrows(IllegalStateException::class.java) {
            intent.fail("x", "y")
        }
    }
}