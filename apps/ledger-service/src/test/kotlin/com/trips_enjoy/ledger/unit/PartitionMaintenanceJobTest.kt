package com.trips_enjoy.ledger.unit

import com.trips_enjoy.ledger.application.PartitionMaintenanceJob
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.springframework.jdbc.core.JdbcTemplate

/**
 * Unit test for the thin Spring wrapper. Reference:
 * docs/shared/PARTITION_FUNCTIONS.md §7.
 *
 * Covers the pre-2026-08-14 dead-double-lock regression: the previous
 * implementation called `pg_try_advisory_xact_lock` twice (the first
 * boolean was discarded). The slim wrapper must call it exactly once
 * and gate on the boolean.
 */
class PartitionMaintenanceJobTest {
    private val jdbc: JdbcTemplate = mock(JdbcTemplate::class.java)
    private val job = PartitionMaintenanceJob(jdbc, horizonMonths = 12)

    private val lockSql =
        "SELECT pg_try_advisory_xact_lock(hashtext('ledger'), hashtext('partition'))"

    @Test
    fun `advisory lock failure returns without calling function`() {
        `when`(jdbc.queryForObject(lockSql, Boolean::class.java)).thenReturn(false)

        job.ensurePartitions()

        verify(jdbc, never()).queryForObject(
            "SELECT partman.ensure_partitions(?::REGCLASS, ?)",
            String::class.java,
            "ledger.postings",
            12,
        )
    }

    @Test
    fun `advisory lock null returns without calling function`() {
        `when`(jdbc.queryForObject(lockSql, Boolean::class.java)).thenReturn(null)

        job.ensurePartitions()

        verify(jdbc, never()).queryForObject(
            "SELECT partman.ensure_partitions(?::REGCLASS, ?)",
            String::class.java,
            "ledger.postings",
            12,
        )
    }

    @Test
    fun `acquired lock calls ensure_partitions for every parent exactly once`() {
        `when`(jdbc.queryForObject(lockSql, Boolean::class.java)).thenReturn(true)
        `when`(
            jdbc.queryForObject(
                "SELECT partman.ensure_partitions(?::REGCLASS, ?)",
                String::class.java,
                "ledger.postings",
                12,
            ),
        ).thenReturn("{}")
        `when`(
            jdbc.queryForObject(
                "SELECT partman.ensure_partitions(?::REGCLASS, ?)",
                String::class.java,
                "ledger.posting_entries",
                12,
            ),
        ).thenReturn("{}")

        job.ensurePartitions()

        verify(jdbc).queryForObject(
            "SELECT partman.ensure_partitions(?::REGCLASS, ?)",
            String::class.java,
            "ledger.postings",
            12,
        )
        verify(jdbc).queryForObject(
            "SELECT partman.ensure_partitions(?::REGCLASS, ?)",
            String::class.java,
            "ledger.posting_entries",
            12,
        )
    }
}
