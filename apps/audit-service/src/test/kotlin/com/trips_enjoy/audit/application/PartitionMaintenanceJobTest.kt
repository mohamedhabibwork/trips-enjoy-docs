package com.trips_enjoy.audit.application

import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.springframework.jdbc.core.JdbcTemplate

/**
 * Unit test for the thin Spring wrapper. Verifies the advisory-lock failure
 * path skips without throwing and that the function is called for each
 * declared parent with the configured horizon. Reference:
 * docs/shared/PARTITION_FUNCTIONS.md §7.
 */
class PartitionMaintenanceJobTest {
    private val jdbc: JdbcTemplate = mock(JdbcTemplate::class.java)
    private val job = PartitionMaintenanceJob(jdbc, horizonMonths = 12)

    private val lockSql =
        "SELECT pg_try_advisory_xact_lock(hashtext('audit'), hashtext('partition'))"

    @Test
    fun `advisory lock failure returns without calling function`() {
        `when`(jdbc.queryForObject(lockSql, Boolean::class.java)).thenReturn(false)

        job.ensurePartitions()

        verify(jdbc, never()).queryForObject(
            "SELECT partman.ensure_partitions(?::REGCLASS, ?)",
            String::class.java,
            "audit.events",
            12,
        )
        verify(jdbc, never()).queryForObject(
            "SELECT partman.ensure_partitions(?::REGCLASS, ?)",
            String::class.java,
            "audit.read_log",
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
            "audit.events",
            12,
        )
        verify(jdbc, never()).queryForObject(
            "SELECT partman.ensure_partitions(?::REGCLASS, ?)",
            String::class.java,
            "audit.read_log",
            12,
        )
    }

    @Test
    fun `acquired lock calls ensure_partitions for every parent`() {
        `when`(jdbc.queryForObject(lockSql, Boolean::class.java)).thenReturn(true)
        `when`(
            jdbc.queryForObject(
                "SELECT partman.ensure_partitions(?::REGCLASS, ?)",
                String::class.java,
                "audit.events",
                12,
            ),
        ).thenReturn("{}")
        `when`(
            jdbc.queryForObject(
                "SELECT partman.ensure_partitions(?::REGCLASS, ?)",
                String::class.java,
                "audit.read_log",
                12,
            ),
        ).thenReturn("{}")

        job.ensurePartitions()

        verify(jdbc).queryForObject(
            "SELECT partman.ensure_partitions(?::REGCLASS, ?)",
            String::class.java,
            "audit.events",
            12,
        )
        verify(jdbc).queryForObject(
            "SELECT partman.ensure_partitions(?::REGCLASS, ?)",
            String::class.java,
            "audit.read_log",
            12,
        )
    }
}
