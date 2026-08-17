package com.trips_enjoy.driver.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/**
 * Unit tests for DriverDocument state machine + DriverCityEligibility
 * lifecycle + IdempotencyKey validation. Mirrors the customer-service
 * `IdempotencyServiceTest` pattern.
 */
class DriverDocumentAndIdempotencyTest {

    private val sys = UUID.randomUUID()
    private val validHash = "a".repeat(64)

    private fun newDoc(status: String = DriverDocument.STATUS_PENDING): DriverDocument =
        DriverDocument(
            driverId = UUID.randomUUID(),
            type = DriverDocument.TYPE_LICENSE,
            fileId = UUID.randomUUID(),
            status = status,
        )

    @Test
    fun `verify moves pending document to verified`() {
        val doc = newDoc()
        val verId = UUID.randomUUID()
        doc.verify(verId, Instant.now())
        assertEquals(DriverDocument.STATUS_VERIFIED, doc.status)
        assertEquals(verId, doc.verificationId)
    }

    @Test
    fun `verify rejects from verified state`() {
        val doc = newDoc(DriverDocument.STATUS_VERIFIED)
        assertThrows(IllegalStateException::class.java) {
            doc.verify(UUID.randomUUID(), Instant.now())
        }
    }

    @Test
    fun `reject requires non-blank reason`() {
        val doc = newDoc()
        assertThrows(IllegalArgumentException::class.java) {
            doc.reject("", Instant.now())
        }
    }

    @Test
    fun `reject moves pending to rejected with reason`() {
        val doc = newDoc()
        doc.reject("blurry", Instant.now())
        assertEquals(DriverDocument.STATUS_REJECTED, doc.status)
        assertEquals("blurry", doc.rejectedReason)
    }

    @Test
    fun `expire moves verified to expired`() {
        val doc = newDoc(DriverDocument.STATUS_VERIFIED)
        doc.expire(Instant.now())
        assertEquals(DriverDocument.STATUS_EXPIRED, doc.status)
    }

    @Test
    fun `expire rejects from pending state`() {
        val doc = newDoc()
        assertThrows(IllegalStateException::class.java) { doc.expire(Instant.now()) }
    }

    @Test
    fun `invalid document type rejected at construction`() {
        assertThrows(IllegalArgumentException::class.java) {
            DriverDocument(
                driverId = UUID.randomUUID(),
                type = "passport",
                fileId = UUID.randomUUID(),
            )
        }
    }

    @Test
    fun `invalid document status rejected at construction`() {
        assertThrows(IllegalArgumentException::class.java) {
            DriverDocument(
                driverId = UUID.randomUUID(),
                type = DriverDocument.TYPE_LICENSE,
                fileId = UUID.randomUUID(),
                status = "archived",
            )
        }
    }

    private fun newEligibility(): DriverCityEligibility = DriverCityEligibility(
        driverId = UUID.randomUUID(),
        cityId = UUID.randomUUID(),
        grantedBy = sys,
    )

    @Test
    fun `eligibility revoke records revokedAt`() {
        val eligibility = newEligibility()
        eligibility.revoke(sys, Instant.now())
        assertNotNull(eligibility.revokedAt)
        assertEquals(sys, eligibility.revokedBy)
    }

    @Test
    fun `eligibility revoke is rejected when already revoked`() {
        val eligibility = newEligibility()
        eligibility.revoke(sys, Instant.now())
        assertThrows(IllegalStateException::class.java) {
            eligibility.revoke(sys, Instant.now().plusSeconds(1))
        }
    }

    @Test
    fun `eligibility isActive returns true when not revoked`() {
        val eligibility = newEligibility()
        assertTrue(eligibility.isActive())
    }

    @Test
    fun `eligibility isActive returns false after revoke`() {
        val eligibility = newEligibility()
        eligibility.revoke(sys, Instant.now())
        assertFalse(eligibility.isActive())
    }

