package com.trips_enjoy.admin.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.time.Instant
import java.util.UUID

/**
 * A pricing geo-config rule. Mirrors `admin.pricing_geo_config` per
 * docs/services/admin-service/ERD.md §3. The admin-service is the
 * canonical writer for `pricing.geo_config.updated.v1` events; the
 * pricing-service mirrors the active rule into `pricing.rule_bindings`.
 *
 * The 7 valid rule_kinds are the same as the pricing-service
 * `RuleBinding.VALID_RULE_KINDS`.
 */
@Entity
@Table(name = "pricing_geo_config", schema = "admin")
class PricingGeoConfig(
    @Id val id: UUID,
    @Column(name = "tenant_id", nullable = false) var tenantId: String = "global",
    @Column(name = "city_id") var cityId: String? = null,
    @Column(name = "origin_zone_id") var originZoneId: UUID? = null,
    @Column(name = "destination_zone_id") var destinationZoneId: UUID? = null,
    @Column(name = "ride_type") var rideType: String? = null,
    @Column(name = "rule_kind", nullable = false) var ruleKind: String,
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb") var value: Map<String, Any?>,
    @Column(nullable = false) var priority: Int = 100,
    @Column(name = "effective_from") var effectiveFrom: Instant? = null,
    @Column(name = "effective_to") var effectiveTo: Instant? = null,
    @Column(name = "created_by_kc_sub", nullable = false) val createdByKcSub: UUID,
    @Column(name = "created_at", nullable = false) val createdAt: Instant = Instant.now(),
    @Column(name = "updated_by_kc_sub") var updatedByKcSub: UUID? = createdByKcSub,
    @Column(name = "updated_at", nullable = false) var updatedAt: Instant = Instant.now(),
    @Column(name = "row_version", nullable = false) var rowVersion: Long = 1L,
) {
    companion object {
        const val RULE_BASE_FARE_OVERRIDE = "base_fare_override"
        const val RULE_PER_KM_OVERRIDE = "per_km_override"
        const val RULE_PER_MIN_OVERRIDE = "per_min_override"
        const val RULE_SURGE_PRESSURE = "surge_pressure"
        const val RULE_LOYALTY_DISCOUNT = "loyalty_discount"
        const val RULE_MIN_FARE_OVERRIDE = "min_fare_override"
        const val RULE_OD_CORRIDOR = "od_corridor"

        val VALID_RULE_KINDS: Set<String> = setOf(
            RULE_BASE_FARE_OVERRIDE, RULE_PER_KM_OVERRIDE, RULE_PER_MIN_OVERRIDE,
            RULE_SURGE_PRESSURE, RULE_LOYALTY_DISCOUNT, RULE_MIN_FARE_OVERRIDE,
            RULE_OD_CORRIDOR,
        )
    }

    init {
        require(ruleKind in VALID_RULE_KINDS) { "unknown rule_kind $ruleKind" }
        if (ruleKind == RULE_OD_CORRIDOR) {
            require(originZoneId != null) { "OD-corridor rule must have origin_zone_id" }
            require(destinationZoneId != null) { "OD-corridor rule must have destination_zone_id" }
        } else {
            require(originZoneId == null) { "non-OD rule must not have origin_zone_id" }
            require(destinationZoneId == null) { "non-OD rule must not have destination_zone_id" }
        }
    }
}