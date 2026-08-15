package com.trips_enjoy.courier.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/**
 * Unit tests for CourierDocument state machine + CourierCityEligibility
 * lifecycle + IdempotencyKey validation. Mirrors the driver-service
 * `DriverDocumentAndIdempotencyTest` pattern. The courier document
 * type list adds `id` vs driver-service.
 */
class CourierDocumentAndIdempotencyTest {

    private val sys = UUID.randomUUID()
    private val validHash = "a".repeat(64)

    private fun newDoc(status: String = CourierDocument.STATUS_PENDING): CourierDocument =
        CourierDocument(
            id = UUID.randomUUID(),
            courierId = UUID.randomUUID(),
            type = CourierDocument.TYPE_LICENSE,
            fileId = UUID.randomUUID(),
            status = status,
            createdBy = sys,
            updatedBy = sys,
        )

    @Test
    fun `verify moves pending document to verified`() {
        val doc = newDoc()
        val verId = UUID.randomUUID()
        doc.verify(verId, Instant.now())
        assertEquals(CourierDocument.STATUS_VERIFIED, doc.status)
        assertEquals(verId, doc.verificationId)
    }

    @Test
    fun `verify rejects from verified state`() {
        val doc = newDoc(CourierDocument.STATUS_VERIFIED)
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
    fun `expire moves verified to expired`() {
        val doc = newDoc(CourierDocument.STATUS_VERIFIED)
        doc.expire(Instant.now())
        assertEquals(CourierDocument.STATUS_EXPIRED, doc.status)
    }

    @Test
    fun `invalid document type rejected at construction`() {
        assertThrows(IllegalArgumentException::class.java) {
            CourierDocument(
                id = UUID.randomUUID(),
                courierId = UUID.randomUUID(),
                type = "passport",
                fileId = UUID.randomUUID(),
                createdBy = sys,
                updatedBy = sys,
            )
        }
    }

    @Test
    fun `valid courier document types include id`() {
        for (type in listOf(
            CourierDocument.TYPE_ID,
            CourierDocument.TYPE_LICENSE,
            CourierDocument.TYPE_VEHICLE_REG,
            CourierDocument.TYPE_INSURANCE,
            CourierDocument.TYPE_SELFIE,
            CourierDocument.TYPE_BACKGROUND_CHECK,
            CourierDocument.TYPE_MEDICAL,
            CourierDocument.TYPE_PERMIT,
        )) {
            val doc = CourierDocument(
                id = UUID.randomUUID(),
                courierId = UUID.randomUUID(),
                type = type,
                fileId = UUID.randomUUID(),
                createdBy = sys,
                updatedBy = sys,
            )
            assertEquals(type, doc.type)
        }
    }

    private fun newEligibility(): CourierCityEligibility = CourierCityEligibility(
        id = UUID.randomUUID(),
        courierId = UUID.randomUUID(),
        cityId = UUID.randomUUID(),
        grantedBy = sys,
        createdBy = sys,
        updatedBy = sys,
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

    @Test
    fun `valid idempotency scope accepted`() {
        for (scope in listOf(
            IdempotencyKey.SCOPE_COURIER_CREATE,
            IdempotencyKey.SCOPE_COURIER_APPROVE,
            IdempotencyKey.SCOPE_COURIER_REJECT,
            IdempotencyKey.SCOPE_COURIER_SUSPEND,
            IdempotencyKey.SCOPE_DOCUMENT_ADD,
            IdempotencyKey.SCOPE_ELIGIBILITY_GRANT,
            IdempotencyKey.SCOPE_SHIFT_SCHEDULE,
            IdempotencyKey.SCOPE_SHIFT_ACTIVATE,
            IdempotencyKey.SCOPE_SHIFT_COMPLETE,
            IdempotencyKey.SCOPE_SHIFT_CANCEL,
        )) {
            val key = IdempotencyKey(
                id = UUID.randomUUID(),
                scope = scope,
                idemKey = "idem_${UUID.randomUUID()}",
                requestHash = validHash,
                createdBy = sys,
            )
            assertEquals(scope, key.scope)
        }
    }

    @Test
    fun `invalid idempotency scope rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            IdempotencyKey(
                id = UUID.randomUUID(),
                scope = "trip_cancel",
                idemKey = "idem_valid_length",
                requestHash = validHash,
                createdBy = sys,
            )
        }
    }

    @Test
    fun `short idem_key rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            IdempotencyKey(
                id = UUID.randomUUID(),
                scope = IdempotencyKey.SCOPE_COURIER_CREATE,
                idemKey = "short",
                requestHash = validHash,
                createdBy = sys,
            )
        }
    }

    @Test
    fun `recordResponse marks completed`() {
        val key = IdempotencyKey(
            id = UUID.randomUUID(),
            scope = IdempotencyKey.SCOPE_COURIER_CREATE,
            idemKey = "idem_valid_length",
            requestHash = validHash,
            createdBy = sys,
        )
        assertFalse(key.isCompleted())
        key.recordResponse(201, mapOf("id" to "cou_123"), Instant.now())
        assertTrue(key.isCompleted())
        assertEquals(201, key.responseStatus)
    }

    @Test
    fun `second recordResponse is rejected`() {
        val key = IdempotencyKey(
            id = UUID.randomUUID(),
            scope = IdempotencyKey.SCOPE_COURIER_CREATE,
            idemKey = "idem_valid_length",
            requestHash = validHash,
            createdBy = sys,
        )
        key.recordResponse(201, mapOf("id" to "cou_123"), Instant.now())
        assertThrows(IllegalStateException::class.java) {
            key.recordResponse(200, mapOf("id" to "cou_456"), Instant.now().plusSeconds(1))
        }
    }

    @Test
    fun `valid actions for CourierAuditLog`() {
        for (action in listOf(
            CourierAuditLog.ACTION_CREATED,
            CourierAuditLog.ACTION_APPROVED,
            CourierAuditLog.ACTION_REJECTED,
            CourierAuditLog.ACTION_SUSPENDED,
            CourierAuditLog.ACTION_REINSTATED,
            CourierAuditLog.ACTION_DISABLED,
            CourierAuditLog.ACTION_ERASED,
            CourierAuditLog.ACTION_DOCUMENT_ADDED,
            CourierAuditLog.ACTION_DOCUMENT_VERIFIED,
            CourierAuditLog.ACTION_CITY_GRANTED,
            CourierAuditLog.ACTION_CITY_REVOKED,
            CourierAuditLog.ACTION_RATING_ADDED,
            CourierAuditLog.ACTION_PRIMARY_VEHICLE_CHANGED,
            CourierAuditLog.ACTION_PROFILE_UPDATED,
            CourierAuditLog.ACTION_SHIFT_SCHEDULED,
            CourierAuditLog.ACTION_SHIFT_ACTIVATED,
            CourierAuditLog.ACTION_SHIFT_COMPLETED,
            CourierAuditLog.ACTION_SHIFT_CANCELLED,
        )) {
            val audit = CourierAuditLog(
                id = UUID.randomUUID(),
                courierId = UUID.randomUUID(),
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
            CourierAuditLog(
                id = UUID.randomUUID(),
                courierId = UUID.randomUUID(),
                action = "wrong_action",
                actorId = sys,
                correlationId = UUID.randomUUID(),
            )
        }
    }
}