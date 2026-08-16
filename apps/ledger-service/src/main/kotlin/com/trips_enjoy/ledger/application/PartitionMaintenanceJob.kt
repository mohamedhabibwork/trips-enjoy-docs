package com.trips_enjoy.ledger.application

import org.springframework.beans.factory.annotation.Value
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

/**
 * Thin Spring `@Scheduled` wrapper around the canonical PL/pgSQL
 * partition-maintenance functions. Primary trigger is pg_cron (see
 * V9__partition_functions.sql); this job is the fallback so a cluster
 * missing pg_cron still keeps the maintenance window open.
 *
 * Reference: docs/shared/PARTITION_FUNCTIONS.md §7 + §12
 *            docs/architecture/DATABASE_ARCHITECTURE.md §12
 *
 * Pre-2026-08-14 bugs fixed by this rewrite:
 *   * `pg_try_advisory_xact_lock` was called twice (lines 33 + 38 of the
 *     previous file), the first call's boolean was discarded — every
 *     invocation lost the lock race to itself. Now exactly one call.
 *   * The outbox event used the wrong namespace
 *     (`audit.partition.maintained.v1`). Per PARTITION_FUNCTIONS.md §10
 *     every service uses its own namespaced event; the
 *     `PartitionMaintenanceEventPublisher` emits
 *     `ledger.partition.maintained.v1` correctly.
 */
@Component
class PartitionMaintenanceJob(
    private val jdbc: JdbcTemplate,
    @Value("\${ledger-service.partition.horizon-months:12}") private val horizonMonths: Int,
) {
    @Scheduled(cron = "\${ledger-service.partition.cron:0 0 2 * * *}")
    @Transactional
    fun ensurePartitions() {
        val acquired = runCatching {
            jdbc.queryForObject(
                "SELECT pg_try_advisory_xact_lock(hashtext('ledger'), hashtext('partition'))",
                Boolean::class.java,
            ) ?: false
        }.getOrElse { return }
        if (!acquired) return

        parents.forEach { parent ->
            runCatching {
                jdbc.queryForObject(
                    "SELECT partman.ensure_partitions(?::REGCLASS, ?)",
                    String::class.java,
                    parent,
                    horizonMonths,
                )
            }
        }
    }

    private val parents = listOf("ledger.postings", "ledger.posting_entries")
}
