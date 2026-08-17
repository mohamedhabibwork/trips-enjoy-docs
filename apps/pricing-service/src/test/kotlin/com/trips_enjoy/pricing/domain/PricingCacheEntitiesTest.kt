package com.trips_enjoy.pricing.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

/**
 * Unit tests for SurgeCache, RatingDensityCache, LoyaltyFrequentCache,
 * RuleBinding, GeoOverride, RuleBindingsHistory, IdempotencyKey.
 * Mirrors the customer-service / driver-service / payment-service
 * domain-entity test patterns.
 */
class PricingCacheEntitiesTest {

    private val sys = UUID.randomUUID()
    private val validHash = "a".repeat(64)
    // Phase C (platform DRY) regression guard: the tests must anchor
    // `now` to the JVM clock so the `isStale()` / `isExpired()` defaults
    // (which use `Instant.now()`) remain in scope. The previous
    // `Instant.parse("2026-08-15T12:00:00Z")` literal drifted into the
    // past once the calendar moved past that date, causing the
    // boundary assertions to fail under CI rotation.
    private val now: Instant = Instant.now()

    // ---------- SurgeCache ----------

    @Test
    fun `surge multiplier starts at 1_00`() {
        val cache = SurgeCache(zoneId = UUID.randomUUID(), multiplier = BigDecimal("1.00"))
        assertEquals(BigDecimal("1.00"), cache.multiplier)
        assertEquals(1, cache.version)
    }

    @Test
    fun `surge multiplier below 1_0 is rejected at construction`() {
        assertThrows(IllegalArgumentException::class.java) {
            SurgeCache(zoneId = UUID.randomUUID(), multiplier = BigDecimal("0.99"))
        }
    }

    @Test
    fun `surge update bumps version and updates timestamp`() {
        val cache = SurgeCache(zoneId = UUID.randomUUID(), multiplier = BigDecimal("1.00"))
        val later = now.plusSeconds(60)
        cache.update(BigDecimal("1.50"), later)
        assertEquals(BigDecimal("1.50"), cache.multiplier)
        assertEquals(2, cache.version)
        assertEquals(later, cache.updatedAt)
    }

    @Test
    fun `surge update rejects multiplier below 1_0`() {
        val cache = SurgeCache(zoneId = UUID.randomUUID(), multiplier = BigDecimal("1.00"))
        assertThrows(IllegalArgumentException::class.java) {
            cache.update(BigDecimal("0.50"), now.plusSeconds(60))
        }
    }

    // ---------- LoyaltyFrequentCache ----------

    @Test
    fun `loyalty tier validation at construction`() {
        for (tier in listOf(
            LoyaltyFrequentCache.TIER_SILVER,
            LoyaltyFrequentCache.TIER_GOLD,
            LoyaltyFrequentCache.TIER_PLATINUM,
        )) {
            val cache = LoyaltyFrequentCache(
                customerId = UUID.randomUUID(),
                zoneId = UUID.randomUUID(),
                tripCount30d = 5,
                tierAtTrip = tier,
                mostRecentQualifyingAt = now,
                expiresAt = now.plusSeconds(86400L * 30),
            )
            assertEquals(tier, cache.tierAtTrip)
        }
    }

    @Test
    fun `loyalty invalid tier rejected at construction`() {
        assertThrows(IllegalArgumentException::class.java) {
            LoyaltyFrequentCache(
                customerId = UUID.randomUUID(),
                zoneId = UUID.randomUUID(),
                tripCount30d = 5,
                tierAtTrip = "diamond",
                mostRecentQualifyingAt = now,
                expiresAt = now.plusSeconds(86400L * 30),
            )
        }
    }

    @Test
    fun `loyalty isStale returns true after expires_at`() {
        val cache = LoyaltyFrequentCache(
            customerId = UUID.randomUUID(),
            zoneId = UUID.randomUUID(),
            tripCount30d = 5,
            tierAtTrip = LoyaltyFrequentCache.TIER_SILVER,
            mostRecentQualifyingAt = now,
            expiresAt = now.plusSeconds(60),
        )
        assertFalse(cache.isStale())
        assertTrue(cache.isStale(now.plusSeconds(120)))
    }

    // ---------- RuleBinding ----------

