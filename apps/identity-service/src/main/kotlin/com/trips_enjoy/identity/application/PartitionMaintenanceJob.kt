package com.trips_enjoy.identity.application

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * Thin Spring `@Scheduled` wrapper around the canonical PL/pgSQL
 * partition-maintenance functions. Primary trigger is pg_cron (see
 * V6__partition_functions.sql); this job is the fallback so a cluster
 * missing pg_cron still keeps the maintenance window open.
 *
 * Reference: docs/shared/PARTITION_FUNCTIONS.md §7 + §12
 *            docs/architecture/DATABASE_ARCHITECTURE.md §12
 */
@Component
class PartitionMaintenanceJob(private val jdbc: JdbcTemplate) {
    @Scheduled(cron = "0 0 2 * * *")
    fun ensurePartitions() {
        val acquired = runCatching {
            jdbc.queryForObject(
                "SELECT pg_try_advisory_xact_lock(hashtext('identity'), hashtext('partition'))",
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
                    12,
                )
            }
        }
    }

    private val parents = listOf(
        "identity.identity_claim_history",
        "identity.role_assignment_history",
    )
}
