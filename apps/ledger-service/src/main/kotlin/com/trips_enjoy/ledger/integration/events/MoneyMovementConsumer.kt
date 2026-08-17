package com.trips_enjoy.ledger.integration.events

import com.fasterxml.jackson.databind.ObjectMapper
import com.trips_enjoy.ledger.api.CreatePostingRequest
import com.trips_enjoy.ledger.api.PostingEntryDto
import com.trips_enjoy.ledger.application.PostingService
import com.trips_enjoy.ledger.domain.InboxEvent
import com.trips_enjoy.ledger.domain.InboxEventRepository
import org.slf4j.LoggerFactory
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.kafka.support.Acknowledgment
import org.springframework.kafka.support.KafkaHeaders
import org.springframework.messaging.handler.annotation.Header
import org.springframework.messaging.handler.annotation.Payload
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.UUID

/**
 * Async consumer for the money-movement topics documented in
 * docs/services/ledger-service/INTEGRATION §4. Idempotent on `event_id`
 * (SRS §15) — the `inbox` row dedups before the posting is built.
 *
 * DLQ behaviour: poison events are routed to `<topic>.dlq` by the
 * container error handler (see KafkaConsumerConfiguration).
 */
@Component
class MoneyMovementConsumer(
    private val objectMapper: ObjectMapper,
    private val inbox: InboxEventRepository,
    private val postingService: PostingService,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @KafkaListener(
        topics = [
            "payment.captured",
            "payment.refund.completed",
            "wallet.credited",
            "wallet.debited",
            "wallet.held",
            "wallet.released",
            "wallet.captured",
            "merchant.settlement.accrued",
            "merchant.payout.completed",
            "courier.earning.accrued",
            "courier.withdrawal.completed",
            "driver.earning.accrued",
            "driver.withdrawal.completed",
            // Informational only (per INTEGRATION §4.5-§4.6): no balancing posting.
            "trip.reward.granted",
            "trip.reward.reversed",
            "configuration.updated",
        ],
        groupId = "\${ledger-service.consumer.group-id:ledger-service}",
        containerFactory = "ledgerKafkaListenerContainerFactory",
    )
    fun consume(
        @Payload payload: String,
        @Header(KafkaHeaders.RECEIVED_TOPIC) topic: String,
        @Header(KafkaHeaders.RECEIVED_PARTITION) partition: Int,
        @Header(KafkaHeaders.OFFSET) offset: Long,
        @Header(value = "X-Request-Id", required = false) requestId: String?,
        @Header(value = "X-Correlation-Id", required = false) correlationHeader: String?,
        ack: Acknowledgment,
    ) {
        try {
            val correlationId = correlationId(requestId, correlationHeader)
            val envelope = objectMapper.readValue(payload, MAP_TYPE)
            val eventId = parseUuid(envelope["event_id"])
                ?: run {
                    log.warn("Skipping event on topic={} — missing event_id", topic)
                    ack.acknowledge()
                    return
                }

            // Dedup on event_id (idempotency per SRS §15).
            if (inbox.existsByEventId(eventId)) {
                log.debug("event_id={} on topic={} already processed; skipping", eventId, topic)
                ack.acknowledge()
                return
            }
            inbox.save(
                InboxEvent(
                    eventId = eventId,
                    topic = topic,
                ),
            )

            when (topic) {
                "configuration.updated" -> {
                    // Hot-reload configuration (placeholder — real implementation reads
                    // configuration-service on startup and reloads on this event).
                    log.info("configuration.updated received; ledger-service config reload (no-op)")
                }
                "trip.reward.granted",
                "trip.reward.reversed" -> {
                    // Informational only — the operational postings flow through
                    // driver earnings / wallet services. The ledger persists the event
                    // for audit and runs the daily reconciliation against the
                    // operational layer.
                    log.info("{} received; informational persistence only", topic)
                }
                else -> {
                    val posting = mapToPosting(envelope, topic, correlationId)
                    postingService.createPosting(posting, idempotencyKey(eventId), correlationId)
                }
            }
            ack.acknowledge()
        } catch (exception: Exception) {
            log.warn("Ledger consumer failed for topic={} partition={} offset={}: {}",
                topic, partition, offset, exception.message)
            // Rethrow so the container error handler routes to DLQ + retries.
            throw exception
        }
    }

    /**
     * Map a money-movement event envelope to a balanced [CreatePostingRequest].
     * Producers publish the entries as part of the event `data`; consumers
     * record the mapping here.
     */
    private fun mapToPosting(envelope: Map<String, Any?>, topic: String, correlationId: UUID): CreatePostingRequest {
        @Suppress("UNCHECKED_CAST")
        val data = envelope["data"] as? Map<String, Any?> ?: emptyMap()
        val eventId = parseUuid(envelope["event_id"]) ?: UUID.randomUUID()
        val description = (data["description"] as? String) ?: topic
        @Suppress("UNCHECKED_CAST")
        val rawEntries = (data["entries"] as? List<Map<String, Any?>>) ?: emptyList()
        val entries = rawEntries.map { e ->
            PostingEntryDto(
                account_code = e["account_code"] as String,
                side = e["side"] as String,
                amount_minor = (e["amount_minor"] as Number).toLong(),
                currency = (e["currency"] as String),
            )
        }
        return CreatePostingRequest(
            description = description,
            posted_at = parseInstant(envelope["occurred_at"]) ?: Instant.now(),
            source_event_id = eventId,
            source_event_name = envelope["event_name"] as? String ?: topic,
            entries = entries,
        )
    }

    private fun parseUuid(value: Any?): UUID? = runCatching {
        when (value) {
            is UUID -> value
            is String -> UUID.fromString(value)
            else -> null
        }
    }.getOrNull()

    private fun parseInstant(value: Any?): java.time.Instant? = runCatching {
        when (value) {
            is java.time.Instant -> value
            is String -> java.time.Instant.parse(value)
            else -> null
        }
    }.getOrNull()

    private fun correlationId(requestId: String?, correlationHeader: String?): UUID {
        val raw = requestId ?: correlationHeader
        return runCatching { UUID.fromString(raw) }.getOrNull() ?: UUID.randomUUID()
    }

    private fun idempotencyKey(eventId: UUID): String = "ledger:event:$eventId"

    private companion object {
        private val MAP_TYPE = objectMapperType()
        private fun objectMapperType(): com.fasterxml.jackson.core.type.TypeReference<Map<String, Any?>> =
            object : com.fasterxml.jackson.core.type.TypeReference<Map<String, Any?>>() {}
    }
}
