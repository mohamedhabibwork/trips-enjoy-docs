package com.trips_enjoy.trip.api

import com.fasterxml.jackson.databind.ObjectMapper
import com.trips_enjoy.trip.application.IdempotencyService
import com.trips_enjoy.trip.application.TripWriteService
import com.trips_enjoy.trip.domain.IdempotencyRecord
import com.trips_enjoy.trip.domain.RequestRepository
import com.trips_enjoy.trip.domain.Trip
import com.trips_enjoy.trip.domain.TripLocationPoint
import com.trips_enjoy.trip.domain.TripLocationPointKey
import com.trips_enjoy.trip.domain.TripRepository
import com.trips_enjoy.trip.domain.TripReward
import com.trips_enjoy.trip.domain.TripRewardRepository
import com.trips_enjoy.trip.domain.TripRewardReversal
import com.trips_enjoy.trip.domain.TripRewardReversalRepository
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID

/**
 * The trip-service REST controller. Mirrors
 * docs/services/trip-service/INTEGRATION.md §1.
 *
 * The surface covers 15 endpoints across /v1/trips (the rider's view)
 * + /v1/trips/{id}/... (the driver's state mutations) + a small
 * /admin/v1/trips surface for the SUPER_ADMIN BFF.
 */
@RestController
@RequestMapping("/v1/trips")
class TripController(
    private val writeService: TripWriteService,
    private val requestRepository: RequestRepository,
    private val tripRepository: TripRepository,
    private val rewardRepository: TripRewardRepository,
    private val rewardReversalRepository: TripRewardReversalRepository,
    private val idemService: IdempotencyService,
    private val objectMapper: ObjectMapper,
) {

    @PostMapping
    @PreAuthorize("hasAuthority('SCOPE_trip.write') or hasAuthority('SCOPE_rider.write')")
    fun createRequest(
        @Valid @RequestBody req: CreateTripRequestRequest,
        @RequestHeader("Idempotency-Key") idempotencyKey: String,
        @RequestHeader("X-User-Id") actingUser: String,
    ): ResponseEntity<TripRequestResponse> {
        val requestHash = sha256(objectMapper.writeValueAsString(req))
        val correlationId = UUID.randomUUID()
        val actorId = UUID.fromString(actingUser)
        val request = com.trips_enjoy.trip.domain.Request(
            id = UUID.randomUUID(),
            riderId = req.riderId,
            cityId = req.cityId,
            originZoneId = req.originZoneId,
            destinationZoneId = req.destinationZoneId,
            rideType = req.rideType,
            fareId = req.fareId,
            quoteSnapshot = req.quoteSnapshot,
            correlationId = correlationId,
            idempotencyKey = idempotencyKey,
            createdBy = actorId,
        )
        requestRepository.save(request)
        idemService.record(
            IdempotencyRecord.SCOPE_TRIP_REQUEST,
            idempotencyKey,
            requestHash,
            201,
            mapOf("request_id" to request.id.toString()),
            actorId,
        )
        return ResponseEntity.status(HttpStatus.CREATED).body(request.toResponse())
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('SCOPE_trip.read')")
    fun get(@PathVariable("id") id: String): TripResponse =
        tripRepository.findByIdAndDeletedAtIsNull(UUID.fromString(id))
            ?.toResponse() ?: throw NoSuchElementException("trip $id not found")

    @GetMapping("/active")
    @PreAuthorize("hasAuthority('SCOPE_trip.read') or hasAuthority('SCOPE_driver.read')")
    fun activeTrips(@RequestHeader("X-User-Id") userId: String): List<TripResponse> {
        val userUuid = UUID.fromString(userId)
        val asRider = tripRepository.findByRiderIdAndDeletedAtIsNullOrderByCreatedAtDesc(userUuid)
        val asDriver = tripRepository.findByDriverIdAndDeletedAtIsNullOrderByCreatedAtDesc(userUuid)
        return (asRider + asDriver).map { it.toResponse() }
    }

    @PostMapping("/{id}/arrive")
    @PreAuthorize("hasAuthority('SCOPE_driver.write') or hasAuthority('SCOPE_trip.write')")
    fun arriveAtPickup(
        @PathVariable("id") id: String,
        @RequestHeader("X-User-Id") actingUser: String,
        @RequestHeader("X-Correlation-Id") correlationId: String? = null,
    ): TripResponse {
        val actorId = UUID.fromString(actingUser)
        val at = Instant.now()
        val trip = writeService.arriveAtPickup(
            tripId = UUID.fromString(id),
            at = at,
            actorKcSub = actorId,
            correlationId = UUID.fromString(correlationId ?: UUID.randomUUID().toString()),
        )
        return trip.toResponse()
    }

    @PostMapping("/{id}/start")
    @PreAuthorize("hasAuthority('SCOPE_driver.write')")
    fun start(
        @PathVariable("id") id: String,
        @Valid @RequestBody req: StartTripRequest,
        @RequestHeader("X-User-Id") actingUser: String,
        @RequestHeader("X-Correlation-Id") correlationId: String? = null,
    ): TripResponse {
        val actorId = UUID.fromString(actingUser)
        val at = Instant.now()
        val trip = writeService.startTrip(
            tripId = UUID.fromString(id),
            distanceKm = req.distanceKm,
            durationMin = req.durationMin,
            at = at,
            actorKcSub = actorId,
            correlationId = UUID.fromString(correlationId ?: UUID.randomUUID().toString()),
        )
        return trip.toResponse()
    }

    @PostMapping("/{id}/complete")
    @PreAuthorize("hasAuthority('SCOPE_driver.write')")
    fun complete(
        @PathVariable("id") id: String,
        @Valid @RequestBody req: CompleteTripRequest,
        @RequestHeader("Idempotency-Key") idempotencyKey: String,
        @RequestHeader("X-User-Id") actingUser: String,
        @RequestHeader("X-Correlation-Id") correlationId: String? = null,
    ): TripResponse {
        val actorId = UUID.fromString(actingUser)
        val at = Instant.now()
        val trip = writeService.completeTrip(
            tripId = UUID.fromString(id),
            finalPriceMinor = req.finalPriceMinor,
            finalCurrency = req.currency,
            at = at,
            actorKcSub = actorId,
            correlationId = UUID.fromString(correlationId ?: UUID.randomUUID().toString()),
        )
        return trip.toResponse()
    }

    @PostMapping("/{id}/cancel")
    @PreAuthorize("hasAuthority('SCOPE_trip.write') or hasAuthority('SCOPE_rider.write')")
    fun cancel(
        @PathVariable("id") id: String,
        @Valid @RequestBody req: CancelTripRequest,
        @RequestHeader("Idempotency-Key") idempotencyKey: String,
        @RequestHeader("X-User-Id") actingUser: String,
        @RequestHeader("X-Correlation-Id") correlationId: String? = null,
    ): TripResponse {
        val actorId = UUID.fromString(actingUser)
        val at = Instant.now()
        val trip = writeService.cancelTrip(
            tripId = UUID.fromString(id),
            reason = req.reason,
            at = at,
            actorKcSub = actorId,
            correlationId = UUID.fromString(correlationId ?: UUID.randomUUID().toString()),
        )
        return trip.toResponse()
    }

    @PostMapping("/{id}/rate")
    @PreAuthorize("hasAuthority('SCOPE_rider.write')")
    fun rate(
        @PathVariable("id") id: String,
        @Valid @RequestBody req: RateTripRequest,
        @RequestHeader("Idempotency-Key") idempotencyKey: String,
        @RequestHeader("X-User-Id") actingUser: String,
        @RequestHeader("X-Correlation-Id") correlationId: String? = null,
    ): TripResponse {
        val actorId = UUID.fromString(actingUser)
        val at = Instant.now()
        val trip = writeService.rateTrip(
            tripId = UUID.fromString(id),
            score = req.rating.toShort(),
            comment = req.comment,
            at = at,
            actorKcSub = actorId,
            correlationId = UUID.fromString(correlationId ?: UUID.randomUUID().toString()),
        )
        return trip.toResponse()
    }

    @PostMapping("/{id}/stops")
    @PreAuthorize("hasAuthority('SCOPE_trip.write') or hasAuthority('SCOPE_driver.write')")
    fun addStop(
        @PathVariable("id") id: String,
        @Valid @RequestBody req: AddStopRequest,
        @RequestHeader("X-User-Id") actingUser: String,
        @RequestHeader("X-Correlation-Id") correlationId: String? = null,
    ): ResponseEntity<Map<String, Any?>> {
        val actorId = UUID.fromString(actingUser)
        val stop = writeService.addStop(
            tripId = UUID.fromString(id),
            sequence = req.sequence,
            zoneId = req.zoneId,
            address = req.address,
            actorKcSub = actorId,
            correlationId = UUID.fromString(correlationId ?: UUID.randomUUID().toString()),
        )
        return ResponseEntity.status(HttpStatus.CREATED).body(mapOf(
            "stop_id" to stop.id.toString(),
            "sequence" to stop.sequence,
        ))
    }

    @PostMapping("/{id}/location")
    @PreAuthorize("hasAuthority('SCOPE_driver.write')")
    fun location(
        @PathVariable("id") id: String,
        @Valid @RequestBody req: RecordLocationRequest,
        @RequestHeader("X-User-Id") actingUser: String,
        @RequestHeader("X-Correlation-Id") correlationId: String? = null,
    ): ResponseEntity<Void> {
        val actorId = UUID.fromString(actingUser)
        writeService.recordLocation(
            tripId = UUID.fromString(id),
            latitude = req.latitude,
            longitude = req.longitude,
            accuracyM = req.accuracyM,
            speedKmh = req.speedKmh,
            headingDeg = req.headingDeg,
            at = Instant.now(),
            actorKcSub = actorId,
            correlationId = UUID.fromString(correlationId ?: UUID.randomUUID().toString()),
        )
        return ResponseEntity.accepted().build()
    }

    @PostMapping("/{id}/reward")
    @PreAuthorize("hasAuthority('SCOPE_trip.write') or hasAuthority('SCOPE_platform.admin')")
    fun grantReward(
        @PathVariable("id") id: String,
        @Valid @RequestBody req: GrantRewardRequest,
        @RequestHeader("X-User-Id") actingUser: String,
        @RequestHeader("X-Correlation-Id") correlationId: String? = null,
    ): ResponseEntity<Map<String, Any?>> {
        val actorId = UUID.fromString(actingUser)
        val reward = writeService.grantReward(
            tripId = UUID.fromString(id),
            driverId = req.driverId,
            riderId = req.riderId,
            amountMinor = req.amountMinor,
            currency = req.currency,
            reason = req.reason,
            at = Instant.now(),
            actorKcSub = actorId,
            correlationId = UUID.fromString(correlationId ?: UUID.randomUUID().toString()),
        )
        return ResponseEntity.status(HttpStatus.CREATED).body(mapOf(
            "reward_id" to reward.id.toString(),
            "trip_id" to reward.tripId.toString(),
        ))
    }

    @PostMapping("/{id}/reward/reverse")
    @PreAuthorize("hasAuthority('SCOPE_trip.write') or hasAuthority('SCOPE_platform.admin')")
    fun reverseReward(
        @PathVariable("id") id: String,
        @Valid @RequestBody req: ReverseRewardRequest,
        @RequestHeader("X-User-Id") actingUser: String,
        @RequestHeader("X-Correlation-Id") correlationId: String? = null,
    ): ResponseEntity<Map<String, Any?>> {
        val actorId = UUID.fromString(actingUser)
        val reversal = writeService.reverseReward(
            rewardId = UUID.fromString(req.rewardId.toString()),
            reason = req.reason,
            at = Instant.now(),
            actorKcSub = actorId,
            correlationId = UUID.fromString(correlationId ?: UUID.randomUUID().toString()),
        )
        return ResponseEntity.status(HttpStatus.CREATED).body(mapOf(
            "reversal_id" to reversal.id.toString(),
            "reward_id" to reversal.rewardId.toString(),
        ))
    }

    private fun sha256(payload: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val bytes = digest.digest(payload.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }
}

private fun com.trips_enjoy.trip.domain.Request.toResponse(): TripRequestResponse = TripRequestResponse(
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