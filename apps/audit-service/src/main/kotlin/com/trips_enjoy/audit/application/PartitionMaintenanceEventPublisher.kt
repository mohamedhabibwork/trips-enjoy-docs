package com.trips_enjoy.audit.application

import com.fasterxml.jackson.databind.ObjectMapper
import com.trips_enjoy.audit.domain.OutboxEvent
import com.trips_enjoy.audit.domain.OutboxEventRepository
import com.trips_enjoy.audit.util.uuidV7
import org.springframework.stereotype.Component
import java.time.Instant

/**
 * Emits `audit.partition.maintained.v1` to the outbox after each run of
 * the canonical `partman.ensure_partitions` function. Per
 * docs/shared/PARTITION_FUNCTIONS.md §10, every service uses its own
 * namespaced topic and event name (this fixes the historical bug where
 * the ledger job emitted under `audit.partition.maintained.v1`).
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
                "event_name" to "audit.partition.maintained.v1",
                "occurred_at" to Instant.now().toString(),
                "schema_version" to 1,
                "producer" to "audit-service",
                "tenant_id" to "global",
                "correlation_id" to uuidV7().toString(),
                "aggregate_type" to "PartitionMaintenance",
                "aggregate_id" to "audit",
                "data" to mapOf(
                    "schema" to "audit",
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
                topic = "audit.partition.maintained",
                eventName = "audit.partition.maintained.v1",
                payload = payload,
            ),
        )
    }
}
