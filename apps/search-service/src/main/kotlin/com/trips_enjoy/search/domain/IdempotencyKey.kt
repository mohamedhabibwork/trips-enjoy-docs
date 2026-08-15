package com.trips_enjoy.search.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "idempotency_keys", schema = "search")
class IdempotencyKey(
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
        const val SCOPE_SEARCH_QUERY = "search_query"
        const val SCOPE_REINDEX_START = "reindex_start"
        const val SCOPE_RELEVANCE_UPDATE = "relevance_update"

        val VALID_SCOPES: Set<String> = setOf(
            SCOPE_SEARCH_QUERY, SCOPE_REINDEX_START, SCOPE_RELEVANCE_UPDATE,
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