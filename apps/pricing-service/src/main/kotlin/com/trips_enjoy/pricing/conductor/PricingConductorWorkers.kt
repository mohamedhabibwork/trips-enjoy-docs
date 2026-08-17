package com.trips_enjoy.pricing.conductor

import com.trips_enjoy.pricing.application.PricingQuoteService
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

/**
 * The Conductor workflow workers for pricing-service. Per
 * [ADR-0018](docs/architecture/adrs/0018-workflow-engine-conductor.md)
 * pricing-service owns 4 of the 17 workflow IDs:
 *   * wf.pricing.quote.v1           (this file)
 *   * wf.pricing.surge.v1           (this file)
 *   * wf.pricing.geo_override.v1    (this file)
 *   * wf.pricing.fairness_band.v1   (this file)
 *
 * Each worker is a thin wrapper that translates a Conductor task input
 * map to a call into the PricingQuoteService application layer.
 */
@Component
class PricingConductorWorkers(
    private val pricingQuoteService: PricingQuoteService,
) {

    /**
     * Conductor task: pricing.quote — drives the quote computation
     * pipeline (B0 base + B4 surge + B2 loyalty + B3 geo-override).
     *
     * Input: { customer_id, product_type, origin_zone_id, destination_zone_id,
     *          ride_type, distance_km, duration_min, base_fare, per_km,
     *          per_min, tax_rate, loyalty_customer_id }
     * Output: { quote_id, final_price_minor }
     */
    fun quote(input: Map<String, Any?>): Map<String, Any?> {
        val idempotencyKey = UUID.fromString(input["idempotency_key"] as String)
        val requestHash = (input["request_hash"] as? String) ?: sha256(idempotencyKey.toString())
        val correlationId = UUID.fromString(input["correlation_id"] as String)
        val actingUser = UUID.fromString(input["acting_user_id"] as String)

        val quote = pricingQuoteService.createQuote(
            customerId = (input["customer_id"] as? String)?.let(UUID::fromString),
            productType = input["product_type"] as String,
            originZoneId = (input["origin_zone_id"] as? String)?.let(UUID::fromString),
            destinationZoneId = (input["destination_zone_id"] as? String)?.let(UUID::fromString),
            rideType = input["ride_type"] as? String,
            distanceKm = BigDecimal(input["distance_km"].toString()),
            durationMin = BigDecimal(input["duration_min"].toString()),
            baseFare = BigDecimal(input["base_fare"].toString()),
            perKm = BigDecimal(input["per_km"].toString()),
            perMin = BigDecimal(input["per_min"].toString()),
            taxRate = BigDecimal(input["tax_rate"].toString()),
            loyaltyCustomerId = (input["loyalty_customer_id"] as? String)?.let(UUID::fromString),
            idempotencyKey = idempotencyKey,
            requestHash = requestHash,
            correlationId = correlationId,
            createdBy = actingUser,
        )
        return mapOf(
            "quote_id" to quote.id.toString(),
            "final_price_minor" to (quote.quote["final_price_minor"] as String),
        )
    }

    /**
     * Conductor task: pricing.surge — applies a surge multiplier to a
     * zone (called by geolocation-service on zone.surge.updated.v1).
     *
     * Input: { zone_id, multiplier }
     * Output: { zone_id, multiplier, version }
     */
    fun surge(input: Map<String, Any?>): Map<String, Any?> {
        val zoneId = UUID.fromString(input["zone_id"] as String)
        val multiplier = BigDecimal(input["multiplier"].toString())
        val cache = pricingQuoteService.applySurge(zoneId, multiplier, Instant.now(), UUID.randomUUID())
        return mapOf(
            "zone_id" to zoneId.toString(),
            "multiplier" to cache.multiplier.toDouble(),
            "version" to cache.version,
        )
    }

    /**
     * Conductor task: pricing.geo_override — applies an OD-corridor
     * override (called by admin-service geo-config API).
     *
     * Input: { tenant_id, city_id, origin_zone_id, destination_zone_id,
     *          ride_type, rule_kind, value, priority }
     * Output: { binding_id, version }
     */
    fun geoOverride(input: Map<String, Any?>): Map<String, Any?> {
        @Suppress("UNCHECKED_CAST")
        val value = (input["value"] as? Map<String, Any?>) ?: emptyMap()
        val binding = pricingQuoteService.upsertRuleBinding(
            tenantId = input["tenant_id"] as String,
            cityId = input["city_id"] as? String,
            originZoneId = (input["origin_zone_id"] as? String)?.let(UUID::fromString),
            destinationZoneId = (input["destination_zone_id"] as? String)?.let(UUID::fromString),
            rideType = input["ride_type"] as? String,
            ruleKind = input["rule_kind"] as String,
            value = value,
            priority = (input["priority"] as? Number)?.toInt() ?: 100,
            effectiveFrom = null,
            effectiveTo = null,
            actorId = UUID.randomUUID(),
        )
        return mapOf(
            "binding_id" to requireNotNull(binding.id) { "RuleBinding.id must be assigned after save" }.toString(),
            "version" to binding.version,
        )
    }

    /**
     * Conductor task: pricing.fairness_band — computes the
     * Make-a-Deal fairness band for an in-driver deal.
     *
     * Input: { quote_id }
     * Output: { min_minor, max_minor }
     */
    fun fairnessBand(input: Map<String, Any?>): Map<String, Any> {
        val quoteId = UUID.fromString(input["quote_id"] as String)
        return pricingQuoteService.computeFairnessBand(quoteId)
    }

    private fun sha256(payload: String): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        val bytes = digest.digest(payload.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }
}