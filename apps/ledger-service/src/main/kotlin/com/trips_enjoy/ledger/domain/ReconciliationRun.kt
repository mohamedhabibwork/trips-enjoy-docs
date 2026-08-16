package com.trips_enjoy.ledger.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * Daily reconciliation summary. One row per `run_date`.
 */
@Entity
@Table(name = "reconciliation_runs", schema = "ledger")
class ReconciliationRun(
    @Id
    val id: UUID,

    @Column(name = "run_date", nullable = false)
    val runDate: LocalDate,

    @Column(name = "started_at", nullable = false)
    val startedAt: Instant = Instant.now(),

    @Column(name = "ended_at")
    var endedAt: Instant? = null,

    @Column(name = "wallet_total", nullable = false)
    var walletTotal: Long = 0L,

    @Column(name = "earnings_total", nullable = false)
    var earningsTotal: Long = 0L,

    @Column(name = "settlement_total", nullable = false)
    var settlementTotal: Long = 0L,

    @Column(name = "ledger_total", nullable = false)
    var ledgerTotal: Long = 0L,

    @Column(name = "drift_minor", nullable = false)
    var driftMinor: Long = 0L,

    /** One of `running`, `matched`, `drift`, `error`. */
    @Column(nullable = false)
    var status: String = "running",

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    var details: String? = null,

    @Column(name = "correlation_id", nullable = false)
    val correlationId: UUID,

    @Column(name = "created_at", nullable = false)
    val createdAt: Instant = Instant.now(),
)
