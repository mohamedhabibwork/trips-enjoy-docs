package com.trips_enjoy.audit.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.IdClass
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.io.Serializable
import java.time.Instant
import java.util.UUID

/**
 * Append-only access log of every read on the audit service (ERD §3 `ReadLog`).
 *
 * Composite PK mirrors the partitioned-parent rule; see `AuditEvent` for context.
 */
@Entity
@Table(name = "read_log", schema = "audit")
@IdClass(AuditReadLog.Pk::class)
class AuditReadLog(
    @Id
    val id: UUID,

    @Column(name = "actor_id", nullable = false)
    val actorId: UUID,

    @Column(name = "actor_ip", columnDefinition = "inet")
    val actorIp: String? = null,

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    val query: String,

    @Column(name = "result_count", nullable = false)
    val resultCount: Int,

    @Column(nullable = false)
    val reason: String,

    @Column(name = "correlation_id", nullable = false)
    val correlationId: UUID,

    @Id
    @Column(name = "created_at", nullable = false)
    val createdAt: Instant = Instant.now(),
) {
    data class Pk(val id: UUID, val createdAt: Instant) : Serializable {
        companion object {
            @Suppress("unused")
            @java.io.Serial
            private const val serialVersionUID: Long = 1L
        }

        /** No-arg constructor required by Hibernate 7's reflection-based PK instantiation. */
        @Suppress("unused")
        constructor() : this(UUID(0, 0), Instant.EPOCH)
    }
}
