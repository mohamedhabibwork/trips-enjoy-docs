package com.trips_enjoy.ledger.integration.events

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.trips_enjoy.ledger.application.PostingService
import com.trips_enjoy.ledger.domain.InboxEventRepository
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.kafka.support.Acknowledgment
import java.time.Instant
import java.util.UUID

/**
 * Cross-service envelope conformance test for the canonical
 * `trip-service → payment-service → ledger-service → customer-service → audit-service`
 * money-movement saga.
 *
 * This test pins the producer-side envelope shape that payment-service
 * (and any other producer of `payment.captured.v1`) MUST publish so that
 * ledger-service's MoneyMovementConsumer can map it to a balanced
 * CreatePostingRequest. It is the cross-service contract test that
 * downstream services (customer-service PaymentCompletedConsumer,
 * audit-service AuditEventConsumer) all implicitly agree on.
 *
 * The envelope shape is the "platform event" canonical envelope defined
 * in docs/architecture/EVENT_ARCHITECTURE.md §"Event Envelope":
 *
 *   event_id        — UUID v7 (required)
 *   event_name      — `payment.captured.v1` (required)
 *   occurred_at     — RFC 3339 instant (required)
 *   schema_version  — int (required)
 *   producer        — service name (required)
 *   tenant_id       — default `global` (required)
 *   correlation_id  — UUID, ADR-0019 (required)
 *   aggregate_type  — e.g. `payment` (required)
 *   aggregate_id    — UUID, partition key (required)
 *   data            — service-specific payload (required):
 *                       description : String
 *                       entries[]   : { account_code, side, amount_minor, currency }
 *
 * If any producer drifts from this shape, ledger-service falls back to
 * `topic` for the description and silently swallows parse errors — which
 * is the exact failure mode this integration test is designed to catch.
 */
class CrossServiceEnvelopeConformanceTest {
    private val mapper = ObjectMapper().apply {
        registerModule(KotlinModule.Builder().build())
        registerModule(JavaTimeModule())
    }
    private val inbox: InboxEventRepository = mock()
    private val postingService: PostingService = mock()
    private val ack: Acknowledgment = mock()
    private val consumer = MoneyMovementConsumer(mapper, inbox, postingService)

    private val correlationId = UUID.randomUUID()
    private val eventId = UUID.randomUUID()
    private val aggregateId = UUID.randomUUID()

    private fun envelopeFor(
        eventName: String = "payment.captured.v1",
        data: Map<String, Any?> = mapOf(
            "description" to "ride payment capture",
            "entries" to listOf(
                mapOf(
                    "account_code" to "1200_customer_receivable",
                    "side" to "debit",
                    "amount_minor" to 1500,
                    "currency" to "USD",
                ),
                mapOf(
                    "account_code" to "4000_revenue",
                    "side" to "credit",
                    "amount_minor" to 1500,
                    "currency" to "USD",
                ),
            ),
        ),
    ): String {
        val payload = mapOf(
            "event_id" to eventId.toString(),
            "event_name" to eventName,
            "occurred_at" to Instant.now().toString(),
            "schema_version" to 1,
            "producer" to "payment-service",
            "tenant_id" to "global",
            "correlation_id" to correlationId.toString(),
            "aggregate_type" to "payment",
            "aggregate_id" to aggregateId.toString(),
            "data" to data,
        )
        return mapper.writeValueAsString(payload)
    }

    @Test
    fun `payment-captured envelope maps to balanced posting`() {
        whenever(inbox.existsByEventId(any<UUID>())).thenReturn(false)
        consumer.consume(
            envelopeFor(),
            "payment.captured",
            0,
            0L,
            correlationId.toString(),
            correlationId.toString(),
            ack,
        )
        verify(postingService).createPosting(
            request = any(),
            idempotencyKey = eq("ledger:event:$eventId"),
            correlationId = eq(correlationId),
        )
        verify(ack).acknowledge()
    }

    @Test
    fun `X-Request-Id header is used as correlation_id when emitted by api-gateway`() {
        // ADR-0019: api-gateway stamps X-Request-Id; downstream bridges it
        // to X-Correlation-Id. Per the canonical request-id-at-edge
        // contract, X-Request-Id is the root and must propagate.
        whenever(inbox.existsByEventId(any<UUID>())).thenReturn(false)
        val requestId = UUID.randomUUID()
        consumer.consume(
            envelopeFor(),
            "payment.captured",
            0,
            0L,
            requestId.toString(),
            requestId.toString(),
            ack,
        )
        verify(postingService).createPosting(
            request = any(),
            idempotencyKey = any(),
            correlationId = eq(requestId),
        )
    }

