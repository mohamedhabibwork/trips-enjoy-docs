package com.trips_enjoy.customer.integration.events

import com.fasterxml.jackson.databind.ObjectMapper
import com.trips_enjoy.customer.domain.InboxRepository
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.util.UUID

class ConfigurationUpdatedConsumerTest {
    private val mapper = ObjectMapper()
    private val inbox: InboxRepository = mock()
    private val consumer = ConfigurationUpdatedConsumer(mapper, inbox)

    @Test
    fun `consume records the event in the inbox`() {
        val eventId = UUID.randomUUID()
        val payload = """
            {"event_id":"$eventId","data":{"key":"customer.segment.frequent_rides","value":20}}
        """.trimIndent()
        whenever(inbox.existsByEventId(eventId)).thenReturn(false)
        consumer.consume(payload)
        verify(inbox).save(any())
    }

    @Test
    fun `consume skips already-processed events`() {
        val eventId = UUID.randomUUID()
        val payload = """{"event_id":"$eventId","data":{"key":"customer.segment.frequent_rides"}}"""
        whenever(inbox.existsByEventId(eventId)).thenReturn(true)
        consumer.consume(payload)
        verify(inbox, never()).save(any())
    }

    @Test
    fun `consume skips malformed payloads without throwing`() {
        consumer.consume("not json")
        verify(inbox, never()).save(any())
    }
}
