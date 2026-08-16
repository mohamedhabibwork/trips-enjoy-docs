package com.trips_enjoy.ledger.application

import com.fasterxml.jackson.databind.ObjectMapper
import com.trips_enjoy.ledger.domain.OutboxEvent
import com.trips_enjoy.ledger.domain.OutboxEventRepository
import com.trips_enjoy.ledger.util.uuidV7
import org.springframework.stereotype.Component
import java.time.Instant

/**
 * Emits `ledger.partition.maintained.v1` to the outbox after each run of
 * the canonical `partman.ensure_partitions` function. Per
 * docs/shared/PARTITION_FUNCTIONS.md §10, every service uses its own
 * namespaced topic and event name.
 *
 * **Bug fix (2026-08-14)**: the previous in-line implementation in
 * `PartitionMaintenanceJob` emitted `audit.partition.maintained.v1` on
 * topic `audit.partition.maintained`, which routed ledger partition
 * maintenance events through the audit consumer pipeline. This publisher
 * corrects both the topic and the event name.
 */
@Component
class PartitionMaintenanceEventPublisher(
    private val outbox: OutboxEventRepository,
    private val objectMapper: ObjectMapper,
) {
    fun emit(parent: String, created: Int, dropped: Int) {
        val payload = objectMapper.writeValueAsString(
            mapOf(
                "event_id" to uuidV7().toString(),
                "event_name" to "ledger.partition.maintained.v1",
                "occurred_at" to Instant.now().toString(),
                "schema_version" to 1,
                "producer" to "ledger-service",
                "tenant_id" to "global",
                "correlation_id" to uuidV7().toString(),
                "aggregate_type" to "PartitionMaintenance",
                "aggregate_id" to null,
                "data" to mapOf(
                    "schema" to "ledger",
                    "parent" to parent,
                    "created" to created,
                    "dropped" to dropped,
                ),
            ),
        )
        outbox.save(
            OutboxEvent(
                id = uuidV7(),
                aggregateType = "PartitionMaintenance",
                aggregateId = null,
                topic = "ledger.partition.maintained",
                eventName = "ledger.partition.maintained.v1",
                payload = payload,
            ),
        )
    }
}
