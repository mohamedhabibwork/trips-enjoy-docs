package com.trips_enjoy.ledger.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/**
 * Chart-of-accounts row. Versioned insert-only per docs/services/ledger-service/ERD.md §3.
 *
 * The current version is the one with `valid_to IS NULL`. Updates to an account
 * are a new row with `version++` and the previous row's `valid_to` set to
 * `valid_from` of the new row.
 */
@Entity
@Table(name = "accounts", schema = "ledger")
class Account(
    @Id
    val id: UUID,

    @Column(nullable = false)
    val code: String,

    @Column(nullable = false)
    val name: String,

    /** One of `asset`, `liability`, `equity`, `revenue`, `expense`. */
    @Column(nullable = false)
    val type: String,

    @Column(nullable = false, length = 3)
    val currency: String,

    @Column(name = "parent_code")
    val parentCode: String? = null,

    @Column(nullable = false)
    val version: Int,

    @Column(name = "valid_from", nullable = false)
    val validFrom: Instant,

    @Column(name = "valid_to")
    val validTo: Instant? = null,

    @Column(name = "created_at", nullable = false)
    val createdAt: Instant = Instant.now(),

    @Column(name = "created_by", nullable = false)
    val createdBy: UUID,
) {
    val isCurrent: Boolean get() = validTo == null
}