    @Test
    fun `rule binding od_corridor requires origin and destination`() {
        assertThrows(IllegalArgumentException::class.java) {
            RuleBinding(
                ruleKind = RuleBinding.RULE_OD_CORRIDOR,
                value = mapOf("multiplier" to "1.0"),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            RuleBinding(
                ruleKind = RuleBinding.RULE_OD_CORRIDOR,
                originZoneId = UUID.randomUUID(),
                value = mapOf("multiplier" to "1.0"),
            )
        }
    }

    @Test
    fun `rule binding non-OD rejects origin and destination`() {
        assertThrows(IllegalArgumentException::class.java) {
            RuleBinding(
                ruleKind = RuleBinding.RULE_BASE_FARE_OVERRIDE,
                originZoneId = UUID.randomUUID(),
                value = mapOf("base_fare" to "10.00"),
            )
        }
    }

    @Test
    fun `rule binding unknown rule kind rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            RuleBinding(
                ruleKind = "unknown_kind",
                value = mapOf("x" to "y"),
            )
        }
    }

    @Test
    fun `rule binding valid rule_kinds accepted`() {
        for (kind in listOf(
            RuleBinding.RULE_BASE_FARE_OVERRIDE,
            RuleBinding.RULE_PER_KM_OVERRIDE,
            RuleBinding.RULE_PER_MIN_OVERRIDE,
            RuleBinding.RULE_SURGE_PRESSURE,
            RuleBinding.RULE_LOYALTY_DISCOUNT,
            RuleBinding.RULE_MIN_FARE_OVERRIDE,
            RuleBinding.RULE_OD_CORRIDOR,
        )) {
            val r = RuleBinding(
                ruleKind = kind,
                originZoneId = if (kind == RuleBinding.RULE_OD_CORRIDOR) UUID.randomUUID() else null,
                destinationZoneId = if (kind == RuleBinding.RULE_OD_CORRIDOR) UUID.randomUUID() else null,
                value = mapOf("multiplier" to "1.0"),
            )
            assertEquals(kind, r.ruleKind)
        }
    }

    @Test
    fun `rule binding supersede rejects double-supersede`() {
        val r = RuleBinding(
            ruleKind = RuleBinding.RULE_BASE_FARE_OVERRIDE,
            value = mapOf("base_fare" to "10.00"),
        )
        r.supersede(UUID.randomUUID())
        assertThrows(IllegalStateException::class.java) {
            r.supersede(UUID.randomUUID())
        }
    }

    // ---------- GeoOverride ----------

    @Test
    fun `geo override isActive respects effective window`() {
        val go = GeoOverride(
            id = UUID.randomUUID(),
            originZoneId = UUID.randomUUID(),
            destinationZoneId = UUID.randomUUID(),
            multiplierAdjustment = BigDecimal("0.95"),
            effectiveFrom = now.plusSeconds(60),
            effectiveTo = now.plusSeconds(3600),
        )
        assertFalse(go.isActive())
        assertTrue(go.isActive(now.plusSeconds(120)))
        assertFalse(go.isActive(now.plusSeconds(7200)))
    }

    @Test
    fun `geo override rejects negative multiplier`() {
        assertThrows(IllegalArgumentException::class.java) {
            GeoOverride(
                id = UUID.randomUUID(),
                originZoneId = UUID.randomUUID(),
                destinationZoneId = UUID.randomUUID(),
                multiplierAdjustment = BigDecimal("-0.10"),
            )
        }
    }

    // ---------- RuleBindingsHistory ----------

    @Test
    fun `rule bindings history valid actions accepted`() {
        for (action in listOf(
            RuleBindingsHistory.ACTION_CREATE,
            RuleBindingsHistory.ACTION_UPDATE,
            RuleBindingsHistory.ACTION_DISABLE,
            RuleBindingsHistory.ACTION_ROLLBACK,
        )) {
            val h = RuleBindingsHistory(
                id = UUID.randomUUID(),
                bindingId = UUID.randomUUID(),
                version = 1,
                action = action,
                actorId = sys,
                payload = mapOf("k" to "v"),
            )
            assertEquals(action, h.action)
        }
    }

    @Test
    fun `rule bindings history invalid action rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            RuleBindingsHistory(
                id = UUID.randomUUID(),
                bindingId = UUID.randomUUID(),
                version = 1,
                action = "wrong",
                actorId = sys,
                payload = mapOf("k" to "v"),
            )
        }
    }

    // ---------- IdempotencyKey ----------

    @Test
    fun `idempotency key request_hash must be 64 chars`() {
        assertThrows(IllegalArgumentException::class.java) {
            IdempotencyKey(
                idempotencyKey = UUID.randomUUID(),
                requestHash = "short",
                responseStatus = 201,
                responseBody = mapOf("k" to "v"),
                actorId = sys,
                expiresAt = now.plusSeconds(3600),
                createdAt = now,
            )
        }
    }

    @Test
    fun `idempotency key expires_at must be after created_at`() {
        assertThrows(IllegalArgumentException::class.java) {
            IdempotencyKey(
                idempotencyKey = UUID.randomUUID(),
                requestHash = validHash,
                responseStatus = 201,
                responseBody = mapOf("k" to "v"),
                actorId = sys,
                expiresAt = now.minusSeconds(60),
                createdAt = now,
            )
        }
    }

    @Test
    fun `idempotency key isExpired returns false before expires_at`() {
        val key = IdempotencyKey(
            idempotencyKey = UUID.randomUUID(),
            requestHash = validHash,
            responseStatus = 201,
            responseBody = mapOf("k" to "v"),
            actorId = sys,
            expiresAt = now.plusSeconds(3600),
            createdAt = now,
        )
        assertFalse(key.isExpired())
    }

    @Test
    fun `idempotency key isExpired returns true after expires_at`() {
        val key = IdempotencyKey(
            idempotencyKey = UUID.randomUUID(),
            requestHash = validHash,
            responseStatus = 201,
            responseBody = mapOf("k" to "v"),
            actorId = sys,
            expiresAt = now.plusSeconds(60),
            createdAt = now,
        )
        assertTrue(key.isExpired(now.plusSeconds(120)))
    }
}