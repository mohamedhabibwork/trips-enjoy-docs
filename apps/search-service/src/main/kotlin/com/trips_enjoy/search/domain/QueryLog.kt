package com.trips_enjoy.search.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.time.Instant
import java.util.UUID

/**
 * An executed search query (append-only audit). Mirrors
 * `search.query_log` per docs/services/search-service/ERD.md §3.
 *
 * Append-only (V3 trigger).
 */
@Entity
@Table(name = "query_log", schema = "search")
class QueryLog(
    @Id val id: UUID,
    @Column(name = "tenant_id", nullable = false) var tenantId: String = "global",
    @Column(nullable = false) val vertical: String,
    @Column(name = "query_text", nullable = false) val queryText: String,
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb") var filters: Map<String, Any?>? = null,
    @Column(name = "result_count", nullable = false) var resultCount: Int = 0,
    @Column(name = "duration_ms", nullable = false) var durationMs: Int = 0,
    @Column(name = "actor_kc_sub") var actorKcSub: UUID? = null,
    @Column(name = "actor_kind", nullable = false) var actorKind: String = "rider",
    @Column(name = "correlation_id", nullable = false) var correlationId: UUID = UUID.randomUUID(),
    @Column(name = "occurred_at", nullable = false) val occurredAt: Instant = Instant.now(),
) {
    companion object {
        const val ACTOR_RIDER = "rider"
        const val ACTOR_DRIVER = "driver"
        const val ACTOR_ADMIN = "admin"
        const val ACTOR_SYSTEM = "system"
        const val ACTOR_MERCHANT = "merchant"

        val VALID_ACTOR_KINDS: Set<String> = setOf(
            ACTOR_RIDER, ACTOR_DRIVER, ACTOR_ADMIN, ACTOR_SYSTEM, ACTOR_MERCHANT,
        )
    }

    init {
        require(actorKind in VALID_ACTOR_KINDS) { "unknown actor_kind $actorKind" }
    }
}