package com.trips_enjoy.restaurant.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/**
 * Unit tests for IdempotencyKey + RestaurantAuditLog + RestaurantCuisine +
 * RestaurantTag validation. Mirrors the customer-service / driver-service /
 * payment-service IdempotencyServiceTest pattern.
 */
class RestaurantIdempotencyTest {

    private val sys = UUID.randomUUID()
    private val validHash = "a".repeat(64)

    private fun newIdem(scope: String = IdempotencyKey.SCOPE_RESTAURANT_CREATE): IdempotencyKey =
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
            IdempotencyKey.SCOPE_RESTAURANT_CREATE,
            IdempotencyKey.SCOPE_RESTAURANT_UPDATE,
            IdempotencyKey.SCOPE_RESTAURANT_SUBMIT,
            IdempotencyKey.SCOPE_RESTAURANT_APPROVE,
            IdempotencyKey.SCOPE_RESTAURANT_REJECT,
            IdempotencyKey.SCOPE_RESTAURANT_ONLINE,
            IdempotencyKey.SCOPE_RESTAURANT_OFFLINE,
            IdempotencyKey.SCOPE_RESTAURANT_SUSPEND,
            IdempotencyKey.SCOPE_RESTAURANT_REINSTATE,
            IdempotencyKey.SCOPE_RESTAURANT_CLOSE,
            IdempotencyKey.SCOPE_RESTAURANT_RESUBMIT,
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
                scope = IdempotencyKey.SCOPE_RESTAURANT_CREATE,
                idemKey = "short",
                requestHash = validHash,
                createdBy = sys,
            )
        }
    }

    @Test
    fun `long idem_key rejected`() {
        val longKey = "x".repeat(201)
        assertThrows(IllegalArgumentException::class.java) {
            IdempotencyKey(
                id = UUID.randomUUID(),
                scope = IdempotencyKey.SCOPE_RESTAURANT_CREATE,
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
                scope = IdempotencyKey.SCOPE_RESTAURANT_CREATE,
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
        key.recordResponse(201, mapOf("id" to "res_123"), Instant.now())
        assertTrue(key.isCompleted())
        assertEquals(201, key.responseStatus)
    }

    @Test
    fun `second recordResponse is rejected`() {
        val key = newIdem()
        key.recordResponse(201, mapOf("id" to "res_123"), Instant.now())
        assertThrows(IllegalStateException::class.java) {
            key.recordResponse(200, mapOf("id" to "res_456"), Instant.now().plusSeconds(1))
        }
    }

    @Test
    fun `valid actions for RestaurantAuditLog`() {
        for (action in listOf(
            RestaurantAuditLog.ACTION_APPROVE,
            RestaurantAuditLog.ACTION_REJECT,
            RestaurantAuditLog.ACTION_SUSPEND,
            RestaurantAuditLog.ACTION_REINSTATE,
            RestaurantAuditLog.ACTION_CLOSE,
            RestaurantAuditLog.ACTION_ONLINE,
            RestaurantAuditLog.ACTION_OFFLINE,
            RestaurantAuditLog.ACTION_SUBMIT,
            RestaurantAuditLog.ACTION_RESUBMIT,
            RestaurantAuditLog.ACTION_MERCHANT_SUSPEND_CASCADE,
            RestaurantAuditLog.ACTION_MERCHANT_REINSTATE_CASCADE,
            RestaurantAuditLog.ACTION_MERCHANT_CLOSE_CASCADE,
        )) {
            val audit = RestaurantAuditLog(
                id = UUID.randomUUID(),
                restaurantId = UUID.randomUUID(),
                action = action,
                actorKcSub = sys,
                actorType = "admin",
                correlationId = UUID.randomUUID(),
            )
            assertEquals(action, audit.action)
        }
    }

    @Test
    fun `invalid audit action rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            RestaurantAuditLog(
                id = UUID.randomUUID(),
                restaurantId = UUID.randomUUID(),
                action = "wrong_action",
                actorKcSub = sys,
                actorType = "admin",
                correlationId = UUID.randomUUID(),
            )
        }
    }

    @Test
    fun `invalid actor_type rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            RestaurantAuditLog(
                id = UUID.randomUUID(),
                restaurantId = UUID.randomUUID(),
                action = RestaurantAuditLog.ACTION_APPROVE,
                actorKcSub = sys,
                actorType = "robot",
                correlationId = UUID.randomUUID(),
            )
        }
    }

    @Test
    fun `valid actor_types for RestaurantAuditLog`() {
        for (actorType in listOf("admin", "owner", "staff", "system")) {
            val audit = RestaurantAuditLog(
                id = UUID.randomUUID(),
                restaurantId = UUID.randomUUID(),
                action = RestaurantAuditLog.ACTION_APPROVE,
                actorKcSub = sys,
                actorType = actorType,
                correlationId = UUID.randomUUID(),
            )
            assertEquals(actorType, audit.actorType)
        }
    }

    @Test
    fun `cuisine length validation at construction`() {
        assertThrows(IllegalArgumentException::class.java) {
            RestaurantCuisine(
                restaurantId = UUID.randomUUID(),
                cuisine = "x".repeat(51),
            )
        }
    }

    @Test
    fun `tag length validation at construction`() {
        assertThrows(IllegalArgumentException::class.java) {
            RestaurantTag(
                restaurantId = UUID.randomUUID(),
                tag = "x".repeat(51),
            )
        }
    }
}