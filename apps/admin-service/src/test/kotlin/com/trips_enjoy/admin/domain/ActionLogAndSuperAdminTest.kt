package com.trips_enjoy.admin.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import com.trips_enjoy.admin.domain.repositories.SuperAdminGrantRepository

/**
 * Unit tests for ActionLog + SuperAdminGrant + BreakGlass + PricingGeoConfig
 * + PricingGeoConfigHistory + IdempotencyKey + OutboxEvent domain entities.
 * Mirrors the test patterns from customer-service / driver-service /
 * courier-service / restaurant-service / pricing-service.
 */
class ActionLogAndSuperAdminTest {

    private val now: Instant = Instant.parse("2026-08-15T12:00:00Z")
    private val sys: UUID = UUID.randomUUID()

    private fun newActionLog(actorKind: String = "admin"): ActionLog = ActionLog(
        id = ActionLogKey(
            id = UUID.randomUUID(),
            occurredAt = java.time.Instant.now(),
        ),
        actionType = "customer.suspend",
        actorKcSub = sys,
        actorKind = actorKind,
        correlationId = UUID.randomUUID(),
    )

    // ---------- ActionLog ----------

    @Test
    fun `valid actor kinds accepted`() {
        for (kind in listOf("admin", "owner", "staff", "system", "model")) {
            newActionLog(actorKind = kind)
        }
    }

