package com.trips_enjoy.courier.domain

import com.trips_enjoy.platform.data.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/**
 * A courier's scheduled shift block. Mirrors `courier.courier_shifts`
 * per docs/services/courier-service/ERD.md §3. The shift lifecycle:
 *   scheduled → active → completed
 *              ↘ cancelled (from any state)
 *
 * The unique partial index on `(courier_id) WHERE status = 'active'`
 * enforces "at most one active shift per courier" at the DB level
 * (the dispatch saga relies on this).
 *
 * Phase C (platform DRY): extends [BaseEntity]. See V6 migration
 * (`created_by` / `updated_by` `UUID` → `VARCHAR(255)`,
 * `row_version` → `version`).
 */
@Entity
@Table(name = "courier_shifts", schema = "courier")
class CourierShift(
    @Column(name = "courier_id", nullable = false) val courierId: UUID,
    @Column(name = "start_at", nullable = false) val startAt: Instant,
    @Column(name = "end_at", nullable = false) val endAt: Instant,
    @Column(name = "actual_start_at") var actualStartAt: Instant? = null,
    @Column(name = "actual_end_at") var actualEndAt: Instant? = null,
    @Column(nullable = false) var status: String = STATUS_SCHEDULED,
    @Column(name = "cancelled_reason") var cancelledReason: String? = null,
) : BaseEntity() {
    companion object {
        const val STATUS_SCHEDULED = "scheduled"
        const val STATUS_ACTIVE = "active"
        const val STATUS_COMPLETED = "completed"
        const val STATUS_CANCELLED = "cancelled"

        val VALID_STATUSES: Set<String> = setOf(
            STATUS_SCHEDULED, STATUS_ACTIVE, STATUS_COMPLETED, STATUS_CANCELLED,
        )
    }

    init {
        require(endAt.isAfter(startAt)) { "shift end_at must be after start_at" }
        require(status in VALID_STATUSES) { "unknown shift status $status" }
    }

    fun activate(actualStart: Instant) {
        check(status == STATUS_SCHEDULED) { "cannot activate shift in status $status" }
        require(!actualStart.isBefore(startAt)) { "actual_start cannot be before planned start" }
        status = STATUS_ACTIVE
        actualStartAt = actualStart
        updatedAt = actualStart
        version += 1
    }

    fun complete(actualEnd: Instant) {
        check(status == STATUS_ACTIVE) { "cannot complete shift in status $status" }
        require(actualEnd.isAfter(actualStartAt ?: startAt)) {
            "actual_end must be after actual_start"
        }
        status = STATUS_COMPLETED
        actualEndAt = actualEnd
        updatedAt = actualEnd
        version += 1
    }

    fun cancel(reason: String, at: Instant) {
        check(status in setOf(STATUS_SCHEDULED, STATUS_ACTIVE)) {
            "cannot cancel shift in status $status"
        }
        require(reason.isNotBlank()) { "cancellation reason required" }
        status = STATUS_CANCELLED
        cancelledReason = reason
        actualEndAt = at
        updatedAt = at
        version += 1
    }
}
