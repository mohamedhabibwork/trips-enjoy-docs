package com.trips_enjoy.trip.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.time.Instant
import java.util.UUID

/**
 * Idempotency-Key middleware. Mirrors `trip.idempotency_record` per
 * docs/services/trip-service/ERD.md §3. Uses the legacy scope+key
 * composite unique index pattern (per the prior 9 graduates).
 */
@Entity
@Table(name = "idempotency_record", schema = "trip")
class IdempotencyRecord(
    @Id val id: UUID,
    @Column(nullable = false) val scope: String,
    @Column(name = "idem_key", nullable = false) val idemKey: String,
    @Column(name = "request_hash", nullable = false) val requestHash: String,
    @Column(name = "response_status") var responseStatus: Int? = null,
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "response_body", columnDefinition = "jsonb") var responseBody: Map<String, Any?>? = null,
    @Column(name = "locked_at", nullable = false) val lockedAt: Instant = Instant.now(),
    @Column(name = "completed_at") var completedAt: Instant? = null,
    @Column(name = "created_by", nullable = false) val createdBy: UUID,
) {
    companion object {
        const val SCOPE_TRIP_REQUEST = "trip_request"
        const val SCOPE_TRIP_CANCEL = "trip_cancel"
        const val SCOPE_TRIP_COMPLETE = "trip_complete"
        const val SCOPE_TRIP_RATE = "trip_rate"
        const val SCOPE_TRIP_REWARD_RE_EVALUATE = "trip_reward_re_evaluate"
        const val SCOPE_TRIP_REWARD_REVERSE = "trip_reward_reverse"
        const val SCOPE_TRIP_LOCATION_PING = "trip_location_ping"
        const val SCOPE_TRIP_STOP = "trip_stop"
        const val SCOPE_TRIP_START = "trip_start"
        const val SCOPE_TRIP_ARRIVE = "trip_arrive"
        const val SCOPE_TRIP_DROPOFF = "trip_dropoff"

        val VALID_SCOPES: Set<String> = setOf(
            SCOPE_TRIP_REQUEST, SCOPE_TRIP_CANCEL, SCOPE_TRIP_COMPLETE,
            SCOPE_TRIP_RATE, SCOPE_TRIP_REWARD_RE_EVALUATE, SCOPE_TRIP_REWARD_REVERSE,
            SCOPE_TRIP_LOCATION_PING, SCOPE_TRIP_STOP, SCOPE_TRIP_START,
            SCOPE_TRIP_ARRIVE, SCOPE_TRIP_DROPOFF,
        )
    }

    init {
        require(scope in VALID_SCOPES) { "unknown scope $scope" }
        require(idemKey.length in 8..200) { "idem_key length must be 8..200" }
        require(requestHash.length == 64) { "request_hash must be SHA-256 hex (64 chars)" }
    }

    fun isCompleted(): Boolean = completedAt != null

    fun recordResponse(status: Int, body: Map<String, Any?>, at: Instant) {
        check(!isCompleted()) { "idempotency response already recorded" }
        responseStatus = status
        responseBody = body
        completedAt = at
    }
}