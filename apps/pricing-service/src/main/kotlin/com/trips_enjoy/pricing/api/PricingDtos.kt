package com.trips_enjoy.pricing.api

import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import java.math.BigDecimal
import java.util.UUID

data class CreateQuoteRequest(
    val customerId: String? = null,
    @field:NotBlank @field:Pattern(regexp = "^(ride|food)$") val productType: String,
    val originZoneId: String? = null,
    val destinationZoneId: String? = null,
    val rideType: String? = null,
    @field:Min(0) val distanceKm: BigDecimal,
    @field:Min(0) val durationMin: BigDecimal,
    @field:Min(0) val baseFare: BigDecimal,
    @field:Min(0) val perKm: BigDecimal,
    @field:Min(0) val perMin: BigDecimal,
    @field:Min(0) val taxRate: BigDecimal,
    val loyaltyCustomerId: String? = null,
    val correlationId: String? = null,
) {
    fun customerIdAsUuidOrNull(): UUID? = customerId?.let(UUID::fromString)
    fun originZoneIdAsUuidOrNull(): UUID? = originZoneId?.let(UUID::fromString)
    fun destinationZoneIdAsUuidOrNull(): UUID? = destinationZoneId?.let(UUID::fromString)
    fun loyaltyCustomerIdAsUuidOrNull(): UUID? = loyaltyCustomerId?.let(UUID::fromString)
}

data class QuoteResponse(
    val quoteId: String,
    val customerId: String?,
    val productType: String,
    val status: String,
    val expiresAt: String,
    val finalPrice: Long,
    val currency: String,
)

data class ReQuoteRequest(
    val customerId: String? = null,
    @field:NotBlank @field:Pattern(regexp = "^(ride|food)$") val productType: String,
    val originZoneId: String? = null,
    val destinationZoneId: String? = null,
    val rideType: String? = null,
    @field:Min(0) val distanceKm: BigDecimal,
    @field:Min(0) val durationMin: BigDecimal,
    @field:Min(0) val baseFare: BigDecimal,
    @field:Min(0) val perKm: BigDecimal,
    @field:Min(0) val perMin: BigDecimal,
    @field:Min(0) val taxRate: BigDecimal,
    val loyaltyCustomerId: String? = null,
)

data class CancellationFeeRequest(@field:Min(0) val waitedMinutes: Int = 0)
data class CancellationFeeResponse(val feeMinor: Long)
data class WaitingFeeRequest(@field:Min(0) val waitedMinutes: Int)
data class WaitingFeeResponse(val feeMinor: Long)
data class FairnessBandResponse(val minMinor: Long, val maxMinor: Long)
data class SnapshotRequest(
    @field:NotBlank @field:Pattern(regexp = "^[A-Z]{3}$") val currency: String,
)

data class UpsertRuleBindingRequest(
    @field:NotBlank @field:Pattern(regexp = "^[a-zA-Z0-9_-]{1,50}$") val tenantId: String,
    val cityId: String? = null,
    val originZoneId: String? = null,
    val destinationZoneId: String? = null,
    val rideType: String? = null,
    @field:NotBlank @field:Pattern(
        regexp = "^(base_fare_override|per_km_override|per_min_override|surge_pressure|loyalty_discount|min_fare_override|od_corridor)$"
    ) val ruleKind: String,
    val value: Map<String, Any?> = emptyMap(),
    @field:Min(0) val priority: Int = 100,
)