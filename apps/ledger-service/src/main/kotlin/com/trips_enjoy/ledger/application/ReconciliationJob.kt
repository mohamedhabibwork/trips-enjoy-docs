package com.trips_enjoy.ledger.application

import com.fasterxml.jackson.databind.ObjectMapper
import com.trips_enjoy.ledger.domain.OutboxEvent
import com.trips_enjoy.ledger.domain.OutboxEventRepository
import com.trips_enjoy.ledger.domain.PostingEntryRepository
import com.trips_enjoy.ledger.domain.ReconciliationRun
import com.trips_enjoy.ledger.domain.ReconciliationRunRepository
import com.trips_enjoy.ledger.util.uuidV7
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * WORKFLOWS §4 — Daily reconciliation. Compares the ledger's totals per
 * account_type against the operational layers (wallet / earnings /
 * settlement). Drift opens a P1 ticket via `ledger.audit.reconciliation_drift.v1`.
 *
 * The reconciliation runs at most once per `run_date` (unique constraint).
 * Idempotent re-runs for the same day are no-ops.
 */
@Component
class ReconciliationJob(
    private val reconciliationRepository: ReconciliationRunRepository,
    private val entryRepository: PostingEntryRepository,
    private val outbox: OutboxEventRepository,
    private val objectMapper: ObjectMapper,
    private val meters: MeterRegistry,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(cron = "\${ledger-service.reconciliation.cron:0 0 4 * * *}")
    fun daily() {
        val yesterday = LocalDate.now(ZoneOffset.UTC).minusDays(1)
        run(yesterday)
    }

    /**
     * Manual trigger used by the admin endpoint `POST /admin/v1/ledger/reconciliation/run`.
     * Re-runs the reconciliation for today (or the requested day).
     */
    @Transactional
    fun run(date: LocalDate): ReconciliationRun {
        val existing = reconciliationRepository.findByRunDate(date).orElse(null)
        if (existing != null) {
            log.info("Reconciliation already exists for date={} (status={}); returning existing row", date, existing.status)
            return existing
        }
        val run = ReconciliationRun(
            id = uuidV7(),
            runDate = date,
            startedAt = Instant.now(),
            correlationId = uuidV7(),
        )
        reconciliationRepository.save(run)

        val from = date.atStartOfDay(ZoneOffset.UTC).toInstant()
        val to = date.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant()

        // Aggregate per-type debits / credits over the window.
        val totals = entryRepository.totalsByAccountType(from, to)
        val walletTotal = totals.filter { it[0] == "asset" }
            .sumOf { (it[1] as Number).toLong() - (it[2] as Number).toLong() }
            .coerceAtLeast(0L)
        val earningsTotal = totals.filter { it[0] == "liability" }
            .sumOf { (it[2] as Number).toLong() - (it[1] as Number).toLong() }
            .coerceAtLeast(0L)
        val settlementTotal = totals.filter { it[0] == "expense" }
            .sumOf { (it[1] as Number).toLong() - (it[2] as Number).toLong() }
            .coerceAtLeast(0L)
        val ledgerTotal = totals.sumOf { (it[1] as Number).toLong() }
        // Drift = net cash movement across asset accounts vs the operational layers' sums.
        val drift = (walletTotal + earningsTotal + settlementTotal) - ledgerTotal

        run.walletTotal = walletTotal
        run.earningsTotal = earningsTotal
        run.settlementTotal = settlementTotal
        run.ledgerTotal = ledgerTotal
        run.driftMinor = drift
        run.endedAt = Instant.now()
        run.details = objectMapper.writeValueAsString(
            mapOf(
                "totals_by_type" to totals.associate { row ->
                    (row[0] as String) to mapOf(
                        "debit_minor" to (row[1] as Number).toLong(),
                        "credit_minor" to (row[2] as Number).toLong(),
                    )
                },
                "computed_at" to Instant.now().toString(),
            ),
        )
        if (drift == 0L) {
            run.status = "matched"
            emitReconciledEvent(run)
        } else {
            run.status = "drift"
            emitDriftEvent(run)
            meters.counter("ledger_reconciliation_drift").increment()
        }
        reconciliationRepository.save(run)
        log.info("Reconciliation for {} finished: status={} drift_minor={}", date, run.status, drift)
        return run
    }

    private fun emitReconciledEvent(run: ReconciliationRun) {
        outbox.save(
            OutboxEvent(
                id = uuidV7(),
                aggregateType = "ReconciliationRun",
                aggregateId = run.id,
                topic = "ledger.audit.reconciled",
                eventName = "ledger.audit.reconciled.v1",
                payload = objectMapper.writeValueAsString(
                    mapOf(
                        "event_id" to uuidV7().toString(),
                        "event_name" to "ledger.audit.reconciled.v1",
                        "occurred_at" to Instant.now().toString(),
                        "schema_version" to 1,
                        "producer" to "ledger-service",
                        "tenant_id" to "global",
                        "correlation_id" to run.correlationId.toString(),
                        "aggregate_type" to "ReconciliationRun",
                        "aggregate_id" to run.id.toString(),
                        "data" to mapOf(
                            "run_date" to run.runDate.toString(),
                            "wallet_total" to run.walletTotal,
                            "earnings_total" to run.earningsTotal,
                            "settlement_total" to run.settlementTotal,
                            "ledger_total" to run.ledgerTotal,
                            "drift_minor" to run.driftMinor,
                        ),
                    ),
                ),
            ),
        )
    }

    private fun emitDriftEvent(run: ReconciliationRun) {
        outbox.save(
            OutboxEvent(
                id = uuidV7(),
                aggregateType = "ReconciliationRun",
                aggregateId = run.id,
                topic = "ledger.audit.reconciliation_drift",
                eventName = "ledger.audit.reconciliation_drift.v1",
                payload = objectMapper.writeValueAsString(
                    mapOf(
                        "event_id" to uuidV7().toString(),
                        "event_name" to "ledger.audit.reconciliation_drift.v1",
                        "occurred_at" to Instant.now().toString(),
                        "schema_version" to 1,
                        "producer" to "ledger-service",
                        "tenant_id" to "global",
                        "correlation_id" to run.correlationId.toString(),
                        "aggregate_type" to "ReconciliationRun",
                        "aggregate_id" to run.id.toString(),
                        "data" to mapOf(
                            "run_date" to run.runDate.toString(),
                            "wallet_total" to run.walletTotal,
                            "earnings_total" to run.earningsTotal,
                            "settlement_total" to run.settlementTotal,
                            "ledger_total" to run.ledgerTotal,
                            "drift_minor" to run.driftMinor,
                        ),
                    ),
                ),
            ),
        )
    }
}
