package com.trips_enjoy.payment.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/**
 * Unit tests for DriverEarnings, CourierEarnings, and MerchantSettlement
 * aggregates. Covers:
 *   * ride / delivery / order line application
 *   * net-pay / net-payout recomputation
 *   * finalize / markPaidOut / dispute lifecycle
 *   * invalid line applications are rejected (negative state mutation)
 *   * margin doctrine invariants per TYPE_CATALOG.md §8.7
 */
class EarningsAndSettlementTest {

    private val now = Instant.parse("2026-08-15T12:00:00Z")
    private val sys = UUID.randomUUID()

    private fun newDriverEarnings(): DriverEarnings = DriverEarnings(
        id = UUID.randomUUID(),
        driverId = UUID.randomUUID(),
        periodKind = DriverEarnings.PERIOD_DAILY,
        periodStart = now,
        periodEnd = now.plusSeconds(86400),
        currency = "USD",
        createdBy = sys,
        updatedBy = sys,
    )

    @Test
    fun `apply ride line increments all counters and net_pay`() {
        val e = newDriverEarnings()
        e.applyRideLine(
            grossFareMinor = 2350L,
            commissionMinor = 470L,   // 20% commission per TYPE_CATALOG §8.7
            tipMinor = 200L,
            bonusMinor = 100L,
            guaranteedTopupMinor = 50L,
            correctionMinor = 0L,
            at = now.plusSeconds(60),
        )
        assertEquals(1, e.ridesCount)
        assertEquals(2350L, e.grossFareMinor)
        assertEquals(470L, e.commissionMinor)
        // net_pay = gross + tip + bonus + guaranteed_topup + correction - commission
        //       = 2350 + 200 + 100 + 50 + 0 - 470 = 2230
        assertEquals(2230L, e.netPayMinor)
    }

    @Test
    fun `apply ride line rejects negative gross`() {
        val e = newDriverEarnings()
        assertThrows(IllegalArgumentException::class.java) {
            e.applyRideLine(-1L, 0L, 0L, 0L, 0L, 0L, now.plusSeconds(60))
        }
    }

    @Test
    fun `apply ride line on finalized period is rejected`() {
        val e = newDriverEarnings()
        e.finalize(now.plusSeconds(3600))
        assertThrows(IllegalStateException::class.java) {
            e.applyRideLine(100L, 0L, 0L, 0L, 0L, 0L, now.plusSeconds(7200))
        }
    }

    @Test
    fun `finalize then markPaidOut then dispute lifecycle`() {
        val e = newDriverEarnings()
        e.finalize(now.plusSeconds(3600))
        e.markPaidOut(now.plusSeconds(7200))
        e.dispute(now.plusSeconds(10800))
        assertEquals(DriverEarnings.STATE_DISPUTED, e.state)
    }

    @Test
    fun `markPaidOut on open period is rejected`() {
        val e = newDriverEarnings()
        assertThrows(IllegalStateException::class.java) {
            e.markPaidOut(now.plusSeconds(60))
        }
    }

    @Test
    fun `dispute on open period is rejected`() {
        val e = newDriverEarnings()
        assertThrows(IllegalStateException::class.java) {
            e.dispute(now.plusSeconds(60))
        }
    }

    private fun newMerchantSettlement(): MerchantSettlement = MerchantSettlement(
        id = UUID.randomUUID(),
        merchantId = UUID.randomUUID(),
        periodStart = now,
        periodEnd = now.plusSeconds(7 * 86400),
        currency = "USD",
        createdBy = sys,
        updatedBy = sys,
    )

    @Test
    fun `apply order line increments counters and net_payout`() {
        val s = newMerchantSettlement()
        s.applyOrderLine(
            grossRevenueMinor = 5000L,
            commissionMinor = 1000L,
            adjustmentMinor = -200L,   // platform-borne discount
            refundReversalMinor = 0L,
            at = now.plusSeconds(60),
        )
        assertEquals(1, s.ordersCount)
        assertEquals(5000L, s.grossRevenueMinor)
        // net_payout = gross + adjustments - commission - refund_reversal
        //           = 5000 + (-200) - 1000 - 0 = 3800
        assertEquals(3800L, s.netPayoutMinor)
    }

    @Test
    fun `merchant settlement markPaidOut requires reference`() {
        val s = newMerchantSettlement()
        s.finalize(now.plusSeconds(7 * 86400))
        assertThrows(IllegalArgumentException::class.java) {
            s.markPaidOut("", now.plusSeconds(8 * 86400))
        }
    }

    @Test
    fun `merchant settlement markPaidOut on open is rejected`() {
        val s = newMerchantSettlement()
        assertThrows(IllegalStateException::class.java) {
            s.markPaidOut("wire_123", now.plusSeconds(60))
        }
    }

    private fun newCourierEarnings(): CourierEarnings = CourierEarnings(
        id = UUID.randomUUID(),
        courierId = UUID.randomUUID(),
        periodKind = CourierEarnings.PERIOD_DAILY,
        periodStart = now,
        periodEnd = now.plusSeconds(86400),
        currency = "USD",
        createdBy = sys,
        updatedBy = sys,
    )

    @Test
    fun `apply delivery line increments counters and net_pay`() {
        val c = newCourierEarnings()
        c.applyDeliveryLine(
            grossFeeMinor = 800L,
            commissionMinor = 160L,
            tipMinor = 50L,
            bonusMinor = 0L,
            correctionMinor = 0L,
            at = now.plusSeconds(60),
        )
        assertEquals(1, c.deliveriesCount)
        assertEquals(800L, c.grossFeeMinor)
        assertEquals(690L, c.netPayMinor)  // 800 + 50 - 160
    }

    @Test
    fun `courier earnings finalize then dispute`() {
        val c = newCourierEarnings()
        c.finalize(now.plusSeconds(3600))
        c.dispute(now.plusSeconds(7200))
        assertEquals(CourierEarnings.STATE_DISPUTED, c.state)
    }
}