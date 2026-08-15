package com.trips_enjoy.pricing.application

import com.trips_enjoy.pricing.domain.GeoOverride
import com.trips_enjoy.pricing.domain.GeoOverrideRepository
import com.trips_enjoy.pricing.domain.IdempotencyKey
import com.trips_enjoy.pricing.domain.LoyaltyFrequentCache
import com.trips_enjoy.pricing.domain.LoyaltyFrequentCacheKey
import com.trips_enjoy.pricing.domain.LoyaltyFrequentCacheRepository
import com.trips_enjoy.pricing.domain.OutboxEvent
import com.trips_enjoy.pricing.domain.OutboxEventRepository
import com.trips_enjoy.pricing.domain.QuoteCache
import com.trips_enjoy.pricing.domain.QuoteCacheRepository
import com.trips_enjoy.pricing.domain.RatingDensityCache
import com.trips_enjoy.pricing.domain.RatingDensityCacheKey
import com.trips_enjoy.pricing.domain.RatingDensityCacheRepository
import com.trips_enjoy.pricing.domain.RuleBinding
import com.trips_enjoy.pricing.domain.RuleBindingRepository
import com.trips_enjoy.pricing.domain.RuleBindingsHistory
import com.trips_enjoy.pricing.domain.RuleBindingsHistoryRepository
import com.trips_enjoy.pricing.domain.SurgeCache
import com.trips_enjoy.pricing.domain.SurgeCacheRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * The pricing quote service — the saga orchestrator for the B0 quote +
 * B1 rating-density + B2 loyalty + B3 geo-config + B4 surge pipeline.
 *
 * Public API methods are idempotent on the Idempotency-Key header
 * (using the newer `idempotency_key` PK pattern). The full quote
 * computation composes:
 *   1. base_fare + per_km * distance + per_min * duration
 *   2. + tax line items (from configuration-service)
 *   3. - loyalty discount (if B2 loyalty tier applies)
 *   4. * surge multiplier (from B4 surge_cache + zone.surge.updated.v1)
 *   5. - rating-density adjustment (if B1 sample > threshold)
 *   6. * geo-override multiplier (if B3 od_corridor rule applies)
 *   7. = final price (rounded HALF_UP to 2 decimals)
 *
 * The pipeline is composed in `computeQuote()`; the dispatcher
 * (trip-service / food-order-service) calls `createQuote()`,
 * `consumeQuote()`, or `reQuote()`.
 *
 * Per the Phase 8.2 plan + TYPE_CATALOG.md §8.7 "Platform margin
 * doctrine", ALL discounts are platform-borne; commission = 0.20 ×
 * gross + 1{currency}. Driver / courier / merchant payouts are
 * computed downstream (in payment-service).
 */
