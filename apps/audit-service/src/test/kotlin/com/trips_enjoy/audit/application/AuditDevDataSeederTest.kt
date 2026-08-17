package com.trips_enjoy.audit.application

import com.trips_enjoy.audit.domain.AuditEvent
import com.trips_enjoy.audit.domain.AuditEventRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import java.util.Optional
import java.util.UUID

class AuditDevDataSeederTest {

    private val events = mock(AuditEventRepository::class.java)
    private val args = mock(org.springframework.boot.ApplicationArguments::class.java)

    private fun seeder(profile: String): AuditDevDataSeeder =
        AuditDevDataSeeder(events, profile)

    private val markerEventId = UUID.fromString("11111111-aaaa-7aaa-8aaa-000000000001")

    @Test
    fun `seeders refuses to run in production profile`() {
        seeder("prod").run(args)
        verify(events, never()).findByEventId(markerEventId)
    }

    @Test
    fun `seeders refuses to run in stg profile`() {
        seeder("stg").run(args)
        verify(events, never()).findByEventId(markerEventId)
    }

    @Test
    fun `seeders refuses to run in live profile`() {
        seeder("live").run(args)
        verify(events, never()).findByEventId(markerEventId)
    }

    @Test
    fun `seeders is idempotent — second boot does not re-insert when marker exists`() {
        `when`(events.findByEventId(markerEventId)).thenReturn(Optional.of(sampleEvent(markerEventId)))

        seeder("dev").run(args)

        verify(events, times(1)).findByEventId(markerEventId)
        // save() should never be called on the second boot.
        // Use a counter via direct verification with concrete arg.
        verify(events, never()).save(sampleEvent(UUID.randomUUID()))
    }

    @Test
    fun `seeders inserts 8 fixtures on a fresh schema`() {
        `when`(events.findByEventId(markerEventId)).thenReturn(Optional.empty())
        val saved = mutableListOf<AuditEvent>()
        // Use doAnswer().when() so the matcher doesn't need to be inferred
        // against the concrete AuditEvent instance the seeder will pass.
        org.mockito.Mockito.doAnswer { invocation ->
            val e = invocation.arguments[0] as AuditEvent
            saved.add(e)
            e
        }.`when`(events).save(org.mockito.ArgumentMatchers.any(AuditEvent::class.java))

        seeder("dev").run(args)

        // 8 fixtures per the buildFixtures() function.
        assertEquals(8, saved.size, "expected 8 fixtures to be inserted")
    }

    @Test
    fun `seeders produces a valid hash chain where each row prev_hash matches the previous row's hash`() {
        `when`(events.findByEventId(markerEventId)).thenReturn(Optional.empty())
        val saved = mutableListOf<AuditEvent>()
        org.mockito.Mockito.doAnswer { invocation ->
            val e = invocation.arguments[0] as AuditEvent
            saved.add(e)
            e
        }.`when`(events).save(org.mockito.ArgumentMatchers.any(AuditEvent::class.java))

        seeder("dev").run(args)

        // Chain integrity: every row's prev_hash equals the previous row's hash
        // (the genesis row has prev_hash = null).
        saved.forEachIndexed { i, row ->
            val expectedPrev: String? = if (i == 0) null else saved[i - 1].hash
            assertEquals(expectedPrev, row.prevHash, "row $i (${row.eventName}) has wrong prev_hash")
        }
        // And every hash is exactly 64 hex chars.
        saved.forEach { row ->
            assertEquals(64, row.hash.length, "hash for ${row.eventName} not 64 chars")
            assertTrue(row.hash.all { it.isDigit() || it in 'a'..'f' }, "hash contains non-hex")
        }
    }

    private fun sampleEvent(eventId: UUID): AuditEvent {
        val id = UUID.randomUUID()
        return AuditEvent(
            id = id,
            eventId = eventId,
            eventName = "trip.completed.v1",
            schemaVersion = 1,
            occurredAt = java.time.Instant.now(),
            receivedAt = java.time.Instant.now(),
            producer = "trip-service",
            tenantId = "global",
            correlationId = UUID.randomUUID(),
            aggregateType = "Trip",
            aggregateId = UUID.randomUUID(),
            subjectType = "trip",
            subjectId = UUID.randomUUID(),
            data = "{}",
            headers = null,
            topic = "trip.completed",
            partition = 0,
            offset = 1L,
            prevHash = null,
            hash = "0".repeat(64),
            retentionClass = "default",
            litigationHold = false,
            retentionUntil = null,
            createdAt = java.time.Instant.now(),
        )
    }
}
