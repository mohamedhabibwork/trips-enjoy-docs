package com.trips_enjoy.audit.application

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.trips_enjoy.audit.domain.AuditEvent
import com.trips_enjoy.audit.domain.AuditEventRepository
import com.trips_enjoy.audit.domain.InboxEvent
import com.trips_enjoy.audit.domain.InboxEventRepository
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import java.time.Instant
import java.util.UUID

class AuditIngestServiceTest {

    private val events = mock(AuditEventRepository::class.java)
    private val inbox = mock(InboxEventRepository::class.java)
    private val meterRegistry = SimpleMeterRegistry()
    private val mapper = ObjectMapper()
        .registerModule(JavaTimeModule())
        .registerModule(KotlinModule.Builder().build())

    private fun service(): AuditIngestService {
        val metrics = IngestionMetrics(meterRegistry)
        return AuditIngestService(events, inbox, mapper, metrics, "sha256", 7, 1)
    }

    @Test
    fun `ingest persists event and inbox row when not duplicated`() {
        val eventId = UUID.randomUUID()
        val envelope = mapOf<String, Any?>(
            "event_id" to eventId.toString(),
            "event_name" to "trip.completed.v1",
            "occurred_at" to Instant.now().toString(),
            "tenant_id" to "global",
            "correlation_id" to UUID.randomUUID().toString(),
            "aggregate_type" to "Trip",
            "data" to mapOf("amount_minor" to 1704),
        )
        `when`(inbox.existsByEventId(eventId)).thenReturn(false)
        `when`(events.lockLatest()).thenReturn(emptyList())

        val result = service().ingest(envelope, "trip.completed", 0, 42L, null)
        assertTrue(result.stored)
        verify(events).save(any(AuditEvent::class.java))
        verify(inbox).save(any(InboxEvent::class.java))
        assertEquals(1.0, meterRegistry.counter("audit_events_ingested_total", "topic", "trip.completed").count())
    }

    @Test
    fun `ingest skips when inbox already has the event id`() {
        val eventId = UUID.randomUUID()
        `when`(inbox.existsByEventId(eventId)).thenReturn(true)
        val result = service().ingest(
            mapOf("event_id" to eventId.toString(), "tenant_id" to "global"),
            "trip.completed",
            0,
            0L,
            null,
        )
        assertFalse(result.stored)
        assertEquals("duplicate", result.reason)
        verify(events, never()).save(any(AuditEvent::class.java))
    }

    @Test
    fun `financial events are tagged with financial retention class`() {
        val eventId = UUID.randomUUID()
        `when`(inbox.existsByEventId(eventId)).thenReturn(false)
        `when`(events.lockLatest()).thenReturn(emptyList())
        val envelope = mapOf<String, Any?>(
            "event_id" to eventId.toString(),
            "event_name" to "payment.captured.v1",
            "tenant_id" to "global",
            "correlation_id" to UUID.randomUUID().toString(),
            "data" to emptyMap<String, Any?>(),
        )
        service().ingest(envelope, "payment.captured", 0, 0L, null)
        val captor = org.mockito.ArgumentCaptor.forClass(AuditEvent::class.java)
        verify(events).save(captor.capture())
        assertEquals("financial", captor.value.retentionClass)
    }

    @Test
    fun `non-financial events are tagged with default retention class`() {
        val eventId = UUID.randomUUID()
        `when`(inbox.existsByEventId(eventId)).thenReturn(false)
        `when`(events.lockLatest()).thenReturn(emptyList())
        val envelope = mapOf<String, Any?>(
            "event_id" to eventId.toString(),
            "event_name" to "customer.suspended.v1",
            "tenant_id" to "global",
            "correlation_id" to UUID.randomUUID().toString(),
            "data" to emptyMap<String, Any?>(),
        )
        service().ingest(envelope, "customer.suspended", 0, 0L, null)
        val captor = org.mockito.ArgumentCaptor.forClass(AuditEvent::class.java)
        verify(events).save(captor.capture())
        assertEquals("default", captor.value.retentionClass)
    }

    @Test
    fun `ingest chains hashes from the latest row`() {
        val eventId = UUID.randomUUID()
        val prev = sampleAuditEvent(prevHash = null, hash = "a".repeat(64))
        `when`(inbox.existsByEventId(eventId)).thenReturn(false)
        `when`(events.lockLatest()).thenReturn(listOf(prev))
        val envelope = mapOf<String, Any?>(
            "event_id" to eventId.toString(),
            "event_name" to "trip.completed.v1",
            "tenant_id" to "global",
            "correlation_id" to UUID.randomUUID().toString(),
            "data" to emptyMap<String, Any?>(),
        )
        service().ingest(envelope, "trip.completed", 0, 0L, null)
        val captor = org.mockito.ArgumentCaptor.forClass(AuditEvent::class.java)
        verify(events).save(captor.capture())
        assertEquals("a".repeat(64), captor.value.prevHash)
        assertTrue(captor.value.hash.length == 64)
    }

    @Test
    fun `subject fields are denormalized for trip events`() {
        val eventId = UUID.randomUUID()
        val customerId = UUID.randomUUID()
        `when`(inbox.existsByEventId(eventId)).thenReturn(false)
        `when`(events.lockLatest()).thenReturn(emptyList())
        val envelope = mapOf<String, Any?>(
            "event_id" to eventId.toString(),
            "event_name" to "trip.completed.v1",
            "tenant_id" to "global",
            "correlation_id" to UUID.randomUUID().toString(),
            "aggregate_id" to customerId.toString(),
            "data" to emptyMap<String, Any?>(),
        )
        service().ingest(envelope, "trip.completed", 0, 0L, null)
        val captor = org.mockito.ArgumentCaptor.forClass(AuditEvent::class.java)
        verify(events).save(captor.capture())
        assertEquals("trip", captor.value.subjectType)
        assertEquals(customerId, captor.value.subjectId)
    }

    private fun sampleAuditEvent(prevHash: String?, hash: String): AuditEvent {
        val id = UUID.randomUUID()
        val now = Instant.now()
        return AuditEvent(
            id = id,
            eventId = UUID.randomUUID(),
            eventName = "trip.completed.v1",
            schemaVersion = 1,
            occurredAt = now,
            receivedAt = now,
            producer = "trip-service",
            tenantId = "global",
            correlationId = UUID.randomUUID(),
            aggregateType = "Trip",
            aggregateId = UUID.randomUUID(),
            subjectType = "trip",
            subjectId = id,
            data = "{}",
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
}
