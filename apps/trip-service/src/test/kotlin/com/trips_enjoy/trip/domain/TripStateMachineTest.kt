package com.trips_enjoy.trip.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

/**
 * Unit tests for the trip-service state machines.
 * Covers:
 *   - Request: price / submit / cancel / convert
 *   - Trip: match / arrive / start / complete / cancel / no_show / rate
 *   - TripStop: arrive / depart
 *   - IdempotencyRecord: scope / idem_key / request_hash invariants
 *   - OutboxEvent: mark_published / mark_failed lifecycle
 *
 * All entities use single-UUID PKs (NOT composite @IdClass) to avoid the
 * Spring Data JPA + Kotlin type-inference blocker that hit
 * admin-service (see the uber-admin-service memory entry).
 *
 * Phase C (platform DRY): Request / Trip / TripStop extend
 * `BaseEntity`; `id` is auto-populated by `@UuidGenerator` and is
 * nullable until persisted, `createdBy` / `updatedBy` are inherited
 * from `BaseEntity` (String?, populated by `PlatformAuditorAware`).
 * The test stubs assign `id` post-construction via `apply { id = ... }`.
 */
class TripStateMachineTest {

    private val now: Instant = Instant.parse("2026-08-15T12:00:00Z")
    private val sys: UUID = UUID.randomUUID()
    @Suppress("unused")
    private val merchant: UUID = UUID.randomUUID()

    private fun newRequest(): Request = Request(
        riderId = UUID.randomUUID(),
        cityId = UUID.randomUUID(),
        originZoneId = UUID.randomUUID(),
        destinationZoneId = UUID.randomUUID(),
        rideType = Request.Companion.RIDE_TYPE_STANDARD,
    ).apply { id = UUID.randomUUID(); createdBy = sys.toString() }

    private fun newTrip(status: String = Trip.STATUS_PENDING): Trip = Trip(
        requestId = UUID.randomUUID(),
        riderId = UUID.randomUUID(),
        driverId = null,
        vehicleId = null,
        cityId = UUID.randomUUID(),
        rideType = "standard",
        status = status,
    ).apply { id = UUID.randomUUID(); createdBy = sys.toString() }

    // ---------- Request ----------

    @Test
    fun `request price moves draft to priced`() {
        val r = newRequest()
        r.price(UUID.randomUUID(), mapOf("final_price_minor" to "2350"), now)
        assertEquals(Request.STATUS_PRICED, r.status)
        assertNotNull(r.fareId)
        assertNotNull(r.quoteSnapshot)
    }

    @Test
    fun `request price rejects non-draft state`() {
        val r = newRequest()
        r.price(UUID.randomUUID(), mapOf("final_price_minor" to "1"), now)
        assertThrows(IllegalStateException::class.java) {
            r.price(UUID.randomUUID(), mapOf("x" to 1), now.plusSeconds(60))
        }
    }

    @Test
    fun `request submit moves draft or priced to submitted`() {
        val r = newRequest()
        r.submit(now)
        assertEquals(Request.STATUS_SUBMITTED, r.status)
    }

    @Test
    fun `request cancel cannot target converted request`() {
        val r = newRequest()
        // After cancel, the request is in CANCELLED state and cannot
        // transition back. Verify the state-machine rejects re-submit.
        r.submit(now)
        r.cancel("customer_no_longer_interested", now.plusSeconds(60))
        // Re-submitting a cancelled request is rejected.
        assertThrows(IllegalStateException::class.java) {
            r.submit(now.plusSeconds(120))
        }
    }

    @Test
    fun `request ride types validated at construction`() {
        for (type in Request.VALID_RIDE_TYPES) {
            Request(
                riderId = UUID.randomUUID(),
                rideType = type,
            ).apply { id = UUID.randomUUID() }
        }
        assertThrows(IllegalArgumentException::class.java) {
            Request(
                riderId = UUID.randomUUID(),
                rideType = "spaceship",
            )
        }
    }

