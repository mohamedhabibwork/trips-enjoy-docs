package com.trips_enjoy.search.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * Unit tests for search-service domain entities.
 * Covers:
 *   - ReindexJob: state machine + validations
 *   - QueryLog: actor_kind validation + invariants
 *   - RelevanceConfig: boost + decay_days invariants
 *   - IndexHealth: status validation + invariants
 *   - IdempotencyKey: scope + idem_key + request_hash invariants
 *   - OutboxEvent / InboxEvent: lifecycle
 */
class SearchDomainTest {

    private val now: Instant = Instant.parse("2026-08-15T12:00:00Z")
    private val sys: UUID = UUID.randomUUID()

    // ---------- ReindexJob ----------

    @Test
    fun `reindex job start moves pending to running`() {
        val job = newReindexJob()
        job.start(now)
        assertEquals(ReindexJob.STATE_RUNNING, job.state)
        assertNotNull(job.startedAt)
    }

    @Test
    fun `reindex job start rejects non-pending state`() {
        val job = newReindexJob(ReindexJob.STATE_RUNNING)
        assertThrows(IllegalStateException::class.java) {
            job.start(now)
        }
    }

    @Test
    fun `reindex job complete moves running to completed`() {
        val job = newReindexJob(ReindexJob.STATE_RUNNING)
        job.complete(now.plusSeconds(60))
        assertEquals(ReindexJob.STATE_COMPLETED, job.state)
        assertNotNull(job.completedAt)
    }

    @Test
    fun `reindex job complete rejects non-running state`() {
        val job = newReindexJob(ReindexJob.STATE_PENDING)
        assertThrows(IllegalStateException::class.java) {
            job.complete(now.plusSeconds(60))
        }
    }

    @Test
    fun `reindex job fail rejects completed state`() {
        val job = newReindexJob(ReindexJob.STATE_COMPLETED)
        assertThrows(IllegalStateException::class.java) {
            job.fail("kafka_unreachable", now.plusSeconds(60))
        }
    }

    @Test
    fun `reindex job cancel rejects terminal states`() {
        val completed = newReindexJob(ReindexJob.STATE_COMPLETED)
        assertThrows(IllegalStateException::class.java) {
            completed.cancel(now.plusSeconds(60))
        }
        val failed = newReindexJob(ReindexJob.STATE_FAILED)
        assertThrows(IllegalStateException::class.java) {
            failed.cancel(now.plusSeconds(60))
        }
    }

    @Test
    fun `reindex job rejects unknown vertical`() {
        assertThrows(IllegalArgumentException::class.java) {
            ReindexJob(
                vertical = "spaceship",
                requestedBy = sys,
                correlationId = UUID.randomUUID(),
            )
        }
    }

    @Test
    fun `reindex job rejects unknown state`() {
        assertThrows(IllegalArgumentException::class.java) {
            ReindexJob(
                vertical = ReindexJob.VERTICAL_RESTAURANTS,
                state = "frozen",
                requestedBy = sys,
                correlationId = UUID.randomUUID(),
            )
        }
    }

    @Test
    fun `reindex job full happy path pending → running → completed`() {
        val job = newReindexJob()
        job.start(now)
        job.complete(now.plusSeconds(600))
        assertEquals(ReindexJob.STATE_COMPLETED, job.state)
        assertNotNull(job.startedAt)
        assertNotNull(job.completedAt)
    }

    // ---------- QueryLog ----------

    @Test
    fun `query_log rejects unknown actor_kind`() {
        assertThrows(IllegalArgumentException::class.java) {
            QueryLog(
                id = UUID.randomUUID(),
                vertical = ReindexJob.VERTICAL_RESTAURANTS,
                queryText = "burger near me",
                actorKind = "robot",
                correlationId = UUID.randomUUID(),
            )
        }
    }

    @Test
    fun `query_log valid actor_kinds accepted`() {
        for (kind in listOf("rider", "driver", "admin", "system", "merchant")) {
            QueryLog(
                id = UUID.randomUUID(),
                vertical = ReindexJob.VERTICAL_RESTAURANTS,
                queryText = "x",
                actorKind = kind,
                correlationId = UUID.randomUUID(),
            )
        }
    }

    // ---------- RelevanceConfig ----------

    @Test
    fun `relevance config rejects negative boost`() {
        assertThrows(IllegalArgumentException::class.java) {
            RelevanceConfig(
                vertical = ReindexJob.VERTICAL_RESTAURANTS,
                field = "name",
                boost = -0.5,
                updatedByKcSub = sys,
                correlationId = UUID.randomUUID(),
            )
        }
    }

    @Test
    fun `relevance config rejects field name too long`() {
        assertThrows(IllegalArgumentException::class.java) {
            RelevanceConfig(
                vertical = ReindexJob.VERTICAL_RESTAURANTS,
                field = "x".repeat(101),
                updatedByKcSub = sys,
                correlationId = UUID.randomUUID(),
            )
        }
    }

