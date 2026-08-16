package com.trips_enjoy.ledger.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.IdClass
import jakarta.persistence.Table
import java.io.Serializable
import java.time.Instant
import java.util.UUID

/**
 * Append-only posting row. Per docs/services/ledger-service/ERD.md §3, the
 * primary key is composite `(id, posted_at)` because the parent table is
 * range-partitioned by month on `posted_at` — PostgreSQL requires the
 * partition key to participate in any UNIQUE constraint on a partitioned
 * table.
 */
@Entity
@Table(name = "postings", schema = "ledger")
@IdClass(Posting.Pk::class)
class Posting(
    @Id
    val id: UUID,

    @Id
    @Column(name = "posted_at", nullable = false)
    val postedAt: Instant,

    @Column(nullable = false)
    val description: String,

    @Column(name = "source_event_id", nullable = false)
    val sourceEventId: UUID,

    @Column(name = "source_event_name", nullable = false)
    val sourceEventName: String,

    @Column(name = "correlation_id", nullable = false)
    val correlationId: UUID,

    @Column(name = "tenant_id", nullable = false)
    val tenantId: String = "global",

    @Column(name = "idempotency_key", nullable = false)
    val idempotencyKey: String,

    @Column(name = "actor_type", nullable = false)
    val actorType: String,

    @Column(name = "actor_id")
    val actorId: UUID? = null,

    @Column(name = "audit_note")
    val auditNote: String? = null,

    @Column(name = "created_at", nullable = false)
    val createdAt: Instant = Instant.now(),
) {
    /** Composite PK; required by JPA because the parent table is partitioned. */
    data class Pk(val id: UUID, val postedAt: Instant) : Serializable
}
