package com.trips_enjoy.payment.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/**
 * Unit tests for the IdempotencyKey aggregate. Covers:
 *   * valid scopes are accepted
 *   * invalid scope is rejected
 *   * idem_key length validation (8..200)
 *   * request_hash must be a SHA-256 hex (64 chars)
 *   * recordResponse marks the row as completed
 *   * second recordResponse is rejected
 *   * isCompleted reflects completed_at presence
 */
class IdempotencyTest {

    private val sys = UUID.randomUUID()
    private val validHash = "a".repeat(64)

    private fun newKey(scope: String = IdempotencyKey.SCOPE_PAYMENT_INTENT): IdempotencyKey =
        IdempotencyKey(
            id = UUID.randomUUID(),
            scope = scope,
            idemKey = "idem_${UUID.randomUUID()}",
            requestHash = validHash,
            createdBy = sys,
        )

    @Test
    fun `valid scope is accepted`() {
        for (scope in listOf(
            IdempotencyKey.SCOPE_PAYMENT_INTENT,
            IdempotencyKey.SCOPE_PAYMENT_CAPTURE,
            IdempotencyKey.SCOPE_PAYMENT_VOID,
            IdempotencyKey.SCOPE_PAYMENT_REFUND,
            IdempotencyKey.SCOPE_WALLET_TOPUP,
            IdempotencyKey.SCOPE_WALLET_DEBIT,
        )) {
            val key = newKey(scope)
            assertEquals(scope, key.scope)
        }
    }

    @Test
    fun `invalid scope is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            newKey(scope = "trip_cancel")
        }
    }

    @Test
    fun `short idem_key is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            IdempotencyKey(
                id = UUID.randomUUID(),
                scope = IdempotencyKey.SCOPE_PAYMENT_INTENT,
                idemKey = "short",
                requestHash = validHash,
                createdBy = sys,
            )
        }
    }

    @Test
    fun `long idem_key is rejected`() {
        val longKey = "x".repeat(201)
        assertThrows(IllegalArgumentException::class.java) {
            IdempotencyKey(
                id = UUID.randomUUID(),
                scope = IdempotencyKey.SCOPE_PAYMENT_INTENT,
                idemKey = longKey,
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
                scope = IdempotencyKey.SCOPE_PAYMENT_INTENT,
                idemKey = "idem_valid_length",
                requestHash = "too_short",
                createdBy = sys,
            )
        }
    }

    @Test
    fun `recordResponse marks completed`() {
        val key = newKey()
        assertFalse(key.isCompleted())
        key.recordResponse(201, mapOf("id" to "pi_123"), Instant.now())
        assertTrue(key.isCompleted())
        assertEquals(201, key.responseStatus)
        assertNotNull(key.responseBody)
    }

    @Test
    fun `second recordResponse is rejected`() {
        val key = newKey()
        key.recordResponse(201, mapOf("id" to "pi_123"), Instant.now())
        assertThrows(IllegalStateException::class.java) {
            key.recordResponse(200, mapOf("id" to "pi_456"), Instant.now().plusSeconds(1))
        }
    }
}