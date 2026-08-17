package com.trips_enjoy.customer.integration.events

import com.fasterxml.jackson.databind.ObjectMapper
import com.trips_enjoy.customer.application.LoyaltyAccountService
import com.trips_enjoy.customer.domain.InboxRepository
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.util.UUID

class LoyaltyTierChangedConsumerTest {
    private val mapper = ObjectMapper()
    private val inbox: InboxRepository = mock()
    private val loyaltyAccountService: LoyaltyAccountService = mock()
    private val consumer = LoyaltyTierChangedConsumer(mapper, inbox, loyaltyAccountService)

    @Test
    fun `consume applies the new loyalty tier to the customer`() {
        val customerId = UUID.randomUUID()
        val eventId = UUID.randomUUID()
        val payload = """
            {"event_id":"$eventId","data":{"customer_id":"$customerId","new_tier":"gold"}}
        """.trimIndent()
        whenever(inbox.existsByEventId(eventId)).thenReturn(false)
        consumer.consume(payload)
        verify(loyaltyAccountService).applyTierChanged(
            customerId = eq(customerId),
            newTier = eq("gold"),
            correlationId = any(),
        )
    }

    @Test
    fun `consume skips already-processed events`() {
        val eventId = UUID.randomUUID()
        val payload = """{"event_id":"$eventId","data":{"customer_id":"${UUID.randomUUID()}","new_tier":"gold"}}"""
        whenever(inbox.existsByEventId(eventId)).thenReturn(true)
        consumer.consume(payload)
        verify(loyaltyAccountService, never()).applyTierChanged(any(), any(), any())
    }

    @Test
    fun `consume skips malformed payloads without throwing`() {
        consumer.consume("not json")
        verify(loyaltyAccountService, never()).applyTierChanged(any(), any(), any())
    }
}