    private fun newIdem(scope: String = IdempotencyKey.SCOPE_DRIVER_CREATE): IdempotencyKey =
        IdempotencyKey(
            id = UUID.randomUUID(),
            scope = scope,
            idemKey = "idem_${UUID.randomUUID()}",
            requestHash = validHash,
            createdBy = sys,
        )

    @Test
    fun `valid idempotency scope accepted`() {
        for (scope in listOf(
            IdempotencyKey.SCOPE_DRIVER_CREATE,
            IdempotencyKey.SCOPE_DRIVER_APPROVE,
            IdempotencyKey.SCOPE_DRIVER_REJECT,
            IdempotencyKey.SCOPE_DRIVER_SUSPEND,
            IdempotencyKey.SCOPE_DOCUMENT_ADD,
            IdempotencyKey.SCOPE_ELIGIBILITY_GRANT,
        )) {
            val key = newIdem(scope)
            assertEquals(scope, key.scope)
        }
    }

    @Test
    fun `invalid idempotency scope rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            newIdem(scope = "trip_cancel")
        }
    }

    @Test
    fun `short idem_key rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            IdempotencyKey(
                id = UUID.randomUUID(),
                scope = IdempotencyKey.SCOPE_DRIVER_CREATE,
                idemKey = "short",
                requestHash = validHash,
                createdBy = sys,
            )
        }
    }

    @Test
    fun `request_hash length must be 64`() {
        assertThrows(IllegalArgumentException::class.java) {
            IdempotencyKey(
                id = UUID.randomUUID(),
                scope = IdempotencyKey.SCOPE_DRIVER_CREATE,
                idemKey = "idem_valid_length",
                requestHash = "too_short",
                createdBy = sys,
            )
        }
    }

    @Test
    fun `recordResponse marks completed`() {
        val key = newIdem()
        assertFalse(key.isCompleted())
        key.recordResponse(201, mapOf("id" to "drv_123"), Instant.now())
        assertTrue(key.isCompleted())
        assertEquals(201, key.responseStatus)
    }

    @Test
    fun `second recordResponse is rejected`() {
        val key = newIdem()
        key.recordResponse(201, mapOf("id" to "drv_123"), Instant.now())
        assertThrows(IllegalStateException::class.java) {
            key.recordResponse(200, mapOf("id" to "drv_456"), Instant.now().plusSeconds(1))
        }
    }

    @Test
    fun `valid actions for DriverAuditLog`() {
        for (action in listOf(
            DriverAuditLog.ACTION_CREATED,
            DriverAuditLog.ACTION_APPROVED,
            DriverAuditLog.ACTION_REJECTED,
            DriverAuditLog.ACTION_SUSPENDED,
            DriverAuditLog.ACTION_REINSTATED,
            DriverAuditLog.ACTION_DISABLED,
            DriverAuditLog.ACTION_ERASED,
            DriverAuditLog.ACTION_DOCUMENT_ADDED,
            DriverAuditLog.ACTION_DOCUMENT_VERIFIED,
            DriverAuditLog.ACTION_CITY_GRANTED,
            DriverAuditLog.ACTION_CITY_REVOKED,
            DriverAuditLog.ACTION_RATING_ADDED,
            DriverAuditLog.ACTION_PRIMARY_VEHICLE_CHANGED,
            DriverAuditLog.ACTION_PROFILE_UPDATED,
        )) {
            val audit = DriverAuditLog(
                id = UUID.randomUUID(),
                driverId = UUID.randomUUID(),
                action = action,
                actorId = sys,
                correlationId = UUID.randomUUID(),
            )
            assertEquals(action, audit.action)
        }
    }

    @Test
    fun `invalid audit action rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            DriverAuditLog(
                id = UUID.randomUUID(),
                driverId = UUID.randomUUID(),
                action = "wrong_action",
                actorId = sys,
                correlationId = UUID.randomUUID(),
            )
        }
    }
}