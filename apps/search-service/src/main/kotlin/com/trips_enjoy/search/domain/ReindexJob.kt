package com.trips_enjoy.search.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/**
 * A reindex job (cursor for backfilling OpenSearch). Mirrors
 * `search.reindex_job` per docs/services/search-service/ERD.md §3.
 *
 * Single-UUID PK (NOT composite) per the lift-forward pattern
 * adopted after the admin-service @EmbeddedId blocker.
 */
@Entity
@Table(name = "reindex_job", schema = "search")
class ReindexJob(
    @Id val id: UUID,
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
    @Column(name = "created_at", nullable = false) val createdAt: Instant = Instant.now(),
    @Column(name = "updated_at", nullable = false) var updatedAt: Instant = Instant.now(),
    @Column(name = "row_version", nullable = false) var rowVersion: Long = 1L,
    @Column(name = "created_by", nullable = false) val createdBy: UUID,
    @Column(name = "updated_by", nullable = false) var updatedBy: UUID = createdBy,
) {
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
        rowVersion += 1
    }

    fun complete(at: Instant) {
        check(state == STATE_RUNNING) { "cannot complete reindex job in state $state" }
        state = STATE_COMPLETED
        completedAt = at
        updatedAt = at
        rowVersion += 1
    }

    fun fail(errorMessage: String, at: Instant) {
        check(state != STATE_COMPLETED) { "cannot fail a completed reindex job" }
        state = STATE_FAILED
        this.errorMessage = errorMessage
        completedAt = at
        updatedAt = at
        rowVersion += 1
    }

    fun cancel(at: Instant) {
        check(state !in setOf(STATE_COMPLETED, STATE_FAILED)) {
            "cannot cancel terminal reindex job"
        }
        state = STATE_CANCELLED
        completedAt = at
        updatedAt = at
        rowVersion += 1
    }
}