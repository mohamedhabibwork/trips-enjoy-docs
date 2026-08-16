package com.trips_enjoy.ledger.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/**
 * Manual journal entry record. One entry → one or more postings (admin-only,
 * audit-logged). `audit_note` ≥ 10 chars (enforced by DB CHECK).
 */
@Entity
@Table(name = "journal_entries", schema = "ledger")
class JournalEntry(
    @Id
    val id: UUID,

    @Column(nullable = false)
    val description: String,

    @Column(name = "actor_id", nullable = false)
    val actorId: UUID,

    @Column(name = "audit_note", nullable = false)
    val auditNote: String,

    @Column(name = "correlation_id", nullable = false)
    val correlationId: UUID,

    @Column(name = "created_at", nullable = false)
    val createdAt: Instant = Instant.now(),
)
