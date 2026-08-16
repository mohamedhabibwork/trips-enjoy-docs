package com.trips_enjoy.customer.integration.events

import com.fasterxml.jackson.databind.ObjectMapper
import com.trips_enjoy.customer.application.LtvUpdateService
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

class PaymentCompletedConsumerTest {
    private val mapper = ObjectMapper()
    private val inbox: InboxRepository = mock()
    private val ltvUpdateService: LtvUpdateService = mock()
    private val consumer = PaymentCompletedConsumer(mapper, inbox, ltvUpdateService)

    @Test
    fun `onRidePaymentCompleted forwards amount and currency to the LTV service`() {
        val customerId = UUID.randomUUID()
        val eventId = UUID.randomUUID()
        val payload = """
            {"event_id":"$eventId","correlation_id":"${UUID.randomUUID()}",
             "data":{"customer_id":"$customerId","amount_minor":2500,"currency":"USD"}}
        """.trimIndent()
        whenever(inbox.existsByEventId(eventId)).thenReturn(false)
        consumer.onRidePaymentCompleted(payload)
        verify(ltvUpdateService).applyPayment(
            customerId = eq(customerId),
            amountMinor = eq(2500L),
            currency = eq("USD"),
            service = eq("ride"),
            requestId = anyOrNull(),
            correlationId = any(),
        )
    }

    @Test
    fun `onFoodPaymentCompleted tags service as food`() {
        val customerId = UUID.randomUUID()
        val eventId = UUID.randomUUID()
        val payload = """
            {"event_id":"$eventId","data":{"customer_id":"$customerId","amount_minor":1500,"currency":"EUR"}}
        """.trimIndent()
        whenever(inbox.existsByEventId(eventId)).thenReturn(false)
        consumer.onFoodPaymentCompleted(payload)
        verify(ltvUpdateService).applyPayment(
            customerId = eq(customerId),
            amountMinor = eq(1500L),
            currency = eq("EUR"),
            service = eq("food"),
            requestId = anyOrNull(),
            correlationId = any(),
        )
    }

    @Test
    fun `consume skips already-processed events`() {
        val eventId = UUID.randomUUID()
        val payload = """{"event_id":"$eventId","data":{"customer_id":"${UUID.randomUUID()}","amount_minor":1}}"""
        whenever(inbox.existsByEventId(eventId)).thenReturn(true)
        consumer.onRidePaymentCompleted(payload)
        verify(ltvUpdateService, never()).applyPayment(
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
        )
    }

    @Test
    fun `consume skips malformed payloads`() {
        consumer.onRidePaymentCompleted("not json")
        verify(ltvUpdateService, never()).applyPayment(
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
        )
    }
}
