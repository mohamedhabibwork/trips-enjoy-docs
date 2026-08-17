package com.trips_enjoy.trip.application

import com.trips_enjoy.trip.domain.IdempotencyRecord
import com.trips_enjoy.trip.domain.OutboxEvent
import com.trips_enjoy.trip.domain.OutboxEventRepository
import com.trips_enjoy.trip.domain.Request
import com.trips_enjoy.trip.domain.RequestRepository
import com.trips_enjoy.trip.domain.Trip
import com.trips_enjoy.trip.domain.TripLocationPoint
import com.trips_enjoy.trip.domain.TripLocationPointRepository
import com.trips_enjoy.trip.domain.TripLocationPointKey
import com.trips_enjoy.trip.domain.TripReward
import com.trips_enjoy.trip.domain.TripRewardRepository
import com.trips_enjoy.trip.domain.TripRewardReversal
import com.trips_enjoy.trip.domain.TripRewardReversalRepository
import com.trips_enjoy.trip.domain.TripRepository
import com.trips_enjoy.trip.domain.TripStateHistory
import com.trips_enjoy.trip.domain.TripStateHistoryRepository
import com.trips_enjoy.trip.domain.TripStop
import com.trips_enjoy.trip.domain.TripStopRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

/**
 * The trip write-service — encapsulates every state-machine mutation:
 *   - TripRequest lifecycle (price / submit / cancel / convert)
 *   - Trip lifecycle (match / arrive / start / complete / cancel /
 *     no_show / rate)
 *   - TripStop lifecycle (arrive / depart)
 *   - TripLocationPoint recording
 *   - TripReward grant + reversal
 *
 * Every mutation is idempotent on the Idempotency-Key, writes a row
 * to `trip_state_history` for audit, and publishes one or more rows
 * to `outbox_event` for kafka publication.
 *
 * The 4 BFF consumers for upstream services (driver-service for
 * matching, pricing-service for fare snapshots, customer-service for
 * rider lookup, payment-service for capture) live in the
 * KafkaConsumerConfiguration — not here.
 */