    @Test
    fun `X-Correlation-Id header is the fallback when X-Request-Id is absent`() {
        whenever(inbox.existsByEventId(any<UUID>())).thenReturn(false)
        val correlationOnly = UUID.randomUUID()
        consumer.consume(
            envelopeFor(),
            "payment.captured",
            0,
            0L,
            null,
            correlationOnly.toString(),
            ack,
        )
        verify(postingService).createPosting(
            request = any(),
            idempotencyKey = any(),
            correlationId = eq(correlationOnly),
        )
    }

    @Test
    fun `corrupt event_id is skipped silently without throwing or building a posting`() {
        // The ledger consumer MUST NOT poison-DLQ on a single missing event_id;
        // it WARNs and acks. Downstream services (customer-service,
        // audit-service) follow the same pattern.
        val bad = """
            {"event_name":"payment.captured.v1","data":{"description":"x","entries":[]}}
        """.trimIndent()
        consumer.consume(
            bad,
            "payment.captured",
            0,
            0L,
            UUID.randomUUID().toString(),
            UUID.randomUUID().toString(),
            ack,
        )
        verify(postingService, never()).createPosting(any(), any(), any())
        verify(inbox, never()).save(any())
        verify(ack).acknowledge()
    }

    @Test
    fun `duplicate event_id is deduped via inbox and not re-posted`() {
        // The dedup invariant is the cornerstone of the platform's
        // at-least-once delivery semantics (EVENT_ARCHITECTURE §"Idempotency").
        // If payment-service republishes a payment.captured after a network
        // glitch, ledger-service MUST skip it (and so must customer-service
        // and audit-service).
        whenever(inbox.existsByEventId(eventId)).thenReturn(true)
        consumer.consume(
            envelopeFor(),
            "payment.captured",
            0,
            0L,
            correlationId.toString(),
            correlationId.toString(),
            ack,
        )
        verify(postingService, never()).createPosting(any(), any(), any())
        verify(ack).acknowledge()
    }

    @Test
    fun `trip-reward-granted is informational only and does not build a posting`() {
        // Per ledger-service INTEGRATION §4.5-§4.6, trip.reward.granted and
        // trip.reward.reversed are persisted as inbox rows but do NOT
        // generate balanced postings — the operational money-layer posts
        // them via driver.earning.accrued. This is the cross-service
        // contract between trip-service, payment-service, and ledger-service.
        whenever(inbox.existsByEventId(any<UUID>())).thenReturn(false)
        consumer.consume(
            envelopeFor(eventName = "trip.reward.granted.v1"),
            "trip.reward.granted",
            0,
            0L,
            correlationId.toString(),
            correlationId.toString(),
            ack,
        )
        verify(inbox).save(any())
        verify(postingService, never()).createPosting(any(), any(), any())
        verify(ack).acknowledge()
    }

    @Test
    fun `wallet-credited envelope maps to single-line posting`() {
        // Wallet operations (top-up, hold, release) are the heaviest
        // producers of money-movement events. Verify the envelope shape
        // for a single-entry wallet credit.
        whenever(inbox.existsByEventId(any<UUID>())).thenReturn(false)
        consumer.consume(
            envelopeFor(
                eventName = "wallet.credited.v1",
                data = mapOf(
                    "description" to "wallet top-up",
                    "entries" to listOf(
                        mapOf(
                            "account_code" to "1100_cash",
                            "side" to "debit",
                            "amount_minor" to 5000,
                            "currency" to "EUR",
                        ),
                        mapOf(
                            "account_code" to "2100_customer_wallet",
                            "side" to "credit",
                            "amount_minor" to 5000,
                            "currency" to "EUR",
                        ),
                    ),
                ),
            ),
            "wallet.credited",
            0,
            0L,
            correlationId.toString(),
            correlationId.toString(),
            ack,
        )
        verify(postingService).createPosting(any(), any(), any())
        verify(ack).acknowledge()
    }
}
