package com.trips_enjoy.configuration.application

import com.fasterxml.jackson.databind.ObjectMapper
import com.trips_enjoy.configuration.domain.OutboxEvent
import com.trips_enjoy.configuration.domain.OutboxRepository
import com.trips_enjoy.configuration.util.uuidV7
import org.springframework.stereotype.Component
import java.time.Instant

/**
 * Emits `configuration.partition.maintained.v1` to the outbox after each
 * run of the canonical `partman.ensure_partitions` function. Per
 * docs/shared/PARTITION_FUNCTIONS.md §10, every service uses its own
 * namespaced topic and event name.
 *
 * Closes a pre-2026-08-14 gap where the configuration-service
 * partition-maintenance job emitted no outbox event at all.
 */
@Component
class PartitionMaintenanceEventPublisher(
    private val outbox: OutboxRepository,
    private val objectMapper: ObjectMapper,
) {
    fun emit(
        parent: String,
        created: Int,
        dropped: Int,
    ) {
        val payload =
            objectMapper.writeValueAsString(
                mapOf(
                    "event_id" to uuidV7().toString(),
                    "event_name" to "configuration.partition.maintained.v1",
                    "occurred_at" to Instant.now().toString(),
                    "schema_version" to 1,
                    "producer" to "configuration-service",
                    "tenant_id" to "global",
                    "correlation_id" to uuidV7().toString(),
                    "aggregate_type" to "PartitionMaintenance",
                    "aggregate_id" to "configuration",
                    "data" to
                        mapOf(
                            "schema" to "configuration",
                            "parent" to parent,
                            "created" to created,
                            "dropped" to dropped,
                        ),
                ),
            )
        outbox.save(
            OutboxEvent(
                id = uuidV7(),
                eventId = uuidV7(),
                topic = "configuration.partition.maintained",
                payload = payload,
            ),
        )
    }
}
