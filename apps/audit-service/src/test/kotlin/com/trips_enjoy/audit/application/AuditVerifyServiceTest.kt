package com.trips_enjoy.audit.application

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.trips_enjoy.audit.api.ApiException
import com.trips_enjoy.audit.domain.AuditEvent
import com.trips_enjoy.audit.domain.AuditEventRepository
import com.trips_enjoy.audit.util.HashChain
import com.trips_enjoy.audit.util.uuidV7
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import java.time.Instant
import java.util.UUID

class AuditVerifyServiceTest {

    private val events = mock(AuditEventRepository::class.java)
    private val meterRegistry = SimpleMeterRegistry()
    private val service = AuditVerifyService(events, IngestionMetrics(meterRegistry), "sha256")
    private val mapper = ObjectMapper()
        .registerModule(JavaTimeModule())
        .registerModule(KotlinModule.Builder().build())

    @Test
    fun `verify on missing event returns 404`() {
        val id = UUID.randomUUID()
        `when`(events.findByEventId(id)).thenReturn(java.util.Optional.empty())
        val ex = assertThrows(ApiException::class.java) { service.verify(id) }
        assertEquals("EVENT_NOT_FOUND", ex.code)
    }

    @Test
    fun `verify on a single-row chain passes`() {
        val row = buildEvent(prevHash = null)
        `when`(events.findByEventId(row.id)).thenReturn(java.util.Optional.of(row))
        `when`(events.findUpToIncluding(row.id, row.createdAt)).thenReturn(listOf(row))
        val result = service.verify(row.id)
        assertTrue(result.verified)
        assertEquals(1L, result.chain_length)
        assertEquals(row.hash, result.target_hash)
    }

    @Test
    fun `verify detects tamper when stored hash differs`() {
        val first = buildEvent(prevHash = null)
        val second = buildEvent(prevHash = first.hash)
        // Tamper: corrupt the second row's stored hash but keep its
        // prevHash pointing at the correct chain tip.
        val tampered = second.copyFields(hash = "f".repeat(64))
        `when`(events.findByEventId(second.id)).thenReturn(java.util.Optional.of(second))
        `when`(events.findUpToIncluding(second.id, second.createdAt)).thenReturn(listOf(first, tampered))
        val result = service.verify(second.id)
        assertFalse(result.verified)
        assertNotNull(result.mismatch_id)
        assertEquals(second.id, result.mismatch_id)
    }

    private fun buildEvent(prevHash: String?): AuditEvent {
        val id = uuidV7()
        val now = Instant.now()
        val eventId = UUID.randomUUID()
        val data = "{}"
        val canonical = HashChain.canonicalize(
            eventId = eventId.toString(),
            eventName = "trip.completed.v1",
            schemaVersion = 1,
            occurredAtIso = now.toString(),
            producer = "trip-service",
            tenantId = "global",
            correlationId = id.toString(),
            aggregateType = "Trip",
            aggregateId = id.toString(),
            subjectType = "trip",
            subjectId = id.toString(),
            dataJson = data,
        )
        val hash = HashChain.nextHash(prevHash, canonical, "sha256")
        return AuditEvent(
            id = id,
            eventId = eventId,
            eventName = "trip.completed.v1",
            schemaVersion = 1,
            occurredAt = now,
            receivedAt = now,
            producer = "trip-service",
            tenantId = "global",
            correlationId = id,
            aggregateType = "Trip",
            aggregateId = id,
            subjectType = "trip",
            subjectId = id,
            data = data,
            headers = null,
            topic = "trip.completed",
            partition = 0,
            offset = 0L,
            prevHash = prevHash,
            hash = hash,
            retentionClass = "default",
            litigationHold = false,
            retentionUntil = null,
            createdAt = now,
        )
    }

    /**
     * Helper to "tamper" with an immutable AuditEvent by replacing just the
     * hash field. The class itself is not a data class so we can't use
     * `copy()`.
     */
    private fun AuditEvent.copyFields(hash: String): AuditEvent = AuditEvent(
        id = this.id,
        eventId = this.eventId,
        eventName = this.eventName,
        schemaVersion = this.schemaVersion,
        occurredAt = this.occurredAt,
        receivedAt = this.receivedAt,
        producer = this.producer,
        tenantId = this.tenantId,
        correlationId = this.correlationId,
        causationId = this.causationId,
        aggregateType = this.aggregateType,
        aggregateId = this.aggregateId,
        subjectType = this.subjectType,
        subjectId = this.subjectId,
        data = this.data,
        headers = this.headers,
        topic = this.topic,
        partition = this.partition,
        offset = this.offset,
        prevHash = this.prevHash,
        hash = hash,
        retentionClass = this.retentionClass,
        litigationHold = this.litigationHold,
        retentionUntil = this.retentionUntil,
        createdAt = this.createdAt,
    )
}
