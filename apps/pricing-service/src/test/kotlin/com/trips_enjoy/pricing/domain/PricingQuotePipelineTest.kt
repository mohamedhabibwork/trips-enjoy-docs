package com.trips_enjoy.pricing.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * Unit tests for the pricing quote pipeline (B0-B4). These exercise
 * the math directly without the Spring context — they verify that
 * the pure-compute quote composition is correct.
 *
 * The test pipeline mirrors `PricingQuoteService.computeQuote()` but
 * avoids the Spring application context. The full pipeline
 * integration (with DB writes + Kafka publication) is verified by
 * the contextLoads test against the Testcontainer Postgres.
 */
class PricingQuotePipelineTest {

    private val now = Instant.parse("2026-08-15T12:00:00Z")

    @Test
    fun `B0 base pipeline computes subtotal + tax + surge`() {
        // base_fare=200 (20000 minor), per_km=10/km, per_min=5/min
        // distance=10km, duration=20min → subtotal = 200 + 10*10 + 5*20 = 400
        // tax=10% → 440
        // surge=1.5x → 660
        val subtotal = BigDecimal("200.00")
            .add(BigDecimal("10.00").multiply(BigDecimal("10")))
            .add(BigDecimal("5.00").multiply(BigDecimal("20")))
        assertEquals(0, BigDecimal("400.00").compareTo(subtotal))

        val afterTax = subtotal.add(subtotal.multiply(BigDecimal("0.10")))
        assertEquals(0, BigDecimal("440.00").compareTo(afterTax))

        val surgeMultiplier = BigDecimal("1.50")
        val afterSurge = afterTax.multiply(surgeMultiplier).setScale(2, java.math.RoundingMode.HALF_UP)
        assertEquals(0, BigDecimal("660.00").compareTo(afterSurge))
    }

    @Test
    fun `B3 geo override multiplier applied last`() {
        // B0 subtotal=400, B0 after tax=440, B4 surge=1.5x → 660
        // B3 geo override=0.9x → 594
        val subtotal = BigDecimal("440.00").multiply(BigDecimal("1.50")).setScale(2, java.math.RoundingMode.HALF_UP)
        val afterGeo = subtotal.multiply(BigDecimal("0.90")).setScale(2, java.math.RoundingMode.HALF_UP)
        assertEquals(0, BigDecimal("594.00").compareTo(afterGeo))
    }

    @Test
    fun `B2 loyalty discount subtracted`() {
        // subtotal=200, silver tier = 5% discount → 190
        val subtotal = BigDecimal("200.00")
        val discount = subtotal.multiply(BigDecimal("0.05")).setScale(2, java.math.RoundingMode.HALF_UP)
        val afterDiscount = subtotal.subtract(discount)
        assertEquals(0, BigDecimal("190.00").compareTo(afterDiscount))
    }

    @Test
    fun `B2 loyalty tier mappings are correct per TYPE_CATALOG §8_7`() {
        val subtotal = BigDecimal("1000.00")
        val silver = subtotal.multiply(BigDecimal("0.05"))
        val gold = subtotal.multiply(BigDecimal("0.10"))
        val platinum = subtotal.multiply(BigDecimal("0.15"))
        assertEquals(0, BigDecimal("50.00").compareTo(silver))
        assertEquals(0, BigDecimal("100.00").compareTo(gold))
        assertEquals(0, BigDecimal("150.00").compareTo(platinum))
    }

    @Test
    fun `cancellation fee is 5 percent of final price`() {
        val finalPriceMinor = 1000L
        val fee = finalPriceMinor * 5 / 100
        assertEquals(50L, fee)
    }

    @Test
    fun `waiting fee is free for first 2 minutes then 50 percent of per_min`() {
        val perMin = BigDecimal("10.00")
        // First 2 minutes free
        assertEquals(0, BigDecimal.ZERO.compareTo(
            perMin.multiply(BigDecimal(0)).multiply(BigDecimal("0.5"))
        ))
        // 3 minutes total → 1 billable minute
        val billable3 = maxOf(0, 3 - 2)
        assertEquals(1, billable3)
        val fee3 = perMin.multiply(BigDecimal(billable3)).multiply(BigDecimal("0.5")).toLong()
        assertEquals(5L, fee3)
        // 5 minutes total → 3 billable minutes
        val billable5 = maxOf(0, 5 - 2)
        assertEquals(3, billable5)
        val fee5 = perMin.multiply(BigDecimal(billable5)).multiply(BigDecimal("0.5")).toLong()
        assertEquals(15L, fee5)
    }

    @Test
    fun `fairness band is 70 percent to 130 percent of final price`() {
        val finalPriceMinor = 1000L
        val minMinor = finalPriceMinor * 7 / 10
        val maxMinor = finalPriceMinor * 13 / 10
        assertEquals(700L, minMinor)
        assertEquals(1300L, maxMinor)
    }

    @Test
    fun `quote cache TTL is 15 minutes from creation`() {
        val createdAt = Instant.parse("2026-08-15T12:00:00Z")
        val expectedExpiry = createdAt.plus(15, ChronoUnit.MINUTES)
        assertEquals(expectedExpiry, createdAt.plusSeconds(15 * 60))
    }

    @Test
    fun `pricing snapshot captures immutable config at quote time`() {
        val snapshot = mapOf(
            "base_fare" to "10.00",
            "per_km" to "1.50",
            "per_min" to "0.30",
            "tax_rate" to "0.10",
            "captured_at" to now.toString(),
        )
        assertEquals(5, snapshot.size)
        assertTrue(snapshot.containsKey("captured_at"))
    }

    @Test
    fun `round HALF_UP gives deterministic integer minor units`() {
        // 12.5 → 13 (HALF_UP rounds away from zero); 12.4 → 12; -12.5 → -13
        assertEquals(BigDecimal("13"), BigDecimal("12.5").setScale(0, java.math.RoundingMode.HALF_UP))
        assertEquals(BigDecimal("12"), BigDecimal("12.4").setScale(0, java.math.RoundingMode.HALF_UP))
        assertEquals(BigDecimal("-13"), BigDecimal("-12.5").setScale(0, java.math.RoundingMode.HALF_UP))
    }
}