@Service
class TripWriteService(
    private val requestRepository: RequestRepository,
    private val tripRepository: TripRepository,
    private val stopRepository: TripStopRepository,
    private val locationPointRepository: TripLocationPointRepository,
    private val stateHistoryRepository: TripStateHistoryRepository,
    private val rewardRepository: TripRewardRepository,
    private val rewardReversalRepository: TripRewardReversalRepository,
    private val outboxRepository: OutboxEventRepository,
    private val idemService: IdempotencyService,
) {

    @Transactional
    fun priceRequest(
        requestId: UUID,
        fareId: UUID,
        snapshot: Map<String, Any?>,
        at: Instant,
        actorKcSub: UUID,
        correlationId: UUID,
        idempotencyKey: String,
        requestHash: String,
    ): Request {
        val existing = idemService.findExisting(IdempotencyRecord.SCOPE_TRIP_REQUEST, idempotencyKey)
        if (existing != null) {
            require(existing.requestHash == requestHash) { "idempotency key body mismatch" }
            val cached = requestRepository.findById(UUID.fromString(existing.responseBody?.get("request_id") as String? ?: ""))
                .orElseThrow { error("idempotency record refers to missing request") }
            return cached
        }
        val request = requestRepository.findById(requestId).orElseThrow()
        request.price(fareId, snapshot, at)
        writeStateHistory(requestId, null, Request.STATUS_PRICED, actorKcSub, "rider", "fare_id=$fareId", correlationId, at)
        idemService.record(
            IdempotencyRecord.SCOPE_TRIP_REQUEST,
            idempotencyKey,
            requestHash,
            200,
            mapOf("request_id" to request.id.toString(), "fare_id" to fareId.toString()),
            actorKcSub,
            at,
        )
        emitEvent(requestId, "trip.request.priced.v1", correlationId, actorKcSub, mapOf(
            "request_id" to request.id.toString(),
            "fare_id" to fareId.toString(),
            "final_price_minor" to snapshot["final_price_minor"].toString(),
        ))
        return request
    }

    @Transactional
    fun submitRequest(requestId: UUID, at: Instant, actorKcSub: UUID, correlationId: UUID): Request {
        val request = requestRepository.findById(requestId).orElseThrow()
        val fromState = request.status
        request.submit(at)
        writeStateHistory(requestId, fromState, Request.STATUS_SUBMITTED, actorKcSub, "rider", null, correlationId, at)
        emitEvent(requestId, "trip.request.submitted.v1", correlationId, actorKcSub, mapOf("request_id" to requestId.toString()))
        return request
    }

    @Transactional
    fun convertRequestToTrip(
        requestId: UUID,
        tripId: UUID,
        driverId: UUID,
        vehicleId: UUID,
        fareId: UUID,
        distanceKm: BigDecimal?,
        durationMin: BigDecimal?,
        at: Instant,
        actorKcSub: UUID,
        correlationId: UUID,
    ): Trip {
        val request = requestRepository.findById(requestId).orElseThrow()
        val fromState = request.status
        request.convert(at)
        // Phase C (platform DRY): trip `id` is auto-populated by the
        // platform `BaseEntity` `@UuidGenerator` (it now expects the
        // caller's tripId argument to be ignored). We still honour the
        // caller-supplied tripId by setting it post-construction.
        val trip = Trip(
            requestId = requestId,
            riderId = request.riderId,
            driverId = driverId,
            vehicleId = vehicleId,
            cityId = request.cityId,
            rideType = request.rideType,
            fareId = fareId,
            originZoneId = request.originZoneId,
            destinationZoneId = request.destinationZoneId,
            distanceKm = distanceKm,
            durationMin = durationMin,
            correlationId = correlationId,
        )
        trip.id = tripId
        trip.match(driverId, vehicleId, fareId, at)
        tripRepository.save(trip)
        writeStateHistory(tripId, null, Trip.STATUS_MATCHED, actorKcSub, "dispatch", "driver=$driverId", correlationId, at)
        emitEvent(tripId, "trip.request.matched.v1", correlationId, actorKcSub, mapOf(
            "trip_id" to tripId.toString(),
            "driver_id" to driverId.toString(),
            "vehicle_id" to vehicleId.toString(),
        ))
        return trip
    }

    @Transactional
    fun cancelRequest(requestId: UUID, reason: String, at: Instant, actorKcSub: UUID, correlationId: UUID): Request {
        val request = requestRepository.findById(requestId).orElseThrow()
        val fromState = request.status
        request.cancel(reason, at)
        writeStateHistory(requestId, fromState, Request.STATUS_CANCELLED, actorKcSub, "rider", reason, correlationId, at)
        emitEvent(requestId, "trip.request.cancelled.v1", correlationId, actorKcSub, mapOf("request_id" to requestId.toString(), "reason" to reason))
        return request
    }

    @Transactional
    fun arriveAtPickup(tripId: UUID, at: Instant, actorKcSub: UUID, correlationId: UUID): Trip {
        val trip = tripRepository.findByIdAndDeletedAtIsNull(tripId)
            ?: throw IllegalArgumentException("trip $tripId not found")
        val fromState = trip.status
        trip.arrive(at)
        writeStateHistory(tripId, fromState, Trip.STATUS_ARRIVED, actorKcSub, "driver", null, correlationId, at)
        emitEvent(tripId, "trip.arrived.v1", correlationId, actorKcSub, mapOf("trip_id" to tripId.toString()))
        return trip
    }

    @Transactional
    fun startTrip(tripId: UUID, distanceKm: BigDecimal, durationMin: BigDecimal, at: Instant, actorKcSub: UUID, correlationId: UUID): Trip {
        val trip = tripRepository.findByIdAndDeletedAtIsNull(tripId)
            ?: throw IllegalArgumentException("trip $tripId not found")
        val fromState = trip.status
        trip.start(distanceKm, durationMin, at)
        writeStateHistory(tripId, fromState, Trip.STATUS_IN_PROGRESS, actorKcSub, "driver", null, correlationId, at)
        emitEvent(tripId, "trip.started.v1", correlationId, actorKcSub, mapOf("trip_id" to tripId.toString()))
        return trip
    }

    @Transactional
    fun completeTrip(tripId: UUID, finalPriceMinor: Long, finalCurrency: String, at: Instant, actorKcSub: UUID, correlationId: UUID): Trip {
        val trip = tripRepository.findByIdAndDeletedAtIsNull(tripId)
            ?: throw IllegalArgumentException("trip $tripId not found")
        val fromState = trip.status
        trip.complete(finalPriceMinor, finalCurrency, at)
        writeStateHistory(tripId, fromState, Trip.STATUS_COMPLETED, actorKcSub, "driver", null, correlationId, at)
        emitEvent(tripId, "trip.completed.v1", correlationId, actorKcSub, mapOf(
            "trip_id" to tripId.toString(),
            "final_price_minor" to finalPriceMinor.toString(),
            "currency" to finalCurrency,
        ))
        return trip
    }

    @Transactional
    fun cancelTrip(tripId: UUID, reason: String, at: Instant, actorKcSub: UUID, correlationId: UUID): Trip {
        val trip = tripRepository.findByIdAndDeletedAtIsNull(tripId)
            ?: throw IllegalArgumentException("trip $tripId not found")
        val fromState = trip.status
        trip.cancel(reason, at)
        writeStateHistory(tripId, fromState, Trip.STATUS_CANCELLED, actorKcSub, "admin", reason, correlationId, at)
        emitEvent(tripId, "trip.cancelled.v1", correlationId, actorKcSub, mapOf("trip_id" to tripId.toString(), "reason" to reason))
        return trip
    }

    @Transactional
    fun rateTrip(tripId: UUID, score: Short, comment: String?, at: Instant, actorKcSub: UUID, correlationId: UUID): Trip {
        val trip = tripRepository.findByIdAndDeletedAtIsNull(tripId)
            ?: throw IllegalArgumentException("trip $tripId not found")
        val fromState = trip.status
        trip.rate(score, comment, at)
        writeStateHistory(tripId, fromState, Trip.STATUS_COMPLETED, actorKcSub, "rider", "rating=$score", correlationId, at)
        emitEvent(tripId, "trip.rated.v1", correlationId, actorKcSub, mapOf("trip_id" to tripId.toString(), "rating" to score.toInt()))
        return trip
    }

    @Transactional
    fun addStop(tripId: UUID, sequence: Int, zoneId: UUID?, address: String?, actorKcSub: UUID, correlationId: UUID): TripStop {
        val stop = TripStop(
            tripId = tripId,
            sequence = sequence,
            zoneId = zoneId,
            address = address,
        )
        // Phase C (platform DRY): `id` is auto-populated by the
        // platform `BaseEntity` `@UuidGenerator`; `createdBy` /
        // `updatedBy` are populated by `PlatformAuditorAware` from the
        // JWT `sub`.
        stopRepository.save(stop)
        emitEvent(tripId, "trip.stop.added.v1", correlationId, actorKcSub, mapOf("trip_id" to tripId.toString(), "sequence" to sequence))
        return stop
    }

    @Transactional
    fun recordLocation(
        tripId: UUID,
        latitude: BigDecimal,
        longitude: BigDecimal,
        accuracyM: BigDecimal?,
        speedKmh: BigDecimal?,
        headingDeg: BigDecimal?,
        at: Instant,
        actorKcSub: UUID,
        correlationId: UUID,
    ): TripLocationPoint {
        val point = TripLocationPoint(
            id = UUID.randomUUID(),
            tripId = tripId,
            latitude = latitude,
            longitude = longitude,
            accuracyM = accuracyM,
            speedKmh = speedKmh,
            headingDeg = headingDeg,
            recordedAt = at,
            correlationId = correlationId,
        )
        locationPointRepository.save(point)
        return point
    }

    @Transactional
    fun grantReward(
        tripId: UUID,
        driverId: UUID,
        riderId: UUID,
        amountMinor: Long,
        currency: String,
        reason: String,
        at: Instant,
        actorKcSub: UUID,
        correlationId: UUID,
    ): TripReward {
        val reward = TripReward(
            id = UUID.randomUUID(),
            tripId = tripId,
            driverId = driverId,
            riderId = riderId,
            amountMinor = amountMinor,
            currency = currency,
            grantedAt = at,
            reason = reason,
            correlationId = correlationId,
        )
        rewardRepository.save(reward)
        emitEvent(tripId, "trip.reward.granted.v1", correlationId, actorKcSub, mapOf(
            "trip_id" to tripId.toString(),
            "driver_id" to driverId.toString(),
            "amount_minor" to amountMinor.toString(),
            "currency" to currency,
        ))
        return reward
    }

    @Transactional
    fun reverseReward(
        rewardId: UUID,
        reason: String,
        at: Instant,
        actorKcSub: UUID,
        correlationId: UUID,
    ): TripRewardReversal {
        val reward = rewardRepository.findById(rewardId).orElseThrow()
        val reversal = TripRewardReversal(
            id = UUID.randomUUID(),
            rewardId = rewardId,
            tripId = reward.tripId,
            reversedByKcSub = actorKcSub,
            reason = reason,
            correlationId = correlationId,
            reversedAt = at,
        )
        rewardReversalRepository.save(reversal)
        emitEvent(reward.tripId, "trip.reward.reversed.v1", correlationId, actorKcSub, mapOf(
            "trip_id" to reward.tripId.toString(),
            "reward_id" to rewardId.toString(),
            "reason" to reason,
        ))
        return reversal
    }

    private fun writeStateHistory(
        tripId: UUID,
        fromState: String?,
        toState: String,
        actorKcSub: UUID,
        actorKind: String,
        reason: String?,
        correlationId: UUID,
        at: Instant,
    ) {
        val history = TripStateHistory(
            id = UUID.randomUUID(),
            tripId = tripId,
            fromState = fromState,
            toState = toState,
            actorKcSub = actorKcSub,
            actorKind = actorKind,
            reason = reason,
            correlationId = correlationId,
            occurredAt = at,
        )
        stateHistoryRepository.save(history)
    }

    private fun emitEvent(
        tripId: UUID,
        eventType: String,
        correlationId: UUID,
        createdBy: UUID,
        payload: Map<String, Any?>,
    ) {
        outboxRepository.save(
            OutboxEvent(
                id = UUID.randomUUID(),
                aggregateType = "Trip",
                aggregateId = tripId,
                eventType = eventType,
                topic = eventType,
                payload = payload,
                correlationId = correlationId,
                createdBy = createdBy,
            ),
        )
    }
}