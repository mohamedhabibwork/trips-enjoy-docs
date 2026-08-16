package com.trips_enjoy.configuration.integration.events

import com.fasterxml.jackson.databind.ObjectMapper
import com.trips_enjoy.configuration.domain.InboxEvent
import com.trips_enjoy.configuration.domain.InboxRepository
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.data.redis.core.StringRedisTemplate
import java.util.UUID

class CustomerSegmentChangedConsumerTest {
    private val mapper = ObjectMapper()
    private val inbox: InboxRepository = mock()
    private val redis: StringRedisTemplate = mock()
    private val consumer = CustomerSegmentChangedConsumer(mapper, inbox, redis)

    @Test
    fun `consume invalidates Redis keys matching the user prefix`() {
        val userId = UUID.randomUUID().toString()
        val payload = """{"event_id":"${UUID.randomUUID()}","data":{"user_id":"$userId"}}"""
        whenever(inbox.existsByEventId(any())).thenReturn(false)
        whenever(redis.keys("cache:user:$userId:*")).thenReturn(setOf("cache:user:$userId:v1", "cache:user:$userId:v2"))
        consumer.consume(payload)
        verify(redis).delete(any<Set<String>>())
    }

    @Test
    fun `consume skips already-processed events without touching Redis`() {
        val userId = UUID.randomUUID().toString()
        val payload = """{"event_id":"${UUID.randomUUID()}","data":{"user_id":"$userId"}}"""
        whenever(inbox.existsByEventId(any())).thenReturn(true)
        consumer.consume(payload)
        verify(redis, never()).keys(any<String>())
        verify(redis, never()).delete(any<Set<String>>())
    }

    @Test
    fun `consume skips malformed payloads without throwing`() {
        val payload = "this is not json"
        // Should not throw — it just logs a warning.
        consumer.consume(payload)
        verify(inbox, never()).save(any<InboxEvent>())
    }

    @Test
    fun `consume skips events with missing user_id`() {
        val eventId = UUID.randomUUID()
        val payload = """{"event_id":"$eventId","data":{}}"""
        whenever(inbox.existsByEventId(any())).thenReturn(false)
        consumer.consume(payload)
        verify(redis, never()).keys(any<String>())
    }
}
