package com.trips_enjoy.search.domain

import com.trips_enjoy.platform.data.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/**
 * A reindex job (cursor for backfilling OpenSearch). Mirrors
 * `search.reindex_job` per docs/services/search-service/ERD.md §3.
 *
 * Single-UUID PK (NOT composite) per the lift-forward pattern
 * adopted after the admin-service @EmbeddedId blocker.
 *
 * Phase C (platform DRY): extends [BaseEntity] so the `id`, `createdAt`,
 * `updatedAt`, `createdBy`, `updatedBy`, `version`, and `deletedAt` columns
 * are inherited from the platform canonical shape. The corresponding
 * column migration is V6 (`created_by` / `updated_by` `UUID` →
 * `VARCHAR(255)`, `row_version` → `version`, plus `deleted_at`).
 */
@Entity
@Table(name = "reindex_job", schema = "search")
class ReindexJob(
    @Column(name = "tenant_id", nullable = false) var tenantId: String = "global",
    @Column(nullable = false) var vertical: String,
    @Column(nullable = false) var scope: String = "all",
    @Column(nullable = false) var state: String = STATE_PENDING,
    @Column(name = "total_docs", nullable = false) var totalDocs: Long = 0L,
    @Column(name = "processed_docs", nullable = false) var processedDocs: Long = 0L,
    @Column(name = "failed_docs", nullable = false) var failedDocs: Long = 0L,
    @Column(name = "started_at") var startedAt: Instant? = null,
    @Column(name = "completed_at") var completedAt: Instant? = null,
    @Column(name = "error_message") var errorMessage: String? = null,
    @Column(name = "requested_by", nullable = false) val requestedBy: UUID,
    @Column(name = "correlation_id", nullable = false) var correlationId: UUID = UUID.randomUUID(),
) : BaseEntity() {
    companion object {
        const val VERTICAL_RESTAURANTS = "restaurants"
        const val VERTICAL_MENU_ITEMS = "menu_items"
        const val VERTICAL_MERCHANTS = "merchants"
        const val VERTICAL_TICKETS = "tickets"

        const val STATE_PENDING = "pending"
        const val STATE_RUNNING = "running"
        const val STATE_COMPLETED = "completed"
        const val STATE_FAILED = "failed"
        const val STATE_CANCELLED = "cancelled"

        val VALID_VERTICALS: Set<String> = setOf(
            VERTICAL_RESTAURANTS, VERTICAL_MENU_ITEMS,
            VERTICAL_MERCHANTS, VERTICAL_TICKETS, "all",
        )
        val VALID_STATES: Set<String> = setOf(
            STATE_PENDING, STATE_RUNNING, STATE_COMPLETED,
            STATE_FAILED, STATE_CANCELLED,
        )
    }

    init {
        require(vertical in VALID_VERTICALS) { "unknown vertical $vertical" }
        require(state in VALID_STATES) { "unknown state $state" }
    }

    fun start(at: Instant) {
        check(state == STATE_PENDING) { "cannot start reindex job in state $state" }
        state = STATE_RUNNING
        startedAt = at
        updatedAt = at
    }

    fun complete(at: Instant) {
        check(state == STATE_RUNNING) { "cannot complete reindex job in state $state" }
        state = STATE_COMPLETED
        completedAt = at
        updatedAt = at
    }

    fun fail(errorMessage: String, at: Instant) {
        check(state != STATE_COMPLETED) { "cannot fail a completed reindex job" }
        state = STATE_FAILED
        this.errorMessage = errorMessage
        completedAt = at
        updatedAt = at
    }

    fun cancel(at: Instant) {
        check(state !in setOf(STATE_COMPLETED, STATE_FAILED)) {
            "cannot cancel terminal reindex job"
        }
        state = STATE_CANCELLED
        completedAt = at
        updatedAt = at
    }
}
