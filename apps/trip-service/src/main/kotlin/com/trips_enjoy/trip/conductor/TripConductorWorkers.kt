package com.trips_enjoy.trip.conductor

import com.trips_enjoy.trip.application.TripWriteService
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.util.UUID

/**
 * The Conductor workflow workers for trip-service. Per
 * [ADR-0018](docs/architecture/adrs/0018-workflow-engine-conductor.md)
 * trip-service owns 4 of the 17 workflow IDs:
 *   - wf.trip.dispatch.v1     (this file — the dispatch saga)
 *   - wf.trip.complete.v1     (the completion + reward grant saga)
 *   - wf.trip.rate.v1         (the rating collection saga)
 *   - wf.trip.safety.v1       (the safety incident response saga)
 *
 * Each worker is a thin wrapper that translates a Conductor task input
 * map to a call into the TripWriteService application layer.
 */
@Component
class TripConductorWorkers(
    private val tripWriteService: TripWriteService,
) {

    /**
     * Conductor task: trip.dispatch — orchestrates the dispatch saga:
     *   request → match driver (driver-service BFF) → arrive at pickup
     * → start. Returns the trip_id when the trip is matched.
     */
    fun dispatch(input: Map<String, Any?>): Map<String, Any?> {
        val requestId = UUID.fromString(input["request_id"] as String)
        val correlationId = UUID.fromString(input["correlation_id"] as String)
        val actingUser = UUID.fromString(input["acting_user_id"] as String)
        val driverId = UUID.fromString(input["driver_id"] as String)
        val vehicleId = UUID.fromString(input["vehicle_id"] as String)
        val fareId = UUID.fromString(input["fare_id"] as String)
        val tripId = UUID.fromString(input["trip_id"] as String)

        tripWriteService.convertRequestToTrip(
            requestId = requestId,
            tripId = tripId,
            driverId = driverId,
            vehicleId = vehicleId,
            fareId = fareId,
            distanceKm = (input["distance_km"] as? Number)?.let { BigDecimal(it.toString()) },
            durationMin = (input["duration_min"] as? Number)?.let { BigDecimal(it.toString()) },
            at = java.time.Instant.now(),
            actorKcSub = actingUser,
            correlationId = correlationId,
        )
        return mapOf(
            "trip_id" to tripId.toString(),
            "request_id" to requestId.toString(),
            "driver_id" to driverId.toString(),
            "status" to "matched",
        )
    }

    /**
     * Conductor task: trip.complete — orchestrates the completion +
     * payment capture + reward grant saga. Returns the trip_id and
     * the final_price_minor.
     */
    fun complete(input: Map<String, Any?>): Map<String, Any?> {
        val tripId = UUID.fromString(input["trip_id"] as String)
        val correlationId = UUID.fromString(input["correlation_id"] as String)
        val actingUser = UUID.fromString(input["acting_user_id"] as String)
        val finalPriceMinor = (input["final_price_minor"] as Number).toLong()
        val currency = input["currency"] as? String ?: "USD"
        val driverId = UUID.fromString(input["driver_id"] as String)
        val riderId = UUID.fromString(input["rider_id"] as String)
        val rewardAmountMinor = (input["reward_amount_minor"] as? Number)?.toLong() ?: 0L

        val trip = tripWriteService.completeTrip(
            tripId = tripId,
            finalPriceMinor = finalPriceMinor,
            finalCurrency = currency,
            at = java.time.Instant.now(),
            actorKcSub = actingUser,
            correlationId = correlationId,
        )

        // Grant reward in same saga (Phase 7 rating-density rewards).
        if (rewardAmountMinor > 0) {
            tripWriteService.grantReward(
                tripId = tripId,
                driverId = driverId,
                riderId = riderId,
                amountMinor = rewardAmountMinor,
                currency = currency,
                reason = "auto_grant_on_complete",
                at = java.time.Instant.now(),
                actorKcSub = actingUser,
                correlationId = correlationId,
            )
        }

        return mapOf(
            "trip_id" to tripId.toString(),
            "final_price_minor" to finalPriceMinor.toString(),
            "currency" to currency,
            "status" to "completed",
        )
    }

    /**
     * Conductor task: trip.rate — the rating collection saga (when the
     * rider rates the trip after completion).
     */
    fun rate(input: Map<String, Any?>): Map<String, Any?> {
        val tripId = UUID.fromString(input["trip_id"] as String)
        val correlationId = UUID.fromString(input["correlation_id"] as String)
        val actingUser = UUID.fromString(input["acting_user_id"] as String)
        val rating = (input["rating"] as Number).toInt()
        val comment = input["comment"] as? String
        tripWriteService.rateTrip(
            tripId = tripId,
            score = rating.toShort(),
            comment = comment,
            at = java.time.Instant.now(),
            actorKcSub = actingUser,
            correlationId = correlationId,
        )
        return mapOf(
            "trip_id" to tripId.toString(),
            "rating" to rating,
        )
    }

    /**
     * Conductor task: trip.safety — handles a safety incident on an
     * active trip. Cancels the trip with a safety reason and triggers
     * the downstream emergency response saga.
     */
    fun safety(input: Map<String, Any?>): Map<String, Any?> {
        val tripId = UUID.fromString(input["trip_id"] as String)
        val correlationId = UUID.fromString(input["correlation_id"] as String)
        val actingUser = UUID.fromString(input["acting_user_id"] as String)
        val reason = input["reason"] as? String ?: "safety_incident"
        tripWriteService.cancelTrip(
            tripId = tripId,
            reason = "safety: $reason",
            at = java.time.Instant.now(),
            actorKcSub = actingUser,
            correlationId = correlationId,
        )
        return mapOf(
            "trip_id" to tripId.toString(),
            "status" to "cancelled",
            "reason" to reason,
        )
    }
}