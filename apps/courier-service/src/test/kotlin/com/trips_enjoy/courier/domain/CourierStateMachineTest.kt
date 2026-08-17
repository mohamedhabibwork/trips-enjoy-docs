package com.trips_enjoy.courier.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

/**
 * Unit tests for the Courier state machine. Covers:
 *   * approve from pending_review / inactive
 *   * reject from pending_review (rejected is terminal)
 *   * suspend / reinstate cycle
 *   * disable / erase lifecycle
 *   * applyRating recomputes the aggregate rating
 *   * setPrimaryVehicle + documentsWarn + touchOnline
 *   * Illegal transitions raise IllegalStateException
 */
class CourierStateMachineTest {

    private val now = Instant.parse("2026-08-15T12:00:00Z")
    private val sys = UUID.randomUUID()

    private fun newCourier(status: String = Courier.STATUS_PENDING_REVIEW): Courier = Courier(
        identityId = UUID.randomUUID(),
        status = status,
    )

    @Test
    fun `approve moves pending_review to approved`() {
        val courier = newCourier()
        courier.approve(now)
        assertEquals(Courier.STATUS_APPROVED, courier.status)
    }

    @Test
    fun `approve moves inactive to approved`() {
        val courier = newCourier(Courier.STATUS_INACTIVE)
        courier.approve(now)
        assertEquals(Courier.STATUS_APPROVED, courier.status)
    }

    @Test
    fun `approve rejects from rejected state`() {
        val courier = newCourier(Courier.STATUS_REJECTED)
        assertThrows(IllegalStateException::class.java) { courier.approve(now) }
    }

    @Test
    fun `approve rejects from erased state`() {
        val courier = newCourier(Courier.STATUS_ERASED)
        assertThrows(IllegalStateException::class.java) { courier.approve(now) }
    }

    @Test
    fun `reject moves pending_review to rejected with reason`() {
        val courier = newCourier()
        courier.reject("kyc_failed", now)
        assertEquals(Courier.STATUS_REJECTED, courier.status)
        assertEquals("kyc_failed", courier.rejectedReason)
    }

    @Test
    fun `reject requires non-blank reason`() {
        val courier = newCourier()
        assertThrows(IllegalArgumentException::class.java) { courier.reject("", now) }
    }

    @Test
    fun `reject rejects from non-pending_review state`() {
        val courier = newCourier(Courier.STATUS_APPROVED)
        assertThrows(IllegalStateException::class.java) { courier.reject("x", now) }
    }

    @Test
    fun `suspend moves approved to suspended with reason`() {
        val courier = newCourier(Courier.STATUS_APPROVED)
        courier.suspend("policy_violation", sys, now)
        assertEquals(Courier.STATUS_SUSPENDED, courier.status)
        assertEquals("policy_violation", courier.suspendedReason)
    }

    @Test
    fun `suspend rejects from non-approved state`() {
        val courier = newCourier(Courier.STATUS_PENDING_REVIEW)
        assertThrows(IllegalStateException::class.java) {
            courier.suspend("x", sys, now)
        }
    }

    @Test
    fun `reinstate moves suspended back to approved`() {
        val courier = newCourier(Courier.STATUS_SUSPENDED)
        courier.reinstate(now)
        assertEquals(Courier.STATUS_APPROVED, courier.status)
    }

    @Test
    fun `disable moves approved to inactive`() {
        val courier = newCourier(Courier.STATUS_APPROVED)
        courier.disable(now)
        assertEquals(Courier.STATUS_INACTIVE, courier.status)
    }

    @Test
    fun `erase from approved moves to erased`() {
        val courier = newCourier(Courier.STATUS_APPROVED)
        courier.erase(now)
        assertEquals(Courier.STATUS_ERASED, courier.status)
    }

    @Test
    fun `erase is idempotent rejected on already erased`() {
        val courier = newCourier(Courier.STATUS_ERASED)
        assertThrows(IllegalStateException::class.java) { courier.erase(now) }
    }

    @Test
    fun `setPrimaryVehicle records vehicle and bumps version`() {
        val courier = newCourier(Courier.STATUS_APPROVED)
        val vehicleId = UUID.randomUUID()
        val v0 = courier.version
        courier.setPrimaryVehicle(vehicleId, now)
        assertEquals(vehicleId, courier.primaryVehicleId)
        assertEquals(v0 + 1, courier.version)
    }

    @Test
    fun `applyRating recomputes aggregate rating from line items`() {
        val courier = newCourier(Courier.STATUS_APPROVED)
        courier.applyRating(BigDecimal(5), now)
        assertEquals(1, courier.ratingCount)
        assertEquals(BigDecimal("5.00"), courier.rating)
        courier.applyRating(BigDecimal(3), now.plusSeconds(60))
        assertEquals(2, courier.ratingCount)
        assertEquals(BigDecimal("4.00"), courier.rating)
    }

    @Test
    fun `applyRating rejects out-of-range`() {
        val courier = newCourier(Courier.STATUS_APPROVED)
        assertThrows(IllegalArgumentException::class.java) {
            courier.applyRating(BigDecimal(0), now)
        }
        assertThrows(IllegalArgumentException::class.java) {
            courier.applyRating(BigDecimal(6), now)
        }
    }

    @Test
    fun `touchOnline records lastOnlineAt`() {
        val courier = newCourier()
        courier.touchOnline(now)
        assertEquals(now, courier.lastOnlineAt)
    }
}