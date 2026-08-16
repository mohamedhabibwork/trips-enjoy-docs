package com.trips_enjoy.identity.application

import com.fasterxml.jackson.databind.ObjectMapper
import com.trips_enjoy.identity.domain.OutboxEvent
import com.trips_enjoy.identity.domain.OutboxEventRepository
import com.trips_enjoy.identity.util.uuidV7
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.UUID

/**
 * Emits `identity.partition.maintained.v1` to the outbox after each run of
 * the canonical `partman.ensure_partitions` function. Per
 * docs/shared/PARTITION_FUNCTIONS.md §10, every service uses its own
 * namespaced topic and event name. Closes a pre-2026-08-14 gap where the
 * identity-service partition-maintenance job emitted no outbox event.
 */
@Component
class PartitionMaintenanceEventPublisher(
    private val outbox: OutboxEventRepository,
    private val objectMapper: ObjectMapper,
) {
    // Stable aggregate id for "all partition-maintenance events" so the
    // outbox publisher can dedup or replay them.
    private val aggregateId: UUID = UUID(0L, 0L)

    fun emit(parent: String, created: Int, dropped: Int) {
        val payload = objectMapper.writeValueAsString(
            mapOf(
                "event_id" to uuidV7().toString(),
                "event_name" to "identity.partition.maintained.v1",
                "occurred_at" to Instant.now().toString(),
                "schema_version" to 1,
                "producer" to "identity-service",
                "tenant_id" to "global",
                "correlation_id" to uuidV7().toString(),
                "aggregate_type" to "PartitionMaintenance",
                "aggregate_id" to aggregateId.toString(),
                "data" to mapOf(
                    "schema" to "identity",
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
                aggregateId = aggregateId,
                topic = "identity.partition.maintained",
                eventName = "identity.partition.maintained.v1",
                payload = payload,
            ),
        )
    }
}
