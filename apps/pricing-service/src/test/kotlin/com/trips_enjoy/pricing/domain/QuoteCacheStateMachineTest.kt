package com.trips_enjoy.pricing.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * Unit tests for the QuoteCache state machine. Covers:
 *   * active → consumed (the dispatcher marks it consumed)
 *   * active → expired (the expiry job after expires_at)
 *   * consumed → terminal
 *   * expired → terminal
 *   * isActive() / isTerminal() helpers
 *   * Illegal transitions raise IllegalStateException
 *   * Construction validation (product_type, status, expires_at > created_at)
 */
class QuoteCacheStateMachineTest {

    private val sys = UUID.randomUUID()
    private val validHash = "a".repeat(64)
    private val now = Instant.parse("2026-08-15T12:00:00Z")

    private fun newQuote(status: String = QuoteCache.STATUS_ACTIVE): QuoteCache =
        QuoteCache(
            id = UUID.randomUUID(),
            customerId = UUID.randomUUID(),
            productType = QuoteCache.PRODUCT_RIDE,
            request = mapOf("origin_zone_id" to UUID.randomUUID().toString()),
            quote = mapOf("final_price_minor" to "100", "currency" to "USD"),
            configSnapshot = mapOf("captured_at" to now.toString()),
            status = status,
            expiresAt = now.plus(15, ChronoUnit.MINUTES),
            createdAt = now,
        )

    @Test
    fun `consume moves active to consumed`() {
        val quote = newQuote()
        val consumeAt = now.plusSeconds(60)
        quote.consume(consumeAt)
        assertEquals(QuoteCache.STATUS_CONSUMED, quote.status)
        assertEquals(consumeAt, quote.consumedAt)
    }

    @Test
    fun `consume rejects from consumed state`() {
        val quote = newQuote(QuoteCache.STATUS_CONSUMED)
        assertThrows(IllegalStateException::class.java) {
            quote.consume(now.plusSeconds(60))
        }
    }

    @Test
    fun `consume rejects from expired state`() {
        val quote = newQuote(QuoteCache.STATUS_EXPIRED)
        assertThrows(IllegalStateException::class.java) {
            quote.consume(now.plusSeconds(60))
        }
    }

    @Test
    fun `consume rejects when called after expires_at`() {
        val quote = newQuote()
        assertThrows(IllegalArgumentException::class.java) {
            quote.consume(now.plus(20, ChronoUnit.MINUTES))
        }
    }

    @Test
    fun `expire moves active to expired`() {
        val quote = newQuote()
        val expireAt = now.plus(15, ChronoUnit.MINUTES).plusSeconds(1)
        quote.expire(expireAt)
        assertEquals(QuoteCache.STATUS_EXPIRED, quote.status)
    }

    @Test
    fun `expire rejects from consumed state`() {
        val quote = newQuote(QuoteCache.STATUS_CONSUMED)
        assertThrows(IllegalStateException::class.java) {
            quote.expire(now.plusSeconds(60))
        }
    }

    @Test
    fun `expire rejects when called before expires_at`() {
        val quote = newQuote()
        assertThrows(IllegalArgumentException::class.java) {
            quote.expire(now.plus(5, ChronoUnit.MINUTES))
        }
    }

    @Test
    fun `isActive returns true within expires_at window`() {
        val quote = newQuote()
        assertTrue(quote.isActive())
        assertTrue(quote.isActive(now.plus(10, ChronoUnit.MINUTES)))
    }

    @Test
    fun `isActive returns false after expires_at`() {
        val quote = newQuote()
        assertFalse(quote.isActive(now.plus(20, ChronoUnit.MINUTES)))
    }

    @Test
    fun `isActive returns false on consumed quote`() {
        val quote = newQuote()
        quote.consume(now.plusSeconds(60))
        assertFalse(quote.isActive())
    }

    @Test
    fun `isTerminal returns true on consumed and expired`() {
        assertTrue(newQuote(QuoteCache.STATUS_CONSUMED).isTerminal())
        assertTrue(newQuote(QuoteCache.STATUS_EXPIRED).isTerminal())
        assertFalse(newQuote(QuoteCache.STATUS_ACTIVE).isTerminal())
    }

    @Test
    fun `unknown product_type rejected at construction`() {
        assertThrows(IllegalArgumentException::class.java) {
            QuoteCache(
                id = UUID.randomUUID(),
                customerId = UUID.randomUUID(),
                productType = "unknown",
                request = mapOf("k" to "v"),
                quote = mapOf("k" to "v"),
                configSnapshot = mapOf("k" to "v"),
                status = QuoteCache.STATUS_ACTIVE,
                expiresAt = now.plus(15, ChronoUnit.MINUTES),
                createdAt = now,
            )
        }
    }

    @Test
    fun `invalid status rejected at construction`() {
        assertThrows(IllegalArgumentException::class.java) {
            QuoteCache(
                id = UUID.randomUUID(),
                customerId = UUID.randomUUID(),
                productType = QuoteCache.PRODUCT_RIDE,
                request = mapOf("k" to "v"),
                quote = mapOf("k" to "v"),
                configSnapshot = mapOf("k" to "v"),
                status = "paused",
                expiresAt = now.plus(15, ChronoUnit.MINUTES),
                createdAt = now,
            )
        }
    }

    @Test
    fun `expires_at must be after created_at`() {
        assertThrows(IllegalArgumentException::class.java) {
            QuoteCache(
                id = UUID.randomUUID(),
                customerId = UUID.randomUUID(),
                productType = QuoteCache.PRODUCT_RIDE,
                request = mapOf("k" to "v"),
                quote = mapOf("k" to "v"),
                configSnapshot = mapOf("k" to "v"),
                status = QuoteCache.STATUS_ACTIVE,
                expiresAt = now.minusSeconds(60),
                createdAt = now,
            )
        }
    }
}