package com.trips_enjoy.audit.application

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import jakarta.annotation.PostConstruct
import org.apache.kafka.common.TopicPartition
import org.springframework.stereotype.Component
import java.util.concurrent.ConcurrentHashMap

/**
 * Audit-service ingestion metrics per `docs/services/audit-service/SRS.md`
 * §22 + README §15 + INTEGRATION §3.2.
 *
 *  - `audit_events_ingested_total{topic}` — every successfully-persisted
 *    event bumps this counter (FR--001–FR--006 contract surface).
 *  - `audit_consumer_lag{topic,partition}` — registered as a Micrometer
 *    Gauge backed by a per-partition map. The Kafka consumer pushes the
 *    last-seen offset after each successful ingest; a separate scheduler
 *    could compute true broker-side lag (offset diff vs. high-water-mark)
 *    in a future iteration. For now the gauge records ingest offset as
 *    a proxy and the alert query uses the value as a relative signal.
 */
@Component
class IngestionMetrics(private val registry: MeterRegistry) {

    private val topicCounters = ConcurrentHashMap<String, Counter>()
    private val partitionLag = ConcurrentHashMap<TopicPartition, java.util.concurrent.atomic.AtomicLong>()

    @PostConstruct
    fun registerWellKnownGauges() {
        // Hash-chain status gauge (1 = verified, 0 = mismatch). Default 1
        // until the first verify() runs so dashboards never show empty
        // until a manual verify.
        registry.gauge("audit_hash_chain_status", io.micrometer.core.instrument.Tags.empty(), 1.0) {
            lastChainStatus.get().toDouble()
        }
        // Eagerly register the per-topic ingest counter with a value of 0
        // so dashboards have a stable time series from t=0 instead of an
        // empty window until the first event lands. The set of topics
        // mirrors INTEGRATION.md §4.1 — new topics are added by code,
        // not configuration, so the list is bounded.
        val bootstrapTopics = listOf(
            "admin.action.performed",
            "payment.captured",
            "trip.completed",
            "ledger.posted",
            "wallet.credited",
            "pricing.quote.created",
            "configuration.updated",
            "identity.user.created",
            "customer.suspended",
            "delivery.completed",
            "fraud.risk.scored",
            "notification.sent",
            "file.uploaded",
            "support.ticket.opened",
        )
        bootstrapTopics.forEach { topic -> ensureRegistered(topic) }
    }

    /** Registers the per-topic counter at 0 if not already present. */
    private fun ensureRegistered(topic: String) {
        topicCounters.computeIfAbsent(topic) {
            Counter.builder("audit_events_ingested_total")
                .description("Total number of audit-relevant events successfully ingested per topic")
                .tag("topic", it)
                .register(registry)
        }
    }

    /** Bumps the per-topic ingested counter after a successful insert. */
    fun record(topic: String) {
        topicCounters.computeIfAbsent(topic) {
            Counter.builder("audit_events_ingested_total")
                .description("Total number of audit-relevant events successfully ingested per topic")
                .tag("topic", it)
                .register(registry)
        }.increment()
    }

    /** Records the last-seen offset for a (topic, partition). */
    fun recordOffset(topic: String, partition: Int, offset: Long) {
        val key = TopicPartition(topic, partition)
        partitionLag.computeIfAbsent(key) {
            val atomic = java.util.concurrent.atomic.AtomicLong(offset)
            registry.gauge(
                "audit_consumer_lag",
                io.micrometer.core.instrument.Tags.of("topic", topic, "partition", partition.toString()),
                atomic,
                java.util.function.ToDoubleFunction<java.util.concurrent.atomic.AtomicLong> { it.get().toDouble() },
            )
            atomic
        }.set(offset)
    }

    /** Returns the most recently recorded offset for a (topic, partition). */
    fun lastOffset(topic: String, partition: Int): Long =
        partitionLag[TopicPartition(topic, partition)]?.get() ?: -1L

    /** Update the hash-chain status (1 = ok, 0 = tamper). */
    fun setChainStatus(verified: Boolean) {
        lastChainStatus.set(if (verified) 1L else 0L)
    }

    private val lastChainStatus = java.util.concurrent.atomic.AtomicLong(1)
}
