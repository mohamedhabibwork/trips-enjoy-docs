package com.trips_enjoy.search.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/**
 * A point-in-time snapshot of OpenSearch cluster health. Mirrors
 * `search.index_health` per docs/services/search-service/ERD.md §3.
 */
@Entity
@Table(name = "index_health", schema = "search")
class IndexHealth(
    @Id val id: UUID,
    @Column(name = "cluster_name", nullable = false) val clusterName: String,
    @Column(nullable = false) val status: String,
    @Column(name = "node_count", nullable = false) val nodeCount: Int = 0,
    @Column(name = "active_shards", nullable = false) val activeShards: Int = 0,
    @Column(name = "unassigned_shards", nullable = false) val unassignedShards: Int = 0,
    @Column(name = "correlation_id", nullable = false) val correlationId: UUID,
    @Column(name = "recorded_at", nullable = false) val recordedAt: Instant = Instant.now(),
) {
    companion object {
        const val STATUS_GREEN = "green"
        const val STATUS_YELLOW = "yellow"
        const val STATUS_RED = "red"
        const val STATUS_UNKNOWN = "unknown"

        val VALID_STATUSES: Set<String> = setOf(STATUS_GREEN, STATUS_YELLOW, STATUS_RED, STATUS_UNKNOWN)
    }

    init {
        require(status in VALID_STATUSES) { "unknown status $status" }
        require(nodeCount >= 0) { "node_count must be >= 0" }
        require(activeShards >= 0) { "active_shards must be >= 0" }
        require(unassignedShards >= 0) { "unassigned_shards must be >= 0" }
    }
}