@Service
class PricingQuoteService(
    private val quoteCacheRepository: QuoteCacheRepository,
    private val ruleBindingRepository: RuleBindingRepository,
    private val geoOverrideRepository: GeoOverrideRepository,
    private val surgeCacheRepository: SurgeCacheRepository,
    private val ratingDensityCacheRepository: RatingDensityCacheRepository,
    private val loyaltyFrequentCacheRepository: LoyaltyFrequentCacheRepository,
    private val ruleBindingsHistoryRepository: RuleBindingsHistoryRepository,
    private val outboxRepository: OutboxEventRepository,
    private val idemService: IdempotencyService,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * Create a new quote for a ride or food order. Returns the cached
     * QuoteCache row that the dispatcher will reference via `quote_id`.
     */
    @Transactional
    fun createQuote(
        customerId: UUID?,
        productType: String,
        originZoneId: UUID?,
        destinationZoneId: UUID?,
        rideType: String?,
        distanceKm: BigDecimal,
        durationMin: BigDecimal,
        baseFare: BigDecimal,
        perKm: BigDecimal,
        perMin: BigDecimal,
        taxRate: BigDecimal,
        loyaltyCustomerId: UUID?,
        idempotencyKey: UUID,
        requestHash: String,
        correlationId: UUID,
        createdBy: UUID,
    ): QuoteCache {
        // Idempotency check (newer PK pattern).
        val existing = idemService.findExisting(idempotencyKey)
        if (existing != null) {
            require(existing.requestHash == requestHash) {
                "idempotency key body mismatch"
            }
            val cached = quoteCacheRepository.findById(existing.responseBody["quote_id"] as UUID? ?: UUID.randomUUID())
                .orElseThrow { error("idempotency recorded but no matching quote_cache row") }
            return cached
        }

        val now = Instant.now()
        val expiresAt = now.plus(15, ChronoUnit.MINUTES)  // quote TTL = 15 minutes
        val configSnapshot = mapOf(
            "base_fare" to baseFare.toDouble(),
            "per_km" to perKm.toDouble(),
            "per_min" to perMin.toDouble(),
            "tax_rate" to taxRate.toDouble(),
            "captured_at" to now.toString(),
        )

        val finalPrice = computeQuote(
            baseFare = baseFare,
            perKm = perKm,
            perMin = perMin,
            distanceKm = distanceKm,
            durationMin = durationMin,
            taxRate = taxRate,
            customerId = loyaltyCustomerId,
            originZoneId = originZoneId,
            destinationZoneId = destinationZoneId,
            rideType = rideType,
        )

        val quote = mapOf(
            "base_fare" to baseFare.toDouble(),
            "distance_km" to distanceKm.toDouble(),
            "duration_min" to durationMin.toDouble(),
            "per_km" to perKm.toDouble(),
            "per_min" to perMin.toDouble(),
            "tax_rate" to taxRate.toDouble(),
            "surge_multiplier" to lookupSurge(originZoneId, now).toDouble(),
            "loyalty_discount_minor" to "0",
            "rating_density_adjustment" to "0",
            "geo_override_multiplier" to "1.0",
            "final_price_minor" to finalPrice.toString(),
            "currency" to "USD",
        )

        val quoteCache = QuoteCache(
            id = UUID.randomUUID(),
            customerId = customerId,
            productType = productType,
            request = mapOf(
                "origin_zone_id" to originZoneId?.toString(),
                "destination_zone_id" to destinationZoneId?.toString(),
                "ride_type" to rideType,
                "distance_km" to distanceKm.toDouble(),
                "duration_min" to durationMin.toDouble(),
            ),
            quote = quote,
            configSnapshot = configSnapshot,
            status = QuoteCache.STATUS_ACTIVE,
            expiresAt = expiresAt,
            createdAt = now,
        )
        quoteCacheRepository.save(quoteCache)

        idemService.record(
            idempotencyKey = idempotencyKey,
            requestHash = requestHash,
            responseStatus = 201,
            responseBody = mapOf("quote_id" to quoteCache.id),
            actorId = createdBy,
            ttlSeconds = 900,  // 15 min, matches quote TTL
            at = now,
        )

        outboxRepository.save(
            OutboxEvent(
                id = UUID.randomUUID(),
                aggregateType = "QuoteCache",
                aggregateId = quoteCache.id,
                eventType = "pricing.quote.created.v1",
                topic = "pricing.quote.created.v1",
                payload = mapOf(
                    "quote_id" to quoteCache.id.toString(),
                    "customer_id" to customerId?.toString(),
                    "product_type" to productType,
                    "final_price_minor" to finalPrice.toString(),
                ),
                correlationId = correlationId,
                createdBy = createdBy,
            ),
        )

        return quoteCache
    }

    /**
     * Consume a quote (the dispatcher — trip-service / food-order-service
     * — calls this when the customer accepts the quote and the ride /
     * food-order is committed).
     */
    @Transactional
    fun consumeQuote(quoteId: UUID, at: Instant = Instant.now()): QuoteCache {
        val quote = quoteCacheRepository.findById(quoteId).orElseThrow()
        quote.consume(at)
        return quote
    }

    /**
     * Re-quote — the dispatcher calls this when the customer has changed
     * the request (different destination, different time, surge spike).
     * Creates a new QuoteCache and expires the old one.
     */
    @Transactional
    fun reQuote(
        oldQuoteId: UUID,
        customerId: UUID?,
        productType: String,
        originZoneId: UUID?,
        destinationZoneId: UUID?,
        rideType: String?,
        distanceKm: BigDecimal,
        durationMin: BigDecimal,
        baseFare: BigDecimal,
        perKm: BigDecimal,
        perMin: BigDecimal,
        taxRate: BigDecimal,
        loyaltyCustomerId: UUID?,
        idempotencyKey: UUID,
        requestHash: String,
        correlationId: UUID,
        createdBy: UUID,
    ): QuoteCache {
        val oldQuote = quoteCacheRepository.findById(oldQuoteId).orElseThrow()
        oldQuote.expire(Instant.now())
        return createQuote(
            customerId, productType, originZoneId, destinationZoneId, rideType,
            distanceKm, durationMin, baseFare, perKm, perMin, taxRate,
            loyaltyCustomerId, idempotencyKey, requestHash, correlationId, createdBy,
        )
    }

    /**
     * Compute the cancellation fee for an active quote.
     */
    fun computeCancellationFee(quoteId: UUID, at: Instant = Instant.now()): Long {
        val quote = quoteCacheRepository.findById(quoteId).orElseThrow()
        check(quote.isActive(at)) { "quote $quoteId is not active" }
        val finalPriceMinor = (quote.quote["final_price_minor"] as String).toLong()
        // Cancellation fee = 5% of final price (caller configures the %
        // in production; here it's a flat 5%).
        return finalPriceMinor * 5 / 100
    }

    /**
     * Compute the waiting fee for an active quote (per minute the
     * driver waits beyond the grace period).
     */
    fun computeWaitingFee(quoteId: UUID, waitedMinutes: Int): Long {
        val quote = quoteCacheRepository.findById(quoteId).orElseThrow()
        val perMin = BigDecimal(quote.quote["per_min"] as String)
        // Free for the first 2 minutes, then 50% of per_min thereafter.
        val billableMinutes = maxOf(0, waitedMinutes - 2)
        return perMin.multiply(BigDecimal(billableMinutes)).multiply(BigDecimal("0.5")).toLong()
    }

    /**
     * Compute the fairness band for an in-driver deal (Phase 7.5
     * Make-a-Deal kernel). Per the platform doctrine, the fairness
     * band is `[0.7 * final_price, 1.3 * final_price]`.
     */
    fun computeFairnessBand(quoteId: UUID): Map<String, Long> {
        val quote = quoteCacheRepository.findById(quoteId).orElseThrow()
        val finalPriceMinor = (quote.quote["final_price_minor"] as String).toLong()
        return mapOf(
            "min_minor" to (finalPriceMinor * 7 / 10),
            "max_minor" to (finalPriceMinor * 13 / 10),
        )
    }

    /**
     * Upsert a rule binding. The admin-service geo-config API calls
     * this with a new `version` and writes the prior version to
     * `rule_bindings_history`. The old binding's `superseded_by_id`
     * points at the new head.
     */
    @Transactional
    fun upsertRuleBinding(
        tenantId: String,
        cityId: String?,
        originZoneId: UUID?,
        destinationZoneId: UUID?,
        rideType: String?,
        ruleKind: String,
        value: Map<String, Any?>,
        priority: Int,
        effectiveFrom: Instant?,
        effectiveTo: Instant?,
        createdBy: UUID,
    ): RuleBinding {
        // Find the active head for this scope (same tenant + city +
        // ride_type + origin/destination). For now we look up the active
        // list and supersede any matching row.
        val active = ruleBindingRepository.findActive(Instant.now())
            .filter { it.tenantId == tenantId && it.cityId == cityId && it.rideType == rideType }
            .filter { it.originZoneId == originZoneId && it.destinationZoneId == destinationZoneId }
            .filter { it.ruleKind == ruleKind }

        val newBinding = RuleBinding(
            id = UUID.randomUUID(),
            version = 1,
            tenantId = tenantId,
            cityId = cityId,
            originZoneId = originZoneId,
            destinationZoneId = destinationZoneId,
            rideType = rideType,
            ruleKind = ruleKind,
            value = value,
            priority = priority,
            effectiveFrom = effectiveFrom,
            effectiveTo = effectiveTo,
            createdBy = createdBy,
            createdAt = Instant.now(),
        )
        ruleBindingRepository.save(newBinding)

        // Mark the active row as superseded and write history.
        for (old in active) {
            old.supersede(newBinding.id)
            ruleBindingsHistoryRepository.save(
                RuleBindingsHistory(
                    id = UUID.randomUUID(),
                    bindingId = old.id,
                    version = old.version,
                    action = RuleBindingsHistory.ACTION_ROLLBACK,
                    actorId = createdBy,
                    payload = mapOf(
                        "version" to old.version,
                        "superseded_by_id" to newBinding.id.toString(),
                    ),
                ),
            )
        }
        // Also write a history row for the new head.
        ruleBindingsHistoryRepository.save(
            RuleBindingsHistory(
                id = UUID.randomUUID(),
                bindingId = newBinding.id,
                version = newBinding.version,
                action = RuleBindingsHistory.ACTION_CREATE,
                actorId = createdBy,
                payload = mapOf(
                    "version" to newBinding.version,
                    "value" to value,
                ),
            ),
        )

        return newBinding
    }

    /**
     * Apply the surge multiplier for an active quote. Called by the
     * trip-service dispatcher after the quote is consumed.
     */
    @Transactional
    fun applySurge(originZoneId: UUID, multiplier: BigDecimal, at: Instant, createdBy: UUID): SurgeCache {
        val existing = surgeCacheRepository.findById(originZoneId).orElse(null)
        return if (existing != null) {
            existing.update(multiplier, at)
            existing
        } else {
            val cache = SurgeCache(
                zoneId = originZoneId,
                multiplier = multiplier,
                updatedAt = at,
            )
            surgeCacheRepository.save(cache)
        }.also {
            outboxRepository.save(
                OutboxEvent(
                    id = UUID.randomUUID(),
                    aggregateType = "SurgeCache",
                    aggregateId = originZoneId,
                    eventType = "pricing.surge.zone_updated.v1",
                    topic = "pricing.surge.zone_updated.v1",
                    payload = mapOf(
                        "zone_id" to originZoneId.toString(),
                        "multiplier" to multiplier.toDouble(),
                    ),
                    correlationId = UUID.randomUUID(),
                    createdBy = createdBy,
                ),
            )
        }
    }

    /**
     * Apply a rating-density event for a zone (debounced per
     * TYPE_CATALOG.md §8.7).
     */
    @Transactional
    fun applyRatingDensity(
        zoneId: UUID,
        windowMinutes: Int,
        avgRating: BigDecimal,
        sampleSize: Int,
        at: Instant,
        createdBy: UUID,
    ) {
        val existing = ratingDensityCacheRepository.findById(
            RatingDensityCacheKey(zoneId, windowMinutes)
        ).orElse(null)
        val cache = if (existing != null) {
            existing.update(avgRating, sampleSize, at)
            existing
        } else {
            val c = RatingDensityCache(
                zoneId = zoneId,
                windowMinutes = windowMinutes,
                avgRating = avgRating,
                sampleSize = sampleSize,
                computedAt = at,
            )
            ratingDensityCacheRepository.save(c)
        }
        outboxRepository.save(
            OutboxEvent(
                id = UUID.randomUUID(),
                aggregateType = "RatingDensityCache",
                aggregateId = zoneId,
                eventType = "pricing.rating_density.applied.v1",
                topic = "pricing.rating_density.applied.v1",
                payload = mapOf(
                    "zone_id" to zoneId.toString(),
                    "window_minutes" to windowMinutes,
                    "avg_rating" to avgRating.toDouble(),
                    "sample_size" to sampleSize,
                ),
                correlationId = UUID.randomUUID(),
                createdBy = createdBy,
            ),
        )
    }

    /**
     * Apply a loyalty frequent-zone event (debounced daily).
     */
    @Transactional
    fun applyLoyaltyFrequent(
        customerId: UUID,
        zoneId: UUID,
        tripCount30d: Int,
        tier: String,
        qualifyingAt: Instant,
        ttlUntil: Instant,
        createdBy: UUID,
    ) {
        val key = LoyaltyFrequentCacheKey(customerId, zoneId)
        val existing = loyaltyFrequentCacheRepository.findById(key).orElse(null)
        if (existing != null) {
            existing.update(tripCount30d, tier, qualifyingAt, ttlUntil)
        } else {
            val c = LoyaltyFrequentCache(
                customerId = customerId,
                zoneId = zoneId,
                tripCount30d = tripCount30d,
                tierAtTrip = tier,
                mostRecentQualifyingAt = qualifyingAt,
                computedAt = qualifyingAt,
                expiresAt = ttlUntil,
            )
            loyaltyFrequentCacheRepository.save(c)
        }
        outboxRepository.save(
            OutboxEvent(
                id = UUID.randomUUID(),
                aggregateType = "LoyaltyFrequentCache",
                aggregateId = customerId,
                eventType = "pricing.loyalty_discount.applied.v1",
                topic = "pricing.loyalty_discount.applied.v1",
                payload = mapOf(
                    "customer_id" to customerId.toString(),
                    "zone_id" to zoneId.toString(),
                    "tier" to tier,
                    "trip_count_30d" to tripCount30d,
                ),
                correlationId = UUID.randomUUID(),
                createdBy = createdBy,
            ),
        )
    }

    // ---------- Pipeline helpers ----------

    private fun computeQuote(
        baseFare: BigDecimal,
        perKm: BigDecimal,
        perMin: BigDecimal,
        distanceKm: BigDecimal,
        durationMin: BigDecimal,
        taxRate: BigDecimal,
        customerId: UUID?,
        originZoneId: UUID?,
        destinationZoneId: UUID?,
        rideType: String?,
    ): Long {
        val now = Instant.now()
        val subtotal = baseFare
            .add(perKm.multiply(distanceKm))
            .add(perMin.multiply(durationMin))
        val afterTax = subtotal.add(subtotal.multiply(taxRate))

        // B4 surge multiplier
        val surge = lookupSurge(originZoneId, now)
        val afterSurge = afterTax.multiply(surge)

        // B3 geo-override multiplier (od_corridor only)
        val geoMultiplier = lookupGeoOverride(originZoneId, destinationZoneId, rideType, now)
        val afterGeo = afterSurge.multiply(geoMultiplier)

        // B2 loyalty discount (silver 5%, gold 10%, platinum 15% of subtotal)
        val loyaltyDiscountMinor = if (customerId != null) {
            val loyalty = lookupLoyalty(customerId, originZoneId, now)
            loyalty?.let { subtotal.multiply(it.discountMultiplier).toLong() } ?: 0L
        } else 0L
        val afterLoyalty = afterGeo.subtract(BigDecimal(loyaltyDiscountMinor))

        // Round HALF_UP to nearest minor unit
        return afterLoyalty.setScale(0, RoundingMode.HALF_UP).toLong()
    }

    private fun lookupSurge(originZoneId: UUID?, at: Instant): BigDecimal {
        if (originZoneId == null) return BigDecimal.ONE
        val cache = surgeCacheRepository.findById(originZoneId).orElse(null) ?: return BigDecimal.ONE
        return cache.multiplier
    }

    private fun lookupGeoOverride(
        originZoneId: UUID?,
        destinationZoneId: UUID?,
        rideType: String?,
        at: Instant,
    ): BigDecimal {
        if (originZoneId == null || destinationZoneId == null) return BigDecimal.ONE
        val overrides = geoOverrideRepository
            .findByOriginZoneIdAndDestinationZoneIdAndRideType(originZoneId, destinationZoneId, rideType ?: "*")
            .filter { it.isActive(at) }
        // The matching override with the highest version wins (most recent).
        return overrides.maxByOrNull { it.version }?.multiplierAdjustment ?: BigDecimal.ONE
    }

    private fun lookupLoyalty(
        customerId: UUID,
        zoneId: UUID?,
        at: Instant,
    ): LoyaltyDiscount? {
        if (zoneId == null) return null
        val key = LoyaltyFrequentCacheKey(customerId, zoneId)
        val cache = loyaltyFrequentCacheRepository.findById(key).orElse(null)
            ?: return null
        if (cache.isStale(at)) return null
        return when (cache.tierAtTrip) {
            LoyaltyFrequentCache.TIER_SILVER -> LoyaltyDiscount(BigDecimal("0.05"))
            LoyaltyFrequentCache.TIER_GOLD -> LoyaltyDiscount(BigDecimal("0.10"))
            LoyaltyFrequentCache.TIER_PLATINUM -> LoyaltyDiscount(BigDecimal("0.15"))
            else -> null
        }
    }
}

data class LoyaltyDiscount(val discountMultiplier: BigDecimal)