package com.trips_enjoy.trip.application

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

@Component
class PartitionMaintenanceJob(
    private val jdbcTemplate: JdbcTemplate,
    @Value("\${trip.partition.retention.location-point-days:30}")
    private val locationPointRetentionDays: Int,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(cron = "0 0 2 * * *", zone = "UTC")
    @Transactional
    fun runDaily() {
        ensureAndDrop("trip.trip_location_point", locationPointRetentionDays)
    }

    private fun ensureAndDrop(parentTable: String, retentionDays: Int) {
        try {
            val now = Instant.now()
            val horizon = now.plusSeconds(86400L * 60)
            jdbcTemplate.update("SELECT partman.ensure_partitions(?, ?, ?)", parentTable, now, horizon)
            jdbcTemplate.update("SELECT partman.drop_expired_partitions(?, ?)", parentTable, retentionDays)
            log.info("partition maintenance: {} retention_days={}", parentTable, retentionDays)
        } catch (e: Exception) {
            log.warn("partition maintenance failed for {}: {}", parentTable, e.message)
        }
    }
}