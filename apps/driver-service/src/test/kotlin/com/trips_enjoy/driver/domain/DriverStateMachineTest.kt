package com.trips_enjoy.driver.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

/**
 * Unit tests for the Driver state machine. Covers:
 *   * approve from pending_review / inactive
 *   * reject from pending_review (rejected is terminal)
 *   * suspend / reinstate cycle
 *   * disable / erase lifecycle
 *   * applyRating recomputes the aggregate rating
 *   * setPrimaryVehicle + documentsWarn + touchOnline
 *   * Illegal transitions raise IllegalStateException
 */
class DriverStateMachineTest {

    private val now = Instant.parse("2026-08-15T12:00:00Z")
    private val sys = UUID.randomUUID()

    private fun newDriver(status: String = Driver.STATUS_PENDING_REVIEW): Driver = Driver(
        identityId = UUID.randomUUID(),
        status = status,
    )

    @Test
    fun `approve moves pending_review to approved`() {
        val driver = newDriver()
        driver.approve(now)
        assertEquals(Driver.STATUS_APPROVED, driver.status)
    }

    @Test
    fun `approve moves inactive to approved`() {
        val driver = newDriver(Driver.STATUS_INACTIVE)
        driver.approve(now)
        assertEquals(Driver.STATUS_APPROVED, driver.status)
    }

    @Test
    fun `approve rejects from rejected state`() {
        val driver = newDriver(Driver.STATUS_REJECTED)
        assertThrows(IllegalStateException::class.java) { driver.approve(now) }
    }

    @Test
    fun `approve rejects from erased state`() {
        val driver = newDriver(Driver.STATUS_ERASED)
        assertThrows(IllegalStateException::class.java) { driver.approve(now) }
    }

    @Test
    fun `reject moves pending_review to rejected with reason`() {
        val driver = newDriver()
        driver.reject("kyc_failed", now)
        assertEquals(Driver.STATUS_REJECTED, driver.status)
        assertEquals("kyc_failed", driver.rejectedReason)
    }

    @Test
    fun `reject requires non-blank reason`() {
        val driver = newDriver()
        assertThrows(IllegalArgumentException::class.java) { driver.reject("", now) }
    }

    @Test
    fun `reject rejects from non-pending_review state`() {
        val driver = newDriver(Driver.STATUS_APPROVED)
        assertThrows(IllegalStateException::class.java) { driver.reject("x", now) }
    }

    @Test
    fun `suspend moves approved to suspended with reason`() {
        val driver = newDriver(Driver.STATUS_APPROVED)
        driver.suspend("policy_violation", sys, now)
        assertEquals(Driver.STATUS_SUSPENDED, driver.status)
        assertEquals("policy_violation", driver.suspendedReason)
        assertEquals(now, driver.suspendedAt)
        assertEquals(sys, driver.suspendedBy)
    }

    @Test
    fun `suspend rejects from non-approved state`() {
        val driver = newDriver(Driver.STATUS_PENDING_REVIEW)
        assertThrows(IllegalStateException::class.java) {
            driver.suspend("x", sys, now)
        }
    }

    @Test
    fun `reinstate moves suspended back to approved`() {
        val driver = newDriver(Driver.STATUS_SUSPENDED)
        driver.reinstate(now)
        assertEquals(Driver.STATUS_APPROVED, driver.status)
        assertEquals(null, driver.suspendedReason)
        assertEquals(null, driver.suspendedAt)
    }

    @Test
    fun `reinstate rejects from non-suspended state`() {
        val driver = newDriver(Driver.STATUS_APPROVED)
        assertThrows(IllegalStateException::class.java) { driver.reinstate(now) }
    }

    @Test
    fun `disable moves approved to inactive`() {
        val driver = newDriver(Driver.STATUS_APPROVED)
        driver.disable(now)
        assertEquals(Driver.STATUS_INACTIVE, driver.status)
    }

    @Test
    fun `disable rejects from pending_review state`() {
        val driver = newDriver(Driver.STATUS_PENDING_REVIEW)
        assertThrows(IllegalStateException::class.java) { driver.disable(now) }
    }

    @Test
    fun `erase from approved moves to erased`() {
        val driver = newDriver(Driver.STATUS_APPROVED)
        driver.erase(now)
        assertEquals(Driver.STATUS_ERASED, driver.status)
    }

    @Test
    fun `erase is idempotent rejected on already erased`() {
        val driver = newDriver(Driver.STATUS_ERASED)
        assertThrows(IllegalStateException::class.java) { driver.erase(now) }
    }

    @Test
    fun `setPrimaryVehicle records vehicle reference`() {
        val driver = newDriver(Driver.STATUS_APPROVED)
        val vehicleId = UUID.randomUUID()
        driver.setPrimaryVehicle(vehicleId, now)
        assertEquals(vehicleId, driver.primaryVehicleId)
        // Phase C: `version` is managed by Hibernate's @Version (BaseEntity);
        // unit-level state-machine methods no longer bump it in memory.
        // The optimistic-lock counter advance is exercised at the JPA save
        // boundary in DriverWriteServiceIntegrationTest.
    }

    @Test
    fun `setPrimaryVehicle rejects on erased driver`() {
        val driver = newDriver(Driver.STATUS_ERASED)
        assertThrows(IllegalStateException::class.java) {
            driver.setPrimaryVehicle(UUID.randomUUID(), now)
        }
    }

    @Test
    fun `applyRating recomputes aggregate rating from line items`() {
        val driver = newDriver(Driver.STATUS_APPROVED)
        driver.applyRating(BigDecimal(5), now)
        assertEquals(1, driver.ratingCount)
        assertEquals(BigDecimal("5.00"), driver.rating)
        driver.applyRating(BigDecimal(3), now.plusSeconds(60))
        assertEquals(2, driver.ratingCount)
        // (5 + 3) / 2 = 4.0
        assertEquals(BigDecimal("4.00"), driver.rating)
    }

    @Test
    fun `applyRating rejects out-of-range`() {
        val driver = newDriver(Driver.STATUS_APPROVED)
        assertThrows(IllegalArgumentException::class.java) {
            driver.applyRating(BigDecimal(0), now)
        }
        assertThrows(IllegalArgumentException::class.java) {
            driver.applyRating(BigDecimal(6), now)
        }
    }

    @Test
    fun `setDocumentsWarn flips the flag`() {
        val driver = newDriver()
        driver.setDocumentsWarn(true, now)
        assertEquals(true, driver.documentsWarn)
        driver.setDocumentsWarn(false, now.plusSeconds(60))
        assertEquals(false, driver.documentsWarn)
    }

    @Test
    fun `touchOnline records lastOnlineAt`() {
        val driver = newDriver()
        driver.touchOnline(now)
        assertEquals(now, driver.lastOnlineAt)
    }
}