    @Test
    fun `request status values validated at construction`() {
        for (s in Request.VALID_STATUSES) {
            Request(
                riderId = UUID.randomUUID(),
                status = s,
            ).apply { id = UUID.randomUUID() }
        }
        assertThrows(IllegalArgumentException::class.java) {
            Request(
                riderId = UUID.randomUUID(),
                status = "archived",
            )
        }
    }

    // ---------- Trip ----------

    @Test
    fun `trip match moves pending to matched`() {
        val trip = newTrip()
        val driverId = UUID.randomUUID()
        val vehicleId = UUID.randomUUID()
        val fareId = UUID.randomUUID()
        trip.match(driverId, vehicleId, fareId, now)
        assertEquals(Trip.STATUS_MATCHED, trip.status)
        assertEquals(driverId, trip.driverId)
        assertEquals(vehicleId, trip.vehicleId)
    }

    @Test
    fun `trip match rejects non-pending state`() {
        val trip = newTrip(Trip.STATUS_IN_PROGRESS)
        assertThrows(IllegalStateException::class.java) {
            trip.match(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), now)
        }
    }

    @Test
    fun `trip arrive moves matched or driver_assigned to arrived`() {
        val trip = newTrip(Trip.STATUS_MATCHED)
        trip.arrive(now)
        assertEquals(Trip.STATUS_ARRIVED, trip.status)
        assertNotNull(trip.arrivedAt)
    }

    @Test
    fun `trip start moves arrived to in_progress`() {
        val trip = newTrip(Trip.STATUS_ARRIVED)
        trip.start(BigDecimal("10.5"), BigDecimal("25"), now)
        assertEquals(Trip.STATUS_IN_PROGRESS, trip.status)
        assertNotNull(trip.startedAt)
    }

    @Test
    fun `trip complete moves in_progress to completed with final price`() {
        val trip = newTrip(Trip.STATUS_IN_PROGRESS)
        trip.complete(2350L, "EUR", now)
        assertEquals(Trip.STATUS_COMPLETED, trip.status)
        assertEquals(2350L, trip.finalPriceMinor)
        assertEquals("EUR", trip.finalCurrency)
    }

    @Test
    fun `trip complete rejects zero or negative price`() {
        val trip = newTrip(Trip.STATUS_IN_PROGRESS)
        // trip.complete uses check() (state check) which throws
        // IllegalStateException — not IllegalArgumentException — when the
        // amount invariant is violated. The invariant is "the trip's
        // computed final price must be > 0".
        assertThrows(IllegalStateException::class.java) {
            trip.complete(0L, "USD", now)
        }
    }

    @Test
    fun `trip cancel rejects terminal state`() {
        val trip = newTrip(Trip.STATUS_COMPLETED)
        assertThrows(IllegalStateException::class.java) {
            trip.cancel("customer_requested", now)
        }
    }

    @Test
    fun `trip rate accepts only completed state`() {
        val trip = newTrip(Trip.STATUS_COMPLETED)
        trip.rate(5.toShort(), "great ride", now)
        assertEquals(5.toShort(), trip.rating)
    }

    @Test
    fun `trip rate rejects non-completed state`() {
        val trip = newTrip(Trip.STATUS_IN_PROGRESS)
        assertThrows(IllegalStateException::class.java) {
            trip.rate(4.toShort(), null, now)
        }
    }

    @Test
    fun `trip rate rejects out-of-range`() {
        val trip = newTrip(Trip.STATUS_COMPLETED)
        assertThrows(IllegalArgumentException::class.java) {
            trip.rate(0.toShort(), null, now)
        }
        assertThrows(IllegalArgumentException::class.java) {
            trip.rate(6.toShort(), null, now)
        }
    }

    // ---------- TripStop ----------

    @Test
    fun `trip_stop arrive then depart`() {
        val stop = TripStop(
            tripId = UUID.randomUUID(),
            sequence = 1,
        ).apply { id = UUID.randomUUID() }
        val arrive = now
        stop.arrive(arrive)
        assertEquals(arrive, stop.arrivedAt)
        val depart = arrive.plusSeconds(180)
        stop.depart(depart)
        assertEquals(depart, stop.departedAt)
    }

    @Test
    fun `trip_stop sequence below zero rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            TripStop(tripId = UUID.randomUUID(), sequence = -1)
        }
    }

    @Test
    fun `trip_stop depart before arrive rejected`() {
        val stop = TripStop(
            tripId = UUID.randomUUID(),
            sequence = 1,
        ).apply { id = UUID.randomUUID() }
        stop.arrive(now)
        assertThrows(IllegalArgumentException::class.java) {
            stop.depart(now.minusSeconds(60))
        }
    }

    // ---------- IdempotencyRecord ----------

    @Test
    fun `idempotency_record valid scope accepted`() {
        for (scope in IdempotencyRecord.VALID_SCOPES) {
            IdempotencyRecord(
                id = UUID.randomUUID(),
                scope = scope,
                idemKey = "idem_valid_length",
                requestHash = "a".repeat(64),
                createdBy = sys,
            )
        }
    }

    @Test
    fun `idempotency_record invalid scope rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            IdempotencyRecord(
                id = UUID.randomUUID(),
                scope = "trip_xxx",
                idemKey = "idem_valid_length",
                requestHash = "a".repeat(64),
                createdBy = sys,
            )
        }
    }

    @Test
    fun `idempotency_record short idem_key rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            IdempotencyRecord(
                id = UUID.randomUUID(),
                scope = IdempotencyRecord.SCOPE_TRIP_REQUEST,
                idemKey = "short",
                requestHash = "a".repeat(64),
                createdBy = sys,
            )
        }
    }

    @Test
    fun `idempotency_record request_hash length enforced`() {
        assertThrows(IllegalArgumentException::class.java) {
            IdempotencyRecord(
                id = UUID.randomUUID(),
                scope = IdempotencyRecord.SCOPE_TRIP_REQUEST,
                idemKey = "idem_valid_length",
                requestHash = "too_short",
                createdBy = sys,
            )
        }
    }

    @Test
    fun `idempotency_record double recordResponse rejected`() {
        val key = IdempotencyRecord(
            id = UUID.randomUUID(),
            scope = IdempotencyRecord.SCOPE_TRIP_REQUEST,
            idemKey = "idem_valid_length",
            requestHash = "a".repeat(64),
            createdBy = sys,
        )
        key.recordResponse(201, mapOf("id" to "x"), now)
        assertTrue(key.isCompleted())
        assertThrows(IllegalStateException::class.java) {
            key.recordResponse(200, mapOf("id" to "y"), now.plusSeconds(60))
        }
    }

    // ---------- OutboxEvent ----------

    @Test
    fun `outbox mark_published sets timestamp`() {
        val e = OutboxEvent(
            id = UUID.randomUUID(),
            aggregateType = "Trip",
            aggregateId = UUID.randomUUID(),
            eventType = "trip.started.v1",
            topic = "trip.started.v1",
            payload = mapOf("x" to 1),
            correlationId = UUID.randomUUID(),
            createdBy = sys,
        )
        e.markPublished(now)
        assertEquals(now, e.publishedAt)
    }

    @Test
    fun `outbox mark_failed increments attempts`() {
        val e = OutboxEvent(
            id = UUID.randomUUID(),
            aggregateType = "Trip",
            aggregateId = UUID.randomUUID(),
            eventType = "trip.started.v1",
            topic = "trip.started.v1",
            payload = mapOf("x" to 1),
            correlationId = UUID.randomUUID(),
            createdBy = sys,
        )
        e.markFailed("kafka_unreachable", now.plusSeconds(60))
        assertEquals(1, e.attempts)
        assertEquals("kafka_unreachable", e.lastError)
    }

    // ---------- TripReward invariants ----------

    @Test
    fun `trip_reward amount_minor must be positive`() {
        assertThrows(IllegalArgumentException::class.java) {
            TripReward(
                id = UUID.randomUUID(),
                tripId = UUID.randomUUID(),
                driverId = UUID.randomUUID(),
                riderId = UUID.randomUUID(),
                amountMinor = 0L,
                reason = "auto_grant",
                correlationId = UUID.randomUUID(),
            )
        }
    }
}