    @Test
    fun `relevance config update applies changes`() {
        val rc = RelevanceConfig(
            vertical = ReindexJob.VERTICAL_RESTAURANTS,
            field = "name",
            boost = 1.0,
            updatedByKcSub = sys,
            correlationId = UUID.randomUUID(),
        )
        rc.update(boost = 2.5, decayDays = 30, enabled = true, at = now.plusSeconds(60))
        assertEquals(2.5, rc.boost)
        assertEquals(30, rc.decayDays)
        assertTrue(rc.enabled)
        assertEquals(now.plusSeconds(60), rc.updatedAt)
    }

    // ---------- IndexHealth ----------

    @Test
    fun `index health rejects unknown status`() {
        assertThrows(IllegalArgumentException::class.java) {
            IndexHealth(
                id = UUID.randomUUID(),
                clusterName = "test",
                status = "purple",
                correlationId = UUID.randomUUID(),
            )
        }
    }

    @Test
    fun `index health valid statuses accepted`() {
        for (s in listOf("green", "yellow", "red", "unknown")) {
            IndexHealth(
                id = UUID.randomUUID(),
                clusterName = "test",
                status = s,
                correlationId = UUID.randomUUID(),
            )
        }
    }

    @Test
    fun `index health rejects negative counters`() {
        assertThrows(IllegalArgumentException::class.java) {
            IndexHealth(
                id = UUID.randomUUID(),
                clusterName = "test",
                status = "green",
                nodeCount = -1,
                correlationId = UUID.randomUUID(),
            )
        }
    }

    // ---------- IdempotencyKey ----------

    @Test
    fun `idempotency key valid scopes accepted`() {
        for (scope in listOf("search_query", "reindex_start", "relevance_update")) {
            IdempotencyKey(
                id = UUID.randomUUID(),
                scope = scope,
                idemKey = "idem_valid_length",
                requestHash = "a".repeat(64),
                createdBy = sys,
            )
        }
    }

    @Test
    fun `idempotency key invalid scope rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            IdempotencyKey(
                id = UUID.randomUUID(),
                scope = "trip_xxx",
                idemKey = "idem_valid_length",
                requestHash = "a".repeat(64),
                createdBy = sys,
            )
        }
    }

    @Test
    fun `idempotency key short idem_key rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            IdempotencyKey(
                id = UUID.randomUUID(),
                scope = IdempotencyKey.SCOPE_SEARCH_QUERY,
                idemKey = "short",
                requestHash = "a".repeat(64),
                createdBy = sys,
            )
        }
    }

    @Test
    fun `idempotency key request_hash length enforced`() {
        assertThrows(IllegalArgumentException::class.java) {
            IdempotencyKey(
                id = UUID.randomUUID(),
                scope = IdempotencyKey.SCOPE_SEARCH_QUERY,
                idemKey = "idem_valid_length",
                requestHash = "too_short",
                createdBy = sys,
            )
        }
    }

    @Test
    fun `idempotency key double recordResponse rejected`() {
        val key = IdempotencyKey(
            id = UUID.randomUUID(),
            scope = IdempotencyKey.SCOPE_SEARCH_QUERY,
            idemKey = "idem_valid_length",
            requestHash = "a".repeat(64),
            createdBy = sys,
        )
        assertFalse(key.isCompleted())
        key.recordResponse(200, mapOf("results" to emptyList<Any>()), now)
        assertTrue(key.isCompleted())
        assertThrows(IllegalStateException::class.java) {
            key.recordResponse(200, mapOf("results" to emptyList<Any>()), now.plusSeconds(1))
        }
    }

    // ---------- OutboxEvent / InboxEvent ----------

    @Test
    fun `outbox mark_published sets timestamp`() {
        val e = OutboxEvent(
            id = UUID.randomUUID(),
            aggregateType = "ReindexJob",
            aggregateId = UUID.randomUUID(),
            eventType = "search.reindex.started.v1",
            topic = "search.reindex.started.v1",
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
            aggregateType = "ReindexJob",
            aggregateId = UUID.randomUUID(),
            eventType = "search.reindex.started.v1",
            topic = "search.reindex.started.v1",
            payload = mapOf("x" to 1),
            correlationId = UUID.randomUUID(),
            createdBy = sys,
        )
        e.markFailed("kafka_unreachable", now.plus(60, ChronoUnit.SECONDS))
        assertEquals(1, e.attempts)
        assertEquals("kafka_unreachable", e.lastError)
    }

    @Test
    fun `inbox mark_processed sets timestamp`() {
        val e = InboxEvent(
            id = UUID.randomUUID(),
            sourceTopic = "restaurant.created.v1",
            sourceEventId = UUID.randomUUID(),
            eventType = "restaurant.created.v1",
            payload = mapOf("x" to 1),
            correlationId = UUID.randomUUID(),
            createdBy = sys,
        )
        e.markProcessed(now)
        assertEquals(now, e.processedAt)
    }

    private fun newReindexJob(state: String = ReindexJob.STATE_PENDING): ReindexJob = ReindexJob(
        vertical = ReindexJob.VERTICAL_RESTAURANTS,
        state = state,
        requestedBy = sys,
        correlationId = UUID.randomUUID(),
    )
}