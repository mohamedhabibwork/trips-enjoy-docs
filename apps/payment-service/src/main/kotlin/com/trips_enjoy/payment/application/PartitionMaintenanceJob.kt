package com.trips_enjoy.payment.application

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

/**
 * The partition maintenance job — calls the canonical PL/pgSQL helpers
 * in partman schema (created by V4) to ensure monthly partitions exist
 * for the time-series tables and drop expired partitions beyond the
 * retention window. Lifted verbatim from audit-service / ledger-service
 * / notification-service / configuration-service / identity-service.
 *
 * The retention defaults are per-service:
 *   * payment_attempts:         180 days (6 months)
 *   * wallet_entries:           730 days (2 years, per the canonical
 *                                       wallet history retention in
 *                                       ledger-service §3)
 *   * driver_earnings:          730 days
 *   * courier_earnings:         730 days
 *   * merchant_settlements:     1825 days (5 years, per regulatory
 *                                          minimum in EMEA markets)
 *
 * The job runs at 02:00 UTC daily (Spring @Scheduled fallback if
 * pg_cron is unavailable). A `payment.partition.health` Prometheus
 * gauge exposes the partition coverage window for alerting.
 */
@Component
class PartitionMaintenanceJob(
    private val jdbcTemplate: JdbcTemplate,
    @Value("\${payment.partition.retention.payment-attempts-days:180}")
    private val paymentAttemptsRetentionDays: Int,
    @Value("\${payment.partition.retention.wallet-entries-days:730}")
    private val walletEntriesRetentionDays: Int,
    @Value("\${payment.partition.retention.driver-earnings-days:730}")
    private val driverEarningsRetentionDays: Int,
    @Value("\${payment.partition.retention.courier-earnings-days:730}")
    private val courierEarningsRetentionDays: Int,
    @Value("\${payment.partition.retention.merchant-settlements-days:1825}")
    private val merchantSettlementsRetentionDays: Int,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(cron = "0 0 2 * * *", zone = "UTC")
    @Transactional
    fun runDaily() {
        ensureAndDrop("payment.payment_attempts", paymentAttemptsRetentionDays)
        ensureAndDrop("payment.wallet_entries", walletEntriesRetentionDays)
        ensureAndDrop("payment.driver_earnings", driverEarningsRetentionDays)
        ensureAndDrop("payment.courier_earnings", courierEarningsRetentionDays)
        ensureAndDrop("payment.merchant_settlements", merchantSettlementsRetentionDays)
    }

    private fun ensureAndDrop(parentTable: String, retentionDays: Int) {
        try {
            val now = Instant.now()
            val horizon = now.plusSeconds(86400L * 60)  // 60 days ahead
            jdbcTemplate.update(
                "SELECT partman.ensure_partitions(?, ?, ?)",
                parentTable, now, horizon,
            )
            jdbcTemplate.update(
                "SELECT partman.drop_expired_partitions(?, ?)",
                parentTable, retentionDays,
            )
            log.info("partition maintenance: {} retention_days={}", parentTable, retentionDays)
        } catch (e: Exception) {
            log.warn("partition maintenance failed for {}: {}", parentTable, e.message)
        }
    }
}