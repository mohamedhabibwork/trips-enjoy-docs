package com.trips_enjoy.trip.api

import com.trips_enjoy.trip.domain.Request
import com.trips_enjoy.trip.domain.Trip
import jakarta.validation.Valid
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

data class CreateTripRequestRequest(
    @field:NotNull val riderId: UUID,
    val cityId: UUID? = null,
    val originZoneId: UUID? = null,
    val destinationZoneId: UUID? = null,
    @field:NotBlank @field:Pattern(regexp = "^(standard|xl|comfort|pool|premium|van|accessible)$") val rideType: String = "standard",
    val fareId: UUID? = null,
    val quoteSnapshot: Map<String, Any?>? = null,
)

data class PriceTripRequestRequest(
    @field:NotNull val requestId: UUID,
    @field:NotNull val fareId: UUID,
    @field:NotNull val snapshot: Map<String, Any?>,
)

data class SubmitTripRequestRequest(@field:NotNull val requestId: UUID)
data class CancelTripRequestRequest(@field:NotNull val requestId: UUID, @field:NotBlank val reason: String)

data class ConvertRequestToTripRequest(
    @field:NotNull val requestId: UUID,
    @field:NotNull val tripId: UUID,
    @field:NotNull val driverId: UUID,
    @field:NotNull val vehicleId: UUID,
    @field:NotNull val fareId: UUID,
    val distanceKm: BigDecimal? = null,
    val durationMin: BigDecimal? = null,
)

data class ArriveAtPickupRequest(@field:NotNull val tripId: UUID)
data class StartTripRequest(
    @field:NotNull val tripId: UUID,
    @field:Min(0) val distanceKm: BigDecimal,
    @field:Min(0) val durationMin: BigDecimal,
)
data class CompleteTripRequest(
    @field:NotNull val tripId: UUID,
    @field:Min(1) val finalPriceMinor: Long,
    @field:NotBlank @field:Pattern(regexp = "^[A-Z]{3}$") val currency: String = "USD",
)
data class CancelTripRequest(@field:NotNull val tripId: UUID, @field:NotBlank val reason: String)
data class RateTripRequest(
    @field:NotNull val tripId: UUID,
    @field:Min(1) val rating: Int,
    val comment: String? = null,
)
data class RecordLocationRequest(
    @field:NotNull val tripId: UUID,
    @field:NotNull val latitude: BigDecimal,
    @field:NotNull val longitude: BigDecimal,
    val accuracyM: BigDecimal? = null,
    val speedKmh: BigDecimal? = null,
    val headingDeg: BigDecimal? = null,
)
data class AddStopRequest(
    @field:NotNull val tripId: UUID,
    @field:Min(0) val sequence: Int,
    val zoneId: UUID? = null,
    val address: String? = null,
)
data class GrantRewardRequest(
    @field:NotNull val tripId: UUID,
    @field:NotNull val driverId: UUID,
    @field:NotNull val riderId: UUID,
    @field:Min(1) val amountMinor: Long,
    @field:NotBlank @field:Pattern(regexp = "^[A-Z]{3}$") val currency: String = "USD",
    @field:NotBlank val reason: String,
)
data class ReverseRewardRequest(@field:NotNull val rewardId: UUID, @field:NotBlank val reason: String)

data class TripRequestResponse(
    val requestId: UUID,
    val riderId: UUID,
    val rideType: String,
    val status: String,
    val fareId: UUID?,
)

data class TripResponse(
    val tripId: UUID,
    val requestId: UUID,
    val riderId: UUID,
    val driverId: UUID?,
    val vehicleId: UUID?,
    val status: String,
    val rideType: String,
    val originZoneId: UUID?,
    val destinationZoneId: UUID?,
    val distanceKm: BigDecimal?,
    val durationMin: BigDecimal?,
    val finalPriceMinor: Long?,
    val finalCurrency: String,
    val matchedAt: Instant?,
    val arrivedAt: Instant?,
    val startedAt: Instant?,
    val completedAt: Instant?,
    val cancelledAt: Instant?,
    val rating: Short?,
)

private fun Request.toResponse(): TripRequestResponse = TripRequestResponse(
    requestId = id,
    riderId = riderId,
    rideType = rideType,
    status = status,
    fareId = fareId,
)

private fun Trip.toResponse(): TripResponse = TripResponse(
    tripId = id,
    requestId = requestId,
    riderId = riderId,
    driverId = driverId,
    vehicleId = vehicleId,
    status = status,
    rideType = rideType,
    originZoneId = originZoneId,
    destinationZoneId = destinationZoneId,
    distanceKm = distanceKm,
    durationMin = durationMin,
    finalPriceMinor = finalPriceMinor,
    finalCurrency = finalCurrency,
    matchedAt = matchedAt,
    arrivedAt = arrivedAt,
    startedAt = startedAt,
    completedAt = completedAt,
    cancelledAt = cancelledAt,
    rating = rating,
)