    @Test
    fun `invalid actor kind rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            newActionLog(actorKind = "robot")
        }
    }

    @Test
    fun `action_type over 100 chars rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            ActionLog(
                id = ActionLogKey(
                    id = UUID.randomUUID(),
                    occurredAt = java.time.Instant.now(),
                ),
                actionType = "x".repeat(101),
                actorKcSub = sys,
                actorKind = "admin",
                correlationId = UUID.randomUUID(),
            )
        }
    }

    // ---------- SuperAdminGrant ----------

    private fun newPermanentGrant(): SuperAdminGrant = SuperAdminGrant(
        id = UUID.randomUUID(),
        granteeKcSub = UUID.randomUUID(),
        grantedByKcSub = UUID.randomUUID(),
        reason = "platform super-admin",
        aliasKind = SuperAdminGrant.ALIAS_PERMANENT,
        correlationId = UUID.randomUUID(),
        createdBy = UUID.randomUUID(),
    )

    @Test
    fun `permanent grant is active by default`() {
        assertTrue(newPermanentGrant().isActive())
    }

    @Test
    fun `time_bounded grant requires expires_at`() {
        assertThrows(IllegalArgumentException::class.java) {
            SuperAdminGrant(
                id = UUID.randomUUID(),
                granteeKcSub = UUID.randomUUID(),
                grantedByKcSub = UUID.randomUUID(),
                reason = "x",
                aliasKind = SuperAdminGrant.ALIAS_TIME_BOUNDED,
                aliasExpiresAt = null,
                correlationId = UUID.randomUUID(),
                createdBy = UUID.randomUUID(),
            )
        }
    }

    @Test
    fun `time_bounded grant expires`() {
        val testNow = java.time.Instant.now().plusSeconds(1)
        val grant = SuperAdminGrant(
            id = UUID.randomUUID(),
            granteeKcSub = UUID.randomUUID(),
            grantedByKcSub = UUID.randomUUID(),
            reason = "x",
            aliasKind = SuperAdminGrant.ALIAS_TIME_BOUNDED,
            aliasExpiresAt = testNow.plus(60, ChronoUnit.SECONDS),
            correlationId = UUID.randomUUID(),
            createdBy = UUID.randomUUID(),
        )
        assertTrue(grant.isActive())
        assertFalse(grant.isActive(testNow.plus(180, ChronoUnit.SECONDS)))
    }

    @Test
    fun `permanent grant rejects alias_expires_at`() {
        assertThrows(IllegalArgumentException::class.java) {
            SuperAdminGrant(
                id = UUID.randomUUID(),
                granteeKcSub = UUID.randomUUID(),
                grantedByKcSub = UUID.randomUUID(),
                reason = "x",
                aliasKind = SuperAdminGrant.ALIAS_PERMANENT,
                aliasExpiresAt = now.plus(1, ChronoUnit.DAYS),
                correlationId = UUID.randomUUID(),
                createdBy = UUID.randomUUID(),
            )
        }
    }

    @Test
    fun `revoke marks grant inactive`() {
        val grant = newPermanentGrant()
        val revoker = UUID.randomUUID()
        val at = now
        grant.revoke(revoker, at)
        assertEquals(at, grant.revokedAt)
        assertEquals(revoker, grant.revokedByKcSub)
        assertFalse(grant.isActive())
    }

    @Test
    fun `double revoke rejected`() {
        val grant = newPermanentGrant()
        grant.revoke(UUID.randomUUID())
        assertThrows(IllegalStateException::class.java) {
            grant.revoke(UUID.randomUUID())
        }
    }

    // ---------- BreakGlass ----------

    @Test
    fun `break_glass creation`() {
        val bg = BreakGlass(
            id = UUID.randomUUID(),
            actionLogId = UUID.randomUUID(),
            cosignerKcSub = UUID.randomUUID(),
            reason = "emergency customer unblock",
            signature = "sig-12345",
            correlationId = UUID.randomUUID(),
            expiresAt = now.plus(7, ChronoUnit.DAYS),
            createdBy = UUID.randomUUID(),
        )
        assertTrue(bg.isActive())
    }

    @Test
    fun `break_glass reason below 8 chars rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            BreakGlass(
                id = UUID.randomUUID(),
                actionLogId = UUID.randomUUID(),
                cosignerKcSub = UUID.randomUUID(),
                reason = "short",
                signature = "sig",
                correlationId = UUID.randomUUID(),
                expiresAt = now.plus(7, ChronoUnit.DAYS),
                createdBy = UUID.randomUUID(),
            )
        }
    }

    @Test
    fun `break_glass expires_at must be after occurred_at`() {
        assertThrows(IllegalArgumentException::class.java) {
            BreakGlass(
                id = UUID.randomUUID(),
                actionLogId = UUID.randomUUID(),
                cosignerKcSub = UUID.randomUUID(),
                reason = "emergency customer unblock",
                signature = "sig",
                correlationId = UUID.randomUUID(),
                occurredAt = now,
                expiresAt = now.minus(60, ChronoUnit.SECONDS),
                createdBy = UUID.randomUUID(),
            )
        }
    }

    @Test
    fun `break_glass revoke marks inactive`() {
        val bg = BreakGlass(
            id = UUID.randomUUID(),
            actionLogId = UUID.randomUUID(),
            cosignerKcSub = UUID.randomUUID(),
            reason = "emergency customer unblock",
            signature = "sig",
            correlationId = UUID.randomUUID(),
            expiresAt = now.plus(7, ChronoUnit.DAYS),
            createdBy = UUID.randomUUID(),
        )
        val revoker = UUID.randomUUID()
        val at = now.plus(1, ChronoUnit.HOURS)
        bg.revoke(revoker, at)
        assertEquals(at, bg.revokedAt)
        assertEquals(revoker, bg.revokedBy)
        assertFalse(bg.isActive())
    }

    // ---------- IdempotencyKey ----------

    @Test
    fun `idempotency_key valid scope accepted`() {
        IdempotencyKey(
            id = UUID.randomUUID(),
            scope = IdempotencyKey.SCOPE_ADMIN_ACTION,
            idemKey = "idem_valid_length",
            requestHash = "a".repeat(64),
            createdBy = UUID.randomUUID(),
        )
    }

    @Test
    fun `idempotency_key invalid scope rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            IdempotencyKey(
                id = UUID.randomUUID(),
                scope = "wrong_scope",
                idemKey = "idem_valid_length",
                requestHash = "a".repeat(64),
                createdBy = UUID.randomUUID(),
            )
        }
    }

    @Test
    fun `idempotency_key short idem_key rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            IdempotencyKey(
                id = UUID.randomUUID(),
                scope = IdempotencyKey.SCOPE_ADMIN_ACTION,
                idemKey = "short",
                requestHash = "a".repeat(64),
                createdBy = UUID.randomUUID(),
            )
        }
    }

    @Test
    fun `idempotency_key short request_hash rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            IdempotencyKey(
                id = UUID.randomUUID(),
                scope = IdempotencyKey.SCOPE_ADMIN_ACTION,
                idemKey = "idem_valid_length",
                requestHash = "too_short",
                createdBy = UUID.randomUUID(),
            )
        }
    }

    @Test
    fun `idempotency_key recordResponse then double rejected`() {
        val key = IdempotencyKey(
            id = UUID.randomUUID(),
            scope = IdempotencyKey.SCOPE_ADMIN_ACTION,
            idemKey = "idem_valid_length",
            requestHash = "a".repeat(64),
            createdBy = UUID.randomUUID(),
        )
        assertFalse(key.isCompleted())
        key.recordResponse(201, mapOf("id" to "x"), now)
        assertTrue(key.isCompleted())
        assertThrows(IllegalStateException::class.java) {
            key.recordResponse(200, mapOf("id" to "y"), now.plus(1, ChronoUnit.SECONDS))
        }
    }

    // ---------- OutboxEvent lifecycle ----------

    @Test
    fun `outbox mark_published`() {
        val e = OutboxEvent(
            id = UUID.randomUUID(),
            aggregateType = "ActionLog",
            aggregateId = UUID.randomUUID(),
            eventType = "admin.action.performed.v1",
            topic = "admin.action.performed.v1",
            payload = mapOf("x" to 1),
            correlationId = UUID.randomUUID(),
            createdBy = UUID.randomUUID(),
        )
        e.markPublished(now)
        assertEquals(now, e.publishedAt)
    }

    @Test
    fun `outbox mark_failed increments attempts`() {
        val e = OutboxEvent(
            id = UUID.randomUUID(),
            aggregateType = "ActionLog",
            aggregateId = UUID.randomUUID(),
            eventType = "admin.action.performed.v1",
            topic = "admin.action.performed.v1",
            payload = mapOf("x" to 1),
            correlationId = UUID.randomUUID(),
            createdBy = UUID.randomUUID(),
        )
        e.markFailed("kafka_unreachable", now.plus(60, ChronoUnit.SECONDS))
        assertEquals(1, e.attempts)
        assertEquals("kafka_unreachable", e.lastError)
    }

    // ---------- PricingGeoConfig ----------

    @Test
    fun `non-OD rule rejects origin and destination`() {
        assertThrows(IllegalArgumentException::class.java) {
            PricingGeoConfig(
                id = UUID.randomUUID(),
                tenantId = "global",
                ruleKind = PricingGeoConfig.RULE_BASE_FARE_OVERRIDE,
                originZoneId = UUID.randomUUID(),
                value = mapOf("base_fare" to "10.00"),
                createdByKcSub = UUID.randomUUID(),
            )
        }
    }

    @Test
    fun `OD-corridor requires both origin and destination`() {
        assertThrows(IllegalArgumentException::class.java) {
            PricingGeoConfig(
                id = UUID.randomUUID(),
                tenantId = "global",
                ruleKind = PricingGeoConfig.RULE_OD_CORRIDOR,
                value = mapOf("multiplier" to "0.95"),
                createdByKcSub = UUID.randomUUID(),
            )
        }
    }

    @Test
    fun `OD-corridor with both zones accepted`() {
        PricingGeoConfig(
            id = UUID.randomUUID(),
            tenantId = "global",
            ruleKind = PricingGeoConfig.RULE_OD_CORRIDOR,
            originZoneId = UUID.randomUUID(),
            destinationZoneId = UUID.randomUUID(),
            value = mapOf("multiplier" to "0.95"),
            createdByKcSub = UUID.randomUUID(),
        )  // no exception
    }

    @Test
    fun `unknown rule_kind rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            PricingGeoConfig(
                id = UUID.randomUUID(),
                tenantId = "global",
                ruleKind = "unknown_kind",
                value = mapOf("x" to "y"),
                createdByKcSub = UUID.randomUUID(),
            )
        }
    }

    // ---------- PricingGeoConfigHistory ----------

    @Test
    fun `pricing geo config history valid actions accepted`() {
        for (action in listOf("create", "update", "disable", "rollback")) {
            PricingGeoConfigHistory(
                id = UUID.randomUUID(),
                configId = UUID.randomUUID(),
                version = 1,
                action = action,
                actorKcSub = UUID.randomUUID(),
                payload = mapOf("x" to 1),
                correlationId = UUID.randomUUID(),
            )
        }
    }

    @Test
    fun `pricing geo config history invalid action rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            PricingGeoConfigHistory(
                id = UUID.randomUUID(),
                configId = UUID.randomUUID(),
                version = 1,
                action = "wrong",
                actorKcSub = UUID.randomUUID(),
                payload = mapOf("x" to 1),
                correlationId = UUID.randomUUID(),
            )
        }
    }

    // ---------- Repository canonical preset ----------

    @Test
    fun `super admin grant repository default preset has 21 scopes`() {
        val scopes = SuperAdminGrantRepository.DEFAULT_PRESET_SCOPES
        assertEquals(21, scopes.size)
        assertTrue(scopes.contains("platform.super_admin"))
        // 20 service admin scopes (everything except platform.super_admin)
        val serviceScopes = scopes - "platform.super_admin"
        assertEquals(20, serviceScopes.size)
    }
}