package com.trips_enjoy.configuration.application

import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.springframework.jdbc.core.JdbcTemplate

/**
 * Unit test for the thin Spring wrapper. Reference:
 * docs/shared/PARTITION_FUNCTIONS.md §7.
 */
class PartitionMaintenanceJobTest {
    private val jdbc: JdbcTemplate = mock(JdbcTemplate::class.java)
    private val job = PartitionMaintenanceJob(jdbc, horizonMonths = 12)

    private val lockSql =
        "SELECT pg_try_advisory_xact_lock(hashtext('configuration'), hashtext('partition'))"

    @Test
    fun `advisory lock failure returns without calling function`() {
        `when`(jdbc.queryForObject(lockSql, Boolean::class.java)).thenReturn(false)

        job.ensurePartitions()

        verify(jdbc, never()).queryForObject(
            "SELECT partman.ensure_partitions(?::REGCLASS, ?)",
            String::class.java,
            "configuration.versions",
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
            "configuration.versions",
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
                "configuration.versions",
                12,
            ),
        ).thenReturn("{}")
        `when`(
            jdbc.queryForObject(
                "SELECT partman.ensure_partitions(?::REGCLASS, ?)",
                String::class.java,
                "configuration.audit_log",
                12,
            ),
        ).thenReturn("{}")

        job.ensurePartitions()

        verify(jdbc).queryForObject(
            "SELECT partman.ensure_partitions(?::REGCLASS, ?)",
            String::class.java,
            "configuration.versions",
            12,
        )
        verify(jdbc).queryForObject(
            "SELECT partman.ensure_partitions(?::REGCLASS, ?)",
            String::class.java,
            "configuration.audit_log",
            12,
        )
    }
}
