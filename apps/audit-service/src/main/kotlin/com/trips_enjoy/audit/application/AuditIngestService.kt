package com.trips_enjoy.audit.application

import com.fasterxml.jackson.databind.ObjectMapper
import com.trips_enjoy.audit.domain.AuditEvent
import com.trips_enjoy.audit.domain.AuditEventRepository
import com.trips_enjoy.audit.domain.InboxEvent
import com.trips_enjoy.audit.domain.InboxEventRepository
import com.trips_enjoy.audit.domain.RetentionClass
import com.trips_enjoy.audit.util.HashChain
import com.trips_enjoy.audit.util.uuidV7
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * Ingest pipeline for audit-service. Implements WORKFLOWS §1 ("Consume an
 * audit-relevant event") + WORKFLOWS §1.5 happy path:
 *
 *   1. dedup on `event_id` via the inbox (SRS §15)
 *   2. serialize on the hash-chain tip (`SELECT ... FOR UPDATE` per SRS §14)
 *   3. compute the next hash from `prev_hash` and the canonical event payload
 *   4. insert into `audit.events` and the inbox in the same transaction
 *   5. write a domain event to the outbox for operational metrics
 */
@Service
class AuditIngestService(
    private val events: AuditEventRepository,
    private val inbox: InboxEventRepository,
    private val objectMapper: ObjectMapper,
    private val metrics: IngestionMetrics,
    @Value("\${audit-service.hash.algo:sha256}") private val hashAlgo: String,
    @Value("\${audit-service.retention.financial-years:7}") private val financialYears: Int,
    @Value("\${audit-service.retention.default-years:1}") private val defaultYears: Int,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** Per INTEGRATION §4.1 — topic prefixes that always carry 7-year retention. */
    private val financialPrefixes = setOf(
        "payment.",
        "wallet.",
        "ledger.",
        "trip.reward.",
        "pricing.rating_density.",
        "pricing.loyalty_discount.",
        "pricing.geo_config.",
        "tax.",
        "promotion.",
        "loyalty.",
        "driver.incentive.",
        "driver.earning.",
        "courier.earning.",
        "merchant.",
        "restaurant.",
        "admin.action.",
        "audit.admin.",
        "audit.security.",
        "audit.retention.",
    )

    data class IngestResult(val stored: Boolean, val reason: String? = null)

    /**
     * Ingest an envelope payload (already deserialized to the canonical Kafka
     * JSON shape). Returns `stored=false` when the event was deduplicated by
     * the inbox.
     */
    @Transactional
    fun ingest(
        envelope: Map<String, Any?>,
        topic: String,
        partition: Int,
        offset: Long,
        headers: Map<String, Any?>?,
    ): IngestResult {
        val eventId = uuidFromEnvelope(envelope, "event_id")
            ?: return IngestResult(stored = false, reason = "missing event_id")
        if (inbox.existsByEventId(eventId)) {
            log.debug("Inbox hit; skipping duplicate event {}", eventId)
            return IngestResult(stored = false, reason = "duplicate")
        }

        val prev = events.lockLatest().firstOrNull()
        val prevHash = prev?.hash

        val eventName = envelope["event_name"] as? String ?: topic
        val schemaVersion = (envelope["schema_version"] as? Number)?.toInt() ?: 1
        val occurredAt = parseOccurredAt(envelope["occurred_at"]) ?: Instant.now()
        val producer = envelope["producer"] as? String ?: "unknown"
        val tenantId = envelope["tenant_id"] as? String
            ?: throw IllegalArgumentException("tenant_id is required")
        val correlationId = uuidFromEnvelope(envelope, "correlation_id") ?: UUID.randomUUID()
        val causationId = uuidFromEnvelope(envelope, "causation_id")
        val aggregateType = envelope["aggregate_type"] as? String
            ?: deriveAggregateType(eventName)
        val aggregateId = uuidFromEnvelope(envelope, "aggregate_id")
        val data = envelope["data"] ?: emptyMap<String, Any?>()
        val dataJson = objectMapper.writeValueAsString(data)

        val canonical = HashChain.canonicalize(
            eventId = eventId.toString(),
            eventName = eventName,
            schemaVersion = schemaVersion,
            occurredAtIso = occurredAt.toString(),
            producer = producer,
            tenantId = tenantId,
            correlationId = correlationId.toString(),
            aggregateType = aggregateType,
            aggregateId = aggregateId?.toString(),
            subjectType = subjectType(eventName, envelope),
            subjectId = subjectId(eventName, envelope, aggregateId)?.toString(),
            dataJson = dataJson,
        )
        val hash = HashChain.nextHash(prevHash, canonical, hashAlgo)

        val retentionClass = RetentionClass.fromWire(
            if (financialPrefixes.any { eventName.startsWith(it) }) "financial" else "default",
        )
        val retentionUntil = Instant.now().plus(
            Duration.ofDays(365L * (if (retentionClass == RetentionClass.FINANCIAL) financialYears else defaultYears)),
        )

        val auditEvent = AuditEvent(
            id = uuidV7(),
            eventId = eventId,
            eventName = eventName,
            schemaVersion = schemaVersion,
            occurredAt = occurredAt,
            receivedAt = Instant.now(),
            producer = producer,
            tenantId = tenantId,
            correlationId = correlationId,
            causationId = causationId,
            aggregateType = aggregateType,
            aggregateId = aggregateId,
            subjectType = subjectType(eventName, envelope),
            subjectId = subjectId(eventName, envelope, aggregateId),
            data = dataJson,
            headers = headers?.let { objectMapper.writeValueAsString(it) },
            topic = topic,
            partition = partition,
            offset = offset,
            prevHash = prevHash,
            hash = hash,
            retentionClass = retentionClass.value,
            litigationHold = false,
            retentionUntil = retentionUntil,
            createdAt = Instant.now(),
        )
        events.save(auditEvent)
        inbox.save(InboxEvent(eventId = eventId, topic = topic, receivedAt = Instant.now()))
        metrics.record(topic)
        metrics.recordOffset(topic, partition, offset)
        return IngestResult(stored = true)
    }

    private fun deriveAggregateType(eventName: String): String {
        val parts = eventName.split(".")
        return if (parts.size >= 2) parts.dropLast(1).joinToString(" ").replaceFirstChar { it.uppercase() }
        else eventName.replaceFirstChar { it.uppercase() }
    }

    /**
     * Per INTEGRATION §4.4 the trip-service publishes `subject_type` /
     * `subject_id` denormalized for trip-completed; otherwise we fall back to
     * the aggregate_id so subject search still has a stable answer.
     */
    private fun subjectType(eventName: String, envelope: Map<String, Any?>): String? {
        when (eventName) {
            "trip.completed.v1", "trip.started.v1", "trip.arrived.v1", "trip.cancelled.v1" -> return "trip"
            "customer.created.v1", "customer.suspended.v1" -> return "customer"
            "driver.created.v1", "driver.suspended.v1" -> return "driver"
            "courier.created.v1", "courier.suspended.v1" -> return "courier"
            "merchant.created.v1" -> return "merchant"
            "restaurant.created.v1" -> return "restaurant"
            "identity.user.created.v1", "identity.user.suspended.v1" -> return "identity"
            "payment.captured.v1", "payment.refund.completed.v1" -> return "payment_intent"
            "ledger.posted.v1" -> return "ledger_entry"
            "notification.sent.v1", "notification.failed.v1" -> return "notification"
        }
        val data = envelope["data"] as? Map<*, *> ?: return null
        return (data["subject_type"] as? String) ?: (envelope["aggregate_type"] as? String)
    }

    private fun subjectId(eventName: String, envelope: Map<String, Any?>, aggregateId: UUID?): UUID? {
        val data = envelope["data"] as? Map<*, *> ?: return aggregateId
        return uuidFromMap(data, "subject_id") ?: uuidFromMap(data, "id") ?: aggregateId
    }

    private fun uuidFromEnvelope(envelope: Map<String, Any?>, key: String): UUID? = uuidFromMap(envelope, key)

    private fun uuidFromMap(map: Map<*, *>, key: String): UUID? = when (val raw = map[key]) {
        is UUID -> raw
        is String -> runCatching { UUID.fromString(raw) }.getOrNull()
        else -> null
    }

    private fun parseOccurredAt(raw: Any?): Instant? = when (raw) {
        is Instant -> raw
        is String -> runCatching { Instant.parse(raw) }.getOrNull()
        else -> null
    }
}
