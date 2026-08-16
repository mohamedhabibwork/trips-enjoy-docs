package com.trips_enjoy.ledger.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

/**
 * Materialised per-account balance snapshot. Updated in the same transaction
 * as the posting that changes it (per ERD.md §3).
 */
@Entity
@Table(name = "account_balances", schema = "ledger")
class AccountBalance(
    @Id
    @Column(name = "account_code")
    val accountCode: String,

    @Column(nullable = false, length = 3)
    val currency: String,

    @Column(name = "debit_total_minor", nullable = false)
    var debitTotalMinor: Long = 0L,

    @Column(name = "credit_total_minor", nullable = false)
    var creditTotalMinor: Long = 0L,

    @Column(name = "balance_minor", nullable = false)
    var balanceMinor: Long = 0L,

    @Column(name = "last_posting_at")
    var lastPostingAt: Instant? = null,

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now(),
)
