package com.trips_enjoy.ledger.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.IdClass
import jakarta.persistence.Table
import java.io.Serializable
import java.time.Instant
import java.util.UUID

/**
 * Posting entry — the debit / credit line. RANGE-partitioned by month on
 * `posted_at`. The primary key is `(id, posted_at)` per PostgreSQL's
 * requirement that partition keys participate in any UNIQUE constraint on a
 * partitioned table.
 */
@Entity
@Table(name = "posting_entries", schema = "ledger")
@IdClass(PostingEntry.Pk::class)
class PostingEntry(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    @Column(name = "posting_id", nullable = false)
    val postingId: UUID,

    @Column(name = "account_code", nullable = false)
    val accountCode: String,

    @Column(name = "account_version", nullable = false)
    val accountVersion: Int,

    /** One of `debit`, `credit`. */
    @Column(nullable = false)
    val side: String,

    /** Always positive. The double-entry invariant is checked at the application layer. */
    @Column(name = "amount_minor", nullable = false)
    val amountMinor: Long,

    @Column(nullable = false, length = 3)
    val currency: String,

    @Id
    @Column(name = "posted_at", nullable = false)
    val postedAt: Instant,

    @Column(name = "correlation_id", nullable = false)
    val correlationId: UUID,

    @Column(name = "created_at", nullable = false)
    val createdAt: Instant = Instant.now(),
) {
    data class Pk(val id: Long, val postedAt: Instant) : Serializable
}
