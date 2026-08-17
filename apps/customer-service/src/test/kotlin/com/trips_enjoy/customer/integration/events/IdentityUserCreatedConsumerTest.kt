package com.trips_enjoy.customer.integration.events

import com.fasterxml.jackson.databind.ObjectMapper
import com.trips_enjoy.customer.application.CustomerWriteService
import com.trips_enjoy.customer.domain.InboxEvent
import com.trips_enjoy.customer.domain.InboxRepository
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.util.UUID

class IdentityUserCreatedConsumerTest {
    private val mapper = ObjectMapper()
    private val inbox: InboxRepository = mock()
    private val writeService: CustomerWriteService = mock()
    private val consumer = IdentityUserCreatedConsumer(mapper, inbox, writeService)

    @Test
    fun `consume creates the customer when identity is new`() {
        val identityId = UUID.randomUUID()
        val eventId = UUID.randomUUID()
        val payload = """
            {"event_id":"$eventId","correlation_id":"${UUID.randomUUID()}",
             "data":{"identity_id":"$identityId","name":"Jane","email":"jane@example.com"}}
        """.trimIndent()
        whenever(inbox.existsByEventId(eventId)).thenReturn(false)
        consumer.consume(payload)
        verify(writeService).upsertFromIdentity(
            identityId = eq(identityId),
            name = eq("Jane"),
            email = eq("jane@example.com"),
            phone = anyOrNull(),
            primaryCityId = anyOrNull(),
            actorId = any(),
            correlationId = any(),
        )
    }

    @Test
    fun `consume skips already-processed events`() {
        val eventId = UUID.randomUUID()
        val payload = """{"event_id":"$eventId","data":{"identity_id":"${UUID.randomUUID()}"}}"""
        whenever(inbox.existsByEventId(eventId)).thenReturn(true)
        consumer.consume(payload)
        verify(writeService, never()).upsertFromIdentity(
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
        )
        verify(inbox, never()).save(any<InboxEvent>())
    }

    @Test
    fun `consume skips malformed payloads without throwing`() {
        val payload = "not json"
        consumer.consume(payload)
        verify(inbox, never()).save(any<InboxEvent>())
    }

    @Test
    fun `consume skips payloads missing identity_id`() {
        val eventId = UUID.randomUUID()
        val payload = """{"event_id":"$eventId","data":{}}"""
        whenever(inbox.existsByEventId(eventId)).thenReturn(false)
        consumer.consume(payload)
        verify(writeService, never()).upsertFromIdentity(
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
        )
    }
}


