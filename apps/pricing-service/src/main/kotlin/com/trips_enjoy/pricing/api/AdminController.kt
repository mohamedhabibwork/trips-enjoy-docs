package com.trips_enjoy.pricing.api

import com.trips_enjoy.pricing.application.PricingQuoteService
import com.trips_enjoy.pricing.domain.GeoOverrideRepository
import com.trips_enjoy.pricing.domain.LoyaltyFrequentCacheRepository
import com.trips_enjoy.pricing.domain.RatingDensityCacheRepository
import com.trips_enjoy.pricing.domain.RuleBinding
import com.trips_enjoy.pricing.domain.RuleBindingRepository
import com.trips_enjoy.pricing.domain.RuleBindingsHistory
import com.trips_enjoy.pricing.domain.RuleBindingsHistoryRepository
import com.trips_enjoy.pricing.domain.SurgeCache
import com.trips_enjoy.pricing.domain.SurgeCacheRepository
import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.Instant
import java.util.UUID

@RestController
@RequestMapping("/admin/v1/pricing")
class AdminController(
    private val pricingQuoteService: PricingQuoteService,
    private val ruleBindingRepository: RuleBindingRepository,
    private val ruleBindingsHistoryRepository: RuleBindingsHistoryRepository,
    private val surgeCacheRepository: SurgeCacheRepository,
    private val ratingDensityCacheRepository: RatingDensityCacheRepository,
    private val loyaltyFrequentCacheRepository: LoyaltyFrequentCacheRepository,
    private val geoOverrideRepository: GeoOverrideRepository,
) {

    @GetMapping("/rule-bindings")
    @PreAuthorize("hasAuthority('SCOPE_pricing.admin')")
    fun listRuleBindings(): List<Map<String, Any?>> =
        ruleBindingRepository.findActive(Instant.now())
            .map { ruleBindingToMap(it) }

    @PostMapping("/rule-bindings")
    @PreAuthorize("hasAuthority('SCOPE_pricing.admin')")
    @Transactional
    fun upsertRuleBinding(
        @Valid @RequestBody req: UpsertRuleBindingRequest,
        @RequestHeader("X-User-Id") actingUser: String,
    ): ResponseEntity<Map<String, Any?>> {
        val binding = pricingQuoteService.upsertRuleBinding(
            tenantId = req.tenantId,
            cityId = req.cityId,
            originZoneId = req.originZoneId?.let(UUID::fromString),
            destinationZoneId = req.destinationZoneId?.let(UUID::fromString),
            rideType = req.rideType,
            ruleKind = req.ruleKind,
            value = req.value,
            priority = req.priority,
            effectiveFrom = null,
            effectiveTo = null,
            createdBy = UUID.fromString(actingUser),
        )
        return ResponseEntity.ok(ruleBindingToMap(binding))
    }

    @GetMapping("/rule-bindings/{id}/history")
    @PreAuthorize("hasAuthority('SCOPE_pricing.admin')")
    fun ruleBindingHistory(@PathVariable("id") id: String): List<Map<String, Any?>> =
        ruleBindingsHistoryRepository.findByBindingIdOrderByCreatedAtDesc(UUID.fromString(id))
            .map { historyToMap(it) }

    @GetMapping("/surge-cache")
    @PreAuthorize("hasAuthority('SCOPE_pricing.admin')")
    fun listSurgeCache(): List<Map<String, Any?>> =
        surgeCacheRepository.findAll()
            .map { mapOf("zone_id" to it.zoneId.toString(), "multiplier" to it.multiplier.toDouble(), "version" to it.version, "updated_at" to it.updatedAt.toString()) }

    @GetMapping("/rating-density-cache")
    @PreAuthorize("hasAuthority('SCOPE_pricing.admin')")
    fun listRatingDensityCache(): List<Map<String, Any?>> =
        ratingDensityCacheRepository.findAll()
            .map { mapOf("zone_id" to it.zoneId.toString(), "window_minutes" to it.windowMinutes, "avg_rating" to it.avgRating.toDouble(), "sample_size" to it.sampleSize, "computed_at" to it.computedAt.toString()) }

    @GetMapping("/loyalty-frequent-cache")
    @PreAuthorize("hasAuthority('SCOPE_pricing.admin')")
    fun listLoyaltyFrequentCache(): List<Map<String, Any?>> =
        loyaltyFrequentCacheRepository.findAll()
            .map { mapOf("customer_id" to it.customerId.toString(), "zone_id" to it.zoneId.toString(), "trip_count_30d" to it.tripCount30d, "tier" to it.tierAtTrip, "expires_at" to it.expiresAt.toString()) }

    @GetMapping("/geo-overrides")
    @PreAuthorize("hasAuthority('SCOPE_pricing.admin')")
    fun listGeoOverrides(): List<Map<String, Any?>> =
        geoOverrideRepository.findAll()
            .map { mapOf("id" to it.id.toString(), "origin_zone_id" to it.originZoneId.toString(), "destination_zone_id" to it.destinationZoneId.toString(), "ride_type" to it.rideType, "multiplier_adjustment" to it.multiplierAdjustment.toDouble(), "version" to it.version) }

    private fun ruleBindingToMap(r: RuleBinding): Map<String, Any?> = mapOf(
        "id" to r.id.toString(),
        "version" to r.version,
        "tenant_id" to r.tenantId,
        "city_id" to r.cityId,
        "origin_zone_id" to r.originZoneId?.toString(),
        "destination_zone_id" to r.destinationZoneId?.toString(),
        "ride_type" to r.rideType,
        "rule_kind" to r.ruleKind,
        "value" to r.value,
        "priority" to r.priority,
        "effective_from" to r.effectiveFrom?.toString(),
        "effective_to" to r.effectiveTo?.toString(),
        "superseded_by_id" to r.supersededById?.toString(),
    )

    private fun historyToMap(h: RuleBindingsHistory): Map<String, Any?> = mapOf(
        "id" to h.id.toString(),
        "binding_id" to h.bindingId.toString(),
        "version" to h.version,
        "action" to h.action,
        "actor_id" to h.actorId.toString(),
        "payload" to h.payload,
        "created_at" to h.createdAt.toString(),
    )
}