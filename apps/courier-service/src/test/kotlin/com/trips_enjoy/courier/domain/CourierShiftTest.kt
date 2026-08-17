package com.trips_enjoy.courier.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/**
 * Unit tests for the CourierShift state machine. Mirrors the
 * `DriverStateMachineTest` pattern but for shifts:
 *   * scheduled → active → completed
 *              ↘ cancelled (from any state)
 *
 * The unique partial index on `(courier_id) WHERE status = 'active'`
 * enforces "at most one active shift per courier" at the DB level.
 */
class CourierShiftTest {

    private val now = Instant.parse("2026-08-15T12:00:00Z")
    private val later = now.plusSeconds(3600)
    private val laterStill = later.plusSeconds(3600)
    private val sys = UUID.randomUUID()

    private fun newShift(
        status: String = CourierShift.STATUS_SCHEDULED,
        startAt: Instant = now,
        endAt: Instant = later,
    ): CourierShift = CourierShift(
        courierId = UUID.randomUUID(),
        startAt = startAt,
        endAt = endAt,
        status = status,
    )

    @Test
    fun `scheduled shifts construct with end_at after start_at`() {
        val shift = newShift()
        assertEquals(CourierShift.STATUS_SCHEDULED, shift.status)
    }

    @Test
    fun `shift construction rejects end_at before start_at`() {
        assertThrows(IllegalArgumentException::class.java) {
            CourierShift(
                courierId = UUID.randomUUID(),
                startAt = later,
                endAt = now,
            )
        }
    }

    @Test
    fun `shift construction rejects invalid status`() {
        assertThrows(IllegalArgumentException::class.java) {
            CourierShift(
                courierId = UUID.randomUUID(),
                startAt = now,
                endAt = later,
                status = "paused",
            )
        }
    }

    @Test
    fun `activate moves scheduled to active and records actual_start_at`() {
        val shift = newShift()
        val actualStart = now.plusSeconds(30)
        shift.activate(actualStart)
        assertEquals(CourierShift.STATUS_ACTIVE, shift.status)
        assertEquals(actualStart, shift.actualStartAt)
    }

    @Test
    fun `activate rejects from already active state`() {
        val shift = newShift(CourierShift.STATUS_ACTIVE)
        assertThrows(IllegalStateException::class.java) {
            shift.activate(now.plusSeconds(60))
        }
    }

    @Test
    fun `activate rejects actual_start before planned start`() {
        val shift = newShift()
        assertThrows(IllegalArgumentException::class.java) {
            shift.activate(now.minusSeconds(60))
        }
    }

    @Test
    fun `complete moves active to completed and records actual_end_at`() {
        val shift = newShift(CourierShift.STATUS_ACTIVE).apply {
            actualStartAt = now.plusSeconds(30)
        }
        shift.complete(laterStill)
        assertEquals(CourierShift.STATUS_COMPLETED, shift.status)
        assertEquals(laterStill, shift.actualEndAt)
    }

    @Test
    fun `complete rejects from scheduled state`() {
        val shift = newShift()
        assertThrows(IllegalStateException::class.java) {
            shift.complete(later)
        }
    }

    @Test
    fun `cancel moves scheduled to cancelled with reason`() {
        val shift = newShift()
        shift.cancel("shift_no_longer_needed", laterStill)
        assertEquals(CourierShift.STATUS_CANCELLED, shift.status)
        assertEquals("shift_no_longer_needed", shift.cancelledReason)
        assertNotNull(shift.actualEndAt)
    }

    @Test
    fun `cancel moves active to cancelled`() {
        val shift = newShift(CourierShift.STATUS_ACTIVE).apply {
            actualStartAt = now.plusSeconds(30)
        }
        shift.cancel("rider_cancelled", laterStill)
        assertEquals(CourierShift.STATUS_CANCELLED, shift.status)
    }

    @Test
    fun `cancel rejects from completed state`() {
        val shift = newShift(CourierShift.STATUS_COMPLETED)
        assertThrows(IllegalStateException::class.java) {
            shift.cancel("x", laterStill)
        }
    }

    @Test
    fun `cancel requires non-blank reason`() {
        val shift = newShift()
        assertThrows(IllegalArgumentException::class.java) {
            shift.cancel("", laterStill)
        }
    }

    @Test
    fun `complete rejects actual_end before actual_start`() {
        val shift = newShift(CourierShift.STATUS_ACTIVE).apply {
            actualStartAt = later
        }
        assertThrows(IllegalArgumentException::class.java) {
            shift.complete(now.plusSeconds(60))
        }
    }

    @Test
    fun `full shift lifecycle scheduled to active to completed`() {
        val shift = newShift()
        shift.activate(now.plusSeconds(30))
        assertEquals(CourierShift.STATUS_ACTIVE, shift.status)
        shift.complete(laterStill)
        assertEquals(CourierShift.STATUS_COMPLETED, shift.status)
    }
}